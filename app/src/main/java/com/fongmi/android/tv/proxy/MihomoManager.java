package com.fongmi.android.tv.proxy;

import android.text.TextUtils;
import android.net.Uri;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Path;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class MihomoManager {

    private static final String TAG = MihomoManager.class.getSimpleName();
    private static final String BINARY = "libmihomo.so";
    private static final String CONFIG = "config.yaml";
    private static final int MIXED_PORT = 18890;
    private static final int CONTROLLER_PORT = 18891;
    private static final int MAX_LOG_LINES = 200;

    private Process process;
    private final StringBuilder logBuffer = new StringBuilder();
    private String lastError = "";

    private static class Loader {
        static volatile MihomoManager INSTANCE = new MihomoManager();
    }

    public static MihomoManager get() {
        return Loader.INSTANCE;
    }

    public static String getProxyUrl() {
        return getProxyUrl("Mihomo");
    }

    public static int getMixedPort() {
        return MIXED_PORT;
    }

    public static String getProxyUrl(String name) {
        return "http://127.0.0.1:" + MIXED_PORT + "#" + Uri.encode(TextUtils.isEmpty(name) ? "Mihomo" : name);
    }

    public String getLastError() {
        return lastError;
    }

    public String getLog() {
        return logBuffer.toString();
    }

    public synchronized boolean start(String config) {
        return start(config, "");
    }

    public synchronized boolean start(String config, String selected) {
        if (TextUtils.isEmpty(config)) {
            lastError = "配置为空";
            return false;
        }
        if (isRunning() && canConnect()) return true;
        stop();
        lastError = "";
        logBuffer.setLength(0);
        try {
            Thread.sleep(300);
            File dir = Path.files("mihomo");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, CONFIG);
            String fixedConfig = fixConfig(config, selected);
            Path.write(file, fixedConfig.getBytes(StandardCharsets.UTF_8));
            appendLog("配置文件: " + file.getAbsolutePath());
            appendLog("配置内容:\n" + fixedConfig);

            File binary = new File(App.get().getApplicationInfo().nativeLibraryDir, BINARY);
            if (!binary.exists()) {
                lastError = "二进制文件不存在: " + binary.getAbsolutePath() + "\nnativeLibraryDir: " + App.get().getApplicationInfo().nativeLibraryDir;
                appendLog(lastError);
                return false;
            }
            binary.setExecutable(true);
            appendLog("启动: " + binary.getAbsolutePath() + " -d " + dir.getAbsolutePath() + " -f " + file.getAbsolutePath());

            process = new ProcessBuilder(binary.getAbsolutePath(), "-d", dir.getAbsolutePath(), "-f", file.getAbsolutePath()).redirectErrorStream(true).start();
            drain(process);
            return waitReady();
        } catch (Exception e) {
            lastError = "启动异常: " + e.getMessage();
            appendLog(lastError);
            Log.e(TAG, "start failed", e);
            stop();
            return false;
        }
    }

    public synchronized void stop() {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                appendLog("进程被强制终止");
            }
        } catch (InterruptedException ignored) {
        }
        process = null;
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }

    private boolean waitReady() throws InterruptedException {
        for (int i = 0; i < 80; i++) {
            if (!isRunning()) {
                lastError = "Mihomo进程启动后立即退出\n可能原因: 配置错误/二进制不兼容/权限不足\n\n日志:\n" + logBuffer.toString();
                appendLog(lastError);
                return false;
            }
            if (canConnect()) {
                appendLog("Mihomo启动成功，耗时" + i * 100 + "ms");
                return true;
            }
            Thread.sleep(100);
        }
        lastError = "Mihomo在8秒内未就绪\n进程状态: " + (isRunning() ? "运行中" : "已退出") + "\n\n日志:\n" + logBuffer.toString();
        appendLog(lastError);
        return isRunning();
    }

    private boolean canConnect() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", MIXED_PORT), 100);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void appendLog(String line) {
        Log.d(TAG, line);
        if (logBuffer.length() > 0) logBuffer.append("\n");
        logBuffer.append(line);
        int lineCount = logBuffer.toString().split("\n").length;
        if (lineCount > MAX_LOG_LINES) {
            String[] lines = logBuffer.toString().split("\n", lineCount - MAX_LOG_LINES + 1);
            logBuffer.setLength(0);
            for (int i = 1; i < lines.length; i++) {
                logBuffer.append(lines[i]);
                if (i < lines.length - 1) logBuffer.append("\n");
            }
        }
    }

    private void drain(Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    appendLog(line);
                }
            } catch (Exception ignored) {
            }
        }, "mihomo-log");
        thread.setDaemon(true);
        thread.start();
    }

    private String fixConfig(String config, String selected) {
        String text = config.replace("\r\n", "\n").replace("\r", "\n");
        if (!TextUtils.isEmpty(selected)) text = buildSelectedConfig(text, selected);
        text = fixServerName(text);
        text = fixVlessFlow(text);
        text = putTopLevel(text, "mixed-port", String.valueOf(MIXED_PORT));
        text = putTopLevel(text, "allow-lan", "false");
        text = putTopLevel(text, "bind-address", "'127.0.0.1'");
        text = putTopLevel(text, "external-controller", "'127.0.0.1:" + CONTROLLER_PORT + "'");
        text = putTopLevel(text, "log-level", "info");
        text = putTopLevel(text, "find-process-mode", "off");
        text = ensureDns(text);
        return text;
    }

    private String fixServerName(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean inProxies = false;
        String currentType = "";
        for (String line : lines) {
            String trimmed = line.trim();
            boolean isTopLevel = !TextUtils.isEmpty(line) && !Character.isWhitespace(line.charAt(0)) && line.contains(":");
            if (isTopLevel) {
                inProxies = trimmed.startsWith("proxies:");
                currentType = "";
                result.append(line).append("\n");
                continue;
            }
            if (inProxies) {
                if (trimmed.startsWith("- ")) {
                    currentType = "";
                } else if (trimmed.startsWith("type:")) {
                    currentType = trimmed.substring("type:".length()).trim();
                } else if (trimmed.startsWith("server-name:")) {
                    boolean useSni = "trojan".equals(currentType) || "hysteria2".equals(currentType) || "hysteria".equals(currentType) || "tuic".equals(currentType) || "snell".equals(currentType);
                    String value = trimmed.substring("server-name:".length()).trim();
                    result.append(line.substring(0, line.length() - trimmed.length())).append(useSni ? "sni: " : "servername: ").append(value).append("\n");
                    continue;
                }
            }
            result.append(line).append("\n");
        }
        return result.toString();
    }

    private String fixVlessFlow(String text) {
        String[] lines = text.split("\n", -1);
        StringBuilder result = new StringBuilder();
        boolean inProxies = false;
        java.util.List<String> entry = new java.util.ArrayList<>();
        boolean isVless = false;
        boolean hasReality = false;
        boolean hasFlow = false;

        for (String line : lines) {
            String trimmed = line.trim();
            boolean isTopLevel = !TextUtils.isEmpty(line) && !Character.isWhitespace(line.charAt(0)) && line.contains(":");
            boolean isNewEntry = inProxies && trimmed.startsWith("- ");

            if (isTopLevel || isNewEntry) {
                if (!entry.isEmpty()) {
                    if (isVless && hasReality && !hasFlow) {
                        String indent = "    ";
                        for (String l : entry) {
                            if (l.trim().startsWith("type:")) {
                                indent = l.substring(0, l.length() - l.trim().length());
                                break;
                            }
                        }
                        for (String l : entry) {
                            result.append(l).append("\n");
                            if (l.trim().startsWith("uuid:")) {
                                result.append(indent).append("flow: xtls-rprx-vision\n");
                            }
                        }
                    } else {
                        for (String l : entry) result.append(l).append("\n");
                    }
                    entry.clear();
                }
                isVless = false;
                hasReality = false;
                hasFlow = false;
            }

            if (isTopLevel) {
                inProxies = trimmed.startsWith("proxies:");
                result.append(line).append("\n");
            } else if (inProxies) {
                if (trimmed.startsWith("type:") && trimmed.contains("vless")) isVless = true;
                if (trimmed.startsWith("reality-opts:")) hasReality = true;
                if (trimmed.startsWith("flow:")) hasFlow = true;
                entry.add(line);
            } else {
                result.append(line).append("\n");
            }
        }

        if (!entry.isEmpty()) {
            if (isVless && hasReality && !hasFlow) {
                String indent = "    ";
                for (String l : entry) {
                    if (l.trim().startsWith("type:")) {
                        indent = l.substring(0, l.length() - l.trim().length());
                        break;
                    }
                }
                for (String l : entry) {
                    result.append(l).append("\n");
                    if (l.trim().startsWith("uuid:")) {
                        result.append(indent).append("flow: xtls-rprx-vision\n");
                    }
                }
            } else {
                for (String l : entry) result.append(l).append("\n");
            }
        }

        return result.toString();
    }

    private String ensureDns(String text) {
        if (text.contains("\ndns:") || text.startsWith("dns:")) return text;
        String dns = "dns:\n" +
                "  enable: true\n" +
                "  listen: 0.0.0.0:1053\n" +
                "  enhanced-mode: fake-ip\n" +
                "  fake-ip-range: 198.18.0.1/16\n" +
                "  nameserver:\n" +
                "    - 223.5.5.5\n" +
                "    - 119.29.29.29\n" +
                "    - https://dns.alidns.com/dns-query\n" +
                "  fallback:\n" +
                "    - https://1.1.1.1/dns-query\n" +
                "    - https://8.8.8.8/dns-query\n" +
                "  fallback-filter:\n" +
                "    geoip: false\n" +
                "    ipcidr:\n" +
                "      - 240.0.0.0/4\n";
        return dns + text;
    }

    private String buildSelectedConfig(String text, String selected) {
        String proxies = extractBlock(text, "proxies");
        if (TextUtils.isEmpty(proxies)) return text;
        return "mode: rule\n" +
                "ipv6: false\n" +
                "proxies:\n" +
                proxies +
                "proxy-groups:\n" +
                "  - name: XYS_PROXY\n" +
                "    type: select\n" +
                "    proxies:\n" +
                "      - " + quote(selected) + "\n" +
                "rules:\n" +
                "  - MATCH,XYS_PROXY\n";
    }

    private String extractBlock(String text, String key) {
        String[] lines = text.split("\n", -1);
        StringBuilder builder = new StringBuilder();
        boolean found = false;
        for (String line : lines) {
            if (!found) {
                found = line.matches("^" + key + "\\s*:.*");
                continue;
            }
            if (!TextUtils.isEmpty(line) && !Character.isWhitespace(line.charAt(0)) && line.contains(":")) break;
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String putTopLevel(String text, String key, String value) {
        String[] lines = text.split("\n", -1);
        boolean found = false;
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (line.matches("^" + key + "\\s*:.*")) {
                builder.append(key).append(": ").append(value).append('\n');
                found = true;
            } else {
                builder.append(line).append('\n');
            }
        }
        if (!found) builder.insert(0, key + ": " + value + "\n");
        return builder.toString();
    }
}

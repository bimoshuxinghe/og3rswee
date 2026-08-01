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

public class MihomoManager {

    private static final String TAG = MihomoManager.class.getSimpleName();
    private static final String BINARY = "libmihomo.so";
    private static final String CONFIG = "config.yaml";
    private static final int MIXED_PORT = 18890;
    private static final int CONTROLLER_PORT = 18891;

    private Process process;

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

    public synchronized boolean start(String config) {
        return start(config, "");
    }

    public synchronized boolean start(String config, String selected) {
        if (TextUtils.isEmpty(config)) return false;
        stop();
        try {
            Thread.sleep(300);
            File dir = Path.files("mihomo");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, CONFIG);
            String fixedConfig = fixConfig(config, selected);
            Path.write(file, fixedConfig.getBytes(StandardCharsets.UTF_8));
            Log.d(TAG, "Config written to " + file.getAbsolutePath());
            Log.d(TAG, "Config content:\n" + fixedConfig);
            File binary = new File(App.get().getApplicationInfo().nativeLibraryDir, BINARY);
            if (!binary.exists()) {
                Log.e(TAG, "Binary not found: " + binary.getAbsolutePath());
                Log.e(TAG, "nativeLibraryDir: " + App.get().getApplicationInfo().nativeLibraryDir);
                return false;
            }
            binary.setExecutable(true);
            Log.d(TAG, "Starting mihomo: " + binary.getAbsolutePath() + " -d " + dir.getAbsolutePath() + " -f " + file.getAbsolutePath());
            process = new ProcessBuilder(binary.getAbsolutePath(), "-d", dir.getAbsolutePath(), "-f", file.getAbsolutePath()).redirectErrorStream(true).start();
            drain(process);
            return waitReady();
        } catch (Exception e) {
            stop();
            Log.e(TAG, "start failed", e);
            return false;
        }
    }

    public synchronized void stop() {
        if (process == null) return;
        process.destroy();
        try {
            if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
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
                Log.e(TAG, "Mihomo process died before becoming ready");
                return false;
            }
            if (canConnect()) {
                Log.d(TAG, "Mihomo ready after " + i * 100 + "ms");
                return true;
            }
            Thread.sleep(100);
        }
        Log.e(TAG, "Mihomo failed to become ready within 8 seconds");
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

    private void drain(Process process) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.d(TAG, line);
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
        text = putTopLevel(text, "mixed-port", String.valueOf(MIXED_PORT));
        text = putTopLevel(text, "allow-lan", "false");
        text = putTopLevel(text, "bind-address", "'127.0.0.1'");
        text = putTopLevel(text, "external-controller", "'127.0.0.1:" + CONTROLLER_PORT + "'");
        text = putTopLevel(text, "log-level", "info");
        text = putTopLevel(text, "find-process-mode", "off");
        text = putTopLevel(text, "global-client-fingerprint", "chrome");
        text = ensureDns(text);
        return text;
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

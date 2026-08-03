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
    private String lastConfig = "";
    private String lastSelected = "";

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

    public static int getControllerPort() {
        return CONTROLLER_PORT;
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

    /**
     * 完全对齐反编译版 b.d() — 启动mihomo
     * 1. 检查配置非空
     * 2. 停止已有进程
     * 3. 写入配置文件
     * 4. 启动进程
     * 5. 等待就绪
     */
    public synchronized boolean start(String config, String selected) {
        if (TextUtils.isEmpty(config)) {
            lastError = "配置为空";
            return false;
        }
        stop();
        lastError = "";
        logBuffer.setLength(0);
        try {
            File dir = Path.files("mihomo");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, CONFIG);
            String fixedConfig = fixConfig(config, selected);
            Path.write(file, fixedConfig.getBytes(StandardCharsets.UTF_8));
            appendLog("配置文件: " + file.getAbsolutePath());
            appendLog("选中节点: " + (TextUtils.isEmpty(selected) ? "(无)" : selected));

            File binary = new File(App.get().getApplicationInfo().nativeLibraryDir, BINARY);
            if (!binary.exists()) {
                lastError = "二进制文件不存在: " + binary.getAbsolutePath();
                appendLog(lastError);
                return false;
            }
            binary.setExecutable(true);
            appendLog("启动: " + binary.getAbsolutePath() + " -d " + dir.getAbsolutePath() + " -f " + file.getAbsolutePath());

            process = new ProcessBuilder(binary.getAbsolutePath(), "-d", dir.getAbsolutePath(), "-f", file.getAbsolutePath()).redirectErrorStream(true).start();
            drain(process);
            boolean ok = waitReady();
            if (ok) {
                lastConfig = config;
                lastSelected = selected == null ? "" : selected;
            }
            return ok;
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

    /**
     * 完全对齐反编译版 b.f() — 等待mihomo就绪
     * 50次尝试, 每次100ms, 共5秒
     */
    private boolean waitReady() throws InterruptedException {
        for (int i = 0; i < 50; i++) {
            if (!isRunning()) {
                lastError = "Mihomo进程启动后退出\n日志:\n" + logBuffer.toString();
                appendLog(lastError);
                return false;
            }
            if (canConnect()) {
                appendLog("Mihomo启动成功，耗时" + i * 100 + "ms");
                return true;
            }
            Thread.sleep(100);
        }
        lastError = "Mihomo在5秒内未就绪\n进程状态: " + (isRunning() ? "运行中" : "已退出") + "\n日志:\n" + logBuffer.toString();
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
        text = putTopLevel(text, "mixed-port", String.valueOf(MIXED_PORT));
        text = putTopLevel(text, "allow-lan", "false");
        text = putTopLevel(text, "bind-address", "'127.0.0.1'");
        text = putTopLevel(text, "external-controller", "'127.0.0.1:" + CONTROLLER_PORT + "'");
        text = putTopLevel(text, "log-level", "warning");
        return text;
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
                "  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve\n" +
                "  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve\n" +
                "  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve\n" +
                "  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve\n" +
                "  - IP-CIDR,100.64.0.0/10,DIRECT,no-resolve\n" +
                "  - IP-CIDR,169.254.0.0/16,DIRECT,no-resolve\n" +
                "  - IP-CIDR,224.0.0.0/4,DIRECT,no-resolve\n" +
                "  - DOMAIN-SUFFIX,cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,com.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,iqiyipic.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,iqiyi.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,iq.com,DIRECT\n" +
                "  - DOMAIN-KEYWORD,iqiyi,DIRECT\n" +
                "  - DOMAIN-SUFFIX,qq.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,qpic.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,gtimg.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,tencent.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,myqcloud.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,tencdns.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,cdntip.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,tencent-cloud.net,DIRECT\n" +
                "  - DOMAIN-SUFFIX,weishi.qq.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,txkt.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,youku.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bilibili.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,hdslb.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bilivideo.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bilivideo.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,mgtv.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,hunantv.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,tvmao.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,sohu.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,sohucs.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,letv.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,le.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,pptv.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,miguvideo.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,migu.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,ifeng.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,cctv.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,cntv.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,yangshipin.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,ipanda.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,kuaishou.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,gifshow.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,yximgs.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,douyin.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,douyincdn.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,douyinpic.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,douyinstatic.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bytecdn.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,byteimg.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,pstatp.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,snssdk.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,ixigua.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bytedance.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bytednsdoc.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,taobao.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,alicdn.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,aliyuncs.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,aliyun.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,mmstat.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,tmall.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,alibaba.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bdstatic.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,baidu.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bdimg.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,360kan.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,haokan.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,360.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,360.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,ksyuncdn.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,ksyun.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,qiniudn.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,qiniucdn.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,upyun.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,upaiyun.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,xiaomi.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,mi.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,huawei.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,wdstm.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,1234567.com.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,jstv.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,189.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,weibo.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,sina.com.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,sinaimg.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,xhscdn.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,xiaohongshu.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,zhihu.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,jd.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,jdcloud.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,pinduoduo.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,yangkeduo.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,utm.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,pages.dev,DIRECT\n" +
                "  - GEOIP,CN,DIRECT\n" +
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

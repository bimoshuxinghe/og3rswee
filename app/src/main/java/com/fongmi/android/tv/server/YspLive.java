package com.fongmi.android.tv.server;

import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.chaquo.YspStarter;
import com.github.catvod.utils.Path;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 内置央视频直播代理（ysp-live v9.5，Python 版）。
 *
 * <p>把 APK assets 中的 ysp-live-v9.5.py 释放到应用缓存目录，经 chaquo
 * 内嵌 Python 在守护线程中运行：监听 127.0.0.1:8767，提供
 * {@code /all.m3u} 全频道订阅（64 频道 + 7 天时移回看）。
 *
 * <p>直播源配置（LiveConfig.defaultConfig）指向
 * {@link #URL}；播放时频道地址形如 {@code http://127.0.0.1:8767/<频道>.m3u8}，
 * 由本代理实时解析央视频设备协议后吐流。
 *
 * <p>启动不阻塞调用方：{@link #start()} 只做资源准备并拉起后台线程，
 * chaquo/Python 初始化在后台完成；{@link #waitReady(long)} 供加载配置前
 * 轮询健康状态。
 */
public class YspLive {

    private static final String TAG = "YspLive";

    /** assets 内的脚本路径 */
    private static final String ASSET = "ysp/ysp-live-v9.5.py";
    /** 释放到缓存后的文件名（与 assets 内一致，含版本号，升级自然替换） */
    private static final String FILE = "ysp-live-v9.5.py";
    /** 全频道订阅地址（与 py 默认端口一致） */
    public static final String URL = "http://127.0.0.1:8767/all.m3u";
    private static final int PORT = 8767;

    private volatile int state; // 0=未启动 1=启动中 2=已启动

    private static class Loader {
        static volatile YspLive INSTANCE = new YspLive();
    }

    public static YspLive get() {
        return Loader.INSTANCE;
    }

    /** 启动内置直播代理（幂等；立即返回，实际初始化在后台线程完成） */
    public synchronized void start() {
        if (state != 0) return;
        state = 1;
        Thread thread = new Thread(this::boot, "ysp-live-boot");
        thread.setDaemon(true);
        thread.start();
    }

    private void boot() {
        try {
            File dir = Path.cache("ysp");
            File script = new File(dir, FILE);
            extract(script);
            File data = Path.cache("ysp-data");
            if (!data.exists()) data.mkdirs();
            String result = YspStarter.start(dir.getAbsolutePath(), PORT, "127.0.0.1", data.getAbsolutePath());
            if (!"ok".equals(result)) throw new Exception(result);
            state = 2;
            Log.i(TAG, "ysp-live 已启动: " + URL);
        } catch (Throwable e) {
            state = 0;
            Log.e(TAG, "ysp-live 启动失败", e);
        }
    }

    /** 把 assets 中的脚本释放到可写缓存（ysp 需要同目录写状态文件）。
     *  带版本校验：assets 内脚本变化（同名新版本）时重新释放 */
    private void extract(File script) throws Exception {
        long assetLen;
        try (InputStream in = App.get().getAssets().open(ASSET)) {
            assetLen = in.available();
        }
        if (script.exists() && script.length() == assetLen) return;
        File parent = script.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (InputStream in = App.get().getAssets().open(ASSET); OutputStream out = new java.io.FileOutputStream(script)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
        }
    }

    /** 轮询等待代理就绪；超时返回 false（加载会失败并记日志，不影响 App 其他功能） */
    public boolean waitReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (healthy()) return true;
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return healthy();
    }

    private boolean healthy() {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/health").openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(800);
            return conn.getResponseCode() == 200;
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}

package com.fongmi.android.tv.api.loader;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Rust 二进制站点 Spider（独立可执行 .bin 模式）。
 *
 * 站点配置形如：
 * {"key":"4KVM","name":"4K • Rust二进制","type":3,"api":"./rust/spider_rust.bin",
 *  "ext":{"host":"https://www.4kvms.org/"}}
 *
 * 每次请求以子进程调用二进制：
 *   <bin> <action> <ext_json> [args...]
 * stdout 整体读取为结果 JSON（TVBox 协议格式），日志走 stderr。
 * 协议详见 rust-spider/src/main.rs 头部注释。
 */
public class BinSpider extends Spider {

    private static final long TIMEOUT_SECONDS = 60;

    private String binPath;
    private String extJson = "-";

    public BinSpider(String binPath) {
        this.binPath = binPath;
    }

    @Override
    public void init(Context context) throws Exception {
        init(context, null);
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        extJson = TextUtils.isEmpty(extend) ? "-" : extend;
    }

    private String run(String action, String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(binPath);
            cmd.add(action);
            cmd.add(extJson);
            for (String arg : args) cmd.add(arg == null ? "" : arg);

            Process process = new ProcessBuilder(cmd).start();
            StringBuilder stdout = new StringBuilder();
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) != -1) stdout.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
            }
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "";
            }
            return process.exitValue() == 0 ? stdout.toString().trim() : "";
        } catch (Throwable t) {
            t.printStackTrace();
            return "";
        }
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        return run("home");
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        return run("category", tid, pg);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "";
        return run("detail", ids.get(0));
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return run("search", key, pg);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        return run("play", id);
    }
}

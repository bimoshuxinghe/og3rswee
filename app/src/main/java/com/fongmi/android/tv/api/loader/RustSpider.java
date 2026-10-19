package com.fongmi.android.tv.api.loader;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

/**
 * Rust 爬虫 Spider 适配层（通用版）。
 * 站点配置 api 形如 `rust://spider_rust`，ext 为 JSON
 * （含 host/module/userAgent/cookie/timeout/headers 等）。
 * 请求经 RustBridge 转发至 libspider_rust.so，由 Rust 侧按 ext.module
 * （或 host 特征）分发到对应站点模块执行。
 */
public class RustSpider extends Spider {

    private static final String DEFAULT_HOST = "https://www.4kvms.org/";

    private String host;

    /** init 时保存的 ext 原文，原样透传给 Rust 引擎（模块分发依据） */
    private String extJson = "";

    /** 规范化后的 ext JSON（含 host 兜底），供 WasmLoader 加载 wasm 时复用，保证 Rust 侧站点 key 一致 */
    String extJson() {
        return extJson;
    }

    public RustSpider() {
    }

    @Override
    public void init(Context context) throws Exception {
        init(context, null);
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        host = parseHost(extend);
        extJson = buildExt(host, extend);
        // 预热 Rust 引擎（建连 + WASM 初始化），失败不阻塞（native 内已容错）
        try {
            if (RustBridge.isLoaded()) RustBridge.nativeWarmUp(host, extJson);
        } catch (Throwable ignored) {
        }
    }

    /** 从 ext JSON 中解析 host；兼容 `host=xxx` 与裸域名写法 */
    private String parseHost(String extend) {
        if (!TextUtils.isEmpty(extend)) {
            try {
                JSONObject json = new JSONObject(extend);
                String h = json.optString("host", "").trim();
                if (!h.isEmpty()) return normalizeHost(h);
            } catch (Exception ignored) {
            }
            if (extend.contains("://")) return normalizeHost(extend.trim());
            if (extend.matches("[\\w.-]+\\.[a-zA-Z]{2,}.*")) return normalizeHost(extend.trim());
        }
        return DEFAULT_HOST;
    }

    private String normalizeHost(String h) {
        if (!h.contains("://")) h = "https://" + h;
        if (!h.endsWith("/")) h = h + "/";
        return h;
    }

    /**
     * 构造透传给 Rust 引擎的 ext JSON：
     * - ext 本身是合法 JSON：保留原字段（module/userAgent/cookie/headers 等均生效），并确保 host 字段存在
     * - ext 非 JSON：包装为 {"host": ...}，其余交由 Rust 侧 host 特征识别
     */
    private String buildExt(String host, String extend) {
        if (!TextUtils.isEmpty(extend)) {
            try {
                JSONObject json = new JSONObject(extend);
                if (json.optString("host", "").trim().isEmpty()) json.put("host", host);
                return json.toString();
            } catch (Exception ignored) {
            }
        }
        try {
            return new JSONObject().put("host", host).toString();
        } catch (Exception e) {
            return "{\"host\":\"" + host + "\"}";
        }
    }

    private String call(RustCall call) {
        if (!RustBridge.isLoaded()) return "";
        try {
            String result = call.invoke();
            return result == null ? "" : result;
        } catch (Throwable t) {
            t.printStackTrace();
            return "";
        }
    }

    private interface RustCall {
        String invoke();
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        return call(() -> RustBridge.nativeHomeContent(host, extJson));
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        final int p = page;
        return call(() -> RustBridge.nativeCategoryContent(host, tid, p, extJson));
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "";
        final String id = ids.get(0);
        return call(() -> RustBridge.nativeDetailContent(host, id, extJson));
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        final int p = page;
        final String k = key;
        return call(() -> RustBridge.nativeSearchContent(host, k, p, extJson));
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        final String playId = id;
        return call(() -> RustBridge.nativePlayerContent(host, playId, extJson));
    }
}

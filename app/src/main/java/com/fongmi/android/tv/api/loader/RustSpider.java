package com.fongmi.android.tv.api.loader;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;

/**
 * Rust 爬虫 Spider 适配层。
 * 站点配置 api 形如 `rust://spider_4kvm`，ext 为 JSON（含 host 字段）。
 * 所有请求转发至 libspider_4kvm.so（Rust 原生实现）。
 */
public class RustSpider extends Spider {

    private static final String DEFAULT_HOST = "https://www.4kvms.org/";

    private String host;

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
        // 预热 Rust 引擎（建连 + WASM 初始化），失败不阻塞（native 内已容错）
        try {
            if (RustBridge.isLoaded()) RustBridge.nativeWarmUp(host);
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
        return call(() -> RustBridge.nativeHomeContent(host));
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        int page = 1;
        try {
            page = Integer.parseInt(pg);
        } catch (Exception ignored) {
        }
        final int p = page;
        return call(() -> RustBridge.nativeCategoryContent(host, tid, p));
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        if (ids == null || ids.isEmpty()) return "";
        final String id = ids.get(0);
        return call(() -> RustBridge.nativeDetailContent(host, id));
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
        return call(() -> RustBridge.nativeSearchContent(host, k, p));
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        final String playId = id;
        return call(() -> RustBridge.nativePlayerContent(host, playId));
    }
}

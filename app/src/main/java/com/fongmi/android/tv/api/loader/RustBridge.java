package com.fongmi.android.tv.api.loader;

import android.util.Log;

/**
 * Rust 爬虫引擎 JNI 桥（通用版）。
 * 对应 rust-spider/src/jni_api.rs 导出的 native 方法，
 * 所有方法返回 Spider 协议 JSON 字符串，异常时返回空串（由上层兜底）。
 *
 * 签名遵循技术文档 5.1：(host, ext) 双参数，ext 为站点配置 JSON
 * （含 host/module/userAgent/cookie/timeout/headers 等，模块分发依据 ext.module 或 host 特征）。
 */
public class RustBridge {

    private static final String TAG = "RustBridge";
    private static volatile boolean loaded;

    static {
        try {
            System.loadLibrary("spider_rust");
            loaded = true;
        } catch (Throwable e) {
            loaded = false;
            Log.e(TAG, "loadLibrary(spider_rust) 失败: " + e.getMessage());
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    // 预热：建连 + 初始化 WASM 签名引擎
    public static native String nativeWarmUp(String host, String ext);

    // 首页内容 → {"class":[...],"list":[...]}
    public static native String nativeHomeContent(String host, String ext);

    // 分类内容 → {"list":[...],"page":..,"pagecount":..,"limit":..,"total":..}
    public static native String nativeCategoryContent(String host, String tid, int pg, String ext);

    // 详情内容 → {"list":[{...}]}
    public static native String nativeDetailContent(String host, String id, String ext);

    // 搜索内容 → {"list":[...],...}
    public static native String nativeSearchContent(String host, String key, int pg, String ext);

    // 播放内容 → {"parse":0,"url":"...","header":{...}}
    public static native String nativePlayerContent(String host, String id, String ext);
}

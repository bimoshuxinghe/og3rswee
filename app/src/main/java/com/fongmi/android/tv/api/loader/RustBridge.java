package com.fongmi.android.tv.api.loader;

import android.util.Log;

/**
 * Rust 爬虫引擎 JNI 桥。
 * 对应 rust-spider/src/jni_api.rs 导出的 native 方法，
 * 所有方法返回 Spider 协议 JSON 字符串，异常时返回空串（由上层兜底）。
 */
public class RustBridge {

    private static final String TAG = "RustBridge";
    private static volatile boolean loaded;

    static {
        try {
            System.loadLibrary("spider_4kvm");
            loaded = true;
        } catch (Throwable e) {
            loaded = false;
            Log.e(TAG, "loadLibrary(spider_4kvm) 失败: " + e.getMessage());
        }
    }

    public static boolean isLoaded() {
        return loaded;
    }

    // 预热：建连 + 初始化 WASM 签名引擎
    public static native String nativeWarmUp(String host);

    // 首页内容 → {"class":[...],"list":[...]}
    public static native String nativeHomeContent(String host);

    // 分类内容 → {"list":[...],"page":..,"pagecount":..,"limit":..,"total":..}
    public static native String nativeCategoryContent(String host, String tid, int pg);

    // 详情内容 → {"list":[{...}]}
    public static native String nativeDetailContent(String host, String id);

    // 搜索内容 → {"list":[...],...}
    public static native String nativeSearchContent(String host, String key, int pg);

    // 播放内容 → {"parse":0,"url":"...","header":{...}}
    public static native String nativePlayerContent(String host, String id);
}

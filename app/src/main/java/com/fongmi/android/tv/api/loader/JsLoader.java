package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JsLoader {

    private final ConcurrentHashMap<String, Spider> spiders;
    private final Loader loader;
    private volatile String recent;

    public JsLoader() {
        spiders = new ConcurrentHashMap<>();
        loader = new Loader();
    }

    public void clear() {
        // 逐个 try/catch：任何一个 Spider 的 destroy 抛异常都不能打断整体清理。
        // 一旦中断，spiders.clear() 与 Module.get().clear() 就执行不到，
        // 已销毁的 Spider 会继续留在 map 里被复用，表现为站点永久空白且不断重建。
        for (Spider spider : spiders.values()) {
            try {
                spider.destroy();
            } catch (Throwable e) {
                logToDebugPage("spider destroy FAILED: " + describe(e));
            }
        }
        // 必须同时清空 QuickJS 的 JS 模块缓存（以 URL 为 key 的 LruCache）。
        // 否则当用户把修改后的 spider.js 覆盖到同一个 URL 后刷新配置，
        // Module.get().fetch(url) 会命中旧内容（甚至首次下载失败缓存下来的 ""），
        // 导致站点“加载不出来”——这正是与“蜂蜜影视能加载、本软件加载不出”的根因差异。
        // getSpider() 不会缓存 SpiderNull：拉取/初始化失败时仅本次返回 SpiderNull，
        // 下一次调用会重新创建，因此这里强制 evict 不会造成永久空白。
        try {
            Module.get().clear();
        } catch (Throwable e) {
            logToDebugPage("module cache clear FAILED: " + describe(e));
        }
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        Spider existing = spiders.get(key);
        if (existing != null) return existing;
        // 必须串行化创建：并发调用会在同一个 key 上重复 new 出多个 Spider
        // （日志里同一站点连着 5 次 init failed 就是 5 个实例在同时跑），
        // 每次都新建 QuickJSContext，既浪费又互相干扰。
        synchronized (this) {
            existing = spiders.get(key);
            if (existing != null) return existing;
            try {
                Spider spider = loader.spider(api, BaseLoader.get().dex(jar));
                spider.siteKey = key;
                spider.init(App.get(), ext);
                spiders.put(key, spider);
                return spider;
            } catch (Throwable e) {
                e.printStackTrace();
                // 写本地调试页：蜘蛛创建失败（init 抛异常、jar/dex 加载失败等）若只 printStackTrace，
                // 调试页完全不可见，表现为「站点空白且反复重建」。
                logToDebugPage("spider create FAILED: key=" + key + " api=" + api + " -> " + describe(e));
                // Do NOT cache SpiderNull — if spider creation fails (e.g. JS file
                // fetch fails, QuickJS context error), returning a cached SpiderNull
                // means all subsequent calls permanently return empty results.
                // By not caching, the next call will retry creation.
                return new SpiderNull();
            }
        }
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }

    private static void logToDebugPage(String msg) {
        try {
            Class<?> cls = Class.forName("com.fongmi.chaquo.DbgLog");
            cls.getMethod("log", String.class).invoke(null, "[JsLoader] " + msg);
        } catch (Throwable ignored) {
        }
    }

    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        Throwable cause = e.getCause();
        int depth = 0;
        while (cause != null && depth++ < 3) {
            sb.append(" | cause: ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            cause = cause.getCause();
        }
        return sb.toString();
    }
}

package com.fongmi.android.tv.api.loader;

import com.fongmi.android.tv.App;
import com.fongmi.quickjs.crawler.Loader;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.DebugLog;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class JsLoader {

    private static final String TAG = "JsLoader";

    private final ConcurrentHashMap<String, Spider> spiders;
    private final Loader loader;
    private volatile String recent;

    public JsLoader() {
        spiders = new ConcurrentHashMap<>();
        loader = new Loader();
    }

    public void clear() {
        DebugLog.i(TAG, "clear: destroying " + spiders.size() + " spiders and clearing JS module cache");
        spiders.values().forEach(Spider::destroy);
        Module.get().clear();
        spiders.clear();
        recent = null;
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        Spider existing = spiders.get(key);
        if (existing != null) return existing;
        DebugLog.i(TAG, "create spider: key=" + key + " api=" + api);
        try {
            Spider spider = loader.spider(api, BaseLoader.get().dex(jar));
            spider.siteKey = key;
            spider.init(App.get(), ext);
            spiders.put(key, spider);
            DebugLog.i(TAG, "spider ready: key=" + key);
            return spider;
        } catch (Throwable e) {
            DebugLog.e(TAG, "spider create failed: key=" + key + " api=" + api + " error=" + e);
            // Do NOT cache SpiderNull — if spider creation fails (e.g. JS file
            // fetch fails, QuickJS context error), returning a cached SpiderNull
            // means all subsequent calls permanently return empty results.
            // By not caching, the next call will retry creation.
            return new SpiderNull();
        }
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}

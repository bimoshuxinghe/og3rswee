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
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
        recent = null;
        // Do NOT call Module.get().clear() here.
        // Clearing the JS module cache forces every JS file to be re-fetched
        // from the network on the next spider creation. If the re-fetch fails
        // (network issue, server down, etc.), the spider cannot initialize,
        // resulting in a blank screen after config refresh.
        // The module cache uses URL as key, so different config URLs will
        // naturally miss the cache. Same URLs pointing to updated content
        // will be refreshed on app restart.
    }

    public void setRecent(String recent) {
        this.recent = recent;
    }

    public Spider getSpider(String key, String api, String ext, String jar) {
        Spider existing = spiders.get(key);
        if (existing != null) return existing;
        try {
            Spider spider = loader.spider(api, BaseLoader.get().dex(jar));
            spider.siteKey = key;
            spider.init(App.get(), ext);
            spiders.put(key, spider);
            return spider;
        } catch (Throwable e) {
            e.printStackTrace();
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

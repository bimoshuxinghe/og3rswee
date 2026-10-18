package com.fongmi.android.tv.api.loader;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rust 线路加载器：api 形如 `rust://spider_rust`。
 * 与 PyLoader/JsLoader 同构，按 siteKey 缓存 Spider 实例。
 */
public class RustLoader {

    private final ConcurrentHashMap<String, Spider> spiders;

    public RustLoader() {
        spiders = new ConcurrentHashMap<>();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
    }

    public Spider getSpider(String key, String api, String ext) {
        return spiders.computeIfAbsent(key, k -> {
            try {
                Spider spider = new RustSpider();
                spider.siteKey = key;
                spider.init(com.fongmi.android.tv.App.get(), ext);
                return spider;
            } catch (Throwable e) {
                e.printStackTrace();
                return new SpiderNull();
            }
        });
    }
}

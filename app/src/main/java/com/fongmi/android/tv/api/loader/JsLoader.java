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
        // 逐个 try/catch：任何一个 Spider 的 destroy 抛异常都不能打断整体清理，
        // 否则已销毁的 Spider 会继续留在 map 里被复用，表现为站点空白。
        for (Spider spider : spiders.values()) {
            try {
                spider.destroy();
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
        // 刷新配置时必须清空 JS 模块缓存，避免 fetch 命中旧内容（或失败时缓存的空串）。
        try {
            Module.get().clear();
        } catch (Throwable e) {
            e.printStackTrace();
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
        // 串行化创建：并发调用会在同一个 key 上重复 new 出多个 Spider，
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
                // 创建失败时缓存 SpiderNull，避免每次访问都重建（反复超时循环）。
                SpiderNull nullSpider = new SpiderNull();
                spiders.put(key, nullSpider);
                return nullSpider;
            }
        }
    }

    public Object[] proxy(Map<String, String> params) throws Exception {
        if (recent == null) return null;
        Spider spider = spiders.get(recent);
        return spider != null ? spider.proxy(params) : null;
    }
}

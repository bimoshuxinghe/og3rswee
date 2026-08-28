package com.fongmi.quickjs.utils;

import android.text.TextUtils;
import android.util.LruCache;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.DebugLog;

public class Module {

    private static final int MAX_SIZE = 50;
    private final LruCache<String, String> cache;

    public Module() {
        cache = new LruCache<>(MAX_SIZE);
    }

    public static Module get() {
        return Loader.INSTANCE;
    }

    public String fetch(String name) {
        String content = cache.get(name);
        if (!TextUtils.isEmpty(content)) {
            DebugLog.i("Module", "cache hit: " + name + " len=" + content.length());
            return content;
        }
        if (name.startsWith("http")) {
            cache.put(name, content = OkHttp.string(name));
            DebugLog.i("Module", "fetched: " + name + " len=" + (content == null ? -1 : content.length()));
        } else if (name.startsWith("assets")) {
            cache.put(name, content = Asset.read(name));
            DebugLog.i("Module", "assets: " + name + " len=" + (content == null ? -1 : content.length()));
        } else if (name.startsWith("lib/")) {
            cache.put(name, content = Asset.read("js/" + name));
        }
        return content == null ? "" : content;
    }

    public void clear() {
        DebugLog.i("Module", "cache cleared, size=" + cache.size());
        cache.evictAll();
    }

    private static class Loader {
        static volatile Module INSTANCE = new Module();
    }
}

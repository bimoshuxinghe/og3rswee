package com.fongmi.quickjs.utils;

import android.text.TextUtils;
import android.util.LruCache;

import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Asset;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;

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
        if (!TextUtils.isEmpty(content)) return content;
        if (name.startsWith("http")) cache.put(name, content = OkHttp.string(name));
        else if (name.startsWith("assets")) cache.put(name, content = Asset.read(name));
        else if (name.startsWith("lib/")) cache.put(name, content = Asset.read("js/" + name));
        else if (name.startsWith("/")) cache.put(name, content = read(name));
        return content == null ? "" : content;
    }

    /**
     * 本地 .js 爬虫：站点 api 字段直接存缓存目录里的绝对路径，
     * 这里按磁盘文件读取，不经过网络与 assets。
     */
    private static String read(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public void clear() {
        cache.evictAll();
    }

    private static class Loader {
        static volatile Module INSTANCE = new Module();
    }
}

package com.fongmi.android.tv.player;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.github.catvod.net.OkHttp;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class PreloadManager {

    private final LinkedHashMap<String, Result> results;
    private final Set<String> pending;
    private ExecutorService executor;
    private int threadCount;

    private PreloadManager() {
        results = new LinkedHashMap<>(16, 0.75f, true);
        pending = new HashSet<>();
    }

    public static PreloadManager get() {
        return Loader.INSTANCE;
    }

    @Nullable
    public synchronized Result get(String key, String flag, Episode episode) {
        return results.get(cacheKey(key, flag, episode));
    }

    public void preload(String key, String flag, Episode episode, boolean useParse) {
        if (!canPreload(key, flag, episode)) return;
        String cacheKey = cacheKey(key, flag, episode);
        synchronized (this) {
            if (results.containsKey(cacheKey) || pending.contains(cacheKey)) return;
            pending.add(cacheKey);
            ensureExecutor();
        }
        executor.execute(() -> execute(cacheKey, key, flag, episode, useParse));
    }

    public synchronized void clear() {
        results.clear();
        pending.clear();
    }

    private void execute(String cacheKey, String key, String flag, Episode episode, boolean useParse) {
        try {
            Result result = SiteApi.playerContent(key, flag, episode.getUrl());
            synchronized (this) {
                results.put(cacheKey, result);
                trimResults();
            }
            if (!useParse && !result.needParse() && result.getDrm() == null) warm(result);
        } catch (Throwable ignored) {
        } finally {
            synchronized (this) {
                pending.remove(cacheKey);
            }
        }
    }

    private boolean canPreload(String key, String flag, Episode episode) {
        return PlayerSetting.isPreload() && PlayerSetting.isPreloadNext() && !TextUtils.isEmpty(key) && !TextUtils.isEmpty(flag) && episode != null && !episode.isSelected() && !TextUtils.isEmpty(episode.getUrl());
    }

    private synchronized void ensureExecutor() {
        int count = PlayerSetting.getPreloadThread();
        if (executor != null && threadCount == count) return;
        if (executor != null) executor.shutdownNow();
        threadCount = count;
        executor = Executors.newFixedThreadPool(count);
    }

    private void trimResults() {
        int max = Math.max(1, PlayerSetting.getPreloadThread() * 2);
        while (results.size() > max) {
            String first = results.keySet().iterator().next();
            results.remove(first);
        }
    }

    private void warm(Result result) {
        String url = result.getRealUrl();
        if (!url.startsWith("http")) return;
        long limit = (long) PlayerSetting.getPreloadCapacity() * 1024L * 1024L;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PlayerSetting.getPreloadSeconds());
        Request.Builder builder = new Request.Builder().url(url).headers(Headers.of(result.getHeader())).header("Range", "bytes=0-" + Math.max(0, limit - 1));
        try (Response response = OkHttp.player().newCall(builder.build()).execute(); ResponseBody body = response.body()) {
            if (body == null) return;
            read(body.byteStream(), limit, deadline);
        } catch (Throwable ignored) {
        }
    }

    private void read(InputStream input, long limit, long deadline) throws Exception {
        byte[] buffer = new byte[32 * 1024];
        long total = 0;
        int read;
        while (total < limit && System.nanoTime() < deadline && (read = input.read(buffer, 0, (int) Math.min(buffer.length, limit - total))) != -1) {
            total += read;
        }
    }

    private static String cacheKey(String key, String flag, Episode episode) {
        return String.format(Locale.ROOT, "%s|%s|%s", key, flag, episode == null ? "" : episode.getUrl());
    }

    private static class Loader {
        static volatile PreloadManager INSTANCE = new PreloadManager();
    }
}

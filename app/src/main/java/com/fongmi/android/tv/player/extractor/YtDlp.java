package com.fongmi.android.tv.player.extractor;

import android.net.Uri;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.chaquo.Platform;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class YtDlp implements Source.Extractor {

    // 直链 -> 原始 YouTube URL。播放中出现 Bad HTTP Status（直链过期/被 CDN 拒绝）时，
    // 播放器层可通过直链反查原始网页地址，重新调用 yt-dlp 解析新直链并恢复播放。
    private static final ConcurrentHashMap<String, String> ORIGINALS = new ConcurrentHashMap<>();

    public static String getOriginal(String directUrl) {
        return directUrl == null ? null : ORIGINALS.get(directUrl);
    }

    public static void clearOriginals() {
        ORIGINALS.clear();
    }

    public YtDlp() {
    }

    @Override
    public boolean match(Uri uri) {
        String host = UrlUtil.host(uri);
        return host.contains("youtube.com") || host.contains("youtu.be");
    }

    @Override
    public String fetch(String url) throws Exception {
        PyObject module = getModule();
        JSONObject obj = new JSONObject(module.callAttr("extract", url, false).toString());
        if ("error".equals(obj.optString("type"))) {
            throw new Exception(obj.optString("message", "yt-dlp parse failed"));
        }
        String playUrl = obj.optString("url");
        if (playUrl.isEmpty()) {
            throw new Exception("yt-dlp: no playable url");
        }
        ORIGINALS.put(playUrl, url);
        return playUrl;
    }

    private static PyObject getModule() {
        if (!Python.isStarted()) Python.start(Platform.create());
        return Python.getInstance().getModule("ytdlp");
    }

    @Override
    public void stop() {
    }

    @Override
    public void exit() {
        ORIGINALS.clear();
    }

    public record Parser(String url) implements Callable<List<Episode>> {

        private static final Pattern PATTERN = Pattern.compile("(youtube\\.com|youtu\\.be).*list=");

        public static boolean match(String url) {
            return PATTERN.matcher(url).find();
        }

        public static Parser get(String url) {
            return new Parser(url);
        }

        @Override
        public List<Episode> call() {
            try {
                PyObject module = getModule();
                JSONObject obj = new JSONObject(module.callAttr("extract", url, true).toString());
                JSONArray items = obj.optJSONArray("items");
                if (items == null) return Collections.emptyList();
                List<Episode> episodes = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;
                    String name = item.optString("name");
                    String itemUrl = item.optString("url");
                    if (name.isEmpty() || itemUrl.isEmpty()) continue;
                    episodes.add(Episode.create(name, itemUrl));
                }
                return episodes;
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }
    }
}

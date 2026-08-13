package com.fongmi.android.tv.player;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.custom.CustomWebView;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.WebViewUtil;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Util;
import com.google.common.net.HttpHeaders;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Response;

public class ParseJob implements ParseCallback {

    private final AtomicBoolean done = new AtomicBoolean();
    private final List<CustomWebView> webViews;
    private ExecutorService executor;
    private ExecutorService infinite;
    private ParseCallback callback;
    private Parse parse;

    private ParseJob(ParseCallback callback) {
        this.executor = Executors.newSingleThreadExecutor();
        this.infinite = Executors.newCachedThreadPool();
        this.webViews = new ArrayList<>();
        this.callback = callback;
    }

    public static ParseJob create(ParseCallback callback) {
        return new ParseJob(callback);
    }

    public ParseJob start(Result result, boolean useParse) {
        setParse(result, useParse);
        execute(result);
        return this;
    }

    private void setParse(Result result, boolean useParse) {
        if (useParse) parse = VodConfig.get().getParse();
        if (result.getPlayUrl().startsWith("json:")) parse = Parse.get(1, result.getPlayUrl().substring(5));
        if (result.getPlayUrl().startsWith("parse:")) parse = VodConfig.get().getParse(result.getPlayUrl().substring(6));
        if (parse == null || parse.isEmpty()) parse = Parse.get(0, result.getPlayUrl());
        parse.setHeader(result.getHeader());
        parse.setClick(getClick(result));
        SpiderDebug.log("parse", "selected name=%s type=%s url=%s useParse=%s", parse.getName(), parse.getType(), parse.getUrl(), useParse);
    }

    private String getClick(Result result) {
        String click = VodConfig.get().getSite(result.getKey()).getClick();
        if (!TextUtils.isEmpty(click)) return click;
        return result.getClick();
    }

    private void execute(Result result) {
        Future<?> task = executor.submit(getTask(result));
        Task.schedule(() -> {
            if (task.cancel(true)) onParseError();
        }, Constant.TIMEOUT_PARSE_DEF, TimeUnit.MILLISECONDS);
    }

    private Runnable getTask(Result result) {
        return () -> {
            try {
                doInBackground(result.getKey(), result.getUrl().v(), result.getFlag());
            } catch (Throwable e) {
                onParseError();
            }
        };
    }

    private void doInBackground(String key, String webUrl, String flag) throws Throwable {
        switch (parse.getType()) {
            case 0:
                startWeb(key, parse, webUrl);
                break;
            case 1:
                jsonParse(parse, webUrl, true);
                break;
            case 2:
                jsonExtend(webUrl);
                break;
            case 3:
                jsonMix(webUrl, flag);
                break;
            case 4:
                superParse(webUrl, flag);
                break;
        }
    }

    private void jsonParse(Parse item, String webUrl, boolean fatal) throws Exception {
        String parseUrl = buildParseUrl(item.getUrl(), webUrl);
        SpiderDebug.log("parse", "request name=%s url=%s", item.getName(), parseUrl);
        try (Response res = OkHttp.newCall(parseUrl, requestHeader(item)).execute()) {
            String body = res.body() == null ? "" : res.body().string();
            SpiderDebug.log("parse", "response name=%s code=%s body=%s", item.getName(), res.code(), summarize(body));
            JsonObject object = parseObject(body);
            if (object != null) {
                String url = Json.safeString(object, "url");
                JsonObject data = getObject(object, "data");
                if (url.isEmpty()) url = Json.safeString(data, "url");
                checkResult(getHeader(object, data), url, item.getName(), fatal);
                return;
            }
            String url = getDirectUrl(body);
            if (!url.isEmpty()) {
                checkResult(item.getHeader(), url, item.getName(), fatal);
                return;
            }
            SpiderDebug.log("parse", "non-json name=%s code=%s url=%s body=%s", item.getName(), res.code(), parseUrl, summarize(body));
            if (fatal) startWeb("", item.getName(), item.getHeader(), parseUrl, item.getClick());
        } catch (Exception e) {
            SpiderDebug.log("parse", "request failed name=%s url=%s error=%s", item.getName(), parseUrl, e.getMessage());
            throw e;
        }
    }

    private void jsonExtend(String webUrl) throws Throwable {
        LinkedHashMap<String, String> jxs = new LinkedHashMap<>();
        for (Parse item : VodConfig.get().getParses()) if (item.getType() == 1) jxs.put(item.getName(), item.extUrl());
        checkResult(Result.fromObject(BaseLoader.get().jsonExt(parse.getUrl(), jxs, webUrl)));
    }

    private void jsonMix(String webUrl, String flag) throws Throwable {
        LinkedHashMap<String, HashMap<String, String>> jxs = new LinkedHashMap<>();
        for (Parse item : VodConfig.get().getParses()) jxs.put(item.getName(), item.mixMap());
        checkResult(Result.fromObject(BaseLoader.get().jsonExtMix(flag, parse.getUrl(), parse.getName(), jxs, webUrl)));
    }

    private void superParse(String webUrl, String flag) throws Exception {
        List<Parse> json = VodConfig.get().getParses(1, flag);
        List<Parse> webs = VodConfig.get().getParses(0, flag);
        int count = json.size() + (webs.isEmpty() ? 0 : 1);
        CountDownLatch latch = new CountDownLatch(count);
        for (Parse item : json) infinite.execute(() -> jsonParse(latch, item, webUrl));
        if (!webs.isEmpty()) startWeb(webs, webUrl);
        latch.await();
        onParseError();
    }

    private void jsonParse(CountDownLatch latch, Parse item, String webUrl) {
        try {
            jsonParse(item, webUrl, false);
        } catch (Exception e) {
            SpiderDebug.log("parse", "json failed name=%s error=%s", item.getName(), e.getMessage());
        } finally {
            latch.countDown();
        }
    }

    private void checkResult(Map<String, String> headers, String url, String from, boolean fatal) {
        if (url.length() > 40) {
            SpiderDebug.log("parse", "success from=%s url=%s", from, url);
            onParseSuccess(headers, cleanM3u8(url, from), from);
        } else if (fatal) {
            SpiderDebug.log("parse", "empty result from=%s url=%s", from, url);
            onParseError();
        }
    }

    private void checkResult(Result result) {
        result.setHeader(parse.getHeader());
        if (result.getUrl().isEmpty()) onParseError();
        else if (result.needParse()) startWeb(result.getHeader(), UrlUtil.convert(result.getUrl().v()));
        else onParseSuccess(result.getHeader(), cleanM3u8(result.getUrl().v(), result.getJxFrom()), result.getJxFrom());
    }

    private String cleanM3u8(String url, String from) {
        if (!shouldCleanM3u8(url, from)) return url;
        String clean = Server.get().getAddress("/m3u8?url=" + Uri.encode(url));
        SpiderDebug.log("parse", "clean m3u8 from=%s url=%s clean=%s", from, url, clean);
        return clean;
    }

    private boolean shouldCleanM3u8(String url, String from) {
        if (!url.contains(".m3u8")) return false;
        if (url.contains("1ljx.com")) return true;
        return !TextUtils.isEmpty(from) && (from.contains("咖啡") || from.toLowerCase().contains("coffee"));
    }

    private String buildParseUrl(String base, String webUrl) {
        if (TextUtils.isEmpty(base)) return webUrl;
        if (base.endsWith("=") || base.endsWith("url=")) return base + Uri.encode(webUrl);
        return base + webUrl;
    }

    private Map<String, String> requestHeader(Parse item) {
        Map<String, String> headers = new HashMap<>(item.getHeader());
        if (headers.keySet().stream().noneMatch(HttpHeaders.USER_AGENT::equalsIgnoreCase)) headers.put(HttpHeaders.USER_AGENT, PlayerHelper.getDefaultUa());
        return headers;
    }

    private void startWeb(List<Parse> items, String webUrl) {
        StringBuilder sb = new StringBuilder();
        for (Parse item : items) sb.append(item.getUrl()).append(";");
        startWeb(new HashMap<>(), Server.get().getAddress("/parse?jxs=" + Util.substring(sb.toString()) + "&url=" + webUrl));
    }

    private void startWeb(String key, Parse item, String webUrl) {
        startWeb(key, item.getName(), item.getHeader(), item.getUrl() + webUrl, item.getClick());
    }

    private void startWeb(Map<String, String> headers, String url) {
        startWeb("", "", headers, url, "");
    }

    private void startWeb(String key, String from, Map<String, String> headers, String url, String click) {
        if (!WebViewUtil.support()) {
            onParseError();
        } else {
            App.post(() -> webViews.add(CustomWebView.create(App.get()).start(key, from, headers, url, click, this, !url.contains("player/?url="))));
        }
    }

    private JsonObject parseObject(String body) {
        String text = trim(body);
        try {
            if (Json.isObj(text)) return Json.parse(text).getAsJsonObject();
            int start = text.indexOf('(');
            int end = text.lastIndexOf(')');
            if (start > 0 && end > start) {
                String jsonp = text.substring(start + 1, end).trim();
                if (Json.isObj(jsonp)) return Json.parse(jsonp).getAsJsonObject();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private JsonObject getObject(JsonObject object, String key) {
        try {
            JsonObject value = object.getAsJsonObject(key);
            return value == null ? new JsonObject() : value;
        } catch (Throwable e) {
            return new JsonObject();
        }
    }

    private String getDirectUrl(String body) {
        String text = trim(body);
        if (Sniffer.isVideoFormat(text)) return text;
        String url = Sniffer.getUrl(text);
        return Sniffer.isVideoFormat(url) ? url : "";
    }

    private String trim(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return text.replace("\uFEFF", "").trim();
    }

    private String summarize(String text) {
        text = trim(text).replaceAll("\\s+", " ");
        return text.length() > 160 ? text.substring(0, 160) : text;
    }

    private Map<String, String> getHeader(JsonObject object, JsonObject data) {
        Map<String, String> headers = getHeader(object);
        if (headers.isEmpty()) headers = getHeader(data);
        return headers.isEmpty() ? parse.getHeader() : headers;
    }

    private Map<String, String> getHeader(JsonObject object) {
        Map<String, String> headers = new HashMap<>();
        if (object == null) return headers;
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) if (!entry.getValue().isJsonNull() && (entry.getKey().equalsIgnoreCase(HttpHeaders.USER_AGENT) || entry.getKey().equalsIgnoreCase(HttpHeaders.REFERER) || entry.getKey().equalsIgnoreCase(HttpHeaders.COOKIE) || entry.getKey().equalsIgnoreCase("ua"))) headers.put(UrlUtil.fixHeader(entry.getKey()), entry.getValue().getAsString());
        return headers;
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!done.compareAndSet(false, true)) return;
        App.post(() -> {
            if (callback != null) callback.onParseSuccess(headers, url, from);
            stop();
        });
    }

    @Override
    public void onParseError() {
        if (!done.compareAndSet(false, true)) return;
        App.post(() -> {
            if (callback != null) callback.onParseError();
            stop();
        });
    }

    private void stopWeb() {
        for (CustomWebView webView : webViews) webView.stop(false);
        for (CustomWebView webView : webViews) webView.destroy();
        if (!webViews.isEmpty()) webViews.clear();
    }

    public void stop() {
        if (executor != null) executor.shutdownNow();
        if (infinite != null) infinite.shutdownNow();
        infinite = null;
        executor = null;
        callback = null;
        done.set(true);
        stopWeb();
    }
}

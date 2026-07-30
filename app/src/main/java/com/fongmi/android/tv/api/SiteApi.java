package com.fongmi.android.tv.api;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Sniffer;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import okhttp3.Call;
import okhttp3.Response;

public class SiteApi {

    public static final String PUSH = "push_agent";
    private static final String MEDIA_FEINIU = "local_media_feiniu_";
    private static final String MEDIA_EMBY = "local_media_emby_";
    private static final String MEDIA_JELLYFIN = "local_media_jellyfin_";

    public static String call(@NonNull Site site, @NonNull ArrayMap<String, String> params) throws IOException {
        if (!site.getExt().isEmpty()) params.put("extend", site.getExt());
        Call call = site.getExt().length() <= 1000 ? OkHttp.newCall(site.getApi(), site.getHeader(), params) : OkHttp.newCall(site.getApi(), site.getHeader(), OkHttp.toBody(params));
        try (Response response = call.execute()) {
            return response.body().string();
        }
    }

    private static boolean isSpider(@NonNull Site site) {
        String api = site.getApi();
        return site.getType() == 3 || api.contains(".js") || api.contains(".py") || api.startsWith("csp_");
    }

    private static String ac(int type) {
        return type == 0 ? "videolist" : "detail";
    }

    @NonNull
    public static Result homeContent(@NonNull Site site) throws Exception {
        if (isSpider(site)) {
            Spider spider = site.recent().spider();
            boolean crash = Prefers.getBoolean("crash");
            String home = crash ? "" : spider.homeContent(true);
            String video = crash ? "" : spider.homeVideoContent();
            Prefers.put("crash", false);
            SpiderDebug.log("home", home);
            SpiderDebug.log("homeVideo", video);
            Result result = Result.fromJson(home);
            List<Vod> list = Result.fromJson(video).getList();
            if (!list.isEmpty()) result.setList(list);
            setTypes(site, result);
            return result;
        } else if (site.getType() == 4) {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("filter", "true");
            String homeContent = call(site.fetchExt(), params);
            SpiderDebug.log("home", homeContent);
            Result result = Result.fromJson(homeContent);
            setTypes(site, result);
            return result;
        } else {
            try (Response response = OkHttp.newCall(site.getApi(), site.getHeader()).execute()) {
                String homeContent = response.body().string();
                SpiderDebug.log("home", homeContent);
                Result result = Result.fromType(site.getType(), homeContent);
                fetchPic(site, result);
                setTypes(site, result);
                return result;
            }
        }
    }

    @NonNull
    public static Result categoryContent(@NonNull String key, @NonNull String tid, @NonNull String page, boolean filter, @NonNull HashMap<String, String> extend) throws Exception {
        SpiderDebug.log("category", "key=%s,tid=%s,page=%s,filter=%s,extend=%s", key, tid, page, filter, extend);
        Site site = VodConfig.get().getSite(key);
        if (isSpider(site)) {
            String categoryContent = site.recent().spider().categoryContent(tid, page, filter, extend);
            SpiderDebug.log("category", categoryContent);
            return Result.fromJson(categoryContent);
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            if (site.getType() == 1 && !extend.isEmpty()) params.put("f", App.gson().toJson(extend));
            if (site.getType() == 4) params.put("ext", Util.base64(App.gson().toJson(extend), Util.URL_SAFE));
            params.put("ac", ac(site.getType()));
            params.put("t", tid);
            params.put("pg", page);
            String categoryContent = call(site, params);
            SpiderDebug.log("category", categoryContent);
            return Result.fromType(site.getType(), categoryContent);
        }
    }

    @NonNull
    public static Result detailContent(@NonNull String key, @NonNull String id) throws Exception {
        SpiderDebug.log("detail", "key=%s,id=%s", key, id);
        Site site = getSite(key);
        if (site.isEmpty() && "local".equals(key)) {
            Vod vod = new Vod();
            vod.setId(id);
            vod.setPlayFrom("本地离线");

            if (id.startsWith("local_play_media://detail")) {
                String currentUrl = "";
                String vodName = "";
                try {
                    android.net.Uri uri = android.net.Uri.parse(id);
                    currentUrl = uri.getQueryParameter("current_url");
                    vodName = uri.getQueryParameter("vod_name");
                } catch (Exception e) {
                    e.printStackTrace();
                }

                if (TextUtils.isEmpty(vodName)) {
                    vod.setName("本地离线");
                    vod.setPlayUrl(id);
                } else {
                    vod.setName(vodName);
                    List<com.fongmi.android.tv.bean.Download> list = com.fongmi.android.tv.bean.Download.getAll();
                    java.util.List<com.fongmi.android.tv.bean.Download> completedList = new java.util.ArrayList<>();
                    for (com.fongmi.android.tv.bean.Download d : list) {
                        if (vodName.equals(d.getVodName()) && d.getStatus() == com.fongmi.android.tv.bean.Download.STATUS_COMPLETED) {
                            completedList.add(d);
                        }
                    }

                    java.util.Collections.sort(completedList, new java.util.Comparator<com.fongmi.android.tv.bean.Download>() {
                        @Override
                        public int compare(com.fongmi.android.tv.bean.Download o1, com.fongmi.android.tv.bean.Download o2) {
                            try {
                                int n1 = Integer.parseInt(o1.getEpisodeName().replaceAll("\\D+", ""));
                                int n2 = Integer.parseInt(o2.getEpisodeName().replaceAll("\\D+", ""));
                                return Integer.compare(n1, n2);
                            } catch (Exception e) {
                                return Long.compare(o1.getCreateTime(), o2.getCreateTime());
                            }
                        }
                    });

                    StringBuilder playUrlBuilder = new StringBuilder();
                    for (int i = 0; i < completedList.size(); i++) {
                        com.fongmi.android.tv.bean.Download d = completedList.get(i);
                        java.io.File m3u8File = new java.io.File(d.getDownloadPath(), "local.m3u8");
                        String dUrl;
                        if (m3u8File.exists()) {
                            dUrl = "http://127.0.0.1:" + com.github.catvod.Proxy.getPort() + "/local_play" + m3u8File.getAbsolutePath();
                        } else {
                            java.io.File mp4File = new java.io.File(d.getDownloadPath(), "video.mp4");
                            java.io.File mkvFile = new java.io.File(d.getDownloadPath(), "video.mkv");
                            java.io.File target = mp4File.exists() ? mp4File : mkvFile;
                            if (target.exists()) {
                                dUrl = "http://127.0.0.1:" + com.github.catvod.Proxy.getPort() + "/local_play" + target.getAbsolutePath();
                            } else {
                                continue;
                            }
                        }
                        if (playUrlBuilder.length() > 0) {
                            playUrlBuilder.append("#");
                        }
                        playUrlBuilder.append(d.getEpisodeName()).append("$").append(dUrl);
                    }

                    if (playUrlBuilder.length() > 0) {
                        vod.setPlayUrl(playUrlBuilder.toString());
                    } else {
                        vod.setPlayUrl(currentUrl);
                    }
                }
            } else {
                vod.setName("本地离线");
                vod.setPlayUrl(id);
            }

            Source.get().parse(vod.setFlags());
            return Result.vod(vod);
        } else if (isDirectMedia(site, key, id)) {
            return Result.vod(directVod(id, site.isEmpty() ? ResUtil.getString(R.string.push) : site.getName(), site.isEmpty()));
        } else if (isMediaLibraryKey(key)) {
            return MediaDetailApi.detail(site, key, id);
        } else if (isSpider(site)) {
            String detailContent = site.recent().spider().detailContent(Arrays.asList(id));
            SpiderDebug.log("detail", detailContent);
            Result result = Result.fromJson(detailContent);
            Source.get().parse(result.getVod().setFlags());
            return result;
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("ac", ac(site.getType()));
            params.put("ids", id);
            String detailContent = call(site, params);
            SpiderDebug.log("detail", detailContent);
            Result result = Result.fromType(site.getType(), detailContent);
            Source.get().parse(result.getVod().setFlags());
            return result;
        }
    }

    @NonNull
    public static Result playerContent(@NonNull String key, @NonNull String flag, @NonNull String id) throws Exception {
        SpiderDebug.log("player", "key=%s,flag=%s,id=%s", key, flag, id);
        Site site = getSite(key);
        Source.get().stop();
        if (isMediaLibraryKey(key) || (site.isEmpty() && PUSH.equals(key))) {
            String url = isMediaLibraryKey(key) ? MediaDetailApi.resolvePlayUrl(site, key, id) : id;
            Result result = new Result();
            result.setUrl(url);
            result.setParse(0);
            result.setFlag(flag);
            applyMediaHeaders(key, site, url, result);
            result.setUrl(Source.get().fetch(result));
            SpiderDebug.log("player", result.toString());
            return result;
        } else if (isSpider(site)) {
            String playerContent = site.recent().spider().playerContent(flag, id, VodConfig.get().getFlags());
            SpiderDebug.log("player", playerContent);
            Result result = Result.fromJson(playerContent);
            if (result.getFlag().isEmpty()) result.setFlag(flag);
            result.setUrl(Source.get().fetch(result));
            result.setHeader(site.getHeader());
            result.setKey(key);
            return result;
        } else if (site.getType() == 4) {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("play", id);
            params.put("flag", flag);
            String playerContent = call(site, params);
            SpiderDebug.log("player", playerContent);
            Result result = Result.fromJson(playerContent);
            if (result.getFlag().isEmpty()) result.setFlag(flag);
            result.setUrl(Source.get().fetch(result));
            result.setHeader(site.getHeader());
            return result;
        } else {
            Result result = new Result();
            result.setUrl(id);
            result.setFlag(flag);
            result.setHeader(site.getHeader());
            result.setPlayUrl(site.getPlayUrl());
            result.setParse(Sniffer.isVideoFormat(id) && result.getPlayUrl().isEmpty() ? 0 : 1);
            result.setUrl(Source.get().fetch(result));
            SpiderDebug.log("player", result.toString());
            return result;
        }
    }

    @NonNull
    public static Result searchContent(@NonNull Site site, @NonNull String keyword, boolean quick, @NonNull String page) throws Exception {
        SpiderDebug.log("search", "site=%s,keyword=%s,quick=%s,page=%s", site.getName(), keyword, quick, page);
        boolean hasPage = !page.equals("1");
        if (isSpider(site)) {
            String searchContent = hasPage ? site.spider().searchContent(keyword, quick, page) : site.spider().searchContent(keyword, quick);
            SpiderDebug.log("search", searchContent);
            Result result = Result.fromJson(searchContent);
            for (Vod vod : result.getList()) vod.setSite(site);
            return result;
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("wd", keyword);
            params.put("quick", String.valueOf(quick));
            params.put("extend", "");
            if (hasPage) params.put("pg", page);
            String searchContent = call(site, params);
            SpiderDebug.log("search", searchContent);
            Result result = fetchPic(site, Result.fromType(site.getType(), searchContent));
            for (Vod vod : result.getList()) vod.setSite(site);
            return result;
        }
    }

    @NonNull
    public static Result action(@NonNull String key, @NonNull String action) throws Exception {
        Site site = getSite(key);
        SpiderDebug.log("action", "key=%s,action=%s", key, action);
        if (isSpider(site)) return Result.fromJson(site.recent().spider().action(action));
        if (site.getType() == 4) return Result.fromJson(OkHttp.string(action));
        return Result.empty();
    }

    @NonNull
    public static Result fetchPic(@NonNull Site site, @NonNull Result result) throws Exception {
        if (site.getType() > 2 || result.getList().isEmpty() || !result.getVod().getPic().isEmpty()) return result;
        ArrayList<String> ids = new ArrayList<>();
        boolean empty = site.getCategories().isEmpty();
        for (Vod item : result.getList()) if (empty || site.getCategories().contains(item.getTypeName())) ids.add(item.getId());
        if (ids.isEmpty()) return result.clear();
        ArrayMap<String, String> params = new ArrayMap<>();
        params.put("ac", ac(site.getType()));
        params.put("ids", TextUtils.join(",", ids));
        try (Response response = OkHttp.newCall(site.getApi(), site.getHeader(), params).execute()) {
            result.setList(Result.fromType(site.getType(), response.body().string()).getList());
            return result;
        }
    }

    private static void setTypes(@NonNull Site site, @NonNull Result result) {
        result.getTypes().stream().filter(type -> result.getFilters().containsKey(type.getTypeId())).forEach(type -> type.setFilters(result.getFilters().get(type.getTypeId())));
        if (site.getCategories().isEmpty()) return;
        Map<String, Class> typeByName = new HashMap<>();
        result.getTypes().forEach(type -> typeByName.put(type.getTypeName(), type));
        List<Class> types = site.getCategories().stream().map(typeByName::get).filter(Objects::nonNull).toList();
        if (!types.isEmpty()) result.setTypes(types);
    }

    private static boolean isDirectMedia(Site site, String key, String id) {
        if (site.isEmpty() && PUSH.equals(key)) return true;
        return isMediaLibraryKey(key) && id.startsWith("media://play");
    }

    private static boolean isMediaLibraryKey(String key) {
        return key.startsWith(MEDIA_FEINIU) || key.startsWith(MEDIA_EMBY) || key.startsWith(MEDIA_JELLYFIN);
    }

    private static Site getSite(String key) {
        Site site = VodConfig.get().getSite(key);
        if (!site.isEmpty() || !isMediaLibraryKey(key)) return site;
        Site local = Site.find(key);
        return local == null ? site : local;
    }

    private static void applyMediaHeaders(String key, Site site, String url, Result result) throws Exception {
        if (!key.startsWith(MEDIA_FEINIU) || TextUtils.isEmpty(url)) return;
        String token = FeiniuAuth.tokenFromExt(site.getExt());
        if (TextUtils.isEmpty(token)) return;
        result.setHeader(FeiniuAuth.headers(token, Uri.parse(url).getEncodedPath()));
    }

    private static Vod directVod(String id, String playFrom, boolean push) throws Exception {
        Vod vod = new Vod();
        Uri uri = id.startsWith("media://play") ? Uri.parse(id) : null;
        String url = uri == null ? id : uri.getQueryParameter("url");
        String name = uri == null ? id : uri.getQueryParameter("name");
        String pic = uri == null ? ResUtil.getString(R.string.push_image) : uri.getQueryParameter("pic");
        String content = uri == null ? "" : uri.getQueryParameter("content");
        String year = uri == null ? "" : uri.getQueryParameter("year");
        String remark = uri == null ? "" : uri.getQueryParameter("remark");
        vod.setId(id);
        vod.setName(TextUtils.isEmpty(name) ? url : name);
        vod.setPlayUrl(vod.getName() + "$" + (TextUtils.isEmpty(url) ? id : url));
        vod.setPlayFrom(push ? playFrom : (TextUtils.isEmpty(playFrom) ? "媒体库" : playFrom));
        vod.setPic(TextUtils.isEmpty(pic) ? ResUtil.getString(R.string.push_image) : pic);
        vod.setContent(content);
        vod.setYear(year);
        vod.setRemarks(remark);
        Source.get().parse(vod.setFlags());
        return vod;
    }
}

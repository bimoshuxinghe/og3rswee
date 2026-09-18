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
                        java.io.File dFile = findLocalVideo(d.getDownloadPath());
                        String dUrl;
                        if (dFile != null) {
                            dUrl = "http://127.0.0.1:" + com.github.catvod.Proxy.getPort() + "/local_play" + dFile.getAbsolutePath();
                        } else {
                            continue;
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
            if (result.getUrl().v().startsWith("pics://") || result.getUrl().v().startsWith("novel://")) {
                result.setPlayUrl("");
                result.setParse(0);
                result.setJx(0);
            }
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
            if (result.getUrl().v().startsWith("pics://") || result.getUrl().v().startsWith("novel://")) {
                result.setPlayUrl("");
                result.setParse(0);
                result.setJx(0);
            }
            result.setUrl(Source.get().fetch(result));
            result.setHeader(site.getHeader());
            return result;
        } else {
            Result result = new Result();
            result.setUrl(id);
            result.setFlag(flag);
            result.setHeader(site.getHeader());
            if (id.startsWith("pics://") || id.startsWith("novel://")) {
                result.setParse(0);
            } else {
                result.setPlayUrl(site.getPlayUrl());
                result.setParse(Sniffer.isVideoFormat(id) && result.getPlayUrl().isEmpty() ? 0 : 1);
            }
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

    /**
     * 查找单集已下载的可播放文件，优先级：合并专属格式 .xhtv > 分片模式 local.m3u8 > 单文件 mp4/mkv。
     * .xhtv 必须通过 TS 同步字节校验、local.m3u8 必须是含分片声明的媒体列表，
     * 防止旧版本遗留的垃圾文件（master 列表被当分片下载）导致播放器一直转圈。
     * 找不到有效文件返回 null。
     */
    public static java.io.File findLocalVideo(String downloadPath) {
        try {
            java.io.File dir = new java.io.File(downloadPath);
            if (!dir.exists() || !dir.isDirectory()) return null;
            java.io.File[] files = dir.listFiles();
            if (files == null) return null;
            java.io.File m3u8 = null, video = null;
            for (java.io.File f : files) {
                String name = f.getName().toLowerCase();
                if (name.endsWith(".xhtv")) {
                    if (isValidTs(f)) return f; // 合并单文件优先，但必须是合法 TS
                } else if (name.equals("local.m3u8") && m3u8 == null) {
                    m3u8 = f;
                } else if ((name.endsWith(".mp4") || name.endsWith(".mkv")) && !name.endsWith(".tmp") && video == null) {
                    video = f;
                }
            }
            if (m3u8 != null && isValidMediaPlaylist(m3u8)) return m3u8;
            return video;
        } catch (Throwable t) {
            return null;
        }
    }

    /** TS 流校验：MPEG-TS 每个 188 字节包以 0x47 同步字节开头，校验首包 */
    private static boolean isValidTs(java.io.File f) {
        java.io.InputStream is = null;
        try {
            byte[] head = new byte[188];
            is = new java.io.FileInputStream(f);
            int got = 0, n;
            while (got < head.length && (n = is.read(head, got, head.length - got)) >= 0) got += n;
            return got == head.length && head[0] == 0x47;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (is != null) is.close();
            } catch (Exception ignored) {}
        }
    }

    /** local.m3u8 必须是含分片声明的媒体列表（master 嵌套列表在分片模式下无法播放） */
    private static boolean isValidMediaPlaylist(java.io.File f) {
        try {
            StringBuilder sb = new StringBuilder();
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null && sb.length() < 65536) sb.append(line).append('\n');
            r.close();
            String s = sb.toString();
            return s.contains("#EXTINF") && !s.contains("#EXT-X-STREAM-INF");
        } catch (Exception e) {
            return false;
        }
    }
}

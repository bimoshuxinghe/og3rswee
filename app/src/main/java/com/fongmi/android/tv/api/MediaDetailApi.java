package com.fongmi.android.tv.api;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.player.Source;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class MediaDetailApi {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String FEINIU = "feiniu";
    private static final String EMBY = "emby";
    private static final String JELLYFIN = "jellyfin";

    private final Site site;
    private final Config config;

    public static Result detail(Site site, String key, String id) throws Exception {
        MediaDetailApi api = new MediaDetailApi(site);
        Vod vod = api.isFeiniu() ? api.feiniuDetail(id) : api.embyDetail(id);
        vod.setSite(site);
        Source.get().parse(vod.setFlags());
        return Result.vod(vod);
    }

    public static String resolvePlayUrl(Site site, String key, String id) throws Exception {
        if (!id.startsWith("media://")) return id;
        MediaDetailApi api = new MediaDetailApi(site);
        Uri uri = Uri.parse(id);
        if ("feiniu".equals(uri.getHost())) return api.feiniuPlayUrl(uri.getQueryParameter("item_guid"));
        if ("emby".equals(uri.getHost()) || "jellyfin".equals(uri.getHost())) return api.embyPlayUrl(uri.getQueryParameter("item_id"));
        return id;
    }

    private MediaDetailApi(Site site) {
        this.site = site;
        this.config = Config.from(site);
    }

    private boolean isFeiniu() {
        return FEINIU.equals(config.type);
    }

    private Vod feiniuDetail(String id) throws Exception {
        ensureFeiniuAuth();
        JsonObject info = feiniuPlayInfo(stripFeiniuId(id));
        JsonObject item = object(info, "item");
        String type = first(info, item, "type", "item_type");
        String parentGuid = first(info, item, "parent_guid", "parentGuid");
        String detailName = detailName(info, item, type);
        List<JsonObject> episodes = new ArrayList<>();
        if ("Episode".equalsIgnoreCase(type) && !TextUtils.isEmpty(parentGuid)) episodes.addAll(objects(requestFeiniuGet("episode/list/" + parentGuid)));
        else if ("Video".equalsIgnoreCase(type) && !TextUtils.isEmpty(parentGuid)) episodes.addAll(objects(requestFeiniuPost("item/list", feiniuListParams(parentGuid))));
        if (episodes.isEmpty()) episodes.add(item.size() == 0 ? info : item);

        Vod vod = new Vod();
        vod.setId(id);
        vod.setName(detailName);
        vod.setPic(feiniuImage(first(item, info, "posters", "poster", "poster_url", "cover", "image", "background", "still_path")));
        vod.setYear(first(item, info, "year", "release_year", "air_date"));
        vod.setRemarks(episodes.size() > 1 ? "共 " + episodes.size() + " 集" : remark(type));
        vod.setContent(first(item, info, "overview", "summary", "description", "intro", "plot"));
        vod.setPlayFrom(site.getName());
        vod.setPlayUrl(feiniuPlayList(episodes));
        return vod;
    }

    private String feiniuPlayList(List<JsonObject> episodes) {
        StringBuilder builder = new StringBuilder();
        for (JsonObject episode : episodes) {
            String itemGuid = findString(episode, "guid", "item_guid", "play_item_guid", "id");
            if (TextUtils.isEmpty(itemGuid)) continue;
            if (builder.length() > 0) builder.append("#");
            builder.append(escapeEpisode(episodeName(episode))).append("$").append(buildFeiniuPlayId(itemGuid));
        }
        return builder.toString();
    }

    private String feiniuPlayUrl(String itemGuid) throws Exception {
        if (TextUtils.isEmpty(itemGuid)) throw new IllegalStateException("飞牛影视缺少播放项目 ID");
        ensureFeiniuAuth();
        JsonObject playInfo = feiniuPlayInfo(itemGuid);
        String mediaGuid = findString(playInfo, "media_guid", "mediaGuid", "video_guid", "videoGuid");
        if (!TextUtils.isEmpty(mediaGuid)) return feiniuUrl("media/range/" + mediaGuid);
        String playLink = findString(playInfo, "play_link", "playLink", "direct_link", "directLink", "url");
        if (!TextUtils.isEmpty(playLink)) return fullUrl(playLink);
        throw new IllegalStateException("飞牛影视没有返回播放地址");
    }

    private JsonObject feiniuPlayInfo(String itemGuid) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("item_guid", itemGuid);
        JsonElement data = requestFeiniuPost("play/info", params);
        return data != null && data.isJsonObject() ? data.getAsJsonObject() : new JsonObject();
    }

    private JsonObject feiniuListParams(String parentGuid) {
        JsonObject params = new JsonObject();
        params.addProperty("parent_guid", parentGuid);
        params.addProperty("exclude_folder", 1);
        params.addProperty("sort_column", "sort_title");
        params.addProperty("sort_type", "ASC");
        return params;
    }

    private Vod embyDetail(String id) throws Exception {
        ensureAuth();
        JsonObject item = requestJson(new Request.Builder().url(url("Users/" + config.userId + "/Items/" + id).addQueryParameter("Fields", "PrimaryImageAspectRatio,MediaSources,Overview,ProductionYear,SeriesPrimaryImageTag").build()).headers(config.headers()).get().build());
        String type = string(item, "Type");
        List<JsonObject> episodes = embyEpisodes(item, type);
        if (episodes.isEmpty()) episodes.add(item);

        Vod vod = new Vod();
        vod.setId(id);
        vod.setName(embyDetailName(item, type));
        vod.setPic(embyImage(item));
        vod.setYear(string(item, "ProductionYear"));
        vod.setRemarks(episodes.size() > 1 ? "共 " + episodes.size() + " 集" : embyRemark(type));
        vod.setContent(string(item, "Overview"));
        vod.setPlayFrom(site.getName());
        vod.setPlayUrl(embyPlayList(episodes));
        return vod;
    }

    private List<JsonObject> embyEpisodes(JsonObject item, String type) throws Exception {
        if ("Episode".equals(type) && !TextUtils.isEmpty(string(item, "SeriesId"))) {
            return objects(requestElement(new Request.Builder().url(url("Users/" + config.userId + "/Items")
                    .addQueryParameter("SeriesId", string(item, "SeriesId"))
                    .addQueryParameter("Recursive", "true")
                    .addQueryParameter("IncludeItemTypes", "Episode")
                    .addQueryParameter("Fields", "PrimaryImageAspectRatio,MediaSources,Overview,ProductionYear,SeriesPrimaryImageTag")
                    .addQueryParameter("SortBy", "ParentIndexNumber,IndexNumber")
                    .addQueryParameter("SortOrder", "Ascending")
                    .build()).headers(config.headers()).get().build()));
        }
        if ("Series".equals(type) || "Season".equals(type) || "Folder".equals(type) || "CollectionFolder".equals(type)) {
            return objects(requestElement(new Request.Builder().url(url("Users/" + config.userId + "/Items")
                    .addQueryParameter("ParentId", string(item, "Id"))
                    .addQueryParameter("Recursive", "true")
                    .addQueryParameter("IncludeItemTypes", "Movie,Episode,Video")
                    .addQueryParameter("Fields", "PrimaryImageAspectRatio,MediaSources,Overview,ProductionYear,SeriesPrimaryImageTag")
                    .addQueryParameter("SortBy", "ParentIndexNumber,IndexNumber,SortName")
                    .addQueryParameter("SortOrder", "Ascending")
                    .build()).headers(config.headers()).get().build()));
        }
        return new ArrayList<>();
    }

    private String embyPlayList(List<JsonObject> episodes) {
        StringBuilder builder = new StringBuilder();
        for (JsonObject episode : episodes) {
            String itemId = string(episode, "Id");
            if (TextUtils.isEmpty(itemId)) continue;
            if (builder.length() > 0) builder.append("#");
            builder.append(escapeEpisode(embyName(episode, string(episode, "Type")))).append("$").append(buildEmbyPlayId(itemId));
        }
        return builder.toString();
    }

    private String embyPlayUrl(String itemId) {
        return url("Videos/" + itemId + "/stream").addQueryParameter("static", "true").addQueryParameter("api_key", config.token).build().toString();
    }

    private void ensureAuth() throws Exception {
        if (isFeiniu()) {
            ensureFeiniuAuth();
            return;
        }
        if (!TextUtils.isEmpty(config.token) && !TextUtils.isEmpty(config.userId)) return;
        if (!TextUtils.isEmpty(config.user) && !TextUtils.isEmpty(config.pass)) authByPassword();
        if (!TextUtils.isEmpty(config.token) && TextUtils.isEmpty(config.userId)) loadUser();
        if (TextUtils.isEmpty(config.token) || TextUtils.isEmpty(config.userId)) throw new IllegalStateException("媒体库登录信息不完整");
        site.setExt(config.toExt());
        site.save();
    }

    private void ensureFeiniuAuth() throws Exception {
        if (!TextUtils.isEmpty(config.token)) {
            try {
                requestFeiniuGet("user/info");
                return;
            } catch (Exception e) {
                config.token = "";
            }
        }
        if (TextUtils.isEmpty(config.user) || TextUtils.isEmpty(config.pass)) throw new IllegalStateException("飞牛影视需要填写账号密码");
        JsonObject params = new JsonObject();
        params.addProperty("username", config.user);
        params.addProperty("password", config.pass);
        params.addProperty("app_name", "trimemedia-web");
        JsonElement data = requestFeiniuPost("login", params);
        config.token = findString(data, "token");
        if (TextUtils.isEmpty(config.token)) throw new IllegalStateException("飞牛影视登录失败：未返回 token");
        site.setExt(config.toExt());
        site.save();
    }

    private void authByPassword() throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("Username", config.user);
        params.addProperty("Pw", config.pass);
        JsonObject result = requestJson(new Request.Builder()
                .url(url("Users/AuthenticateByName").build())
                .headers(config.headers())
                .post(RequestBody.create(params.toString(), JSON))
                .build());
        config.token = string(result, "AccessToken");
        if (result.has("User")) config.userId = string(result.getAsJsonObject("User"), "Id");
    }

    private void loadUser() throws Exception {
        JsonArray users = requestElement(new Request.Builder().url(url("Users").build()).headers(config.headers()).get().build()).getAsJsonArray();
        if (users.size() > 0) config.userId = string(users.get(0).getAsJsonObject(), "Id");
    }

    private JsonElement requestFeiniuGet(String path) throws Exception {
        return requestFeiniuData(new Request.Builder().url(feiniuUrl(path)).headers(feiniuHeaders(path, "")).get().build());
    }

    private JsonElement requestFeiniuPost(String path, JsonObject params) throws Exception {
        params.addProperty("nonce", String.valueOf(System.currentTimeMillis() % 100000000));
        String data = params.toString();
        return requestFeiniuData(new Request.Builder().url(feiniuUrl(path)).headers(feiniuHeaders(path, data)).post(RequestBody.create(data, JSON)).build());
    }

    private JsonElement requestFeiniuData(Request request) throws Exception {
        JsonObject object = requestJson(request);
        int code = integer(object, "code");
        if (code != 0) throw new IllegalStateException("飞牛影视请求失败(" + code + ")：" + string(object, "msg"));
        return object.has("data") && !object.get("data").isJsonNull() ? object.get("data") : object;
    }

    private Headers feiniuHeaders(String path, String data) throws Exception {
        Headers.Builder builder = new Headers.Builder().add("Accept", "application/json").add("Content-Type", "application/json").add("Cookie", "mode=relay");
        for (Map.Entry<String, String> entry : FeiniuAuth.headers(config.token, FeiniuAuth.apiPath(path), data).entrySet()) builder.set(entry.getKey(), entry.getValue());
        return builder.build();
    }

    private JsonObject requestJson(Request request) throws Exception {
        return requestElement(request).getAsJsonObject();
    }

    private JsonElement requestElement(Request request) throws Exception {
        try (Response response = OkHttp.client().newCall(request).execute(); ResponseBody body = response.body()) {
            if (!response.isSuccessful()) throw new IllegalStateException("服务器请求失败：" + response.code());
            return JsonParser.parseString(body == null ? "" : body.string());
        }
    }

    private HttpUrl.Builder url(String path) {
        HttpUrl base = HttpUrl.parse(config.host);
        if (base == null) throw new IllegalStateException("服务器地址不正确");
        HttpUrl.Builder builder = base.newBuilder();
        for (String segment : path.split("/")) builder.addPathSegment(segment);
        return builder;
    }

    private String feiniuUrl(String path) {
        return fullUrl(FeiniuAuth.apiPath(path));
    }

    private String feiniuImage(String url) {
        return FeiniuAuth.withHeaders(fullUrl(url), config.token);
    }

    private String fullUrl(String value) {
        if (TextUtils.isEmpty(value) || value.startsWith("http://") || value.startsWith("https://")) return value;
        HttpUrl base = HttpUrl.parse(config.host);
        if (base == null) return value;
        return base.newBuilder().encodedPath(value.startsWith("/") ? value : "/" + value).build().toString();
    }

    private String embyImage(JsonObject item) {
        String id = string(item, "Id");
        if ("Episode".equals(string(item, "Type")) && item.has("SeriesPrimaryImageTag")) id = string(item, "SeriesId");
        if (TextUtils.isEmpty(id)) return "";
        return url("Items/" + id + "/Images/Primary").addQueryParameter("fillWidth", "300").addQueryParameter("quality", "90").addQueryParameter("api_key", config.token).build().toString();
    }

    private String buildFeiniuPlayId(String itemGuid) {
        return new Uri.Builder().scheme("media").authority("feiniu").appendQueryParameter("item_guid", itemGuid).build().toString();
    }

    private String buildEmbyPlayId(String itemId) {
        return new Uri.Builder().scheme("media").authority(JELLYFIN.equals(config.type) ? "jellyfin" : "emby").appendQueryParameter("item_id", itemId).build().toString();
    }

    private String stripFeiniuId(String id) {
        if (id == null) return "";
        if (id.startsWith("feiniu:item:")) return id.substring("feiniu:item:".length());
        if (id.startsWith("feiniu:mdb:")) return id.substring("feiniu:mdb:".length());
        return id;
    }

    private String detailName(JsonObject info, JsonObject item, String type) {
        if ("Episode".equalsIgnoreCase(type)) {
            String series = first(item, info, "tv_title", "series_title", "SeriesName", "parent_title");
            if (!TextUtils.isEmpty(series)) return series;
        }
        return first(item, info, "title", "name", "tv_title", "filename", "file_name");
    }

    private String episodeName(JsonObject item) {
        String title = first(item, item, "title", "name", "tv_title", "filename", "file_name");
        int season = integer(item, "season_number");
        int episode = integer(item, "episode_number");
        if (season > 0 && episode > 0) return "S" + season + "E" + episode + " " + title;
        if (episode > 0) return "第" + episode + "集 " + title;
        return TextUtils.isEmpty(title) ? "播放" : title;
    }

    private String embyDetailName(JsonObject item, String type) {
        if ("Episode".equals(type) && !TextUtils.isEmpty(string(item, "SeriesName"))) return string(item, "SeriesName");
        return string(item, "Name");
    }

    private String embyName(JsonObject item, String type) {
        if (!"Episode".equals(type)) return string(item, "Name");
        String name = string(item, "Name");
        int season = integer(item, "ParentIndexNumber");
        int episode = integer(item, "IndexNumber");
        if (season > 0 && episode > 0) return "S" + season + "E" + episode + " " + name;
        if (episode > 0) return "第" + episode + "集 " + name;
        return name;
    }

    private String remark(String type) {
        if ("Movie".equalsIgnoreCase(type)) return "电影";
        if ("Episode".equalsIgnoreCase(type)) return "剧集";
        if ("Video".equalsIgnoreCase(type)) return "视频";
        return TextUtils.isEmpty(type) ? "媒体库" : type;
    }

    private String embyRemark(String type) {
        if ("Movie".equals(type)) return "电影";
        if ("Episode".equals(type)) return "剧集";
        if ("Series".equals(type)) return "电视剧";
        if ("Video".equals(type)) return "视频";
        return TextUtils.isEmpty(type) ? "媒体库" : type;
    }

    private List<JsonObject> objects(JsonElement element) {
        List<JsonObject> list = new ArrayList<>();
        JsonArray array = array(element, "list", "items", "Items", "records", "results", "data");
        for (int index = 0; index < array.size(); index++) if (array.get(index).isJsonObject()) list.add(array.get(index).getAsJsonObject());
        return list;
    }

    private JsonArray array(JsonElement element, String... names) {
        if (element == null || element.isJsonNull()) return new JsonArray();
        if (element.isJsonArray()) return element.getAsJsonArray();
        if (!element.isJsonObject()) return new JsonArray();
        JsonObject object = element.getAsJsonObject();
        for (String name : names) {
            if (object.has(name) && object.get(name).isJsonArray()) return object.getAsJsonArray(name);
            if (object.has(name) && object.get(name).isJsonObject()) {
                JsonArray child = array(object.get(name), names);
                if (child.size() > 0) return child;
            }
        }
        return new JsonArray();
    }

    private JsonObject object(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonObject() ? object.getAsJsonObject(name) : new JsonObject();
    }

    private String first(JsonObject first, JsonObject second, String... names) {
        String value = findString(first, names);
        return TextUtils.isEmpty(value) ? findString(second, names) : value;
    }

    private String findString(JsonElement element, String... names) {
        if (element == null || element.isJsonNull()) return "";
        if (element.isJsonPrimitive()) return element.getAsString();
        if (element.isJsonArray() && element.getAsJsonArray().size() > 0) return findString(element.getAsJsonArray().get(0), names);
        if (!element.isJsonObject()) return "";
        JsonObject object = element.getAsJsonObject();
        for (String name : names) {
            if (!object.has(name) || object.get(name).isJsonNull()) continue;
            JsonElement value = object.get(name);
            if (value.isJsonPrimitive()) {
                String text = value.getAsString();
                if (!TextUtils.isEmpty(text)) return text;
                continue;
            }
            if (value.isJsonArray() && value.getAsJsonArray().size() > 0) return findString(value.getAsJsonArray().get(0), "url", "path", "filename", "name");
            if (value.isJsonObject()) {
                String text = findString(value, "url", "path", "filename", "name");
                if (!TextUtils.isEmpty(text)) return text;
            }
        }
        return "";
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsString() : "";
    }

    private static int integer(JsonObject object, String name) {
        try {
            return object.has(name) && !object.get(name).isJsonNull() ? object.get(name).getAsInt() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String escapeEpisode(String value) {
        value = TextUtils.isEmpty(value) ? "播放" : value.trim();
        return value.replace("$", " ").replace("#", " ");
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static class Config {

        private String type;
        private String host;
        private String user;
        private String pass;
        private String token;
        private String userId;

        private static Config from(Site site) {
            Config config = new Config();
            Uri uri = Uri.parse(site.getExt().startsWith("media://") ? site.getExt() : "media://server?" + site.getExt());
            config.type = value(uri, "type");
            config.host = normalize(value(uri, "host"));
            config.user = value(uri, "user");
            config.pass = value(uri, "pass");
            config.token = value(uri, "token");
            config.userId = value(uri, "userId");
            if (TextUtils.isEmpty(config.token) && TextUtils.isEmpty(config.user) && !TextUtils.isEmpty(config.pass)) config.token = config.pass;
            return config;
        }

        private Headers headers() {
            Headers.Builder builder = new Headers.Builder()
                    .add("Accept", "application/json")
                    .add("X-Emby-Authorization", "MediaBrowser Client=\"XYS\", Device=\"Android TV\", DeviceId=\"XYS-TV\", Version=\"1.0.0\"");
            if (!TextUtils.isEmpty(token)) builder.add("X-Emby-Token", token);
            return builder.build();
        }

        private String toExt() {
            return new Uri.Builder()
                    .scheme("media").authority(type)
                    .appendQueryParameter("type", safe(type))
                    .appendQueryParameter("host", safe(host))
                    .appendQueryParameter("user", safe(user))
                    .appendQueryParameter("pass", safe(pass))
                    .appendQueryParameter("token", safe(token))
                    .appendQueryParameter("userId", safe(userId))
                    .build()
                    .toString();
        }

        private static String value(Uri uri, String key) {
            String value = uri.getQueryParameter(key);
            return value == null ? "" : value;
        }

        private static String normalize(String value) {
            value = safe(value);
            if (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            return value;
        }
    }
}

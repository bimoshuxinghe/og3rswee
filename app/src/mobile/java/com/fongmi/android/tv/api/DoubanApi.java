package com.fongmi.android.tv.api;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.Vod;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.HttpUrl;

public class DoubanApi {

    private static final String BASE = "https://m.douban.com/rexxar/api/v2";
    private static final String ZJRL_COS = "https://zjrl-1318856176.cos.accelerate.myqcloud.com";
    private static final String ZJRL_API = "https://api.thinkofuture.com";
    private static final String ZJRL_ID_PREFIX = "zjrl://";
    private static final int HOT_LIMIT = 20;
    private static final Map<String, String> HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0",
            "Referer", "https://movie.douban.com/",
            "Origin", "https://movie.douban.com"
    );
    private static final Map<String, String> ZJRL_HEADERS = Map.of(
            "User-Agent", "okhttp/4.12.0",
            "Accept", "application/json,text/plain,*/*",
            "authorization", "Pancha_Skandhah_Shunyata_Lakshana"
    );
    private static final Map<String, String> ZJRL_COS_HEADERS = Map.of(
            "User-Agent", "Mozilla/5.0",
            "Accept", "application/json,text/plain,*/*"
    );

    public static List<Vod> hot() {
        return hot("all", 1);
    }

    public static List<Vod> hot(String type, int page) {
        int start = Math.max(page - 1, 0) * HOT_LIMIT;
        if ("movie".equals(type)) return hot("movie", null, "movie", start);
        if ("tv".equals(type)) return hot("tv", null, "tv", start);
        if ("show".equals(type)) return hot("tv", "\u7efc\u827a", "show", start);
        if ("anime".equals(type)) return anime(page);
        if ("radar".equals(type)) return radar(page);
        return mix(hot("movie", null, "movie", start), hot("tv", null, "tv", start), hot("tv", "\u7efc\u827a", "show", start), anime(page), radar(page));
    }

    public static JSONObject detail(String id) throws Exception {
        return new JSONObject(OkHttp.string(BASE + "/subject/" + id, HEADERS));
    }

    public static boolean isZhuiJu(String id) {
        return id != null && id.startsWith(ZJRL_ID_PREFIX);
    }

    public static JSONObject zhuijuDetail(String rawId) throws Exception {
        ZhuiJuId id = ZhuiJuId.parse(rawId);
        return zhuijuDetail(id.id, id.isMovie);
    }

    private static JSONObject zhuijuDetail(String id, boolean isMovie) throws Exception {
        HttpUrl url = Objects.requireNonNull(HttpUrl.parse(ZJRL_API + "/tm")).newBuilder()
                .addQueryParameter("id", id)
                .addQueryParameter("isMovie", String.valueOf(isMovie))
                .build();
        return new JSONObject(OkHttp.string(url.toString(), ZJRL_HEADERS));
    }

    public static List<Photo> zhuijuPhotos(JSONObject object) {
        List<Photo> photos = new ArrayList<>();
        addPhoto(photos, zhuijuImage(object.optString("backdrop_path"), "w780"), 16, 9);
        addPhoto(photos, zhuijuImage(object.optString("poster_path"), "w342"), 2, 3);
        JSONArray seasons = object.optJSONArray("seasons");
        if (seasons != null) for (int i = 0; i < seasons.length(); i++) {
            JSONObject season = seasons.optJSONObject(i);
            if (season == null) continue;
            addPhoto(photos, zhuijuImage(season.optString("poster_path"), "w342"), 2, 3);
        }
        return photos;
    }

    public static List<Photo> photos(String id) {
        List<Photo> items = new ArrayList<>();
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(BASE + "/subject/" + id + "/photos")).newBuilder()
                    .addQueryParameter("type", "R")
                    .addQueryParameter("start", "0")
                    .addQueryParameter("count", "30")
                    .build();
            JSONArray array = new JSONObject(OkHttp.string(url.toString(), HEADERS)).optJSONArray("photos");
            if (array == null) return items;
            for (int i = 0; i < array.length(); i++) {
                Photo photo = Photo.objectFrom(array.optJSONObject(i));
                if (!photo.url.isEmpty()) items.add(photo);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return items;
    }

    private static List<Vod> anime(int page) {
        return page <= 1 ? animeHome() : animeSimilar(page);
    }

    private static List<Vod> radar(int page) {
        return page <= 1 ? zhuijuSection("10", "home1.json", "radar", "\u65b0\u5267\u96f7\u8fbe") : new ArrayList<>();
    }

    private static List<Vod> animeHome() {
        try {
            JSONArray array = new JSONArray(OkHttp.string(ZJRL_COS + "/home0.json", ZJRL_COS_HEADERS));
            for (int i = 0; i < array.length(); i++) {
                JSONObject section = array.optJSONObject(i);
                if (section == null || !"category".equals(section.optString("type"))) continue;
                JSONArray content = section.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject item = content.optJSONObject(j);
                    if (item == null || !"\u56fd\u6f2b".equals(item.optString("title"))) continue;
                    return zhuijuVods(item.optJSONArray("data"), "anime", "\u56fd\u6f2b");
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }

    private static List<Vod> zhuijuSection(String id, String file, String tag, String source) {
        try {
            JSONArray array = new JSONArray(OkHttp.string(ZJRL_COS + "/" + file, ZJRL_COS_HEADERS));
            for (int i = 0; i < array.length(); i++) {
                JSONObject section = array.optJSONObject(i);
                if (section == null || !id.equals(section.optString("id"))) continue;
                return zhuijuVods(section.optJSONArray("content"), tag, first(section.optString("title"), source));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }

    private static List<Vod> animeSimilar(int page) {
        try {
            HttpUrl url = Objects.requireNonNull(HttpUrl.parse(ZJRL_API + "/similar")).newBuilder()
                    .addQueryParameter("page", String.valueOf(page))
                    .addQueryParameter("country", "CN")
                    .addQueryParameter("genre", "16")
                    .build();
            return zhuijuVods(new JSONArray(OkHttp.string(url.toString(), ZJRL_HEADERS)), "anime", "\u56fd\u6f2b");
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ArrayList<>();
    }

    private static List<Vod> hot(String path, String category, String tag, int start) {
        List<Vod> items = new ArrayList<>();
        try {
            HttpUrl.Builder builder = Objects.requireNonNull(HttpUrl.parse(BASE + "/subject/recent_hot/" + path)).newBuilder()
                    .addQueryParameter("start", String.valueOf(start))
                    .addQueryParameter("limit", String.valueOf(HOT_LIMIT));
            if (category != null) builder.addQueryParameter("category", category);
            JSONArray array = new JSONObject(OkHttp.string(builder.build().toString(), HEADERS)).optJSONArray("items");
            if (array == null) return items;
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) continue;
                Vod vod = new Vod();
                vod.setId(object.optString("id"));
                vod.setName(object.optString("title"));
                vod.setPic(pic(object));
                vod.setTag(tag);
                if (!vod.getName().isEmpty() && !vod.getPic().isEmpty()) items.add(vod);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return items;
    }

    private static List<Vod> zhuijuVods(JSONArray array, String tag, String source) {
        List<Vod> items = new ArrayList<>();
        if (array == null) return items;
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) continue;
            String id = object.optString("id");
            boolean isMovie = object.optBoolean("isMovie");
            String name = first(object.optString("name"), object.optString("title"), object.optString("t1"));
            String pic = zhuijuImage(object.optString("poster_path"), "w342");
            if (TextUtils.isEmpty(id) || TextUtils.isEmpty(name) || TextUtils.isEmpty(pic)) continue;
            Vod vod = new Vod();
            vod.setId(ZJRL_ID_PREFIX + id + "/" + isMovie);
            vod.setName(name);
            vod.setPic(pic);
            vod.setRemarks("radar".equals(tag) ? radarRemark(id, isMovie) : first(object.optString("vod_remarks"), object.optString("t2"), source));
            vod.setTag(tag);
            items.add(vod);
        }
        return items;
    }

    private static String radarRemark(String id, boolean isMovie) {
        try {
            return airDateBadge(zhuijuAirDate(zhuijuDetail(id, isMovie)));
        } catch (Exception e) {
            e.printStackTrace();
            return "\u5f85\u5b9a";
        }
    }

    private static String zhuijuAirDate(JSONObject object) {
        JSONObject next = object.optJSONObject("next_episode_to_air");
        return first(object.optString("first_air_date"), object.optString("release_date"), next == null ? "" : next.optString("air_date"), object.optString("last_air_date"));
    }

    private static String airDateBadge(String date) {
        try {
            if (TextUtils.isEmpty(date) || date.length() < 10) return "\u5f85\u5b9a";
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            Calendar target = Calendar.getInstance();
            Calendar today = Calendar.getInstance();
            target.setTime(format.parse(date.substring(0, 10)));
            clearTime(target);
            clearTime(today);
            long diff = TimeUnit.MILLISECONDS.toDays(target.getTimeInMillis() - today.getTimeInMillis());
            if (diff < 0) return "\u5df2\u4e0a\u7ebf";
            if (diff == 0) return "\u4eca\u5929\u4e0a\u7ebf";
            if (diff == 1) return "\u660e\u5929\u4e0a\u7ebf";
            return "\u5269\u4f59" + diff + "\u5929";
        } catch (Exception e) {
            return "\u5f85\u5b9a";
        }
    }

    private static void clearTime(Calendar calendar) {
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
    }

    public static String pic(JSONObject object) {
        JSONObject pic = object.optJSONObject("pic");
        if (pic != null) {
            String large = pic.optString("large");
            if (!large.isEmpty()) return image(large);
            String normal = pic.optString("normal");
            if (!normal.isEmpty()) return image(normal);
        }
        return image(object.optString("cover_url", object.optString("cover")));
    }

    @SafeVarargs
    private static List<Vod> mix(List<Vod>... groups) {
        List<Vod> items = new ArrayList<>();
        int max = 0;
        for (List<Vod> group : groups) max = Math.max(max, group.size());
        for (int i = 0; i < max; i++) {
            for (List<Vod> group : groups) if (i < group.size()) items.add(group.get(i));
        }
        return items;
    }

    private static String image(String url) {
        if (url == null || url.isEmpty()) return "";
        return url + "@Referer=https://movie.douban.com/@User-Agent=Mozilla/5.0";
    }

    public static String zhuijuImage(String path, String size) {
        if (TextUtils.isEmpty(path)) return "";
        if (path.startsWith("http")) return path;
        return ZJRL_COS + "/" + size + (path.startsWith("/") ? path : "/" + path);
    }

    private static void addPhoto(List<Photo> photos, String url, int width, int height) {
        if (!TextUtils.isEmpty(url)) photos.add(new Photo(url, width, height));
    }

    private static String first(String... values) {
        for (String value : values) if (!TextUtils.isEmpty(value)) return value;
        return "";
    }

    private static String dec(String value) {
        try {
            return java.net.URLDecoder.decode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static class Photo {

        public final String url;
        public final int width;
        public final int height;

        private Photo(String url, int width, int height) {
            this.url = image(url);
            this.width = width;
            this.height = height;
        }

        public boolean isLand() {
            return width > height;
        }

        private static Photo objectFrom(JSONObject object) {
            if (object == null) return new Photo("", 0, 0);
            JSONObject image = object.optJSONObject("image");
            JSONObject large = image == null ? null : image.optJSONObject("large");
            JSONObject normal = image == null ? null : image.optJSONObject("normal");
            JSONObject data = large != null ? large : normal;
            if (data == null) return new Photo("", 0, 0);
            return new Photo(data.optString("url"), data.optInt("width"), data.optInt("height"));
        }
    }

    private static class ZhuiJuId {

        private final String id;
        private final boolean isMovie;

        private ZhuiJuId(String id, boolean isMovie) {
            this.id = id;
            this.isMovie = isMovie;
        }

        private static ZhuiJuId parse(String rawId) {
            String value = isZhuiJu(rawId) ? rawId.substring(ZJRL_ID_PREFIX.length()) : rawId;
            String[] split = value.split("/");
            String id = split.length > 0 ? dec(split[0]) : "";
            boolean isMovie = split.length > 1 && Boolean.parseBoolean(split[1]);
            return new ZhuiJuId(id, isMovie);
        }
    }
}

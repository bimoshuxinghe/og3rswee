package com.github.catvod.fallback;

import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * csp_AppDrama 协议的 app 内置兜底实现（电影天堂 / 橘汁 等短剧类 AppY 源）。
 * <p>
 * 背景：星河.jar 等第三方 spider jar 里的 AppDrama 依赖 app classloader 提供的运行环境，
 * 在部分构建/机型上会因初始化异常（zone 握手、NPE、依赖类签名差异等）导致站点无法加载。
 * 本类不依赖 jar，直接实现同一套协议，作为 JarLoader 加载失败时的回退：
 * <ul>
 *   <li>host 允许为空，通过 ext.site 动态解析 domain（best-effort，失败不阻断）；</li>
 *   <li>/api/v5/find/app/zone 握手 best-effort——实测部分服务端常年返回「RSA解密失败」，
 *       握手失败时回退用 ext.publicKey 签名，分类/搜索/详情/取流均能正常出数据；</li>
 *   <li>protobuf 采用手写 wire-format，不引入 protobuf-java 依赖。</li>
 * </ul>
 * 协议参考：星河.jar 反编译 + 已离线自测的等价 JS 实现字段号。
 */
public class AppDrama extends Spider {

    /** 服务端约定的 AES key/iv（JSON 参数体加密用） */
    private static final String PARAM_SECRET = "ed5fdsgucxumegqa";
    private static final String UA = "okhttp/3.12.1";
    private static final String MEDIA_SUFFIX = "(?i).*\\.(mp4|m3u8|flv|mkv|avi|ts|mov|mpd|m4a|wmv)(\\?.*)?$";

    private final SecureRandom random = new SecureRandom();

    private String host = "";
    private String publicKey = "";
    private String dynamicKey = "";     // zone 握手成功后拼接的动态公钥
    private String pkg = "";
    private String appName = "";
    private String version = "";
    private String decrypt = "0";
    private String dataKey = "";
    private String dataIv = "";
    private String androidId = "";

    private OkHttpClient client;

    // ==================== 初始化 ====================

    @Override
    public void init(Context context, String extend) {
        JSONObject ext = new JSONObject(extend == null || extend.isEmpty() ? "{}" : extend);
        host = ext.optString("host", "");
        publicKey = ext.optString("publicKey", "");
        pkg = ext.optString("pkg", "");
        appName = ext.optString("appName", "");
        version = ext.optString("version", "");
        decrypt = ext.optString("decrypt", "0");
        dataKey = ext.optString("dataKey", "");
        dataIv = ext.optString("dataIv", "");
        try {
            if (context != null) androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        } catch (Throwable ignored) {
        }
        if (androidId == null || androidId.isEmpty()) androidId = UUID.randomUUID().toString().replace("-", "").toUpperCase().substring(0, 16);
        resolveSite(ext.optString("site", ""));
        if (!host.isEmpty()) handshake();
        SpiderDebug.log("AppDrama(fallback) init host=" + host + " handshake=" + (dynamicKey.isEmpty() ? "fail(fallback-public-key)" : "ok"));
    }

    /** ext.site 指向的 txt 里带 domain 字段，解析失败保持 host 原值（可能为空） */
    private void resolveSite(String site) {
        if (site == null || site.isEmpty() || !site.startsWith("http")) return;
        try {
            String body = getBody(site);
            if (body == null || body.isEmpty()) return;
            String domain = new JSONObject(body).optString("domain", "");
            if (!domain.isEmpty()) host = domain.replaceAll("/+$", "");
        } catch (Throwable e) {
            SpiderDebug.log("AppDrama(fallback) resolveSite failed: " + e);
        }
    }

    /** zone 握手换取动态公钥；任何失败都不阻断后续请求（回退用 publicKey 签名） */
    private void handshake() {
        try {
            long ts = System.currentTimeMillis();
            String rnd = randomText(16);
            byte[] body = Pb.builder()
                    .num(1, ts)
                    .str(2, rsaBase64(String.valueOf(ts) + rnd, publicKey))
                    .str(3, randomText(16))
                    .str(4, rnd)
                    .str(5, randomText(16))
                    .build();
            byte[] resp = postProto(host + "/api/v5/find/app/zone", body);
            if (resp == null || resp.length == 0) return;
            Pb result = Pb.parse(resp);
            if (!result.has(3)) return;
            Pb rsa = Pb.parse(result.bytes(3));
            StringBuilder sb = new StringBuilder();
            for (int f : new int[]{2, 3, 4, 5}) sb.append(rsa.str(f));
            dynamicKey = sb.toString();
        } catch (Throwable e) {
            dynamicKey = "";
            SpiderDebug.log("AppDrama(fallback) handshake failed (ignored): " + e);
        }
    }

    private String signKey() {
        return dynamicKey.isEmpty() ? publicKey : dynamicKey;
    }

    // ==================== Spider 接口 ====================

    @Override
    public String homeContent(boolean filter) {
        JSONObject result = new JSONObject();
        JSONArray types = new JSONArray();
        JSONObject filters = new JSONObject();
        try {
            String body = getBody(host + "/api/v3/drama/getCategory?orderBy=type_id", jsonHeaders());
            if (body != null && !body.isEmpty()) {
                JSONArray data = new JSONObject(body).optJSONArray("data");
                if (data != null) {
                    for (int i = 0; i < data.length(); i++) {
                        JSONObject item = data.getJSONObject(i);
                        String name = item.optString("name");
                        if ("公告".equals(name)) continue;
                        String id = item.optString("id");
                        JSONObject type = new JSONObject();
                        type.put("type_id", id);
                        type.put("type_name", name);
                        types.put(type);
                        String converUrl = item.optString("converUrl");
                        if (!converUrl.isEmpty()) {
                            JSONObject conf = new JSONObject(converUrl);
                            JSONArray values = new JSONArray();
                            for (String key : new String[]{"class", "lang", "area", "year", "extend_sort"}) {
                                String val = conf.optString(key);
                                if (val.isEmpty()) continue;
                                JSONObject f = new JSONObject();
                                f.put("key", key);
                                f.put("name", key);
                                JSONArray v = new JSONArray();
                                for (String one : val.split(",")) {
                                    if (one.isEmpty()) continue;
                                    JSONObject nv = new JSONObject();
                                    nv.put("n", one);
                                    nv.put("v", one);
                                    v.put(nv);
                                }
                                f.put("value", v);
                                values.put(f);
                            }
                            if (values.length() > 0) filters.put(id, values);
                        }
                    }
                }
            }
            result.put("class", types);
            if (filters.length() > 0) result.put("filters", filters);
        } catch (Throwable e) {
            SpiderDebug.log("AppDrama(fallback) homeContent: " + e);
        }
        return result.toString();
    }

    @Override
    public String homeVideoContent() {
        JSONArray list = new JSONArray();
        try {
            String body = getBody(host + "/api/ex/v3/security/tag/list", jsonHeaders());
            if (body != null && !body.isEmpty()) {
                // data 可能是明文 JSONArray（部分站点），也可能是加密后的字符串
                Object data = new JSONObject(body).opt("data");
                if (data instanceof JSONArray) {
                    parseTags((JSONArray) data, list);
                } else if (data instanceof String) {
                    String s = (String) data;
                    if (!s.isEmpty() && !"0".equals(decrypt)) {
                        try {
                            s = aesEcbDecrypt(aesEcbDecrypt(s, dataKey), dataIv);
                        } catch (Throwable e) {
                            SpiderDebug.log("AppDrama(fallback) tag/list decrypt failed, use raw: " + e);
                        }
                    }
                    if (!s.isEmpty()) parseTags(new JSONArray(s), list);
                }
            }
        } catch (Throwable e) {
            SpiderDebug.log("AppDrama(fallback) homeVideoContent: " + e);
        }
        return wrapList(list);
    }

    private static void parseTags(JSONArray tags, JSONArray list) {
        for (int i = 0; i < tags.length(); i++) {
            JSONArray sections = tags.getJSONObject(i).optJSONArray("sections");
            if (sections == null) continue;
            for (int j = 0; j < sections.length(); j++) {
                JSONArray vods = sections.getJSONObject(j).optJSONArray("vodList");
                if (vods == null) continue;
                for (int k = 0; k < vods.length(); k++) {
                    JSONObject v = vods.getJSONObject(k);
                    list.put(vod(String.valueOf(v.optLong("id", 0)), v.optString("name"), pic(v.optJSONObject("coverImage")), v.optString("remark")));
                }
            }
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        JSONArray list = new JSONArray();
        try {
            HashMap<String, String> params = new HashMap<>();
            params.put("pagesize", "21");
            params.put("typeId1", tid == null ? "" : tid);
            params.put("page", pg == null ? "1" : pg);
            params.put("vodOrderBy", extend != null && extend.get("extend_sort") != null ? extend.get("extend_sort") : "最新");
            params.put("vodArea", extend != null && extend.get("area") != null ? extend.get("area") : "");
            params.put("vodLang", extend != null && extend.get("lang") != null ? extend.get("lang") : "");
            params.put("vodClass", extend != null && extend.get("class") != null ? extend.get("class") : "");
            params.put("vodYear", extend != null && extend.get("year") != null ? extend.get("year") : "");
            byte[] resp = postProto(host + "/api/proto/v5/drama/category", secureRequest(params));
            Pb result = Pb.parse(resp);
            if (result.has(3)) {
                Pb page = Pb.parse(result.bytes(3));
                for (byte[] item : page.list(1)) list.put(dramaItem(Pb.parse(item)));
            }
        } catch (Throwable e) {
            SpiderDebug.log("AppDrama(fallback) categoryContent: " + e);
        }
        return wrapList(list);
    }

    @Override
    public String detailContent(List<String> ids) {
        JSONObject vod = new JSONObject();
        try {
            String id = ids == null || ids.isEmpty() ? "" : ids.get(0);
            byte[] resp = postProto(host + "/api/proto/v5/drama/getDetail", secureRequest(singleton("id", id)));
            Pb result = Pb.parse(resp);
            if (result.has(3)) {
                Pb d = Pb.parse(result.bytes(3));
                vod.put("vod_id", id);
                vod.put("vod_name", d.str(9));
                vod.put("vod_pic", coverPic(d));
                vod.put("type_name", d.str(13));
                vod.put("vod_year", d.num(18) > 0 ? String.valueOf(d.num(18)) : "");
                vod.put("vod_area", d.str(1));
                vod.put("vod_remarks", d.str(26));
                vod.put("vod_actor", d.str(25));
                vod.put("vod_director", d.str(12));
                vod.put("vod_content", firstNonEmpty(d.str(6), d.str(7)));
                LinkedHashMap<String, List<String>> routes = new LinkedHashMap<>();
                for (byte[] item : d.list(29)) {
                    Pb v = Pb.parse(item);
                    // 字段2=集数序号，字段3=标题（"正片"/"原声版"/集数），字段4=path，9=源code，10=源中文名
                    String title = firstNonEmpty(v.str(3), v.str(2));
                    String path = v.str(4);
                    if (path == null || path.isEmpty()) continue;
                    String source = v.str(9);
                    String sourceCn = v.str(10);
                    if (sourceCn == null || sourceCn.isEmpty()) sourceCn = "橘汁";
                    String flag = path;
                    if (!path.matches(MEDIA_SUFFIX)) {
                        JSONObject play = new JSONObject();
                        play.put("vodPlayFrom", source == null ? "" : source);
                        play.put("playUrl", path);
                        flag = android.util.Base64.encodeToString(play.toString().getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
                    }
                    List<String> episodes = routes.computeIfAbsent(sourceCn, k -> new ArrayList<>());
                    String name = title == null || title.isEmpty() ? "第" + (episodes.size() + 1) + "集" : title;
                    episodes.add(name + "$" + flag);
                }
                List<String> froms = new ArrayList<>();
                List<String> urls = new ArrayList<>();
                for (Map.Entry<String, List<String>> e : routes.entrySet()) {
                    froms.add(e.getKey());
                    urls.add(TextUtils.join("#", e.getValue()));
                }
                vod.put("vod_play_from", TextUtils.join("$$$", froms));
                vod.put("vod_play_url", TextUtils.join("$$$", urls));
            }
        } catch (Throwable e) {
            SpiderDebug.log("AppDrama(fallback) detailContent: " + e);
        }
        return vod.toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        JSONObject result = new JSONObject();
        result.put("parse", 0);
        result.put("playUrl", "");
        try {
            String target = id == null ? "" : id;
            if (target.matches(MEDIA_SUFFIX)) {
                result.put("url", target);
                return result.toString();
            }
            JSONObject payload = new JSONObject(new String(android.util.Base64.decode(target, android.util.Base64.DEFAULT), StandardCharsets.UTF_8));
            HashMap<String, String> params = new HashMap<>();
            Iterator<String> keys = payload.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                params.put(key, payload.optString(key));
            }
            byte[] resp = postProto(host + "/api/proto/v5/videoUsableUrl", secureRequest(params));
            Pb api = Pb.parse(resp);
            if (api.has(3)) {
                Pb bean = Pb.parse(api.bytes(3));
                String url = bean.str(1);
                result.put("url", url);
                JSONObject headers = new JSONObject();
                for (byte[] entry : bean.list(6)) {
                    Pb e = Pb.parse(entry);
                    String k = e.str(1);
                    String v = e.str(2);
                    if (k != null && !k.isEmpty() && v != null) headers.put(k, v);
                }
                if (headers.length() > 0) result.put("header", headers);
            }
        } catch (Throwable e) {
            SpiderDebug.log("AppDrama(fallback) playerContent: " + e);
        }
        return result.toString();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        JSONArray list = new JSONArray();
        try {
            HashMap<String, String> params = new HashMap<>();
            params.put("searchKeys", key == null ? "" : key);
            params.put("page", pg == null ? "1" : pg);
            params.put("pagesize", "21");
            byte[] resp = postProto(host + "/api/proto/v5/drama/search", secureRequest(params));
            Pb result = Pb.parse(resp);
            if (result.has(3)) {
                Pb page = Pb.parse(result.bytes(3));
                for (byte[] item : page.list(1)) list.put(dramaItem(Pb.parse(item)));
            }
        } catch (Throwable e) {
            SpiderDebug.log("AppDrama(fallback) searchContent: " + e);
        }
        return wrapList(list);
    }

    // ==================== 协议细节 ====================

    /** 设备参数（对照真实 App 抓包字段；vApp = version 去点号，服务端校验「客户端版本不能为空」） */
    private JSONObject deviceParams() {
        JSONObject d = new JSONObject();
        try {
            String uuid = UUID.randomUUID().toString().replace("-", "").toUpperCase();
            d.put("country", "CN");
            d.put("vName", version);
            d.put("cpuId", "MT6893Z%2FCZA");
            d.put("young", 0);
            d.put("facturer", "Xiaomi");
            d.put("pkg", pkg);
            d.put("uuid", uuid);
            d.put("resolution", "1080x2272");
            d.put("mac", "02%3A00%3A00%3A00%3A00%3A00");
            d.put("abid", "397");
            d.put("model", "M2012K11AC");
            d.put("plat", "android");
            d.put("udid", uuid);
            d.put("dpi", "440");
            d.put("net", "1");
            d.put("lang", "zh");
            d.put("brand", "Redmi");
            d.put("density", "2.75");
            d.put("appName", appName);
            d.put("cpu", "arm64-v8a");
            d.put("chid", "10000");
            d.put("carrier", "%E8%81%94%E9%80%9A");
            d.put("_vOsCode", 33);
            d.put("vOs", "13");
            d.put("v", 1);
            d.put("tenantId", "");
            d.put("vApp", version.replace(".", ""));
            d.put("device", 0);
            d.put("androidID", androidId);
        } catch (Throwable ignored) {
        }
        return d;
    }

    /** proto 接口头：带 sig/sig2/sig3 签名 */
    private Map<String, String> protoHeaders() {
        JSONObject params = deviceParams();
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            long ts = System.currentTimeMillis();
            String rnd = randomText(16);
            String ecb = aesEcbBase64(String.valueOf(ts) + rnd, dataIv);
            params.put("sig", rsaBase64(String.valueOf(ts) + rnd + params.optString("vApp"), signKey()));
            params.put("random_str", rnd);
            params.put("timestamp", ts);
            params.put("sig2", ecb.substring(0, Math.min(8, ecb.length())));
            params.put("sig3", ecb.length() > 8 ? ecb.substring(8) : "");
            headers.put("User-Agent", UA);
            headers.put("Accept", "application/x-protobuf");
            headers.put("Content-Type", "application/x-protobuf");
            headers.put("publicParams", new JSONObject().put("paramsData", aesCbcHex(params.toString())).toString());
        } catch (Throwable e) {
            SpiderDebug.log("AppDrama(fallback) protoHeaders: " + e);
        }
        return headers;
    }

    /** JSON 接口头：无签名（getCategory / tag/list） */
    private Map<String, String> jsonHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        try {
            headers.put("User-Agent", UA);
            headers.put("Accept", "application/json");
            headers.put("Content-Type", "application/json; charset=utf-8");
            headers.put("publicParams", new JSONObject().put("paramsData", aesCbcHex(deviceParams().toString())).toString());
        } catch (Throwable ignored) {
        }
        return headers;
    }

    /** SecureRequest 请求体：{1:aes1(20字节), 2:aes2, 3:fakestr, 4:ts, 5:randomStr8} */
    private byte[] secureRequest(HashMap<String, String> params) {
        long ts = System.currentTimeMillis();
        String rnd = randomText(8);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (e.getValue() == null || e.getValue().isEmpty()) continue;
            if (sb.length() > 0) sb.append("&");
            sb.append(e.getKey()).append("=").append(e.getValue());
        }
        String enc = rnd + aesEcbBase64(sb + String.valueOf(ts), dataKey);
        return Pb.builder()
                .str(1, enc.substring(0, Math.min(20, enc.length())))
                .str(2, enc.length() > 20 ? enc.substring(20) : "")
                .str(3, randomText(20))
                .num(4, ts)
                .str(5, rnd)
                .build();
    }

    // ==================== 加解密 ====================

    private static String aesEcbBase64(String text, String key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
            return android.util.Base64.encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP);
        } catch (Throwable e) {
            throw new RuntimeException("aesEcbBase64", e);
        }
    }

    private static String aesEcbDecrypt(String base64, String key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "AES"));
            return new String(cipher.doFinal(android.util.Base64.decode(base64, android.util.Base64.DEFAULT)), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            throw new RuntimeException("aesEcbDecrypt", e);
        }
    }

    private static String aesCbcHex(String text) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(PARAM_SECRET.getBytes(StandardCharsets.UTF_8), "AES"), new IvParameterSpec(PARAM_SECRET.getBytes(StandardCharsets.UTF_8)));
            StringBuilder sb = new StringBuilder();
            for (byte b : cipher.doFinal(text.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable e) {
            throw new RuntimeException("aesCbcHex", e);
        }
    }

    private static String rsaBase64(String text, String publicKey) {
        try {
            PublicKey key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(android.util.Base64.decode(publicKey, android.util.Base64.DEFAULT)));
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, key);
            return android.util.Base64.encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)), android.util.Base64.NO_WRAP);
        } catch (Throwable e) {
            throw new RuntimeException("rsaBase64", e);
        }
    }

    private String randomText(int len) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len - 1; i++) sb.append("1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".charAt(random.nextInt(62)));
        return sb.append("=").toString();
    }

    // ==================== 网络 ====================

    private OkHttpClient client() {
        if (client == null) {
            client = new OkHttpClient.Builder()
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .writeTimeout(30, TimeUnit.SECONDS)
                    .hostnameVerifier((hostname, session) -> true)
                    .build();
        }
        return client;
    }

    private String getBody(String url) {
        if (url == null || !url.startsWith("http")) return "";
        try (Response res = client().newCall(new Request.Builder().url(url).header("User-Agent", UA).build()).execute()) {
            return res.body() == null ? "" : res.body().string();
        } catch (Throwable e) {
            return "";
        }
    }

    private String getBody(String url, Map<String, String> headers) {
        if (url == null || !url.startsWith("http")) return "";
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> e : headers.entrySet()) builder.header(e.getKey(), e.getValue());
        try (Response res = client().newCall(builder.build()).execute()) {
            return res.body() == null ? "" : res.body().string();
        } catch (Throwable e) {
            SpiderDebug.log("AppDrama(fallback) GET " + url + ": " + e);
            return "";
        }
    }

    private byte[] postProto(String url, byte[] body) {
        if (url == null || !url.startsWith("http") || body == null) return new byte[0];
        Request.Builder builder = new Request.Builder().url(url).post(RequestBody.create(MediaType.get("application/x-protobuf"), body));
        for (Map.Entry<String, String> e : protoHeaders().entrySet()) builder.header(e.getKey(), e.getValue());
        try (Response res = client().newCall(builder.build()).execute()) {
            if (res.body() == null) return new byte[0];
            InputStream in = res.body().byteStream();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toByteArray();
        } catch (Throwable e) {
            SpiderDebug.log("AppDrama(fallback) POST " + url + ": " + e);
            return new byte[0];
        }
    }

    // ==================== protobuf 手写 wire format ====================

    /** 极简 protobuf 读写：仅覆盖本协议用到的 varint / length-delimited */
    private static class Pb {
        private final Map<Integer, List<Object>> fields = new HashMap<>();

        static Builder builder() {
            return new Builder();
        }

        static class Builder {
            private final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();

            Builder num(int field, long value) {
                writeVarint(field << 3);
                writeVarint(value);
                return this;
            }

            Builder str(int field, String value) {
                byte[] b = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                writeVarint(field << 3 | 2);
                writeVarint(b.length);
                out.write(b, 0, b.length);
                return this;
            }

            Builder bytes(int field, byte[] b) {
                writeVarint(field << 3 | 2);
                writeVarint(b.length);
                out.write(b, 0, b.length);
                return this;
            }

            private void writeVarint(long v) {
                while (true) {
                    if ((v & ~0x7FL) == 0) {
                        out.write((int) v);
                        return;
                    }
                    out.write((int) ((v & 0x7F) | 0x80));
                    v >>>= 7;
                }
            }

            byte[] build() {
                return out.toByteArray();
            }
        }

        static Pb parse(byte[] data) {
            Pb pb = new Pb();
            if (data == null) return pb;
            int i = 0;
            try {
                while (i < data.length) {
                    long tag = readVarint(data, i)[0];
                    i += readVarint(data, i)[1];
                    int field = (int) (tag >>> 3);
                    int wire = (int) (tag & 7);
                    if (wire == 0) {
                        long[] v = readVarint(data, i);
                        i += v[1];
                        pb.fields.computeIfAbsent(field, k -> new ArrayList<>()).add(v[0]);
                    } else if (wire == 2) {
                        long[] len = readVarint(data, i);
                        i += len[1];
                        int l = (int) len[0];
                        if (i + l > data.length) break;
                        byte[] chunk = new byte[l];
                        System.arraycopy(data, i, chunk, 0, l);
                        i += l;
                        pb.fields.computeIfAbsent(field, k -> new ArrayList<>()).add(chunk);
                    } else if (wire == 5) { // fixed32
                        if (i + 4 > data.length) break;
                        long v = (data[i] & 0xFFL) | (data[i + 1] & 0xFFL) << 8 | (data[i + 2] & 0xFFL) << 16 | (data[i + 3] & 0xFFL) << 24;
                        i += 4;
                        pb.fields.computeIfAbsent(field, k -> new ArrayList<>()).add(v);
                    } else if (wire == 1) { // fixed64
                        if (i + 8 > data.length) break;
                        long v = 0;
                        for (int k = 7; k >= 0; k--) v = (v << 8) | (data[i + k] & 0xFFL);
                        i += 8;
                        pb.fields.computeIfAbsent(field, k -> new ArrayList<>()).add(v);
                    } else {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            return pb;
        }

        private static long[] readVarint(byte[] data, int i) {
            long result = 0;
            int shift = 0, pos = i;
            while (pos < data.length) {
                byte b = data[pos++];
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return new long[]{result, pos - i};
        }

        boolean has(int field) {
            return fields.containsKey(field);
        }

        String str(int field) {
            List<Object> list = fields.get(field);
            if (list == null || list.isEmpty()) return "";
            Object v = list.get(0);
            return v instanceof byte[] ? new String((byte[]) v, StandardCharsets.UTF_8) : String.valueOf(v);
        }

        long num(int field) {
            List<Object> list = fields.get(field);
            if (list == null || list.isEmpty()) return 0;
            Object v = list.get(0);
            return v instanceof Long ? (Long) v : 0;
        }

        byte[] bytes(int field) {
            List<Object> list = fields.get(field);
            if (list == null || list.isEmpty()) return new byte[0];
            Object v = list.get(0);
            return v instanceof byte[] ? (byte[]) v : new byte[0];
        }

        List<byte[]> list(int field) {
            List<byte[]> out = new ArrayList<>();
            List<Object> list = fields.get(field);
            if (list != null) for (Object v : list) if (v instanceof byte[]) out.add((byte[]) v);
            return out;
        }
    }

    // ==================== 结果组装 ====================

    private static JSONObject vod(String id, String name, String pic, String remark) {
        JSONObject v = new JSONObject();
        try {
            v.put("vod_id", id == null ? "" : id);
            v.put("vod_name", name == null ? "" : name);
            v.put("vod_pic", pic == null ? "" : pic);
            v.put("vod_remarks", remark == null ? "" : remark);
        } catch (Throwable ignored) {
        }
        return v;
    }

    private static String pic(JSONObject cover) {
        if (cover == null) return "";
        String pic = cover.optString("thumbnailPath", cover.optString("path", ""));
        return pic == null ? "" : pic;
    }

    /** DramaBean: id=3(varint) name=5 cover=2{thumbnailPath=2,path=1} remark=13 */
    private static JSONObject dramaItem(Pb d) {
        return vod(String.valueOf(d.num(3)), d.str(5), picOf(d), d.str(13));
    }

    private static String picOf(Pb d) {
        try {
            if (!d.has(2)) return "";
            Pb cover = Pb.parse(d.bytes(2));
            String thumb = cover.str(2);
            return !thumb.isEmpty() ? thumb : cover.str(1);
        } catch (Throwable e) {
            return "";
        }
    }

    /** DramaDetailBean: cover=2 */
    private static String coverPic(Pb d) {
        try {
            if (!d.has(2)) return "";
            Pb cover = Pb.parse(d.bytes(2));
            String thumb = cover.str(2);
            return !thumb.isEmpty() ? thumb : cover.str(1);
        } catch (Throwable e) {
            return "";
        }
    }

    private static String wrapList(JSONArray list) {
        JSONObject result = new JSONObject();
        try {
            result.put("list", list);
        } catch (Throwable ignored) {
        }
        return result.toString();
    }

    private static String firstNonEmpty(String a, String b) {
        return a != null && !a.isEmpty() ? a : (b == null ? "" : b);
    }

    private static HashMap<String, String> singleton(String k, String v) {
        HashMap<String, String> map = new HashMap<>();
        map.put(k, v == null ? "" : v);
        return map;
    }
}

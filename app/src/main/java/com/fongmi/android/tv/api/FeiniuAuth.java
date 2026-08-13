package com.fongmi.android.tv.api;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

public class FeiniuAuth {

    private static final String API_KEY = "NDzZTVxnRKP8Z0jXg1VAMonaG8akvh";
    private static final String API_SECRET = "16CCEB3D-AB42-077D-36A1-F355324E4237";

    public static Map<String, String> headers(String token, String path) throws Exception {
        return headers(token, path, "");
    }

    public static Map<String, String> headers(String token, String path, String data) throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "*/*");
        headers.put("Cookie", "mode=relay");
        headers.put("Authx", authx(path, data));
        if (!TextUtils.isEmpty(token)) headers.put("Authorization", token);
        return headers;
    }

    public static String withHeaders(String url, String token) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(token)) return url;
        String path = path(url);
        if (TextUtils.isEmpty(path)) return url;
        try {
            return url + "@Headers=" + App.gson().toJson(headers(token, path));
        } catch (Exception e) {
            return url;
        }
    }

    public static String tokenFromExt(String ext) {
        try {
            Uri uri = Uri.parse(ext.startsWith("media://") ? ext : "media://server?" + ext);
            String token = uri.getQueryParameter("token");
            if (!TextUtils.isEmpty(token)) return token;
            String pass = uri.getQueryParameter("pass");
            String user = uri.getQueryParameter("user");
            return TextUtils.isEmpty(user) && !TextUtils.isEmpty(pass) ? pass : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static String apiPath(String path) {
        return path.startsWith("/") ? path : "/v/api/v1/" + path;
    }

    private static String path(String url) {
        try {
            return Uri.parse(url).getEncodedPath();
        } catch (Exception e) {
            return "";
        }
    }

    private static String authx(String path, String data) throws Exception {
        String nonce = randomDigits(6);
        String timestamp = String.valueOf(System.currentTimeMillis());
        String sign = md5(API_KEY + "_" + apiPath(path) + "_" + nonce + "_" + timestamp + "_" + md5(data) + "_" + API_SECRET);
        return "nonce=" + nonce + "&timestamp=" + timestamp + "&sign=" + sign;
    }

    private static String randomDigits(int length) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < length; index++) builder.append(ThreadLocalRandom.current().nextInt(10));
        return builder.toString();
    }

    private static String md5(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        for (byte item : bytes) builder.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return builder.toString();
    }
}

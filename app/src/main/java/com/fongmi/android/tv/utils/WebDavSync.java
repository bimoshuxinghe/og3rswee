package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.net.Proxy;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class WebDavSync {

    private static final String FOLDER_NAME = "星落";

    /**
     * 补全 URL scheme：如果用户输入的 URL 没有 http:// 或 https:// 前缀，自动补上 https://
     */
    private static String normalizeUrl(String url) {
        if (TextUtils.isEmpty(url)) return url;
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "https://" + url;
        }
        return url;
    }

    /**
     * 创建不走代理的 OkHttpClient，避免应用内代理设置干扰 WebDAV 连接（如坚果云）
     */
    private static OkHttpClient noProxyClient(long timeoutMs) {
        try {
            javax.net.ssl.SSLContext sslContext = javax.net.ssl.SSLContext.getInstance("TLS");
            javax.net.ssl.TrustManager[] trustAll = new javax.net.ssl.TrustManager[]{
                new javax.net.ssl.X509TrustManager() {
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {}
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                }
            };
            sslContext.init(null, trustAll, new java.security.SecureRandom());
            return new OkHttpClient.Builder()
                    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .proxy(Proxy.NO_PROXY)
                    .hostnameVerifier((hostname, session) -> true)
                    .sslSocketFactory(sslContext.getSocketFactory(), (javax.net.ssl.X509TrustManager) trustAll[0])
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .retryOnConnectionFailure(true)
                    .build();
        } catch (Exception e) {
            return new OkHttpClient.Builder()
                    .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .proxy(Proxy.NO_PROXY)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
        }
    }

    private static String getFolderUrl(String url) {
        if (!url.endsWith("/")) url += "/";
        return url + URLEncoder.encode(FOLDER_NAME, StandardCharsets.UTF_8) + "/";
    }

    private static String getBackupUrl(String url) {
        return getFolderUrl(url) + "fongmi_backup.json";
    }

    /**
     * 空请求体（用于 PROPFIND、MKCOL 等 WebDAV 方法，OkHttp 要求非 GET/HEAD 方法必须有 body）
     */
    private static RequestBody emptyBody() {
        return RequestBody.create(new byte[0], null);
    }

    /**
     * 创建星落文件夹（MKCOL），已存在则忽略
     */
    private static boolean createFolder(String url, String user, String pass) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) return false;
        try {
            String folderUrl = getFolderUrl(url);
            Request request = new Request.Builder()
                    .url(folderUrl)
                    .header("Authorization", Credentials.basic(user, pass))
                    .method("MKCOL", emptyBody())
                    .build();
            Response response = noProxyClient(5000).newCall(request).execute();
            int code = response.code();
            response.close();
            android.util.Log.d("WebDavSync", "createFolder: code=" + code);
            // 201=创建成功, 405=已存在(MKCOL已支持), 301=重定向 都视为成功
            return code == 201 || code == 200 || code == 405;
        } catch (Exception e) {
            android.util.Log.e("WebDavSync", "createFolder error: " + e.getMessage(), e);
            return false;
        }
    }

    public static void test(String url, String user, String pass, com.fongmi.android.tv.impl.Callback callback) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            App.post(callback::error);
            return;
        }
        url = normalizeUrl(url);
        if (!url.endsWith("/")) url += "/";

        final String finalUrl = url;
        final String finalUser = user;
        final String finalPass = pass;

        Task.execute(() -> {
            try {
                android.util.Log.d("WebDavSync", "test: url=" + finalUrl + ", user=" + finalUser);

                // 第一步：用 PROPFIND 测试认证（OkHttp 需要非 null body）
                Request propfindRequest = new Request.Builder()
                        .url(finalUrl)
                        .header("Authorization", Credentials.basic(finalUser, finalPass))
                        .method("PROPFIND", emptyBody())
                        .header("Depth", "0")
                        .header("Content-Type", "application/xml; charset=utf-8")
                        .build();

                Response response = noProxyClient(10000).newCall(propfindRequest).execute();
                int code = response.code();
                response.close();
                android.util.Log.d("WebDavSync", "test PROPFIND: code=" + code);

                // 401 = 认证失败
                if (code == 401) {
                    App.post(() -> callback.error("账号或密码错误 (401)，请检查用户名和应用密码"));
                    return;
                }

                // 207/200 = 认证成功且 WebDAV 可用
                // 403/404 = 可能路径问题但认证通过
                // 301/302 = 重定向（followRedirects 已开启，一般不会到这里）
                if (code == 207 || code == 200 || code == 301 || code == 302 || code == 403 || code == 404) {
                    android.util.Log.d("WebDavSync", "test: auth ok (code=" + code + "), creating folder...");
                    boolean folderCreated = createFolder(finalUrl, finalUser, finalPass);
                    android.util.Log.d("WebDavSync", "test: folder created=" + folderCreated);
                    App.post(callback::success);
                    return;
                }

                // PROPFIND 失败，降级用 OPTIONS 测试
                android.util.Log.d("WebDavSync", "test: PROPFIND failed with " + code + ", trying OPTIONS...");
                Request optionsRequest = new Request.Builder()
                        .url(finalUrl)
                        .header("Authorization", Credentials.basic(finalUser, finalPass))
                        .method("OPTIONS", emptyBody())
                        .build();

                Response optResponse = noProxyClient(10000).newCall(optionsRequest).execute();
                int optCode = optResponse.code();
                optResponse.close();
                android.util.Log.d("WebDavSync", "test OPTIONS: code=" + optCode);

                if (optCode == 401) {
                    App.post(() -> callback.error("账号或密码错误 (401)，请检查用户名和应用密码"));
                    return;
                }
                if (optCode == 200 || optCode == 204) {
                    boolean folderCreated = createFolder(finalUrl, finalUser, finalPass);
                    android.util.Log.d("WebDavSync", "test: OPTIONS ok, folder created=" + folderCreated);
                    App.post(callback::success);
                    return;
                }

                // OPTIONS 也失败，降级用 GET 测试
                android.util.Log.d("WebDavSync", "test: OPTIONS failed with " + optCode + ", trying GET...");
                Request getRequest = new Request.Builder()
                        .url(finalUrl)
                        .header("Authorization", Credentials.basic(finalUser, finalPass))
                        .build();

                Response getResponse = noProxyClient(10000).newCall(getRequest).execute();
                int getCode = getResponse.code();
                getResponse.close();
                android.util.Log.d("WebDavSync", "test GET: code=" + getCode);

                if (getCode == 401) {
                    App.post(() -> callback.error("账号或密码错误 (401)，请检查用户名和应用密码"));
                    return;
                }
                if (getCode == 200 || getCode == 207) {
                    boolean folderCreated = createFolder(finalUrl, finalUser, finalPass);
                    android.util.Log.d("WebDavSync", "test: GET ok, folder created=" + folderCreated);
                    App.post(callback::success);
                    return;
                }

                final int finalCode = getCode;
                App.post(() -> callback.error("服务器返回 HTTP " + finalCode));

            } catch (Exception e) {
                android.util.Log.e("WebDavSync", "test error: " + e.getMessage(), e);
                String msg = e.getMessage();
                if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
                String finalMsg = msg;
                App.post(() -> callback.error(finalMsg));
            }
        });
    }

    public static void upload(com.fongmi.android.tv.impl.Callback callback) {
        String url = Setting.getSyncUrl();
        String user = Setting.getSyncUser();
        String pass = Setting.getSyncPass();
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            if (callback != null) App.post(callback::error);
            return;
        }
        final String finalUrl = normalizeUrl(url);
        final String finalUser = user;
        final String finalPass = pass;

        Task.execute(() -> {
            try {
                // 确保星落文件夹存在
                createFolder(finalUrl, finalUser, finalPass);

                Backup backup = Backup.create();
                String json = backup.toString();
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

                String backupUrl = getBackupUrl(finalUrl);
                Request request = new Request.Builder()
                        .url(backupUrl)
                        .header("Authorization", Credentials.basic(finalUser, finalPass))
                        .put(RequestBody.create(bytes, MediaType.parse("application/json; charset=utf-8")))
                        .build();

                Response response = noProxyClient(10000).newCall(request).execute();
                boolean isSuccessful = response.isSuccessful();
                response.close();
                if (isSuccessful) {
                    if (callback != null) App.post(callback::success);
                } else {
                    if (callback != null) App.post(callback::error);
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (callback != null) App.post(callback::error);
            }
        });
    }

    public static void download(com.fongmi.android.tv.impl.Callback callback) {
        String url = Setting.getSyncUrl();
        String user = Setting.getSyncUser();
        String pass = Setting.getSyncPass();
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            if (callback != null) App.post(callback::error);
            return;
        }
        final String finalUrl = normalizeUrl(url);
        final String finalUser = user;
        final String finalPass = pass;

        Task.execute(() -> {
            try {
                String backupUrl = getBackupUrl(finalUrl);
                Request request = new Request.Builder()
                        .url(backupUrl)
                        .header("Authorization", Credentials.basic(finalUser, finalPass))
                        .build();

                Response response = noProxyClient(10000).newCall(request).execute();
                if (response.isSuccessful()) {
                    String json = response.body().string();
                    response.close();
                    Backup backup = Backup.objectFrom(json);
                    if (backup.getConfig().isEmpty() && backup.getSite().isEmpty()) {
                        if (callback != null) App.post(callback::error);
                    } else {
                        backup.restore();
                        if (callback != null) App.post(callback::success);
                    }
                } else {
                    response.close();
                    // 兼容旧备份：星落文件夹不存在时，尝试从根目录下载
                    String oldUrl = finalUrl.endsWith("/") ? finalUrl + "fongmi_backup.json" : finalUrl + "/fongmi_backup.json";
                    Request oldRequest = new Request.Builder()
                            .url(oldUrl)
                            .header("Authorization", Credentials.basic(finalUser, finalPass))
                            .build();
                    Response oldResponse = noProxyClient(10000).newCall(oldRequest).execute();
                    if (oldResponse.isSuccessful()) {
                        String json = oldResponse.body().string();
                        oldResponse.close();
                        Backup backup = Backup.objectFrom(json);
                        if (!backup.getConfig().isEmpty() || !backup.getSite().isEmpty()) {
                            backup.restore();
                            // 迁移到星落文件夹
                            createFolder(finalUrl, finalUser, finalPass);
                            if (callback != null) App.post(callback::success);
                            return;
                        }
                    }
                    oldResponse.close();
                    if (callback != null) App.post(callback::error);
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (callback != null) App.post(callback::error);
            }
        });
    }
}

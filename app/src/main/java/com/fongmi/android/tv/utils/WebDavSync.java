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
     * 创建星落文件夹（MKCOL），已存在则忽略
     */
    private static boolean createFolder(String url, String user, String pass) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) return false;
        try {
            String folderUrl = getFolderUrl(url);
            Request request = new Request.Builder()
                    .url(folderUrl)
                    .header("Authorization", Credentials.basic(user, pass))
                    .method("MKCOL", null)
                    .build();
            Response response = noProxyClient(5000).newCall(request).execute();
            int code = response.code();
            response.close();
            // 201=创建成功, 405=已存在, 301=重定向(部分服务端) 都视为成功
            return code == 201 || code == 200 || code == 405;
        } catch (Exception e) {
            return false;
        }
    }

    public static void test(String url, String user, String pass, com.fongmi.android.tv.impl.Callback callback) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            App.post(callback::error);
            return;
        }
        String backupUrl = getBackupUrl(url);
        Request request = new Request.Builder()
                .url(backupUrl)
                .header("Authorization", Credentials.basic(user, pass))
                .build();

        noProxyClient(5000).newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                android.util.Log.e("WebDavSync", "test onFailure: " + e.getMessage(), e);
                String msg = e.getMessage();
                if (msg == null || msg.isEmpty()) msg = e.getClass().getSimpleName();
                String finalMsg = msg;
                App.post(() -> callback.error(finalMsg));
            }

            @Override
            public void onResponse(Call call, Response response) {
                int code = response.code();
                response.close();
                if (code == 200 || code == 404) {
                    // 验证通过，自动创建星落文件夹
                    boolean folderCreated = createFolder(url, user, pass);
                    android.util.Log.d("WebDavSync", "test: connection ok, folder created=" + folderCreated);
                    App.post(callback::success);
                } else {
                    App.post(callback::error);
                }
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

        Task.execute(() -> {
            try {
                // 确保星落文件夹存在
                createFolder(url, user, pass);

                Backup backup = Backup.create();
                String json = backup.toString();
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

                String backupUrl = getBackupUrl(url);
                Request request = new Request.Builder()
                        .url(backupUrl)
                        .header("Authorization", Credentials.basic(user, pass))
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

        Task.execute(() -> {
            try {
                String backupUrl = getBackupUrl(url);
                Request request = new Request.Builder()
                        .url(backupUrl)
                        .header("Authorization", Credentials.basic(user, pass))
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
                    String oldUrl = url.endsWith("/") ? url + "fongmi_backup.json" : url + "/fongmi_backup.json";
                    Request oldRequest = new Request.Builder()
                            .url(oldUrl)
                            .header("Authorization", Credentials.basic(user, pass))
                            .build();
                    Response oldResponse = noProxyClient(10000).newCall(oldRequest).execute();
                    if (oldResponse.isSuccessful()) {
                        String json = oldResponse.body().string();
                        oldResponse.close();
                        Backup backup = Backup.objectFrom(json);
                        if (!backup.getConfig().isEmpty() || !backup.getSite().isEmpty()) {
                            backup.restore();
                            // 迁移到星落文件夹
                            createFolder(url, user, pass);
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

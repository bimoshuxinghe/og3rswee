package com.fongmi.android.tv.utils;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Backup;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import okhttp3.Call;
import okhttp3.Credentials;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class WebDavSync {

    private static String getBackupUrl(String url) {
        if (!url.endsWith("/")) {
            url += "/";
        }
        return url + "fongmi_backup.json";
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

        OkHttp.client(5000).newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                App.post(() -> callback.error(e.getMessage()));
            }

            @Override
            public void onResponse(Call call, Response response) {
                int code = response.code();
                response.close();
                if (code == 200 || code == 404) {
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
                Backup backup = Backup.create();
                String json = backup.toString();
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

                String backupUrl = getBackupUrl(url);
                Request request = new Request.Builder()
                        .url(backupUrl)
                        .header("Authorization", Credentials.basic(user, pass))
                        .put(RequestBody.create(bytes, MediaType.parse("application/json; charset=utf-8")))
                        .build();

                Response response = OkHttp.client(10000).newCall(request).execute();
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

                Response response = OkHttp.client(10000).newCall(request).execute();
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
                    if (callback != null) App.post(callback::error);
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (callback != null) App.post(callback::error);
            }
        });
    }
}

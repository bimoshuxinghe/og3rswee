package com.fongmi.android.tv.api;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.collection.ArrayMap;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Trans;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import okhttp3.Call;
import okhttp3.Response;

public class DanmakuApi {

    private static final String TAG = DanmakuApi.class.getSimpleName();

    /**
     * 请求序号（对齐上游 FongMi 5.7.4 的竞态保护）：
     * 每次 cancel() 递增，异步回调内可比对序号判断结果是否已过期，
     * 防止快速切集时旧弹幕搜索结果覆盖新集数。
     */
    private static final AtomicInteger REQUEST_ID = new AtomicInteger();

    public static boolean canSearch() {
        return DanmakuSetting.isLoad() && DanmakuSetting.isAuto() && !TextUtils.isEmpty(DanmakuSetting.getEffectiveApiUrl());
    }

    public static void cancel() {
        REQUEST_ID.incrementAndGet();
        OkHttp.cancel(TAG);
    }

    /** 当前请求序号（异步回调内与 cancel 序号比对可实现过期丢弃） */
    public static int requestId() {
        return REQUEST_ID.get();
    }

    public static Call newCall(String name, String episode) {
        name = Trans.t2s(name);
        episode = Trans.t2s(episode);
        String url = DanmakuSetting.getEffectiveApiUrl();
        if (url.contains("{name}") || url.contains("{episode}")) {
            // 对齐上游：查询参数必须 URL 编码，否则中文/特殊字符片名会导致请求 400
            return OkHttp.newCall(url.replace("{name}", Uri.encode(name)).replace("{episode}", Uri.encode(episode)), TAG);
        } else {
            ArrayMap<String, String> params = new ArrayMap<>();
            params.put("name", name);
            params.put("episode", episode);
            return OkHttp.newCall(url, OkHttp.toBody(params), TAG);
        }
    }

    public static void search(String name, String episode, Consumer<Danmaku> found) {
        int requestId = begin();
        newCall(name, episode).enqueue(new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                // 过期请求直接丢弃（竞态保护语义）
                if (requestId != REQUEST_ID.get()) return;
                try {
                    Danmaku.arrayFrom(response.body().string()).stream().findFirst().ifPresent(item -> App.post(() -> found.accept(item)));
                } catch (Exception ignored) {
                }
            }
        });
    }

    private static int begin() {
        cancel();
        return REQUEST_ID.get();
    }
}

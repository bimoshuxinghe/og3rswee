package com.fongmi.android.tv.player;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;

import org.json.JSONObject;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * WebSocket 弹幕客户端
 * <p>
 * 对接规范：
 * 1. Spider 的 playerContent 返回 ws:// 开头的弹幕地址
 * 2. 壳检测到 ws:// 后建立 WebSocket 连接
 * 3. 收到 JSON 后解析 message 字段作为弹幕文本
 * 4. color 字段为 #RRGGBB 格式颜色
 * 5. 播放器退出时关闭 WebSocket 连接
 * <p>
 * JSON 消息格式：
 * {"type":"chat","user_name":"用户名","message":"弹幕内容","color":"#FFFFFF"}
 * type=chat 普通弹幕, type=online 在线人数, type=superChat 醒目留言
 */
public class WsDanmakuClient {

    private static final String TAG = "WsDanmakuClient";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client;
    private WebSocket webSocket;
    private String url;
    private boolean connected;
    private int reconnectAttempts;
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 3000;

    public interface Callback {
        void onDanmaku(String text, int color);
    }

    public WsDanmakuClient() {
        this.client = OkHttp.player();
    }

    public void connect(String url, Callback callback) {
        disconnect();
        this.url = url;
        this.reconnectAttempts = 0;
        doConnect(url, callback);
    }

    private void doConnect(String url, Callback callback) {
        SpiderDebug.log("danmaku", "WS connect url=%s", url);
        Request request = new Request.Builder().url(url).build();
        webSocket = client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                connected = true;
                reconnectAttempts = 0;
                SpiderDebug.log("danmaku", "WS connected");
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                handleMessage(text, callback);
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                webSocket.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                connected = false;
                SpiderDebug.log("danmaku", "WS closed code=%d reason=%s", code, reason);
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                connected = false;
                SpiderDebug.log("danmaku", "WS failure: %s", t.getMessage());
                attemptReconnect(callback);
            }
        });
    }

    private void attemptReconnect(Callback callback) {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS || TextUtils.isEmpty(url)) {
            SpiderDebug.log("danmaku", "WS max reconnect attempts reached, giving up");
            return;
        }
        reconnectAttempts++;
        SpiderDebug.log("danmaku", "WS reconnect attempt %d/%d", reconnectAttempts, MAX_RECONNECT_ATTEMPTS);
        mainHandler.postDelayed(() -> {
            if (connected || this.webSocket == null) return;
            doConnect(url, callback);
        }, RECONNECT_DELAY_MS * reconnectAttempts);
    }

    private void handleMessage(String text, Callback callback) {
        if (TextUtils.isEmpty(text) || callback == null) return;
        try {
            JSONObject json = new JSONObject(text);
            String type = json.optString("type", "chat");

            // 只处理普通弹幕和醒目留言
            if ("chat".equals(type) || "superChat".equals(type)) {
                String message = json.optString("message", "");
                if (TextUtils.isEmpty(message)) return;

                String colorStr = json.optString("color", "#FFFFFF");
                int color = parseColor(colorStr);

                String userName = json.optString("user_name", "");

                // superChat 添加用户名前缀
                String displayText;
                if ("superChat".equals(type) && !TextUtils.isEmpty(userName)) {
                    displayText = userName + ": " + message;
                } else {
                    displayText = message;
                }

                final String finalText = displayText;
                final int finalColor = color;
                mainHandler.post(() -> callback.onDanmaku(finalText, finalColor));
            }
        } catch (Exception e) {
            SpiderDebug.log("danmaku", "WS parse error: %s raw=%s", e.getMessage(), text.length() > 200 ? text.substring(0, 200) : text);
        }
    }

    private int parseColor(String colorStr) {
        if (TextUtils.isEmpty(colorStr)) return Color.WHITE;
        try {
            if (colorStr.startsWith("#")) {
                return Color.parseColor(colorStr);
            } else {
                return Color.parseColor("#" + colorStr);
            }
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    public void disconnect() {
        if (webSocket != null) {
            try {
                webSocket.close(1000, "client disconnect");
            } catch (Exception e) {
                Log.e(TAG, "disconnect error", e);
            }
            webSocket = null;
        }
        connected = false;
        url = null;
        reconnectAttempts = MAX_RECONNECT_ATTEMPTS; // 阻止重连
    }

    public boolean isConnected() {
        return connected;
    }
}

package com.fongmi.chaquo;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.github.catvod.utils.Auth;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.UriUtil;
import com.github.catvod.utils.Util;
import com.google.gson.Gson;
import com.google.common.net.HttpHeaders;

import java.io.FilterInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Headers;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

public class Spider extends com.github.catvod.crawler.Spider {

    private static final String TAG = "PyStreamProxy";
    private static final String STREAM_PROXY_MIME = "application/x-codex-stream-url";

    private final PyObject app;
    private final PyObject obj;
    private final String api;
    private final Gson gson;

    public Spider(PyObject app, PyObject obj, String api) {
        this.gson = new Gson();
        this.app = app;
        this.obj = obj;
        this.api = api;
    }

    @Override
    public void init(Context context, String extend) {
        PyObject dependence = app.callAttr("getDependence", obj);
        if (dependence != null) for (PyObject item : dependence.asList()) download(item + ".py");
        obj.put("siteKey", siteKey);
        app.callAttr("init", obj, extend);
    }

    @Override
    public String homeContent(boolean filter) {
        return app.callAttr("homeContent", obj, filter).toString();
    }

    @Override
    public String homeVideoContent() {
        return app.callAttr("homeVideoContent", obj).toString();
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        return app.callAttr("categoryContent", obj, tid, pg, filter, gson.toJson(extend)).toString();
    }

    @Override
    public String detailContent(List<String> ids) {
        return app.callAttr("detailContent", obj, gson.toJson(ids)).toString();
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return app.callAttr("searchContent", obj, key, quick).toString();
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        return app.callAttr("searchContent", obj, key, quick, pg).toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        return app.callAttr("playerContent", obj, flag, id, gson.toJson(vipFlags)).toString();
    }

    @Override
    public String liveContent(String url) {
        return app.callAttr("liveContent", obj, url).toString();
    }

    @Override
    public boolean manualVideoCheck() {
        return app.callAttr("manualVideoCheck", obj).toBoolean();
    }

    @Override
    public boolean isVideoFormat(String url) {
        return app.callAttr("isVideoFormat", obj, url).toBoolean();
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        List<PyObject> list = app.callAttr("localProxy", obj, gson.toJson(params)).asList();
        if (list.size() > 2 && STREAM_PROXY_MIME.equals(list.get(1).toString())) return getRemoteStream(list.get(2));
        boolean base64 = list.size() > 4 && list.get(4).toInt() == 1;
        boolean header = list.size() > 3 && list.get(3) != null;
        Object[] result = new Object[4];
        result[0] = list.get(0).toInt();
        result[1] = list.get(1).toString();
        result[2] = getStream(list.get(2), base64);
        result[3] = header ? getHeader(list.get(3)) : null;
        return result;
    }

    @Override
    public String action(String action) {
        return app.callAttr("action", obj, action).toString();
    }

    @Override
    public void destroy() {
        try {
            app.callAttr("destroy", obj);
        } catch (Exception ignored) {
        }
    }

    private Map<String, String> getHeader(PyObject obj) {
        try {
            Map<String, String> header = new HashMap<>();
            for (Map.Entry<PyObject, PyObject> entry : obj.asMap().entrySet()) header.put(entry.getKey().toString(), entry.getValue().toString());
            return header;
        } catch (Exception e) {
            return null;
        }
    }

    private Object[] getRemoteStream(PyObject obj) throws Exception {
        Map<String, String> data = getHeader(obj);
        if (data == null || !data.containsKey("url")) return new Object[]{404, "text/plain", new ByteArrayInputStream("stream url missing".getBytes()), null};
        Map<String, String> headers = new HashMap<>();
        PyObject headerObj = getMapValue(obj, "headers");
        if (headerObj != null) headers.putAll(getHeader(headerObj));
        String url = data.get("url");
        String proxy = data.get("proxy");
        Log.d(TAG, "request host=" + getHost(url) + ", proxy=" + safeProxy(proxy) + ", range=" + getHeaderValue(headers, "Range"));
        Response response = getStreamClient(proxy).newCall(buildRequest(url, headers)).execute();
        ResponseBody body = response.body();
        if (body == null) {
            response.close();
            return new Object[]{502, "text/plain", new ByteArrayInputStream("stream body empty".getBytes()), null};
        }
        String contentType = response.header("Content-Type", data.containsKey("content_type") ? data.get("content_type") : "application/octet-stream");
        Map<String, String> responseHeaders = getResponseHeaders(response.headers(), contentType);
        Log.d(TAG, "response code=" + response.code() + ", type=" + contentType + ", length=" + response.header("Content-Length") + ", range=" + response.header("Content-Range"));
        return new Object[]{response.code(), contentType, new ResponseInputStream(body.byteStream(), response), responseHeaders};
    }

    private PyObject getMapValue(PyObject obj, String key) {
        for (Map.Entry<PyObject, PyObject> entry : obj.asMap().entrySet()) if (key.equals(entry.getKey().toString())) return entry.getValue();
        return null;
    }

    private Request buildRequest(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null || "host".equalsIgnoreCase(key) || "connection".equalsIgnoreCase(key)) continue;
            builder.addHeader(key, value);
        }
        return builder.build();
    }

    private OkHttpClient getStreamClient(String proxy) {
        java.net.Proxy javaProxy = createProxy(proxy);
        OkHttpClient.Builder builder = com.github.catvod.net.OkHttp.player().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true);
        if (javaProxy == null) return builder.build();
        builder.proxy(javaProxy);
        String userInfo = getProxyUserInfo(proxy);
        if (!TextUtils.isEmpty(userInfo)) {
            builder.proxyAuthenticator((route, response) -> response.request().header(HttpHeaders.PROXY_AUTHORIZATION) == null ? response.request().newBuilder().header(HttpHeaders.PROXY_AUTHORIZATION, Auth.basic(userInfo)).build() : null);
        }
        return builder.build();
    }

    private java.net.Proxy createProxy(String proxy) {
        if (TextUtils.isEmpty(proxy)) return null;
        try {
            Uri uri = Uri.parse(proxy.contains("://") ? proxy : "http://" + proxy);
            if (TextUtils.isEmpty(uri.getHost()) || uri.getPort() <= 0) return null;
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            java.net.Proxy.Type type = scheme.startsWith("socks") || isLocalMihomo(uri) ? java.net.Proxy.Type.SOCKS : java.net.Proxy.Type.HTTP;
            return new java.net.Proxy(type, InetSocketAddress.createUnresolved(uri.getHost(), uri.getPort()));
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isLocalMihomo(Uri uri) {
        String host = uri.getHost();
        return uri.getPort() == 18890 && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host));
    }

    private String getProxyUserInfo(String proxy) {
        if (TextUtils.isEmpty(proxy)) return "";
        try {
            String userInfo = Uri.parse(proxy.contains("://") ? proxy : "http://" + proxy).getUserInfo();
            return TextUtils.isEmpty(userInfo) ? "" : Uri.decode(userInfo);
        } catch (Exception e) {
            return "";
        }
    }

    private String getHost(String url) {
        try {
            Uri uri = Uri.parse(url);
            return uri.getHost();
        } catch (Exception e) {
            return "";
        }
    }

    private String safeProxy(String proxy) {
        if (TextUtils.isEmpty(proxy)) return "";
        try {
            Uri uri = Uri.parse(proxy.contains("://") ? proxy : "http://" + proxy);
            String host = uri.getHost();
            int port = uri.getPort();
            return TextUtils.isEmpty(host) || port <= 0 ? "" : host + ":" + port;
        } catch (Exception e) {
            return "";
        }
    }

    private String getHeaderValue(Map<String, String> headers, String name) {
        for (Map.Entry<String, String> entry : headers.entrySet()) if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        return "";
    }

    private Map<String, String> getResponseHeaders(Headers headers, String contentType) {
        Map<String, String> result = new HashMap<>();
        result.put("Content-Type", contentType);
        result.put("Accept-Ranges", "bytes");
        result.put("Cache-Control", "no-cache");
        for (String name : new String[]{"Content-Length", "Content-Range", "ETag", "Last-Modified"}) {
            String value = headers.get(name);
            if (value != null) result.put(name, value);
        }
        return result;
    }

    private static class ResponseInputStream extends FilterInputStream {

        private final Response response;

        ResponseInputStream(java.io.InputStream input, Response response) {
            super(input);
            this.response = response;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                response.close();
            }
        }
    }

    private ByteArrayInputStream getStream(PyObject o, boolean base64) {
        if (o == null) return null;
        if (o.type().toString().contains("bytes")) return new ByteArrayInputStream(o.toJava(byte[].class));
        String content = o.toString();
        if (base64 && content.contains("base64,")) content = content.split("base64,")[1];
        return new ByteArrayInputStream(base64 ? Util.decode(content) : content.getBytes());
    }

    private void download(String name) {
        String path = Path.py(name).getAbsolutePath();
        String url = UriUtil.resolve(api, name);
        app.callAttr("download", path, url);
    }
}

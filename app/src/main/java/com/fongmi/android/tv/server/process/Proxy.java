package com.fongmi.android.tv.server.process;

import android.util.Log;

import com.fongmi.android.tv.api.loader.BaseLoader;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;

import java.io.InputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

public class Proxy implements Process {

    private static final String TAG = "LocalProxy";

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/proxy") && !url.endsWith(".html");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            Map<String, String> params = session.getParms();
            params.putAll(session.getHeaders());
            params.putAll(files);
            logRequest(params);
            Object[] rs = BaseLoader.get().proxy(params);
            if (rs[0] instanceof Response) return (Response) rs[0];
            Map<String, String> headers = rs.length > 3 && rs[3] != null ? (Map<String, String>) rs[3] : null;
            Response response = createResponse((Integer) rs[0], (String) rs[1], (InputStream) rs[2], headers);
            if (headers != null) for (Map.Entry<String, String> entry : headers.entrySet()) response.addHeader(entry.getKey(), entry.getValue());
            logResponse((Integer) rs[0], (String) rs[1], headers);
            return response;
        } catch (Throwable e) {
            e.printStackTrace();
            return Nano.error(e.getMessage());
        }
    }

    private Response createResponse(int status, String mimeType, InputStream input, Map<String, String> headers) {
        Response.Status code = lookup(status);
        long length = getLength(headers);
        if (length >= 0) return NanoHTTPD.newFixedLengthResponse(code, mimeType, input, length);
        return NanoHTTPD.newChunkedResponse(code, mimeType, input);
    }

    /** 上游状态码降级映射：NanoHTTPD 枚举外的状态码（如 502/504）lookup 返回 null，会让 send() 抛 Error 炸掉服务线程 */
    private Response.Status lookup(int status) {
        Response.Status code = Response.Status.lookup(status);
        if (code != null) return code;
        if (status >= 500) return Response.Status.INTERNAL_ERROR;
        if (status >= 400) return Response.Status.BAD_REQUEST;
        if (status >= 300) return Response.Status.REDIRECT;
        return Response.Status.OK;
    }

    private long getLength(Map<String, String> headers) {
        if (headers == null) return -1;
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (!"Content-Length".equalsIgnoreCase(entry.getKey())) continue;
            try {
                return Long.parseLong(entry.getValue());
            } catch (Exception ignored) {
                return -1;
            }
        }
        return -1;
    }

    private void logRequest(Map<String, String> params) {
        String type = params.get("type");
        if (!"mpd".equals(type) && !"media".equals(type) && !"single".equals(type)) return;
        Log.d(TAG, "request type=" + type + ", track=" + params.get("track") + ", range=" + getValue(params, "range"));
    }

    private void logResponse(int status, String mimeType, Map<String, String> headers) {
        if (!"application/dash+xml".equals(mimeType) && (headers == null || (getValue(headers, "Content-Range").isEmpty() && getValue(headers, "Accept-Ranges").isEmpty()))) return;
        Log.d(TAG, "response status=" + status + ", type=" + mimeType + ", length=" + getValue(headers, "Content-Length") + ", range=" + getValue(headers, "Content-Range"));
    }

    private String getValue(Map<String, String> map, String name) {
        if (map == null) return "";
        for (Map.Entry<String, String> entry : map.entrySet()) if (name.equalsIgnoreCase(entry.getKey())) return entry.getValue();
        return "";
    }
}

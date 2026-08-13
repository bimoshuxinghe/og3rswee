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
        long length = getLength(headers);
        if (length >= 0) return NanoHTTPD.newFixedLengthResponse(Status.lookup(status), mimeType, input, length);
        return NanoHTTPD.newChunkedResponse(Status.lookup(status), mimeType, input);
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

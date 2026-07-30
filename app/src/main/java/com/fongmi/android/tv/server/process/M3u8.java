package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.net.OkHttp;

import java.util.Map;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import okhttp3.HttpUrl;
import okhttp3.Request;

public class M3u8 implements Process {

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/m3u8");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            String target = session.getParms().get("url");
            if (target == null || !target.startsWith("http")) return Nano.error("Url Error");
            try (okhttp3.Response response = OkHttp.client().newCall(new Request.Builder().url(target).header("User-Agent", "Mozilla/5.0").build()).execute()) {
                String body = response.body() == null ? "" : response.body().string();
                int start = body.indexOf("#EXTM3U");
                if (start < 0) return Nano.error("M3U8 Error");
                Response result = newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", clean(body.substring(start), response.request().url()));
                result.addHeader("Access-Control-Allow-Origin", "*");
                return result;
            }
        } catch (Throwable e) {
            return Nano.error(e.getMessage());
        }
    }

    private String clean(String body, HttpUrl base) {
        StringBuilder sb = new StringBuilder();
        for (String line : body.replace("\r", "").split("\n")) {
            String text = line.trim();
            if (text.isEmpty()) continue;
            sb.append(text.startsWith("#") ? text : resolve(base, text)).append('\n');
        }
        return sb.toString();
    }

    private String resolve(HttpUrl base, String line) {
        if (line.startsWith("http")) return line;
        HttpUrl url = base.resolve(line);
        return url == null ? line : url.toString();
    }
}

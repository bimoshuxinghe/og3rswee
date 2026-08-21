package com.fongmi.android.tv.server.process;

import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.player.mpv.IsoParser;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.Proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * ISO 流媒体代理端点。
 * <p>
 * 当播放器遇到远程 ISO 镜像链接时，IsoParser 会解析镜像内部文件系统，
 * 找到主视频文件的字节偏移和大小，然后通过本端点以 HTTP Range 方式
 * 按需从原始远程 URL 读取对应区段数据并转发给播放器。
 * <p>
 * 流程：
 * 1. {@link #register(String, Map)} 解析 ISO 并注册代理条目，返回本地代理 URL
 * 2. 播放器用该 URL 发起播放请求（支持 Range）
 * 3. 本端点将 Range 翻译为 ISO 内绝对偏移，向远程 URL 发起 Range 请求
 * 4. 将远程响应数据流式返回给播放器
 */
public class IsoStream implements Process {

    private static final String TAG = "IsoStream";
    private static final String PATH_PREFIX = "/iso_stream";

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    private static class Entry {
        final String url;
        final String localPath;
        final Map<String, String> headers;
        final long startOffset;
        final long endOffset;
        final long size;
        final String mime;

        Entry(String url, String localPath, Map<String, String> headers, long startOffset, long size, String mime) {
            this.url = url;
            this.localPath = localPath;
            this.headers = headers;
            this.startOffset = startOffset;
            this.size = size;
            this.endOffset = startOffset + size;
            this.mime = mime;
        }

        boolean isLocal() {
            return localPath != null;
        }
    }

    private static final ConcurrentHashMap<String, Entry> ENTRIES = new ConcurrentHashMap<>();

    /**
     * 解析 ISO 镜像（远程或本地）并注册代理条目。
     *
     * @param url    ISO 镜像的 URL（HTTP(S) 远程链接或本地文件路径）
     * @param headers 请求所需的 headers（Cookie、UA 等，仅远程链接需要）
     * @return 本地代理 URL（如 http://127.0.0.1:9978/iso_stream?id=xxx），
     *         解析失败时返回 null
     */
    @Nullable
    public static String register(@NonNull String url, @Nullable Map<String, String> headers) {
        IsoParser.VideoFile vf = IsoParser.findVideoFile(url, headers);
        if (vf == null) {
            Log.w(TAG, "No video file found in ISO: " + url);
            return null;
        }
        String id = UUID.randomUUID().toString().replace("-", "");
        String mime = getMimeForFormat(vf.format);
        Entry entry;
        if (IsoParser.isLocalFile(url)) {
            String localPath = IsoParser.getLocalPath(url);
            entry = new Entry(null, localPath, null, vf.offset, vf.size, mime);
        } else {
            entry = new Entry(url, null, headers, vf.offset, vf.size, mime);
        }
        ENTRIES.put(id, entry);
        cleanupOldEntries();
        String proxyUrl = "http://127.0.0.1:" + Proxy.getPort() + PATH_PREFIX + "?id=" + id;
        Log.i(TAG, "Registered ISO stream: " + vf.name + " -> " + proxyUrl + " (offset=" + vf.offset + ", size=" + vf.size + ")");
        return proxyUrl;
    }

    /**
     * 清理过期的代理条目（最多保留 10 个）。
     */
    private static void cleanupOldEntries() {
        while (ENTRIES.size() > 10) {
            String oldestKey = ENTRIES.keys().nextElement();
            ENTRIES.remove(oldestKey);
        }
    }

    @NonNull
    private static String getMimeForFormat(@NonNull String format) {
        switch (format.toLowerCase()) {
            case "m2ts":
            case "ts":
                return "video/mp2t";
            case "vob":
            case "mpg":
            case "mpeg":
                return "video/mpeg";
            case "mp4":
            case "m4v":
                return "video/mp4";
            case "mkv":
                return "video/x-matroska";
            case "webm":
                return "video/webm";
            case "flv":
                return "video/x-flv";
            case "avi":
                return "video/x-msvideo";
            case "mov":
                return "video/quicktime";
            case "wmv":
                return "video/x-ms-wmv";
            default:
                return "application/octet-stream";
        }
    }

    @Override
    public boolean isRequest(NanoHTTPD.IHTTPSession session, String url) {
        return url.startsWith(PATH_PREFIX);
    }

    @Override
    public NanoHTTPD.Response doResponse(NanoHTTPD.IHTTPSession session, String url, Map<String, String> files) {
        Map<String, String> params = session.getParms();
        String id = params.get("id");
        if (TextUtils.isEmpty(id)) {
            return Nano.error("Missing id parameter");
        }
        Entry entry = ENTRIES.get(id);
        if (entry == null) {
            return Nano.error("ISO stream entry not found: " + id);
        }
        return serveStream(session.getHeaders(), entry);
    }

    @NonNull
    private NanoHTTPD.Response serveStream(Map<String, String> reqHeaders, Entry entry) {
        long totalLen = entry.size;
        long start = 0;
        long end = totalLen - 1;
        String rangeHeader = reqHeaders.get("range");
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String[] parts = rangeHeader.substring(6).split("-", 2);
                if (!parts[0].isEmpty()) start = Long.parseLong(parts[0]);
                if (parts.length > 1 && !parts[1].isEmpty()) end = Long.parseLong(parts[1]);
                if (start >= totalLen) return createRangeNotSatisfiable(totalLen);
                if (end >= totalLen || end < 0) end = totalLen - 1;
                if (start > end) return createRangeNotSatisfiable(totalLen);
            } catch (NumberFormatException e) {
                return Nano.error("Invalid range: " + rangeHeader);
            }
        }
        long length = end - start + 1;
        if (entry.isLocal()) return serveLocalStream(entry, start, end, length);
        return serveRemoteStream(entry, start, end, length);
    }

    @NonNull
    private NanoHTTPD.Response serveLocalStream(Entry entry, long start, long end, long length) {
        try {
            RandomAccessFile raf = new RandomAccessFile(entry.localPath, "r");
            raf.seek(entry.startOffset + start);
            InputStream is = new FileRangeInputStream(raf, length);
            boolean partial = start > 0 || end < entry.size - 1;
            NanoHTTPD.Response resp;
            if (partial) {
                resp = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.PARTIAL_CONTENT, entry.mime, is);
                resp.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + entry.size);
            } else {
                resp = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, entry.mime, is);
            }
            resp.addHeader("Content-Length", String.valueOf(length));
            resp.addHeader("Accept-Ranges", "bytes");
            resp.addHeader("Cache-Control", "no-cache, no-store");
            return resp;
        } catch (IOException e) {
            Log.e(TAG, "Local stream error: " + e.getMessage(), e);
            return Nano.error("Local stream error: " + e.getMessage());
        }
    }

    @NonNull
    private NanoHTTPD.Response serveRemoteStream(Entry entry, long start, long end, long length) {
        long absStart = entry.startOffset + start;
        long absEnd = entry.startOffset + end;
        try {
            Request.Builder rb = new Request.Builder().url(entry.url)
                    .header("Range", "bytes=" + absStart + "-" + absEnd);
            if (entry.headers != null) {
                for (Map.Entry<String, String> h : entry.headers.entrySet()) {
                    String k = h.getKey();
                    String v = h.getValue();
                    if (TextUtils.isEmpty(k) || TextUtils.isEmpty(v)) continue;
                    if ("range".equalsIgnoreCase(k) || "host".equalsIgnoreCase(k)) continue;
                    rb.header(k, v);
                }
            }
            Response remoteResp = HTTP.newCall(rb.build()).execute();
            int code = remoteResp.code();
            if (code != 206 && code != 200) {
                remoteResp.close();
                return Nano.error("Remote HTTP " + code);
            }
            ResponseBody body = remoteResp.body();
            if (body == null) {
                remoteResp.close();
                return Nano.error("Empty response body");
            }
            InputStream is = body.byteStream();
            boolean partial = start > 0 || end < entry.size - 1;
            NanoHTTPD.Response resp;
            if (partial) {
                resp = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.PARTIAL_CONTENT, entry.mime, is);
                resp.addHeader("Content-Range", "bytes " + start + "-" + end + "/" + entry.size);
            } else {
                resp = NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, entry.mime, is);
            }
            resp.addHeader("Content-Length", String.valueOf(length));
            resp.addHeader("Accept-Ranges", "bytes");
            resp.addHeader("Cache-Control", "no-cache, no-store");
            return resp;
        } catch (IOException e) {
            Log.e(TAG, "Stream error: " + e.getMessage(), e);
            return Nano.error("Stream error: " + e.getMessage());
        }
    }

    @NonNull
    private NanoHTTPD.Response createRangeNotSatisfiable(long totalLen) {
        NanoHTTPD.Response resp = NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.RANGE_NOT_SATISFIABLE,
                NanoHTTPD.MIME_PLAINTEXT, "");
        resp.addHeader("Content-Range", "bytes */" + totalLen);
        return resp;
    }

    /**
     * 从 RandomAccessFile 读取指定长度数据的 InputStream，读取完毕后自动关闭文件。
     */
    private static class FileRangeInputStream extends InputStream {
        private final RandomAccessFile raf;
        private long remaining;

        FileRangeInputStream(RandomAccessFile raf, long length) {
            this.raf = raf;
            this.remaining = length;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = raf.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int toRead = (int) Math.min(len, remaining);
            int read = raf.read(b, off, toRead);
            if (read > 0) remaining -= read;
            return read;
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }
}

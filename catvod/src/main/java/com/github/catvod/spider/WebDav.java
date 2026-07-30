package com.github.catvod.spider;

import android.content.Context;
import android.net.Uri;
import com.github.catvod.crawler.Spider;
import com.thegrizzlylabs.sardineandroid.DavResource;
import com.thegrizzlylabs.sardineandroid.Sardine;
import com.thegrizzlylabs.sardineandroid.impl.OkHttpSardine;
import org.json.JSONArray;
import org.json.JSONObject;
import com.github.catvod.utils.LocalScraper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class WebDav extends Spider {

    private String baseUrl;
    private String rootPath;
    private Sardine sardine;

    private static final List<String> MEDIA_EXT = Arrays.asList(
            "mp4", "mkv", "avi", "flv", "mov", "webm", "ts", "m3u8", "3gp", "wmv",
            "mp3", "flac", "wav", "ogg", "m4a", "aac", "ape", "wma",
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg",
            "srt", "ass", "ssa", "vtt"
    );

    private boolean isMediaFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot == -1) return false;
        String ext = lower.substring(dot + 1);
        return MEDIA_EXT.contains(ext);
    }

    private boolean isPlayableFile(String name) {
        if (!isMediaFile(name)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String ext = lower.substring(dot + 1);
        // 排除字幕和图片文件
        return !ext.equals("srt") && !ext.equals("ass") && !ext.equals("ssa") && !ext.equals("vtt")
                && !ext.equals("jpg") && !ext.equals("jpeg") && !ext.equals("png") 
                && !ext.equals("gif") && !ext.equals("webp") && !ext.equals("bmp") && !ext.equals("svg");
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        
        String url = extend.trim();
        String scheme = "http";
        String user = "";
        String password = "";
        String host = "";
        int port = -1;
        String path = "/";
        
        // Parse scheme
        int schemeIdx = url.indexOf("://");
        if (schemeIdx != -1) {
            String tempScheme = url.substring(0, schemeIdx).toLowerCase();
            if (tempScheme.startsWith("https") || tempScheme.startsWith("webdavs")) {
                scheme = "https";
            } else if (tempScheme.startsWith("dav") || tempScheme.startsWith("webdav")) {
                scheme = "http";
            } else {
                scheme = tempScheme;
            }
            url = url.substring(schemeIdx + 3);
        }
        
        // Parse userinfo
        int lastAtIdx = url.lastIndexOf("@");
        if (lastAtIdx != -1) {
            String userInfo = url.substring(0, lastAtIdx);
            int colonIdx = userInfo.indexOf(':');
            if (colonIdx != -1) {
                user = userInfo.substring(0, colonIdx);
                password = userInfo.substring(colonIdx + 1);
            } else {
                user = userInfo;
            }
            url = url.substring(lastAtIdx + 1);
        }
        
        // Parse path
        int pathIdx = url.indexOf('/');
        if (pathIdx != -1) {
            path = url.substring(pathIdx);
            url = url.substring(0, pathIdx);
        }
        
        // Parse host & port
        int colonIdx = url.indexOf(':');
        if (colonIdx != -1) {
            host = url.substring(0, colonIdx);
            try {
                port = Integer.parseInt(url.substring(colonIdx + 1));
            } catch (NumberFormatException ignored) {}
        } else {
            host = url;
        }
        
        if (port != -1) {
            this.baseUrl = scheme + "://" + host + ":" + port;
        } else {
            this.baseUrl = scheme + "://" + host;
        }
        
        OkHttpSardine okSardine = new OkHttpSardine();
        okSardine.setCredentials(user, password);
        this.sardine = okSardine;
        
        this.rootPath = path;
        if (!this.rootPath.startsWith("/")) {
            this.rootPath = "/" + this.rootPath;
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        String currentPath = tid.equals("root") || tid.isEmpty() ? rootPath : tid;
        if (!currentPath.endsWith("/")) {
            currentPath += "/";
        }

        String url = baseUrl + currentPath;
        List<DavResource> resources = sardine.list(url);
        JSONArray listArr = new JSONArray();

        List<String> allNames = new ArrayList<>();
        boolean first = true;
        for (DavResource res : resources) {
            if (first) {
                first = false;
                continue;
            }
            String name = res.getName();
            if (name.startsWith(".")) continue;
            allNames.add(name);
        }

        String defaultPoster = LocalScraper.getDefaultPoster(allNames);

        first = true;
        for (DavResource res : resources) {
            // Sardine.list returns the directory itself as the first item in the list
            if (first) {
                first = false;
                continue;
            }

            String name = res.getName();
            if (name.startsWith(".")) continue;

            if (res.isDirectory() || isMediaFile(name)) {
                String path = res.getPath();
                // Sardine returns decoded absolute path, ensure we extract the relative server path properly
                if (path.startsWith(baseUrl)) {
                    path = path.substring(baseUrl.length());
                }

                JSONObject vod = new JSONObject();
                vod.put("vod_id", path);
                vod.put("vod_name", name);
                if (res.isDirectory()) {
                    String poster = LocalScraper.getCompanionPoster(allNames, name);
                    if (poster == null) {
                        try {
                            String subUrl = baseUrl + path;
                            if (!subUrl.endsWith("/")) subUrl += "/";
                            List<DavResource> subRes = sardine.list(subUrl);
                            List<String> subNames = new ArrayList<>();
                            boolean subFirst = true;
                            for (DavResource sr : subRes) {
                                if (subFirst) {
                                    subFirst = false;
                                    continue;
                                }
                                String srName = sr.getName();
                                if (!srName.startsWith(".")) {
                                    subNames.add(srName);
                                }
                            }
                            String innerPoster = LocalScraper.getDefaultPoster(subNames);
                            if (innerPoster != null) {
                                poster = name + "/" + innerPoster;
                            }
                        } catch (Exception ignored) {}
                    }
                    if (poster != null) {
                        String posterUrl = com.github.catvod.Proxy.getUrl(true) + "?siteKey=" + siteKey + "&path=" + Uri.encode(currentPath + poster);
                        vod.put("vod_pic", posterUrl);
                    } else {
                        vod.put("vod_pic", "push_folder");
                    }
                    vod.put("vod_tag", "folder");
                    vod.put("vod_remarks", "文件夹");
                } else {
                    String itemBase = LocalScraper.stripExtension(name);
                    String poster = LocalScraper.getCompanionPoster(allNames, itemBase);
                    if (poster == null && defaultPoster != null) {
                        poster = defaultPoster;
                    }
                    if (poster != null) {
                        String posterUrl = com.github.catvod.Proxy.getUrl(true) + "?siteKey=" + siteKey + "&path=" + Uri.encode(currentPath + poster);
                        vod.put("vod_pic", posterUrl);
                    } else {
                        vod.put("vod_pic", "push_video");
                    }
                    vod.put("vod_remarks", formatSize(res.getContentLength()));
                }
                listArr.put(vod);
            }
        }

        JSONObject result = new JSONObject();
        result.put("page", 1);
        result.put("pagecount", 1);
        result.put("limit", listArr.length());
        result.put("total", listArr.length());
        result.put("list", listArr);
        return result.toString();
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String path = ids.get(0);
        int slash = path.lastIndexOf('/');
        String name = slash != -1 ? path.substring(slash + 1) : path;
        String parentPath = slash != -1 ? path.substring(0, slash) : "/";
        if (!parentPath.endsWith("/")) parentPath += "/";
        
        String playUrl = com.github.catvod.Proxy.getUrl(true) + "?siteKey=" + siteKey + "&path=" + Uri.encode(path);
        
        JSONObject vod = new JSONObject();
        vod.put("vod_id", path);
        vod.put("vod_name", name);
        vod.put("vod_play_from", "WebDAV");

        // 如果是图片文件，直接显示图片本身
        if (isImageFile(name)) {
            vod.put("vod_play_url", name + "$" + playUrl);
            vod.put("vod_pic", playUrl);
            JSONObject result = new JSONObject();
            result.put("list", new JSONArray(Collections.singletonList(vod)));
            return result.toString();
        }

        List<String> siblingNames = new ArrayList<>();
        List<DavResource> playables = new ArrayList<>();
        try {
            String parentUrl = baseUrl + parentPath;
            List<DavResource> siblings = sardine.list(parentUrl);
            boolean first = true;
            for (DavResource s : siblings) {
                if (first) {
                    first = false;
                    continue;
                }
                String sName = s.getName();
                if (!sName.startsWith(".")) {
                    siblingNames.add(sName);
                    if (!s.isDirectory() && isPlayableFile(sName)) {
                        playables.add(s);
                    }
                }
            }
        } catch (Exception ignored) {}

        playables.sort((o1, o2) -> o1.getName().compareToIgnoreCase(o2.getName()));

        if (playables.isEmpty()) {
            vod.put("vod_play_url", name + "$" + playUrl);
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < playables.size(); i++) {
                DavResource r = playables.get(i);
                String rName = r.getName();
                String rPath = parentPath + rName;
                String pUrl = com.github.catvod.Proxy.getUrl(true) + "?siteKey=" + siteKey + "&path=" + Uri.encode(rPath);
                if (i > 0) sb.append("#");
                sb.append(rName).append("$").append(pUrl);
            }
            vod.put("vod_play_url", sb.toString());
        }

        String itemBase = LocalScraper.stripExtension(name);
        String poster = LocalScraper.getCompanionPoster(siblingNames, itemBase);
        if (poster == null) poster = LocalScraper.getDefaultPoster(siblingNames);
        if (poster != null) {
            String posterUrl = com.github.catvod.Proxy.getUrl(true) + "?siteKey=" + siteKey + "&path=" + Uri.encode(parentPath + poster);
            vod.put("vod_pic", posterUrl);
        } else {
            vod.put("vod_pic", "push_video");
        }

        String nfoName = itemBase + ".nfo";
        String targetNfo = null;
        if (siblingNames.contains(nfoName)) {
            targetNfo = nfoName;
        } else if (siblingNames.contains("movie.nfo")) {
            targetNfo = "movie.nfo";
        } else if (siblingNames.contains("tvshow.nfo")) {
            targetNfo = "tvshow.nfo";
        }
        
        if (targetNfo != null) {
            String nfoUrl = baseUrl + parentPath + targetNfo;
            try (InputStream in = sardine.get(nfoUrl)) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = in.read(buf)) != -1) {
                    bos.write(buf, 0, len);
                }
                String xml = bos.toString("UTF-8");
                LocalScraper.parseNfo(xml, vod);
            } catch (Exception ignored) {}
        }
        
        JSONObject result = new JSONObject();
        result.put("list", new JSONArray(Collections.singletonList(vod)));
        return result.toString();
    }

    private boolean isImageFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot == -1) return false;
        String ext = lower.substring(dot + 1);
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") 
                || ext.equals("gif") || ext.equals("webp") || ext.equals("bmp") || ext.equals("svg");
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSONObject result = new JSONObject();
        result.put("url", id);
        result.put("parse", 0);
        return result.toString();
    }

    private String getMimeType(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mkv")) return "video/x-matroska";
        if (lower.endsWith(".avi")) return "video/x-msvideo";
        if (lower.endsWith(".flv")) return "video/x-flv";
        if (lower.endsWith(".mov")) return "video/quicktime";
        if (lower.endsWith(".ts")) return "video/MP2T";
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".3gp")) return "video/3gpp";
        if (lower.endsWith(".wmv")) return "video/x-ms-wmv";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".flac")) return "audio/flac";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".aac")) return "audio/aac";
        if (lower.endsWith(".ape")) return "audio/ape";
        return "video/mp4";
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        String path = params.get("path");
        String url = baseUrl + path;

        // Fetch file size using PROPFIND (list)
        List<DavResource> resList = sardine.list(url, 0);
        long fileSize = 0;
        if (resList != null && !resList.isEmpty()) {
            fileSize = resList.get(0).getContentLength();
        }

        long start = 0;
        long end = fileSize - 1;

        String rangeHeader = params.get("Range");
        if (rangeHeader == null) rangeHeader = params.get("range");

        Map<String, String> requestHeaders = new HashMap<>();
        if (rangeHeader != null) {
            requestHeaders.put("Range", rangeHeader);
            String[] ranges = rangeHeader.substring(6).split("-");
            try {
                start = Long.parseLong(ranges[0]);
                if (ranges.length > 1 && !ranges[1].isEmpty()) {
                    end = Long.parseLong(ranges[1]);
                }
            } catch (NumberFormatException ignored) {}
        }

        InputStream stream = sardine.get(url, requestHeaders);
        String mime = getMimeType(path);

        Map<String, String> responseHeaders = new HashMap<>();
        responseHeaders.put("Content-Type", mime);
        responseHeaders.put("Accept-Ranges", "bytes");

        if (rangeHeader != null) {
            responseHeaders.put("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
            responseHeaders.put("Content-Length", String.valueOf(end - start + 1));
            return new Object[]{206, mime, stream, responseHeaders};
        } else {
            responseHeaders.put("Content-Length", String.valueOf(fileSize));
            return new Object[]{200, mime, stream, responseHeaders};
        }
    }

    private String formatSize(long length) {
        if (length <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(length) / Math.log10(1024));
        return String.format(Locale.ROOT, "%.1f %s", length / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}

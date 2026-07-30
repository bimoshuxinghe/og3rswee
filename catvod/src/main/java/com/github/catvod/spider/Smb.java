package com.github.catvod.spider;

import android.content.Context;
import android.net.Uri;
import com.github.catvod.crawler.Spider;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;
import org.json.JSONArray;
import org.json.JSONObject;
import com.github.catvod.utils.LocalScraper;
import java.nio.charset.StandardCharsets;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Smb extends Spider {

    private String host;
    private int port;
    private String user;
    private String password;
    private String initialShare;
    private String initialPath;

    private SMBClient client;
    private Connection connection;
    private Session session;

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

    private synchronized Session getSession() throws Exception {
        if (session == null || connection == null || !connection.isConnected()) {
            closeSession();
            client = new SMBClient();
            connection = client.connect(host, port);
            session = connection.authenticate(new AuthenticationContext(user, password.toCharArray(), ""));
        }
        return session;
    }

    private synchronized void closeSession() {
        if (session != null) {
            try { session.close(); } catch (Exception ignored) {}
            session = null;
        }
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
            connection = null;
        }
        if (client != null) {
            try { client.close(); } catch (Exception ignored) {}
            client = null;
        }
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        
        String url = extend.trim();
        String user = "GUEST";
        String password = "";
        String host = "";
        int port = 445;
        String path = "";
        
        // Parse scheme
        int schemeIdx = url.indexOf("://");
        if (schemeIdx != -1) {
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
        
        this.host = host;
        this.port = port;
        this.user = user;
        this.password = password;
        
        if (path.startsWith("/")) path = path.substring(1);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        
        int firstSlash = path.indexOf('/');
        if (firstSlash != -1) {
            this.initialShare = path.substring(0, firstSlash);
            this.initialPath = path.substring(firstSlash + 1);
        } else {
            this.initialShare = path;
            this.initialPath = "";
        }
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        Session activeSession = getSession();
        JSONArray listArr = new JSONArray();

        String shareName = "";
        String directoryPath = "";

        if (tid.equals("root") || tid.isEmpty()) {
            if (initialShare.isEmpty()) {
                JSONObject errorVod = new JSONObject();
                errorVod.put("vod_id", "error");
                errorVod.put("vod_name", "请在连接设置中指定共享文件夹，如：192.168.1.249/共享空间");
                errorVod.put("vod_pic", "push_folder");
                errorVod.put("vod_tag", "folder");
                errorVod.put("vod_remarks", "配置错误");
                listArr.put(errorVod);
                
                JSONObject result = new JSONObject();
                result.put("page", 1);
                result.put("pagecount", 1);
                result.put("limit", listArr.length());
                result.put("total", listArr.length());
                result.put("list", listArr);
                return result.toString();
            } else {
                shareName = initialShare;
                directoryPath = initialPath;
            }
        } else {
            int firstSlash = tid.indexOf('/');
            if (firstSlash != -1) {
                shareName = tid.substring(0, firstSlash);
                directoryPath = tid.substring(firstSlash + 1);
            } else {
                shareName = tid;
                directoryPath = "";
            }
        }

        try (DiskShare share = (DiskShare) activeSession.connectShare(shareName)) {
            List<FileIdBothDirectoryInformation> files = new ArrayList<>();
            List<String> allNames = new ArrayList<>();
            for (FileIdBothDirectoryInformation info : share.list(directoryPath.replace('/', '\\'))) {
                String name = info.getFileName();
                if (name.equals(".") || name.equals("..") || name.startsWith(".")) continue;
                allNames.add(name);
                
                boolean isDir = (info.getFileAttributes() & 0x10) != 0;
                if (isDir || isMediaFile(name)) {
                    files.add(info);
                }
            }

            // Sort folders first, then files alphabetically
            files.sort((o1, o2) -> {
                boolean dir1 = (o1.getFileAttributes() & 0x10) != 0;
                boolean dir2 = (o2.getFileAttributes() & 0x10) != 0;
                if (dir1 && !dir2) return -1;
                if (!dir1 && dir2) return 1;
                return o1.getFileName().compareToIgnoreCase(o2.getFileName());
            });

            String defaultPoster = LocalScraper.getDefaultPoster(allNames);

            for (FileIdBothDirectoryInformation info : files) {
                String name = info.getFileName();
                boolean isDir = (info.getFileAttributes() & 0x10) != 0;
                String relativePath = directoryPath.isEmpty() ? name : directoryPath + "/" + name;
                String fullId = shareName + "/" + relativePath;

                JSONObject vod = new JSONObject();
                vod.put("vod_id", fullId);
                vod.put("vod_name", name);
                if (isDir) {
                    String poster = LocalScraper.getCompanionPoster(allNames, name);
                    if (poster == null) {
                        try {
                            List<String> subNames = new ArrayList<>();
                            for (FileIdBothDirectoryInformation subInfo : share.list(relativePath.replace('/', '\\'))) {
                                String subName = subInfo.getFileName();
                                if (!subName.equals(".") && !subName.equals("..") && !subName.startsWith(".")) {
                                    subNames.add(subName);
                                }
                            }
                            String innerPoster = LocalScraper.getDefaultPoster(subNames);
                            if (innerPoster != null) {
                                poster = relativePath + "/" + innerPoster;
                            }
                        } catch (Exception ignored) {}
                    } else {
                        poster = directoryPath.isEmpty() ? poster : directoryPath + "/" + poster;
                    }
                    if (poster != null) {
                        String posterUrl = com.github.catvod.Proxy.getUrl(true) + "?siteKey=" + siteKey + "&path=" + Uri.encode(shareName + "/" + poster);
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
                        String posterPath = directoryPath.isEmpty() ? poster : directoryPath + "/" + poster;
                        String posterUrl = com.github.catvod.Proxy.getUrl(true) + "?siteKey=" + siteKey + "&path=" + Uri.encode(shareName + "/" + posterPath);
                        vod.put("vod_pic", posterUrl);
                    } else {
                        vod.put("vod_pic", "push_video");
                    }
                    vod.put("vod_remarks", formatSize(info.getEndOfFile()));
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
        String fullId = ids.get(0);
        int slash = fullId.lastIndexOf('/');
        String name = slash != -1 ? fullId.substring(slash + 1) : fullId;
        
        String playUrl = com.github.catvod.Proxy.getUrl(true) + "?siteKey=" + siteKey + "&path=" + Uri.encode(fullId);
        
        JSONObject vod = new JSONObject();
        vod.put("vod_id", fullId);
        vod.put("vod_name", name);
        vod.put("vod_play_from", "SMB");

        // 如果是图片文件，直接显示图片本身
        if (isImageFile(name)) {
            vod.put("vod_play_url", name + "$" + playUrl);
            vod.put("vod_pic", playUrl);
            JSONObject result = new JSONObject();
            result.put("list", new JSONArray(Collections.singletonList(vod)));
            return result.toString();
        }

        Session activeSession = getSession();
        int firstSlash = fullId.indexOf('/');
        if (firstSlash != -1) {
            String shareName = fullId.substring(0, firstSlash);
            String filePath = fullId.substring(firstSlash + 1);
            int lastSlash = filePath.lastIndexOf('/');
            String directoryPath = lastSlash != -1 ? filePath.substring(0, lastSlash) : "";
            
            try (DiskShare share = (DiskShare) activeSession.connectShare(shareName)) {
                List<String> siblingNames = new ArrayList<>();
                List<FileIdBothDirectoryInformation> playables = new ArrayList<>();
                for (FileIdBothDirectoryInformation info : share.list(directoryPath.replace('/', '\\'))) {
                    String sName = info.getFileName();
                    if (!sName.equals(".") && !sName.equals("..") && !sName.startsWith(".")) {
                        siblingNames.add(sName);
                        boolean isDir = (info.getFileAttributes() & 0x10) != 0;
                        if (!isDir && isPlayableFile(sName)) {
                            playables.add(info);
                        }
                    }
                }
                playables.sort((o1, o2) -> o1.getFileName().compareToIgnoreCase(o2.getFileName()));

                if (playables.isEmpty()) {
                    vod.put("vod_play_url", name + "$" + playUrl);
                } else {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < playables.size(); i++) {
                        FileIdBothDirectoryInformation info = playables.get(i);
                        String sName = info.getFileName();
                        String relativePath = directoryPath.isEmpty() ? sName : directoryPath + "/" + sName;
                        String fId = shareName + "/" + relativePath;
                        String pUrl = com.github.catvod.Proxy.getUrl(true) + "?siteKey=" + siteKey + "&path=" + Uri.encode(fId);
                        if (i > 0) sb.append("#");
                        sb.append(sName).append("$").append(pUrl);
                    }
                    vod.put("vod_play_url", sb.toString());
                }
                
                String itemBase = LocalScraper.stripExtension(name);
                String poster = LocalScraper.getCompanionPoster(siblingNames, itemBase);
                if (poster == null) poster = LocalScraper.getDefaultPoster(siblingNames);
                
                if (poster != null) {
                    String posterPath = directoryPath.isEmpty() ? poster : directoryPath + "/" + poster;
                    String posterUrl = com.github.catvod.Proxy.getUrl(true) + "?siteKey=" + siteKey + "&path=" + Uri.encode(shareName + "/" + posterPath);
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
                    String nfoPath = directoryPath.isEmpty() ? targetNfo : directoryPath + "/" + targetNfo;
                    try (File fileObj = share.openFile(
                            nfoPath.replace('/', '\\'),
                            EnumSet.of(AccessMask.GENERIC_READ),
                            null,
                            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                            SMB2CreateDisposition.FILE_OPEN,
                            null
                    )) {
                        long nfoSize = fileObj.getFileInformation().getStandardInformation().getEndOfFile();
                        byte[] buf = new byte[(int) nfoSize];
                        int read = 0;
                        while (read < buf.length) {
                            int r = fileObj.read(buf, (long) read, read, buf.length - read);
                            if (r == -1 || r == 0) break;
                            read += r;
                        }
                        String xml = new String(buf, 0, read, StandardCharsets.UTF_8);
                        LocalScraper.parseNfo(xml, vod);
                    }
                }
            } catch (Exception ignored) {
                vod.put("vod_pic", "push_video");
            }
        } else {
            vod.put("vod_pic", "push_video");
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
        String fullId = params.get("path");
        int firstSlash = fullId.indexOf('/');
        if (firstSlash == -1) {
            return new Object[]{404, "text/plain", null};
        }
        String shareName = fullId.substring(0, firstSlash);
        String filePath = fullId.substring(firstSlash + 1);

        Session activeSession = getSession();
        DiskShare share = (DiskShare) activeSession.connectShare(shareName);
        
        File file = share.openFile(
                filePath.replace('/', '\\'),
                EnumSet.of(AccessMask.GENERIC_READ),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null
        );

        long fileSize = file.getFileInformation().getStandardInformation().getEndOfFile();
        long start = 0;
        long end = fileSize - 1;

        String rangeHeader = params.get("Range");
        if (rangeHeader == null) rangeHeader = params.get("range");

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] ranges = rangeHeader.substring(6).split("-");
            try {
                start = Long.parseLong(ranges[0]);
                if (ranges.length > 1 && !ranges[1].isEmpty()) {
                    end = Long.parseLong(ranges[1]);
                }
            } catch (NumberFormatException ignored) {}
        }

        long contentLength = end - start + 1;
        InputStream smbStream = new SmbInputStream(file, start);
        String mime = getMimeType(filePath);

        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", mime);
        headers.put("Accept-Ranges", "bytes");
        if (rangeHeader != null) {
            headers.put("Content-Range", "bytes " + start + "-" + end + "/" + fileSize);
            headers.put("Content-Length", String.valueOf(contentLength));
            return new Object[]{206, mime, smbStream, headers};
        } else {
            headers.put("Content-Length", String.valueOf(fileSize));
            return new Object[]{200, mime, smbStream, headers};
        }
    }

    @Override
    public void destroy() {
        super.destroy();
        closeSession();
    }

    private String formatSize(long length) {
        if (length <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(length) / Math.log10(1024));
        return String.format(Locale.ROOT, "%.1f %s", length / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    private static class SmbInputStream extends InputStream {
        private final File file;
        private long offset;
        private final long length;

        public SmbInputStream(File file, long offset) throws IOException {
            this.file = file;
            this.offset = offset;
            this.length = file.getFileInformation().getStandardInformation().getEndOfFile();
        }

        @Override
        public int read() throws IOException {
            byte[] b = new byte[1];
            int r = read(b, 0, 1);
            return r == -1 ? -1 : b[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (offset >= length) return -1;
            int read = file.read(b, offset, off, len);
            if (read > 0) {
                offset += read;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            file.close();
        }
    }
}

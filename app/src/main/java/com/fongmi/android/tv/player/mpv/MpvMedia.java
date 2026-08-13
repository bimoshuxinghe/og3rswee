package com.fongmi.android.tv.player.mpv;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import java.util.Locale;

public final class MpvMedia {

    private MpvMedia() {
    }

    public static boolean shouldPreferMpv(@Nullable String url) {
        // ISO 不再强制使用 MPV，改由 EXO 通过 IsoStream 代理播放（EXO 更稳定）。
        // 用户仍可在设置中手动选择 MPV 引擎来播放 ISO。
        return isSpoofedSegment(url) || isCoffeeM3u8(url);
    }

    public static boolean isCoffeeM3u8(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return false;
        String text = decode(stripFragment(url));
        if (TextUtils.isEmpty(text)) return false;
        text = text.toLowerCase(Locale.ROOT);
        return text.contains(".m3u8") && (text.contains("1ljx.com") || text.contains("coffee.1ljx.com"));
    }

    public static boolean isHls(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return false;
        String text = decode(stripFragment(url));
        if (TextUtils.isEmpty(text)) return false;
        text = text.toLowerCase(Locale.ROOT);
        if (text.contains(".m3u8") || text.contains("mpegurl") || text.contains("application/vnd.apple.mpegurl")) return true;
        if (text.contains("format=application/x-mpegurl") || text.contains("format=application/vnd.apple.mpegurl")) return true;
        return isLikelyPhpProxyHls(text);
    }

    /**
     * 判断 URL 是否为 PHP 代理直播流（用于直播场景下强制按 HLS 处理）。
     * 任何包含 .php 的 HTTP(S) 链接都视为可能的直播代理。
     */
    public static boolean isPhpProxyStream(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return false;
        String text = decode(stripFragment(url));
        if (TextUtils.isEmpty(text)) return false;
        text = text.toLowerCase(Locale.ROOT);
        if (!text.contains(".php")) return false;
        return true;
    }

    /**
     * 判断 URL 是否为常见视频文件格式。
     */
    public static boolean isVideoFile(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return false;
        String text = decode(stripFragment(url));
        if (TextUtils.isEmpty(text)) return false;
        text = text.toLowerCase(Locale.ROOT);
        String path = text.split("[?#]", 2)[0];
        return path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".avi") || path.endsWith(".mov")
                || path.endsWith(".flv") || path.endsWith(".webm") || path.endsWith(".ts") || path.endsWith(".m4v")
                || path.endsWith(".wmv") || path.endsWith(".mpg") || path.endsWith(".mpeg") || path.endsWith(".3gp")
                || path.endsWith(".rmvb") || path.endsWith(".rm") || path.endsWith(".vob") || path.endsWith(".f4v");
    }

    /**
     * 判断 URL 是否为常见音频文件格式。
     */
    public static boolean isAudioFile(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return false;
        String text = decode(stripFragment(url));
        if (TextUtils.isEmpty(text)) return false;
        text = text.toLowerCase(Locale.ROOT);
        String path = text.split("[?#]", 2)[0];
        return path.endsWith(".mp3") || path.endsWith(".aac") || path.endsWith(".m4a") || path.endsWith(".ogg")
                || path.endsWith(".oga") || path.endsWith(".opus") || path.endsWith(".flac") || path.endsWith(".wav")
                || path.endsWith(".wma") || path.endsWith(".amr") || path.endsWith(".ape") || path.endsWith(".mka");
    }

    public static boolean isRadioAudio(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return false;
        String text = decode(stripFragment(url));
        if (TextUtils.isEmpty(text)) return false;
        text = text.toLowerCase(Locale.ROOT);
        if (isAudioExtension(text) || containsAudioMime(text)) return true;
        if (text.contains("icecast") || text.contains("shoutcast") || text.contains("internet-radio")) return true;
        if (text.contains("radio") || text.contains("fm") || text.contains("audio")) return isLikelyStreamUrl(text);
        if (text.contains(":8000/") || text.contains(":8001/") || text.contains(":8080/")) return !hasVideoOrPlaylistExtension(text);
        return isLikelyPhpProxyRadioAudio(text);
    }

    private static boolean isLikelyPhpProxyHls(String text) {
        if (!text.contains(".php")) return false;
        if (text.contains("m3u8") || text.contains("hls")) return true;
        if (text.contains("proxy") || text.contains("play") || text.contains("live") || text.contains("stream")) {
            return text.contains("url=") || text.contains("u=") || text.contains("src=") || text.contains("id=") || text.contains("channel=");
        }
        return false;
    }

    private static boolean isLikelyPhpProxyRadioAudio(String text) {
        if (!text.contains(".php")) return false;
        if (!(text.contains("proxy") || text.contains("play") || text.contains("live") || text.contains("stream"))) return false;
        if (!(text.contains("url=") || text.contains("u=") || text.contains("src=") || text.contains("id=") || text.contains("channel="))) return false;
        return text.contains("radio") || text.contains("fm") || text.contains("audio") || text.contains(":8000/") || text.contains(":8001/") || text.contains(":8080/") || isAudioExtension(text) || containsAudioMime(text);
    }

    private static boolean isLikelyStreamUrl(String text) {
        return text.startsWith("http://") || text.startsWith("https://") || text.contains("url=") || text.contains("u=") || text.contains("src=");
    }

    private static boolean isAudioExtension(String text) {
        String path = text.split("[?#]", 2)[0];
        return path.endsWith(".mp3") || path.endsWith(".aac") || path.endsWith(".m4a") || path.endsWith(".ogg") || path.endsWith(".oga") || path.endsWith(".opus") || path.endsWith(".flac") || path.endsWith(".wav");
    }

    private static boolean containsAudioMime(String text) {
        return text.contains("audio/") || text.contains("content-type=audio") || text.contains("format=audio");
    }

    private static boolean hasVideoOrPlaylistExtension(String text) {
        String path = text.split("[?#]", 2)[0];
        return path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".mp4") || path.endsWith(".mkv") || path.endsWith(".ts") || path.endsWith(".flv") || path.endsWith(".avi") || path.endsWith(".mov") || path.endsWith(".webm");
    }

    public static boolean isSpoofedSegment(@Nullable String url) {
        String path = getPath(url);
        return path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".webp") || path.endsWith(".gif");
    }

    public static boolean isBluRayIso(@Nullable String url) {
        return getPath(url).endsWith(".iso");
    }

    /**
     * 判断 URL 是否为远程（HTTP/HTTPS）ISO 镜像链接。
     * 网盘返回的 ISO 下载链接属于此类。
     */
    public static boolean isRemoteIso(@Nullable String url) {
        if (!isBluRayIso(url)) return false;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return scheme != null && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
    }

    public static String getPlayableUrl(String url) {
        if (!isBluRayIso(url)) return stripFragment(url);
        // 远程 ISO 不能直接使用 bd:// 协议（libbluray 不支持 HTTP 读取），
        // 由 MpvSimplePlayer 在播放前通过 IsoStream 代理转换为可播放的 URL。
        if (isRemoteIso(url)) return stripFragment(url);
        String title = getFragmentValue(url, "title");
        return TextUtils.isEmpty(title) ? "bd://" : "bd://" + title;
    }

    @Nullable
    public static String getBluRayDevice(String url) {
        if (!isBluRayIso(url)) return null;
        // 远程 ISO 不设置 bluray-device，避免 libbluray 尝试用文件方式打开 HTTP URL。
        if (isRemoteIso(url)) return null;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (scheme == null || "file".equalsIgnoreCase(scheme)) return decode(uri.getPath());
        return stripFragment(url);
    }

    private static String getPath(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return "";
        try {
            Uri uri = Uri.parse(url);
            String path = uri.getPath();
            if (!TextUtils.isEmpty(path)) return path.toLowerCase(Locale.ROOT);
        } catch (Throwable ignored) {
        }
        return stripFragment(url).split("\\?", 2)[0].toLowerCase(Locale.ROOT);
    }

    private static String stripFragment(String url) {
        int index = url.indexOf('#');
        return index == -1 ? url : url.substring(0, index);
    }

    @Nullable
    private static String getFragmentValue(String url, String name) {
        String fragment = Uri.parse(url).getFragment();
        if (TextUtils.isEmpty(fragment)) return null;
        for (String part : fragment.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && name.equals(pair[0])) return pair[1];
        }
        return null;
    }

    @Nullable
    private static String decode(@Nullable String value) {
        if (TextUtils.isEmpty(value)) return value;
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return value;
        }
    }
}

package com.fongmi.android.tv.player;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import java.util.Locale;

/**
 * 原盘（ISO 镜像）通用判断工具。
 * 独立于具体播放内核（Exo / VLC），供各引擎共用。
 */
public final class IsoMedia {

    private IsoMedia() {
    }

    /**
     * 判断 URL 是否应优先使用 VLC 内核。
     * VLC 擅长处理图片伪装的分段流（spoofed segment）与 coffee 系 m3u8。
     */
    public static boolean shouldPreferVlc(@Nullable String url) {
        return isSpoofedSegment(url) || isCoffeeM3u8(url);
    }

    public static boolean isCoffeeM3u8(@Nullable String url) {
        String text = decodedPath(url);
        if (TextUtils.isEmpty(text)) return false;
        return text.contains(".m3u8") && (text.contains("1ljx.com") || text.contains("coffee.1ljx.com"));
    }

    public static boolean isSpoofedSegment(@Nullable String url) {
        String path = getPath(url);
        return path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".webp") || path.endsWith(".gif");
    }

    /**
     * 判断 URL 是否为原盘镜像（.iso）。
     */
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

    private static String decodedPath(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return "";
        String text = decode(stripFragment(url));
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private static String stripFragment(String url) {
        int index = url.indexOf('#');
        return index == -1 ? url : url.substring(0, index);
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

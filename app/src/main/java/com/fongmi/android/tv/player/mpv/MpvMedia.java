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
        return isSpoofedSegment(url) || isBluRayIso(url) || isCoffeeM3u8(url);
    }

    public static boolean isCoffeeM3u8(@Nullable String url) {
        if (TextUtils.isEmpty(url)) return false;
        String text = decode(stripFragment(url));
        if (TextUtils.isEmpty(text)) return false;
        text = text.toLowerCase(Locale.ROOT);
        return text.contains(".m3u8") && (text.contains("1ljx.com") || text.contains("coffee.1ljx.com"));
    }

    public static boolean isSpoofedSegment(@Nullable String url) {
        String path = getPath(url);
        return path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".webp") || path.endsWith(".gif");
    }

    public static boolean isBluRayIso(@Nullable String url) {
        return getPath(url).endsWith(".iso");
    }

    public static String getPlayableUrl(String url) {
        if (!isBluRayIso(url)) return stripFragment(url);
        String title = getFragmentValue(url, "title");
        return TextUtils.isEmpty(title) ? "bd://" : "bd://" + title;
    }

    @Nullable
    public static String getBluRayDevice(String url) {
        if (!isBluRayIso(url)) return null;
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

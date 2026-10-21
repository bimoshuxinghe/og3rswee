package com.fongmi.android.tv.utils;

import android.content.Context;
import android.net.Uri;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.event.RefreshEvent;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 本地爬虫（.js / .py）线路管理。
 * 源文件会被复制到缓存目录再登记为站点，避免用户删改原文件后线路失效，
 * 同时也绕开 Android 11+ 分区存储对外部路径的直接访问限制。
 */
public class LocalSpider {

    public static final String PREFIX = "local_spider_";
    public static final String GROUP = "本地";

    /** 登记一个本地爬虫文件，返回生成的站点；失败返回 null。 */
    public static Site add(File src) {
        if (src == null) return null;
        return add(src.getName(), () -> new FileInputStream(src));
    }

    /** 登记一个来自 SAF（系统文件选择器）的爬虫文件。 */
    public static Site add(Context context, Uri uri, String name) {
        if (context == null || uri == null || name == null) return null;
        return add(name, () -> context.getContentResolver().openInputStream(uri));
    }

    /** 统一登记入口：把内容写入缓存目录并建站点。 */
    private static Site add(String name, InputStreamSupplier supplier) {
        File dst = target(name);
        if (dst == null || !copy(supplier, dst)) return null;
        Site site = new Site();
        site.setKey(keyOfName(dst.getName()));
        site.setName(trimExt(dst.getName()));
        site.setApi(dst.getAbsolutePath());
        site.setGroup(GROUP);
        site.setType(3);
        site.setSearchable(1);
        site.setChangeable(0);
        site.save();
        append(site);
        return site;
    }

    private interface InputStreamSupplier {
        InputStream get() throws Exception;
    }

    /** 已登记的本地爬虫站点。 */
    public static List<Site> all() {
        List<Site> result = new ArrayList<>();
        for (Site site : Site.findAll()) {
            if (site.getKey().startsWith(PREFIX)) result.add(site);
        }
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    public static boolean exists(String name) {
        File dst = target(name);
        return dst != null && Site.find(keyOfName(dst.getName())) != null;
    }

    /** 取消登记，同时删除缓存副本。 */
    public static void remove(Site site) {
        VodConfig.get().getSites().remove(site);
        new File(site.getApi()).delete();
        site.delete();
        RefreshEvent.home();
    }

    /** 按原始文件名移除登记的线路（用于扫描页取消勾选同步）。 */
    public static void removeByName(String name) {
        Site site = Site.find(keyOfName(name));
        if (site == null) return;
        remove(site);
    }

    /** 站点是否已登记（导入前去重）。 */
    public static boolean contains(Site site, List<Site> sites) {
        for (Site item : sites) {
            if (item.getKey().equals(site.getKey())) return true;
        }
        return false;
    }

    private static void append(Site site) {
        List<Site> sites = VodConfig.get().getSites();
        if (contains(site, sites)) return;
        sites.add(site);
        RefreshEvent.home();
    }

    private static File target(String name) {
        String lower = name.toLowerCase(Locale.getDefault());
        if (lower.endsWith(".py")) return new File(Path.py(), name);
        if (lower.endsWith(".js")) return new File(Path.js(), name);
        return null;
    }

    private static String keyOfName(String filename) {
        return PREFIX + Util.md5(filename).substring(0, 16);
    }

    private static String trimExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean copy(InputStreamSupplier supplier, File dst) {
        try (InputStream in = supplier.get(); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
            return dst.length() > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}

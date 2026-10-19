package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.event.RefreshEvent;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
        File dst = target(src);
        if (dst == null || !copy(src, dst)) return null;
        Site site = new Site();
        site.setKey(keyOf(dst));
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

    /** 已登记的本地爬虫站点。 */
    public static List<Site> all() {
        List<Site> result = new ArrayList<>();
        for (Site site : Site.findAll()) {
            if (site.getKey().startsWith(PREFIX)) result.add(site);
        }
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    public static boolean exists(File src) {
        File dst = target(src);
        return dst != null && Site.find(keyOf(dst)) != null;
    }

    /** 取消登记，同时删除缓存副本。 */
    public static void remove(Site site) {
        VodConfig.get().getSites().remove(site);
        new File(site.getApi()).delete();
        site.delete();
        RefreshEvent.home();
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

    private static File target(File src) {
        String name = src.getName().toLowerCase(Locale.getDefault());
        if (name.endsWith(".py")) return new File(Path.py(), src.getName());
        if (name.endsWith(".js")) return new File(Path.js(), src.getName());
        return null;
    }

    private static String keyOf(File dst) {
        return PREFIX + Util.md5(dst.getName()).substring(0, 16);
    }

    private static String trimExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean copy(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src); FileOutputStream out = new FileOutputStream(dst)) {
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

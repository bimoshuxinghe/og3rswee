package com.fongmi.android.tv.api.loader;

import android.os.Build;

import com.fongmi.android.tv.App;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderNull;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rust 二进制线路加载器：站点 api 以 ".bin" 结尾（如 ./rust/spider_rust.bin）。
 *
 * 二进制文件解析顺序：
 * 1. http(s):// 开头 → 下载缓存到 Path.cache("rust")；
 * 2. APK 内置 assets：rust/<名>.<abi>.bin → rust/<名>.bin → <名>.bin；
 * 3. 本地相对/绝对路径（配合本地配置使用）。
 *
 * 命中后释放到缓存目录并 chmod 755，供 BinSpider 以子进程方式执行。
 */
public class BinLoader {

    private final ConcurrentHashMap<String, Spider> spiders;

    public BinLoader() {
        spiders = new ConcurrentHashMap<>();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
    }

    public Spider getSpider(String key, String api, String ext) {
        return spiders.computeIfAbsent(key, k -> {
            try {
                File bin = resolveBin(api);
                if (bin == null) return new SpiderNull();
                Spider spider = new BinSpider(bin.getAbsolutePath());
                spider.siteKey = key;
                spider.init(App.get(), ext);
                return spider;
            } catch (Throwable e) {
                e.printStackTrace();
                return new SpiderNull();
            }
        });
    }

    /** 解析 api 指向的二进制文件，返回可执行 File；找不到返回 null */
    private File resolveBin(String api) throws Exception {
        if (api == null) return null;
        String trimmed = api.trim();
        if (trimmed.startsWith("./")) trimmed = trimmed.substring(2);

        // 1) 网络 URL：下载缓存
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            File target = new File(Path.cache("rust"), Util.md5(trimmed) + ".bin");
            if (!Path.exists(target)) download(trimmed, target);
            return chmod(target) ? target : null;
        }

        // 2) APK 内置 assets（按设备 ABI 优先匹配）
        String base = trimmed;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.endsWith(".bin")) base = base.substring(0, base.length() - 4);

        for (String candidate : assetCandidates(base)) {
            try (InputStream in = Asset.open(candidate)) {
                if (in != null) {
                    File target = new File(Path.cache("rust"), base + "-" + Util.md5(candidate) + ".bin");
                    copy(in, target);
                    return chmod(target) ? target : null;
                }
            } catch (Throwable ignored) {
                // assets 中不存在该候选名，继续尝试下一个
            }
        }

        // 3) 本地路径（绝对路径直接使用；相对路径按缓存目录解析；均自动补可执行权限）
        File local = new File(trimmed);
        if (Path.exists(local)) return chmod(local) ? local : null;
        // ./rust/xxx.bin → <cache>/rust/xxx.bin（兼容 <cache>/rust/rust/xxx.bin 旧双层写法）
        File rel = new File(Path.cache("rust"), trimmed);
        if (Path.exists(rel)) return chmod(rel) ? rel : null;
        File relFlat = new File(Path.cache("rust"), base + ".bin");
        if (Path.exists(relFlat)) return chmod(relFlat) ? relFlat : null;
        return null;
    }

    /** assets 候选名：abi 优先，其次无 abi */
    private String[] assetCandidates(String base) {
        String[] abis = Build.SUPPORTED_ABIS;
        String[] candidates = new String[abis.length + 2];
        for (int i = 0; i < abis.length; i++) candidates[i] = "rust/" + base + "." + abis[i] + ".bin";
        candidates[abis.length] = "rust/" + base + ".bin";
        candidates[abis.length + 1] = base + ".bin";
        return candidates;
    }

    private void download(String url, File target) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.connect();
        if (conn.getResponseCode() != 200) throw new Exception("下载 bin 失败: HTTP " + conn.getResponseCode());
        try (InputStream in = conn.getInputStream()) {
            copy(in, target);
        } finally {
            conn.disconnect();
        }
    }

    private void copy(InputStream in, File target) throws Exception {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) != -1) out.write(buffer, 0, len);
        }
    }

    /** 赋予可执行权限（targetSdk 28 可直接 exec 数据目录文件） */
    private boolean chmod(File file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file.toPath());
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file.toPath(), perms);
            return true;
        } catch (Throwable e) {
            try {
                Process p = new ProcessBuilder("chmod", "755", file.getAbsolutePath()).start();
                return p.waitFor() == 0;
            } catch (Throwable ignored) {
                return false;
            }
        }
    }
}

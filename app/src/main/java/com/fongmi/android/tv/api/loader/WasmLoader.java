package com.fongmi.android.tv.api.loader;

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
import java.util.concurrent.ConcurrentHashMap;

/**
 * WASM 站点线路加载器（"Rust 版 py" 模式）：站点 api 以 ".wasm" 结尾
 * （如 ./rust/4kvm.wasm）。
 *
 * 站点 wasm 是纯逻辑模块（几十 KB ~ 几百 KB），网络请求、HTML 解析、正则、
 * 播放签名等运行依赖全部内置在 libspider_rust.so 壳子中，经 host imports
 * 提供给 wasm 调用——与 JS/PY 外置爬虫同构，但体积小两个数量级。
 *
 * 文件解析顺序（与 BinLoader 同构；wasm 与 ABI 无关，故无 abi 候选）：
 * 1. http(s):// 开头 → 下载缓存到 Path.cache("rust")；
 * 2. APK 内置 assets：wasm/<名>.wasm → <名>.wasm；
 * 3. 本地路径（绝对路径直接使用；相对路径按缓存目录解析）。
 *
 * 命中后经 RustBridge.nativeLoadSiteWasm 注册到 Rust 引擎（按 ext 推断站点
 * key），之后 RustSpider 的 6 个内容入口自动分派到该 wasm 站点执行。
 *
 * 注意：多个不同站点的 wasm 线路，应在各自 ext 中显式声明 "module" 字段
 * （如 {"host":"...","module":"4kvm"}），避免 host 推断不出时共用 "default" key。
 */
public class WasmLoader {

    private final ConcurrentHashMap<String, Spider> spiders;

    public WasmLoader() {
        spiders = new ConcurrentHashMap<>();
    }

    public void clear() {
        spiders.values().forEach(Spider::destroy);
        spiders.clear();
        // 卸载全部外置站点 wasm，回到内置站点分发模式（native 内已容错）
        try {
            if (RustBridge.isLoaded()) RustBridge.nativeClearSiteWasm();
        } catch (Throwable ignored) {
        }
    }

    public Spider getSpider(String key, String api, String ext) {
        return spiders.computeIfAbsent(key, k -> {
            try {
                File wasm = resolveWasm(api);
                if (wasm == null) return new SpiderNull();
                // 先 init（预热走内置分发，无网络 I/O），再注册 wasm：
                // 注册成功后本实例后续 6 个内容入口全部分派到 wasm 站点
                RustSpider spider = new RustSpider();
                spider.siteKey = key;
                spider.init(App.get(), ext);
                String result = RustBridge.nativeLoadSiteWasm(spider.extJson(), wasm.getAbsolutePath());
                if (!"ok".equals(result)) {
                    System.err.println("[WasmLoader] 加载 wasm 站点失败: " + result);
                    return new SpiderNull();
                }
                return spider;
            } catch (Throwable e) {
                e.printStackTrace();
                return new SpiderNull();
            }
        });
    }

    /** 解析 api 指向的 wasm 文件，返回 File；找不到返回 null */
    private File resolveWasm(String api) throws Exception {
        if (api == null) return null;
        String trimmed = api.trim();
        if (trimmed.startsWith("./")) trimmed = trimmed.substring(2);

        // 1) 网络 URL：下载缓存
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            File target = new File(Path.cache("rust"), Util.md5(trimmed) + ".wasm");
            if (!Path.exists(target)) download(trimmed, target);
            return Path.exists(target) ? target : null;
        }

        // 2) APK 内置 assets（wasm 与 ABI 无关，无需 abi 候选）
        String base = trimmed;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) base = base.substring(slash + 1);
        if (base.endsWith(".wasm")) base = base.substring(0, base.length() - 5);

        for (String candidate : new String[]{"wasm/" + base + ".wasm", base + ".wasm"}) {
            try (InputStream in = Asset.open(candidate)) {
                if (in != null) {
                    File target = new File(Path.cache("rust"), base + "-" + Util.md5(candidate) + ".wasm");
                    copy(in, target);
                    return target;
                }
            } catch (Throwable ignored) {
                // assets 中不存在该候选名，继续尝试下一个
            }
        }

        // 3) 本地路径（绝对路径直接使用；相对路径按缓存目录解析）
        File local = new File(trimmed);
        if (Path.exists(local)) return local;
        // ./rust/xxx.wasm → <cache>/rust/xxx.wasm（兼容 <cache>/rust/rust/xxx.wasm 旧双层写法）
        File rel = new File(Path.cache("rust"), trimmed);
        if (Path.exists(rel)) return rel;
        File relFlat = new File(Path.cache("rust"), base + ".wasm");
        if (Path.exists(relFlat)) return relFlat;
        return null;
    }

    private void download(String url, File target) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(30000);
        conn.connect();
        if (conn.getResponseCode() != 200) throw new Exception("下载 wasm 失败: HTTP " + conn.getResponseCode());
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
}

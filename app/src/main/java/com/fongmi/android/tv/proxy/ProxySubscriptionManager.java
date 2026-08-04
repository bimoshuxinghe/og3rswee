package com.fongmi.android.tv.proxy;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.bean.Proxy;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ProxySubscriptionManager {

    public static final String NAME = "subscription";
    private static final int MAX_THREADS = 20;
    private static final int TCP_TIMEOUT = 2000;
    private static final int DELAY_TIMEOUT = 3000;
    private static final String TEST_HOST = "www.gstatic.com";
    private static final String TEST_URL = "https://" + TEST_HOST + "/generate_204";
    private static final int TEST_TIMEOUT = 3000;
    private static final String[] FILTER_NAMES = {"更新订阅", "特殊时期", "如果是", "客户端太旧", "请到网站", "更新一下客户端"};

    private volatile List<ProxyNode> nodes;
    private volatile boolean testing = false;
    private final java.util.concurrent.atomic.AtomicInteger testedCount = new java.util.concurrent.atomic.AtomicInteger(0);
    private volatile Runnable progressCallback;
    private volatile long lastNotifyTime = 0;

    private static class Loader {
        static volatile ProxySubscriptionManager INSTANCE = new ProxySubscriptionManager();
    }

    public static ProxySubscriptionManager get() {
        return Loader.INSTANCE;
    }

    public boolean isTesting() {
        return testing;
    }

    public void setProgressCallback(Runnable callback) {
        this.progressCallback = callback;
    }

    private void notifyProgress() {
        if (progressCallback == null) return;
        // 节流：最多每300ms通知一次，避免主线程消息队列洪水
        long now = System.currentTimeMillis();
        if (now - lastNotifyTime < 300) return;
        lastNotifyTime = now;
        App.post(progressCallback);
    }

    public int getTestedCount() {
        return testedCount.get();
    }

    public boolean hasNodes() {
        return nodes != null && !nodes.isEmpty();
    }

    public String getSummary() {
        if (!Setting.isProxySubscriptionEnabled()) return "关闭";
        ProxyNode node = getSelected();
        if (node == null) return "未选择";
        return node.getDisplay();
    }

    public List<ProxyNode> getNodes() {
        List<ProxyNode> snapshot = nodes;
        if (snapshot != null) return new ArrayList<>(snapshot);
        synchronized (this) {
            if (nodes == null) {
                Type type = new TypeToken<List<ProxyNode>>() {}.getType();
                List<ProxyNode> items = App.gson().fromJson(Setting.getProxySubscriptionNodes(), type);
                nodes = items == null ? new ArrayList<>() : items;
            }
            return new ArrayList<>(nodes);
        }
    }

    public ProxyNode getSelected() {
        String selected = Setting.getProxySubscriptionSelected();
        if (TextUtils.isEmpty(selected)) return null;
        if (selected.startsWith("http://127.0.0.1:" + MihomoManager.getMixedPort())) {
            ProxyNode node = ProxyNode.mihomo(Setting.getProxySubscriptionCoreName());
            if (node != null) Setting.putProxySubscriptionSelected(node.getUrl());
            return node;
        }
        for (ProxyNode node : getNodes()) if (selected.equals(node.getUrl())) return node;
        return ProxyNode.fromUri(selected);
    }

    public String getProxyUrl() {
        ProxyNode node = getSelected();
        return Setting.isProxySubscriptionEnabled() && node != null && node.isSupported() ? node.getUrl() : "";
    }

    /**
     * 完全对齐反编译版 f.b() 中的 z.B("proxy_subscription_config")
     * 直接返回保存的订阅配置，不做任何验证或重新生成
     */
    private String getConfig() {
        return Setting.getProxySubscriptionConfig();
    }

    /**
     * 完全对齐反编译版 f.b() — 同步应用已保存的代理
     * 1. 取选中节点, 若启用且为支持的代理类型 → 取URL
     * 2. 若URL指向mihomo(127.0.0.1:18890)且mihomo未运行 → 同步启动mihomo
     * 3. 启动失败则return, 不应用代理; 成功则应用代理到OkHttp
     */
    public void applySaved() {
        ProxyNode node = getSelected();
        String url = (Setting.isProxySubscriptionEnabled() && node != null && node.isSupported()) ? node.getUrl() : "";
        if (TextUtils.isEmpty(url)) return;
        // 若是mihomo代理且mihomo未运行 → 先同步启动
        if (url.startsWith("http://127.0.0.1:" + MihomoManager.getMixedPort()) && !MihomoManager.get().isRunning()) {
            String config = getConfig();
            String coreName = Setting.getProxySubscriptionCoreName();
            if (!MihomoManager.get().start(config, coreName)) {
                android.util.Log.e("ProxySub", "applySaved: mihomo start failed, proxy not applied");
                return;
            }
        }
        OkHttp.selector().addOrReplace(Proxy.create(NAME, Arrays.asList("*"), Arrays.asList(url)));
        android.util.Log.d("ProxySub", "applySaved: proxy applied -> " + url);
    }

    /**
     * 完全对齐反编译版 f.l() — 选中节点
     * 对于支持的节点(http/socks5): 直接保存, 启用, 清除coreName, 调用applySaved()
     * 对于不支持的节点(vmess/ss等): 先启动mihomo, 再创建mihomo URL, 保存, 应用代理
     */
    public boolean select(ProxyNode node) {
        if (node == null) return false;
        if (node.isSupported()) {
            // http/socks5 代理: 直接应用, 不经过mihomo
            Setting.putProxySubscriptionSelected(node.getUrl());
            Setting.putProxySubscriptionEnabled(true);
            Setting.putProxySubscriptionCoreName("");
            applySaved();
            return true;
        }
        // vmess/ss/vless等: 需要mihomo转发
        String config = getConfig();
        if (TextUtils.isEmpty(config) || !MihomoManager.get().start(config, node.getName())) {
            android.util.Log.e("ProxySub", "select: mihomo start failed for " + node.getName());
            return false;
        }
        // mihomo启动成功, 创建本地mihomo代理URL
        ProxyNode local = ProxyNode.mihomo(node.getName());
        Setting.putProxySubscriptionCoreName(node.getName());
        Setting.putProxySubscriptionSelected(local.getUrl());
        Setting.putProxySubscriptionEnabled(true);
        OkHttp.selector().addOrReplace(Proxy.create(NAME, Arrays.asList("*"), Arrays.asList(local.getUrl())));
        android.util.Log.d("ProxySub", "select success: " + node.getName() + " -> " + local.getUrl());
        return true;
    }

    public void disable() {
        OkHttp.selector().remove(NAME);
        MihomoManager.get().stop();
    }

    public List<ProxyNode> refresh(String url) throws Exception {
        String text;
        if (isDirectProxyLink(url)) {
            text = url;
        } else {
            String decoded = decode(url.trim());
            if (isDirectProxyLink(decoded)) {
                android.util.Log.d("ProxySub", "refresh: input is base64-encoded proxy link");
                text = decoded;
            } else {
                text = fetch(url);
            }
        }
        if (TextUtils.isEmpty(text)) throw new Exception("Subscription returned empty content");
        String config = getClashConfig(text);
        List<ProxyNode> result;
        if (TextUtils.isEmpty(config)) {
            result = parse(text);
            config = generateClashConfig(result);
        } else {
            result = parse(config);
        }
        if (result.isEmpty()) throw new Exception("No valid proxy nodes found in subscription");
        if (TextUtils.isEmpty(config)) throw new Exception("Failed to generate config from parsed nodes");
        android.util.Log.d("ProxySub", "refresh: directLink=" + isDirectProxyLink(url) + " nodes=" + result.size() + " configLen=" + config.length());
        android.util.Log.d("ProxySub", "refresh generated config:\n" + (config.length() > 3000 ? config.substring(0, 3000) + "..." : config));
        nodes = null;
        Setting.putProxySubscriptionConfig(config);
        saveNodes(result);
        return result;
    }

    private boolean isDirectProxyLink(String input) {
        if (TextUtils.isEmpty(input)) return false;
        String lower = input.trim().toLowerCase(Locale.ROOT);
        String[] schemes = {"vless://", "vmess://", "ss://", "ssr://", "trojan://", "hysteria2://", "hy2://", "hysteria://", "hy://", "tuic://", "snell://", "anytls://", "wireguard://", "wg://", "juicity://"};
        for (String scheme : schemes) if (lower.startsWith(scheme)) return true;
        String[] lines = input.replace("\r", "\n").split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;
            for (String scheme : schemes) if (trimmed.toLowerCase(Locale.ROOT).startsWith(scheme)) return true;
        }
        return false;
    }

    private String fetch(String url) {
        String[] userAgents = {
            "Clash Verge/2.0.0", "ClashforWindows/0.20.39", "clash-meta/1.18.0",
            "v2rayN/6.0", "Shadowrocket/1900", "Quantumult/1.0",
            "Surge/5.0", "Mozilla/5.0"
        };
        for (String ua : userAgents) {
            try {
                String text = OkHttp.string(url, Map.of(
                    "User-Agent", ua,
                    "Accept", "text/plain, application/json, application/yaml, */*"
                ));
                if (!TextUtils.isEmpty(text) && text.length() > 10) {
                    android.util.Log.d("ProxySub", "fetch: success with UA=" + ua + " len=" + text.length());
                    return text;
                }
            } catch (Exception e) {
                android.util.Log.w("ProxySub", "fetch: failed with UA=" + ua + " err=" + e.getMessage());
            }
        }
        return OkHttp.string(url);
    }

    /**
     * 完全对齐反编译版 f.c() — 自动选择最优节点
     * 1. 找延迟最低的节点, 调用select()
     * 2. 若无, 找第一个需要mihomo的节点, 调用select()
     */
    public ProxyNode autoSelect() {
        ProxyNode best = null;
        for (ProxyNode node : getNodes()) {
            if (node.getLatency() > 0 && (best == null || node.getLatency() < best.getLatency())) {
                best = node;
            }
        }
        if (best != null) {
            return select(best) ? best : null;
        }
        ProxyNode first = firstCoreNode();
        return first != null && select(first) ? ProxyNode.mihomo(first.getName()) : null;
    }

    public List<ProxyNode> testAll() {
        if (testing) return getNodes();
        List<ProxyNode> allNodes = getNodes();
        if (allNodes.isEmpty()) return allNodes;
        testing = true;
        testedCount.set(0);
        lastNotifyTime = 0;
        try {
            return testAllInternal(allNodes);
        } finally {
            testing = false;
            notifyProgress();
        }
    }

    private List<ProxyNode> testAllInternal(List<ProxyNode> allNodes) {
        long startTime = System.currentTimeMillis();
        int totalNodes = allNodes.size();

        android.util.Log.d("ProxySub", "testAll: TCP test for " + totalNodes + " nodes (no lock, lightweight)");

        int tcpThreads = Math.min(MAX_THREADS, totalNodes);
        java.util.concurrent.ExecutorService tcpExecutor = java.util.concurrent.Executors.newFixedThreadPool(tcpThreads);
        java.util.concurrent.CountDownLatch tcpLatch = new java.util.concurrent.CountDownLatch(totalNodes);

        for (ProxyNode node : allNodes) {
            tcpExecutor.submit(() -> {
                try {
                    node.setLatency(testTcpReachability(node));
                } catch (Exception e) {
                    node.setLatency(-1);
                } finally {
                    tcpLatch.countDown();
                    testedCount.incrementAndGet();
                    notifyProgress();
                }
            });
        }

        try {
            tcpLatch.await(120, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        tcpExecutor.shutdownNow();

        testedCount.set(totalNodes);

        int reachable = 0;
        for (ProxyNode node : allNodes) {
            if (node.getLatency() > 0) reachable++;
        }
        android.util.Log.d("ProxySub", "testAll: done in " + (System.currentTimeMillis() - startTime) + "ms, " + reachable + " reachable, " + (totalNodes - reachable) + " unreachable");
        saveNodes(allNodes);
        return allNodes;
    }

    /**
     * 快速TCP可达性测试：测试节点的server:port是否可连接
     */
    private long testTcpReachability(ProxyNode node) {
        if (node == null) return -1;
        if (TextUtils.isEmpty(node.getHost()) || node.getPort() <= 0) return -1;
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(node.getHost(), node.getPort()), TCP_TIMEOUT);
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 通过mihomo delay API并行测试所有节点延迟
     * API: GET /proxies/{name}/delay?url=...&timeout=...
     * 无需切换selector，可完全并行
     */
    private void testMihomoNodesViaApi(List<ProxyNode> mihomoNodes) {
        String config = getConfig();
        if (TextUtils.isEmpty(config)) {
            android.util.Log.e("ProxySub", "testMihomoNodesViaApi: config empty, fallback to sequential");
            for (ProxyNode node : mihomoNodes) {
                node.setLatency(testOne(node));
            }
            return;
        }
        // 启动mihomo（使用第一个节点作为初始选择）
        String firstName = mihomoNodes.get(0).getName();
        if (!MihomoManager.get().start(config, firstName)) {
            android.util.Log.e("ProxySub", "testMihomoNodesViaApi: mihomo start failed, fallback to sequential");
            for (ProxyNode node : mihomoNodes) {
                node.setLatency(testOne(node));
            }
            return;
        }
        // 等待mihomo就绪
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        // 保存用户当前选中的节点，测试后恢复
        String originalName = Setting.getProxySubscriptionCoreName();

        // 并行调用delay API测试所有节点
        int threads = Math.min(50, mihomoNodes.size());
        java.util.concurrent.ExecutorService delayExecutor = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch delayLatch = new java.util.concurrent.CountDownLatch(mihomoNodes.size());
        for (ProxyNode node : mihomoNodes) {
            delayExecutor.submit(() -> {
                try {
                    long latency = testDelayViaApi(node.getName());
                    node.setLatency(latency);
                } catch (Exception e) {
                    node.setLatency(-1);
                } finally {
                    delayLatch.countDown();
                    testedCount.incrementAndGet();
                    notifyProgress();
                }
            });
        }
        try {
            // 动态计算超时: 每批(threads个)最多需要 DELAY_TIMEOUT+2 秒, 加上基础等待时间
            int batches = (mihomoNodes.size() + threads - 1) / threads;
            long totalTimeout = (long) batches * (DELAY_TIMEOUT + 2) + 10;
            android.util.Log.d("ProxySub", "testMihomoNodesViaApi: " + mihomoNodes.size() + " nodes, " + threads + " threads, timeout=" + totalTimeout + "s");
            delayLatch.await(totalTimeout, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        delayExecutor.shutdownNow();

        // 恢复用户原来选中的节点
        if (!TextUtils.isEmpty(originalName) && !originalName.equals(firstName)) {
            switchSelectorViaApi(originalName);
        }
    }

    /**
     * 通过API切换XYS_PROXY组的选中节点
     */
    private void switchSelectorViaApi(String name) {
        try {
            String apiUrl = "http://127.0.0.1:" + MihomoManager.getControllerPort() + "/proxies/XYS_PROXY";
            JsonObject body = new JsonObject();
            body.addProperty("name", name);
            okhttp3.MediaType jsonType = okhttp3.MediaType.parse("application/json; charset=utf-8");
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(apiUrl)
                    .put(okhttp3.RequestBody.create(body.toString(), jsonType))
                    .build();
            try (okhttp3.Response response = OkHttp.client(2000).newCall(request).execute()) {
                android.util.Log.d("ProxySub", "switchSelectorViaApi: " + name + " -> " + response.code());
            }
        } catch (Exception e) {
            android.util.Log.w("ProxySub", "switchSelectorViaApi error: " + e.getMessage());
        }
    }

    /**
     * 调用mihomo的delay API测试单个节点延迟
     * GET /proxies/{name}/delay?url=https://www.gstatic.com/generate_204&timeout=3000
     * 返回: {"delay": 123}
     */
    private long testDelayViaApi(String name) {
        try {
            String encodedName = java.net.URLEncoder.encode(name, "UTF-8");
            String apiUrl = "http://127.0.0.1:" + MihomoManager.getControllerPort()
                    + "/proxies/" + encodedName + "/delay"
                    + "?url=" + java.net.URLEncoder.encode(TEST_URL, "UTF-8")
                    + "&timeout=" + DELAY_TIMEOUT;
            okhttp3.Request request = new okhttp3.Request.Builder().url(apiUrl).get().build();
            try (okhttp3.Response response = OkHttp.client(DELAY_TIMEOUT + 1000).newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    android.util.Log.w("ProxySub", "testDelayViaApi: " + name + " HTTP " + response.code());
                    return -1;
                }
                String body = response.body() != null ? response.body().string() : "";
                JsonObject json = App.gson().fromJson(body, JsonObject.class);
                if (json != null && json.has("delay")) {
                    long delay = json.get("delay").getAsLong();
                    android.util.Log.d("ProxySub", "testDelayViaApi: " + name + " delay=" + delay);
                    return delay;
                }
                return -1;
            }
        } catch (Exception e) {
            android.util.Log.w("ProxySub", "testDelayViaApi: " + name + " error: " + e.getMessage());
            return -1;
        }
    }

    /**
     * 完全对齐反编译版 f.m() — 测试单个节点延迟
     * 对于支持的节点(http/socks5): 直接TCP测试
     * 对于不支持的节点(vmess/ss等): 先启动mihomo, 再通过mihomo代理发HTTP HEAD请求
     */
    public long testOne(ProxyNode node) {
        if (node == null) return -1;
        if (node.isSupported()) {
            // http/socks5: 直接TCP测试
            long latency = testTcpReachability(node);
            node.setLatency(latency);
            saveNodes(getNodes());
            return latency;
        }
        // vmess/ss等: 通过mihomo测试
        String config = getConfig();
        if (TextUtils.isEmpty(config)) {
            node.setLatency(-1);
            return -1;
        }
        long start = System.currentTimeMillis();
        if (!MihomoManager.get().start(config, node.getName())) {
            android.util.Log.e("ProxySub", "testOne: mihomo start failed for " + node.getName());
            node.setLatency(-1);
            return -1;
        }
        // 通过mihomo代理发HTTP HEAD请求测试
        long result = testViaMihomoProxy(start);
        node.setLatency(result);
        saveNodes(getNodes());
        return result;
    }

    /**
     * 通过mihomo本地代理(127.0.0.1:18890)发HTTP HEAD请求测试连通性
     * 对齐反编译版: OkHttpClient.Builder.proxy(HTTP, 127.0.0.1:18890) → HEAD gstatic.com/generate_204
     */
    private long testViaMihomoProxy(long startTime) {
        try {
            okhttp3.OkHttpClient proxyClient = OkHttp.client().newBuilder()
                    .proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", MihomoManager.getMixedPort())))
                    .connectTimeout(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .writeTimeout(3500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build();
            okhttp3.Request request = new okhttp3.Request.Builder()
                    .url(TEST_URL)
                    .head()
                    .build();
            try (okhttp3.Response response = proxyClient.newCall(request).execute()) {
                if (response.isSuccessful() || response.code() == 204) {
                    return System.currentTimeMillis() - startTime;
                }
                return -2;
            }
        } catch (Exception e) {
            android.util.Log.e("ProxySub", "testViaMihomoProxy: " + e.getMessage());
            return -2;
        }
    }

    public String mergeExt(String ext) {
        String proxy = getProxyUrl();
        if (TextUtils.isEmpty(proxy)) return ext;
        try {
            JsonObject object = TextUtils.isEmpty(ext) ? new JsonObject() : App.gson().fromJson(ext, JsonObject.class);
            if (object == null) object = new JsonObject();
            if (!object.has("proxy") || isSystemProxy(object.get("proxy").getAsString())) object.addProperty("proxy", proxy);
            object.addProperty("global_proxy", true);
            return object.toString();
        } catch (Exception e) {
            return ext;
        }
    }

    private boolean isSystemProxy(String proxy) {
        if (proxy == null) return true;
        String value = proxy.trim().toLowerCase(Locale.ROOT);
        return value.isEmpty() || "system".equals(value) || "vpn".equals(value) || "direct".equals(value) || "none".equals(value);
    }

    private void saveNodes(List<ProxyNode> items) {
        nodes = items;
        Setting.putProxySubscriptionNodes(App.gson().toJson(items));
    }

    private List<ProxyNode> parse(String text) {
        Map<String, ProxyNode> result = new LinkedHashMap<>();
        String decoded = decode(text);
        String decodedRaw = decodeRaw(text);
        // 尝试原始文本
        addLines(result, text);
        addClash(result, text);
        addSip008(result, text);
        addClashJson(result, text);
        // 尝试base64解码后的文本
        addLines(result, decoded);
        addLines(result, decodedRaw);
        addClash(result, decoded);
        addClash(result, decodedRaw);
        addSip008(result, decoded);
        addSip008(result, decodedRaw);
        addClashJson(result, decoded);
        addClashJson(result, decodedRaw);
        List<ProxyNode> nodes = new ArrayList<>();
        for (ProxyNode node : result.values()) if (isValidNode(node)) nodes.add(node);
        return nodes;
    }

    private boolean isValidNode(ProxyNode node) {
        if (node == null) return false;
        String name = node.getName();
        if (TextUtils.isEmpty(name)) return false;
        for (String filter : FILTER_NAMES) if (name.contains(filter)) return false;
        return true;
    }

    private void addLines(Map<String, ProxyNode> result, String text) {
        if (TextUtils.isEmpty(text)) return;
        for (String raw : text.replace("\r", "\n").split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("proxies:")) continue;
            ProxyNode node = parseLine(line);
            if (node != null) result.putIfAbsent(node.isSupported() ? node.getUrl() : line, node);
        }
    }

    private ProxyNode parseLine(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("socks://") || lower.startsWith("socks5://") || lower.startsWith("socks5h://")) return ProxyNode.fromUri(line);
        if (lower.startsWith("vmess://")) return parseVmess(line);
        if (lower.startsWith("ss://")) return makeNode(getFragmentName(line, "SS"), "ss", line);
        if (lower.startsWith("ssr://")) return makeNode(getFragmentName(line, "SSR"), "ssr", line);
        if (lower.startsWith("vless://")) return makeNode(getFragmentName(line, "VLESS"), "vless", line);
        if (lower.startsWith("trojan://")) return makeNode(getFragmentName(line, "Trojan"), "trojan", line);
        if (lower.startsWith("hysteria2://") || lower.startsWith("hy2://")) return makeNode(getFragmentName(line, "Hysteria2"), "hysteria2", line);
        if (lower.startsWith("hysteria://") || lower.startsWith("hy://")) return makeNode(getFragmentName(line, "Hysteria"), "hysteria", line);
        if (lower.startsWith("tuic://")) return makeNode(getFragmentName(line, "TUIC"), "tuic", line);
        if (lower.startsWith("snell://")) return makeNode(getFragmentName(line, "Snell"), "snell", line);
        if (lower.startsWith("anytls://")) return makeNode(getFragmentName(line, "AnyTLS"), "anytls", line);
        if (lower.startsWith("wireguard://") || lower.startsWith("wg://")) return makeNode(getFragmentName(line, "WireGuard"), "wireguard", line);
        if (lower.startsWith("juicity://")) return makeNode(getFragmentName(line, "Juicity"), "juicity", line);
        return null;
    }

    private ProxyNode makeNode(String name, String scheme, String rawUri) {
        // 从 URI 中解析 host 和 port，用于 TCP 可达性测试
        String[] hostPort = extractHostPort(scheme, rawUri);
        String host = hostPort[0];
        int port = -1;
        try { if (!TextUtils.isEmpty(hostPort[1])) port = Integer.parseInt(hostPort[1]); } catch (Exception ignored) {}
        ProxyNode node = ProxyNode.unsupported(name, scheme, host, port, rawUri);
        String yaml = toClashProxy(node);
        if (!TextUtils.isEmpty(yaml)) {
            node.setProxyYaml(yaml);
            android.util.Log.d("ProxySub", "makeNode: " + name + " [" + scheme + "] host=" + host + " port=" + port + " proxyYaml已设置");
        } else {
            android.util.Log.w("ProxySub", "makeNode: " + name + " [" + scheme + "] YAML生成失败，URI: " + rawUri);
        }
        return node;
    }

    /**
     * 从代理 URI 中提取 host 和 port，用于 TCP 可达性测试
     */
    private String[] extractHostPort(String scheme, String rawUri) {
        String[] result = new String[]{"", null};
        if (TextUtils.isEmpty(rawUri)) return result;
        try {
            if ("vmess".equals(scheme)) {
                String json = decode(rawUri.substring("vmess://".length()));
                JsonObject o = App.gson().fromJson(json, JsonObject.class);
                if (o != null) {
                    result[0] = str(o, "add");
                    result[1] = intStr(o, "port");
                }
            } else if ("ss".equals(scheme)) {
                String body = rawUri.substring("ss://".length());
                int hashIdx = body.indexOf('#');
                String main = hashIdx >= 0 ? body.substring(0, hashIdx) : body;
                int atIdx = main.indexOf('@');
                if (atIdx >= 0) {
                    String serverPort = main.substring(atIdx + 1);
                    String[] sp = serverPort.split(":");
                    if (sp.length >= 2) { result[0] = sp[0]; result[1] = sp[1]; }
                } else {
                    String decoded = decode(main);
                    if (decoded.contains("@")) {
                        atIdx = decoded.lastIndexOf('@');
                        String serverPort = decoded.substring(atIdx + 1);
                        String[] sp = serverPort.split(":");
                        if (sp.length >= 2) { result[0] = sp[0]; result[1] = sp[1]; }
                    }
                }
            } else if ("ssr".equals(scheme)) {
                String decoded = decode(rawUri.substring("ssr://".length()));
                String main = decoded.contains("/?") ? decoded.substring(0, decoded.indexOf("/?")) : decoded;
                String[] parts = main.split(":");
                if (parts.length >= 2) { result[0] = parts[0]; result[1] = parts[1]; }
            } else {
                // vless, trojan, hysteria2, hysteria, tuic, snell, anytls, wireguard, juicity
                Uri u = Uri.parse(rawUri);
                String h = u.getHost();
                int p = u.getPort();
                if (!TextUtils.isEmpty(h) && p > 0) {
                    result[0] = h;
                    result[1] = String.valueOf(p);
                } else {
                    // Uri.parse fallback for URIs it can't parse properly
                    String afterScheme = rawUri.substring(scheme.length() + 3);
                    int queryIdx = afterScheme.indexOf('?');
                    int fragIdx = afterScheme.indexOf('#');
                    String authPart = queryIdx >= 0 ? afterScheme.substring(0, queryIdx) : (fragIdx >= 0 ? afterScheme.substring(0, fragIdx) : afterScheme);
                    int atIdx = authPart.indexOf('@');
                    String hostPortStr = atIdx >= 0 ? authPart.substring(atIdx + 1) : authPart;
                    if (hostPortStr.startsWith("[")) {
                        int bracketEnd = hostPortStr.indexOf(']');
                        if (bracketEnd > 0) {
                            result[0] = hostPortStr.substring(1, bracketEnd);
                            String afterBracket = hostPortStr.substring(bracketEnd + 1);
                            if (afterBracket.startsWith(":")) result[1] = afterBracket.substring(1);
                        }
                    } else {
                        int colonIdx = hostPortStr.lastIndexOf(':');
                        if (colonIdx >= 0) {
                            result[0] = hostPortStr.substring(0, colonIdx);
                            result[1] = hostPortStr.substring(colonIdx + 1);
                        } else {
                            result[0] = hostPortStr;
                        }
                    }
                }
            }
        } catch (Exception e) {
            android.util.Log.w("ProxySub", "extractHostPort: failed for " + scheme + " - " + e.getMessage());
        }
        // 清理 host 前面的点
        if (!TextUtils.isEmpty(result[0])) {
            while (result[0].startsWith(".")) result[0] = result[0].substring(1);
        }
        return result;
    }

    private ProxyNode parseVmess(String line) {
        try {
            String json = decode(line.substring("vmess://".length()));
            JsonObject object = App.gson().fromJson(json, JsonObject.class);
            String name = object != null && object.has("ps") ? object.get("ps").getAsString() : "VMess";
            return makeNode(name, "vmess", line);
        } catch (Exception e) {
            return makeNode("VMess", "vmess", line);
        }
    }

    private String getFragmentName(String line, String fallback) {
        int index = line.indexOf('#');
        return index == -1 ? fallback : Uri.decode(line.substring(index + 1));
    }

    private void addClash(Map<String, ProxyNode> result, String text) {
        if (TextUtils.isEmpty(text)) return;
        String name = "";
        String type = "";
        String server = "";
        int port = -1;
        boolean proxies = false;
        StringBuilder yamlBuilder = new StringBuilder();
        for (String raw : text.replace("\r", "\n").split("\n")) {
            String line = raw.trim();
            if (isTopLevel(raw)) {
                if (proxies && !TextUtils.isEmpty(name)) {
                    ProxyNode node = clashNode(name, type, server, port);
                    if (node != null) {
                        node.setProxyYaml(yamlBuilder.toString());
                        result.putIfAbsent(node.isSupported() ? node.getUrl() : name + type + server + port, node);
                    }
                }
                proxies = line.startsWith("proxies:");
                name = "";
                type = "";
                server = "";
                port = -1;
                yamlBuilder.setLength(0);
                continue;
            }
            if (!proxies) continue;
            if (line.startsWith("-")) {
                if (!TextUtils.isEmpty(name)) {
                    ProxyNode node = clashNode(name, type, server, port);
                    if (node != null) {
                        node.setProxyYaml(yamlBuilder.toString());
                        result.putIfAbsent(node.isSupported() ? node.getUrl() : name + type + server + port, node);
                    }
                }
                name = value(line, "name");
                type = value(line, "type");
                server = value(line, "server");
                port = parsePort(value(line, "port"));
                yamlBuilder.setLength(0);
            } else {
                if (TextUtils.isEmpty(name)) name = value(line, "name");
                if (TextUtils.isEmpty(type)) type = value(line, "type");
                if (TextUtils.isEmpty(server)) server = value(line, "server");
                if (port <= 0) port = parsePort(value(line, "port"));
            }
            yamlBuilder.append(raw).append("\n");
        }
        if (proxies && !TextUtils.isEmpty(name)) {
            ProxyNode node = clashNode(name, type, server, port);
            if (node != null) {
                node.setProxyYaml(yamlBuilder.toString());
                result.putIfAbsent(node.isSupported() ? node.getUrl() : name + type + server + port, node);
            }
        }
    }

    private ProxyNode clashNode(String name, String type, String server, int port) {
        if (TextUtils.isEmpty(type) || TextUtils.isEmpty(server) || port <= 0) return null;
        String scheme = type.toLowerCase(Locale.ROOT);
        if ("socks5".equals(scheme)) scheme = "socks5";
        else if ("http".equals(scheme) || "https".equals(scheme)) scheme = "http";
        else return ProxyNode.unsupported(TextUtils.isEmpty(name) ? type : name, type, server, port);
        return ProxyNode.fromUri(scheme + "://" + server + ":" + port + "#" + Uri.encode(TextUtils.isEmpty(name) ? server : name));
    }

    private boolean isTopLevel(String line) {
        return !TextUtils.isEmpty(line) && !Character.isWhitespace(line.charAt(0)) && line.contains(":");
    }

    private void addSip008(Map<String, ProxyNode> result, String text) {
        if (TextUtils.isEmpty(text)) return;
        String trimmed = text.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("[")) return;
        try {
            com.google.gson.JsonElement element = App.gson().fromJson(trimmed, com.google.gson.JsonElement.class);
            if (element == null) return;
            // 支持 JSON 数组格式：[{...}, {...}]
            if (element.isJsonArray()) {
                for (com.google.gson.JsonElement item : element.getAsJsonArray()) {
                    if (!item.isJsonObject()) continue;
                    parseSip008Server(result, item.getAsJsonObject());
                }
                return;
            }
            // 支持 JSON 对象格式：{"servers": [{...}, ...]}
            if (!element.isJsonObject()) return;
            JsonObject root = element.getAsJsonObject();
            if (!root.has("servers")) return;
            for (com.google.gson.JsonElement item : root.getAsJsonArray("servers")) {
                if (!item.isJsonObject()) continue;
                parseSip008Server(result, item.getAsJsonObject());
            }
        } catch (Exception e) {
            // Not SIP008 format
        }
    }

    private void parseSip008Server(Map<String, ProxyNode> result, JsonObject server) {
        String serverAddr = server.has("server") ? server.get("server").getAsString() : "";
        int port = server.has("server_port") ? server.get("server_port").getAsInt() : (server.has("port") ? server.get("port").getAsInt() : -1);
        if (TextUtils.isEmpty(serverAddr) || port <= 0) return;
        String name = server.has("remarks") ? server.get("remarks").getAsString() : (server.has("name") ? server.get("name").getAsString() : serverAddr + ":" + port);
        String method = server.has("method") ? server.get("method").getAsString() : "";
        String password = server.has("password") ? server.get("password").getAsString() : "";
        String ssUri = "ss://" + android.util.Base64.encodeToString((method + ":" + password).getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP) + "@" + serverAddr + ":" + port + "#" + Uri.encode(name);
        ProxyNode node = makeNode(name, "ss", ssUri);
        result.putIfAbsent(ssUri, node);
    }

    /**
     * 解析 Clash API JSON 格式的订阅（proxies 为 JSON 数组）
     * 例如：{"proxies": [{"name": "node1", "type": "vmess", "server": "...", "port": 443, ...}]}
     */
    private void addClashJson(Map<String, ProxyNode> result, String text) {
        if (TextUtils.isEmpty(text)) return;
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) return;
        try {
            com.google.gson.JsonElement element = App.gson().fromJson(trimmed, com.google.gson.JsonElement.class);
            if (element == null || !element.isJsonObject()) return;
            JsonObject root = element.getAsJsonObject();
            if (!root.has("proxies") || !root.get("proxies").isJsonArray()) return;
            for (com.google.gson.JsonElement item : root.getAsJsonArray("proxies")) {
                if (!item.isJsonObject()) continue;
                JsonObject proxy = item.getAsJsonObject();
                String name = proxy.has("name") ? proxy.get("name").getAsString() : "";
                String type = proxy.has("type") ? proxy.get("type").getAsString() : "";
                String server = proxy.has("server") ? proxy.get("server").getAsString() : "";
                int port = proxy.has("port") ? proxy.get("port").getAsInt() : -1;
                if (TextUtils.isEmpty(type) || TextUtils.isEmpty(server) || port <= 0) continue;
                // 生成 YAML 片段
                StringBuilder yaml = new StringBuilder();
                yaml.append("- name: ").append(name).append("\n");
                yaml.append("  type: ").append(type).append("\n");
                yaml.append("  server: ").append(server).append("\n");
                yaml.append("  port: ").append(port).append("\n");
                for (Map.Entry<String, com.google.gson.JsonElement> entry : proxy.entrySet()) {
                    String key = entry.getKey();
                    if (key.equals("name") || key.equals("type") || key.equals("server") || key.equals("port")) continue;
                    String val = entry.getValue().isJsonPrimitive() ? entry.getValue().getAsString() : entry.getValue().toString();
                    yaml.append("  ").append(key).append(": ").append(val).append("\n");
                }
                ProxyNode node = clashNode(name, type, server, port);
                if (node != null) {
                    node.setProxyYaml(yaml.toString());
                    result.putIfAbsent(node.isSupported() ? node.getUrl() : name + type + server + port, node);
                }
            }
            android.util.Log.d("ProxySub", "addClashJson: parsed " + root.getAsJsonArray("proxies").size() + " proxies from JSON");
        } catch (Exception e) {
            // Not Clash JSON format
        }
    }

    private boolean isClashConfig(String text) {
        return !TextUtils.isEmpty(text) && text.contains("proxies:");
    }

    private boolean isClashJsonConfig(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) return false;
        try {
            com.google.gson.JsonElement element = App.gson().fromJson(trimmed, com.google.gson.JsonElement.class);
            return element != null && element.isJsonObject() && element.getAsJsonObject().has("proxies");
        } catch (Exception e) {
            return false;
        }
    }

    private String getClashConfig(String text) {
        if (isClashConfig(text)) return text;
        if (isClashJsonConfig(text)) return text;
        String decoded = decode(text);
        if (isClashConfig(decoded)) return decoded;
        if (isClashJsonConfig(decoded)) return decoded;
        String decodedRaw = decodeRaw(text);
        if (isClashConfig(decodedRaw)) return decodedRaw;
        if (isClashJsonConfig(decodedRaw)) return decodedRaw;
        return "";
    }

    private boolean hasCoreNodes() {
        return firstCoreNode() != null;
    }

    private ProxyNode firstCoreNode() {
        for (ProxyNode node : getNodes()) if (node.needsCore()) return node;
        return null;
    }

    private boolean isMihomo(String url) {
        return url != null && url.startsWith("http://127.0.0.1:" + 18890);
    }

    private String value(String line, String key) {
        String searchKey = key + ":";
        int index = -1;
        int from = 0;
        while (true) {
            int found = line.indexOf(searchKey, from);
            if (found == -1) return "";
            int before = found - 1;
            if (before < 0 || !Character.isLetterOrDigit(line.charAt(before)) && line.charAt(before) != '-') {
                index = found;
                break;
            }
            from = found + searchKey.length();
        }
        String value = line.substring(index + searchKey.length()).trim();
        int comma = value.indexOf(',');
        if (comma != -1) value = value.substring(0, comma);
        return value.replace("{", "").replace("}", "").replace("\"", "").replace("'", "").trim();
    }

    private int parsePort(String value) {
        try {
            return TextUtils.isEmpty(value) ? -1 : Integer.parseInt(value.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    private String generateClashConfig(List<ProxyNode> nodes) {
        StringBuilder proxies = new StringBuilder();
        StringBuilder names = new StringBuilder();
        for (ProxyNode node : nodes) {
            String entry = toClashProxy(node);
            if (entry == null) continue;
            proxies.append(entry).append("\n");
            names.append("      - ").append(quote(node.getName())).append("\n");
        }
        if (proxies.length() == 0) return "";
        return "mode: rule\n" +
                "ipv6: false\n" +
                "allow-lan: false\n" +
                "proxies:\n" +
                proxies +
                "proxy-groups:\n" +
                "  - name: XYS_PROXY\n" +
                "    type: select\n" +
                "    proxies:\n" +
                names +
                "rules:\n" +
                // 本地网络直连
                "  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve\n" +
                "  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve\n" +
                "  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve\n" +
                "  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve\n" +
                "  - IP-CIDR,100.64.0.0/10,DIRECT,no-resolve\n" +
                "  - IP-CIDR,169.254.0.0/16,DIRECT,no-resolve\n" +
                "  - IP-CIDR,224.0.0.0/4,DIRECT,no-resolve\n" +
                // 国内域名直连
                "  - DOMAIN-SUFFIX,cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,com.cn,DIRECT\n" +
                // 爱奇艺
                "  - DOMAIN-SUFFIX,iqiyipic.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,iqiyi.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,iq.com,DIRECT\n" +
                "  - DOMAIN-KEYWORD,iqiyi,DIRECT\n" +
                // 腾讯视频
                "  - DOMAIN-SUFFIX,qq.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,qpic.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,gtimg.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,tencent.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,myqcloud.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,tencdns.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,cdntip.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,tencent-cloud.net,DIRECT\n" +
                "  - DOMAIN-SUFFIX,weishi.qq.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,txkt.cn,DIRECT\n" +
                // 优酷
                "  - DOMAIN-SUFFIX,youku.com,DIRECT\n" +
                // B站
                "  - DOMAIN-SUFFIX,bilibili.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,hdslb.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bilivideo.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bilivideo.cn,DIRECT\n" +
                // 湖南卫视/MGTV
                "  - DOMAIN-SUFFIX,mgtv.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,hunantv.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,tvmao.com,DIRECT\n" +
                // 搜狐
                "  - DOMAIN-SUFFIX,sohu.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,sohucs.com,DIRECT\n" +
                // 乐视/PPTV
                "  - DOMAIN-SUFFIX,letv.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,le.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,pptv.com,DIRECT\n" +
                // 咪咕
                "  - DOMAIN-SUFFIX,miguvideo.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,migu.cn,DIRECT\n" +
                // 凤凰
                "  - DOMAIN-SUFFIX,ifeng.com,DIRECT\n" +
                // CCTV/央视
                "  - DOMAIN-SUFFIX,cctv.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,cntv.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,yangshipin.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,ipanda.com,DIRECT\n" +
                // 快手
                "  - DOMAIN-SUFFIX,kuaishou.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,gifshow.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,yximgs.com,DIRECT\n" +
                // 抖音/字节
                "  - DOMAIN-SUFFIX,douyin.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,douyincdn.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,douyinpic.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,douyinstatic.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bytecdn.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,byteimg.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,pstatp.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,snssdk.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,ixigua.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bytedance.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bytednsdoc.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bytegoofy.com,DIRECT\n" +
                // 阿里系
                "  - DOMAIN-SUFFIX,taobao.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,alicdn.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,aliyuncs.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,aliyun.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,mmstat.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,tmall.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,xiami.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,alibaba.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,aliexpress.com,DIRECT\n" +
                // 百度系
                "  - DOMAIN-SUFFIX,bdstatic.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,baidu.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bdimg.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bcelive.com,DIRECT\n" +
                // 360
                "  - DOMAIN-SUFFIX,360kan.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,haokan.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,360.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,360.com,DIRECT\n" +
                // CDN
                "  - DOMAIN-SUFFIX,ksyuncdn.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,ksyun.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,qiniudn.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,qiniucdn.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,upyun.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,upaiyun.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,hapame.com,DIRECT\n" +
                // 小米/华为
                "  - DOMAIN-SUFFIX,xiaomi.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,mi.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,huawei.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,wdstm.com,DIRECT\n" +
                // 其他国内
                "  - DOMAIN-SUFFIX,1234567.com.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,jstv.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,189.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,weibo.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,sina.com.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,sinaimg.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,xhscdn.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,xiaohongshu.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,zhihu.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,jd.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,jdcloud.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,pinduoduo.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,yangkeduo.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,utm.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,pages.dev,DIRECT\n" +
                // 兜底走代理
                "  - MATCH,XYS_PROXY\n";
    }

    private String toClashProxy(ProxyNode node) {
        if (node == null) return null;
        if (!TextUtils.isEmpty(node.getProxyYaml())) return node.getProxyYaml();
        if (node.isSupported()) {
            String scheme = node.getScheme();
            if ("socks5".equals(scheme)) {
                return clashProxyEntry("socks5", node.getName(), node.getHost(), node.getPort(),
                        "username", node.getUserInfo(), "password", "");
            }
            return clashProxyEntry("http", node.getName(), node.getHost(), node.getPort(), null, null, null, null);
        }
        String uri = node.getRawUri();
        if (TextUtils.isEmpty(uri)) return null;
        String scheme = node.getScheme();
        if ("vmess".equals(scheme)) return vmessToClash(uri, node.getName());
        if ("ss".equals(scheme)) return ssToClash(uri, node.getName());
        if ("ssr".equals(scheme)) return ssrToClash(uri, node.getName());
        if ("vless".equals(scheme)) return vlessToClash(uri, node.getName());
        if ("trojan".equals(scheme)) return trojanToClash(uri, node.getName());
        if ("hysteria2".equals(scheme)) return hysteria2ToClash(uri, node.getName());
        if ("hysteria".equals(scheme)) return hysteriaToClash(uri, node.getName());
        if ("tuic".equals(scheme)) return tuicToClash(uri, node.getName());
        if ("snell".equals(scheme)) return snellToClash(uri, node.getName());
        if ("anytls".equals(scheme)) return anytlsToClash(uri, node.getName());
        if ("wireguard".equals(scheme)) return wireguardToClash(uri, node.getName());
        if ("juicity".equals(scheme)) return juicityToClash(uri, node.getName());
        return null;
    }

    private String clashProxyEntry(String type, String name, String server, int port, String userKey, String userVal, String passKey, String passVal) {
        StringBuilder sb = new StringBuilder();
        sb.append("  - name: ").append(quote(name)).append("\n");
        sb.append("    type: ").append(type).append("\n");
        sb.append("    server: ").append(server).append("\n");
        sb.append("    port: ").append(port).append("\n");
        sb.append("    udp: true\n");
        if (userKey != null && !TextUtils.isEmpty(userVal)) {
            sb.append("    ").append(userKey).append(": ").append(quote(userVal)).append("\n");
            sb.append("    ").append(passKey).append(": ").append(quote(passVal)).append("\n");
        }
        return sb.toString();
    }

    private String vmessToClash(String uri, String name) {
        try {
            String json = decode(uri.substring("vmess://".length()));
            JsonObject o = App.gson().fromJson(json, JsonObject.class);
            if (o == null) return null;
            StringBuilder sb = new StringBuilder();
            sb.append("  - name: ").append(quote(name)).append("\n");
            sb.append("    type: vmess\n");
            sb.append("    server: ").append(str(o, "add")).append("\n");
            sb.append("    port: ").append(intStr(o, "port")).append("\n");
            sb.append("    uuid: ").append(quote(str(o, "id"))).append("\n");
            sb.append("    alterId: ").append(o.has("aid") ? intStr(o, "aid") : "0").append("\n");
            sb.append("    cipher: ").append(o.has("scy") ? str(o, "scy") : "auto").append("\n");
            sb.append("    udp: true\n");
            String net = o.has("net") ? str(o, "net") : "tcp";
            if (!"tcp".equals(net)) {
                sb.append("    network: ").append(net).append("\n");
                if ("ws".equals(net) && o.has("path")) {
                    sb.append("    ws-opts:\n");
                    sb.append("      path: ").append(quote(str(o, "path"))).append("\n");
                    if (o.has("host")) sb.append("      headers:\n        Host: ").append(quote(str(o, "host"))).append("\n");
                }
                if ("grpc".equals(net) && o.has("path")) sb.append("    grpc-opts:\n      grpc-service-name: ").append(quote(str(o, "path"))).append("\n");
            } else {
                sb.append("    network: tcp\n");
            }
            if (o.has("tls") && ("tls".equals(str(o, "tls")) || "1".equals(str(o, "tls")))) {
                sb.append("    tls: true\n");
                if (o.has("sni")) sb.append("    servername: ").append(quote(str(o, "sni"))).append("\n");
                if (o.has("allowInsecure") && ("1".equals(str(o, "allowInsecure")) || "true".equals(str(o, "allowInsecure")))) sb.append("    skip-cert-verify: true\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String ssToClash(String uri, String name) {
        try {
            String body = uri.substring("ss://".length());
            int hashIdx = body.indexOf('#');
            String main = hashIdx >= 0 ? body.substring(0, hashIdx) : body;
            int atIdx = main.indexOf('@');
            String method, password, server, port;
            if (atIdx >= 0) {
                String userInfo = main.substring(0, atIdx);
                String serverPort = main.substring(atIdx + 1);
                String decoded = decode(userInfo);
                if (decoded.isEmpty() || !decoded.contains(":")) decoded = userInfo;
                int colonIdx = decoded.indexOf(':');
                if (colonIdx < 0) return null;
                method = decoded.substring(0, colonIdx);
                password = decoded.substring(colonIdx + 1);
                String[] sp = serverPort.split(":");
                if (sp.length < 2) return null;
                server = sp[0];
                port = sp[1];
            } else {
                String decoded = decode(main);
                if (decoded.isEmpty() || !decoded.contains("@")) return null;
                atIdx = decoded.lastIndexOf('@');
                String userInfo = decoded.substring(0, atIdx);
                String serverPort = decoded.substring(atIdx + 1);
                int colonIdx = userInfo.indexOf(':');
                if (colonIdx < 0) return null;
                method = userInfo.substring(0, colonIdx);
                password = userInfo.substring(colonIdx + 1);
                String[] sp = serverPort.split(":");
                if (sp.length < 2) return null;
                server = sp[0];
                port = sp[1];
            }
            StringBuilder sb = new StringBuilder();
            sb.append("  - name: ").append(quote(name)).append("\n");
            sb.append("    type: ss\n");
            sb.append("    server: ").append(server).append("\n");
            sb.append("    port: ").append(port).append("\n");
            sb.append("    cipher: ").append(method).append("\n");
            sb.append("    password: ").append(quote(password)).append("\n");
            sb.append("    udp: true\n");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String ssrToClash(String uri, String name) {
        try {
            String decoded = decode(uri.substring("ssr://".length()));
            if (TextUtils.isEmpty(decoded)) return null;
            int slashIdx = decoded.indexOf("/?");
            String main = slashIdx >= 0 ? decoded.substring(0, slashIdx) : decoded;
            String[] parts = main.split(":");
            if (parts.length < 6) return null;
            StringBuilder sb = new StringBuilder();
            sb.append("  - name: ").append(quote(name)).append("\n");
            sb.append("    type: ssr\n");
            sb.append("    server: ").append(parts[0]).append("\n");
            sb.append("    port: ").append(parts[1]).append("\n");
            sb.append("    cipher: ").append(parts[3]).append("\n");
            String pwdB64 = parts[5];
            String pwd = decode(pwdB64);
            sb.append("    password: ").append(quote(TextUtils.isEmpty(pwd) ? pwdB64 : pwd)).append("\n");
            sb.append("    protocol: ").append(parts[2]).append("\n");
            sb.append("    obfs: ").append(parts[4]).append("\n");
            sb.append("    udp: true\n");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String getParam(Uri uri, String... names) {
        for (String name : names) {
            String value = uri.getQueryParameter(name);
            if (value != null) return value;
        }
        Set<String> paramNames = uri.getQueryParameterNames();
        for (String name : names) {
            for (String paramName : paramNames) {
                if (paramName.equalsIgnoreCase(name)) return uri.getQueryParameter(paramName);
            }
        }
        return null;
    }

    private String vlessToClash(String uri, String name) {
        try {
            android.util.Log.d("ProxySub", "vlessToClash input URI: " + uri);
            Uri u = Uri.parse(uri);
            String host = u.getHost();
            int port = u.getPort();
            if (host == null || port <= 0) {
                String afterScheme = uri.substring("vless://".length());
                int queryIdx = afterScheme.indexOf('?');
                int fragIdx = afterScheme.indexOf('#');
                String authPart = queryIdx >= 0 ? afterScheme.substring(0, queryIdx) : (fragIdx >= 0 ? afterScheme.substring(0, fragIdx) : afterScheme);
                int atIdx = authPart.indexOf('@');
                String hostPort = atIdx >= 0 ? authPart.substring(atIdx + 1) : authPart;
                if (hostPort.startsWith("[")) {
                    int bracketEnd = hostPort.indexOf(']');
                    if (bracketEnd > 0) {
                        host = hostPort.substring(1, bracketEnd);
                        String afterBracket = hostPort.substring(bracketEnd + 1);
                        if (afterBracket.startsWith(":")) { try { port = Integer.parseInt(afterBracket.substring(1)); } catch (Exception ignored) {} }
                    }
                } else {
                    int colonIdx = hostPort.lastIndexOf(':');
                    if (colonIdx >= 0) {
                        host = hostPort.substring(0, colonIdx);
                        try { port = Integer.parseInt(hostPort.substring(colonIdx + 1)); } catch (Exception ignored) {}
                    } else { host = hostPort; }
                }
                android.util.Log.d("ProxySub", "vlessToClash: Uri.parse fallback, host=" + host + " port=" + port);
            }
            if (host == null || host.isEmpty() || port <= 0) {
                android.util.Log.e("ProxySub", "vlessToClash: invalid host or port in URI: " + uri);
                return null;
            }
            while (host.startsWith(".")) host = host.substring(1);
            String uuid = u.getUserInfo() != null ? u.getUserInfo() : "";
            if (uuid.isEmpty()) { String uuidParam = getParam(u, "uuid"); if (uuidParam != null) uuid = uuidParam; }
            StringBuilder sb = new StringBuilder();
            sb.append("  - name: ").append(quote(name)).append("\n");
            sb.append("    type: vless\n");
            sb.append("    server: ").append(host).append("\n");
            sb.append("    port: ").append(port).append("\n");
            sb.append("    uuid: ").append(quote(uuid)).append("\n");
            sb.append("    udp: true\n");
            String network = getParam(u, "type");
            if (network == null) network = "tcp";
            String headerType = getParam(u, "headerType", "header-type");
            if ("ws".equals(network)) {
                sb.append("    network: ws\n");
                sb.append("    ws-opts:\n");
                String path = getParam(u, "path");
                if (path == null) path = "/";
                String ed = getParam(u, "ed");
                if (ed == null && path.contains("?ed=")) { int i = path.indexOf("?ed="); ed = path.substring(i + 4); int a = ed.indexOf('&'); if (a >= 0) ed = ed.substring(0, a); path = path.substring(0, i); }
                else if (ed == null && path.contains("&ed=")) { int i = path.indexOf("&ed="); ed = path.substring(i + 4); int a = ed.indexOf('&'); if (a >= 0) ed = ed.substring(0, a); path = path.substring(0, i); }
                sb.append("      path: ").append(quote(path)).append("\n");
                String wsHost = getParam(u, "host");
                if (wsHost != null) sb.append("      headers:\n        Host: ").append(quote(wsHost)).append("\n");
                if (ed != null) { try { sb.append("      max-early-data: ").append(Integer.parseInt(ed.trim())).append("\n"); sb.append("      early-data-header-name: Sec-WebSocket-Protocol\n"); } catch (NumberFormatException ignored) {} }
            } else if ("grpc".equals(network)) {
                sb.append("    network: grpc\n");
                sb.append("    grpc-opts:\n");
                String sn = getParam(u, "serviceName", "service-name");
                sb.append("      grpc-service-name: ").append(quote(sn != null ? sn : "")).append("\n");
            } else if ("http".equals(network) || (headerType != null && "http".equals(headerType))) {
                sb.append("    network: http\n");
                sb.append("    http-opts:\n");
                String path = getParam(u, "path");
                if (path != null) sb.append("      path:\n        - ").append(quote(path)).append("\n");
                String httpHost = getParam(u, "host");
                if (httpHost != null) sb.append("      headers:\n        Host:\n          - ").append(quote(httpHost)).append("\n");
            } else {
                sb.append("    network: tcp\n");
            }
            String security = getParam(u, "security");
            String sni = getParam(u, "sni");
            String pbk = getParam(u, "pbk", "public-key");
            String sid = getParam(u, "sid", "short-id");
            boolean isReality = (security != null && "reality".equals(security)) || pbk != null;
            boolean isTls = (security != null && "tls".equals(security)) || isReality;
            if (pbk != null) {
                pbk = pbk.replace("+", "-").replace("/", "_").replaceAll("=+$", "");
                android.util.Log.d("ProxySub", "vlessToClash: pbk=" + pbk);
            }
            String flow = getParam(u, "flow");
            if (flow == null && isReality && "tcp".equals(network)) flow = "xtls-rprx-vision";
            android.util.Log.d("ProxySub", "vlessToClash: flow=" + flow + " isReality=" + isReality + " isTls=" + isTls + " network=" + network);
            if (flow != null && isTls) sb.append("    flow: ").append(flow).append("\n");
            String allowInsecure = getParam(u, "allowInsecure", "allow-insecure", "insecure");
            boolean skipCertVerify = "1".equals(allowInsecure) || "true".equalsIgnoreCase(allowInsecure);
            if (isTls) {
                sb.append("    tls: true\n");
                sb.append("    servername: ").append(quote(sni != null ? sni : host)).append("\n");
                if (skipCertVerify) sb.append("    skip-cert-verify: true\n");
            }
            if (isReality) {
                sb.append("    reality-opts:\n");
                if (pbk != null) sb.append("      public-key: ").append(quote(pbk)).append("\n");
                if (sid != null) sb.append("      short-id: ").append(quote(sid)).append("\n");
            }
            String alpn = getParam(u, "alpn");
            if (alpn != null) { sb.append("    alpn:\n"); for (String a : alpn.split(",")) sb.append("      - ").append(quote(a.trim())).append("\n"); }
            String fp = getParam(u, "fp", "fingerprint");
            if (fp != null) sb.append("    client-fingerprint: ").append(fp).append("\n");
            else if (isReality) sb.append("    client-fingerprint: chrome\n");
            android.util.Log.d("ProxySub", "vlessToClash: " + name + " -> " + sb.toString().replace("\n", " | "));
            return sb.toString();
        } catch (Exception e) {
            android.util.Log.e("ProxySub", "vlessToClash failed: " + e.getMessage(), e);
            return null;
        }
    }

    private String trojanToClash(String uri, String name) {
        try {
            Uri u = Uri.parse(uri);
            StringBuilder sb = new StringBuilder();
            sb.append("  - name: ").append(quote(name)).append("\n");
            sb.append("    type: trojan\n");
            sb.append("    server: ").append(u.getHost()).append("\n");
            sb.append("    port: ").append(u.getPort()).append("\n");
            sb.append("    password: ").append(quote(u.getUserInfo() != null ? u.getUserInfo() : "")).append("\n");
            sb.append("    udp: true\n");
            if (u.getQueryParameter("sni") != null) sb.append("    sni: ").append(quote(u.getQueryParameter("sni"))).append("\n");
            if (u.getQueryParameter("allowInsecure") != null && "1".equals(u.getQueryParameter("allowInsecure"))) sb.append("    skip-cert-verify: true\n");
            String network = u.getQueryParameter("type");
            if (network != null && !"tcp".equals(network)) {
                sb.append("    network: ").append(network).append("\n");
                if ("ws".equals(network)) {
                    sb.append("    ws-opts:\n");
                    if (u.getQueryParameter("path") != null) sb.append("      path: ").append(quote(u.getQueryParameter("path"))).append("\n");
                    if (u.getQueryParameter("host") != null) sb.append("      headers:\n        Host: ").append(quote(u.getQueryParameter("host"))).append("\n");
                }
            } else {
                sb.append("    network: tcp\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String hysteria2ToClash(String uri, String name) {
        try {
            Uri u = Uri.parse(uri);
            StringBuilder sb = new StringBuilder();
            sb.append("  - name: ").append(quote(name)).append("\n");
            sb.append("    type: hysteria2\n");
            sb.append("    server: ").append(u.getHost()).append("\n");
            sb.append("    port: ").append(u.getPort()).append("\n");
            sb.append("    udp: true\n");
            if (u.getUserInfo() != null) sb.append("    password: ").append(quote(u.getUserInfo())).append("\n");
            if (u.getQueryParameter("sni") != null) sb.append("    sni: ").append(quote(u.getQueryParameter("sni"))).append("\n");
            if (u.getQueryParameter("insecure") != null && "1".equals(u.getQueryParameter("insecure"))) sb.append("    skip-cert-verify: true\n");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String hysteriaToClash(String uri, String name) {
        try {
            Uri u = Uri.parse(uri);
            StringBuilder sb = new StringBuilder();
            sb.append("  - name: ").append(quote(name)).append("\n");
            sb.append("    type: hysteria\n");
            sb.append("    server: ").append(u.getHost()).append("\n");
            sb.append("    port: ").append(u.getPort()).append("\n");
            sb.append("    udp: true\n");
            if (u.getQueryParameter("auth") != null) sb.append("    auth-str: ").append(quote(u.getQueryParameter("auth"))).append("\n");
            if (u.getQueryParameter("peer") != null) sb.append("    peers:\n      - ").append(quote(u.getQueryParameter("peer"))).append("\n");
            if (u.getQueryParameter("insecure") != null && "1".equals(u.getQueryParameter("insecure"))) sb.append("    skip-cert-verify: true\n");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String tuicToClash(String uri, String name) {
        try {
            Uri u = Uri.parse(uri);
            StringBuilder sb = new StringBuilder();
            sb.append("  - name: ").append(quote(name)).append("\n");
            sb.append("    type: tuic\n");
            sb.append("    server: ").append(u.getHost()).append("\n");
            sb.append("    port: ").append(u.getPort()).append("\n");
            sb.append("    udp: true\n");
            if (u.getUserInfo() != null) {
                String[] up = u.getUserInfo().split(":");
                if (up.length >= 1) sb.append("    uuid: ").append(quote(up[0])).append("\n");
                if (up.length >= 2) sb.append("    password: ").append(quote(up[1])).append("\n");
            }
            if (u.getQueryParameter("sni") != null) sb.append("    sni: ").append(quote(u.getQueryParameter("sni"))).append("\n");
            if (u.getQueryParameter("alpn") != null) sb.append("    alpn:\n      - ").append(quote(u.getQueryParameter("alpn"))).append("\n");
            if (u.getQueryParameter("insecure") != null && "1".equals(u.getQueryParameter("insecure"))) sb.append("    skip-cert-verify: true\n");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String snellToClash(String uri, String name) {
        try {
            Uri u = Uri.parse(uri);
            StringBuilder sb = new StringBuilder();
            sb.append("  - name: ").append(quote(name)).append("\n");
            sb.append("    type: snell\n");
            sb.append("    server: ").append(u.getHost()).append("\n");
            sb.append("    port: ").append(u.getPort()).append("\n");
            sb.append("    udp: true\n");
            if (u.getUserInfo() != null) sb.append("    psk: ").append(quote(u.getUserInfo())).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String anytlsToClash(String uri, String name) {
        try {
            Uri u = Uri.parse(uri);
            StringBuilder sb = new StringBuilder();
            sb.append("  - name: ").append(quote(name)).append("\n");
            sb.append("    type: anytls\n");
            sb.append("    server: ").append(u.getHost()).append("\n");
            sb.append("    port: ").append(u.getPort()).append("\n");
            sb.append("    udp: true\n");
            if (u.getUserInfo() != null) sb.append("    password: ").append(quote(u.getUserInfo())).append("\n");
            if (u.getQueryParameter("sni") != null) sb.append("    sni: ").append(quote(u.getQueryParameter("sni"))).append("\n");
            if (u.getQueryParameter("insecure") != null && "1".equals(u.getQueryParameter("insecure"))) sb.append("    skip-cert-verify: true\n");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String wireguardToClash(String uri, String name) {
        return null;
    }

    private String juicityToClash(String uri, String name) {
        try {
            Uri u = Uri.parse(uri);
            StringBuilder sb = new StringBuilder();
            sb.append("  - name: ").append(quote(name)).append("\n");
            sb.append("    type: juicity\n");
            sb.append("    server: ").append(u.getHost()).append("\n");
            sb.append("    port: ").append(u.getPort()).append("\n");
            sb.append("    udp: true\n");
            if (u.getUserInfo() != null) {
                String[] up = u.getUserInfo().split(":");
                if (up.length >= 1) sb.append("    uuid: ").append(quote(up[0])).append("\n");
                if (up.length >= 2) sb.append("    password: ").append(quote(up[1])).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String quote(String value) {
        if (value == null) return "\"\"";
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private String str(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private String intStr(JsonObject o, String key) {
        return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "0";
    }

    private String decode(String text) {
        if (TextUtils.isEmpty(text)) return "";
        String value = text.trim().replace("\n", "").replace("\r", "").replace(" ", "");
        String result = tryDecode(value, Base64.NO_WRAP);
        if (result.contains("://")) return result;
        result = tryDecode(value, Base64.URL_SAFE | Base64.NO_WRAP);
        if (result.contains("://")) return result;
        return "";
    }

    /**
     * 解码base64内容，不限制解码结果必须包含 "://"
     * 用于解析base64编码的JSON订阅（如Clash API JSON、SIP008等）
     */
    private String decodeRaw(String text) {
        if (TextUtils.isEmpty(text)) return "";
        String value = text.trim().replace("\n", "").replace("\r", "").replace(" ", "");
        // 如果原文已经是可读内容（包含中文或JSON结构），不需要解码
        if (value.startsWith("{") || value.startsWith("[") || value.contains("proxies:") || value.contains("://")) return "";
        String result = tryDecode(value, Base64.NO_WRAP);
        if (isValidDecoded(result)) return result;
        result = tryDecode(value, Base64.URL_SAFE | Base64.NO_WRAP);
        if (isValidDecoded(result)) return result;
        return "";
    }

    private boolean isValidDecoded(String text) {
        if (TextUtils.isEmpty(text) || text.length() < 10) return false;
        // 检查解码结果是否为有效内容（JSON、YAML或代理链接）
        return text.contains("{") || text.contains("proxies:") || text.contains("://") || text.contains("servers:");
    }

    private String tryDecode(String value, int flags) {
        try {
            byte[] bytes = Base64.decode(value, flags);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}

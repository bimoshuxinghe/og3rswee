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

    private static final String NAME = "subscription";
    private static final int MAX_TEST = 60;
    private static final String TEST_HOST = "www.gstatic.com";
    private static final String TEST_URL = "https://" + TEST_HOST + "/generate_204";
    private static final int TEST_TIMEOUT = 5000;
    private static final String[] FILTER_NAMES = {"更新订阅", "特殊时期", "如果是", "客户端太旧", "请到网站", "更新一下客户端"};

    private List<ProxyNode> nodes;
    private volatile boolean mihomoStarting = false;

    private static class Loader {
        static volatile ProxySubscriptionManager INSTANCE = new ProxySubscriptionManager();
    }

    public static ProxySubscriptionManager get() {
        return Loader.INSTANCE;
    }

    public String getSummary() {
        if (!Setting.isProxySubscriptionEnabled()) return "关闭";
        ProxyNode node = getSelected();
        if (node == null) return "未选择";
        return node.getDisplay();
    }

    public synchronized List<ProxyNode> getNodes() {
        if (nodes != null) return nodes;
        Type type = new TypeToken<List<ProxyNode>>() {}.getType();
        List<ProxyNode> items = App.gson().fromJson(Setting.getProxySubscriptionNodes(), type);
        return nodes = items == null ? new ArrayList<>() : items;
    }

    public synchronized ProxyNode getSelected() {
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

    private String getConfig() {
        String saved = Setting.getProxySubscriptionConfig();
        if (!TextUtils.isEmpty(saved) && saved.contains("proxies:")) {
            android.util.Log.d("ProxySub", "getConfig: using saved config (" + saved.length() + " chars)");
            return saved;
        }
        android.util.Log.d("ProxySub", "getConfig: saved config empty or invalid, regenerating from nodes");
        String config = generateClashConfig(getNodes());
        if (!TextUtils.isEmpty(config)) Setting.putProxySubscriptionConfig(config);
        return config;
    }

    public void applySaved() {
        applySaved(true);
    }

    public void applySaved(boolean async) {
        String url = getProxyUrl();
        if (TextUtils.isEmpty(url)) return;
        if (isMihomo(url)) {
            if (MihomoManager.get().isRunning()) {
                mihomoStarting = false;
                OkHttp.selector().addOrReplace(Proxy.create(NAME, Arrays.asList("*"), Arrays.asList(url)));
            } else if (async) {
                if (!mihomoStarting) {
                    mihomoStarting = true;
                    startMihomoAsync(url);
                }
            } else {
                startMihomoSync(url);
            }
        } else {
            OkHttp.selector().addOrReplace(Proxy.create(NAME, Arrays.asList("*"), Arrays.asList(url)));
        }
    }

    private void startMihomoSync(String url) {
        mihomoStarting = false;
        String config = getConfig();
        if (TextUtils.isEmpty(config)) {
            android.util.Log.e("ProxySub", "startMihomoSync: config is empty");
            return;
        }
        boolean ok = MihomoManager.get().start(config, Setting.getProxySubscriptionCoreName());
        if (ok) {
            OkHttp.selector().addOrReplace(Proxy.create(NAME, Arrays.asList("*"), Arrays.asList(url)));
            android.util.Log.d("ProxySub", "startMihomoSync: success, proxy applied");
        } else {
            android.util.Log.e("ProxySub", "startMihomoSync: mihomo start failed: " + MihomoManager.get().getLastError());
        }
    }

    private void startMihomoAsync(String url) {
        new Thread(() -> {
            try {
                String config = getConfig();
                if (TextUtils.isEmpty(config)) {
                    android.util.Log.e("ProxySub", "startMihomoAsync: config is empty");
                    return;
                }
                boolean ok = MihomoManager.get().start(config, Setting.getProxySubscriptionCoreName());
                if (ok) {
                    OkHttp.selector().addOrReplace(Proxy.create(NAME, Arrays.asList("*"), Arrays.asList(url)));
                    android.util.Log.d("ProxySub", "startMihomoAsync: success, proxy applied");
                } else {
                    android.util.Log.e("ProxySub", "startMihomoAsync: mihomo start failed: " + MihomoManager.get().getLastError());
                }
            } finally {
                mihomoStarting = false;
            }
        }, "mihomo-async-start").start();
    }

    private long quickTestProxy() {
        for (int attempt = 0; attempt < 3; attempt++) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", MihomoManager.getMixedPort()), 1000);
                socket.setSoTimeout(3000);
                java.io.OutputStream out = socket.getOutputStream();
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
                String connectRequest = "CONNECT " + TEST_HOST + ":443 HTTP/1.1\r\nHost: " + TEST_HOST + ":443\r\n\r\n";
                out.write(connectRequest.getBytes(StandardCharsets.UTF_8));
                out.flush();
                String statusLine = reader.readLine();
                android.util.Log.d("ProxySub", "quickTestProxy attempt=" + attempt + " status=" + statusLine);
                if (statusLine != null && statusLine.contains("200")) return 1;
            } catch (Exception e) {
                android.util.Log.e("ProxySub", "quickTestProxy attempt=" + attempt + " failed: " + e.getMessage());
            }
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { return -1; }
        }
        return -1;
    }

    public boolean testConnection() {
        if (!MihomoManager.get().isRunning()) return false;
        return quickTestProxy() > 0;
    }

    public boolean select(ProxyNode node) {
        if (node == null) return false;
        if (node.isSupported()) {
            Setting.putProxySubscriptionSelected(node.getUrl());
            Setting.putProxySubscriptionEnabled(true);
            Setting.putProxySubscriptionCoreName("");
            applySaved();
            return true;
        }
        String config = getConfig();
        android.util.Log.d("ProxySub", "select: starting mihomo node=" + node.getName() + " configLen=" + config.length());
        if (!MihomoManager.get().start(config, node.getName())) {
            android.util.Log.e("ProxySub", "select: mihomo start failed, regenerating config. Error: " + MihomoManager.get().getLastError());
            config = generateClashConfig(Arrays.asList(node));
            if (TextUtils.isEmpty(config)) {
                android.util.Log.e("ProxySub", "select: regenerated config is empty");
                return false;
            }
            android.util.Log.d("ProxySub", "select: retrying with freshly generated config:\n" + (config.length() > 1000 ? config.substring(0, 1000) + "..." : config));
            Setting.putProxySubscriptionConfig(config);
            if (!MihomoManager.get().start(config, node.getName())) {
                android.util.Log.e("ProxySub", "select: mihomo start failed even with regenerated config: " + MihomoManager.get().getLastError());
                return false;
            }
        }
        if (quickTestProxy() <= 0) {
            android.util.Log.e("ProxySub", "select: connection test failed after mihomo start, proxy NOT applied. Node: " + node.getName());
            return false;
        }
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
        String[] userAgents = {"Clash Verge/2.0.0", "ClashforWindows/0.20.39", "v2rayN/6.0", "Shadowrocket/1900", "Mozilla/5.0"};
        for (String ua : userAgents) {
            String text = OkHttp.string(url, Map.of("User-Agent", ua, "Accept", "text/plain, application/json, */*"));
            if (!TextUtils.isEmpty(text) && text.length() > 10) return text;
        }
        return OkHttp.string(url);
    }

    public ProxyNode autoSelect() {
        ProxyNode best = null;
        for (ProxyNode node : getNodes()) if (node.getLatency() > 0 && (best == null || node.getLatency() < best.getLatency())) best = node;
        if (best != null) {
            return select(best) ? best : null;
        }
        ProxyNode first = firstCoreNode();
        return first != null && select(first) ? ProxyNode.mihomo(first.getName()) : null;
    }

    public synchronized List<ProxyNode> testAll() {
        int tested = 0;
        for (ProxyNode node : getNodes()) {
            if (tested++ >= MAX_TEST) break;
            node.setLatency(test(node));
        }
        saveNodes(getNodes());
        return getNodes();
    }

    public synchronized long testOne(ProxyNode node) {
        long latency = test(node);
        node.setLatency(latency);
        saveNodes(getNodes());
        return latency;
    }

    public long test(ProxyNode node) {
        if (node == null) return -1;
        if (!node.isSupported()) return testMihomo(node);
        long start = System.currentTimeMillis();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(node.getHost(), node.getPort()), 2500);
            return System.currentTimeMillis() - start;
        } catch (Exception e) {
            return -1;
        }
    }

    private long testMihomo(ProxyNode node) {
        String config = getConfig();
        if (TextUtils.isEmpty(config)) return -1;
        long start = System.currentTimeMillis();
        if (!MihomoManager.get().start(config, node.getName())) {
            android.util.Log.e("ProxySub", "testMihomo: mihomo start failed for " + node.getName());
            return -1;
        }
        long result = requestByLocalProxy(start);
        android.util.Log.d("ProxySub", "testMihomo: " + node.getName() + " result=" + result);
        return result;
    }

    private long requestByLocalProxy(long start) {
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}
        for (int attempt = 0; attempt < 3; attempt++) {
            long result = testProxyConnect();
            if (result > 0) {
                long latency = System.currentTimeMillis() - start;
                android.util.Log.d("ProxySub", "requestByLocalProxy: success on attempt " + attempt + " latency=" + latency);
                return latency;
            }
            android.util.Log.w("ProxySub", "requestByLocalProxy: attempt " + attempt + " failed, retrying...");
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }
        return -2;
    }

    private long testProxyConnect() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", MihomoManager.getMixedPort()), TEST_TIMEOUT);
            socket.setSoTimeout(TEST_TIMEOUT);
            java.io.OutputStream out = socket.getOutputStream();
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream()));
            String connectRequest = "CONNECT " + TEST_HOST + ":443 HTTP/1.1\r\nHost: " + TEST_HOST + ":443\r\n\r\n";
            out.write(connectRequest.getBytes(StandardCharsets.UTF_8));
            out.flush();
            String statusLine = reader.readLine();
            android.util.Log.d("ProxySub", "testProxyConnect: status=" + statusLine);
            if (statusLine != null && statusLine.contains("200")) {
                return 1;
            }
            return -1;
        } catch (Exception e) {
            android.util.Log.e("ProxySub", "testProxyConnect failed", e);
            return -1;
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

    private synchronized void saveNodes(List<ProxyNode> items) {
        nodes = items;
        Setting.putProxySubscriptionNodes(App.gson().toJson(items));
    }

    private List<ProxyNode> parse(String text) {
        Map<String, ProxyNode> result = new LinkedHashMap<>();
        addLines(result, text);
        String decoded = decode(text);
        addLines(result, decoded);
        addClash(result, text);
        addClash(result, decoded);
        addSip008(result, text);
        addSip008(result, decoded);
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
        ProxyNode node = ProxyNode.unsupported(name, scheme, "", -1, rawUri);
        String yaml = toClashProxy(node);
        if (!TextUtils.isEmpty(yaml)) {
            node.setProxyYaml(yaml);
            android.util.Log.d("ProxySub", "makeNode: " + name + " [" + scheme + "] proxyYaml已设置");
        } else {
            android.util.Log.w("ProxySub", "makeNode: " + name + " [" + scheme + "] YAML生成失败，URI: " + rawUri);
        }
        return node;
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
        if (TextUtils.isEmpty(text) || !text.trim().startsWith("{") && !text.trim().startsWith("[")) return;
        try {
            com.google.gson.JsonElement element = App.gson().fromJson(text, com.google.gson.JsonElement.class);
            if (element == null || !element.isJsonObject()) return;
            JsonObject root = element.getAsJsonObject();
            if (!root.has("servers")) return;
            for (com.google.gson.JsonElement item : root.getAsJsonArray("servers")) {
                if (!item.isJsonObject()) continue;
                JsonObject server = item.getAsJsonObject();
                String serverAddr = server.has("server") ? server.get("server").getAsString() : "";
                int port = server.has("server_port") ? server.get("server_port").getAsInt() : (server.has("port") ? server.get("port").getAsInt() : -1);
                if (TextUtils.isEmpty(serverAddr) || port <= 0) continue;
                String name = server.has("remarks") ? server.get("remarks").getAsString() : (server.has("name") ? server.get("name").getAsString() : serverAddr + ":" + port);
                String method = server.has("method") ? server.get("method").getAsString() : "";
                String password = server.has("password") ? server.get("password").getAsString() : "";
                String ssUri = "ss://" + android.util.Base64.encodeToString((method + ":" + password).getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP) + "@" + serverAddr + ":" + port + "#" + Uri.encode(name);
                ProxyNode node = makeNode(name, "ss", ssUri);
                result.putIfAbsent(ssUri, node);
            }
        } catch (Exception e) {
            // Not SIP008 format
        }
    }

    private boolean isClashConfig(String text) {
        return !TextUtils.isEmpty(text) && text.contains("proxies:");
    }

    private String getClashConfig(String text) {
        if (isClashConfig(text)) return text;
        String decoded = decode(text);
        return isClashConfig(decoded) ? decoded : "";
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
                "proxies:\n" +
                proxies +
                "proxy-groups:\n" +
                "  - name: XYS_PROXY\n" +
                "    type: select\n" +
                "    proxies:\n" +
                names +
                "rules:\n" +
                "  - IP-CIDR,127.0.0.0/8,DIRECT,no-resolve\n" +
                "  - IP-CIDR,192.168.0.0/16,DIRECT,no-resolve\n" +
                "  - IP-CIDR,10.0.0.0/8,DIRECT,no-resolve\n" +
                "  - IP-CIDR,172.16.0.0/12,DIRECT,no-resolve\n" +
                "  - DOMAIN-SUFFIX,cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,iqiyipic.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,iqiyi.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,qpic.cn,DIRECT\n" +
                "  - DOMAIN-SUFFIX,qq.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,youku.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,bilibili.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,hdslb.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,gtimg.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,taobao.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,alicdn.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,aliyuncs.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,aliyun.com,DIRECT\n" +
                "  - DOMAIN-SUFFIX,pages.dev,DIRECT\n" +
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

    private String tryDecode(String value, int flags) {
        try {
            byte[] bytes = Base64.decode(value, flags);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}

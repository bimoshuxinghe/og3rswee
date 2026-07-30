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
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class ProxySubscriptionManager {

    private static final String NAME = "subscription";
    private static final int MAX_TEST = 60;
    private static final String TEST_URL = "https://www.gstatic.com/generate_204";
    private static final int TEST_TIMEOUT = 3500;
    private static final String[] FILTER_NAMES = {"更新订阅", "特殊时期", "如果是", "客户端太旧", "请到网站", "更新一下客户端"};

    private List<ProxyNode> nodes;

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
        if (selected.startsWith("http://127.0.0.1:17890")) {
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

    public void applySaved() {
        String url = getProxyUrl();
        if (TextUtils.isEmpty(url)) return;
        if (isMihomo(url) && !MihomoManager.get().isRunning() && !MihomoManager.get().start(Setting.getProxySubscriptionConfig(), Setting.getProxySubscriptionCoreName())) return;
        OkHttp.selector().addOrReplace(Proxy.create(NAME, Arrays.asList("*"), Arrays.asList(url)));
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
        if (TextUtils.isEmpty(Setting.getProxySubscriptionConfig())) return false;
        if (!MihomoManager.get().start(Setting.getProxySubscriptionConfig(), node.getName())) return false;
        ProxyNode local = ProxyNode.mihomo(node.getName());
        Setting.putProxySubscriptionCoreName(node.getName());
        Setting.putProxySubscriptionSelected(local.getUrl());
        Setting.putProxySubscriptionEnabled(true);
        OkHttp.selector().addOrReplace(Proxy.create(NAME, Arrays.asList("*"), Arrays.asList(local.getUrl())));
        return true;
    }

    public void disable() {
        OkHttp.selector().remove(NAME);
        MihomoManager.get().stop();
    }

    public List<ProxyNode> refresh(String url) throws Exception {
        String text = fetch(url);
        String config = getClashConfig(text);
        List<ProxyNode> result = parse(TextUtils.isEmpty(config) ? text : config);
        Setting.putProxySubscriptionConfig(config);
        saveNodes(result);
        return result;
    }

    private String fetch(String url) {
        String text = OkHttp.string(url, Map.of("User-Agent", "Clash Verge/2.0.0", "Accept", "text/plain, */*"));
        return TextUtils.isEmpty(text) ? OkHttp.string(url) : text;
    }

    public ProxyNode autoSelect() {
        ProxyNode best = null;
        for (ProxyNode node : getNodes()) if (node.getLatency() > 0 && (best == null || node.getLatency() < best.getLatency())) best = node;
        if (best != null) {
            select(best);
            return best;
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
        if (TextUtils.isEmpty(Setting.getProxySubscriptionConfig())) return -1;
        long start = System.currentTimeMillis();
        if (!MihomoManager.get().start(Setting.getProxySubscriptionConfig(), node.getName())) return -1;
        return requestByLocalProxy(start);
    }

    private long requestByLocalProxy(long start) {
        java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP, new InetSocketAddress("127.0.0.1", MihomoManager.getMixedPort()));
        OkHttpClient client = OkHttp.client().newBuilder().proxy(proxy).connectTimeout(TEST_TIMEOUT, TimeUnit.MILLISECONDS).readTimeout(TEST_TIMEOUT, TimeUnit.MILLISECONDS).writeTimeout(TEST_TIMEOUT, TimeUnit.MILLISECONDS).build();
        Request request = new Request.Builder().url(TEST_URL).head().build();
        try (Response response = client.newCall(request).execute()) {
            return response.isSuccessful() || response.code() == 204 ? System.currentTimeMillis() - start : -2;
        } catch (Exception e) {
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

    private synchronized void saveNodes(List<ProxyNode> items) {
        nodes = items;
        Setting.putProxySubscriptionNodes(App.gson().toJson(items));
    }

    private List<ProxyNode> parse(String text) {
        Map<String, ProxyNode> result = new LinkedHashMap<>();
        addLines(result, text);
        addLines(result, decode(text));
        addClash(result, text);
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
        if (lower.startsWith("ss://")) return ProxyNode.unsupported(getFragmentName(line, "SS"), "ss");
        if (lower.startsWith("ssr://")) return ProxyNode.unsupported(getFragmentName(line, "SSR"), "ssr");
        if (lower.startsWith("vless://")) return ProxyNode.unsupported(getFragmentName(line, "VLESS"), "vless");
        if (lower.startsWith("trojan://")) return ProxyNode.unsupported(getFragmentName(line, "Trojan"), "trojan");
        if (lower.startsWith("hysteria2://") || lower.startsWith("hy2://")) return ProxyNode.unsupported(getFragmentName(line, "Hysteria2"), "hysteria2");
        if (lower.startsWith("anytls://")) return ProxyNode.unsupported(getFragmentName(line, "AnyTLS"), "anytls");
        return null;
    }

    private ProxyNode parseVmess(String line) {
        try {
            String json = decode(line.substring("vmess://".length()));
            JsonObject object = App.gson().fromJson(json, JsonObject.class);
            String name = object != null && object.has("ps") ? object.get("ps").getAsString() : "VMess";
            return ProxyNode.unsupported(name, "vmess");
        } catch (Exception e) {
            return ProxyNode.unsupported("VMess", "vmess");
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
        for (String raw : text.replace("\r", "\n").split("\n")) {
            String line = raw.trim();
            if (isTopLevel(raw)) {
                proxies = line.startsWith("proxies:");
                if (!proxies) {
                    ProxyNode node = clashNode(name, type, server, port);
                    if (node != null) result.putIfAbsent(node.isSupported() ? node.getUrl() : name + type + server + port, node);
                    name = "";
                    type = "";
                    server = "";
                    port = -1;
                }
                continue;
            }
            if (!proxies) continue;
            if (line.startsWith("-")) {
                ProxyNode node = clashNode(name, type, server, port);
                if (node != null) result.putIfAbsent(node.isSupported() ? node.getUrl() : name + type + server + port, node);
                name = value(line, "name");
                type = value(line, "type");
                server = value(line, "server");
                port = parsePort(value(line, "port"));
            } else {
                if (TextUtils.isEmpty(name)) name = value(line, "name");
                if (TextUtils.isEmpty(type)) type = value(line, "type");
                if (TextUtils.isEmpty(server)) server = value(line, "server");
                if (port <= 0) port = parsePort(value(line, "port"));
            }
        }
        ProxyNode node = clashNode(name, type, server, port);
        if (node != null) result.putIfAbsent(node.isSupported() ? node.getUrl() : name + type + server + port, node);
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

    private boolean isClashConfig(String text) {
        return !TextUtils.isEmpty(text) && text.matches("(?s).*\\n?proxies\\s*:.*");
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
        if (TextUtils.isEmpty(Setting.getProxySubscriptionConfig())) return null;
        for (ProxyNode node : getNodes()) if (node.needsCore()) return node;
        return null;
    }

    private boolean isMihomo(String url) {
        return url != null && url.startsWith("http://127.0.0.1:" + 18890);
    }

    private String value(String line, String key) {
        int index = line.indexOf(key + ":");
        if (index == -1) return "";
        String value = line.substring(index + key.length() + 1).trim();
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

    private String decode(String text) {
        try {
            if (TextUtils.isEmpty(text)) return "";
            String value = text.trim().replace("\n", "").replace("\r", "");
            byte[] bytes = Base64.decode(value, Base64.DEFAULT | Base64.URL_SAFE);
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            return decoded.contains("://") ? decoded : "";
        } catch (Exception e) {
            return "";
        }
    }
}

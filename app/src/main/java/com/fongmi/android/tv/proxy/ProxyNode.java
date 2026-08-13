package com.fongmi.android.tv.proxy;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;

public class ProxyNode {

    private String name;
    private String scheme;
    private String host;
    private int port;
    private String userInfo;
    private boolean supported;
    private long latency;
    private String rawUri;
    private String proxyYaml;

    public static ProxyNode unsupported(String name, String scheme) {
        return unsupported(name, scheme, "", -1, null);
    }

    public static ProxyNode unsupported(String name, String scheme, String host, int port) {
        return unsupported(name, scheme, host, port, null);
    }

    public static ProxyNode unsupported(String name, String scheme, String host, int port, String rawUri) {
        ProxyNode node = new ProxyNode();
        node.name = name;
        node.scheme = scheme;
        node.host = host;
        node.port = port;
        node.supported = false;
        node.latency = -1;
        node.rawUri = rawUri;
        return node;
    }

    public static ProxyNode fromUri(String url) {
        Uri uri = Uri.parse(url);
        String scheme = normalizeScheme(uri.getScheme());
        if (TextUtils.isEmpty(scheme) || TextUtils.isEmpty(uri.getHost()) || uri.getPort() <= 0) return null;
        ProxyNode node = new ProxyNode();
        node.scheme = scheme;
        node.host = uri.getHost();
        node.port = uri.getPort();
        node.userInfo = uri.getUserInfo();
        node.name = TextUtils.isEmpty(uri.getFragment()) ? uri.getHost() + ":" + uri.getPort() : Uri.decode(uri.getFragment());
        node.supported = isSupported(scheme);
        node.latency = -1;
        return node;
    }

    public static ProxyNode mihomo(String name) {
        return fromUri(MihomoManager.getProxyUrl(name));
    }

    private static String normalizeScheme(String scheme) {
        if (scheme == null) return "";
        scheme = scheme.toLowerCase();
        if ("socks5h".equals(scheme)) return "socks5";
        if ("socks".equals(scheme)) return "socks5";
        return scheme;
    }

    private static boolean isSupported(String scheme) {
        return "http".equals(scheme) || "https".equals(scheme) || "socks5".equals(scheme);
    }

    public String getName() {
        return TextUtils.isEmpty(name) ? getAddress() : name;
    }

    public String getScheme() {
        return TextUtils.isEmpty(scheme) ? "" : scheme;
    }

    public String getHost() {
        return TextUtils.isEmpty(host) ? "" : host;
    }

    public int getPort() {
        return port;
    }

    public String getUserInfo() {
        return TextUtils.isEmpty(userInfo) ? "" : userInfo;
    }

    public boolean isSupported() {
        return supported;
    }

    public boolean needsCore() {
        return !isSupported();
    }

    public String getRawUri() {
        return TextUtils.isEmpty(rawUri) ? "" : rawUri;
    }

    public String getProxyYaml() {
        return TextUtils.isEmpty(proxyYaml) ? "" : proxyYaml;
    }

    public void setProxyYaml(String proxyYaml) {
        this.proxyYaml = proxyYaml;
    }

    public long getLatency() {
        return latency;
    }

    public void setLatency(long latency) {
        this.latency = latency;
    }

    public String getAddress() {
        return getHost() + ":" + getPort();
    }

    public String getUrl() {
        if (!isSupported()) return "";
        Uri.Builder builder = new Uri.Builder().scheme(getScheme()).encodedAuthority(buildAuthority()).fragment(getName());
        return builder.build().toString();
    }

    private String buildAuthority() {
        String auth = getAddress();
        if (!TextUtils.isEmpty(getUserInfo())) auth = getUserInfo() + "@" + auth;
        return auth;
    }

    public String getDisplay() {
        String type = getScheme().isEmpty() ? "" : " [" + getScheme() + "]";
        String speed = latency > 0 ? " · " + latency + "ms" : latency == -2 ? " · timeout" : "";
        return getName() + type + speed;
    }

    /**
     * 获取延迟对应的颜色：
     * latency > 0 且 < 2000ms → 绿色
     * latency >= 2000ms → 红色
     * latency <= 0（未测试/超时）→ 红色
     */
    public int getLatencyColor() {
        if (latency > 0 && latency < 2000) return android.graphics.Color.parseColor("#4CAF50");
        return android.graphics.Color.parseColor("#F44336");
    }

    @NonNull
    @Override
    public String toString() {
        return getDisplay();
    }
}

package com.fongmi.android.tv.bean;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * HLS 结构化去广告规则（移植自 WebHTV HlsManifestCleaner 的 Rule 模型）。
 *
 * <p>与旧版「URL 黑名单」的本质区别：规则匹配的是 <b>m3u8 正文里的分片</b>，
 * 且必须多信号同时满足才删除，单信号不动作——这是不误删正片的第一道闸。
 *
 * <p>信号类型（命中越多种子越可信）：
 * <ol>
 *   <li>{@code hostSuffixes}：分片域名后缀（如 {@code bfzycdn.com}）；</li>
 *   <li>{@code segmentUrlPatterns}：分片 URL 正则；</li>
 *   <li>{@code durationRange}：分片时长区间（贴片广告通常几秒且固定）；</li>
 *   <li>{@code requireDiscontinuity}：分片前必须有 {@code #EXT-X-DISCONTINUITY}；</li>
 *   <li>{@code requireCrossDomain}：分片域名与播放列表域名不同（广告多走独立 CDN）。</li>
 * </ol>
 *
 * <p>作用域（scope）限定规则只对特定播放列表域名生效，避免误伤其他站点：
 * {@code playlistHostSuffixes} / {@code playlistHostPatterns} 任一非空即启用作用域。
 */
public final class HlsAdRule {

    private final String id;
    private final String name;
    private final List<String> hostSuffixes;
    private final List<String> playlistHostSuffixes;
    private final List<Pattern> playlistHostPatterns;
    private final List<Pattern> segmentUrlPatterns;
    private final int minimumSignals;
    private final double minDuration;
    private final double maxDuration;
    private final boolean requireDiscontinuity;
    private final boolean requireCrossDomain;
    private final boolean enabled;

    private HlsAdRule(Builder b) {
        this.id = b.id;
        this.name = b.name;
        this.hostSuffixes = lower(b.hostSuffixes);
        this.playlistHostSuffixes = lower(b.playlistHostSuffixes);
        this.playlistHostPatterns = compile(b.playlistHostPatterns);
        this.segmentUrlPatterns = compile(b.segmentUrlPatterns);
        this.minimumSignals = Math.max(1, b.minimumSignals);
        this.minDuration = b.minDuration;
        this.maxDuration = b.maxDuration;
        this.requireDiscontinuity = b.requireDiscontinuity;
        this.requireCrossDomain = b.requireCrossDomain;
        this.enabled = b.enabled;
    }

    public String id() { return id; }
    public String name() { return name; }
    public boolean enabled() { return enabled; }

    /** 是否对该播放列表域名生效；无作用域限定的规则全局生效。 */
    public boolean scopedTo(String playlistHost) {
        if (playlistHostSuffixes.isEmpty() && playlistHostPatterns.isEmpty()) return true;
        return matchesHost(playlistHost, playlistHostSuffixes) || matchesPattern(playlistHost, playlistHostPatterns);
    }

    /**
     * 多信号投票：命中信号数达到门槛才判定为广告分片。
     *
     * @param segmentUrl   分片完整 URL
     * @param segmentHost  分片域名（小写）
     * @param playlistHost 播放列表域名（小写）
     * @param durationSec  分片时长（秒）
     * @param discontinuityBefore 分片前是否有 #EXT-X-DISCONTINUITY
     */
    public boolean matches(String segmentUrl, String segmentHost, String playlistHost,
                           double durationSec, boolean discontinuityBefore) {
        int signals = 0;
        if (matchesHost(segmentHost, hostSuffixes)) signals++;
        if (matchesPattern(segmentUrl, segmentUrlPatterns)) signals++;
        if (hasDurationRange() && durationSec >= minDuration && durationSec <= maxDuration) signals++;
        if (requireDiscontinuity && discontinuityBefore) signals++;
        if (requireCrossDomain && !segmentHost.isEmpty() && !playlistHost.isEmpty()
                && !segmentHost.equals(playlistHost)) signals++;
        return signals >= minimumSignals;
    }

    private boolean hasDurationRange() {
        return !Double.isNaN(minDuration) && !Double.isNaN(maxDuration);
    }

    private static boolean matchesHost(String host, List<String> suffixes) {
        if (host == null || host.isEmpty()) return false;
        String value = host.toLowerCase(java.util.Locale.US);
        for (String suffix : suffixes) {
            if (value.equals(suffix) || value.endsWith("." + suffix)) return true;
        }
        return false;
    }

    private static boolean matchesPattern(String value, List<Pattern> patterns) {
        if (value == null || value.isEmpty()) return false;
        for (Pattern pattern : patterns) if (pattern.matcher(value).find()) return true;
        return false;
    }

    private static List<String> lower(List<String> values) {
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) result.add(value.toLowerCase(java.util.Locale.US));
        return result;
    }

    private static List<Pattern> compile(List<String> values) {
        List<Pattern> result = new ArrayList<>(values.size());
        for (String value : values) result.add(Pattern.compile(value));
        return result;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id = "";
        private String name = "";
        private List<String> hostSuffixes = new ArrayList<>();
        private List<String> playlistHostSuffixes = new ArrayList<>();
        private List<String> playlistHostPatterns = new ArrayList<>();
        private List<String> segmentUrlPatterns = new ArrayList<>();
        private int minimumSignals = 1;
        private double minDuration = Double.NaN;
        private double maxDuration = Double.NaN;
        private boolean requireDiscontinuity;
        private boolean requireCrossDomain;
        private boolean enabled = true;

        public Builder id(String v) { id = v == null ? "" : v; return this; }
        public Builder name(String v) { name = v == null ? "" : v; return this; }
        public Builder hostSuffixes(List<String> v) { hostSuffixes = v == null ? new ArrayList<>() : v; return this; }
        public Builder playlistHostSuffixes(List<String> v) { playlistHostSuffixes = v == null ? new ArrayList<>() : v; return this; }
        public Builder playlistHostPatterns(List<String> v) { playlistHostPatterns = v == null ? new ArrayList<>() : v; return this; }
        public Builder segmentUrlPatterns(List<String> v) { segmentUrlPatterns = v == null ? new ArrayList<>() : v; return this; }
        public Builder minimumSignals(int v) { minimumSignals = v; return this; }
        public Builder durationRange(double min, double max) { minDuration = min; maxDuration = max; return this; }
        public Builder requireDiscontinuity(boolean v) { requireDiscontinuity = v; return this; }
        public Builder requireCrossDomain(boolean v) { requireCrossDomain = v; return this; }
        public Builder enabled(boolean v) { enabled = v; return this; }

        public HlsAdRule build() { return new HlsAdRule(this); }
    }

    @NonNull
    @Override
    public String toString() {
        return "HlsAdRule{" + id + " " + name + " signals>=" + minimumSignals + "}";
    }
}

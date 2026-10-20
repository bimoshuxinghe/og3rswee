package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.HlsAdRule;

import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * HLS 去广告统一引擎：规则引擎（主）+ 特征引擎（兜底）+ 安全框架（裁决）。
 *
 * <p>架构移植自 WebHTV {@code HlsManifestCleaner}（纯 Java manifest 净化核心），
 * 特征兜底移植自 ltxlong {@code M3U8-Filter-Ad-Script} 的双模式算法：
 * <ol>
 *   <li><b>规则引擎</b>：结构化多信号规则（域名/正则/时长/discontinuity/跨域投票），
 *       覆盖已知广告源（暴风、量子、非凡等），精确打击；</li>
 *   <li><b>特征引擎</b>：无规则命中时，按切片命名规律（序号连续性、前缀长度、
 *       EXTINF 模板）识别广告块，覆盖规则库未收录的源；</li>
 *   <li><b>安全框架</b>：两个引擎的删除结论都必须通过同一组阈值与结构检查，
 *       任一不满足整体回退原文——宁可漏删广告，绝不误删正片。</li>
 * </ol>
 *
 * <p>fail-open 原则贯穿全程：改写播放列表的收益是「少看几秒广告」，代价是
 * 「删错就整个视频播不了」，收益与风险严重不对称，所有边界一律往「不删」取。
 */
public final class HlsAdEngine {

    /** 允许删除的分片数占比上限。 */
    private static final double MAX_REMOVAL_RATIO = 0.35;

    /** 允许删除的媒体时长上限（秒）。 */
    private static final double MAX_REMOVED_DURATION_SEC = 90;

    /** manifest 体积上限（字符）；超限直接回退。 */
    private static final int MAX_MANIFEST_CHARS = 2 * 1024 * 1024;

    /** manifest 行数上限；超限直接回退。 */
    private static final int MAX_MANIFEST_LINES = 20_000;

    private HlsAdEngine() {
    }

    /** 净化结果；{@code changed=false} 时 {@code manifest} 恒为原文。 */
    public record Result(String manifest, boolean changed, boolean fallback,
                         int removedSegments, double removedDurationSec, String engine) {

        public static Result unchanged(String manifest) {
            return new Result(manifest, false, false, 0, 0, "none");
        }

        public static Result fallback(String manifest, String engine) {
            return new Result(manifest, false, true, 0, 0, engine);
        }

        public static Result stripped(String manifest, int removed, double durationSec, String engine) {
            return new Result(manifest, true, false, removed, durationSec, engine);
        }
    }

    /** 使用的引擎标识（用于诊断与统计）。 */
    public static final String ENGINE_RULE = "rule";
    public static final String ENGINE_FEATURE = "feature";
    public static final String ENGINE_PROBE = "probe";

    /**
     * 净化入口。
     *
     * @param baseUrl  播放列表地址（用于解析相对分片 URL 与作用域判定）
     * @param manifest 原始 m3u8 文本
     * @param rules    结构化规则（可空）
     * @param featureEnabled 是否启用特征兜底引擎
     */
    public static Result clean(String baseUrl, String manifest, List<HlsAdRule> rules,
                               boolean featureEnabled, boolean probeEnabled) {
        if (manifest == null || manifest.isEmpty()) return Result.unchanged(manifest);
        if (!manifest.trim().startsWith("#EXTM3U")) return Result.unchanged(manifest);
        if (manifest.length() > MAX_MANIFEST_CHARS || lineCount(manifest) > MAX_MANIFEST_LINES) {
            return Result.fallback(manifest, "size");
        }
        // 结构不安全的特性直接回退：byterange 分片删除会错位；
        // LL-HLS（SKIP/PART/PRELOAD-HINT）增量语义下删除会与服务器状态冲突。
        if (manifest.contains("#EXT-X-BYTERANGE")) return Result.fallback(manifest, "byterange");
        if (manifest.contains("#EXT-X-SKIP:") || manifest.contains("#EXT-X-PART:")
                || manifest.contains("#EXT-X-PRELOAD-HINT:")) return Result.fallback(manifest, "llhls");
        try {
            List<Node> nodes = parse(manifest);
            List<Segment> segments = collectSegments(nodes);
            if (segments.isEmpty()) return Result.unchanged(manifest);

            String baseHost = hostOf(baseUrl);
            boolean live = !manifest.contains("#EXT-X-ENDLIST");

            // 引擎一：结构化规则
            Set<Integer> adIndexes = matchRules(segments, baseUrl, baseHost, rules);
            if (!adIndexes.isEmpty()) {
                return apply(nodes, segments, adIndexes, live, manifest, ENGINE_RULE);
            }

            // 引擎二：特征兜底（规则未覆盖的源）
            if (featureEnabled) {
                HlsSegmentAdJudger.Verdict verdict = HlsSegmentAdJudger.judge(toJudgeSegments(segments));
                if (verdict.mode == HlsSegmentAdJudger.Mode.VIOLENCE && verdict.stripDiscontinuityOnly) {
                    // 暴力拆解：只删标识不改时间轴，不触发删除类安全阈值，
                    // 但仍需保证列表可解析（parse 已通过）。
                    return applyViolence(nodes, segments, manifest, ENGINE_FEATURE);
                }
                if (!verdict.adIndexes.isEmpty()) {
                    return apply(nodes, segments, verdict.adIndexes, live, manifest, ENGINE_FEATURE);
                }
            }

            // 引擎三：分辨率探测。有一类源把广告整段转码后混入正片，
            // manifest 层面零特征，唯一差异是编码分辨率；对直播禁用
            // （直播切档换分辨率是正常行为）。
            if (probeEnabled && !live) {
                Set<Integer> probed = HlsResolutionProbe.detect(cacheKey(baseUrl, segments.size()),
                        buildGroups(baseUrl, segments));
                if (!probed.isEmpty()) {
                    return apply(nodes, segments, probed, live, manifest, ENGINE_PROBE);
                }
            }
            return Result.unchanged(manifest);
        } catch (RuntimeException e) {
            return Result.fallback(manifest, "error");
        }
    }

    // ---------------- 引擎三：分辨率探测分组 ----------------

    /** 按 DISCONTINUITY 把分片切段，供探测器逐段取首分片。 */
    private static List<HlsResolutionProbe.Group> buildGroups(String baseUrl, List<Segment> segments) {
        List<HlsResolutionProbe.Group> groups = new ArrayList<>();
        List<Integer> current = new ArrayList<>();
        double duration = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            if (seg.discontinuityBefore && !current.isEmpty()) {
                groups.add(toGroup(baseUrl, segments, current, duration));
                current = new ArrayList<>();
                duration = 0;
            }
            current.add(i);
            duration += seg.durationSec;
        }
        if (!current.isEmpty()) groups.add(toGroup(baseUrl, segments, current, duration));
        return groups;
    }

    private static HlsResolutionProbe.Group toGroup(String baseUrl, List<Segment> segments,
                                                    List<Integer> indexes, double duration) {
        int[] array = new int[indexes.size()];
        for (int i = 0; i < array.length; i++) array[i] = indexes.get(i);
        String probeUrl = resolve(baseUrl, segments.get(array[0]).uri);
        return new HlsResolutionProbe.Group(array, probeUrl, duration);
    }

    private static String cacheKey(String baseUrl, int count) {
        return baseUrl + "#" + count;
    }

    // ---------------- 引擎一：规则匹配 ----------------

    private static Set<Integer> matchRules(List<Segment> segments, String baseUrl, String baseHost,
                                           List<HlsAdRule> rules) {
        Set<Integer> adIndexes = new HashSet<>();
        if (rules == null || rules.isEmpty()) return adIndexes;
        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            String url = resolve(baseUrl, seg.uri);
            String host = hostOf(url);
            for (HlsAdRule rule : rules) {
                if (!rule.enabled()) continue;
                if (!rule.scopedTo(baseHost)) continue;
                if (rule.matches(url, host, baseHost, seg.durationSec, seg.discontinuityBefore)) {
                    adIndexes.add(i);
                    break;
                }
            }
        }
        return adIndexes;
    }

    // ---------------- 安全框架与重建 ----------------

    private static Result apply(List<Node> nodes, List<Segment> segments, Set<Integer> adIndexes,
                                boolean live, String manifest, String engine) {
        int segmentCount = segments.size();
        int removedCount = adIndexes.size();
        double removedDurationSec = 0;
        for (int index : adIndexes) removedDurationSec += segments.get(index).durationSec;

        if (removedCount == 0) return Result.unchanged(manifest);
        // 全删或比例超限 → 区间不可信，回退
        if (removedCount == segmentCount) return Result.fallback(manifest, engine);
        if ((double) removedCount / segmentCount > MAX_REMOVAL_RATIO) return Result.fallback(manifest, engine);
        if (removedDurationSec > MAX_REMOVED_DURATION_SEC) return Result.fallback(manifest, engine);

        // 直播流（无 ENDLIST）：只允许删除头部连续分片；
        // 遇到「保留之后又删除」说明区间判断不可信，回退。
        int mediaSequenceIncrement = 0;
        int discontinuitySequenceIncrement = 0;
        if (live) {
            boolean retainedSeen = false;
            for (int i = 0; i < segments.size(); i++) {
                if (adIndexes.contains(i)) {
                    if (retainedSeen) return Result.fallback(manifest, engine);
                    mediaSequenceIncrement++;
                    if (segments.get(i).discontinuityBefore) discontinuitySequenceIncrement++;
                } else {
                    retainedSeen = true;
                }
            }
            // 直播删除后必须同步序列号；原本没有对应标签说明源站不用序列语义，回退
            if (mediaSequenceIncrement > 0 && !manifest.contains("#EXT-X-MEDIA-SEQUENCE:")) {
                return Result.fallback(manifest, engine);
            }
            if (discontinuitySequenceIncrement > 0 && !manifest.contains("#EXT-X-DISCONTINUITY-SEQUENCE:")) {
                return Result.fallback(manifest, engine);
            }
        }
        String rendered = render(nodes, adIndexes, manifest.endsWith("\n"),
                mediaSequenceIncrement, discontinuitySequenceIncrement, buildSegmentMap(nodes));
        return Result.stripped(rendered, removedCount, removedDurationSec, engine);
    }

    /** 暴力拆解模式：仅删除 DISCONTINUITY 标识行，不动分片与时间轴。 */
    private static Result applyViolence(List<Node> nodes, List<Segment> segments, String manifest, String engine) {
        Set<Integer> dropLines = new HashSet<>();
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) instanceof Line line && isSegmentPrefix(line.value())) {
                dropLines.add(i);
            }
        }
        if (dropLines.isEmpty()) return Result.unchanged(manifest);
        StringBuilder out = new StringBuilder(manifest.length() + 64);
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node instanceof Line line && dropLines.contains(i)) {
                for (String text : node.lines()) if (!isSegmentPrefix(text.trim())) out.append(text).append('\n');
                continue;
            }
            for (String text : node.lines()) out.append(text).append('\n');
        }
        if (!manifest.endsWith("\n") && out.length() > 0) out.setLength(out.length() - 1);
        // 暴力模式不改动分片，removedSegments 记 0，duration 记 0
        return Result.stripped(out.toString(), 0, 0, engine);
    }

    private static String render(List<Node> nodes, Set<Integer> adIndexes, boolean trailingNewline,
                                 int mediaIncrement, int discontinuityIncrement, int[] segmentMap) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            if (node instanceof Segment && adIndexes.contains(segmentMap[i])) continue;
            if (node instanceof Line line && isSegmentPrefix(line.value())
                    && prefixesRemovedSegment(nodes, i, adIndexes, segmentMap)) continue;
            for (String text : node.lines()) {
                output.append(rewriteSequence(text, mediaIncrement, discontinuityIncrement)).append('\n');
            }
        }
        if (!trailingNewline && output.length() > 0) output.setLength(output.length() - 1);
        return output.toString();
    }

    /** 预计算 nodes[i] 对应的分片序号（非 Segment 节点为 -1），避免 render 反复重算。 */
    private static int[] buildSegmentMap(List<Node> nodes) {
        int[] map = new int[nodes.size()];
        int count = 0;
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i) instanceof Segment) {
                map[i] = count++;
            } else {
                map[i] = -1;
            }
        }
        return map;
    }

    /** DISCONTINUITY/PROGRAM-DATE-TIME 前缀行：若后随被删分片则一并删除。 */
    private static boolean prefixesRemovedSegment(List<Node> nodes, int index, Set<Integer> adIndexes, int[] segmentMap) {
        for (int i = index + 1; i < nodes.size(); i++) {
            Node next = nodes.get(i);
            if (next instanceof Segment) return adIndexes.contains(segmentMap[i]);
            if (!(next instanceof Line line)) return false;
            String value = line.value().trim();
            if (!isSegmentPrefix(value) && !isTransparentStateTag(value)) return false;
        }
        return false;
    }

    private static String rewriteSequence(String line, int mediaIncrement, int discontinuityIncrement) {
        String trimmed = line.trim();
        if (mediaIncrement > 0 && trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
            return incrementTag(trimmed, "#EXT-X-MEDIA-SEQUENCE:", mediaIncrement);
        }
        if (discontinuityIncrement > 0 && trimmed.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE:")) {
            return incrementTag(trimmed, "#EXT-X-DISCONTINUITY-SEQUENCE:", discontinuityIncrement);
        }
        return line;
    }

    private static String incrementTag(String trimmed, String tag, int increment) {
        BigInteger value = new BigInteger(trimmed.substring(tag.length()).trim());
        if (value.signum() < 0 || value.bitLength() > 64) throw new IllegalArgumentException("Invalid HLS sequence");
        BigInteger updated = value.add(BigInteger.valueOf(increment));
        if (updated.bitLength() > 64) throw new IllegalArgumentException("HLS sequence overflow");
        return tag + updated;
    }

    private static boolean isSegmentPrefix(String value) {
        return value.equals("#EXT-X-DISCONTINUITY") || value.startsWith("#EXT-X-PROGRAM-DATE-TIME:");
    }

    private static boolean isTransparentStateTag(String value) {
        return value.startsWith("#EXT-X-KEY:") || value.startsWith("#EXT-X-MAP:");
    }

    // ---------------- 解析 ----------------

    private interface Node {
        List<String> lines();
    }

    private record Line(String value) implements Node {
        @Override public List<String> lines() { return List.of(value); }
    }

    /** 分片：前置标签行 + URI + 时长 + 是否紧跟 DISCONTINUITY。 */
    private static final class Segment implements Node {
        private final List<String> leadingLines;
        private final String uri;
        private final double durationSec;
        private final boolean discontinuityBefore;

        Segment(List<String> leadingLines, String uri, double durationSec, boolean discontinuityBefore) {
            this.leadingLines = leadingLines;
            this.uri = uri;
            this.durationSec = durationSec;
            this.discontinuityBefore = discontinuityBefore;
        }

        @Override public List<String> lines() {
            List<String> lines = new ArrayList<>(leadingLines);
            lines.add(uri);
            return lines;
        }
    }

    private static List<Node> parse(String manifest) {
        String normalized = manifest.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        List<Node> nodes = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        boolean discontinuityBefore = false;
        for (String line : lines) {
            if (line.isEmpty() && pending.isEmpty()) continue;
            String trimmed = line.trim();
            if (trimmed.equals("#EXT-X-DISCONTINUITY")) discontinuityBefore = true;
            if (trimmed.startsWith("#EXTINF:")) {
                flushPending(nodes, pending);
                pending.add(line);
                continue;
            }
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                if (hasExtInf(pending)) {
                    nodes.add(new Segment(new ArrayList<>(pending), line, duration(pending), discontinuityBefore));
                    pending.clear();
                    discontinuityBefore = false;
                } else {
                    flushPending(nodes, pending);
                    nodes.add(new Line(line));
                }
            } else {
                pending.add(line);
            }
        }
        flushPending(nodes, pending);
        return nodes;
    }

    private static double duration(List<String> lines) {
        for (String line : lines) {
            String value = line.trim();
            if (!value.startsWith("#EXTINF:")) continue;
            int comma = value.indexOf(',');
            String number = value.substring(8, comma < 0 ? value.length() : comma);
            return Double.parseDouble(number);
        }
        return 0;
    }

    private static boolean hasExtInf(List<String> lines) {
        for (String line : lines) if (line.trim().startsWith("#EXTINF:")) return true;
        return false;
    }

    private static void flushPending(List<Node> nodes, List<String> pending) {
        for (String line : pending) nodes.add(new Line(line));
        pending.clear();
    }

    private static List<Segment> collectSegments(List<Node> nodes) {
        List<Segment> segments = new ArrayList<>();
        for (Node node : nodes) if (node instanceof Segment segment) segments.add(segment);
        return segments;
    }

    private static List<HlsSegmentAdJudger.Segment> toJudgeSegments(List<Segment> segments) {
        List<HlsSegmentAdJudger.Segment> result = new ArrayList<>(segments.size());
        for (Segment seg : segments) {
            result.add(new HlsSegmentAdJudger.Segment(seg.uri, seg.durationSec,
                    seg.discontinuityBefore, findExtinf(seg.leadingLines)));
        }
        return result;
    }

    private static String findExtinf(List<String> leadingLines) {
        for (String line : leadingLines) if (line.trim().startsWith("#EXTINF:")) return line.trim();
        return null;
    }

    private static int lineCount(String value) {
        int count = 1;
        for (int i = 0; i < value.length(); i++) if (value.charAt(i) == '\n') count++;
        return count;
    }

    private static String resolve(String baseUrl, String uri) {
        try {
            if (baseUrl == null || baseUrl.isEmpty()) return uri;
            return URI.create(baseUrl).resolve(uri).toString();
        } catch (RuntimeException e) {
            return uri;
        }
    }

    private static String hostOf(String url) {
        try {
            if (url == null || url.isEmpty()) return "";
            URI uri = URI.create(url);
            String host = uri.getHost();
            return host == null ? "" : host.toLowerCase(Locale.US);
        } catch (RuntimeException e) {
            return "";
        }
    }
}

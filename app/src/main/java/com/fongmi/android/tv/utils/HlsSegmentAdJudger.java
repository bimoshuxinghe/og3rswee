package com.fongmi.android.tv.utils;

import com.fongmi.android.tv.bean.HlsAdRule;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * HLS 切片广告特征判定引擎（移植自 ltxlong/M3U8-Filter-Ad-Script 的双模式算法）。
 *
 * <p>与 {@link HlsAdEngine} 的规则引擎互补：规则引擎按已知站点特征精确打击，
 * 本引擎不依赖任何规则库，纯靠切片命名规律识别广告块，作为<b>兜底</b>覆盖
 * 规则库未收录的源。两者结论都送交 {@link HlsAdEngine} 的统一安全框架裁决，
 * 任何不安全信号都整体回退原文，保证不误删正片。
 *
 * <p>移植自油猴脚本 {@code m3u8_filter_ad.js}（MIT，ltxlong），核心思想：
 * <ol>
 *   <li><b>ts 命名模式识别</b>：模式0=序号数字递增且前缀等长（{@code a0001.ts}）；
 *       模式1=无序号（{@code abcdef.ts}）；模式2=兜底暴力拆解；</li>
 *   <li><b>模式0 判定</b>：{@code #EXT-X-DISCONTINUITY} 之后的分片，若 ts 前缀
 *       长度突变（超过容差）或序号断档（非 prev+1），判定该块为广告；</li>
 *   <li><b>模式1 判定</b>：首个 EXTINF 文本重复出现超过基准次数后，DISCONTINUITY
 *       之后出现不同文本的 EXTINF，判定该块为广告；</li>
 *   <li><b>暴力拆解</b>：无法识别命名规律时，只删 DISCONTINUITY 标识不删数据。</li>
 * </ol>
 */
public final class HlsSegmentAdJudger {

    /** ts 前缀长度容差（与原脚本一致）。 */
    private static final int NAME_LEN_EXTEND = 1;

    /** 模式1 中首个 EXTINF 重复出现的基准次数。 */
    private static final int EXTINF_BENCHMARK = 5;

    /** 判定模式。 */
    public enum Mode {
        /** 序号递增型命名（模式0）。 */
        SEQUENCE,
        /** 无序号命名（模式1）。 */
        PLAIN,
        /** 兜底：只删标识（暴力拆解）。 */
        VIOLENCE,
        /** 未识别（列表不含 DISCONTINUITY 或结构不完整，不动作）。 */
        NONE
    }

    private HlsSegmentAdJudger() {
    }

    /** 供引擎使用的结构化分片（与 HlsAdEngine 内部表示一致）。 */
    public static final class Segment {
        public final String uri;
        public final double durationSec;
        public final boolean discontinuityBefore;
        public final String extinfLine;

        public Segment(String uri, double durationSec, boolean discontinuityBefore, String extinfLine) {
            this.uri = uri;
            this.durationSec = durationSec;
            this.discontinuityBefore = discontinuityBefore;
            this.extinfLine = extinfLine;
        }
    }

    public static final class Verdict {
        /** 判定出的广告分片下标（调用方仍需过安全框架）。 */
        public final Set<Integer> adIndexes;
        /** 识别出的命名模式。 */
        public final Mode mode;
        /** 暴力拆解模式：只删 DISCONTINUITY 标识，不删分片数据。 */
        public final boolean stripDiscontinuityOnly;
        /** 判定依据描述（诊断用）。 */
        public final String reason;

        Verdict(Set<Integer> adIndexes, Mode mode, boolean stripDiscontinuityOnly, String reason) {
            this.adIndexes = adIndexes;
            this.mode = mode;
            this.stripDiscontinuityOnly = stripDiscontinuityOnly;
            this.reason = reason;
        }
    }

    /**
     * 对结构化分片列表做特征判定。
     *
     * <p>保守原则：没有任何 DISCONTINUITY 的列表直接放弃（原脚本的全部删除规则
     * 都依赖 DISCONTINUITY 边界，没有边界时无从判断广告块起止）。
     */
    /** 暴力拆解可容忍的最大 DISCONTINUITY 数：真实贴片一般只有几个标记，
     *  大量（尤其均匀分布的）DISCONTINUITY 是编码分段而非广告隔离，删了会破坏解码边界。 */
    private static final int MAX_VIOLENCE_DISCONTINUITY = 8;

    public static Verdict judge(List<Segment> segments) {
        if (segments == null || segments.isEmpty()) return none();
        int discCount = 0;
        for (Segment s : segments) if (s.discontinuityBefore) discCount++;
        if (discCount == 0) return none();

        Mode mode = detectMode(segments);
        Set<Integer> adIndexes = new HashSet<>();
        switch (mode) {
            case SEQUENCE:
                judgeSequence(segments, adIndexes);
                break;
            case PLAIN:
                judgePlain(segments, adIndexes);
                break;
            case VIOLENCE:
                // 暴力拆解：不删分片，只删标识；标记过多说明是编码分段，放弃
                if (discCount > MAX_VIOLENCE_DISCONTINUITY) return none();
                return new Verdict(adIndexes, Mode.VIOLENCE, true, "violence");
            default:
                return none();
        }
        return new Verdict(adIndexes, mode, false,
                mode + " ad=" + adIndexes.size() + "/" + segments.size());
    }

    private static Verdict none() {
        return new Verdict(new HashSet<>(), Mode.NONE, false, "none");
    }

    /**
     * 命名模式识别（对应原脚本初始化循环）。
     *
     * <p>扫描全部分片：能提取到序号数字、且相邻分片序号连续（相隔 1）、
     * 前缀等长 → SEQUENCE；完全无序号 → PLAIN；序号时断时续 →
     * VIOLENCE（兜底）。抽样上限保证大列表的性能。
     */
    private static Mode detectMode(List<Segment> segments) {
        int limit = Math.min(segments.size(), 64);
        int seqCount = 0;
        int plainCount = 0;
        int brokenCount = 0;
        long prevIndex = -1;
        int prevPrefixLen = -1;
        boolean first = true;
        for (int i = 0; i < limit; i++) {
            Segment seg = segments.get(i);
            long index = extractIndex(seg.uri);
            int prefixLen = prefixLength(seg.uri);
            if (index < 0) {
                plainCount++;
                prevIndex = -1;
                prevPrefixLen = -1;
                first = true;
                continue;
            }
            if (first) {
                prevIndex = index;
                prevPrefixLen = prefixLen;
                first = false;
                seqCount++;
                continue;
            }
            if (prevIndex >= 0 && index == prevIndex + 1 && prefixLen == prevPrefixLen) {
                seqCount++;
            } else {
                brokenCount++;
            }
            prevIndex = index;
            prevPrefixLen = prefixLen;
        }
        if (plainCount > 0 && seqCount == 0) return Mode.PLAIN;
        if (seqCount > 0 && brokenCount == 0) return Mode.SEQUENCE;
        if (seqCount > 0) return Mode.VIOLENCE;
        return Mode.VIOLENCE;
    }

    /**
     * 模式0：DISCONTINUITY 之后的分片，前缀长度突变或序号断档 → 广告块。
     *
     * <p>与原脚本的差别：原脚本按「行」删除，这里按「分片」标记，删除动作
     * （含 EXTINF 与附属标签）交给引擎统一完成；前缀长度基准取列表中首个
     * 分片，随正片分片滚动更新（与原脚本一致）。
     */
    private static void judgeSequence(List<Segment> segments, Set<Integer> adIndexes) {
        int basePrefixLen = prefixLength(segments.get(0).uri);
        long prevIndex = extractIndex(segments.get(0).uri);
        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            if (!seg.discontinuityBefore) {
                // 非边界分片：滚动更新基准
                long index = extractIndex(seg.uri);
                if (index >= 0) prevIndex = index;
                int len = prefixLength(seg.uri);
                if (len > 0) basePrefixLen = len;
                continue;
            }
            int len = prefixLength(seg.uri);
            long index = extractIndex(seg.uri);
            boolean prefixJump = len > 0 && basePrefixLen > 0
                    && Math.abs(len - basePrefixLen) > NAME_LEN_EXTEND;
            boolean seqBreak = index >= 0 && prevIndex >= 0 && index != prevIndex + 1;
            if (prefixJump || seqBreak) {
                // 广告块起点：向后吞掉连续的 DISCONTINUITY 边界分片
                // （贴片广告常被两个 DISCONTINUITY 夹住）
                int j = i;
                while (j < segments.size() && segments.get(j).discontinuityBefore) {
                    adIndexes.add(j);
                    j++;
                }
                i = j - 1;
                continue;
            }
            prevIndex = index;
            basePrefixLen = len;
        }
    }

    /**
     * 模式1：无序号命名。首个 EXTINF 文本重复超过基准次数后，
     * DISCONTINUITY 之后出现不同文本 → 广告块（广告分片的 EXTINF 往往
     * 与正片模板不同且只出现一次）。
     */
    private static void judgePlain(List<Segment> segments, Set<Integer> adIndexes) {
        String firstExtinf = segments.get(0).extinfLine;
        int sameCount = 0;
        boolean extinfMode = false;
        for (Segment seg : segments) {
            if (seg.extinfLine == null) continue;
            if (!extinfMode && seg.extinfLine.equals(firstExtinf)) {
                sameCount++;
                if (sameCount > EXTINF_BENCHMARK) extinfMode = true;
            } else if (!seg.extinfLine.equals(firstExtinf)) {
                extinfMode = true;
            }
        }
        if (!extinfMode) return;
        for (int i = 0; i < segments.size(); i++) {
            Segment seg = segments.get(i);
            if (!seg.discontinuityBefore) continue;
            if (seg.extinfLine != null && !seg.extinfLine.equals(firstExtinf)) {
                int j = i;
                while (j < segments.size() && segments.get(j).discontinuityBefore) {
                    adIndexes.add(j);
                    j++;
                }
                i = j - 1;
            }
        }
    }

    /** 提取分片序号：basename 中第一段连续数字；无数字返回 -1。 */
    static long extractIndex(String uri) {
        String name = baseName(uri);
        int start = -1;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            if (digit && start < 0) start = i;
            if (!digit && start >= 0) {
                try {
                    return Long.parseLong(name.substring(start, i));
                } catch (NumberFormatException e) {
                    return -1;
                }
            }
        }
        if (start >= 0) {
            try {
                return Long.parseLong(name.substring(start));
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
    }

    /** 分片序号前的字符长度（对应原脚本的 ts 前缀长度）；无序号返回 0。 */
    static int prefixLength(String uri) {
        String name = baseName(uri);
        int start = -1;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean digit = c >= '0' && c <= '9';
            if (digit && start < 0) start = i;
            if (!digit && start >= 0) return start;
        }
        return start >= 0 ? start : 0;
    }

    private static String baseName(String uri) {
        if (uri == null) return "";
        String value = uri;
        int query = value.indexOf('?');
        if (query > 0) value = value.substring(0, query);
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    /** 从规则构建的占位（保持 API 对称，规则匹配不走本类）。 */
    public static List<HlsAdRule> emptyRules() {
        return new ArrayList<>();
    }
}

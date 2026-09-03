package com.fongmi.android.tv.player.exo;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.player.AdSegmentMemory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 依据已知广告区间删除 m3u8 中的广告分片（HLS 专属）。
 *
 * <p>内置的「智能去广」{@code HlsAdsParser} 只按分片 URL 的路径分组与块大小做
 * 启发式判断，完全不听音频，所以广告与正片同目录、命名相似时必然失效。
 * 这里改用声纹记忆给出的真实广告区间：累加 {@code #EXTINF} 得到每个分片的媒体
 * 时间范围，凡与广告区间重叠足够多的分片直接从播放列表移除，播放器根本不会
 * 下载它们，从而做到广告一帧不播（比「检测后再 seek」更干净）。
 *
 * <p>只处理含 {@code #EXTINF} 的媒体播放列表；master playlist 原样返回。
 *
 * <p><b>安全边界（fail-open 优先）</b>：改写播放列表比不删广告危险得多——删错就是
 * 整个视频播不了。因此对改写结果做三重校验，任一不满足就整体放弃改写：
 * <ol>
 *   <li>删除的媒体时长不得超过列表总时长的一半；</li>
 *   <li>删除后至少保留两个分片；</li>
 *   <li>{@code #EXT-X-KEY} / {@code #EXT-X-MAP} 一律保留（删掉会让剩余分片无法解密）。</li>
 * </ol>
 */
public final class HlsAdStripper {

    private static final String TAG_INF = "#EXTINF";
    private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";
    private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";
    private static final String TAG_MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE";
    private static final String TAG_KEY = "#EXT-X-KEY";
    private static final String TAG_MAP = "#EXT-X-MAP";

    /**
     * 判定为广告分片所需的最小重叠时长。低于此值视为区间边界抖动，
     * 保留该分片，宁可残留极短广告片段，也不误删正片。
     */
    private static final long MIN_OVERLAP_MS = 500L;

    /**
     * 允许删除的媒体时长占比上限（百分比）。
     *
     * <p>记忆区间来自声纹探针，存在误报可能。若某条误报区间覆盖了正片大部分，
     * 按区间删除会把视频删成只剩几秒——表现为进度条在 0 附近反复重播。
     * 广告不可能占据点播内容的一半以上，越界即说明区间不可信，放弃改写。
     */
    private static final long MAX_STRIP_RATIO_PERCENT = 50L;

    /** 改写后至少要保留的分片数；只剩一两个分片的列表没有保留价值。 */
    private static final int MIN_KEEP_SEGMENTS = 2;

    private HlsAdStripper() {
    }

    /**
     * 删除落在已知广告区间内的分片。
     *
     * @return 重建后的 m3u8；无命中、不可处理或不满足安全边界时返回原文
     */
    public static String strip(String m3u8, @NonNull List<AdSegmentMemory.Range> ranges) {
        if (m3u8 == null || m3u8.isEmpty() || ranges.isEmpty()) return m3u8;
        if (!m3u8.contains(TAG_INF)) return m3u8;
        // 只处理点播：直播列表随时间滑动，同一坐标此刻是广告、几小时后是正片，
        // 拿历史区间去删会直接把正片开头抹掉。宁可放过直播，也不能误删。
        if (!m3u8.contains(TAG_ENDLIST)) return m3u8;
        String[] lines = m3u8.split("\\r?\\n");
        SegmentScan scan = findAdSegments(lines, ranges);
        if (scan.adIndexes.isEmpty()) return m3u8;
        if (!isSafeToStrip(scan)) return m3u8;
        return rebuild(lines, scan, m3u8.length());
    }

    /** 累加 EXTINF 推出每个分片的媒体时间范围，返回命中广告区间的分片序号与时长统计。 */
    private static SegmentScan findAdSegments(String[] lines, List<AdSegmentMemory.Range> ranges) {
        Set<Integer> adIndexes = new HashSet<>();
        long cursorMs = 0L;
        long adDurationMs = 0L;
        int index = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (!line.startsWith(TAG_INF)) continue;
            long durationMs = Math.max(0L, Math.round(parseDuration(line) * 1000.0));
            long startMs = cursorMs;
            long endMs = cursorMs + durationMs;
            for (AdSegmentMemory.Range range : ranges) {
                long overlap = Math.min(endMs, range.end) - Math.max(startMs, range.start);
                if (overlap >= MIN_OVERLAP_MS) {
                    adIndexes.add(index);
                    adDurationMs += durationMs;
                    break;
                }
            }
            cursorMs = endMs;
            index++;
        }
        return new SegmentScan(adIndexes, index, cursorMs, adDurationMs);
    }

    /**
     * 改写前的安全校验：任一条不满足就保持原样。
     *
     * <p>这里必须保守：改写播放列表的收益是「少看几秒广告」，代价是「删错就播不了」。
     * 收益与风险严重不对称，所以边界一律往「不删」的方向取。
     */
    private static boolean isSafeToStrip(SegmentScan scan) {
        if (scan.segmentCount - scan.adIndexes.size() < MIN_KEEP_SEGMENTS) return false;
        if (scan.totalDurationMs <= 0L) return false;
        return scan.adDurationMs * 100L <= scan.totalDurationMs * MAX_STRIP_RATIO_PERCENT;
    }

    /** 解析 #EXTINF:<duration>[,<title>] 中的时长，单位秒。 */
    private static double parseDuration(String line) {
        try {
            int colon = line.indexOf(':');
            if (colon < 0) return 0.0;
            int comma = line.indexOf(',', colon + 1);
            String value = comma > 0 ? line.substring(colon + 1, comma) : line.substring(colon + 1);
            return Double.parseDouble(value.trim());
        } catch (Throwable e) {
            return 0.0;
        }
    }

    /** 移除命中分片、清理悬空标签并同步媒体序号。 */
    private static String rebuild(String[] lines, SegmentScan scan, int originalLength) {
        List<String> kept = removeSegments(lines, scan.adIndexes);
        kept = sanitizeDiscontinuity(kept);
        kept = syncMediaSequence(kept, scan.adIndexes.size());
        StringBuilder builder = new StringBuilder(Math.max(originalLength, 256));
        for (String line : kept) builder.append(line).append("\n");
        return builder.toString();
    }

    /**
     * 移除命中分片的 EXTINF 与其后的 URI，以及二者之间的附属标签。
     *
     * <p>分片序号只在消费 URI 行时递增：EXTINF 与它后面的 URI 同属一个分片，
     * 若在 EXTINF 处也递增，序号会与后续分片整体错位，导致该删的漏删、
     * 不该删的正片被误删。
     *
     * <p>{@code #EXT-X-KEY} 与 {@code #EXT-X-MAP} 不随分片删除：它们既可能是全列表
     * 共用的解密信息，也可能是 fMP4 的初始化段。删掉会让剩余分片直接无法解码，
     * 代价远大于残留一个广告分片，因此无条件保留。
     */
    private static List<String> removeSegments(String[] lines, Set<Integer> adIndexes) {
        List<String> result = new ArrayList<>(lines.length);
        boolean skipping = false;
        int index = 0;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith(TAG_INF)) {
                if (adIndexes.contains(index)) {
                    skipping = true;
                    continue;
                }
            } else if (isSegmentLine(line)) {
                boolean ad = adIndexes.contains(index);
                index++;
                if (skipping || ad) {
                    skipping = false;
                    continue;
                }
            } else if (skipping && !isProtectedTag(line)) {
                // EXTINF 与 URI 之间的标签（如 #EXT-X-BYTERANGE）属于该分片，一并丢弃；
                // 但解密与初始化信息必须留下。
                continue;
            }
            result.add(line);
        }
        return result;
    }

    private static boolean isProtectedTag(String line) {
        return line.startsWith(TAG_KEY) || line.startsWith(TAG_MAP);
    }

    /**
     * 清理删除后失去意义的 discontinuity。
     *
     * <p>两类必须清掉：
     * <ol>
     *   <li><b>位于第一个分片之前的标签</b>：删除片头广告后，原本标记「广告→正片」的
     *       {@code #EXT-X-DISCONTINUITY} 会悬在第一个分片之前。它前面是 {@code #EXTM3U}
     *       等头部行，所以「前一行是不是 discontinuity」的判断永远不成立，必须显式
     *       按「是否出现过分片」来判定。留着它会把整个列表推入 discontinuity 序列 1，
     *       播放器要为一段不存在的内容做时间线补偿，Media3 1.11 起该场景会出现
     *       起播即回退、进度条在 0 附近反复的表现；</li>
     *   <li>相邻重复或紧邻 {@code #EXT-X-ENDLIST} 的标签。</li>
     * </ol>
     */
    private static List<String> sanitizeDiscontinuity(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        boolean seenSegment = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (isSegmentLine(line)) seenSegment = true;
            if (line.equals(TAG_DISCONTINUITY)) {
                boolean beforeAnySegment = !seenSegment;
                boolean prevBoundary = i == 0 || lines.get(i - 1).equals(TAG_DISCONTINUITY);
                String next = i + 1 < lines.size() ? lines.get(i + 1) : null;
                boolean nextBoundary = next == null || next.equals(TAG_DISCONTINUITY)
                        || next.equals(TAG_ENDLIST);
                if (beforeAnySegment || prevBoundary || nextBoundary) continue;
            }
            result.add(line);
        }
        return result;
    }

    /**
     * 删除分片后同步 {@code #EXT-X-MEDIA-SEQUENCE}。
     *
     * <p>该标签声明列表第一个分片的媒体序号。分片被移除后序号必须相应前进，
     * 否则任何按序号做增量刷新/去重的环节都会把删减版误认为与原始列表同序号的
     * 完整列表，进而按错误的时间基准定位。列表原本没有该标签时不添加，
     * 避免改变源站的语义。
     */
    private static List<String> syncMediaSequence(List<String> lines, int removedCount) {
        if (removedCount <= 0) return lines;
        List<String> result = new ArrayList<>(lines.size());
        boolean found = false;
        for (String line : lines) {
            if (!found && line.startsWith(TAG_MEDIA_SEQUENCE)) {
                int colon = line.indexOf(':');
                String value = colon >= 0 ? line.substring(colon + 1).trim() : "0";
                long sequence;
                try {
                    sequence = Long.parseLong(value);
                } catch (NumberFormatException ignored) {
                    // 源站写了非数字，保持原样不动
                    result.add(line);
                    found = true;
                    continue;
                }
                result.add(TAG_MEDIA_SEQUENCE + ":" + (sequence + removedCount));
                found = true;
                continue;
            }
            result.add(line);
        }
        return found ? result : lines;
    }

    private static boolean isSegmentLine(String line) {
        return !line.isEmpty() && !line.startsWith("#");
    }

    /** 分片扫描结果：命中序号、分片总数、列表总时长、将被删除的时长。 */
    private static final class SegmentScan {
        final Set<Integer> adIndexes;
        final int segmentCount;
        final long totalDurationMs;
        final long adDurationMs;

        SegmentScan(Set<Integer> adIndexes, int segmentCount, long totalDurationMs,
                    long adDurationMs) {
            this.adIndexes = adIndexes;
            this.segmentCount = segmentCount;
            this.totalDurationMs = totalDurationMs;
            this.adDurationMs = adDurationMs;
        }
    }
}

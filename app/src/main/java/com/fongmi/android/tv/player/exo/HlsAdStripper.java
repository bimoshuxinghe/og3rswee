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
 */
public final class HlsAdStripper {

    private static final String TAG_INF = "#EXTINF";
    private static final String TAG_DISCONTINUITY = "#EXT-X-DISCONTINUITY";
    private static final String TAG_ENDLIST = "#EXT-X-ENDLIST";

    /**
     * 判定为广告分片所需的最小重叠时长。低于此值视为区间边界抖动，
     * 保留该分片，宁可残留极短广告片段，也不误删正片。
     */
    private static final long MIN_OVERLAP_MS = 500L;

    private HlsAdStripper() {
    }

    /**
     * 删除落在已知广告区间内的分片。
     *
     * @return 重建后的 m3u8；无命中或不可处理时返回原文
     */
    public static String strip(String m3u8, @NonNull List<AdSegmentMemory.Range> ranges) {
        if (m3u8 == null || m3u8.isEmpty() || ranges.isEmpty()) return m3u8;
        if (!m3u8.contains(TAG_INF)) return m3u8;
        // 只处理点播：直播列表随时间滑动，同一坐标此刻是广告、几小时后是正片，
        // 拿历史区间去删会直接把正片开头抹掉。宁可放过直播，也不能误删。
        if (!m3u8.contains(TAG_ENDLIST)) return m3u8;
        String[] lines = m3u8.split("\\r?\\n");
        Set<Integer> adIndexes = findAdSegments(lines, ranges);
        if (adIndexes.isEmpty()) return m3u8;
        return rebuild(lines, adIndexes, m3u8.length());
    }

    /** 累加 EXTINF 推出每个分片的媒体时间范围，返回命中广告区间的分片序号。 */
    private static Set<Integer> findAdSegments(String[] lines, List<AdSegmentMemory.Range> ranges) {
        Set<Integer> adIndexes = new HashSet<>();
        long cursorMs = 0L;
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
                    break;
                }
            }
            cursorMs = endMs;
            index++;
        }
        return adIndexes;
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

    /** 移除命中分片的 EXTINF 与其后的 URI，以及二者之间的附属标签。 */
    private static String rebuild(String[] lines, Set<Integer> adIndexes, int originalLength) {
        List<String> kept = removeSegments(lines, adIndexes);
        kept = removeOrphanedDiscontinuity(kept);
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
            } else if (skipping) {
                // EXTINF 与 URI 之间的标签（如 #EXT-X-KEY）属于该分片，一并丢弃
                continue;
            }
            result.add(line);
        }
        return result;
    }

    /** 清掉删除后悬空的 discontinuity，避免播放器在首尾产生无意义的断层。 */
    private static List<String> removeOrphanedDiscontinuity(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.equals(TAG_DISCONTINUITY)) {
                boolean prevBoundary = i == 0 || lines.get(i - 1).equals(TAG_DISCONTINUITY);
                String next = i + 1 < lines.size() ? lines.get(i + 1) : null;
                boolean nextBoundary = next == null || next.equals(TAG_DISCONTINUITY)
                        || next.equals(TAG_ENDLIST);
                if (prevBoundary || nextBoundary) continue;
            }
            result.add(line);
        }
        return result;
    }

    private static boolean isSegmentLine(String line) {
        return !line.isEmpty() && !line.startsWith("#");
    }
}

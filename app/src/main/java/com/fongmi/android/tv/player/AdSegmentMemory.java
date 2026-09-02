package com.fongmi.android.tv.player;

import android.util.Log;

import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.utils.Prefers;
import com.github.catvod.utils.Util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 广告区间记忆库。
 *
 * <p>把声纹去广告实时确认过的广告区间按视频持久化。同一视频再次播放时，在开播前
 * 就已知广告位置：HLS 可以直接把对应分片从 m3u8 里删掉（见 {@code HlsAdStripper}），
 * MP4/FLV 则预先登记为待跳过区间、宿主一到起点立即 seek。首次播放仍走实时检测。
 *
 * <p>存储按视频 URL 的 md5 建索引，值为 {@code [[startMs, endMs], ...]}，
 * 带 LRU 上限与长度过滤；任何异常都退化为「没有记忆」，绝不影响正常播放。
 */
public final class AdSegmentMemory {

    private static final String TAG = "AdSegmentMemory";
    private static final String PREF = "ad_segment_memory";
    private static final int MAX_VIDEOS = 300;
    private static final int MAX_RANGES_PER_VIDEO = 32;
    private static final long MIN_DURATION_MS = 800L;
    private static final long MAX_DURATION_MS = 600_000L;
    private static final long MERGE_TOLERANCE_MS = 1500L;

    private static volatile LinkedHashMap<String, List<long[]>> cache;

    /**
     * 当前正在起播的视频地址。
     *
     * <p>不能依赖 {@code AdProbeManager.getLastUrl()}：探针在实例为空或音纹去广告
     * 未开启时会直接 return，那个字段就永远是 null，删分片会静默失效。这里由播放侧
     * 在 {@code engine.start()} 之前显式写入，保证播放列表请求发出时一定已就绪。
     */
    private static volatile String currentUrl;

    /**
     * 本次播放是否已经删过 m3u8 分片。
     *
     * <p>分片被删除后媒体时间轴会整体前移：原本位于 0～30s 的广告消失，正片从 0s
     * 开始。此时如果再拿记忆区间去做位置判断，就会把正片开头当成广告跳掉。因此
     * 删过分片后必须同时禁用两件事——按位置的预跳过、以及写入新区间（新记录的时间
     * 轴已变，写进去就是脏数据）。
     */
    private static volatile boolean strippedThisSession;

    /** 播放列表改写层成功删掉分片后调用。 */
    public static void markStripped() {
        strippedThisSession = true;
    }

    /** 本次播放是否已删过分片。 */
    public static boolean isStrippedThisSession() {
        return strippedThisSession;
    }

    /** 起播前登记当前视频地址；HLS 删分片与非 HLS 预跳过都以它为准。 */
    public static void setCurrentUrl(String url) {
        currentUrl = url;
        strippedThisSession = false;
    }

    public static String getCurrentUrl() {
        return currentUrl;
    }

    /** 一条已知广告区间，单位为毫秒的媒体时间。 */
    public static final class Range {
        public final long start;
        public final long end;

        Range(long start, long end) {
            this.start = start;
            this.end = end;
        }

        public boolean contains(long positionMs) {
            return positionMs >= start && positionMs < end;
        }
    }

    private AdSegmentMemory() {
    }

    private static LinkedHashMap<String, List<long[]>> map() {
        LinkedHashMap<String, List<long[]>> current = cache;
        if (current != null) return current;
        LinkedHashMap<String, List<long[]>> loaded = new LinkedHashMap<>();
        try {
            JSONObject root = new JSONObject(Prefers.getString(PREF, "{}"));
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                JSONArray array = root.optJSONArray(key);
                if (array == null) continue;
                List<long[]> ranges = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) {
                    JSONArray item = array.optJSONArray(i);
                    if (item == null || item.length() != 2) continue;
                    long start = item.optLong(0);
                    long end = item.optLong(1);
                    if (end > start) ranges.add(new long[]{start, end});
                }
                if (!ranges.isEmpty()) loaded.put(key, ranges);
            }
        } catch (Throwable ignored) {
            // 记忆库损坏时退化为「没有记忆」，绝不能影响播放。
        }
        cache = loaded;
        return loaded;
    }

    private static void persist(LinkedHashMap<String, List<long[]>> data) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<String, List<long[]>> entry : data.entrySet()) {
                JSONArray array = new JSONArray();
                for (long[] range : entry.getValue()) {
                    JSONArray item = new JSONArray();
                    item.put(range[0]);
                    item.put(range[1]);
                    array.put(item);
                }
                root.put(entry.getKey(), array);
            }
            Prefers.put(PREF, root.toString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * 查询某视频已知广告区间；未开启记忆或无记录时返回空列表。
     *
     * <p>传入 null 时回落到 {@link #getCurrentUrl()}——调用方（如播放列表改写层）
     * 拿不到显式地址时用当前播放中的地址兜底。
     */
    public static List<Range> get(String url) {
        String target = (url == null || url.isEmpty()) ? currentUrl : url;
        if (target == null || target.isEmpty() || !Setting.isAdSegmentMemory()) return Collections.emptyList();
        List<long[]> raw = map().get(keyOf(target));
        if (raw == null || raw.isEmpty()) return Collections.emptyList();
        List<Range> output = new ArrayList<>(raw.size());
        for (long[] range : raw) output.add(new Range(range[0], range[1]));
        return output;
    }

    /** 记录一条确认过的广告区间；异常长度与重复记录会被忽略。 */
    public static void record(String url, long startMs, long endMs) {
        if (url == null || url.isEmpty() || endMs <= startMs) return;
        // 已删分片的这次播放，时间轴与记忆库里的区间不再同一坐标系，写入即脏数据。
        if (strippedThisSession) return;
        long duration = endMs - startMs;
        if (duration < MIN_DURATION_MS || duration > MAX_DURATION_MS) return;
        synchronized (AdSegmentMemory.class) {
            LinkedHashMap<String, List<long[]>> data = map();
            String key = keyOf(url);
            List<long[]> ranges = data.get(key);
            if (ranges == null) ranges = new ArrayList<>();
            for (long[] existing : ranges) {
                if (Math.abs(existing[0] - startMs) <= MERGE_TOLERANCE_MS
                        && Math.abs(existing[1] - endMs) <= MERGE_TOLERANCE_MS) {
                    return;
                }
            }
            ranges.add(new long[]{startMs, endMs});
            if (ranges.size() > MAX_RANGES_PER_VIDEO) {
                ranges = new ArrayList<>(
                        ranges.subList(ranges.size() - MAX_RANGES_PER_VIDEO, ranges.size()));
            }
            data.remove(key);
            data.put(key, ranges);
            while (data.size() > MAX_VIDEOS) {
                Iterator<String> iterator = data.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
            persist(data);
        }
        Log.d(TAG, "记忆广告区间 " + startMs + "-" + endMs + "ms key=" + keyOf(url));
    }

    /**
     * 命中任一已知区间则返回该区间，否则返回 null。
     *
     * <p>仅用于时间轴不变的封装格式（MP4/FLV 等）。HLS 删分片会让时间轴前移，
     * 因此已删过分片时一律返回 null，避免把正片开头误当广告跳掉。
     */
    public static Range find(String url, long positionMs) {
        if (strippedThisSession) return null;
        for (Range range : get(url)) {
            if (range.contains(positionMs)) return range;
        }
        return null;
    }

    public static void clear() {
        synchronized (AdSegmentMemory.class) {
            cache = new LinkedHashMap<>();
            Prefers.put(PREF, "{}");
        }
    }

    private static String keyOf(String url) {
        return Util.md5(url);
    }
}

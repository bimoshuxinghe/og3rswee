package com.fongmi.android.tv.bean;

import android.content.Context;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.github.catvod.utils.Prefers;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 去广告拦截统计（移植自 WebHTV AdBlockStats，按需精简）。
 *
 * <p>意义：让「有没有误杀」从猜变成看得见。每次净化后由 DataSource 层记录：
 * 命中删除（按引擎分规则/特征/暴力）、安全回退、无动作，各自计数。
 */
public final class AdBlockStats {

    private static final String TAG = "AdBlockStats";
    private static final String KEY = "hls_ad_stats";

    /** 引擎标识（与 HlsAdEngine.ENGINE_* 保持一致）。 */
    public static final String ENGINE_RULE = "rule";
    public static final String ENGINE_FEATURE = "feature";

    private long totalStripped;
    private long totalFallback;
    private long totalUnchanged;
    private long ruleHits;
    private long featureHits;
    private long violenceHits;
    private long lastResetAt;

    private static volatile AdBlockStats cache;

    private AdBlockStats() {
    }

    public static AdBlockStats get() {
        AdBlockStats local = cache;
        if (local != null) return local;
        synchronized (AdBlockStats.class) {
            if (cache != null) return cache;
            AdBlockStats stats = new AdBlockStats();
            stats.load();
            cache = stats;
            return stats;
        }
    }

    public long totalStripped() { return totalStripped; }
    public long totalFallback() { return totalFallback; }
    public long totalUnchanged() { return totalUnchanged; }
    public long ruleHits() { return ruleHits; }
    public long featureHits() { return featureHits; }
    public long violenceHits() { return violenceHits; }
    public long lastResetAt() { return lastResetAt; }

    /**
     * 记录一次净化结果。
     *
     * @param engine          HlsAdEngine.ENGINE_RULE / ENGINE_FEATURE
     * @param changed         是否改写了播放列表
     * @param fallback        是否因不安全而回退原文
     * @param removedSegments 删除的分片数（暴力拆解模式为 0）
     */
    public void record(String engine, boolean changed, boolean fallback, int removedSegments) {
        if (changed) {
            totalStripped++;
            if (removedSegments == 0) {
                // 暴力拆解：只删标识未删分片
                violenceHits++;
            } else if (ENGINE_RULE.equals(engine)) {
                ruleHits++;
            } else {
                featureHits++;
            }
        } else if (fallback) {
            totalFallback++;
        } else {
            totalUnchanged++;
        }
        save();
    }

    public void reset() {
        totalStripped = 0;
        totalFallback = 0;
        totalUnchanged = 0;
        ruleHits = 0;
        featureHits = 0;
        violenceHits = 0;
        lastResetAt = System.currentTimeMillis();
        save();
    }

    private void load() {
        try {
            String raw = Prefers.getString(KEY, "");
            if (raw == null || raw.isEmpty()) return;
            JSONObject json = new JSONObject(raw);
            totalStripped = json.optLong("totalStripped");
            totalFallback = json.optLong("totalFallback");
            totalUnchanged = json.optLong("totalUnchanged");
            ruleHits = json.optLong("ruleHits");
            featureHits = json.optLong("featureHits");
            violenceHits = json.optLong("violenceHits");
            lastResetAt = json.optLong("lastResetAt");
        } catch (Throwable e) {
            Log.w(TAG, "load failed", e);
        }
    }

    private void save() {
        try {
            Context context = App.get();
            if (context == null) return;
            JSONObject json = new JSONObject();
            json.put("totalStripped", totalStripped);
            json.put("totalFallback", totalFallback);
            json.put("totalUnchanged", totalUnchanged);
            json.put("ruleHits", ruleHits);
            json.put("featureHits", featureHits);
            json.put("violenceHits", violenceHits);
            json.put("lastResetAt", lastResetAt);
            Prefers.put(KEY, json.toString());
        } catch (Throwable e) {
            Log.w(TAG, "save failed", e);
        }
    }

    /** 展示用汇总（设置页）。 */
    public static Map<String, String> summary() {
        AdBlockStats s = get();
        Map<String, String> map = new LinkedHashMap<>();
        map.put("totalStripped", String.valueOf(s.totalStripped()));
        map.put("ruleHits", String.valueOf(s.ruleHits()));
        map.put("featureHits", String.valueOf(s.featureHits()));
        map.put("violenceHits", String.valueOf(s.violenceHits()));
        map.put("totalFallback", String.valueOf(s.totalFallback()));
        map.put("totalUnchanged", String.valueOf(s.totalUnchanged()));
        return map;
    }
}

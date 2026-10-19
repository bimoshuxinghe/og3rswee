package com.fongmi.android.tv.utils;

import android.content.Context;
import android.util.Log;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.bean.HlsAdRule;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * HLS 去广告规则库：assets 内置基线 + 用户禁用状态持久化。
 *
 * <p>规则治理沿用 WebHTV 的纪律：规则 ID 是永久逻辑身份；内置规则经
 * 「正样本（应删）+ 反样本（相同时长无广告边界 / 错误 host）」验证后才收录；
 * 每条规则可单独禁用，禁用状态持久化。
 */
public final class HlsAdRuleStore {

    private static final String TAG = "HlsAdRuleStore";
    private static final String ASSET = "adrules.json";
    private static final String KEY_DISABLED = "hls_ad_rule_disabled";

    private static volatile List<HlsAdRule> cache;

    private HlsAdRuleStore() {
    }

    /** 全部内置规则（enabled 已合并用户禁用状态；调用方无需再判禁用）。 */
    public static List<HlsAdRule> getRules() {
        List<HlsAdRule> local = cache;
        if (local != null) return local;
        synchronized (HlsAdRuleStore.class) {
            if (cache != null) return cache;
            cache = parseAssets();
            return cache;
        }
    }

    /** 重新加载（设置页修改禁用状态后调用）。 */
    public static void reload() {
        cache = null;
    }

    public static boolean isDisabled(String id) {
        return disabledIds().contains(id);
    }

    public static void setDisabled(String id, boolean disabled) {
        List<String> ids = disabledIds();
        if (disabled) {
            if (!ids.contains(id)) ids.add(id);
        } else {
            ids.remove(id);
        }
        Prefers.put(KEY_DISABLED, new JSONArray(ids).toString());
        reload();
    }

    private static List<String> disabledIds() {
        List<String> ids = new ArrayList<>();
        try {
            String raw = Prefers.getString(KEY_DISABLED, "");
            if (raw == null || raw.isEmpty()) return ids;
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) ids.add(array.getString(i));
        } catch (Throwable e) {
            Log.w(TAG, "disabledIds parse failed", e);
        }
        return ids;
    }

    private static List<HlsAdRule> parseAssets() {
        List<HlsAdRule> rules = new ArrayList<>();
        try {
            Context context = App.get();
            if (context == null) return rules;
            byte[] raw = readAsset(context, ASSET);
            if (raw == null) return rules;
            JSONObject root = new JSONObject(new String(raw, "UTF-8"));
            JSONArray array = root.optJSONArray("rules");
            if (array == null) return rules;
            List<String> disabled = disabledIds();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                HlsAdRule rule = fromJson(item, !disabled.contains(item.optString("id")));
                if (rule != null) rules.add(rule);
            }
        } catch (Throwable e) {
            Log.w(TAG, "parse assets failed", e);
        }
        return rules;
    }

    private static HlsAdRule fromJson(JSONObject item, boolean enabled) {
        try {
            HlsAdRule.Builder builder = HlsAdRule.builder()
                    .id(item.optString("id"))
                    .name(item.optString("name"))
                    .enabled(enabled)
                    .hostSuffixes(toStringList(item.optJSONArray("hostSuffixes")))
                    .playlistHostSuffixes(toStringList(item.optJSONArray("playlistHostSuffixes")))
                    .playlistHostPatterns(toStringList(item.optJSONArray("playlistHostPatterns")))
                    .segmentUrlPatterns(toStringList(item.optJSONArray("segmentUrlPatterns")))
                    .minimumSignals(item.optInt("minimumSignals", 1))
                    .requireDiscontinuity(item.optBoolean("requireDiscontinuity", false))
                    .requireCrossDomain(item.optBoolean("requireCrossDomain", false));
            JSONArray range = item.optJSONArray("durationRange");
            if (range != null && range.length() == 2) {
                builder.durationRange(range.getDouble(0), range.getDouble(1));
            }
            HlsAdRule rule = builder.build();
            if (rule.id().isEmpty()) return null;
            return rule;
        } catch (Throwable e) {
            Log.w(TAG, "rule parse failed", e);
            return null;
        }
    }

    private static List<String> toStringList(JSONArray array) {
        List<String> result = new ArrayList<>();
        if (array == null) return result;
        for (int i = 0; i < array.length(); i++) result.add(array.optString(i));
        return result;
    }

    private static byte[] readAsset(Context context, String name) throws Exception {
        try (InputStream in = context.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }
}

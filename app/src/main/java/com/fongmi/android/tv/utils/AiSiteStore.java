package com.fongmi.android.tv.utils;

import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 写源的站点级持久化辅助：
 * <ul>
 *   <li>禁用集合：站点保留在数据库但不出现在线路列表（启停开关）；</li>
 *   <li>生成历史：每次 AI 识别的配置存档，可回看、重应用、导出。</li>
 * </ul>
 * 放在 main 源码集，供 VodConfig.initSite 回填时过滤。
 */
public class AiSiteStore {

    private static final String KEY_DISABLED = "ai_site_disabled";
    private static final String KEY_HISTORY = "ai_site_history";
    private static final int MAX_HISTORY = 20;

    // ---------------- 禁用集合 ----------------

    public static List<String> disabledKeys() {
        List<String> list = new ArrayList<>();
        try {
            String raw = Prefers.getString(KEY_DISABLED);
            if (!raw.isEmpty()) {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) list.add(array.optString(i));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static boolean isDisabled(String key) {
        return key != null && disabledKeys().contains(key);
    }

    public static void setDisabled(String key, boolean disabled) {
        try {
            List<String> keys = disabledKeys();
            if (disabled) {
                if (!keys.contains(key)) keys.add(key);
            } else {
                keys.remove(key);
            }
            JSONArray array = new JSONArray();
            for (String item : keys) array.put(item);
            Prefers.put(KEY_DISABLED, array.toString());
        } catch (Exception ignored) {
        }
    }

    // ---------------- 生成历史 ----------------

    public static class Record {

        public final long time;
        public final String url;
        public final String config;

        Record(long time, String url, String config) {
            this.time = time;
            this.url = url;
            this.config = config;
        }

        public String label() {
            String timeText = new java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(new java.util.Date(time));
            return timeText + "  " + (url == null ? "" : url);
        }
    }

    public static List<Record> history() {
        List<Record> list = new ArrayList<>();
        try {
            String raw = Prefers.getString(KEY_HISTORY);
            if (raw.isEmpty()) return list;
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                list.add(new Record(obj.optLong("time"), obj.optString("url"), obj.optString("config")));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void addHistory(String url, String config) {
        try {
            if (config == null || config.isEmpty()) return;
            JSONArray array = new JSONArray();
            JSONObject obj = new JSONObject();
            obj.put("time", System.currentTimeMillis());
            obj.put("url", url == null ? "" : url);
            obj.put("config", config);
            array.put(obj);
            String raw = Prefers.getString(KEY_HISTORY);
            if (!raw.isEmpty()) {
                JSONArray old = new JSONArray(raw);
                for (int i = 0; i < old.length() && array.length() < MAX_HISTORY; i++) array.put(old.get(i));
            }
            Prefers.put(KEY_HISTORY, array.toString());
        } catch (Exception ignored) {
        }
    }

    public static void removeHistory(long time) {
        try {
            JSONArray array = new JSONArray();
            for (Record record : history()) {
                if (record.time != time) {
                    JSONObject obj = new JSONObject();
                    obj.put("time", record.time);
                    obj.put("url", record.url);
                    obj.put("config", record.config);
                    array.put(obj);
                }
            }
            Prefers.put(KEY_HISTORY, array.toString());
        } catch (Exception ignored) {
        }
    }
}

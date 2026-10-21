package com.fongmi.android.tv.utils;

import com.github.catvod.utils.Prefers;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * AI 写源的"技能"库：用户从存储导入的写法说明/示例文本（.md/.txt/.js/.py 等），
 * AI 识别时把勾选启用的技能内容注入 prompt，引导模型按特定站型/写法生成。
 */
public class AiSkillStore {

    private static final String KEY = "ai_skills";

    public static class Skill {

        public final String name;
        public final String content;
        public boolean enabled;

        Skill(String name, String content, boolean enabled) {
            this.name = name;
            this.content = content;
            this.enabled = enabled;
        }
    }

    public static List<Skill> all() {
        List<Skill> list = new ArrayList<>();
        try {
            String raw = Prefers.getString(KEY);
            if (raw.isEmpty()) return list;
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) continue;
                list.add(new Skill(obj.optString("name"), obj.optString("content"), obj.optBoolean("enabled", true)));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void save(List<Skill> skills) {
        try {
            JSONArray array = new JSONArray();
            for (Skill skill : skills) {
                JSONObject obj = new JSONObject();
                obj.put("name", skill.name);
                obj.put("content", skill.content);
                obj.put("enabled", skill.enabled);
                array.put(obj);
            }
            Prefers.put(KEY, array.toString());
        } catch (Exception ignored) {
        }
    }

    public static void add(String name, String content) {
        List<Skill> skills = all();
        skills.removeIf(skill -> skill.name.equals(name));
        skills.add(new Skill(name, content, true));
        save(skills);
    }

    public static void remove(String name) {
        List<Skill> skills = all();
        skills.removeIf(skill -> skill.name.equals(name));
        save(skills);
    }

    /** 勾选启用的技能文本，供 prompt 注入。 */
    public static List<String> enabledContents() {
        List<String> result = new ArrayList<>();
        for (Skill skill : all()) {
            if (skill.enabled && skill.content != null && !skill.content.isEmpty()) {
                result.add("【技能：" + skill.name + "】\n" + skill.content);
            }
        }
        return result;
    }
}

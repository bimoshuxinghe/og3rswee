package com.fongmi.android.tv.player;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.media3.common.Player;
import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文本关键词规则：监听字幕轨道 cue 文本，按用户编写的通配规则匹配广告标记并跳过。
 *
 * <p>规则文件：RULES.JSON 的 {@code textRules} 数组，每个元素一条规则字符串，语法：</p>
 * <ul>
 *   <li>{@code //} 开头为注释行</li>
 *   <li>{@code *} 通配任意字符（含空）</li>
 *   <li>{@code ##} 分隔多句，按顺序全部出现才触发（跨多条字幕累积）</li>
 *   <li>{@code #30} 命中后向后跳 30 秒</li>
 *   <li>{@code [2,30]} 命中后延迟 2 秒，跳到命中位置 + 30 秒处</li>
 *   <li>无跳转指令时默认向后跳 30 秒</li>
 * </ul>
 *
 * <p>示例：{@code 本片*赞助##广告之后*回来#30} 表示先出现“本片…赞助”，再出现
 * “广告之后…回来”时命中，向后跳 30 秒。</p>
 */
public final class TextAdRuleManager {

    private static volatile TextAdRuleManager instance;

    /** 字幕规则匹配窗口：字幕命中后窗口期内需完整走完多句序列。 */
    private static final long MATCH_WINDOW_MS = 20_000;
    /** 语音识别命中冷却：同一段广告口语反复出现时不重复跳转。 */
    private static final long SPEECH_COOLDOWN_MS = 15_000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<Rule> rules = new ArrayList<>();

    private Player player;
    private String loadedPath;
    private long lastSpeechHitMs;

    private final Player.Listener listener = new Player.Listener() {
        @Override
        public void onCues(@NonNull CueGroup cueGroup) {
            Player p = player;
            if (p == null || !Setting.isTextAdRule() || rules.isEmpty()) return;
            long pos = p.getCurrentPosition();
            for (Cue cue : cueGroup.cues) {
                if (cue == null || cue.text == null || cue.text.length() == 0) continue;
                String text = cue.text.toString().replaceAll("\\s+", "").toLowerCase(Locale.US);
                if (text.isEmpty()) continue;
                for (Rule rule : rules) {
                    if (rule.consume(text, pos, p)) break;
                }
            }
        }
    };

    public static TextAdRuleManager get() {
        if (instance == null) {
            synchronized (TextAdRuleManager.class) {
                if (instance == null) instance = new TextAdRuleManager();
            }
        }
        return instance;
    }

    private TextAdRuleManager() {
    }

    /** 绑定宿主播放器并注册字幕监听。每次 PlayerManager 重建都会调用。 */
    public void attach(Player player) {
        detach();
        this.player = player;
        if (player != null) {
            player.addListener(listener);
            loadRules();
        }
    }

    /** 释放播放器时解绑。 */
    public void detach() {
        if (player != null) {
            try {
                player.removeListener(listener);
            } catch (Exception ignored) {
            }
        }
        player = null;
        cancelPendingSeek();
        loadedPath = null;
    }

    /** 新媒体打开时调用：重置规则状态并重新加载规则文件。 */
    public void onMediaOpened() {
        cancelPendingSeek();
        for (Rule rule : rules) rule.reset();
        loadRules();
    }

    /** 手动重载（规则文件路径变更后调用）。 */
    public void reload() {
        cancelPendingSeek();
        for (Rule rule : rules) rule.reset();
        loadRules();
    }

    private void loadRules() {
        rules.clear();
        loadedPath = null;
        String path = Setting.getAdRulesPath();
        if (path == null || path.trim().isEmpty()) return;
        File file = new File(path.trim());
        if (!file.exists() || !file.isFile() || !file.canRead()) return;
        loadedPath = file.getAbsolutePath();
        try {
            StringBuilder sb = new StringBuilder((int) Math.min(file.length(), 4 * 1024 * 1024));
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                char[] buf = new char[8192];
                int len;
                while ((len = reader.read(buf)) != -1) sb.append(buf, 0, len);
            }
            JSONObject root = new JSONObject(sb.toString().trim());
            JSONArray arr = root.optJSONArray("textRules");
            if (arr == null) return;
            for (int i = 0; i < arr.length(); i++) {
                Rule rule = Rule.parse(this, arr.optString(i));
                if (rule != null) rules.add(rule);
            }
        } catch (Exception ignored) {
            rules.clear();
        }
    }

    private void cancelPendingSeek() {
        mainHandler.removeCallbacksAndMessages(null);
    }

    private void scheduleSeek(final Player p, final long targetPosMs, final long delayMs) {
        if (delayMs > 0L) {
            mainHandler.postDelayed(() -> {
                if (p == player) p.seekTo(targetPosMs);
            }, delayMs);
        } else {
            p.seekTo(targetPosMs);
        }
    }

    private void notifyHit() {
        Notify.show(ResUtil.getString(R.string.ad_skipped));
    }

    /** 用户设置的默认跳过秒数（无 #N 后缀的规则使用）。 */
    private long defaultSkipMs() {
        return Setting.getAdTextSkipSeconds() * 1000L;
    }

    /**
     * 语音识别（Vosk）文本入口：把识别出的口语文本按关键字规则匹配并跳秒。
     * Vosk partial 结果会连续重复，故带冷却窗口，避免同一句广告反复触发跳转。
     */
    public void matchSpokenText(String text) {
        Player p = player;
        if (p == null || !Setting.isVoskEnabled() || rules.isEmpty()) return;
        long pos = p.getCurrentPosition();
        if (pos - lastSpeechHitMs < SPEECH_COOLDOWN_MS) return;
        if (text == null) return;
        String normalized = text.replaceAll("\\s+", "").toLowerCase(Locale.US);
        if (normalized.isEmpty()) return;
        for (Rule rule : rules) {
            if (rule.consume(normalized, pos, p)) {
                lastSpeechHitMs = pos;
                break;
            }
        }
    }

    /** 单条文本规则。静态内部类，通过 owner 调用外部实例方法。 */
    private static final class Rule {

        private final TextAdRuleManager owner;
        private final List<Pattern> segments = new ArrayList<>();
        private final long skipMs;
        private final long delayMs;
        private final long jumpMs;
        private int index;

        private Rule(TextAdRuleManager owner, long skipMs, long delayMs, long jumpMs) {
            this.owner = owner;
            this.skipMs = skipMs;
            this.delayMs = delayMs;
            this.jumpMs = jumpMs;
        }

        static Rule parse(TextAdRuleManager owner, String rawLine) {
            if (rawLine == null) return null;
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("//")) return null;

            long skipMs = 0L, delayMs = 0L, jumpMs = 0L;
            Matcher rangeMatcher = Pattern.compile("\\[(\\d+),(\\d+)]$").matcher(line);
            if (rangeMatcher.find()) {
                delayMs = Long.parseLong(rangeMatcher.group(1)) * 1000L;
                jumpMs = Long.parseLong(rangeMatcher.group(2)) * 1000L;
                line = line.substring(0, rangeMatcher.start());
            } else {
                Matcher skipMatcher = Pattern.compile("#(\\d+)$").matcher(line);
                if (skipMatcher.find()) {
                    skipMs = Long.parseLong(skipMatcher.group(1)) * 1000L;
                    line = line.substring(0, skipMatcher.start());
                }
            }
            if (line.isEmpty()) return null;

            Rule rule = new Rule(owner, skipMs, delayMs, jumpMs);
            String[] parts = line.split("##", -1);
            for (String part : parts) {
                String pattern = part.trim();
                if (pattern.isEmpty()) continue;
                rule.segments.add(Pattern.compile(toRegex(pattern)));
            }
            return rule.segments.isEmpty() ? null : rule;
        }

        /** 把用户规则转成正则：* 通配任意字符，其余字符转义。 */
        private static String toRegex(String pattern) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < pattern.length(); i++) {
                char c = pattern.charAt(i);
                if (c == '*') {
                    sb.append(".*");
                } else if ("\\.^$|?+()[]{}-".indexOf(c) >= 0) {
                    sb.append('\\').append(c);
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        /** 消费一条字幕文本；命中最后一段时触发跳转并返回 true。 */
        boolean consume(String text, long positionMs, Player p) {
            if (index >= segments.size()) return false;
            if (!segments.get(index).matcher(text).find()) return false;
            index++;
            if (index < segments.size()) return false;
            reset();
            trigger(positionMs, p);
            return true;
        }

        private void trigger(long positionMs, Player p) {
            if (jumpMs > 0L) {
                owner.scheduleSeek(p, positionMs + jumpMs, delayMs);
            } else {
                long dist = skipMs > 0L ? skipMs : owner.defaultSkipMs();
                p.seekTo(positionMs + dist);
            }
            owner.notifyHit();
        }

        void reset() {
            index = 0;
        }
    }
}

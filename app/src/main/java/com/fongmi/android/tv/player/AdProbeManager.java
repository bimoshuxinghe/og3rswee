package com.fongmi.android.tv.player;

import android.content.Context;
import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.fongmi.adaudio.probe.AdAudioProbe;
import io.github.fongmi.adaudio.probe.PlaybackClock;
import io.github.fongmi.adaudio.probe.ProbeError;
import io.github.fongmi.adaudio.probe.ProbeListener;
import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.RuleReplacementResult;
import io.github.fongmi.adaudio.probe.SkipRequest;

/**
 * 智能趣广告（音频探针）宿主桥接层。
 *
 * <p>SDK 自包含：内部用无声纯音频 ExoPlayer 快速解码并与规则指纹匹配，宿主只负责
 * 提供当前播放位置（{@link PlaybackClock}）、在收到跳转请求时 seek 自己的播放器、
 * 以及在自身拖动时通知探针。规则由采集器 APK 生成本地 RULES.JSON 文件，
 * 探针通过 {@code replaceRulesJson()} 注入，未配置或文件不存在时 fail-open，
 * 不影响正常播放。</p>
 */
public final class AdProbeManager {

    /** 诊断日志 TAG；探针是 fail-open 设计，出问题时只有这里能看到原因。 */
    private static final String TAG = "AdProbe";

    /** SDK 适配器仅放行不会向重定向目标泄露凭据的安全请求头白名单。 */
    private static final Set<String> ALLOWED_HEADER_NAMES = Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "user-agent", "accept", "accept-language", "cache-control", "pragma")));

    private static volatile AdProbeManager instance;

    private AdAudioProbe probe;
    private Player player;
    private Context appContext;
    private String lastUrl;
    private Map<String, String> lastHeaders;
    private String rulesPath;

    private final PlaybackClock clock = () -> {
        Player p = player;
        if (p == null) return 0L;
        long pos = p.getCurrentPosition();
        return pos < 0L ? 0L : pos;
    };

    private final ProbeListener listener = new ProbeListener() {
        @Override
        public void onSkipRequested(SkipRequest request) {
            Player p = player;
            if (p == null) return;
            int mode = Setting.getAdSkipMode();
            long target = request.getSeekTargetPositionMs();
            // 0=仅提示，1=提示+自动跳过，2=仅自动跳过
            if (mode == Setting.AD_SKIP_MODE_NOTICE) {
                Notify.show(ResUtil.getString(R.string.ad_skipped));
                return;
            }
            if (target < 0L) return;
            Log.i(TAG, "命中广告，请求跳转 " + target + "ms（当前 " + p.getCurrentPosition()
                    + "ms，模式 " + mode + "）");
            try {
                p.seekTo(target);
            } catch (Exception e) {
                // 宿主 seek 失败不应影响后续检测
                Log.w(TAG, "跳转失败", e);
            }
            if (mode == Setting.AD_SKIP_MODE_NOTICE_AND_SKIP) {
                Notify.show(ResUtil.getString(R.string.ad_skipped));
            }
        }

        @Override
        public void onError(ProbeError error) {
            // fail-open：探针错误只记录，绝不打断宿主播放。
            // 这里必须打日志——否则 Media3 大版本升级导致探针内部崩溃时，
            // 用户只会看到「音纹去广告不工作」，而拿不到任何线索。
            if (error == null) return;
            Log.w(TAG, "探针错误 code=" + error.getCode()
                    + " fatal=" + error.isFatal()
                    + " retryable=" + error.isRetryable()
                    + " msg=" + error.getMessage(), error.getCause());
        }

        @Override
        public void onRulesReplaced(RuleReplacementResult result) {
            // 规则替换终态回调（本地文件加载成功/失败均可在此处理）
            Log.i(TAG, "规则已替换 result=" + result);
        }
    };

    public static AdProbeManager get() {
        if (instance == null) {
            synchronized (AdProbeManager.class) {
                if (instance == null) instance = new AdProbeManager();
            }
        }
        return instance;
    }

    private AdProbeManager() {
    }

    /** 绑定宿主播放器并（按需）创建探针实例。每次 PlayerManager 重建都会调用。 */
    public void init(Context context, Player player) {
        this.player = player;
        if (context != null) this.appContext = context.getApplicationContext();
        this.rulesPath = Setting.getAdRulesPath();
        ensureProbe(context);
    }

    /** PlayerManager 切换引擎后更新宿主播放器引用。 */
    public void setPlayer(Player player) {
        this.player = player;
    }

    /**
     * 创建探针实例（无远程 URL），然后尝试从本地规则文件加载规则。
     * 文件不存在或格式错误时不抛异常（fail-open）。
     */
    private void ensureProbe(Context context) {
        if (probe != null) return;
        try {
            probe = AdAudioProbe.builder(context.getApplicationContext())
                    .setPlaybackClock(clock)
                    .setListener(listener)
                    .build();
            probe.setEnabled(Setting.isAiAdblock());
            loadRulesFromFile();  // 初始化后立即尝试加载本地规则
            Log.i(TAG, "探针已就绪 enabled=" + Setting.isAiAdblock()
                    + " rulesPath=" + rulesPath);
        } catch (RuntimeException | LinkageError e) {
            // fail-open：探针创建失败绝不打断宿主播放，但必须留下可诊断的日志，
            // 否则音纹去广告会「静默失效」，宿主与用户都察觉不到原因。
            Log.e(TAG, "探针初始化失败，音纹去广告不可用", e);
            probe = null;
        }
    }

    /** 从当前 rulesPath 读取 RULES.JSON 并注入探针。文件不存在或读取失败时静默忽略。 */
    private void loadRulesFromFile() {
        if (probe == null || rulesPath == null || rulesPath.trim().isEmpty()) return;
        File file = new File(rulesPath.trim());
        if (!file.exists() || !file.isFile() || !file.canRead()) {
            Log.w(TAG, "规则文件不可用，音纹去广告无规则可匹配 path=" + rulesPath);
            return;
        }
        StringBuilder sb = new StringBuilder((int) Math.min(file.length(), 4 * 1024 * 1024));
        try (BufferedReader reader = new BufferedReader(new FileReader(file), 8192)) {
            char[] buf = new char[8192];
            int len;
            while ((len = reader.read(buf)) != -1) {
                sb.append(buf, 0, len);
            }
            String json = sb.toString().trim();
            if (!json.isEmpty()) {
                probe.replaceRulesJson(sanitizeForProbe(json));
                Log.i(TAG, "已加载规则文件 size=" + json.length() + " path=" + rulesPath);
            }
        } catch (Exception e) {
            // 规则文件读取失败不影响宿主播放，但要留下线索
            Log.e(TAG, "规则文件读取失败", e);
        }
    }

    /** 开始分析新媒体；同一 URL 不会重复开（避免 subtitle/format 切换误触发）。 */
    public void open(String url, Map<String, String> headers) {
        if (url == null) return;
        if (probe == null) {
            Log.w(TAG, "open 跳过：探针实例为空（初始化阶段曾失败）");
            return;
        }
        if (!probe.isEnabled()) {
            Log.w(TAG, "open 跳过：音纹去广告开关未启用");
            return;
        }
        if (url.equals(lastUrl)) return;
        lastUrl = url;
        lastHeaders = headers;
        try {
            ProbeMedia media = ProbeMedia.builder(url).setHeaders(filterHeaders(headers)).build();
            probe.open(media);
            Log.d(TAG, "已开启分析 " + url);
        } catch (RuntimeException | LinkageError e) {
            Log.e(TAG, "开启分析失败", e);
        }
        // 内置采集器：后台扫描 HLS 广告候选并生成指纹规则（开关开启时）
        AdRuleCollector.get().maybeCollect(url, headers, appContext);
    }

    /** 采集器生成新规则后注入探针（原子替换，fail-open）。 */
    public void applyCollectedRules(String json) {
        if (probe == null || json == null || json.trim().isEmpty()) return;
        try {
            probe.replaceRulesJson(sanitizeForProbe(json));
        } catch (RuntimeException | LinkageError e) {
            e.printStackTrace();
        }
    }

    /**
     * 探针 SDK 使用严格 rules-v1 解析器，遇到未知字段（如历史遗留的
     * {@code textRules} 数组）会整体拒绝规则。这里在注入前剥离非音纹字段，
     * 只保留探针必需的 format/schemaVersion/algorithm/revision/rules，
     * 保证音纹跳广告稳定生效。
     */
    private static String sanitizeForProbe(String json) {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject out = new JSONObject();
            if (root.has("format")) out.put("format", root.optString("format"));
            if (root.has("schemaVersion")) out.put("schemaVersion", root.optInt("schemaVersion", 1));
            if (root.has("algorithm")) out.put("algorithm", root.optString("algorithm"));
            if (root.has("revision")) out.put("revision", root.optLong("revision", 0L));
            if (root.has("rules")) out.put("rules", root.optJSONArray("rules"));
            return out.toString();
        } catch (Exception e) {
            return json;
        }
    }

    /** 宿主主动 seek 后调用，让探针把内部分析位置同步到新时间轴。 */
    public void onHostSeek(long positionMs) {
        if (probe == null) return;
        try {
            probe.notifyHostDiscontinuity(Math.max(0L, positionMs));
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /** 开关变化时调用：开启会（按需）创建探针并重开当前媒体，关闭会停用。 */
    public void setEnabled(boolean enabled, Context context) {
        if (enabled) ensureProbe(context);
        if (probe == null) return;
        try {
            probe.setEnabled(enabled);
        } catch (RuntimeException | LinkageError ignored) {
        }
        if (enabled && lastUrl != null) open(lastUrl, lastHeaders);
    }

    /**
     * 规则文件路径变化时重新加载规则（不需要重建探针实例，
     * 直接用 {@code replaceRulesJson()} 原子替换即可）。
     */
    public void setRulesPath(String path) {
        String normalized = (path == null || path.trim().isEmpty())
                ? Setting.DEFAULT_RULES_PATH : path.trim();
        if (normalized.equals(rulesPath)) return;
        rulesPath = normalized;
        Setting.putAdRulesPath(normalized);
        loadRulesFromFile();  // 异步解析并原子替换规则
    }

    /** 停止当前分析会话（保留实例与规则缓存），用于 PlayerManager 释放时。 */
    public void release() {
        if (probe == null) return;
        try {
            probe.stop();
        } catch (RuntimeException | LinkageError ignored) {
        }
        lastUrl = null;
        lastHeaders = null;
    }

    private static Map<String, String> filterHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return Collections.emptyMap();
        Map<String, String> allowed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null) continue;
            String normalized = name.toLowerCase(Locale.US);
            if (ALLOWED_HEADER_NAMES.contains(normalized)) {
                allowed.put(normalized, entry.getValue());
            }
        }
        return allowed;
    }
}

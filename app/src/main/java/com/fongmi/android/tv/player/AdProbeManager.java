package com.fongmi.android.tv.player;

import android.content.Context;
import android.util.Log;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.fongmi.adaudio.probe.AdAudioProbe;
import io.github.fongmi.adaudio.probe.adapter.media3.v1_9.ProbeAudioStats;
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

    // 自检面板用：记录探针实际运行状态。fail-open 设计下没有这些就无法诊断。
    private volatile int ruleCount;
    private volatile long lastSkipAtMs;
    private volatile long lastErrorAtMs;
    private volatile String lastErrorText;
    /** 探针尚未创建时到达的规则，暂存待注入（避免云端规则被静默丢弃）。 */
    private volatile String pendingRulesJson;

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
            lastSkipAtMs = System.currentTimeMillis();
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
            lastErrorAtMs = System.currentTimeMillis();
            lastErrorText = "code=" + error.getCode()
                    + " fatal=" + error.isFatal()
                    + " msg=" + error.getMessage();
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
            // 探针创建前到达的规则（典型场景：App 启动即异步拉取云端规则，
            // 而此时用户还没播放、probe 仍为 null）此前会被静默丢弃。
            String pending = pendingRulesJson;
            if (pending != null) {
                pendingRulesJson = null;
                applyCollectedRules(pending);
            }
            // 探针就绪后兜底：若本地仍无规则（典型为冷启动预热同步因网络未就绪失败），
            // 此刻代理/VPN 通常已就绪，主动拉一次云端规则，免去手动关开开关。
            ensureCloudRulesOnce();
            Log.i(TAG, "探针已就绪 enabled=" + Setting.isAiAdblock()
                    + " rulesPath=" + rulesPath);
        } catch (RuntimeException | LinkageError e) {
            // fail-open：探针创建失败绝不打断宿主播放，但必须留下可诊断的日志，
            // 否则音纹去广告会「静默失效」，宿主与用户都察觉不到原因。
            Log.e(TAG, "探针初始化失败，音纹去广告不可用", e);
            probe = null;
        }
    }

    /**
     * 兜底同步：探针就绪但本地还没有可用规则时，主动向云端拉取一次。
     * <p>
     * 为什么需要：{@code App.onCreate} 里的预热同步跑在进程创建极早期，此时代理/VPN 往往
     * 还没 {@code applySaved()} 就绪，HTTP 请求会静默失败；而 {@link #ensureProbe(Context)}
     * 只在 probe 首次创建时跑一次、之后 probe 常驻不再 reload，也没有重试机制——结果就是
     * 冷启动后永远没有规则，必须手动关开开关触发一次重新初始化才偶然拉到。
     * <p>
     * 这里把兜底挂在「探针真正就绪、即将分析媒体」的时机：本地无规则则立即拉取；
     * {@link AdCloudSyncManager#isSyncing()} 负责防重入，拉到规则后（ruleCount>0）自动停止重试。
     */
    private void ensureCloudRulesOnce() {
        String url = Setting.getAdCloudUrl();
        if (url == null || url.trim().isEmpty()) return;
        if (ruleCount > 0) return;                          // 本地已有规则，无需联网
        if (AdCloudSyncManager.get().isSyncing()) return;   // 已有同步在跑，防并发重复
        Log.i(TAG, "探针就绪但本地无规则(ruleCount=" + ruleCount + ")，兜底向云端同步一次");
        AdCloudSyncManager.get().syncFromCloud(null);
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
                String sanitized = sanitizeForProbe(json);
                probe.replaceRulesJson(sanitized);
                ruleCount = countRules(sanitized);
                Log.i(TAG, "已加载规则文件 size=" + json.length()
                        + " rules=" + ruleCount + " path=" + rulesPath);
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
        // 即将分析媒体：若本地仍无规则（典型为冷启动预热同步因网络未就绪失败），
        // 此刻代理/VPN 通常已就绪，兜底拉一次云端规则，免去手动关开开关。
        ensureCloudRulesOnce();
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

    /** 采集器或云端生成新规则后注入探针（原子替换，fail-open）。 */
    public void applyCollectedRules(String json) {
        if (json == null || json.trim().isEmpty()) return;
        if (probe == null) {
            // 暂存，等探针创建后补发，绝不丢弃
            pendingRulesJson = json;
            Log.i(TAG, "规则已暂存，待探针创建后注入 size=" + json.length());
            return;
        }
        try {
            String sanitized = sanitizeForProbe(json);
            probe.replaceRulesJson(sanitized);
            ruleCount = countRules(sanitized);
            Log.i(TAG, "规则已注入探针 rules=" + ruleCount);
        } catch (RuntimeException | LinkageError e) {
            Log.e(TAG, "规则注入失败", e);
        }
    }

    /**
     * 探针 SDK 使用严格 rules-v1 解析器，且运行期还会校验主指纹前缀冲突。
     * 这里在注入前做两件事：
     * 1. 剥离非音纹字段（如历史遗留的 {@code textRules}），只保留白名单字段；
     * 2. 过滤掉会导致 SDK 整体拒绝的前缀冲突规则（同一广告开头相同但结束位置不同）。
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
            if (root.has("rules")) out.put("rules", filterConflictingRules(root.optJSONArray("rules")));
            return out.toString();
        } catch (Exception e) {
            // 解析失败绝不能把可能含 textRules 或冲突规则的原始串直传给严格解析器，
            // 否则会复现 RULE_PARSE_FAILED 让整份规则被拒。降级为最小空规则集
            // （fail-open），保证探针不崩、不被整份拒绝。
            return "{\"format\":\"ad-audio-probe-rules\",\"schemaVersion\":1,"
                    + "\"algorithm\":\"spectral-sequence-v1\",\"revision\":0,\"rules\":[]}";
        }
    }

    /**
     * 按 SDK {@code AdRuleSet.validatePrimaryPrefix} 预检：如果两条规则主指纹
     * （phaseMs=0）前 8 帧相同但结束位置（durationMs - anchorOffsetMs）不同，
     * SDK 会整体拒绝整份规则。这里按顺序保留不冲突的子集，避免一两条坏规则毒死全部。
     */
    private static JSONArray filterConflictingRules(JSONArray rules) {
        if (rules == null || rules.length() == 0) return new JSONArray();
        PrefixTrie root = new PrefixTrie();
        JSONArray out = new JSONArray();
        int dropped = 0;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            if (rule == null) continue;
            String id = rule.optString("id", "rule-" + i);
            long durationMs = rule.optLong("durationMs", 0L);
            long anchorOffsetMs = rule.optLong("anchorOffsetMs", 0L);
            long endpoint = durationMs - anchorOffsetMs;
            JSONArray hashes = null;
            JSONArray variants = rule.optJSONArray("fingerprints");
            if (variants != null) {
                for (int j = 0; j < variants.length(); j++) {
                    JSONObject v = variants.optJSONObject(j);
                    if (v != null && v.optInt("phaseMs", -1) == 0) {
                        hashes = v.optJSONArray("hashes");
                        break;
                    }
                }
            }
            if (hashes == null || hashes.length() == 0) {
                out.put(rule);
                continue;
            }
            int limit = Math.min(8, hashes.length());
            PrefixTrie node = root;
            List<PrefixTrie> path = new ArrayList<>(limit);
            boolean conflict = false;
            for (int k = 0; k < limit; k++) {
                if (node.terminalEndpoint != null && node.terminalEndpoint != endpoint) {
                    conflict = true;
                    break;
                }
                String hash = hashes.optString(k);
                PrefixTrie child = node.children.get(hash);
                if (child == null) {
                    child = new PrefixTrie();
                    node.children.put(hash, child);
                }
                node = child;
                path.add(node);
            }
            if (!conflict && node.subtreeEndpoint != null
                    && (node.subtreeMixed || node.subtreeEndpoint != endpoint)) {
                conflict = true;
            }
            if (conflict) {
                dropped++;
                Log.w(TAG, "规则前缀冲突，已过滤避免整份规则被拒: " + id);
                continue;
            }
            node.terminalEndpoint = endpoint;
            node.terminalRuleId = id;
            for (PrefixTrie item : path) {
                if (item.subtreeEndpoint == null) {
                    item.subtreeEndpoint = endpoint;
                    item.subtreeRuleId = id;
                } else if (item.subtreeEndpoint != endpoint) {
                    item.subtreeMixed = true;
                    if (item.mixedRuleId == null) item.mixedRuleId = id;
                }
            }
            out.put(rule);
        }
        if (dropped > 0) {
            Log.i(TAG, "规则预过滤完成：原始 " + rules.length() + " 条，保留 "
                    + out.length() + " 条，过滤冲突 " + dropped + " 条");
        }
        return out;
    }

    private static final class PrefixTrie {
        final Map<String, PrefixTrie> children = new HashMap<>();
        Long terminalEndpoint;
        String terminalRuleId;
        Long subtreeEndpoint;
        String subtreeRuleId;
        String mixedRuleId;
        boolean subtreeMixed;
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
        if (enabled) {
            ensureProbe(context);
            // 用户主动开启开关时，若本地仍无规则则立即兜底同步，不必等下次播放
            ensureCloudRulesOnce();
        }
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

    /** 统计规则 JSON 里的规则条数，用于自检面板（解析失败返回 -1）。 */
    private static int countRules(String json) {
        try {
            JSONArray rules = new JSONObject(json).optJSONArray("rules");
            return rules == null ? 0 : rules.length();
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * 生成音纹去广告自检报告，供设置页展示。
     *
     * <p>探针是 fail-open 设计，出错静默。没有这份报告，用户只能看到「没反应」，
     * 既不知道是开关没开、规则为空，还是探针本身挂了。</p>
     */
    public String getDiagnosticReport() {
        StringBuilder sb = new StringBuilder();
        boolean on = Setting.isAiAdblock();
        sb.append("① 开关：").append(on ? "已开启" : "未开启（默认关闭，需手动打开）").append("\n\n");

        sb.append("② 探针实例：").append(probe == null ? "未创建（初始化曾失败）" : "已就绪").append("\n");
        if (probe != null) {
            boolean enabled = false;
            try {
                enabled = probe.isEnabled();
            } catch (RuntimeException | LinkageError ignored) {
            }
            sb.append("   运行状态：").append(enabled ? "运行中" : "已停用").append("\n");
        }
        sb.append("\n");

        sb.append("③ 规则库：").append(ruleCount).append(" 条\n");
        String path = rulesPath == null || rulesPath.trim().isEmpty()
                ? Setting.DEFAULT_RULES_PATH : rulesPath.trim();
        sb.append("   路径：").append(path).append("\n");
        File file = new File(path);
        if (!file.exists()) {
            sb.append("   状态：文件不存在\n");
        } else if (!file.isFile()) {
            sb.append("   状态：不是文件\n");
        } else if (!file.canRead()) {
            sb.append("   状态：存在但无法读取（存储权限不足？）\n");
        } else {
            sb.append("   状态：可读，").append(file.length()).append(" 字节\n");
        }
        sb.append("\n");

        sb.append("④ 当前分析：").append(lastUrl == null ? "尚未播放" : "已接入\n   " + lastUrl).append("\n\n");

        sb.append("④b 云端同步：").append(AdCloudSyncManager.get().getStatusText()).append("\n");
        String cloudUrl = Setting.getAdCloudUrl();
        sb.append("    地址：").append(cloudUrl == null || cloudUrl.trim().isEmpty()
                ? "未配置" : cloudUrl.trim()).append("\n\n");

        sb.append("④c 自动采集：").append(Setting.isAutoCollect() ? "已开启" : "未开启").append("\n");
        sb.append("    ").append(AdRuleCollector.get().getStatusText()).append("\n\n");

        long now = System.currentTimeMillis();
        if (lastErrorAtMs > 0L) {
            sb.append("⑤ 最近错误：").append((now - lastErrorAtMs) / 1000L).append(" 秒前\n   ")
                    .append(lastErrorText).append("\n\n");
        } else {
            sb.append("⑤ 最近错误：无\n\n");
        }

        if (lastSkipAtMs > 0L) {
            sb.append("⑥ 最近命中：").append((now - lastSkipAtMs) / 1000L).append(" 秒前");
        } else {
            sb.append("⑥ 最近命中：从未命中过");
        }

        sb.append("\n\n⑦ 音频链路：\n   ").append(ProbeAudioStats.summary().replace("\n", "\n   "));

        sb.append("\n\n—— 结论 ——\n")
                .append(buildVerdict(on, probe != null, file, ruleCount, Setting.isAutoCollect()));
        return sb.toString();
    }

    /** 根据自检结果给出下一步该做什么，避免用户看到一堆状态却不知道怎么修。 */
    private String buildVerdict(boolean on, boolean ready, File file, int rules, boolean autoCollect) {
        if (!on) {
            return "开关没开。请打开本页的「音频去广」开关——它默认是关闭的。";
        }
        if (!ready) {
            return "探针没起来。通常是初始化阶段抛异常导致，需要看 adb logcat -s AdProbe:V 的堆栈。";
        }
        if (!file.exists() || rules <= 0) {
            if (!autoCollect) {
                return "规则库是空的，而且自动采集也没开。\n"
                        + "App 不内置广告指纹，得先有规则才能跳过。请打开本页的「自动采集」，"
                        + "然后播一个 m3u8 源——采集成功后，下次播放才会跳过。";
            }
            return "规则库还是空的。\n"
                    + "采集只对 HLS/m3u8 源有效（MP4 不行），且广告片段要长于 5 秒。"
                    + "请播一个 m3u8 源，看上面④b 的采集状态；"
                    + "注意第一次播放只是采集，第二次才会跳过。";
        }
        if (lastErrorAtMs > 0L && lastSkipAtMs == 0L) {
            return "规则和探针都在，但分析过程报错了。把上面⑤的错误码发我，"
                    + "注意直播流不支持（仅支持有限时长的普通点播）。";
        }
        if (lastSkipAtMs == 0L) {
            return "一切正常，只是还没命中过。可能这个源的广告指纹没采集过，"
                    + "或者广告在开头就已经播完。换个已知有广告的源再试。";
        }
        return "工作正常，最近已经命中过广告。";
    }

    /** 自检面板用：让探针重新加载一次规则文件（用户手动改文件后可用）。 */
    public int reloadRulesForCheck() {
        loadRulesFromFile();
        return ruleCount;
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

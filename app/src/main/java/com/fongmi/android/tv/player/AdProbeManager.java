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
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

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

    // 与 probe SDK AdRuleSet（probe-core）的校验常量保持一致。
    // SDK 对任一规则校验失败都会整体拒绝整份规则，预过滤必须用同一套阈值。
    private static final int SDK_WINDOW_MS = 512;
    private static final int SDK_HOP_MS = 256;
    private static final int SDK_MIN_CONFIRMATION_FRAMES = 4;
    private static final int SDK_MAX_CONFIRMATION_FRAMES = 8;
    private static final int SDK_MAX_TOTAL_HASHES = 65536;
    private static final int SDK_MAX_RULES = 1024;
    private static final int SDK_MIN_DURATION_MS = 1000;
    private static final int SDK_MAX_DURATION_MS = 10 * 60 * 1000;
    private static final int SDK_MIN_ANCHOR_DURATION_MS = 2000;
    private static final int SDK_MAX_ANCHOR_DURATION_MS = 5000;
    private static final int SDK_MAX_ID_LENGTH = 64;
    private static final Pattern SDK_ID_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,63}$");
    private static final Pattern SDK_HASH_PATTERN = Pattern.compile("^[0-9a-f]{8}$");
    private static final Set<Integer> SDK_REQUIRED_PHASES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(0, 64, 128, 192)));

    /** SDK 适配器仅放行不会向重定向目标泄露凭据的安全请求头白名单。 */
    private static final Set<String> ALLOWED_HEADER_NAMES = Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "user-agent", "accept", "accept-language", "cache-control", "pragma")));

    /** 同一跳转目标的重复命中判定窗口；短于此间隔的重复视为无法收敛。 */
    private static final long PROBE_SKIP_COOLDOWN_MS = 1500L;
    /** 同一跳转目标最多执行次数，其余直接丢弃。 */
    private static final int MAX_PROBE_SKIP_TRIES = 2;

    private static volatile AdProbeManager instance;

    private AdAudioProbe probe;
    private Player player;
    private Context appContext;
    private String lastUrl;
    private Map<String, String> lastHeaders;
    private String rulesPath;

    /** 当前正在分析的视频地址；HLS 删分片时用它在记忆库中查询已知广告区间。 */
    public String getLastUrl() {
        return lastUrl;
    }

    // 自检面板用：记录探针实际运行状态。fail-open 设计下没有这些就无法诊断。
    private volatile int ruleCount;
    private volatile long lastSkipAtMs;
    private volatile long lastErrorAtMs;
    private volatile String lastErrorText;
    /** 探针尚未创建时到达的规则，暂存待注入（避免规则被暂存丢弃）。 */
    private volatile String pendingRulesJson;
    /** 上一次探针跳转的目标与时刻、重复次数，用于识别不可收敛的反复跳转。 */
    private volatile long lastProbeSkipTargetMs = -1L;
    private volatile long lastProbeSkipAtMs;
    private volatile int probeSkipTries;

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
            if (p == null || request == null) return;
            // 恢复 5.9.0 行为：声纹命中广告即直接 seek 跳过，不做 HLS/删分片护栏
            // （删 m3u8 分片的方案已移除，详见 5.9.0 的 AdAudioProbe 直接跳过机制）。
            int mode = Setting.getAdSkipMode();
            long target = request.getSeekTargetPositionMs();
            // 0=仅提示，1=提示+自动跳过，2=仅自动跳过
            if (mode == Setting.AD_SKIP_MODE_NOTICE) {
                Notify.show(ResUtil.getString(R.string.ad_skipped));
                return;
            }
            if (target < 0L) return;
            long now = System.currentTimeMillis();
            // 兜底护栏：SDK 的冷却只针对同一条规则，遇到「同一目标反复命中」的源
            // （seek 落点仍在区间内、或区间互相重叠）时宿主仍会被连续要求跳转。
            // 这里对「同一目标 + 短时间内重复」直接限次：宁可残留几秒广告，
            // 也不能让播放位置陷入不可收敛的来回跳。
            if (target == lastProbeSkipTargetMs) {
                // 同一目标达到上限即锁定：seek 始终停在区间内、或区间互相重叠的源，
                // 跳过去还会被再次命中。冷却重置反而给它无限次机会，因此一旦确认
                // 无法收敛，就在本次媒体会话内彻底放弃该目标。
                if (probeSkipTries >= MAX_PROBE_SKIP_TRIES) {
                    Log.w(TAG, "跳转目标无法收敛，本次会话内不再跳转 target=" + target + "ms");
                    return;
                }
                if (now - lastProbeSkipAtMs < PROBE_SKIP_COOLDOWN_MS) return;
                probeSkipTries++;
            } else {
                lastProbeSkipTargetMs = target;
                probeSkipTries = 0;
            }
            lastProbeSkipAtMs = now;
            lastSkipAtMs = now;
            Log.i(TAG, "命中广告，请求跳转 " + target + "ms（当前 " + p.getCurrentPosition()
                    + "ms，模式 " + mode + "）");
            // 只在真正执行跳转时记忆区间，避免误判污染记忆库：
            // 下次播放同一视频时开播即知广告位置，HLS 可直接删掉对应分片。
            String url = lastUrl != null ? lastUrl : AdSegmentMemory.getCurrentUrl();
            AdSegmentMemory.record(url, request.getAdStartPositionMs(),
                    request.getAdEndPositionMs());
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
                    .setConfirmEarly(Setting.isAdEarlyConfirm())
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
            Log.i(TAG, "探针已就绪 enabled=" + Setting.isAiAdblock()
                    + " rulesPath=" + rulesPath);
        } catch (RuntimeException | LinkageError e) {
            // fail-open：探针创建失败绝不打断宿主播放，但必须留下可诊断的日志，
            // 否则音纹去广告会「静默失效」，宿主与用户都察觉不到原因。
            Log.e(TAG, "探针初始化失败，音纹去广告不可用", e);
            probe = null;
        }
    }

    /** 从候选路径依次读取 RULES.JSON 并注入探针；找到第一个可读文件即停。 */
    private void loadRulesFromFile() {
        if (probe == null) return;
        for (String path : Setting.getRulesPathCandidates()) {
            File file = new File(path);
            if (!file.exists() || !file.isFile() || !file.canRead()) continue;
            if (loadRulesFrom(file)) return;
        }
        Log.w(TAG, "规则文件不可用，音纹去广告无规则可匹配（已尝试外部与私有目录）");
    }

    /** 读取单个规则文件并注入探针。成功返回 true。 */
    private boolean loadRulesFrom(File file) {
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
                rulesPath = file.getAbsolutePath();
                Log.i(TAG, "已加载规则文件 size=" + json.length()
                        + " rules=" + ruleCount + " path=" + rulesPath);
                return true;
            }
        } catch (Exception e) {
            // 规则文件读取失败不影响宿主播放，但要留下线索
            Log.e(TAG, "规则文件读取失败 path=" + file.getAbsolutePath(), e);
        }
        return false;
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
        // 换了媒体就清空跳转限流状态，否则上一个视频用掉的配额会传染给新起播。
        resetSkipGuard();
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
            if (root.has("rules")) out.put("rules", filterInvalidRules(root.optJSONArray("rules")));
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
     * 按 SDK {@code AdRuleSet} 的全部校验逐条预检，剔除不合规规则。
     * SDK 对任何一条规则校验失败都会整体拒绝整份规则，一条坏规则就能毒死全部，
     * 因此注入前必须用与 SDK 完全相同的阈值逐条把关。校验项与 SDK 一一对应：
     * <ol>
     *   <li>id 缺失或重复；</li>
     *   <li>相位偏移无效或重复（{@code phaseMs < 0} / {@code >= 256}）；</li>
     *   <li>指纹帧数与锚点时长不一致；</li>
     *   <li>指纹开头区分度不足（前 8 帧与首帧汉明距离均 ≤ 5）；</li>
     *   <li>规则指纹总量超过上限（65536）；</li>
     *   <li>缺少零偏移主指纹；</li>
     *   <li>主指纹前缀冲突（前 8 帧相同但结束位置不同）。</li>
     * </ol>
     */
    private static JSONArray filterInvalidRules(JSONArray rules) throws JSONException {
        if (rules == null || rules.length() == 0) return new JSONArray();
        PrefixTrie root = new PrefixTrie();
        JSONArray out = new JSONArray();
        Set<String> ids = new HashSet<>();
        int dropped = 0;
        for (int i = 0; i < rules.length(); i++) {
            JSONObject rule = rules.optJSONObject(i);
            String reason = validateRuleStructure(rule, ids);
            if (reason == null) reason = validatePrimaryPrefix(rule, root);
            if (reason != null) {
                dropped++;
                Log.w(TAG, "规则未通过 SDK 预检，已剔除避免整份被拒: "
                        + (rule == null ? "null" : rule.optString("id", "?"))
                        + "（" + reason + "）");
                continue;
            }
            if (out.length() >= SDK_MAX_RULES) {
                Log.w(TAG, "规则数量达到 SDK 上限，其余已截断");
                break;
            }
            // 重建为 SDK 只会接受的规范化形态：字段类型收敛为整数、
            // 剥离 test 等非注入必需字段，杜绝格式漂移触发严格解析器。
            JSONObject normalized = normalizeRule(rule);
            if (normalized == null) {
                Log.w(TAG, "规则规范化失败，已剔除: " + rule.optString("id", "?"));
                continue;
            }
            out.put(normalized);
        }
        if (dropped > 0) {
            Log.i(TAG, "规则预过滤完成：原始 " + rules.length() + " 条，保留 "
                    + out.length() + " 条，剔除 " + dropped + " 条");
        }
        return out;
    }

    /**
     * 按探针 rules-v1 只读字段的白名单重建单条规则：数字统一转成 long/int，
     * 字符串原样，{@code test} 元数据（仅供规则工具使用、不进入运行时匹配）
     * 直接剥离。这样无论云端 JSON 里数字写成字符串、浮点还是科学计数，
     * 输出都是严格解析器必然接受的普通十进制整数。
     */
    private static JSONObject normalizeRule(JSONObject rule) throws JSONException {
        try {
            JSONObject out = new JSONObject();
            out.put("id", rule.optString("id"));
            out.put("durationMs", rule.optLong("durationMs", 0L));
            out.put("anchorOffsetMs", rule.optLong("anchorOffsetMs", 0L));
            out.put("anchorDurationMs", rule.optLong("anchorDurationMs", 0L));
            JSONArray fps = new JSONArray();
            JSONArray variants = rule.optJSONArray("fingerprints");
            for (int j = 0; variants != null && j < variants.length(); j++) {
                JSONObject v = variants.optJSONObject(j);
                if (v == null) continue;
                JSONObject nv = new JSONObject();
                nv.put("phaseMs", v.optInt("phaseMs", -1));
                JSONArray srcHashes = v.optJSONArray("hashes");
                JSONArray hashes = new JSONArray();
                for (int k = 0; srcHashes != null && k < srcHashes.length(); k++) {
                    hashes.put(srcHashes.optString(k));
                }
                nv.put("hashes", hashes);
                fps.put(nv);
            }
            out.put("fingerprints", fps);
            return out;
        } catch (JSONException e) {
            // 结构校验已通过，理论上不会走到这里；兜底让调用方剔除该条。
            return null;
        }
    }

    /** 对齐 SDK AdRule 构造器 + RuleSetJsonParser.readRule + AdRuleSet.validate 的结构校验；返回 null 表示通过，否则返回拒绝原因。 */
    private static String validateRuleStructure(JSONObject rule, Set<String> ids) {
        if (rule == null) return "规则不是 JSON 对象";
        String id = rule.optString("id", "");
        if (id.isEmpty()) return "缺少 id";
        if (id.length() > SDK_MAX_ID_LENGTH || !SDK_ID_PATTERN.matcher(id).matches()) {
            return "id 无效（须匹配 [a-z0-9][a-z0-9._-]{0,63}）";
        }
        if (!ids.add(id)) return "id 重复";
        long durationMs = rule.optLong("durationMs", 0L);
        if (durationMs < SDK_MIN_DURATION_MS || durationMs > SDK_MAX_DURATION_MS) {
            return "广告时长超出允许范围";
        }
        long anchorOffsetMs = rule.optLong("anchorOffsetMs", 0L);
        long anchorDurationMs = rule.optLong("anchorDurationMs", 0L);
        if (anchorDurationMs < SDK_MIN_ANCHOR_DURATION_MS
                || anchorDurationMs > SDK_MAX_ANCHOR_DURATION_MS
                || anchorOffsetMs < 0 || anchorOffsetMs > durationMs - anchorDurationMs) {
            return "广告锚点范围无效";
        }
        JSONArray variants = rule.optJSONArray("fingerprints");
        if (variants == null || variants.length() == 0) return "缺少 fingerprints";
        long totalHashes = 0L;
        boolean hasPrimary = false;
        Set<Integer> offsets = new HashSet<>();
        for (int j = 0; j < variants.length(); j++) {
            JSONObject v = variants.optJSONObject(j);
            if (v == null) return "指纹变体不是对象";
            int phaseMs = v.optInt("phaseMs", -1);
            if (phaseMs < 0 || phaseMs >= SDK_HOP_MS || !offsets.add(phaseMs)) {
                return "相位偏移无效或重复";
            }
            if (phaseMs == 0) hasPrimary = true;
            JSONArray hashes = v.optJSONArray("hashes");
            if (hashes == null) return "缺少 hashes";
            if (hashes.length() < 4 || hashes.length() > 64) return "指纹序列长度无效";
            for (int k = 0; k < hashes.length(); k++) {
                String hash = hashes.optString(k);
                if (!SDK_HASH_PATTERN.matcher(hash).matches()) {
                    return "频谱哈希格式无效: " + hash;
                }
            }
            if (hashes.length() != expectedFrames(anchorDurationMs, phaseMs)) {
                return "指纹帧数与锚点时长不一致";
            }
            if (requiredConfirmationFrames(hashes) < 0) return "指纹开头区分度不足";
            totalHashes += hashes.length();
            if (totalHashes > SDK_MAX_TOTAL_HASHES) return "指纹总量超过上限";
        }
        if (!hasPrimary) return "缺少零偏移主指纹";
        if (!offsets.equals(SDK_REQUIRED_PHASES)) return "必须包含 0/64/128/192 四个固定相位";
        return null;
    }

    /** 对齐 SDK validatePrimaryPrefix 的前缀树校验；返回 null 表示通过，否则返回拒绝原因。 */
    private static String validatePrimaryPrefix(JSONObject rule, PrefixTrie root) {
        JSONArray variants = rule.optJSONArray("fingerprints");
        JSONArray hashes = null;
        for (int j = 0; j < variants.length(); j++) {
            JSONObject v = variants.optJSONObject(j);
            if (v != null && v.optInt("phaseMs", -1) == 0) {
                hashes = v.optJSONArray("hashes");
                break;
            }
        }
        String id = rule.optString("id", "?");
        long endpoint = rule.optLong("durationMs", 0L) - rule.optLong("anchorOffsetMs", 0L);
        int limit = Math.min(SDK_MAX_CONFIRMATION_FRAMES, hashes.length());
        PrefixTrie node = root;
        List<PrefixTrie> path = new ArrayList<>(limit);
        for (int k = 0; k < limit; k++) {
            String hash = hashes.optString(k);
            if (node.terminalEndpoint != null && node.terminalEndpoint != endpoint) {
                return "前缀冲突（相同开头不同结束）: " + node.terminalRuleId;
            }
            PrefixTrie child = node.children.get(hash);
            if (child == null) {
                child = new PrefixTrie();
                node.children.put(hash, child);
            }
            node = child;
            path.add(node);
        }
        if (node.subtreeEndpoint != null
                && (node.subtreeMixed || node.subtreeEndpoint != endpoint)) {
            return "前缀冲突（子树终点不一致）: "
                    + (node.subtreeEndpoint != endpoint ? node.subtreeRuleId : node.mixedRuleId);
        }
        node.terminalEndpoint = endpoint;
        node.terminalRuleId = id;
        for (PrefixTrie item : path) item.record(endpoint, id);
        return null;
    }

    /** 对齐 SDK AdRuleSet.expectedFrames。 */
    private static int expectedFrames(long anchorDurationMs, int phaseMs) {
        long available = anchorDurationMs - phaseMs - SDK_WINDOW_MS;
        return available < 0 ? 0 : (int) (available / SDK_HOP_MS) + 1;
    }

    /**
     * 对齐 SDK AdRuleSet.requiredConfirmationFrames：在最多 8 帧内寻找与首帧
     * 汉明距离 > 5 的帧，返回确认所需帧数；找不到（区分度不足）或 hash 非法返回 -1。
     */
    private static int requiredConfirmationFrames(JSONArray hashes) {
        int limit = Math.min(SDK_MAX_CONFIRMATION_FRAMES, hashes.length());
        try {
            int first = (int) Long.parseUnsignedLong(hashes.optString(0), 16);
            for (int i = 1; i < limit; i++) {
                int current = (int) Long.parseUnsignedLong(hashes.optString(i), 16);
                if (Integer.bitCount(first ^ current) > 5) {
                    return Math.max(SDK_MIN_CONFIRMATION_FRAMES, i + 1);
                }
            }
        } catch (NumberFormatException e) {
            return -1;
        }
        return -1;
    }

    private static final class PrefixTrie {
        final Map<String, PrefixTrie> children = new HashMap<>();
        Long terminalEndpoint;
        String terminalRuleId;
        Long subtreeEndpoint;
        String subtreeRuleId;
        String mixedRuleId;
        boolean subtreeMixed;

        /** 对齐 SDK PrefixNode.record：沿路径记录子树终点，出现多终点时标记 mixed。 */
        void record(long endpoint, String ruleId) {
            if (subtreeEndpoint == null) {
                subtreeEndpoint = endpoint;
                subtreeRuleId = ruleId;
            } else if (subtreeEndpoint != endpoint) {
                subtreeMixed = true;
                if (mixedRuleId == null) mixedRuleId = ruleId;
            }
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
        if (enabled) {
            ensureProbe(context);
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
                ? Setting.getDefaultRulesPath() : path.trim();
        if (normalized.equals(rulesPath)) return;
        rulesPath = normalized;
        Setting.putAdRulesPath(normalized);
        loadRulesFromFile();  // 异步解析并原子替换规则
    }

    /** 停止当前分析会话（保留实例与规则缓存），用于 PlayerManager 释放时。 */
    public void release() {
        resetSkipGuard();
        if (probe == null) return;
        try {
            probe.stop();
        } catch (RuntimeException | LinkageError ignored) {
        }
        lastUrl = null;
        lastHeaders = null;
    }

    /** 清空「反复跳转」限流状态；换媒体或释放时调用。 */
    private void resetSkipGuard() {
        lastProbeSkipTargetMs = -1L;
        lastProbeSkipAtMs = 0L;
        probeSkipTries = 0;
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
        sb.append("① 开关：").append(on ? "已开启（默认开启）" : "未开启").append("\n\n");

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
                ? Setting.getAdRulesPath() : rulesPath.trim();
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

        sb.append("④b 规则库地址：").append(Setting.getRuleLibraryUrl()).append("\n");
        sb.append("    拉取：仅下载、不上传（本地采集规则只留本地）\n");

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
            return "开关没开。请打开本页的「音频去广」开关——它默认是开启的，若被关掉了请重新打开。";
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

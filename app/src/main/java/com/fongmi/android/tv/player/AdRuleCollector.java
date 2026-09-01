package com.fongmi.android.tv.player;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.tools.AudioFingerprintCollector;
import io.github.fongmi.adaudio.probe.tools.FingerprintCaptureListener;
import io.github.fongmi.adaudio.probe.tools.FingerprintCaptureProgress;
import io.github.fongmi.adaudio.probe.tools.FingerprintCaptureRequest;
import io.github.fongmi.adaudio.probe.tools.FingerprintRuleDraft;
import io.github.fongmi.adaudio.probe.tools.HlsAdCandidate;
import io.github.fongmi.adaudio.probe.tools.HlsCandidateOccurrence;
import io.github.fongmi.adaudio.probe.tools.HlsCandidateScanner;
import io.github.fongmi.adaudio.probe.tools.HlsScanListener;
import io.github.fongmi.adaudio.probe.tools.HlsScanResult;
import io.github.fongmi.adaudio.probe.tools.ProbeToolError;

/**
 * 采集器内置桥接：播放新媒体时在后台扫描 HLS 广告候选，
 * 对首个候选广告区间采集音频指纹并合并进本地 RULES.JSON，
 * 再注入探针，之后同一广告即可被自动跳过。
 *
 * <p>串行执行：同一时间只处理一个媒体；新链接到来会取消旧任务。
 * 全程 fail-open，任何失败只记录通知，不影响播放。</p>
 */
public final class AdRuleCollector {

    private static final Set<String> ALLOWED_HEADER_NAMES = Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "user-agent", "accept", "accept-language", "cache-control", "pragma")));

    /**
     * 单次扫描最多采集的广告候选数量。
     * 长视频可能识别出大量候选，逐个采集会拖慢流程并占用带宽，故只取优先级最高的一批。
     */
    private static final int MAX_CAPTURE_PER_SCAN = 6;

    /** 指纹锚点固定 5 秒，是 FingerprintCaptureRequest 的硬性要求。 */
    private static final long ANCHOR_MS = FingerprintCaptureRequest.REQUIRED_ANCHOR_DURATION_MS;
    /** 可采集的广告区间上限，与 FingerprintCaptureRequest 的校验保持一致。 */
    private static final long MAX_CAPTURE_RANGE_MS = 600_000L;

    private static volatile AdRuleCollector instance;

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private HandlerThread workThread;
    private Handler workHandler;
    private HlsCandidateScanner scanner;
    private AudioFingerprintCollector collector;
    private Context appContext;
    private volatile String currentUrl;

    // 手动标记状态：起点为负数表示当前没有进行中的标记
    private volatile long manualStartMs = -1L;
    private volatile String manualUrl;
    private volatile Map<String, String> manualHeaders;

    // 采集状态（供自检面板展示）。采集也是 fail-open，没有记录就无法判断
    // 到底是「没扫到广告」还是「采集失败」。
    private volatile String lastStatus = "尚未采集过";
    private volatile long lastStatusAtMs;

    public static AdRuleCollector get() {
        if (instance == null) {
            synchronized (AdRuleCollector.class) {
                if (instance == null) instance = new AdRuleCollector();
            }
        }
        return instance;
    }

    private AdRuleCollector() {
    }

    /**
     * 播放界面「标记广告」按钮入口：第一次调用记录广告起点，第二次记录终点并采集这段区间的音频指纹。
     *
     * <p>生成规则的路径与自动采集完全一致（写本地 RULES.JSON → 注入探针 → 上传云端），
     * 因此其他用户同步云端规则后，播放同一源时也能靠音频指纹匹配自动跳过。</p>
     *
     * @param positionMs 当前播放位置
     * @param url 当前播放链接
     * @param headers 当前播放请求头
     */
    public void toggleMarkAd(long positionMs, String url, Map<String, String> headers) {
        if (!Setting.isAiAdblock()) {
            Notify.show(R.string.ad_mark_disabled);
            return;
        }
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            Notify.show(R.string.ad_mark_no_url);
            return;
        }
        // 换了视频：丢弃上一个视频残留的标记
        if (manualStartMs >= 0 && !url.equals(manualUrl)) manualStartMs = -1L;

        if (manualStartMs < 0) {
            manualStartMs = positionMs;
            manualUrl = url;
            manualHeaders = headers;
            Notify.show(ResUtil.getString(R.string.ad_mark_start, formatTime(positionMs)));
            return;
        }

        long startMs = Math.min(manualStartMs, positionMs);
        long endMs = Math.max(manualStartMs, positionMs);
        long durationMs = endMs - startMs;
        manualStartMs = -1L;
        String targetUrl = manualUrl != null ? manualUrl : url;
        Map<String, String> targetHeaders = manualHeaders != null ? manualHeaders : headers;
        manualUrl = null;
        manualHeaders = null;

        if (durationMs < ANCHOR_MS) {
            Notify.show(ResUtil.getString(R.string.ad_mark_too_short, formatTime(durationMs)));
            return;
        }
        if (durationMs > MAX_CAPTURE_RANGE_MS) {
            Notify.show(R.string.ad_mark_too_long);
            return;
        }
        if (!busy.compareAndSet(false, true)) {
            Notify.show(R.string.ad_mark_busy);
            return;
        }
        Notify.show(ResUtil.getString(R.string.ad_mark_capturing,
                formatTime(startMs), formatTime(endMs)));
        post(new Runnable() {
            @Override public void run() { captureManual(targetUrl, targetHeaders, startMs, endMs); }
        });
    }

    /** 取消进行中的手动标记（切换视频或退出播放时调用）。 */
    public void clearMark() {
        manualStartMs = -1L;
        manualUrl = null;
        manualHeaders = null;
    }

    /** 是否存在进行中的手动标记，供 UI 切换按钮外观。 */
    public boolean hasMark() {
        return manualStartMs >= 0L;
    }

    /** 采集用户手动标记区间的音频指纹。 */
    private void captureManual(String url, Map<String, String> headers, long startMs, long endMs) {
        final String ruleId = manualRuleId(url, startMs, endMs);
        try {
            if (appContext == null) {
                finishBusy();
                return;
            }
            if (collector == null) {
                collector = new AudioFingerprintCollector.Builder(appContext)
                        .setTimeoutMs(45_000L)
                        .build();
            }
            ProbeMedia media = ProbeMedia.builder(url).setHeaders(filterHeaders(headers)).build();
            // 锚点取区间正中间：用户点按钮时通常带几秒误差（起点偏早、终点偏晚），
            // 默认情况下锚点是从起点开始的 5 秒，那样很可能采到相邻的正片内容。
            long anchorOffset = Math.max(0L, (endMs - startMs - ANCHOR_MS) / 2L);
            FingerprintCaptureRequest request = FingerprintCaptureRequest
                    .builder(media, ruleId, startMs, endMs)
                    .setAnchor(anchorOffset, ANCHOR_MS)
                    .build();
            setStatus("正在手动采集 " + formatTime(startMs) + " - " + formatTime(endMs));
            collector.capture(request, new FingerprintCaptureListener() {
                @Override public void onProgress(FingerprintCaptureProgress progress) {
                    // 进度无需打扰用户
                }

                @Override public void onCompleted(long sessionId, FingerprintRuleDraft draft) {
                    if (draft != null) {
                        List<FingerprintRuleDraft> drafts = new ArrayList<FingerprintRuleDraft>();
                        drafts.add(draft);
                        // 写入本地并上传云端，其他用户同步后即可共享这条规则
                        mergeAndApplyAll(drafts);
                    } else {
                        setStatus("手动采集未产出规则");
                    }
                    finishBusy();
                }

                @Override public void onCancelled(long sessionId) {
                    setStatus("手动采集已取消");
                    finishBusy();
                }

                @Override public void onError(ProbeToolError error) {
                    setStatus("手动采集失败："
                            + (error == null ? "未知" : error.getMessage()));
                    finishBusy();
                }
            });
        } catch (RuntimeException | LinkageError e) {
            e.printStackTrace();
            setStatus("手动采集异常：" + e.getClass().getSimpleName());
            finishBusy();
        }
    }

    /**
     * 手动标记的规则 ID，需匹配 [a-z0-9][a-z0-9._-]{0,63}。
     * 由链接与区间（秒级）派生，同一段广告重复标记时 ID 一致，便于去重。
     */
    private static String manualRuleId(String url, long startMs, long endMs) {
        String seed = url + "|" + (startMs / 1000L) + "|" + (endMs / 1000L);
        String id = "manual-" + Integer.toHexString(seed.hashCode());
        return id.length() > 64 ? id.substring(0, 64) : id;
    }

    /** 把毫秒格式化为 m:ss，供提示文案使用。 */
    private static String formatTime(long ms) {
        long total = Math.max(0L, ms) / 1000L;
        return (total / 60L) + ":" + String.format(Locale.US, "%02d", total % 60L);
    }

    /**
     * 新媒体打开时调用：开关未开、探针未启用或 URL 非法时直接忽略。
     * 同一 URL 不会重复采集。
     */
    public void maybeCollect(String url, Map<String, String> headers, Context context) {
        if (url == null || !Setting.isAutoCollect() || !Setting.isAiAdblock()) return;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return;
        if (url.equals(currentUrl)) return;
        currentUrl = url;
        if (appContext == null && context != null) {
            appContext = context.getApplicationContext();
        }
        if (appContext == null) return;
        post(new Runnable() {
            @Override public void run() { startScan(url, headers); }
        });
    }

    private void startScan(final String url, final Map<String, String> headers) {
        if (!busy.compareAndSet(false, true)) return;
        setStatus("正在扫描广告候选…");
        try {
            if (scanner == null) {
                scanner = new HlsCandidateScanner.Builder()
                        .setTimeoutMs(20_000L)
                        .build();
            }
            ProbeMedia media = ProbeMedia.builder(url)
                    .setHeaders(filterHeaders(headers))
                    .build();
            scanner.scan(media, new HlsScanListener() {
                @Override public void onCompleted(HlsScanResult result) {
                    handleScanResult(result);
                }

                @Override public void onCancelled(long sessionId) {
                    finishBusy();
                }

                @Override public void onError(ProbeToolError error) {
                    setStatus("扫描失败：" + (error == null ? "未知" : error.getMessage()));
                    finishBusy();
                }
            });
        } catch (RuntimeException | LinkageError e) {
            e.printStackTrace();
            setStatus("扫描异常：" + e.getClass().getSimpleName());
            finishBusy();
        }
    }

    private void handleScanResult(HlsScanResult result) {
        if (result == null || result.getCandidates().isEmpty()) {
            // 给出可诊断的提示：源里有很多分片边界却仍无候选，说明这些段落缺少可识别的
            // 结构特征（源组/加密变化、重复出现等），达不到置信度门槛，只能依赖云端规则。
            int disc = result == null ? 0 : result.getDiscontinuityCount();
            setStatus(disc > 0
                    ? "未发现广告候选（" + disc + " 个分片边界但结构特征不足，无法自动采集）"
                    : "未发现广告候选（仅支持 HLS/m3u8；MP4 源无法自动采集）");
            finishBusy();
            return;
        }
        // 收集全部合格候选。原实现取到第一个就 break，导致同一视频里靠后的中插广告
        // 永远采集不到——这是「有些广告采集不到」的直接原因。
        List<HlsAdCandidate> qualified = new ArrayList<>();
        for (HlsAdCandidate item : result.getCandidates()) {
            if (item == null) continue;
            // 指纹采集要求广告区间至少 5 秒
            if (item.getDurationMs() < FingerprintCaptureRequest.REQUIRED_ANCHOR_DURATION_MS) continue;
            if (item.getOccurrences() == null || item.getOccurrences().isEmpty()) continue;
            qualified.add(item);
        }
        if (qualified.isEmpty()) {
            setStatus("发现候选但都短于 5 秒，无法采集指纹");
            finishBusy();
            return;
        }
        // 优先采集置信度高、重复出现次数多的候选
        Collections.sort(qualified, new Comparator<HlsAdCandidate>() {
            @Override public int compare(HlsAdCandidate left, HlsAdCandidate right) {
                int byConfidence = Integer.compare(right.getConfidence(), left.getConfidence());
                if (byConfidence != 0) return byConfidence;
                return Integer.compare(right.getOccurrences().size(),
                        left.getOccurrences().size());
            }
        });
        if (qualified.size() > MAX_CAPTURE_PER_SCAN) {
            qualified = new ArrayList<HlsAdCandidate>(
                    qualified.subList(0, MAX_CAPTURE_PER_SCAN));
        }
        final String mediaUrl = result.getMediaPlaylistUrl();
        captureNext(qualified, 0, mediaUrl, new ArrayList<FingerprintRuleDraft>());
    }

    /**
     * 串行采集候选队列：单个候选失败不影响其余候选，全部结束后一次性合并落盘，
     * 避免多次写文件与重复上传云端。
     */
    private void captureNext(final List<HlsAdCandidate> queue, final int index,
                             final String mediaUrl, final List<FingerprintRuleDraft> drafts) {
        if (index >= queue.size()) {
            if (drafts.isEmpty()) {
                setStatus("未采集到可用指纹（共 " + queue.size() + " 个候选）");
            } else {
                mergeAndApplyAll(drafts);
            }
            finishBusy();
            return;
        }
        final HlsAdCandidate target = queue.get(index);
        final HlsCandidateOccurrence occurrence = target.getOccurrences().get(0);
        try {
            if (collector == null) {
                collector = new AudioFingerprintCollector.Builder(appContext)
                        .setTimeoutMs(45_000L)
                        .build();
            }
            ProbeMedia media = ProbeMedia.builder(mediaUrl).build();
            FingerprintCaptureRequest request = FingerprintCaptureRequest.builder(
                            media, target.getId(), occurrence.getStartMs(), occurrence.getEndMs())
                    .build();
            setStatus("正在采集广告指纹 " + (index + 1) + "/" + queue.size() + "…");
            collector.capture(request, new FingerprintCaptureListener() {
                @Override public void onProgress(FingerprintCaptureProgress progress) {
                    // 进度无需打扰用户
                }

                @Override public void onCompleted(long sessionId, FingerprintRuleDraft draft) {
                    if (draft != null) drafts.add(draft);
                    captureNext(queue, index + 1, mediaUrl, drafts);
                }

                @Override public void onCancelled(long sessionId) {
                    // 单个候选被取消不中断，继续采集其余候选
                    captureNext(queue, index + 1, mediaUrl, drafts);
                }

                @Override public void onError(ProbeToolError error) {
                    // 单个候选失败不中断，继续采集其余候选
                    captureNext(queue, index + 1, mediaUrl, drafts);
                }
            });
        } catch (RuntimeException | LinkageError e) {
            e.printStackTrace();
            captureNext(queue, index + 1, mediaUrl, drafts);
        }
    }

    private void setStatus(String status) {
        lastStatus = status;
        lastStatusAtMs = System.currentTimeMillis();
    }

    /** 自检面板用：返回最近一次采集状态。 */
    public String getStatusText() {
        if (lastStatusAtMs == 0L) return lastStatus;
        long sec = (System.currentTimeMillis() - lastStatusAtMs) / 1000L;
        return lastStatus + "（" + sec + " 秒前）";
    }

    /**
     * 把本轮采集到的全部新规则合并进本地 RULES.JSON（按 id 去重、revision+1），再注入探针。
     * 整批只写一次文件、只上传一次云端。
     */
    private void mergeAndApplyAll(List<FingerprintRuleDraft> drafts) {
        try {
            String path = Setting.getAdRulesPath();
            File file = new File(path);
            JSONObject root = readOrCreate(file);
            long revision = root.optLong("revision", 0L);
            JSONArray rules = root.optJSONArray("rules");
            if (rules == null) rules = new JSONArray();
            int added = 0;
            String lastName = null;
            for (FingerprintRuleDraft draft : drafts) {
                if (draft == null) continue;
                boolean exists = false;
                for (int i = 0; i < rules.length(); i++) {
                    JSONObject rule = rules.optJSONObject(i);
                    if (rule != null && draft.getId().equals(rule.optString("id"))) {
                        exists = true;
                        break;
                    }
                }
                if (exists) continue;
                rules.put(new JSONObject(draft.toRuleJson()));
                added++;
                lastName = draft.getId();
            }
            if (added == 0) {
                setStatus("规则已存在，无需重复采集");
                return;
            }
            root.put("rules", rules);
            root.put("revision", revision + 1L);
            writeAtomic(file, root.toString());
            String json = root.toString();
            AdProbeManager.get().applyCollectedRules(json);
            // 本地新规则默认自动上传到云端（增量、幂等）
            AdCloudSyncManager.get().uploadNewRules();
            setStatus("已生成 " + added + " 条规则，下次播放该源即可跳过");
            Notify.show(ResUtil.getString(R.string.ad_rule_collected,
                    added > 1 ? lastName + " 等 " + added + " 条" : String.valueOf(lastName)));
        } catch (Exception e) {
            e.printStackTrace();
            setStatus("写入规则文件失败：" + e.getClass().getSimpleName());
        }
    }

    private JSONObject readOrCreate(File file) throws Exception {
        JSONObject root = new JSONObject();
        if (file.exists() && file.isFile() && file.length() > 0L) {
            StringBuilder sb = new StringBuilder((int) Math.min(file.length(), 4 * 1024 * 1024));
            try (InputStreamReader reader = new InputStreamReader(
                    new FileInputStream(file), StandardCharsets.UTF_8)) {
                char[] buf = new char[8192];
                int len;
                while ((len = reader.read(buf)) != -1) sb.append(buf, 0, len);
            }
            try {
                JSONObject parsed = new JSONObject(sb.toString().trim());
                if (parsed.has("rules") && parsed.has("revision")) root = parsed;
            } catch (Exception ignored) {
                // 文件损坏时重建新文档
            }
        }
        if (!root.has("format")) root.put("format", "ad-audio-probe-rules");
        if (!root.has("schemaVersion")) root.put("schemaVersion", 1);
        if (!root.has("algorithm")) root.put("algorithm", "spectral-sequence-v1");
        if (!root.has("revision")) root.put("revision", 0L);
        if (!root.has("rules")) root.put("rules", new JSONArray());
        return root;
    }

    private void writeAtomic(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
        if (file.exists()) {
            File backup = new File(file.getAbsolutePath() + ".bak");
            if (backup.exists()) backup.delete();
            if (!file.renameTo(backup)) {
                file.delete();
            }
        }
        if (!tmp.renameTo(file)) {
            throw new IOException("无法写入规则文件: " + file.getAbsolutePath());
        }
        if (file.exists()) {
            File backup = new File(file.getAbsolutePath() + ".bak");
            if (backup.exists()) backup.delete();
        }
    }

    private void finishBusy() {
        busy.set(false);
    }

    private void post(Runnable command) {
        ensureThread();
        workHandler.post(command);
    }

    private synchronized void ensureThread() {
        if (workThread != null) return;
        workThread = new HandlerThread("AdRuleCollector");
        workThread.start();
        workHandler = new Handler(workThread.getLooper());
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

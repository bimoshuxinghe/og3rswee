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
import java.util.Collections;
import java.util.LinkedHashMap;
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

    private static volatile AdRuleCollector instance;

    private final AtomicBoolean busy = new AtomicBoolean(false);
    private HandlerThread workThread;
    private Handler workHandler;
    private HlsCandidateScanner scanner;
    private AudioFingerprintCollector collector;
    private Context appContext;
    private volatile String currentUrl;

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
                    finishBusy();
                }
            });
        } catch (RuntimeException | LinkageError e) {
            e.printStackTrace();
            finishBusy();
        }
    }

    private void handleScanResult(HlsScanResult result) {
        if (result == null || result.getCandidates().isEmpty()) {
            finishBusy();
            return;
        }
        HlsAdCandidate candidate = null;
        for (HlsAdCandidate item : result.getCandidates()) {
            // 指纹采集要求广告区间至少 5 秒
            if (item.getDurationMs() >= FingerprintCaptureRequest.REQUIRED_ANCHOR_DURATION_MS) {
                candidate = item;
                break;
            }
        }
        if (candidate == null) {
            finishBusy();
            return;
        }
        final HlsAdCandidate target = candidate;
        final HlsCandidateOccurrence occurrence = target.getOccurrences().get(0);
        final String mediaUrl = result.getMediaPlaylistUrl();
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
            collector.capture(request, new FingerprintCaptureListener() {
                @Override public void onProgress(FingerprintCaptureProgress progress) {
                    // 进度无需打扰用户
                }

                @Override public void onCompleted(long sessionId, FingerprintRuleDraft draft) {
                    mergeAndApply(draft);
                }

                @Override public void onCancelled(long sessionId) {
                    finishBusy();
                }

                @Override public void onError(ProbeToolError error) {
                    finishBusy();
                }
            });
        } catch (RuntimeException | LinkageError e) {
            e.printStackTrace();
            finishBusy();
        }
    }

    /** 把新规则合并进本地 RULES.JSON（按 id 去重、revision+1），再注入探针。 */
    private void mergeAndApply(FingerprintRuleDraft draft) {
        try {
            String path = Setting.getAdRulesPath();
            File file = new File(path);
            JSONObject root = readOrCreate(file);
            long revision = root.optLong("revision", 0L);
            JSONArray rules = root.optJSONArray("rules");
            if (rules == null) rules = new JSONArray();
            boolean exists = false;
            for (int i = 0; i < rules.length(); i++) {
                JSONObject rule = rules.optJSONObject(i);
                if (rule != null && draft.getId().equals(rule.optString("id"))) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                rules.put(new JSONObject(draft.toRuleJson()));
                root.put("rules", rules);
                root.put("revision", revision + 1L);
                writeAtomic(file, root.toString());
                String json = root.toString();
                AdProbeManager.get().applyCollectedRules(json);
                // 本地新规则默认自动上传到云端（增量、幂等）
                AdCloudSyncManager.get().uploadNewRules();
                Notify.show(ResUtil.getString(R.string.ad_rule_collected, draft.getId()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            finishBusy();
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

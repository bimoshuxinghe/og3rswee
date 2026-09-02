package com.fongmi.android.tv.player;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 云端广告规则同步管理器。
 * <p>
 * 负责：
 * 1. 拉取：GET rules_server.php，把云端 RULES.JSON 与本地合并（音频按 id 去重、文本按内容去重），
 *    revision 取较大值，原子写回本地并注入 AdProbeManager。
 * 2. 上传：POST rules_server.php（带 X-Rules-Token），把本地尚未上传过的规则增量上传，
 *    成功后把新规则 id 记入 Setting.ad_cloud_uploaded_ids 防止重复上传。
 */
public final class AdCloudSyncManager {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final String TAG = "AdCloudSync";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    // 云端同步状态（供自检面板展示）。整条链路原为静默执行，
    // 一旦拉不到规则，用户只能看到「什么都不提示」而无从判断。
    private volatile String lastStatus = "尚未同步过";
    private volatile long lastStatusAtMs;
    private volatile int lastAudioCount;
    /** 实际成功落盘的规则文件路径（外部失败会降级私有目录，用于自检面板展示）。 */
    private volatile String lastWrittenPath;
    /** 同步进行中标记，供 AdProbeManager 兜底逻辑防重入。 */
    private volatile boolean syncing;

    private AdCloudSyncManager() {
    }

    private void setStatus(String status) {
        lastStatus = status;
        lastStatusAtMs = System.currentTimeMillis();
    }

    /** 自检面板用：返回最近一次云端同步状态。 */
    public String getStatusText() {
        if (lastStatusAtMs == 0L) return lastStatus;
        long sec = (System.currentTimeMillis() - lastStatusAtMs) / 1000L;
        return lastStatus + "（" + sec + " 秒前）";
    }

    /** 自检面板用：最近一次同步后的规则条数。 */
    public int getLastAudioCount() {
        return lastAudioCount;
    }

    /** 是否正在同步中（供 AdProbeManager 兜底逻辑防重入）。 */
    public boolean isSyncing() {
        return syncing;
    }

    private static class Holder {
        private static final AdCloudSyncManager INSTANCE = new AdCloudSyncManager();
    }

    public static AdCloudSyncManager get() {
        return Holder.INSTANCE;
    }

    /** 同步结果回调（主线程）。 */
    public interface SyncCallback {
        /** 云端已加载。audioCount/textRuleCount 为云端合并后的本地总条数；added 为本次新增条数。 */
        void onLoaded(int audioCount, int textRuleCount, int added);

        /** 未配置云端地址。 */
        void onNoUrl();

        /** 拉取/合并失败。message 为可展示的错误信息。 */
        void onError(@NonNull String message);
    }

    /**
     * 拉取云端规则并合并到本地。
     *
     * <p>注意：当前版本按需求<strong>彻底关闭云端同步</strong>（上传/下载全关）。
     * 规则只来自本地文件与本地自动采集，不再与云端交互，避免云端毒数据污染本地规则库。
     * 这里直接短路为 no-op，只更新自检面板状态，不发起任何网络请求。</p>
     */
    public void syncFromCloud(SyncCallback callback) {
        setStatus("云端同步已关闭，规则仅来自本地文件与本地采集");
        if (callback != null) callback.onNoUrl();
    }

    /**
     * 上传本地新增规则到云端（增量、幂等）。
     *
     * <p>注意：当前版本按需求<strong>彻底关闭云端同步</strong>（上传/下载全关）。
     * 本地自动采集到的规则只保存到本地 RULES.JSON，不再上传到云端，避免把毒数据回传给其他用户。
     * 这里直接短路为 no-op，不发起任何网络请求。</p>
     */
    public void uploadNewRules() {
        setStatus("云端同步已关闭，本地采集的规则仅保存到本地");
    }

    private void postLoaded(SyncCallback callback, Result r) {
        MAIN.post(() -> {
            if (callback != null) callback.onLoaded(r.audioCount, r.textRuleCount, r.added);
        });
    }

    private void postError(SyncCallback callback, String msg) {
        MAIN.post(() -> {
            if (callback != null) callback.onError(msg);
        });
    }

    private static class Result {
        int audioCount;
        int textRuleCount;
        int added;
    }

    /** 把云端文档合并进本地 RULES.JSON，原子写回并注入探针。 */
    private Result mergeIntoLocal(JSONObject cloud) throws Exception {
        List<String> candidates = Setting.getRulesPathCandidates();
        File file = firstExistingOrPrimary(candidates);
        JSONObject local = readOrCreate(file);
        long localRev = local.optLong("revision", 0L);
        long cloudRev = cloud.optLong("revision", 0L);

        // 合并音频规则（按 id 去重）
        Set<String> seen = new HashSet<>();
        JSONArray merged = new JSONArray();
        JSONArray localRules = local.optJSONArray("rules");
        if (localRules != null) {
            for (int i = 0; i < localRules.length(); i++) {
                JSONObject rule = localRules.optJSONObject(i);
                if (rule == null) continue;
                String id = rule.optString("id");
                if (id.isEmpty() || seen.contains(id)) continue;
                seen.add(id);
                merged.put(rule);
            }
        }
        int added = 0;
        JSONArray cloudRules = cloud.optJSONArray("rules");
        if (cloudRules != null) {
            for (int i = 0; i < cloudRules.length(); i++) {
                JSONObject rule = cloudRules.optJSONObject(i);
                if (rule == null) continue;
                String id = rule.optString("id");
                if (id.isEmpty() || seen.contains(id)) continue;
                seen.add(id);
                merged.put(rule);
                added++;
            }
        }

        // 文本规则纯本地：不合并云端 textRules，避免云端历史残留污染本地。
        // textRules 为历史遗留字段，现仅保留在 RULES.JSON 中，无运行逻辑依赖。
        Set<String> textSeen = new HashSet<>();
        JSONArray mergedText = new JSONArray();
        JSONArray localText = local.optJSONArray("textRules");
        if (localText != null) {
            for (int i = 0; i < localText.length(); i++) {
                String t = localText.optString(i);
                if (t.isEmpty() || textSeen.contains(t)) continue;
                textSeen.add(t);
                mergedText.put(t);
            }
        }

        // 新文档：格式字段保留云端，revision 取较大值
        JSONObject out = new JSONObject();
        out.put("format", "ad-audio-probe-rules");
        out.put("schemaVersion", 1);
        out.put("algorithm", "spectral-sequence-v1");
        out.put("revision", Math.max(localRev, cloudRev));
        out.put("rules", merged);

        // 注意：落盘文件【不能】含 textRules。该字段是历史遗留、运行无依赖，
        // 但探针 SDK 的 rules-v1 解析器是严格白名单模式，遇到未知字段会整份拒绝规则
        // （RULE_PARSE_FAILED），导致声纹去广告完全失效。文本规则仅保留在内存/统计中，
        // 不再写入本地 RULES.JSON。
        // 写盘优先外部目录（与旧版/采集器兼容）；Android 11+ 未授予“所有文件访问权限”
        // 会 EACCES 时，自动降级到应用私有目录，保证规则一定能落盘。
        File written = writeToCandidates(candidates, out.toString(2));
        lastWrittenPath = written.getAbsolutePath();

        // 注入探针：只给探针纯音频指纹规则。
        // textRules 为历史遗留字段，探针 SDK 严格解析器
        // 遇到未知字段会整体拒绝，因此音频与文本必须分开读取、分开注入。
        JSONObject probeDoc = new JSONObject();
        probeDoc.put("format", "ad-audio-probe-rules");
        probeDoc.put("schemaVersion", 1);
        probeDoc.put("algorithm", "spectral-sequence-v1");
        probeDoc.put("revision", Math.max(localRev, cloudRev));
        probeDoc.put("rules", merged);
        AdProbeManager.get().applyCollectedRules(probeDoc.toString());

        Result r = new Result();
        r.audioCount = merged.length();
        r.textRuleCount = mergedText.length();
        r.added = added;
        return r;
    }

    private JSONObject readOrCreate(File file) throws Exception {
        JSONObject root = new JSONObject();
        if (file.exists() && file.isFile() && file.length() > 0L) {
            StringBuilder sb = new StringBuilder((int) Math.min(file.length(), 4 * 1024 * 1024));
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                char[] buf = new char[8192];
                int len;
                while ((len = reader.read(buf)) != -1) sb.append(buf, 0, len);
            }
            try {
                JSONObject parsed = new JSONObject(sb.toString().trim());
                if (parsed.has("rules")) root = parsed;
            } catch (Exception ignored) {
            }
        }
        if (!root.has("format")) root.put("format", "ad-audio-probe-rules");
        if (!root.has("schemaVersion")) root.put("schemaVersion", 1);
        if (!root.has("algorithm")) root.put("algorithm", "spectral-sequence-v1");
        if (!root.has("revision")) root.put("revision", 0L);
        if (!root.has("rules")) root.put("rules", new JSONArray());
        return root;
    }

    private void writeAtomic(File file, String content) throws Exception {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        File tmp = new File(file.getAbsolutePath() + ".tmp");
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            writer.write(content);
        }
        if (file.exists() && !file.delete()) {
            throw new Exception("cannot replace rules file");
        }
        if (!tmp.renameTo(file)) {
            throw new Exception("cannot write rules file");
        }
    }

    /** 按优先级尝试写盘：外部目录优先，全部失败则降级到下一个候选（私有目录）。 */
    private File writeToCandidates(List<String> candidates, String content) throws Exception {
        Exception last = null;
        for (String path : candidates) {
            try {
                File file = new File(path);
                writeAtomic(file, content);
                return file;
            } catch (Exception e) {
                last = e;
                Log.w(TAG, "规则写盘失败 path=" + path + " msg=" + e.getMessage());
            }
        }
        throw last != null ? last : new Exception("cannot write rules file");
    }

    /** 在候选路径中挑一个已存在的文件；都没有则返回第一个（主路径），供读取/合并使用。 */
    private File firstExistingOrPrimary(List<String> candidates) {
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists() && f.isFile()) return f;
        }
        return new File(candidates.get(0));
    }

    /** 自检面板用：最近一次成功落盘的规则文件绝对路径。 */
    public String getLastWrittenPath() {
        return lastWrittenPath;
    }
}

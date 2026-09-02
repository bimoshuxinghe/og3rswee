package com.fongmi.android.tv.player;

import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

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

import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 广告规则库拉取管理器（仅下载、不上传）。
 * <p>
 * 行为：GET 用户配置的“广告规则库地址”（默认指向社区公开 rules.json），解析后与本地
 * RULES.JSON（含本地自动采集到的规则）按 id 合并，原子写回本地并注入声纹探针。
 * 全程<strong>只下载、永不回传</strong>——本地自动采集到的规则只留在本地，避免把脏数据回流到任何远端。
 */
public final class AdCloudSyncManager {

    private static final String TAG = "AdRuleLib";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private AdCloudSyncManager() {
    }

    private static class Holder {
        private static final AdCloudSyncManager INSTANCE = new AdCloudSyncManager();
    }

    public static AdCloudSyncManager get() {
        return Holder.INSTANCE;
    }

    /** 拉取结果回调（主线程）。 */
    public interface SyncCallback {
        /** 规则库已加载并合并。audioCount 为合并后本地总条数；added 为本次新增条数。 */
        void onLoaded(int audioCount, int textRuleCount, int added);

        /** 未配置规则库地址。 */
        void onNoUrl();

        /** 拉取/合并失败。message 为可展示的错误信息。 */
        void onError(@NonNull String message);
    }

    /**
     * 拉取广告规则库并合并到本地（仅下载、不上传）。
     * <p>在后台线程执行网络请求，结果通过 callback 回主线程。</p>
     */
    public void syncFromCloud(@Nullable SyncCallback callback) {
        executor.execute(() -> {
            try {
                String url = Setting.getRuleLibraryUrl();
                if (url == null || url.trim().isEmpty()) {
                    if (callback != null) callback.onNoUrl();
                    return;
                }
                String body;
                try (Response resp = OkHttp.newCall(url.trim()).execute();
                     ResponseBody rb = resp.body()) {
                    if (!resp.isSuccessful() || rb == null) {
                        if (callback != null) callback.onError("HTTP " + resp.code());
                        return;
                    }
                    body = rb.string();
                }

                JSONObject remote = new JSONObject(body);
                JSONArray remoteRules = remote.optJSONArray("rules");
                if (remoteRules == null) remoteRules = new JSONArray();
                long remoteRev = remote.optLong("revision", 0L);

                // 读本地（含自动采集的规则），合并 remote + 本地，按 id 去重
                List<String> candidates = Setting.getRulesPathCandidates();
                File readFile = firstExistingOrPrimary(candidates);
                JSONObject local = readOrCreate(readFile);
                long localRev = local.optLong("revision", 0L);
                JSONArray localRules = local.optJSONArray("rules");
                if (localRules == null) localRules = new JSONArray();

                Set<String> seen = new HashSet<>();
                JSONArray merged = new JSONArray();
                int added = 0;
                // 先放本地（含自动采集），保证本地规则永不丢失
                for (int i = 0; i < localRules.length(); i++) {
                    JSONObject r = localRules.optJSONObject(i);
                    if (r == null) continue;
                    String id = r.optString("id");
                    if (id.isEmpty() || seen.contains(id)) continue;
                    seen.add(id);
                    merged.put(r);
                }
                // 再放远端，去重；远端新增的计入 added
                for (int i = 0; i < remoteRules.length(); i++) {
                    JSONObject r = remoteRules.optJSONObject(i);
                    if (r == null) continue;
                    String id = r.optString("id");
                    if (id.isEmpty() || seen.contains(id)) continue;
                    seen.add(id);
                    merged.put(r);
                    added++;
                }

                JSONObject out = new JSONObject();
                out.put("format", "ad-audio-probe-rules");
                out.put("schemaVersion", 1);
                out.put("algorithm", "spectral-sequence-v1");
                out.put("revision", Math.max(localRev, remoteRev));
                out.put("rules", merged);

                File written = writeToCandidates(candidates, out.toString(2));
                // 若写到了别的候选路径（如外部目录不可写降级到私有目录），删除旧的残留文件，
                // 避免探针先读到旧的 stale 文件。
                if (!written.getAbsolutePath().equals(readFile.getAbsolutePath())
                        && readFile.exists() && readFile.isFile()) {
                    readFile.delete();
                }
                AdProbeManager.get().applyCollectedRules(out.toString());

                int total = merged.length();
                if (callback != null) callback.onLoaded(total, 0, added);
            } catch (Exception e) {
                Log.e(TAG, "拉取规则库失败", e);
                if (callback != null) callback.onError(e.getMessage());
            }
        });
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
}

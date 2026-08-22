package com.fongmi.android.tv.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fongmi.android.tv.setting.Setting;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Vosk 模型下载器：从用户可配置的 URL 下载 zip 并解压到应用文件目录，
 * 解压完成后把模型目录路径写入 {@link Setting#putVoskModelPath(String)}。
 *
 * <p>模型不内置：用户先下载、再自行开启 {@link Setting#putVoskEnabled(boolean)}。</p>
 */
public final class VoskModelManager {

    private static volatile VoskModelManager instance;

    private final OkHttpClient client = new OkHttpClient.Builder().build();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean downloading;

    public interface Callback {
        void onProgress(int percent);

        void onSuccess(@NonNull File modelDir);

        void onError(@NonNull String message);
    }

    public static VoskModelManager get() {
        if (instance == null) {
            synchronized (VoskModelManager.class) {
                if (instance == null) instance = new VoskModelManager();
            }
        }
        return instance;
    }

    private VoskModelManager() {
    }

    public boolean isDownloading() {
        return downloading;
    }

    /** 下载并解压模型。目标目录：filesDir/vosk-model-small-cn-0.22（按 URL 文件名推导）。 */
    public void download(@NonNull Context context, @Nullable Callback callback) {
        if (downloading) return;
        String url = Setting.getVoskModelUrl();
        if (url == null || url.trim().isEmpty()) {
            if (callback != null) callback.onError("empty url");
            return;
        }
        String fileName = fileNameOf(url);
        File zipFile = new File(context.getFilesDir(), fileName + ".download");
        File modelDir = new File(context.getFilesDir(), modelNameOf(fileName));
        downloading = true;

        Request request = new Request.Builder().url(url.trim()).build();
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                downloading = false;
                if (callback != null) mainHandler.post(() -> callback.onError(e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response resp = response) {
                    if (!resp.isSuccessful()) {
                        downloading = false;
                        if (callback != null) mainHandler.post(() -> callback.onError("HTTP " + resp.code()));
                        return;
                    }
                    long total = resp.body() == null ? -1 : resp.body().contentLength();
                    long read = 0;
                    byte[] buffer = new byte[32768];
                    try (InputStream in = new BufferedInputStream(resp.body().byteStream());
                         FileOutputStream out = new FileOutputStream(zipFile)) {
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            read += n;
                            out.write(buffer, 0, n);
                            if (total > 0 && callback != null) {
                                final int pct = (int) (read * 100 / total);
                                mainHandler.post(() -> callback.onProgress(pct));
                            }
                        }
                    }
                    // 解压（先清掉旧目录避免残留）
                    deleteRecursively(modelDir);
                    if (!modelDir.exists() && !modelDir.mkdirs()) {
                        throw new IOException("mkdir failed");
                    }
                    try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
                        ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            File target = new File(modelDir, entry.getName());
                            if (entry.isDirectory()) {
                                if (!target.exists() && !target.mkdirs()) throw new IOException("mkdir failed: " + entry.getName());
                            } else {
                                File parent = target.getParentFile();
                                if (parent != null && !parent.exists() && !parent.mkdirs()) throw new IOException("mkdir failed");
                                try (FileOutputStream fos = new FileOutputStream(target)) {
                                    byte[] buf = new byte[16384];
                                    int len;
                                    while ((len = zis.read(buf)) != -1) fos.write(buf, 0, len);
                                }
                            }
                            zis.closeEntry();
                        }
                    }
                    if (!zipFile.delete()) zipFile.deleteOnExit();
                    File realModelDir = findModelDir(modelDir);
                    Setting.putVoskModelPath(realModelDir.getAbsolutePath());
                    downloading = false;
                    if (callback != null) mainHandler.post(() -> callback.onSuccess(realModelDir));
                } catch (Exception e) {
                    downloading = false;
                    deleteRecursively(modelDir);
                    if (zipFile.exists()) zipFile.delete();
                    if (callback != null) mainHandler.post(() -> callback.onError(e.getMessage()));
                }
            }
        });
    }

    /** 删除已下载模型（用户主动删除，删除后开关自动关闭）。 */
    public void delete(@NonNull Context context) {
        String path = Setting.getVoskModelPath();
        if (path != null && !path.isEmpty()) {
            File modelDir = new File(path);
            // 若模型目录外面还有下载外层目录，一并清理
            File parent = modelDir.getParentFile();
            if (parent != null && parent.getName().startsWith("vosk-model-")) {
                deleteRecursively(parent);
            } else {
                deleteRecursively(modelDir);
            }
        }
        Setting.putVoskModelPath("");
        Setting.putVoskEnabled(false);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static String fileNameOf(String url) {
        String name = url.substring(url.lastIndexOf('/') + 1);
        if (name.isEmpty()) name = "vosk-model-small-cn-0.22.zip";
        return name;
    }

    private static String modelNameOf(String fileName) {
        String name = fileName;
        if (name.endsWith(".zip")) name = name.substring(0, name.length() - 4);
        return name;
    }

    /**
     * 定位真正的模型目录：Vosk 模型目录特征为包含 am（acoustic model）目录。
     * 若 zip 顶层还包了一层目录，则返回该子目录；否则返回 dir 本身。
     */
    private static File findModelDir(File dir) {
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory() && new File(child, "am").isDirectory()) {
                    return child;
                }
            }
        }
        return dir;
    }
}

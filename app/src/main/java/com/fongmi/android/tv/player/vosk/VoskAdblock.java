package com.fongmi.android.tv.player.vosk;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.FileUtil;
import com.github.catvod.net.OkHttp;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoskAdblock {

    public static final String MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-cn-0.22.zip";
    public static final String MODEL_FOLDER = "vosk-model-small-cn-0.22";

    public interface Listener {
        void onAdDetected(long skipMs);
    }

    public interface DownloadCallback {
        void onDone(boolean success, String error);
    }

    private static volatile VoskAdblock instance;

    private final List<Listener> listeners = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private Model model;
    private Recognizer recognizer;
    private volatile boolean ready;
    private volatile boolean enabled;
    private volatile boolean probing;
    private long lastDetectAt;
    private long lastResetAt;

    private VoskAdblock() {
    }

    public static VoskAdblock get() {
        if (instance == null) {
            synchronized (VoskAdblock.class) {
                if (instance == null) instance = new VoskAdblock();
            }
        }
        return instance;
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public File getModelDir() {
        return new File(App.get().getFilesDir(), MODEL_FOLDER);
    }

    public boolean isModelDownloaded() {
        File dir = getModelDir();
        return new File(dir, "am").isDirectory() && new File(dir, "conf").isDirectory();
    }

    public boolean isActive() {
        return enabled && ready && recognizer != null;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (enabled) ensureReady();
        else releaseRecognizer();
    }

    public void ensureReady() {
        if (!Setting.isAiAdblock()) return;
        executor.execute(() -> {
            if (model != null || !isModelDownloaded()) return;
            synchronized (lock) {
                try {
                    model = new Model(getModelDir().getAbsolutePath());
                    recognizer = new Recognizer(model, 16000.0f);
                    ready = true;
                } catch (Exception e) {
                    model = null;
                    recognizer = null;
                    ready = false;
                }
            }
        });
    }

    public void downloadModel(DownloadCallback callback) {
        executor.execute(() -> {
            File zip = new File(App.get().getFilesDir(), MODEL_FOLDER + ".zip");
            try {
                okhttp3.Request request = new okhttp3.Request.Builder().url(MODEL_URL).build();
                try (okhttp3.Response response = OkHttp.client(60000).newCall(request).execute()) {
                    if (!response.isSuccessful() || response.body() == null) {
                        dispatchDownload(callback, false, "HTTP " + response.code());
                        return;
                    }
                    try (InputStream is = response.body().byteStream(); FileOutputStream fos = new FileOutputStream(zip)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
                    }
                }
                FileUtil.zipDecompress(zip, App.get().getFilesDir());
                zip.delete();
                boolean ok = isModelDownloaded();
                if (ok) ensureReady();
                dispatchDownload(callback, ok, null);
            } catch (Exception e) {
                dispatchDownload(callback, false, e.getMessage());
            }
        });
    }

    private void releaseRecognizer() {
        executor.execute(() -> {
            synchronized (lock) {
                if (recognizer != null) {
                    try {
                        recognizer.close();
                    } catch (Exception ignored) {
                    }
                    recognizer = null;
                }
                if (model != null) {
                    try {
                        model.close();
                    } catch (Exception ignored) {
                    }
                    model = null;
                }
                ready = false;
            }
        });
    }

    public void acceptPcm16(byte[] pcm16, int sampleRate, int channels) {
        if (!isActive()) return;
        short[] samples = bytesToShorts(pcm16);
        short[] mono = channels == 1 ? samples : toMono(samples, channels);
        short[] resampled = sampleRate == 16000 ? mono : resample(mono, sampleRate, 16000);
        synchronized (lock) {
            if (recognizer == null) return;
            try {
                boolean finalResult = recognizer.acceptWaveForm(resampled, resampled.length);
                String json = finalResult ? recognizer.getResult() : recognizer.getPartialResult();
                handleResult(json);
            } catch (Exception ignored) {
            }
        }
    }

    private void handleResult(String json) {
        if (TextUtils.isEmpty(json)) return;
        try {
            JSONObject obj = new JSONObject(json);
            String text = obj.optString("text", obj.optString("partial", "")).trim();
            if (TextUtils.isEmpty(text)) {
                if (probing && SystemClock.elapsedRealtime() - lastResetAt > 3000) probing = false;
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastResetAt < 800) return;
            if (containsKeyword(text)) {
                if (!probing && now - lastDetectAt < 5000) return;
                lastDetectAt = now;
                probing = true;
                notifyAd();
            } else if (probing) {
                probing = false;
                resetRecognizer();
            }
        } catch (Exception ignored) {
        }
    }

    private void notifyAd() {
        long skipMs = Setting.getAiAdblockSkipSeconds() * 1000L;
        mainHandler.post(() -> {
            for (Listener listener : listeners) listener.onAdDetected(skipMs);
        });
        resetRecognizer();
    }

    private void resetRecognizer() {
        lastResetAt = SystemClock.elapsedRealtime();
        synchronized (lock) {
            if (recognizer != null) {
                try {
                    recognizer.close();
                } catch (Exception ignored) {
                }
                recognizer = null;
            }
            if (model != null) {
                try {
                    recognizer = new Recognizer(model, 16000.0f);
                } catch (Exception e) {
                    recognizer = null;
                }
            }
        }
    }

    private boolean containsKeyword(String text) {
        String keywords = Setting.getAiAdblockKeywords();
        if (TextUtils.isEmpty(keywords)) return false;
        for (String keyword : keywords.split("[,，]")) {
            String k = keyword.trim();
            if (!k.isEmpty() && text.contains(k)) return true;
        }
        return false;
    }

    private void dispatchDownload(DownloadCallback callback, boolean success, String error) {
        mainHandler.post(() -> callback.onDone(success, error));
    }

    private static short[] bytesToShorts(byte[] bytes) {
        short[] shorts = new short[bytes.length / 2];
        for (int i = 0; i < shorts.length; i++) {
            int b1 = bytes[i * 2] & 0xFF;
            int b2 = bytes[i * 2 + 1] & 0xFF;
            shorts[i] = (short) (b1 | (b2 << 8));
        }
        return shorts;
    }

    private static short[] toMono(short[] input, int channels) {
        short[] out = new short[input.length / channels];
        for (int i = 0, j = 0; i < out.length; i++, j += channels) {
            int sum = 0;
            for (int c = 0; c < channels; c++) sum += input[j + c];
            out[i] = (short) (sum / channels);
        }
        return out;
    }

    private static short[] resample(short[] input, int inRate, int outRate) {
        if (inRate <= 0 || outRate <= 0 || input.length == 0) return input;
        double ratio = (double) outRate / inRate;
        int outLen = (int) (input.length * ratio);
        short[] out = new short[Math.max(1, outLen)];
        for (int i = 0; i < out.length; i++) {
            double pos = i / ratio;
            int idx = (int) pos;
            double frac = pos - idx;
            int a = input[idx];
            int b = input[Math.min(idx + 1, input.length - 1)];
            out[i] = (short) Math.round(a + (b - a) * frac);
        }
        return out;
    }
}

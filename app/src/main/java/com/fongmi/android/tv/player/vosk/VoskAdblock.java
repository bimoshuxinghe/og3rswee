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

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
    private final ExecutorService executor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128),
            new ThreadPoolExecutor.DiscardPolicy());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();

    private Model model;
    private Recognizer recognizer;
    private volatile boolean ready;
    private volatile boolean enabled;
    private volatile boolean probing;
    private long lastDetectAt;
    private long lastResetAt;
    private int consecutiveHits;
    private volatile String lastRecognizedText = "";

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

    public boolean isReady() {
        return ready && model != null;
    }

    public String getLastRecognizedText() {
        return lastRecognizedText;
    }

    public boolean isActive() {
        return enabled && ready && recognizer != null;
    }

    public boolean isEnabled() {
        return enabled;
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

    public void offerPcm16(byte[] pcm16, int sampleRate, int channels) {
        if (!isActive()) return;
        executor.execute(() -> acceptPcm16(pcm16, sampleRate, channels));
    }

    private void acceptPcm16(byte[] pcm16, int sampleRate, int channels) {
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
            lastRecognizedText = text;
            if (TextUtils.isEmpty(text)) {
                if (probing && SystemClock.elapsedRealtime() - lastResetAt > 3000) probing = false;
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (now - lastResetAt < 800) return;
            if (containsKeyword(text)) {
                if (now - lastDetectAt < 5000) return;
                boolean continuous = now - lastDetectAt < 10000;
                consecutiveHits = continuous ? consecutiveHits + 1 : 0;
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
        int base = Setting.getAiAdblockSkipSeconds();
        long skipMs = base * (1L << Math.min(consecutiveHits, 2)) * 1000L;
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
        String pinyinText = toPinyin(text);
        for (String keyword : keywords.split("[,，\\n]")) {
            String k = keyword.trim();
            if (k.isEmpty()) continue;
            if (text.contains(k)) return true;
            String pinyinKeyword = toPinyin(k);
            if (!pinyinKeyword.isEmpty()) {
                if (pinyinText.contains(pinyinKeyword)) return true;
                // 模糊匹配：识别常有同音近音错字（如"飞鹤启萃"被听成"非和起称"），
                // 精确拼音比对会漏掉，这里取关键词拼音前 70%（至少 4 个字母）做前缀匹配。
                int prefixLen = Math.max(4, (int) (pinyinKeyword.length() * 0.7));
                String prefix = pinyinKeyword.substring(0, Math.min(prefixLen, pinyinKeyword.length()));
                if (prefix.length() >= 4 && pinyinText.contains(prefix)) return true;
            }
        }
        return false;
    }

    private static String toPinyin(String text) {
        if (TextUtils.isEmpty(text)) return "";
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) continue;
            try {
                String[] pinyin = PinyinHelper.toHanyuPinyinStringArray(c, format);
                if (pinyin != null && pinyin.length > 0) sb.append(pinyin[0]);
                else sb.append(Character.toLowerCase(c));
            } catch (BadHanyuPinyinOutputFormatCombination e) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
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

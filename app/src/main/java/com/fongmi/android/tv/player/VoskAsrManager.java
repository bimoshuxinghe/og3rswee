package com.fongmi.android.tv.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.fongmi.android.tv.setting.Setting;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.File;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Vosk 语音识别去广告：旁路采集播放器 PCM（任意采样率/声道/位深），
 * 在后台线程重采样为 16kHz 单声道 16bit 喂给 Vosk 识别器，
 * 识别出的文本交回 {@link TextAdRuleManager} 走关键字规则命中跳秒。
 *
 * <p>模型不内置：用户需在设置页下载 Vosk 中文小模型并解压到应用目录，
 * 路径保存在 {@link Setting#getVoskModelPath()}。总开关
 * {@link Setting#isVoskEnabled()} 默认关闭，由用户自行开启。</p>
 */
public final class VoskAsrManager {

    private static final int TARGET_SAMPLE_RATE = 16000;
    private static final int QUEUE_CAPACITY = 128;

    private static volatile VoskAsrManager instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BlockingQueue<PcmFrame> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(false);

    private Thread worker;
    private Model model;
    private Recognizer recognizer;
    private String loadedModelPath;

    public static VoskAsrManager get() {
        if (instance == null) {
            synchronized (VoskAsrManager.class) {
                if (instance == null) instance = new VoskAsrManager();
            }
        }
        return instance;
    }

    private VoskAsrManager() {
    }

    /** 是否已加载可用模型（模型目录存在且被成功加载过）。 */
    public boolean isModelLoaded() {
        return model != null && loadedModelPath != null;
    }

    /**
     * 尝试加载模型并启动识别线程。总开关开启时才真正启动；
     * 模型缺失时静默返回 false，不阻塞播放。
     */
    public boolean start(Context context) {
        if (!Setting.isVoskEnabled()) return false;
        String modelPath = Setting.getVoskModelPath();
        if (TextUtils.isEmpty(modelPath)) return false;
        File modelDir = new File(modelPath);
        if (!modelDir.exists() || !modelDir.isDirectory()) return false;
        if (running.get()) return true;

        // 模型目录变化时重新加载
        if (model == null || !modelPath.equals(loadedModelPath)) {
            try {
                if (model != null) model.close();
                model = new Model(modelPath);
                loadedModelPath = modelPath;
            } catch (Exception e) {
                model = null;
                loadedModelPath = null;
                return false;
            }
        }
        try {
            if (recognizer != null) recognizer.close();
            recognizer = new Recognizer(model, TARGET_SAMPLE_RATE);
        } catch (Exception e) {
            return false;
        }
        queue.clear();
        running.set(true);
        worker = new Thread(this::loop, "vosk-asr");
        worker.setDaemon(true);
        worker.start();
        return true;
    }

    /** 停止识别并释放资源。 */
    public void stop() {
        running.set(false);
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        queue.clear();
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
            loadedModelPath = null;
        }
    }

    /**
     * 由播放器 AudioProcessor 旁路调用：复制一份 PCM 入队，不阻塞播放线程。
     *
     * @param data     原始 PCM 字节（16bit 或 float 小端）
     * @param sampleRate 原始采样率
     * @param channels   原始声道数
     * @param isFloat    true=float PCM，false=16bit PCM
     */
    public void feedPcm(byte[] data, int sampleRate, int channels, boolean isFloat) {
        if (!running.get() || recognizer == null) return;
        if (data == null || data.length == 0 || sampleRate <= 0 || channels <= 0) return;
        queue.offer(new PcmFrame(data, sampleRate, channels, isFloat));
    }

    private void loop() {
        while (running.get()) {
            try {
                PcmFrame frame = queue.poll(200, TimeUnit.MILLISECONDS);
                if (frame == null) continue;
                short[] samples = convertToMono16k(frame);
                if (samples == null || samples.length == 0) continue;
                recognizer.acceptWaveForm(samples, samples.length);
                String partial = parseText(recognizer.getPartialResult());
                if (TextUtils.isEmpty(partial)) continue;
                final String text = partial;
                mainHandler.post(() -> TextAdRuleManager.get().matchSpokenText(text));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception ignored) {
            }
        }
    }

    private String parseText(String json) {
        try {
            if (TextUtils.isEmpty(json)) return "";
            JSONObject obj = new JSONObject(json);
            return obj.optString("partial", "");
        } catch (Exception e) {
            return "";
        }
    }

    /** 任意格式 PCM -> 16kHz 单声道 16bit short[]。 */
    private short[] convertToMono16k(PcmFrame f) {
        int frameCount;
        float[] mono;
        if (f.isFloat) {
            int bytesPerFrame = 4 * f.channels;
            if (bytesPerFrame <= 0 || f.data.length < bytesPerFrame) return null;
            frameCount = f.data.length / bytesPerFrame;
            mono = new float[frameCount];
            for (int i = 0; i < frameCount; i++) {
                int base = i * f.channels * 4;
                mono[i] = readFloat(f.data, base);
            }
        } else {
            int bytesPerFrame = 2 * f.channels;
            if (bytesPerFrame <= 0 || f.data.length < bytesPerFrame) return null;
            frameCount = f.data.length / bytesPerFrame;
            mono = new float[frameCount];
            for (int i = 0; i < frameCount; i++) {
                int base = i * f.channels * 2;
                mono[i] = readShort(f.data, base) / 32768f;
            }
        }
        if (frameCount == 0) return null;

        // 线性重采样到 16k
        int outCount = Math.max(1, (int) ((long) frameCount * TARGET_SAMPLE_RATE / f.sampleRate));
        short[] out = new short[outCount];
        float ratio = (float) frameCount / outCount;
        for (int i = 0; i < outCount; i++) {
            float srcPos = i * ratio;
            int idx = (int) srcPos;
            if (idx >= frameCount - 1) {
                out[i] = toShort(mono[frameCount - 1]);
                continue;
            }
            float frac = srcPos - idx;
            float v = mono[idx] * (1f - frac) + mono[idx + 1] * frac;
            out[i] = toShort(v);
        }
        return out;
    }

    private static float readFloat(byte[] d, int off) {
        int bits = (d[off] & 0xff) | ((d[off + 1] & 0xff) << 8) | ((d[off + 2] & 0xff) << 16) | ((d[off + 3] & 0xff) << 24);
        return Float.intBitsToFloat(bits);
    }

    private static float readShort(byte[] d, int off) {
        short v = (short) ((d[off] & 0xff) | ((d[off + 1] & 0xff) << 8));
        return v / 32768f;
    }

    private static short toShort(float v) {
        if (v > 1f) v = 1f;
        if (v < -1f) v = -1f;
        return (short) (v * 32767f);
    }

    private static final class PcmFrame {
        final byte[] data;
        final int sampleRate;
        final int channels;
        final boolean isFloat;

        PcmFrame(byte[] data, int sampleRate, int channels, boolean isFloat) {
            this.data = data;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.isFloat = isFloat;
        }
    }
}

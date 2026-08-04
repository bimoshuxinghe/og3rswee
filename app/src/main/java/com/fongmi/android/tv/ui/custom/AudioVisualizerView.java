package com.fongmi.android.tv.ui.custom;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.audiofx.Visualizer;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class AudioVisualizerView extends View {

    private static final int BAR_COUNT = 48;
    private static final int MAX_BAR_HEIGHT_DP = 70;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] magnitudes = new float[BAR_COUNT];
    private final float[] targetMagnitudes = new float[BAR_COUNT];
    private Visualizer visualizer;
    private int audioSessionId = -1;
    private boolean isActive = false;
    private float maxBarHeight;
    private int barColor1 = Color.parseColor("#6750A4");
    private int barColor2 = Color.parseColor("#FFD700");
    private float barWidth;
    private float barSpacing;
    private float cornerRadius;

    // Fallback animation state
    private long lastDataTime = 0;
    private long frameCount = 0;
    private boolean usingFallback = false;
    private static final long DATA_TIMEOUT_MS = 300;

    public AudioVisualizerView(Context context) {
        this(context, null);
    }

    public AudioVisualizerView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AudioVisualizerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        maxBarHeight = dpToPx(MAX_BAR_HEIGHT_DP);
        cornerRadius = dpToPx(2);
        paint.setStyle(Paint.Style.FILL);
        paint.setAntiAlias(true);
        setWillNotDraw(false);
    }

    private float dpToPx(int dp) {
        return dp * getResources().getDisplayMetrics().density;
    }

    public void setColors(int color1, int color2) {
        this.barColor1 = color1;
        this.barColor2 = color2;
        updateGradient();
        invalidate();
    }

    private void updateGradient() {
        if (getWidth() > 0 && getHeight() > 0) {
            paint.setShader(new LinearGradient(0, getHeight(), 0, getHeight() - maxBarHeight,
                    barColor1, barColor2, Shader.TileMode.CLAMP));
        }
    }

    public void setAudioSessionId(int sessionId) {
        // If same session and visualizer still alive, just re-enable
        if (sessionId == audioSessionId && visualizer != null) {
            try {
                if (!visualizer.getEnabled()) visualizer.setEnabled(true);
            } catch (Exception e) {
                e.printStackTrace();
                // Re-create if re-enable fails
                release();
                audioSessionId = sessionId;
                createVisualizer(sessionId);
            }
            return;
        }
        release();
        audioSessionId = sessionId;
        if (sessionId < 0) return;
        createVisualizer(sessionId);
    }

    private void createVisualizer(int sessionId) {
        try {
            visualizer = new Visualizer(sessionId);
            visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[0]);
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                    processWaveform(waveform);
                    lastDataTime = System.currentTimeMillis();
                    usingFallback = false;
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    processFft(fft);
                    lastDataTime = System.currentTimeMillis();
                    usingFallback = false;
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, true);
            visualizer.setEnabled(true);
        } catch (Exception e) {
            e.printStackTrace();
            // Visualizer failed (likely permission), will use fallback animation
        }
    }

    private void processWaveform(byte[] waveform) {
        int samplesPerBar = waveform.length / BAR_COUNT;
        if (samplesPerBar <= 0) samplesPerBar = 1;
        for (int i = 0; i < BAR_COUNT; i++) {
            float sum = 0;
            for (int j = 0; j < samplesPerBar; j++) {
                int idx = i * samplesPerBar + j;
                if (idx < waveform.length) {
                    float v = (waveform[idx] & 0xFF) - 128;
                    sum += Math.abs(v);
                }
            }
            targetMagnitudes[i] = (sum / samplesPerBar) / 128f;
        }
    }

    private void processFft(byte[] fft) {
        int usableBins = (fft.length / 2) - 1;
        int binsPerBar = usableBins / BAR_COUNT;
        if (binsPerBar <= 0) binsPerBar = 1;
        for (int i = 0; i < BAR_COUNT; i++) {
            float max = 0;
            for (int j = 0; j < binsPerBar; j++) {
                int binIdx = (i * binsPerBar + j + 1) * 2;
                if (binIdx + 1 < fft.length) {
                    float real = fft[binIdx];
                    float imag = fft[binIdx + 1];
                    float magnitude = (float) Math.sqrt(real * real + imag * imag);
                    if (magnitude > max) max = magnitude;
                }
            }
            targetMagnitudes[i] = Math.min(1f, max / 100f);
        }
    }

    /**
     * Generate pseudo-random bar heights for fallback animation.
     * Simulates music-like rhythmic pulsing with varied patterns.
     */
    private void generateFallbackData() {
        frameCount++;
        float t = frameCount * 0.12f;
        for (int i = 0; i < BAR_COUNT; i++) {
            // Multiple sine waves at different frequencies for natural variation
            float wave1 = 0.30f * (float) (Math.sin(t + i * 0.25) * 0.5 + 0.5);
            float wave2 = 0.25f * (float) (Math.sin(t * 1.7 + i * 0.45) * 0.5 + 0.5);
            float wave3 = 0.15f * (float) (Math.sin(t * 2.3 + i * 0.7) * 0.5 + 0.5);
            // Random-ish jitter for organic feel
            float jitter = 0.10f * (float) (Math.sin(t * 3.1 + i * 1.3) * Math.cos(t * 0.9 + i * 0.6));
            // Center emphasis like real music spectrum
            float centerBoost = 1f - Math.abs(i - BAR_COUNT / 2f) / (BAR_COUNT / 2f);
            centerBoost = 0.4f + centerBoost * 0.6f;
            // Beat pulse - simulates bass drum
            float beat = 0.15f * (float) Math.max(0, Math.sin(t * 2.0));
            targetMagnitudes[i] = (wave1 + wave2 + wave3 + jitter + beat) * centerBoost;
            targetMagnitudes[i] = Math.min(0.95f, Math.max(0.08f, targetMagnitudes[i]));
        }
    }

    public void start() {
        if (visualizer != null && !visualizer.getEnabled()) {
            try {
                visualizer.setEnabled(true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        isActive = true;
        lastDataTime = 0;
        frameCount = 0;
        invalidate();
    }

    public void stop() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        isActive = false;
        for (int i = 0; i < BAR_COUNT; i++) targetMagnitudes[i] = 0;
        invalidate();
    }

    public void release() {
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
                visualizer.release();
            } catch (Exception e) {
                e.printStackTrace();
            }
            visualizer = null;
        }
        isActive = false;
        audioSessionId = -1;
        usingFallback = false;
        for (int i = 0; i < BAR_COUNT; i++) {
            targetMagnitudes[i] = 0;
            magnitudes[i] = 0;
        }
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        barWidth = (w * 0.8f) / BAR_COUNT;
        barSpacing = (w * 0.2f) / (BAR_COUNT - 1);
        updateGradient();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        // Check if we need fallback animation (no real data received)
        if (isActive) {
            if (lastDataTime == 0 || (System.currentTimeMillis() - lastDataTime > DATA_TIMEOUT_MS)) {
                usingFallback = true;
                generateFallbackData();
            }
        }

        // Smooth interpolation
        for (int i = 0; i < BAR_COUNT; i++) {
            magnitudes[i] += (targetMagnitudes[i] - magnitudes[i]) * 0.25f;
        }

        float startY = height;
        float x = width * 0.1f;

        for (int i = 0; i < BAR_COUNT; i++) {
            float barHeight = magnitudes[i] * maxBarHeight;
            if (barHeight < dpToPx(2)) barHeight = dpToPx(2);

            float left = x + i * (barWidth + barSpacing);
            float right = left + barWidth;
            float top = startY - barHeight;

            if (cornerRadius > 0) {
                canvas.drawRoundRect(left, top, right, startY, cornerRadius, cornerRadius, paint);
            } else {
                canvas.drawRect(left, top, right, startY, paint);
            }
        }

        if (isActive) {
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (visualizer != null && isActive) {
            try {
                visualizer.setEnabled(true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (visualizer != null) {
            try {
                visualizer.setEnabled(false);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}

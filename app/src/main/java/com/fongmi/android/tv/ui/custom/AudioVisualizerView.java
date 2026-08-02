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
    private static final int MAX_BAR_HEIGHT_DP = 60;

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
        if (sessionId == audioSessionId) return;
        release();
        audioSessionId = sessionId;
        if (sessionId < 0) return;
        try {
            visualizer = new Visualizer(sessionId);
            visualizer.setCaptureSize(Visualizer.getCaptureSizeRange()[0]);
            visualizer.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override
                public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
                    processWaveform(waveform);
                }

                @Override
                public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
                    processFft(fft);
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, true);
            visualizer.setEnabled(true);
            isActive = true;
        } catch (Exception e) {
            e.printStackTrace();
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
        // FFT data: bytes[0]=real(0), bytes[1]=imag(0), bytes[2]=real(1), etc.
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
            // Normalize to 0-1 range (byte values are -128 to 127, magnitude max ~181)
            targetMagnitudes[i] = Math.min(1f, max / 100f);
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

package com.fongmi.android.tv.ads;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fongmi.android.tv.player.PlayerManager;

/**
 * 智能广告检测器 - 基于音频突变检测
 * 使用滑动窗口分析音频特征，检测广告与正文之间的突然变化
 */
public class SmartAdDetector {

    private static final String TAG = "SmartAdDetector";
    
    private static final int SAMPLE_RATE = 44100;
    private static final int BUFFER_SIZE = 4096;
    private static final int HISTORY_SIZE = 10;
    private static final float ENERGY_JUMP_THRESHOLD = 3.0f;
    private static final float SPECTRAL_CENTROID_JUMP = 500.0f;
    private static final int CONFIRM_FRAMES = 3;
    private static final long AD_SKIP_DELAY_MS = 2000;
    private static final long AD_DURATIONS[] = {15000, 30000, 60000};
    
    private AudioRecord audioRecord;
    private Thread analysisThread;
    private volatile boolean isRunning;
    private volatile boolean isAdDetected;
    private Handler mainHandler;
    private PlayerManager playerManager;
    
    private float[] historyEnergy;
    private float[] historySpectralCentroid;
    private int historyIndex;
    private int confirmCount;
    private long adStartTime;
    
    public SmartAdDetector(PlayerManager playerManager) {
        this.playerManager = playerManager;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.historyEnergy = new float[HISTORY_SIZE];
        this.historySpectralCentroid = new float[HISTORY_SIZE];
        this.historyIndex = 0;
        this.confirmCount = 0;
    }
    
    public void start() {
        if (isRunning) return;
        
        isRunning = true;
        isAdDetected = false;
        confirmCount = 0;
        
        int bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ) * 2;
        
        audioRecord = new AudioRecord(
            MediaRecorder.AudioSource.DEFAULT,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        );
        
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed");
            isRunning = false;
            return;
        }
        
        audioRecord.startRecording();
        analysisThread = new Thread(this::analyzeAudio, "ad-detector");
        analysisThread.start();
        
        Log.d(TAG, "Ad detection started");
    }
    
    public void stop() {
        isRunning = false;
        if (analysisThread != null) {
            try {
                analysisThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
                audioRecord.release();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping audio record", e);
            }
            audioRecord = null;
        }
        Log.d(TAG, "Ad detection stopped");
    }
    
    public void release() {
        stop();
    }
    
    private void analyzeAudio() {
        short[] buffer = new short[BUFFER_SIZE];
        int framesRead;
        int baselineEnergy = 0;
        int baselineSpectral = 0;
        int frameCount = 0;
        
        while (isRunning) {
            framesRead = audioRecord.read(buffer, 0, BUFFER_SIZE);
            if (framesRead < 0) continue;
            
            float energy = calculateEnergy(buffer, framesRead);
            float spectralCentroid = calculateSpectralCentroid(buffer, framesRead);
            
            if (frameCount < HISTORY_SIZE) {
                baselineEnergy = (baselineEnergy * frameCount + (int)energy) / (frameCount + 1);
                baselineSpectral = (baselineSpectral * frameCount + (int)spectralCentroid) / (frameCount + 1);
                frameCount++;
                continue;
            }
            
            float energyJump = Math.abs(energy - baselineEnergy) / Math.max(baselineEnergy, 1);
            float spectralJump = Math.abs(spectralCentroid - baselineSpectral);
            
            if (energyJump > ENERGY_JUMP_THRESHOLD || spectralJump > SPECTRAL_CENTROID_JUMP) {
                confirmCount++;
                Log.d(TAG, "Audio anomaly detected: energyJump=" + energyJump 
                    + ", spectralJump=" + spectralJump + ", confirmCount=" + confirmCount);
                
                if (confirmCount >= CONFIRM_FRAMES && !isAdDetected) {
                    onAdDetected();
                }
            } else {
                confirmCount = Math.max(0, confirmCount - 1);
            }
            
            // 更新基线
            baselineEnergy = (int)((baselineEnergy * 0.9f) + (energy * 0.1f));
            baselineSpectral = (int)((baselineSpectral * 0.9f) + (spectralCentroid * 0.1f));
        }
    }
    
    private float calculateEnergy(short[] buffer, int length) {
        long sum = 0;
        for (int i = 0; i < length; i++) {
            sum += (long)buffer[i] * buffer[i];
        }
        return (float)sum / length;
    }
    
    private float calculateSpectralCentroid(short[] buffer, int length) {
        float sum = 0;
        float weightedSum = 0;
        for (int i = 0; i < length; i++) {
            float magnitude = Math.abs(buffer[i]);
            sum += magnitude;
            weightedSum += magnitude * i;
        }
        return sum > 0 ? weightedSum / sum : 0;
    }
    
    private void onAdDetected() {
        if (isAdDetected) return;
        isAdDetected = true;
        adStartTime = System.currentTimeMillis();
        
        Log.d(TAG, "Ad detected! Will skip after delay...");
        
        mainHandler.postDelayed(() -> {
            skipAd();
        }, AD_SKIP_DELAY_MS);
    }
    
    private void skipAd() {
        if (playerManager == null) return;
        
        long currentPosition = playerManager.getCurrentPosition();
        long estimatedAdDuration = estimateAdDuration();
        long skipPosition = currentPosition + estimatedAdDuration;
        
        Log.d(TAG, "Skipping ad: current=" + currentPosition 
            + ", estimatedDuration=" + estimatedAdDuration 
            + ", target=" + skipPosition);
        
        playerManager.seekTo(skipPosition);
        
        isAdDetected = false;
        confirmCount = 0;
    }
    
    private long estimateAdDuration() {
        long playedDuration = System.currentTimeMillis() - adStartTime;
        
        for (long duration : AD_DURATIONS) {
            if (playedDuration >= duration * 0.5f) {
                return duration;
            }
        }
        return 30000;
    }
    
    public boolean isAdDetected() {
        return isAdDetected;
    }
    
    public void onAdEnded() {
        isAdDetected = false;
        confirmCount = 0;
        adStartTime = 0;
        Log.d(TAG, "Ad ended");
    }
}

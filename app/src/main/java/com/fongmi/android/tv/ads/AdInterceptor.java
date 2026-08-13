package com.fongmi.android.tv.ads;

import android.content.Context;
import android.util.Log;

import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.setting.Setting;

/**
 * 播放器广告拦截器
 * 使用 SmartAdDetector 进行智能广告检测
 */
public class AdInterceptor implements PlayerEngine.AdListener {

    private static final String TAG = "AdInterceptor";
    
    private final Context context;
    private final SmartAdDetector detector;
    private PlayerManager playerManager;
    
    public AdInterceptor(Context context, PlayerManager playerManager) {
        this.context = context;
        this.playerManager = playerManager;
        this.detector = new SmartAdDetector(playerManager);
    }
    
    @Override
    public void onPlayStateChanged(boolean playing) {
        if (playing && Setting.isAdblock()) {
            detector.start();
        } else {
            detector.stop();
        }
    }
    
    @Override
    public void onVideoSizeChanged(int width, int height) {
        if (detector.isAdDetected()) {
            detector.onAdEnded();
        }
    }
    
    @Override
    public void onIsPlayingChanged(boolean isPlaying) {
        if (!isPlaying && detector.isAdDetected()) {
            Log.d(TAG, "Playback paused during ad detection");
        }
    }
    
    @Override
    public void onTimelineChanged() {
        if (detector.isAdDetected()) {
            detector.onAdEnded();
        }
    }
    
    public void release() {
        detector.release();
    }
}

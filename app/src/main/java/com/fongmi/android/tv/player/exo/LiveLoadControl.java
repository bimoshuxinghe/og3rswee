package com.fongmi.android.tv.player.exo;

import androidx.media3.common.C;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.PlayerId;
import androidx.media3.exoplayer.source.MediaPeriodId;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.DefaultAllocator;

/**
 * 直播感知缓冲策略：在点播缓冲与直播小缓冲之间实时切换。
 * <p>
 * 为什么需要它：ExoPlayer 的 {@link LoadControl} 只能在创建播放器时设置一次，
 * 而直播/点播是在起播那一刻才知道的。官方 {@link DefaultLoadControl#DEFAULT_MIN_BUFFER_MS}
 * 是 50 秒，本项目原来还会按用户倍率放大（默认 ×2 → 预缓冲 100 秒）。
 * 高码率直播（8Mbps 左右）要凑够 100 秒缓冲得下将近 100MB，
 * 于是用户看到的就是「一直转圈缓冲」——直播是实时流，缓冲目标越追越落后。
 * <p>
 * 这里不重建播放器实例（重建会让宿主持有的 Player / PlayerView 引用失效导致黑屏），
 * 而是持两套 DefaultLoadControl，按当前是否在播直播来转发调用。
 * 两套策略共享同一个 {@link Allocator}，切换时不会出现 buffer 归属错乱。
 */
public final class LiveLoadControl implements LoadControl {

    private final DefaultLoadControl vod;
    private final DefaultLoadControl live;
    private final Allocator allocator;
    private volatile boolean liveMode;

    public LiveLoadControl(DefaultLoadControl vod, DefaultLoadControl live, Allocator allocator) {
        this.vod = vod;
        this.live = live;
        this.allocator = allocator;
    }

    /** 切换到直播/点播缓冲档位；起播前后调用都安全，下一次加载判定立即生效 */
    public void setLive(boolean live) {
        this.liveMode = live;
    }

    public boolean isLive() {
        return liveMode;
    }

    private LoadControl cur() {
        return liveMode ? live : vod;
    }

    @Override
    public Allocator getAllocator(PlayerId playerId) {
        return allocator;
    }

    @Override
    public void onPrepared(PlayerId playerId) {
        cur().onPrepared(playerId);
    }

    @Override
    public void onTracksSelected(Parameters parameters, TrackGroupArray trackGroups, ExoTrackSelection[] trackSelections) {
        cur().onTracksSelected(parameters, trackGroups, trackSelections);
    }

    @Override
    public void onStopped(PlayerId playerId) {
        cur().onStopped(playerId);
    }

    @Override
    public void onReleased(PlayerId playerId) {
        vod.onReleased(playerId);
        live.onReleased(playerId);
    }

    @Override
    public long getBackBufferDurationUs(PlayerId playerId) {
        return cur().getBackBufferDurationUs(playerId);
    }

    @Override
    public boolean retainBackBufferFromKeyframe(PlayerId playerId) {
        return cur().retainBackBufferFromKeyframe(playerId);
    }

    @Override
    public boolean shouldContinueLoading(Parameters parameters) {
        return cur().shouldContinueLoading(parameters);
    }

    @Override
    public boolean shouldContinuePreloading(PlayerId playerId, Timeline timeline, MediaPeriodId mediaPeriodId, long bufferedDurationUs) {
        return cur().shouldContinuePreloading(playerId, timeline, mediaPeriodId, bufferedDurationUs);
    }

    @Override
    public boolean shouldStartPlayback(Parameters parameters) {
        return cur().shouldStartPlayback(parameters);
    }

    /** 直播专用：小缓冲 + 起播门槛，避免实时流追缓冲导致的无限转圈 */
    public static DefaultLoadControl buildLive(DefaultAllocator allocator) {
        return new DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(15_000, 30_000, 2_500, 5_000)
                .build();
    }

    /** 点播：沿用官方默认档位 × 用户倍率（起播门槛保持项目原有的 500ms / 1500ms） */
    public static DefaultLoadControl buildVod(DefaultAllocator allocator, int factor) {
        int f = Math.max(factor, 1);
        return new DefaultLoadControl.Builder()
                .setAllocator(allocator)
                .setBufferDurationsMs(
                        DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * f,
                        DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * f,
                        500,
                        1500)
                .build();
    }

    public static DefaultAllocator newAllocator() {
        return new DefaultAllocator(true, C.DEFAULT_BUFFER_SEGMENT_SIZE);
    }
}

package com.fongmi.android.tv.player.mpv;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.TextureView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;

import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.Notify;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.net.HttpHeaders;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import is.xyz.mpv.MPVLib;

@UnstableApi
public final class MpvSimplePlayer extends SimpleBasePlayer implements MPVLib.EventObserver, MPVLib.LogObserver {

    private static final String TAG = "MpvSimplePlayer";
    private static Throwable availabilityError;

    private static final Player.Commands COMMANDS = new Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_RELEASE)
            .add(Player.COMMAND_SET_MEDIA_ITEM)
            .add(Player.COMMAND_CHANGE_MEDIA_ITEMS)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TIMELINE)
            .add(Player.COMMAND_GET_METADATA)
            .add(Player.COMMAND_GET_TRACKS)
            .add(Player.COMMAND_GET_VOLUME)
            .add(Player.COMMAND_SET_VOLUME)
            .add(Player.COMMAND_SET_SPEED_AND_PITCH)
            .add(Player.COMMAND_SET_VIDEO_SURFACE)
            .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_BACK)
            .add(Player.COMMAND_SEEK_FORWARD)
            .build();

    private final Context context;
    private final Handler handler;
    private MediaItem mediaItem;
    private PlaybackParameters playbackParameters;
    private PlaybackException playerError;
    private Tracks currentTracks;
    private VideoSize videoSize;
    private Surface textureSurface;
    private Surface currentSurface;
    private Object currentVideoOutput;
    private SurfaceHolder currentSurfaceHolder;
    private TextureView currentTextureView;
    private SurfaceHolder.Callback surfaceCallback;
    private TextureView.SurfaceTextureListener textureListener;
    private boolean playWhenReady;
    private boolean initialized;
    private boolean released;
    private boolean loading;
    private boolean renderedFirstFrame;
    private boolean reportRenderedFirstFrame;
    private boolean externalSubtitlesAdded;
    private boolean passthroughEnabled;
    private boolean passthroughRecoveryAttempted;
    private boolean hlsAbortRetryAttempted;
    private boolean audioOnlyFallback;
    private boolean manualStop;
    private boolean ignoreNextEndFile;
    private boolean loadedFileActive;
    private float volume;
    private int playbackState;
    private int decode;
    private double subtitleScale;
    private double subtitlePosition;
    private long textOffsetMs;
    private long audioOffsetMs;
    private long pendingInitialSeekMs;
    private long pendingStartPositionMs;
    private long durationMs;
    private long positionMs;
    private long bufferedPositionMs;
    private int currentSurfaceWidth;
    private int currentSurfaceHeight;
    private String activeLoadUrl;
    private String lastErrorMessage;
    private String lastErrorUrl;

    public MpvSimplePlayer(Context context, int decode) {
        super(Looper.getMainLooper());
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
        this.playbackParameters = PlaybackParameters.DEFAULT;
        this.currentTracks = Tracks.EMPTY;
        this.videoSize = VideoSize.UNKNOWN;
        this.playWhenReady = true;
        this.volume = 1.0f;
        this.subtitleScale = com.fongmi.android.tv.setting.PlayerSetting.getMpvSubtitleScale();
        this.subtitlePosition = com.fongmi.android.tv.setting.PlayerSetting.getMpvSubtitlePosition();
        this.playbackState = Player.STATE_IDLE;
        this.durationMs = C.TIME_UNSET;
        this.bufferedPositionMs = C.TIME_UNSET;
        this.pendingInitialSeekMs = C.TIME_UNSET;
        this.pendingStartPositionMs = C.TIME_UNSET;
        this.decode = decode;
        initialize();
    }

    public static boolean isAvailable() {
        try {
            Class.forName("is.xyz.mpv.MPVLib");
            availabilityError = null;
            return true;
        } catch (Throwable e) {
            availabilityError = e;
            Log.e(TAG, "MPV native library unavailable", e);
            return false;
        }
    }

    public static String getAvailabilityError() {
        Throwable error = availabilityError;
        if (error == null) return "";
        String message = error.getMessage();
        return TextUtils.isEmpty(message) ? error.getClass().getSimpleName() : message;
    }

    public void setDecode(int decode) {
        this.decode = decode;
        applyDecodeOption();
        if (initialized && mediaItem != null && playbackState != Player.STATE_IDLE) loadMediaItem(positionMs, true);
    }

    public void setTrack(List<com.fongmi.android.tv.bean.Track> tracks) {
        for (com.fongmi.android.tv.bean.Track track : tracks) {
            String[] parts = parseTrackFormat(track.getFormat());
            if (parts == null) continue;
            setMpvProperty(getTrackProperty(track.getType()), track.isSelected() ? parts[2] : "no");
        }
        buildTracks();
        invalidateState();
    }

    public void resetTrack() {
        setMpvProperty("aid", "auto");
        setMpvProperty("sid", "auto");
        setMpvProperty("vid", "auto");
        buildTracks();
        invalidateState();
    }

    public long getTextOffsetMs() {
        return textOffsetMs;
    }

    public void setTextOffsetMs(long offsetMs) {
        textOffsetMs = offsetMs;
        setMpvProperty("sub-delay", offsetMs / 1000.0);
    }

    public long getAudioOffsetMs() {
        return audioOffsetMs;
    }

    public void setAudioOffsetMs(long offsetMs) {
        audioOffsetMs = offsetMs;
        setMpvProperty("audio-delay", offsetMs / 1000.0);
    }

    public void addSubtitleSize() {
        setSubtitleScale(subtitleScale + 0.05);
    }

    public void subSubtitleSize() {
        setSubtitleScale(subtitleScale - 0.05);
    }

    public void addSubtitlePosition() {
        setSubtitlePosition(subtitlePosition - 2.0);
    }

    public void subSubtitlePosition() {
        setSubtitlePosition(subtitlePosition + 2.0);
    }

    public void resetSubtitleStyle() {
        setSubtitleScale(1.0);
        setSubtitlePosition(100.0);
    }

    @Override
    protected State getState() {
        int safePlaybackState = playerError == null ? playbackState : Player.STATE_IDLE;
        State.Builder builder = new State.Builder()
                .setAvailableCommands(COMMANDS)
                .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(mediaItem == null ? Player.STATE_IDLE : safePlaybackState)
                .setIsLoading(playerError == null && loading && safePlaybackState != Player.STATE_IDLE && safePlaybackState != Player.STATE_ENDED && mediaItem != null)
                .setPlaybackParameters(playbackParameters)
                .setVolume(volume)
                .setVideoSize(videoSize)
                .setSurfaceSize(getCurrentSurfaceSize())
                .setNewlyRenderedFirstFrame(consumeRenderedFirstFrame());
        if (playerError != null) builder.setPlayerError(playerError);
        if (mediaItem != null) {
            builder.setPlaylist(ImmutableList.of(buildMediaItemData()));
            builder.setCurrentMediaItemIndex(0);
            builder.setContentPositionMs(sanitizePosition(positionMs));
            builder.setContentBufferedPositionMs(PositionSupplier.getConstant(getBufferedPositionMs()));
            builder.setTotalBufferedDurationMs(PositionSupplier.getConstant(getTotalBufferedDurationMs()));
        }
        return builder.build();
    }

    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        this.playWhenReady = playWhenReady;
        setMpvProperty("pause", !playWhenReady);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        if (mediaItem == null) return Futures.immediateVoidFuture();
        loadMediaItem(pendingStartPositionMs, false);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        stopMpvPlayback(false);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRelease() {
        releaseInternal();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetPlaybackParameters(PlaybackParameters playbackParameters) {
        this.playbackParameters = playbackParameters;
        setMpvProperty("speed", (double) playbackParameters.speed);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVolume(float volume, @C.VolumeOperationType int volumeOperationType) {
        this.volume = Math.min(Math.max(volume, 0.0f), 1.0f);
        setMpvProperty("volume", this.volume * 100.0);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVideoOutput(Object videoOutput) {
        attachVideoOutput(videoOutput);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleClearVideoOutput(@Nullable Object videoOutput) {
        if (videoOutput == null || videoOutput == currentVideoOutput) detachVideoOutput();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetMediaItems(List<MediaItem> mediaItems, int startIndex, long startPositionMs) {
        mediaItem = mediaItems.isEmpty() ? null : mediaItems.get(Math.max(0, Math.min(startIndex == C.INDEX_UNSET ? 0 : startIndex, mediaItems.size() - 1)));
        pendingStartPositionMs = startPositionMs;
        resetMediaState();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleAddMediaItems(int index, List<MediaItem> mediaItems) {
        if (mediaItem == null && !mediaItems.isEmpty()) mediaItem = mediaItems.get(0);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleReplaceMediaItems(int fromIndex, int toIndex, List<MediaItem> mediaItems) {
        if (!mediaItems.isEmpty()) mediaItem = mediaItems.get(0);
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleRemoveMediaItems(int fromIndex, int toIndex) {
        mediaItem = null;
        stopMpvPlayback(false);
        resetMediaState();
        playbackState = Player.STATE_IDLE;
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, @Player.Command int seekCommand) {
        long target = positionMs == C.TIME_UNSET ? 0 : Math.max(0, positionMs);
        this.positionMs = target;
        if (playbackState == Player.STATE_BUFFERING && !renderedFirstFrame) pendingStartPositionMs = target;
        else command("seek", formatSeconds(target), "absolute", "exact");
        return Futures.immediateVoidFuture();
    }

    @Override
    public void eventProperty(String property) {
        if (isVideoSizeProperty(property)) updateVideoSize();
        if ("track-list".equals(property)) {
            handler.post(() -> {
                if (released) return;
                buildTracks();
                invalidateState();
            });
        }
        postInvalidate();
    }

    @Override
    public void eventProperty(String property, long value) {
        postProperty(property, (double) value);
    }

    @Override
    public void eventProperty(String property, boolean value) {
        handler.post(() -> {
            if (released) return;
            if ("pause".equals(property)) playWhenReady = !value;
            if ("eof-reached".equals(property) && value) {
                playbackState = Player.STATE_ENDED;
                loading = false;
            }
            if ("paused-for-cache".equals(property)) {
                if (value) {
                    playbackState = Player.STATE_BUFFERING;
                    loading = true;
                } else {
                    if (playbackState == Player.STATE_BUFFERING) {
                        playbackState = Player.STATE_READY;
                        loading = false;
                    }
                }
            }
            invalidateState();
        });
    }

    @Override
    public void eventProperty(String property, String value) {
        if (isVideoSizeProperty(property)) {
            handler.post(() -> {
                if (released) return;
                updateVideoSize();
                invalidateState();
            });
        }
    }

    @Override
    public void eventProperty(String property, double value) {
        postProperty(property, value);
    }

    @Override
    public void event(int eventId) {
        handler.post(() -> {
            if (released) return;
            if (eventId == MPVLib.MpvEvent.MPV_EVENT_START_FILE) {
                playerError = null;
                playbackState = Player.STATE_BUFFERING;
                loading = true;
                videoSize = VideoSize.UNKNOWN;
                manualStop = false;
                externalSubtitlesAdded = false;
                audioOnlyFallback = false;
                renderedFirstFrame = false;
                reportRenderedFirstFrame = false;
            } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED) {
                updateVideoSize();
                buildTracks();
                addExternalSubtitles();
                if (seekPendingInitialPosition()) {
                    playbackState = Player.STATE_BUFFERING;
                    loading = true;
                } else {
                    playbackState = Player.STATE_READY;
                    loading = false;
                }
                ignoreNextEndFile = false;
                loadedFileActive = true;
            } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART) {
                playerError = null;
                playbackState = Player.STATE_READY;
                loading = false;
                ignoreNextEndFile = false;
                loadedFileActive = true;
                updateVideoSize();
                buildTracks();
                markRenderedFirstFrame();
                hlsAbortRetryAttempted = false;
            } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_SEEK) {
                playbackState = Player.STATE_BUFFERING;
                loading = true;
            } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG) {
                updateVideoSize();
                buildTracks();
            } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_AUDIO_RECONFIG) {
                buildTracks();
            } else if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) {
                if (ignoreNextEndFile) {
                    ignoreNextEndFile = false;
                } else if (isStaleEndFileError()) {
                    return;
                } else if (manualStop || mediaItem == null) {
                    playbackState = Player.STATE_IDLE;
                } else if (loading) {
                    // 正在加载新流时收到的 END_FILE 是旧流被中断产生的，忽略不报错
                    playbackState = Player.STATE_BUFFERING;
                } else if (!manualStop && !renderedFirstFrame && !audioOnlyFallback && mediaItem != null) {
                    if (retryHlsAbortError()) return;
                    setError(lastErrorMessage == null ? "MPV 播放失败" : lastErrorMessage);
                }
                else playbackState = Player.STATE_ENDED;
                loadedFileActive = false;
            }
            invalidateState();
        });
    }

    @Override
    public void logMessage(String prefix, int level, String text) {
        if (TextUtils.isEmpty(text)) return;
        String value = text.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (level <= 20 || lower.contains("failed to open") || lower.contains("opening failed") || lower.contains("loading failed") || lower.contains("tls certificate")) {
            lastErrorMessage = "MPV: " + value;
            lastErrorUrl = extractErrorUrl(value);
        }
        if (passthroughEnabled && MpvAudioPassthrough.isFailureLog(value)) disablePassthroughAndReload();
    }

    private void initialize() {
        if (initialized) return;
        try {
            MPVLib.create(context);
            applyConfigOptions();
            setMpvOption("profile", "fast");
            applyRenderOptions();
            applyProbeOptions();
            applyDecodeOption();
            setMpvOption("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1");
            applyAudioOptions();
            applyHdrOptions();
            setMpvOption("tls-verify", "no");
            setMpvOption("ytdl", "no");
            // Increased demuxer buffer from 64MB to 128MB for better buffering
            // of high-bitrate streams and reduced buffering pauses
            setMpvOption("demuxer-max-bytes", "134217728");
            setMpvOption("demuxer-max-back-bytes", "67108864");
            // Enable cache for better seek performance and network resilience
            setMpvOption("cache", "yes");
            setMpvOption("cache-secs", "15");
            setMpvOption("cache-on-disk", "no");
            // Wait 2s when cache is low before pausing, prevents stutter on
            // brief network hiccups
            setMpvOption("cache-pause-wait", "2.0");
            // Demuxer readahead: read 20s ahead for smoother long streaming
            setMpvOption("demuxer-readahead-secs", "20");
            // Frame dropping: drop VO frames when behind to maintain sync
            setMpvOption("framedrop", "vo");
            // Correct audio pitch at non-1x playback speeds
            setMpvOption("audio-pitch-correction", "yes");
            // Correct PTS for better A/V sync on problematic streams
            setMpvOption("correct-pts", "yes");
            // Allow seeking on streams that don't explicitly declare seekable
            setMpvOption("force-seekable", "yes");
            // Network stream buffer size (256KB) for smoother I/O
            setMpvOption("stream-buffer-size", "262144");
            // Network timeout: 30s before giving up on stalled connections
            setMpvOption("network-timeout", "30");
            setMpvOption("idle", "yes");
            setMpvOption("force-window", "no");
            MPVLib.init();
            applySubtitleStyle();
            observeProperties();
            MPVLib.addObserver(this);
            MPVLib.addLogObserver(this);
            initialized = true;
        } catch (Throwable e) {
            playerError = new PlaybackException(e.getMessage(), e, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK);
            playbackState = Player.STATE_IDLE;
        }
    }

    private void applyConfigOptions() {
        if (PlayerSetting.hasMpvConfig()) {
            setMpvOption("config", "yes");
            setMpvOption("config-dir", PlayerSetting.getMpvConfigDir().getAbsolutePath());
        } else {
            setMpvOption("config", "no");
        }
    }

    private void observeProperties() {
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("duration/full", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("width", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        MPVLib.observeProperty("height", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        MPVLib.observeProperty("video-params", MPVLib.MpvFormat.MPV_FORMAT_NONE);
        MPVLib.observeProperty("video-out-params", MPVLib.MpvFormat.MPV_FORMAT_NONE);
        MPVLib.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE);
    }

    private void loadMediaItem(long startPositionMs) {
        loadMediaItem(startPositionMs, false);
    }

    private void loadMediaItem(long startPositionMs, boolean useStartOption) {
        if (mediaItem == null || mediaItem.localConfiguration == null) return;
        String url = mediaItem.localConfiguration.uri.toString();
        if (!TextUtils.equals(activeLoadUrl, url)) {
            activeLoadUrl = url;
            hlsAbortRetryAttempted = false;
        }
        applyHeaders(mediaItem);
        applyDecodeOption();
        positionMs = startPositionMs == C.TIME_UNSET ? 0 : Math.max(0, startPositionMs);
        pendingInitialSeekMs = positionMs > 0 && !useStartOption ? positionMs : C.TIME_UNSET;
        pendingStartPositionMs = C.TIME_UNSET;
        // 始终忽略下一个 END_FILE 事件：切换频道时旧流被 replace 中断不应视为错误
        ignoreNextEndFile = true;
        loadedFileActive = false;
        playbackState = Player.STATE_BUFFERING;
        loading = true;
        videoSize = VideoSize.UNKNOWN;
        playerError = null;
        lastErrorMessage = null;
        lastErrorUrl = null;
        invalidateState();
        applyOffsets();
        restoreVideoOutput();
        applyMediaOptions(url);
        String playableUrl = MpvMedia.getPlayableUrl(url);
        String options = getLoadOptions(positionMs, useStartOption, mediaItem, url);
        if (TextUtils.isEmpty(options)) command("loadfile", playableUrl, "replace");
        else command("loadfile", playableUrl, "replace", "-1", options);
        setMpvProperty("pause", !playWhenReady);
    }

    private boolean seekPendingInitialPosition() {
        if (pendingInitialSeekMs == C.TIME_UNSET || pendingInitialSeekMs <= 0) return false;
        long target = pendingInitialSeekMs;
        positionMs = target;
        command("seek", formatSeconds(target), "absolute", "exact");
        return true;
    }

    private void addExternalSubtitles() {
        if (externalSubtitlesAdded || mediaItem == null || mediaItem.localConfiguration == null) return;
        externalSubtitlesAdded = true;
        for (MediaItem.SubtitleConfiguration subtitle : mediaItem.localConfiguration.subtitleConfigurations) {
            String title = TextUtils.isEmpty(subtitle.label) ? subtitle.uri.toString() : subtitle.label;
            String language = TextUtils.isEmpty(subtitle.language) ? "und" : subtitle.language;
            String flag = (subtitle.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0 || (subtitle.selectionFlags & C.SELECTION_FLAG_FORCED) != 0 ? "select" : "auto";
            command("sub-add", subtitle.uri.toString(), flag, title, language);
        }
    }

    private void applyHeaders(MediaItem item) {
        Map<String, String> headers = ExoUtil.extractHeaders(item);
        String userAgent = null;
        StringBuilder fields = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (TextUtils.isEmpty(entry.getKey()) || TextUtils.isEmpty(entry.getValue())) continue;
            if (HttpHeaders.USER_AGENT.equalsIgnoreCase(entry.getKey())) userAgent = entry.getValue();
            if (fields.length() > 0) fields.append(',');
            fields.append(entry.getKey()).append(": ").append(entry.getValue().replace(",", "\\,"));
        }
        // 确保 UA 始终设置：若 headers 中没有 UA，使用默认 UA
        if (TextUtils.isEmpty(userAgent)) {
            String defaultUa = com.fongmi.android.tv.setting.Setting.getUa();
            if (TextUtils.isEmpty(defaultUa)) defaultUa = com.fongmi.android.tv.player.PlayerHelper.getDefaultUa();
            userAgent = defaultUa;
        }
        if (!TextUtils.isEmpty(userAgent)) setMpvOption("user-agent", userAgent);
        if (fields.length() > 0) setMpvOption("http-header-fields", fields.toString());
    }

    private void applyDecodeOption() {
        String value = decode == com.fongmi.android.tv.player.engine.PlayerEngine.HARD ? "mediacodec-copy" : "no";
        setMpvOption("hwdec", value);
        if (initialized) setMpvProperty("hwdec", value);
    }

    private void applyRenderOptions() {
        int render = PlayerSetting.getMpvRender();
        setMpvOption("vo", getMpvVo());
        if (render == 2) {
            setMpvOption("gpu-api", "vulkan");
            setMpvOption("gpu-context", "androidvk");
        } else {
            setMpvOption("gpu-api", "opengl");
            setMpvOption("gpu-context", "android");
            setMpvOption("opengl-es", "yes");
        }
    }

    private String getMpvVo() {
        int render = PlayerSetting.getMpvRender();
        return render == 0 ? "gpu" : "gpu-next";
    }

    private void applyProbeOptions() {
        setMpvOption("demuxer-lavf-probe-info", "yes");
        setMpvOption("demuxer-lavf-probesize", "10485760");
        setMpvOption("demuxer-lavf-analyzeduration", "10");
        setMpvOption("demuxer-lavf-allow-mimetype", "no");
    }

    private void applyMediaOptions(String url) {
        if (MpvMedia.isSpoofedSegment(url) || MpvMedia.isRadioAudio(url)) applyProbeOptions();
        String device = MpvMedia.getBluRayDevice(url);
        if (!TextUtils.isEmpty(device)) setMpvOption("bluray-device", device);
    }

    private String getLoadOptions(long positionMs, boolean useStartOption, MediaItem item, String url) {
        List<String> options = new ArrayList<>();
        if (positionMs > 0 && useStartOption) options.add("start=" + formatSeconds(positionMs));
        if (isHls(item, url)) {
            // HLS 直播流：强制 lavf demuxer + HLS 格式，快速起播
            options.add("demuxer=lavf");
            options.add("demuxer-lavf-format=hls");
            options.add("demuxer-lavf-probesize=32");
            options.add("demuxer-lavf-analyzeduration=0");
            options.add("cache=yes");
            options.add("cache-secs=10");
            options.add("keep-open=yes");
            options.add("keep-open-pause=no");
        } else if (MpvMedia.isAudioFile(url) || MpvMedia.isRadioAudio(url)) {
            // 音频文件：关闭视频轨，只保留音频
            options.add("demuxer=lavf");
            options.add("vid=no");
            options.add("aid=auto");
            options.add("keep-open=yes");
            options.add("keep-open-pause=no");
        } else {
            // 未知格式或视频文件：用 lavf demuxer 自动探测，同时允许纯音频播放
            options.add("demuxer=lavf");
            options.add("keep-open=yes");
            options.add("keep-open-pause=no");
            // 如果没有视频轨，自动切换为纯音频模式
            options.add("vid=auto");
            options.add("aid=auto");
        }
        return TextUtils.join(",", options);
    }

    private boolean isHls(MediaItem item, String url) {
        String mimeType = item.localConfiguration == null ? null : item.localConfiguration.mimeType;
        if (MimeTypes.APPLICATION_M3U8.equals(mimeType)) return true;
        if (!TextUtils.isEmpty(mimeType) && mimeType.toLowerCase(Locale.ROOT).contains("mpegurl")) return true;
        if (MpvMedia.isHls(MpvMedia.getPlayableUrl(url))) return true;
        // PHP 代理直播链接强制按 HLS 处理
        return MpvMedia.isPhpProxyStream(url);
    }

    private void applyAudioOptions() {
        setMpvOption("ao", "audiotrack,opensles");
        String formats = MpvAudioPassthrough.getSupportedFormats(context, PlayerSetting.isMpvAudioPassthrough(), PlayerSetting.isMpvDolbyPassthrough());
        passthroughEnabled = !TextUtils.isEmpty(formats);
        if (passthroughEnabled) {
            setMpvOption("audio-spdif", formats);
        } else {
            if (PlayerSetting.isMpvAudioPassthrough() || PlayerSetting.isMpvDolbyPassthrough()) {
                PlayerSetting.putMpvAudioPassthrough(false);
                PlayerSetting.putMpvDolbyPassthrough(false);
                handler.post(() -> Notify.show("当前设备不支持音频直通，已关闭"));
            }
            setMpvOption("audio-spdif", "");
        }
    }

    private void disablePassthroughAndReload() {
        handler.post(() -> {
            if (released || !passthroughEnabled || passthroughRecoveryAttempted) return;
            passthroughRecoveryAttempted = true;
            passthroughEnabled = false;
            PlayerSetting.putMpvAudioPassthrough(false);
            PlayerSetting.putMpvDolbyPassthrough(false);
            setMpvOption("audio-spdif", "");
            Notify.show("音频直通失败，已关闭并恢复播放");
            if (mediaItem != null && playbackState != Player.STATE_IDLE) loadMediaItem(positionMs, false);
        });
    }

    private void applyHdrOptions() {
        setMpvOption("target-colorspace-hint", "yes");
        setMpvOption("hdr-compute-peak", "yes");
        setMpvOption("tone-mapping", "auto");
    }

    private void applyOffsets() {
        setTextOffsetMs(textOffsetMs);
        setAudioOffsetMs(audioOffsetMs);
    }

    private void applySubtitleStyle() {
        setSubtitleScale(subtitleScale);
        setSubtitlePosition(subtitlePosition);
    }

    private void setSubtitleScale(double scale) {
        subtitleScale = Math.max(0.5, Math.min(scale, 3.0));
        setMpvProperty("sub-scale", subtitleScale);
        com.fongmi.android.tv.setting.PlayerSetting.putMpvSubtitleScale((float) subtitleScale);
    }

    private void setSubtitlePosition(double position) {
        subtitlePosition = Math.max(0.0, Math.min(position, 100.0));
        setMpvProperty("sub-pos", subtitlePosition);
        com.fongmi.android.tv.setting.PlayerSetting.putMpvSubtitlePosition((float) subtitlePosition);
    }

    private SimpleBasePlayer.MediaItemData buildMediaItemData() {
        long durationUs = durationMs == C.TIME_UNSET ? C.TIME_UNSET : Util.msToUs(durationMs);
        return new SimpleBasePlayer.MediaItemData.Builder("mpv")
                .setMediaItem(mediaItem)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .setTracks(currentTracks)
                .setIsSeekable(durationMs > 0)
                .setIsDynamic(durationMs == C.TIME_UNSET)
                .setDurationUs(durationUs)
                .build();
    }

    private void postProperty(String property, double value) {
        handler.post(() -> {
            if (released) return;
            if ("time-pos".equals(property)) {
                long position = secondsToMs(value);
                if (pendingInitialSeekMs != C.TIME_UNSET && position + 2000 < pendingInitialSeekMs) {
                    playbackState = Player.STATE_BUFFERING;
                    loading = true;
                    invalidateState();
                    return;
                }
                pendingInitialSeekMs = C.TIME_UNSET;
                positionMs = position;
                if (mediaItem != null && playerError == null && playbackState == Player.STATE_BUFFERING && value >= 0) {
                    markRenderedFirstFrame();
                    playbackState = Player.STATE_READY;
                    loading = false;
                }
            }
            else if ("duration/full".equals(property) || "duration".equals(property)) durationMs = secondsToMs(value);
            else if ("demuxer-cache-time".equals(property)) bufferedPositionMs = Math.max(positionMs, positionMs + secondsToMs(value));
            else if (isVideoSizeProperty(property)) updateVideoSize();
            invalidateState();
        });
    }

    private void updateVideoSize() {
        Integer width = firstPositiveInt("video-out-params/w", "video-params/w");
        Integer height = firstPositiveInt("video-out-params/h", "video-params/h");
        if (width == null || height == null) {
            Integer displayWidth = safeGetInt("video-out-params/dw");
            Integer displayHeight = safeGetInt("video-out-params/dh");
            if (displayWidth != null && displayHeight != null && displayWidth > 0 && displayHeight > 0) videoSize = new VideoSize(displayWidth, displayHeight);
            return;
        }
        double storageAspectRatio = width / (double) height;
        double displayAspectRatio = firstPositiveDouble("video-out-params/aspect", "video-params/aspect");
        if (displayAspectRatio <= 0) {
            Integer displayWidth = safeGetInt("video-out-params/dw");
            Integer displayHeight = safeGetInt("video-out-params/dh");
            if (displayWidth != null && displayHeight != null && displayWidth > 0 && displayHeight > 0) displayAspectRatio = displayWidth / (double) displayHeight;
        }
        float pixelWidthHeightRatio = displayAspectRatio > 0 && storageAspectRatio > 0 ? (float) (displayAspectRatio / storageAspectRatio) : 1.0f;
        videoSize = new VideoSize(width, height, Math.max(0.01f, pixelWidthHeightRatio));
    }

    private boolean isVideoSizeProperty(String property) {
        return "video-params".equals(property) || "video-out-params".equals(property);
    }

    @Nullable
    private Integer firstPositiveInt(String... properties) {
        for (String property : properties) {
            Integer value = safeGetInt(property);
            if (value != null && value > 0) return value;
        }
        return null;
    }

    private double firstPositiveDouble(String... properties) {
        for (String property : properties) {
            Double value = safeGetDouble(property);
            if (value != null && value > 0) return value;
        }
        return 0;
    }

    private void buildTracks() {
        Integer count = safeGetInt("track-list/count");
        if (count == null || count <= 0) {
            currentTracks = Tracks.EMPTY;
            return;
        }
        List<Tracks.Group> groups = new ArrayList<>();
        boolean hasAudio = false;
        boolean hasVideo = false;
        for (int index = 0; index < count; index++) {
            Integer mpvId = safeGetInt("track-list/" + index + "/id");
            String type = safeGetString("track-list/" + index + "/type");
            int trackType = getTrackType(type);
            if (mpvId == null || mpvId <= 0 || trackType == C.TRACK_TYPE_UNKNOWN) continue;
            if (trackType == C.TRACK_TYPE_AUDIO) hasAudio = true;
            if (trackType == C.TRACK_TYPE_VIDEO) hasVideo = true;
            Format format = buildFormat(index, mpvId, trackType);
            boolean selected = Boolean.TRUE.equals(safeGetBoolean("track-list/" + index + "/selected"));
            groups.add(new Tracks.Group(new TrackGroup("mpv-" + type + "-" + mpvId, format), false, new int[]{C.FORMAT_HANDLED}, new boolean[]{selected}));
        }
        currentTracks = groups.isEmpty() ? Tracks.EMPTY : new Tracks(groups);
        applyAudioOnlyFallback(hasAudio, hasVideo);
    }

    private void applyAudioOnlyFallback(boolean hasAudio, boolean hasVideo) {
        audioOnlyFallback = hasAudio && !hasVideo;
        if (!audioOnlyFallback) return;
        videoSize = VideoSize.UNKNOWN;
        setMpvProperty("vid", "no");
        setMpvProperty("aid", "auto");
        if (playerError == null && playbackState == Player.STATE_BUFFERING) {
            renderedFirstFrame = true;
            reportRenderedFirstFrame = false;
            playbackState = Player.STATE_READY;
            loading = false;
        }
    }

    private Format buildFormat(int index, int mpvId, int trackType) {
        String codec = safeGetString("track-list/" + index + "/codec");
        Format.Builder builder = new Format.Builder()
                .setId("mpv:" + trackType + ":" + mpvId)
                .setLabel(getTrackLabel(index, mpvId, trackType))
                .setLanguage(safeGetString("track-list/" + index + "/lang"))
                .setCodecs(codec)
                .setSampleMimeType(getSampleMimeType(trackType, codec));
        Integer width = safeGetInt("track-list/" + index + "/demux-w");
        if (width == null || width <= 0) width = safeGetInt("track-list/" + index + "/w");
        Integer height = safeGetInt("track-list/" + index + "/demux-h");
        if (height == null || height <= 0) height = safeGetInt("track-list/" + index + "/h");
        Integer channels = safeGetInt("track-list/" + index + "/demux-channel-count");
        if (channels == null || channels <= 0) channels = safeGetInt("track-list/" + index + "/channel-count");
        Integer sampleRate = safeGetInt("track-list/" + index + "/demux-samplerate");
        if (sampleRate == null || sampleRate <= 0) sampleRate = safeGetInt("track-list/" + index + "/samplerate");
        if (width != null && width > 0) builder.setWidth(width);
        if (height != null && height > 0) builder.setHeight(height);
        if (channels != null && channels > 0) builder.setChannelCount(channels);
        if (sampleRate != null && sampleRate > 0) builder.setSampleRate(sampleRate);
        return builder.build();
    }

    private String getTrackLabel(int index, int mpvId, int trackType) {
        String title = safeGetString("track-list/" + index + "/title");
        String codec = safeGetString("track-list/" + index + "/codec");
        String lang = safeGetString("track-list/" + index + "/lang");
        String prefix;
        if (trackType == C.TRACK_TYPE_AUDIO) prefix = "音轨";
        else if (trackType == C.TRACK_TYPE_TEXT) prefix = "字幕";
        else prefix = "视轨";
        StringBuilder builder = new StringBuilder(prefix).append(' ').append(mpvId);
        if (!TextUtils.isEmpty(title)) {
            builder.append(" - ").append(title);
        } else {
            if (!TextUtils.isEmpty(lang) && !"und".equals(lang)) builder.append(" [").append(lang).append("]");
            if (!TextUtils.isEmpty(codec)) builder.append(" ").append(codec.toUpperCase(Locale.ROOT));
            if (trackType == C.TRACK_TYPE_VIDEO) {
                Integer w = safeGetInt("track-list/" + index + "/demux-w");
                if (w == null || w <= 0) w = safeGetInt("track-list/" + index + "/w");
                Integer h = safeGetInt("track-list/" + index + "/demux-h");
                if (h == null || h <= 0) h = safeGetInt("track-list/" + index + "/h");
                if (w != null && w > 0 && h != null && h > 0) builder.append(" ").append(w).append("x").append(h);
                Integer fps = safeGetInt("track-list/" + index + "/demux-fps");
                if (fps == null || fps <= 0) fps = safeGetInt("track-list/" + index + "/fps");
                if (fps != null && fps > 0) builder.append(" ").append(String.format(java.util.Locale.ROOT, "%.0f", (double) fps)).append("fps");
                Integer bps = safeGetInt("track-list/" + index + "/demux-bitrate");
                if (bps != null && bps > 0) builder.append(" ").append(bps / 1000).append("kbps");
            } else if (trackType == C.TRACK_TYPE_AUDIO) {
                Integer ch = safeGetInt("track-list/" + index + "/demux-channel-count");
                if (ch == null || ch <= 0) ch = safeGetInt("track-list/" + index + "/channel-count");
                if (ch != null && ch > 0) builder.append(" ").append(ch).append("ch");
                Integer sr = safeGetInt("track-list/" + index + "/demux-samplerate");
                if (sr == null || sr <= 0) sr = safeGetInt("track-list/" + index + "/samplerate");
                if (sr != null && sr > 0) builder.append(" ").append(sr / 1000).append("kHz");
                Integer bps = safeGetInt("track-list/" + index + "/demux-bitrate");
                if (bps != null && bps > 0) builder.append(" ").append(bps / 1000).append("kbps");
            }
        }
        return builder.toString();
    }

    private String getSampleMimeType(int trackType, String codec) {
        if (trackType == C.TRACK_TYPE_VIDEO) return MimeTypes.VIDEO_UNKNOWN;
        if (trackType == C.TRACK_TYPE_AUDIO) return MimeTypes.AUDIO_UNKNOWN;
        if ("ass".equalsIgnoreCase(codec) || "ssa".equalsIgnoreCase(codec)) return MimeTypes.TEXT_SSA;
        if ("webvtt".equalsIgnoreCase(codec) || "vtt".equalsIgnoreCase(codec)) return MimeTypes.TEXT_VTT;
        if ("subrip".equalsIgnoreCase(codec) || "srt".equalsIgnoreCase(codec)) return MimeTypes.APPLICATION_SUBRIP;
        return MimeTypes.TEXT_UNKNOWN;
    }

    private int getTrackType(String type) {
        if ("audio".equals(type)) return C.TRACK_TYPE_AUDIO;
        if ("sub".equals(type)) return C.TRACK_TYPE_TEXT;
        if ("video".equals(type)) return C.TRACK_TYPE_VIDEO;
        return C.TRACK_TYPE_UNKNOWN;
    }

    @Nullable
    private String[] parseTrackFormat(String format) {
        if (TextUtils.isEmpty(format)) return null;
        String id = format.split(",", 2)[0];
        String[] parts = id.split(":", 3);
        return parts.length == 3 && "mpv".equals(parts[0]) ? parts : null;
    }

    private String getTrackProperty(int type) {
        if (type == C.TRACK_TYPE_AUDIO) return "aid";
        if (type == C.TRACK_TYPE_TEXT) return "sid";
        if (type == C.TRACK_TYPE_VIDEO) return "vid";
        return "";
    }

    private void attachVideoOutput(Object videoOutput) {
        detachVideoOutput();
        currentVideoOutput = videoOutput;
        if (videoOutput instanceof Surface surface) {
            attachSurface(surface, 0, 0);
        } else if (videoOutput instanceof SurfaceHolder holder) {
            attachSurfaceHolder(holder);
        } else if (videoOutput instanceof SurfaceView view) {
            attachSurfaceHolder(view.getHolder());
        } else if (videoOutput instanceof TextureView view) {
            attachTextureView(view);
        }
    }

    private void attachSurfaceHolder(SurfaceHolder holder) {
        currentSurfaceHolder = holder;
        surfaceCallback = new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                attachSurface(holder.getSurface(), holder.getSurfaceFrame().width(), holder.getSurfaceFrame().height());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                setSurfaceSize(width, height);
                attachSurface(holder.getSurface(), width, height);
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                detachSurface();
            }
        };
        holder.addCallback(surfaceCallback);
        if (holder.getSurface() != null && holder.getSurface().isValid()) {
            attachSurface(holder.getSurface(), holder.getSurfaceFrame().width(), holder.getSurfaceFrame().height());
        }
    }

    private void attachTextureView(TextureView view) {
        currentTextureView = view;
        textureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
                releaseTextureSurface();
                textureSurface = new Surface(surfaceTexture);
                attachSurface(textureSurface, width, height);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
                setSurfaceSize(width, height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
                detachSurface();
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            }
        };
        view.setSurfaceTextureListener(textureListener);
        if (view.isAvailable()) {
            releaseTextureSurface();
            textureSurface = new Surface(view.getSurfaceTexture());
            attachSurface(textureSurface, view.getWidth(), view.getHeight());
        }
    }

    private void attachSurface(@Nullable Surface surface, int width, int height) {
        if (surface == null || !surface.isValid()) return;
        if (currentSurface == surface) {
            setSurfaceSize(width, height);
            return;
        }
        currentSurface = surface;
        currentSurfaceWidth = width;
        currentSurfaceHeight = height;
        MPVLib.attachSurface(surface);
        setSurfaceSize(width, height);
        setMpvProperty("vo", getMpvVo());
        setMpvOption("force-window", "yes");
    }

    private void detachSurface() {
        if (currentSurface == null) return;
        setMpvProperty("vo", "null");
        setMpvOption("force-window", "no");
        MPVLib.detachSurface();
        currentSurface = null;
        currentSurfaceWidth = 0;
        currentSurfaceHeight = 0;
        releaseTextureSurface();
    }

    private void restoreVideoOutput() {
        if (currentSurface == null || !currentSurface.isValid()) return;
        MPVLib.attachSurface(currentSurface);
        setSurfaceSize(currentSurfaceWidth, currentSurfaceHeight);
        setMpvProperty("vo", getMpvVo());
        setMpvOption("force-window", "yes");
    }

    private void detachVideoOutput() {
        if (currentSurfaceHolder != null && surfaceCallback != null) currentSurfaceHolder.removeCallback(surfaceCallback);
        if (currentTextureView != null && currentTextureView.getSurfaceTextureListener() == textureListener) currentTextureView.setSurfaceTextureListener(null);
        currentSurfaceHolder = null;
        currentTextureView = null;
        surfaceCallback = null;
        textureListener = null;
        currentVideoOutput = null;
        detachSurface();
    }

    private void setSurfaceSize(int width, int height) {
        if (width <= 0 || height <= 0) return;
        setMpvProperty("android-surface-size", width + "x" + height);
    }

    private void releaseTextureSurface() {
        if (textureSurface == null) return;
        textureSurface.release();
        textureSurface = null;
    }

    private void resetMediaState() {
        playerError = null;
        loading = false;
        durationMs = C.TIME_UNSET;
        positionMs = 0;
        bufferedPositionMs = C.TIME_UNSET;
        pendingInitialSeekMs = C.TIME_UNSET;
        currentTracks = Tracks.EMPTY;
        videoSize = VideoSize.UNKNOWN;
        externalSubtitlesAdded = false;
        passthroughRecoveryAttempted = false;
        hlsAbortRetryAttempted = false;
        audioOnlyFallback = false;
        manualStop = false;
        activeLoadUrl = null;
        lastErrorMessage = null;
        lastErrorUrl = null;
        renderedFirstFrame = false;
        reportRenderedFirstFrame = false;
        ignoreNextEndFile = false;
        loadedFileActive = false;
        playbackState = mediaItem == null ? Player.STATE_IDLE : Player.STATE_BUFFERING;
    }

    private void releaseInternal() {
        if (released) return;
        released = true;
        try {
            MPVLib.removeObserver(this);
            MPVLib.removeLogObserver(this);
            stopMpvPlayback(true);
            detachSurface();
            if (initialized) MPVLib.destroy();
        } catch (Throwable ignored) {
        }
        releaseTextureSurface();
        initialized = false;
    }

    private void postInvalidate() {
        handler.post(() -> {
            if (!released) invalidateState();
        });
    }

    private void command(String... args) {
        if (!initialized) return;
        try {
            MPVLib.command(args);
        } catch (Throwable e) {
            setError(e.getMessage(), e);
        }
    }

    private void stopMpvPlayback(boolean releasing) {
        manualStop = true;
        loading = false;
        playbackState = Player.STATE_IDLE;
        positionMs = 0;
        bufferedPositionMs = C.TIME_UNSET;
        pendingInitialSeekMs = C.TIME_UNSET;
        pendingStartPositionMs = C.TIME_UNSET;
        renderedFirstFrame = false;
        reportRenderedFirstFrame = false;
        ignoreNextEndFile = false;
        loadedFileActive = false;
        playerError = null;
        activeLoadUrl = null;
        lastErrorMessage = null;
        lastErrorUrl = null;
        hlsAbortRetryAttempted = false;
        audioOnlyFallback = false;
        if (initialized) {
            setMpvProperty("pause", true);
            command("stop");
            command("playlist-clear");
            setMpvProperty("vo", "null");
            setMpvOption("force-window", "no");
        }
        if (!releasing) invalidateState();
    }

    private void setError(String message) {
        setError(message, null);
    }

    private void setError(String message, @Nullable Throwable cause) {
        playerError = new PlaybackException(TextUtils.isEmpty(message) ? "MPV 播放失败" : message, cause, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK);
        playbackState = Player.STATE_IDLE;
        loading = false;
        invalidateState();
    }

    private boolean isStaleEndFileError() {
        if (TextUtils.isEmpty(lastErrorUrl) || mediaItem == null || mediaItem.localConfiguration == null) return false;
        String currentUrl = mediaItem.localConfiguration.uri.toString();
        String currentPlayableUrl = MpvMedia.getPlayableUrl(currentUrl);
        return !TextUtils.equals(lastErrorUrl, currentUrl) && !TextUtils.equals(lastErrorUrl, currentPlayableUrl);
    }

    private boolean retryHlsAbortError() {
        if (hlsAbortRetryAttempted || mediaItem == null || mediaItem.localConfiguration == null) return false;
        String url = mediaItem.localConfiguration.uri.toString();
        if (!isHls(mediaItem, url) || !isOpeningAbortedError()) return false;
        hlsAbortRetryAttempted = true;
        loading = true;
        playbackState = Player.STATE_BUFFERING;
        lastErrorMessage = null;
        lastErrorUrl = null;
        handler.postDelayed(() -> {
            if (released || mediaItem == null || mediaItem.localConfiguration == null) return;
            if (!TextUtils.equals(url, mediaItem.localConfiguration.uri.toString())) return;
            loadMediaItem(C.TIME_UNSET, false);
        }, 300);
        invalidateState();
        return true;
    }

    private boolean isOpeningAbortedError() {
        if (TextUtils.isEmpty(lastErrorMessage)) return false;
        String lower = lastErrorMessage.toLowerCase(Locale.ROOT);
        return lower.contains("opening failed or was aborted") || lower.contains("operation was aborted") || lower.contains("immediate exit requested");
    }

    @Nullable
    private String extractErrorUrl(String message) {
        if (TextUtils.isEmpty(message)) return null;
        int start = message.indexOf("http://");
        if (start < 0) start = message.indexOf("https://");
        if (start < 0) return null;
        int end = message.length();
        for (int i = start; i < message.length(); i++) {
            char c = message.charAt(i);
            if (Character.isWhitespace(c) || c == '\'' || c == '"' || c == ')') {
                end = i;
                break;
            }
        }
        return message.substring(start, end);
    }

    private void setMpvOption(String name, String value) {
        try {
            MPVLib.setOptionString(name, value);
        } catch (Throwable ignored) {
        }
    }

    private void setMpvProperty(String name, boolean value) {
        try {
            MPVLib.setPropertyBoolean(name, value);
        } catch (Throwable ignored) {
        }
    }

    private void setMpvProperty(String name, double value) {
        try {
            MPVLib.setPropertyDouble(name, value);
        } catch (Throwable ignored) {
        }
    }

    private void setMpvProperty(String name, String value) {
        try {
            MPVLib.setPropertyString(name, value);
        } catch (Throwable ignored) {
        }
    }

    @Nullable
    private Integer safeGetInt(String property) {
        try {
            return MPVLib.getPropertyInt(property);
        } catch (Throwable e) {
            return null;
        }
    }

    @Nullable
    private Double safeGetDouble(String property) {
        try {
            return MPVLib.getPropertyDouble(property);
        } catch (Throwable e) {
            return null;
        }
    }

    @Nullable
    private Boolean safeGetBoolean(String property) {
        try {
            return MPVLib.getPropertyBoolean(property);
        } catch (Throwable e) {
            return null;
        }
    }

    @Nullable
    private String safeGetString(String property) {
        try {
            return MPVLib.getPropertyString(property);
        } catch (Throwable e) {
            return null;
        }
    }

    private long getBufferedPositionMs() {
        if (bufferedPositionMs != C.TIME_UNSET) return bufferedPositionMs;
        return durationMs == C.TIME_UNSET ? positionMs : durationMs;
    }

    private long getTotalBufferedDurationMs() {
        long buffered = getBufferedPositionMs();
        return Math.max(0, buffered - sanitizePosition(positionMs));
    }

    private Size getCurrentSurfaceSize() {
        if (currentTextureView != null) return new Size(currentTextureView.getWidth(), currentTextureView.getHeight());
        if (currentSurfaceHolder != null) return new Size(currentSurfaceHolder.getSurfaceFrame().width(), currentSurfaceHolder.getSurfaceFrame().height());
        return Size.UNKNOWN;
    }

    private void markRenderedFirstFrame() {
        if (renderedFirstFrame) return;
        renderedFirstFrame = true;
        reportRenderedFirstFrame = true;
        if (playerError == null && playbackState == Player.STATE_BUFFERING) {
            playbackState = Player.STATE_READY;
            loading = false;
        }
    }

    private boolean consumeRenderedFirstFrame() {
        boolean value = reportRenderedFirstFrame;
        reportRenderedFirstFrame = false;
        return value;
    }

    private long sanitizePosition(long positionMs) {
        return positionMs == C.TIME_UNSET ? 0 : Math.max(0, positionMs);
    }

    private long secondsToMs(double seconds) {
        if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds < 0) return C.TIME_UNSET;
        return (long) (seconds * 1000.0);
    }

    private String formatSeconds(long positionMs) {
        return String.format(Locale.US, "%.3f", positionMs / 1000.0);
    }
}

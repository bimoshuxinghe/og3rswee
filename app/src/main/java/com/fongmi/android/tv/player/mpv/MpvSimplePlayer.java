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
import com.fongmi.android.tv.server.process.IsoStream;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.net.OkHttp;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.net.HttpHeaders;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
    private boolean isoResolving;
    private String isoOriginalUrl;
    private String isoProxyUrl;
    private boolean doviFallbackApplied;
    private boolean doviReloadPending;
    private String originalVo;
    // 硬件解码降级：当检测到 10-bit 内容使用 mediacodec-copy 时，自动切换避免绿屏
    // 多级降级策略：
    //   Stage 1: 尝试 mediacodec 非copy模式（帧直接在GPU内存，避免拷贝导致的格式降级）
    //   Stage 2: 降级到软件解码（正确处理10-bit色彩，但CPU负载较高）
    // GitHub issue mpv-android#1088 测试确认：
    //   hwdec=mediacodec-copy + vo=gpu → 绿屏（FFmpeg mediacodec-copy 10→8bit NV12 转换bug）
    //   hwdec=mediacodec + vo=gpu → 正常（部分设备为HW非copy，部分设备静默回退到SW）
    //   hwdec=no → 正常（软件解码，色彩正确但CPU高）
    private boolean hwdecFallbackApplied;
    private boolean hwdecMediacodecTried;
    private String currentHwdec;
    private String currentPixelformat;
    private int endFileReason;
    private int endFileError;
    private String endFileErrorString;
    private static boolean hasReplaceSurface = true;

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
        // 恢复原始渲染器
        if (doviFallbackApplied && originalVo != null && initialized) {
            setMpvProperty("vo", originalVo);
        }
        doviFallbackApplied = false;
        doviReloadPending = false;
        // 重置硬件解码降级标志：切换解码模式时清除之前的降级状态
        hwdecFallbackApplied = false;
        hwdecMediacodecTried = false;
        currentHwdec = null;
        currentPixelformat = null;
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
        if (isDoviProperty(property)) checkDoviPrimaries();
        if ("hwdec-current".equals(property) || "video-params/pixelformat".equals(property)) {
            handler.post(() -> {
                if (released) return;
                checkHwdecFallback();
            });
        }
        if ("track-list".equals(property) || "chapter-list".equals(property) || "edition-list".equals(property)) {
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
        if (isDoviProperty(property)) {
            handler.post(() -> {
                if (released) return;
                checkDoviPrimaries();
            });
        }
        // 记录当前硬件解码器和像素格式，用于检测 10-bit + mediacodec-copy 绿屏风险
        if ("hwdec-current".equals(property)) {
            handler.post(() -> {
                if (released) return;
                currentHwdec = value;
                checkHwdecFallback();
            });
        }
        if ("video-params/pixelformat".equals(property)) {
            handler.post(() -> {
                if (released) return;
                currentPixelformat = value;
                checkHwdecFallback();
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
                } else if (endFileReason == MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_REDIRECT) {
                    // 重定向：MPV 会自动加载新地址，保持 buffering 状态
                    playbackState = Player.STATE_BUFFERING;
                    loading = true;
                } else if (!manualStop && !renderedFirstFrame && !audioOnlyFallback && mediaItem != null) {
                    if (retryHlsAbortError()) return;
                    String errorMsg = !TextUtils.isEmpty(endFileErrorString) ? "MPV: " + endFileErrorString : lastErrorMessage;
                    setError(errorMsg == null ? "MPV 播放失败" : errorMsg);
                }
                else playbackState = Player.STATE_ENDED;
                loadedFileActive = false;
            }
            invalidateState();
        });
    }

    @Override
    public void eventEndFile(int reason, int error, String errorString) {
        handler.post(() -> {
            if (released) return;
            endFileReason = reason;
            endFileError = error;
            endFileErrorString = errorString;
            if (reason == MPVLib.MpvEndFileReason.MPV_END_FILE_REASON_ERROR && !TextUtils.isEmpty(errorString)) {
                lastErrorMessage = "MPV: " + errorString;
            }
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
            // === Pre-init options matching APK exactly ===
            applyConfigOptions();
            applyRenderOptions();
            setMpvOption("force-window", "no");
            setMpvOption("keepaspect", "no");
            applyShaderCacheDir();
            setMpvOption("ao", "audiotrack,opensles");
            setMpvOption("hwdec", "mediacodec");
            setMpvOption("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1,vc1");
            setMpvOption("ytdl", "no");
            applyProxyUrl();
            setMpvOption("profile", "fast");
            // 帧丢弃策略：当解码器输出帧速度 > 显示器刷新率时，在 VO 层丢弃帧
            // 避免 A/V 不同步和画面卡顿，尤其在软解高分辨率内容时
            setMpvOption("framedrop", "vo");
            // 视频同步模式：以音频时钟为基准同步视频
            // display-resample 模式需要 vsync 支持，移动设备使用 audio 更稳定
            setMpvOption("video-sync", "audio");
            // 插值：在帧之间进行插值以平滑运动
            // 仅在 video-sync=display-resample 时生效，此处设为 no 避免不必要开销
            setMpvOption("interpolation", "no");
            applyTlsCaFile();
            applyCacheOptions();
            applySubtitleConfig();
            applyHdrOptions();
            // === Init MPV ===
            MPVLib.init();
            // === Post-init options matching APK ===
            applyDecodeOption();
            applyAudioOptions();
            observeProperties();
            MPVLib.addObserver(this);
            MPVLib.addLogObserver(this);
            initialized = true;
        } catch (Throwable e) {
            playerError = new PlaybackException(e.getMessage(), e, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK);
            playbackState = Player.STATE_IDLE;
        }
    }

    private void applyShaderCacheDir() {
        try {
            java.io.File cacheDir = new java.io.File(context.getCacheDir(), "mpv");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            setMpvOption("gpu-shader-cache-dir", cacheDir.getAbsolutePath());
        } catch (Exception ignored) {
        }
    }

    private void applyProxyUrl() {
        try {
            int port = com.github.catvod.Proxy.getPort();
            if (port > 0) {
                setMpvOption("proxy-url", "http://127.0.0.1:" + port + "/proxy?");
            }
        } catch (Exception ignored) {
        }
    }

    private void applyTlsCaFile() {
        try {
            java.io.File cacert = new java.io.File(context.getFilesDir(), "cacert.pem");
            if (!cacert.isFile() || cacert.length() <= 0) {
                cacert.getParentFile().mkdirs();
                java.io.InputStream is = context.getAssets().open("cacert.pem");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(cacert, false);
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) != -1) fos.write(buf, 0, len);
                fos.close();
                is.close();
            }
            if (cacert.isFile() && cacert.length() > 0) {
                setMpvOption("tls-ca-file", cacert.getAbsolutePath());
            }
        } catch (Exception ignored) {
        }
    }

    private void applyCacheOptions() {
        if (!PlayerSetting.isPreload()) return;
        int cacheSecs = PlayerSetting.getMpvCacheSecs();
        setMpvOption("cache", "yes");
        setMpvOption("cache-on-disk", "yes");
        setMpvOption("cache-secs", String.valueOf(cacheSecs));
        try {
            java.io.File cacheDir = new java.io.File(context.getCacheDir(), "mpv");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            setMpvOption("demuxer-cache-dir", cacheDir.getAbsolutePath());
        } catch (Exception ignored) {
        }
    }

    private void applySubtitleConfig() {
        setMpvOption("embeddedfonts", "no");
        setMpvOption("sub-ass-override", "force");
        applySubtitleStyle();
        applySubtitleScaleAndPosition();
        applySecondarySubtitle();
    }

    private void applySubtitleStyle() {
        int styleSource = PlayerSetting.getSubtitleStyleSource();
        if (styleSource == 0) return;
        if (styleSource == 1) {
            applySystemSubtitleStyle();
        } else {
            applyCustomSubtitleStyle();
        }
    }

    private void applySystemSubtitleStyle() {
        android.view.accessibility.CaptioningManager cm = (android.view.accessibility.CaptioningManager)
                context.getSystemService(Context.CAPTIONING_SERVICE);
        android.view.accessibility.CaptioningManager.CaptionStyle userStyle = null;
        if (cm != null) userStyle = cm.getUserStyle();
        int fgColor = (userStyle != null && userStyle.hasForegroundColor()) ? userStyle.foregroundColor : 0xFFFFFFFF;
        int bgColor = (userStyle != null && userStyle.hasBackgroundColor()) ? userStyle.backgroundColor : 0xFF000000;
        int edgeType = (userStyle != null && userStyle.hasEdgeType()) ? userStyle.edgeType : 0;
        int edgeColor = (userStyle != null && userStyle.hasEdgeColor()) ? userStyle.edgeColor : 0xFFFFFFFF;
        String borderStyle = "outline-and-shadow";
        int actualBgColor = bgColor;
        if (edgeType == 2) {
            actualBgColor = edgeColor;
        }
        setMpvOption("sub-color", colorToHex(fgColor));
        setMpvOption("sub-back-color", colorToHex(actualBgColor));
        if (edgeType != 2 && android.graphics.Color.alpha(bgColor) != 0) {
            borderStyle = "background-box";
        }
        setMpvOption("sub-border-style", borderStyle);
        setMpvOption("sub-outline-color", colorToHex(edgeColor));
        if (edgeType == 0) {
            setMpvOption("sub-outline-size", "0");
            setMpvOption("sub-shadow-offset", "0");
        } else if (edgeType == 2) {
            setMpvOption("sub-outline-size", "0");
            setMpvOption("sub-shadow-offset", "2");
        } else {
            setMpvOption("sub-outline-size", "1.65");
            setMpvOption("sub-shadow-offset", "0");
        }
    }

    private void applyCustomSubtitleStyle() {
        int fgColor = applyOpacity(PlayerSetting.getSubtitleForegroundOpacity(), PlayerSetting.getSubtitleForegroundColor());
        int bgColor = applyOpacity(PlayerSetting.getSubtitleBackgroundOpacity(), PlayerSetting.getSubtitleBackgroundColor());
        int edgeType = PlayerSetting.getSubtitleEdgeType();
        int edgeColor = applyOpacity(PlayerSetting.getSubtitleEdgeOpacity(), PlayerSetting.getSubtitleEdgeColor());
        String borderStyle = "outline-and-shadow";
        int actualBgColor = bgColor;
        if (edgeType == 2 && android.graphics.Color.alpha(bgColor) == 0) {
            actualBgColor = edgeColor;
        }
        setMpvOption("sub-color", colorToHex(fgColor));
        setMpvOption("sub-back-color", colorToHex(actualBgColor));
        if (edgeType != 2 && android.graphics.Color.alpha(bgColor) != 0) {
            borderStyle = "background-box";
        }
        setMpvOption("sub-border-style", borderStyle);
        setMpvOption("sub-outline-color", colorToHex(edgeColor));
        if (edgeType == 0) {
            setMpvOption("sub-outline-size", "0");
            setMpvOption("sub-shadow-offset", "0");
        } else {
            if (edgeType == 1) {
                setMpvOption("sub-outline-size", String.valueOf(PlayerSetting.getSubtitleEdgeWidth()));
            } else {
                setMpvOption("sub-outline-size", "0");
            }
            if (edgeType == 2) {
                setMpvOption("sub-shadow-offset", String.valueOf(PlayerSetting.getSubtitleShadow()));
            } else {
                setMpvOption("sub-shadow-offset", "0");
            }
        }
    }

    private void applySubtitleScaleAndPosition() {
        float position = PlayerSetting.getSubtitlePosition();
        float subPos;
        if (position != 0.0f) {
            if (Math.abs(position) < 0.5f) position *= 100.0f;
            position = Math.min(30.0f, Math.max(position, -20.0f));
            subPos = (float) Math.max(0.0, Math.min(100.0 - position, 150.0));
        } else {
            subPos = PlayerSetting.getMpvSubtitlePosition();
        }
        subtitlePosition = subPos;
        setMpvProperty("sub-pos", subtitlePosition);
        int styleSource = PlayerSetting.getSubtitleStyleSource();
        float scaleValue = PlayerSetting.getMpvSubtitleScaleValue(context);
        boolean enableScale = styleSource == 1 || scaleValue != 1.0f;
        if (enableScale) {
            subtitleScale = scaleValue;
            setMpvProperty("sub-scale", subtitleScale);
            setMpvOption("sub-scale-signs", "yes");
        } else {
            subtitleScale = PlayerSetting.getMpvSubtitleScale();
            if (subtitleScale != 1.0f) {
                setMpvProperty("sub-scale", subtitleScale);
                setMpvOption("sub-scale-signs", "yes");
            }
        }
    }

    private void applySecondarySubtitle() {
        int styleSource = PlayerSetting.getSubtitleStyleSource();
        int secondaryTrack = PlayerSetting.getSubtitleSecondaryTrack();
        String assOverride = styleSource != 0 ? "force" : "scale";
        setMpvOption("secondary-sub-ass-override", assOverride);
        if (secondaryTrack == -2) {
            setMpvOption("secondary-sid", "no");
        } else if (secondaryTrack == -1) {
            setMpvOption("secondary-sid", "auto");
        } else {
            setMpvOption("secondary-sid", String.valueOf(secondaryTrack));
        }
        if (secondaryTrack != -2) {
            setMpvOption("secondary-sub-pos", String.valueOf(PlayerSetting.getSubtitleSecondaryPosition()));
        }
    }

    private static int applyOpacity(float opacity, int color) {
        float clampedOpacity = Math.min(1.0f, Math.max(opacity, 0.0f));
        int alpha = Math.round(clampedOpacity * android.graphics.Color.alpha(color));
        return android.graphics.Color.argb(alpha, android.graphics.Color.red(color), android.graphics.Color.green(color), android.graphics.Color.blue(color));
    }

    private static String colorToHex(int color) {
        return String.format(Locale.US, "#%02X%02X%02X%02X",
                android.graphics.Color.alpha(color), android.graphics.Color.red(color),
                android.graphics.Color.green(color), android.graphics.Color.blue(color));
    }

    private void applyConfigOptions() {
        java.io.File configDir = new java.io.File(context.getFilesDir(), "mpv");
        if (!configDir.exists()) configDir.mkdirs();
        java.io.File cacheDir = new java.io.File(context.getCacheDir(), "mpv");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        // Create fonts.conf if not exists (matching APK behavior)
        java.io.File fontsConf = new java.io.File(configDir, "fonts.conf");
        if (!fontsConf.isFile() || fontsConf.length() <= 0) {
            try {
                fontsConf.getParentFile().mkdirs();
                java.io.FileOutputStream fos = new java.io.FileOutputStream(fontsConf, false);
                fos.write("<?xml version=\"1.0\"?>\n<!DOCTYPE fontconfig SYSTEM \"fonts.dtd\">\n<fontconfig>\n<dir>".getBytes());
                fos.write(cacheDir.getAbsolutePath().getBytes());
                fos.write("</dir>\n<cachedir>".getBytes());
                fos.write(cacheDir.getAbsolutePath().getBytes());
                fos.write("</cachedir>\n</fontconfig>\n".getBytes());
                fos.close();
            } catch (java.io.IOException ignored) {
            }
        }
        setMpvOption("config", "yes");
        setMpvOption("config-dir", configDir.getAbsolutePath());
    }

    private void observeProperties() {
        MPVLib.observeProperty("time-pos", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("duration/full", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("demuxer-cache-time", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("demuxer-cache-duration", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("paused-for-cache", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("media-live", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("seekable", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("width", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        MPVLib.observeProperty("height", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        MPVLib.observeProperty("video-params/aspect", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE);
        MPVLib.observeProperty("video-params/rotate", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        MPVLib.observeProperty("video-params", MPVLib.MpvFormat.MPV_FORMAT_NONE);
        MPVLib.observeProperty("video-out-params", MPVLib.MpvFormat.MPV_FORMAT_NONE);
        MPVLib.observeProperty("video-params/primaries", MPVLib.MpvFormat.MPV_FORMAT_NONE);
        MPVLib.observeProperty("video-params/colormatrix", MPVLib.MpvFormat.MPV_FORMAT_NONE);
        MPVLib.observeProperty("video-params/transfer", MPVLib.MpvFormat.MPV_FORMAT_NONE);
        MPVLib.observeProperty("current-tracks/video/albumart", MPVLib.MpvFormat.MPV_FORMAT_FLAG);
        MPVLib.observeProperty("chapter", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        MPVLib.observeProperty("current-edition", MPVLib.MpvFormat.MPV_FORMAT_INT64);
        MPVLib.observeProperty("chapter-list", MPVLib.MpvFormat.MPV_FORMAT_NONE);
        MPVLib.observeProperty("edition-list", MPVLib.MpvFormat.MPV_FORMAT_NONE);
        MPVLib.observeProperty("track-list", MPVLib.MpvFormat.MPV_FORMAT_NONE);
        // 监听硬件解码状态：hwdec-current 显示当前实际使用的硬件解码器
        // 当值为 "no" 时表示硬件解码失败，需要自动降级到软件解码
        MPVLib.observeProperty("hwdec-current", MPVLib.MpvFormat.MPV_FORMAT_NONE);
        // 监听像素格式：检测 10-bit 内容，用于判断是否需要特殊处理
        MPVLib.observeProperty("video-params/pixelformat", MPVLib.MpvFormat.MPV_FORMAT_NONE);
    }

    private void loadMediaItem(long startPositionMs) {
        loadMediaItem(startPositionMs, false);
    }

    private void loadMediaItem(long startPositionMs, boolean useStartOption) {
        if (mediaItem == null || mediaItem.localConfiguration == null) return;
        String url = mediaItem.localConfiguration.uri.toString();
        // 远程 ISO 链接需要先解析文件系统，找到内部视频文件并注册代理，
        // 然后用代理 URL 替换原始 URL 进行播放。
        if (MpvMedia.isRemoteIso(url)) {
            if (isoResolving) return;
            if (TextUtils.equals(url, isoOriginalUrl) && !TextUtils.isEmpty(isoProxyUrl)) {
                url = isoProxyUrl;
            } else if (!TextUtils.equals(url, isoOriginalUrl)) {
                resolveRemoteIso(url, startPositionMs, useStartOption);
                return;
            }
        }
        if (!TextUtils.equals(activeLoadUrl, url)) {
            activeLoadUrl = url;
            hlsAbortRetryAttempted = false;
        }
        applyDecodeOption(isHls(mediaItem, url));
        positionMs = startPositionMs == C.TIME_UNSET ? 0 : Math.max(0, startPositionMs);
        pendingInitialSeekMs = positionMs > 0 && !useStartOption ? positionMs : C.TIME_UNSET;
        pendingStartPositionMs = C.TIME_UNSET;
        // 始终忽略下一个 END_FILE 事件：切换频道时旧流被 replace 中断不应视为错误
        ignoreNextEndFile = true;
        loadedFileActive = false;
        // 非 DoVi 重载时清除 DoVi 状态并恢复原始渲染器
        if (!doviReloadPending) {
            if (doviFallbackApplied && originalVo != null) {
                setMpvProperty("vo", originalVo);
            }
            doviFallbackApplied = false;
        }
        endFileReason = 0;
        endFileError = 0;
        endFileErrorString = null;
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

    private void resolveRemoteIso(String url, long startPositionMs, boolean useStartOption) {
        isoResolving = true;
        isoOriginalUrl = null;
        isoProxyUrl = null;
        playbackState = Player.STATE_BUFFERING;
        loading = true;
        videoSize = VideoSize.UNKNOWN;
        playerError = null;
        invalidateState();
        new Thread(() -> {
            try {
                Map<String, String> hdrs = ExoUtil.extractHeaders(mediaItem);
                String proxyUrl = IsoStream.register(url, hdrs);
                if (!TextUtils.isEmpty(proxyUrl)) {
                    isoOriginalUrl = url;
                    isoProxyUrl = proxyUrl;
                }
            } catch (Exception e) {
                Log.e(TAG, "ISO resolution failed: " + e.getMessage(), e);
            } finally {
                isoResolving = false;
                handler.post(() -> {
                    if (released || mediaItem == null || mediaItem.localConfiguration == null) return;
                    String currentUrl = mediaItem.localConfiguration.uri.toString();
                    if (!TextUtils.equals(currentUrl, url)) return;
                    loadMediaItem(startPositionMs, useStartOption);
                });
            }
        }, "iso-resolver").start();
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
        // 1. 从 MediaItem 提取 Channel 级别的 headers（来自 Live/Channel 的 ua/header/origin/referer）
        Map<String, String> headers = new HashMap<>(ExoUtil.extractHeaders(item));

        // 2. 从配置文件的 headers 数组中，按 host 匹配获取额外的 headers
        //    ExoPlayer 通过 OkHttp 的 ResponseInterceptor 自动应用这些 headers，
        //    但 MPV 使用自己的 HTTP 栈（FFmpeg），不经过 OkHttp，所以需要手动合并
        String url = item.requestMetadata.mediaUri != null ? item.requestMetadata.mediaUri.toString() : "";
        Map<String, String> configHeaders = OkHttp.responseInterceptor().getMatchedHeaders(url);
        // config headers 覆盖 channel headers（与 ExoPlayer + OkHttp 的行为一致）
        headers.putAll(configHeaders);

        Log.d(TAG, "applyHeaders url=" + url + " channelHeaders=" + ExoUtil.extractHeaders(item) + " configHeaders=" + configHeaders + " merged=" + headers);

        String userAgent = null;
        StringBuilder fields = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (TextUtils.isEmpty(entry.getKey()) || TextUtils.isEmpty(entry.getValue())) continue;
            if (HttpHeaders.USER_AGENT.equalsIgnoreCase(entry.getKey())) {
                userAgent = entry.getValue();
                continue; // UA 通过 user-agent 属性单独设置，避免在 http-header-fields 中重复
            }
            if (fields.length() > 0) fields.append(',');
            fields.append(entry.getKey()).append(": ").append(entry.getValue().replace(",", "\\,"));
        }
        // 确保 UA 始终设置：若 headers 中没有 UA，使用默认 UA
        if (TextUtils.isEmpty(userAgent)) {
            String defaultUa = com.fongmi.android.tv.setting.Setting.getUa();
            if (TextUtils.isEmpty(defaultUa)) defaultUa = com.fongmi.android.tv.player.PlayerHelper.getDefaultUa();
            userAgent = defaultUa;
        }
        // 关键修复：MPV 初始化后 setOptionString 不生效，必须用 setPropertyString
        // user-agent 和 http-header-fields 既是 option 也是 property，运行时用 property 设置
        if (!TextUtils.isEmpty(userAgent)) {
            if (initialized) setMpvProperty("user-agent", userAgent);
            else setMpvOption("user-agent", userAgent);
        }
        if (fields.length() > 0) {
            String headerFields = fields.toString();
            Log.d(TAG, "applyHeaders setting user-agent=" + userAgent + " http-header-fields=" + headerFields);
            if (initialized) setMpvProperty("http-header-fields", headerFields);
            else setMpvOption("http-header-fields", headerFields);
        }
    }

    private void applyDecodeOption() {
        applyDecodeOption(false);
    }

    private void applyDecodeOption(boolean isHlsStream) {
        // 当硬件解码降级已生效（10-bit 绿屏降级或 DoVi 降级），保持软件解码
        if (decode != com.fongmi.android.tv.player.engine.PlayerEngine.HARD || doviFallbackApplied || hwdecFallbackApplied) {
            setMpvOption("hwdec", "no");
            // 软件解码多线程：0 = 自动检测 CPU 核心数
            // GitHub #1088 报告 SW 解码 4K HEVC CPU 负载 200-350%，多线程可显著缓解
            setMpvOption("vd-lavc-threads", "0");
            if (initialized) {
                setMpvProperty("hwdec", "no");
                setMpvProperty("vf", "");
                // DoVi 回退时强制使用 gpu-next 渲染器
                if (doviFallbackApplied) {
                    setMpvProperty("vo", "gpu-next");
                }
            }
            return;
        }
        // 硬件解码策略（GPU转码方案）：
        //
        // 核心问题：mediacodec-copy 模式将帧拷贝到CPU内存后，MPV渲染器做 YUV→RGB 转换时
        // 使用了错误的色彩参数（色彩范围/色彩矩阵/传输函数），导致画面发绿发白。
        // GitHub #1088 测试确认：mediacodec-copy + vo=gpu → 绿屏
        //                        mediacodec(非copy) + vo=gpu → 正常
        //
        // 解决方案：
        // 1. 非HLS内容：仅使用 mediacodec（非copy模式），帧直接在GPU内存中渲染
        //    硬件解码器内置的色彩转换始终正确，避免MPV渲染器的色彩参数错误
        //    如果 mediacodec 不可用，MPV自动回退到软件解码（色彩也正确）
        //
        // 2. HLS直播流：使用 mediacodec-copy + GPU色彩转换滤镜(vf=format)
        //    mediacodec 非copy模式在HLS下会导致底部绿线（高度非16倍数时填充未初始化数据）
        //    mediacodec-copy 模式通过 vf=format 滤镜强制正确的色彩参数
        //
        // 3. GPU色彩转换滤镜：vf=format=colorlevels=limited:colormatrix=bt.709
        //    这就是"GPU转码"——在GPU上强制使用正确的色彩矩阵和范围进行 YUV→RGB 转换
        String value;
        if (hwdecMediacodecTried) {
            // Stage 1 降级：仅使用 mediacodec 非copy模式
            value = "mediacodec";
        } else if (isHlsStream) {
            // HLS直播流：使用 mediacodec-copy + GPU色彩转换滤镜
            value = "mediacodec-copy";
        } else {
            // 非HLS：仅使用 mediacodec 非copy模式，从根源避免绿屏
            value = "mediacodec";
        }
        setMpvOption("hwdec", value);
        if (initialized) {
            setMpvProperty("hwdec", value);
            // GPU色彩转换滤镜：
            // - HLS: 裁剪底部绿线 + 强制正确色彩参数
            // - 非HLS: 清空滤镜（mediacodec非copy模式不需要，硬件解码器内置正确转换）
            if (isHlsStream) {
                // GPU转码：crop去除底部绿线 + format强制正确YUV→RGB转换参数
                setMpvProperty("vf", "lavfi=[crop=iw:ih-ih%2:0:0],format=colorlevels=limited:colormatrix=bt.709:primaries=bt.709:transfer=bt.1886");
            } else {
                setMpvProperty("vf", "");
            }
        }
    }

    private void applyRenderOptions() {
        boolean gpuNext = PlayerSetting.isMpvGpuNext();
        boolean vulkan = PlayerSetting.isMpvVulkan();
        setMpvOption("vo", gpuNext ? "gpu-next" : "gpu");
        if (vulkan) {
            setMpvOption("gpu-api", "vulkan");
            setMpvOption("gpu-context", "androidvk");
        } else {
            setMpvOption("gpu-api", "opengl");
            setMpvOption("gpu-context", "android");
        }
        // 色彩抖动：减少色带（color banding），在 8-bit 显示器上改善 10-bit 内容的渐变
        setMpvOption("dither-depth", "auto");
        // 时间抖动：在帧之间快速切换抖动模式，人眼感知更高色深
        // 对动态画面效果显著，可有效减少 10-bit → 8-bit 降级时的色带
        setMpvOption("temporal-dither", "yes");
        setMpvOption("temporal-dither-period", "2");
        // 高质量缩放算法：改善低分辨率视频的放大质量
        setMpvOption("scale", "lanczos");
        setMpvOption("dscale", "mitchell");
        setMpvOption("cscale", "lanczos");
        // 正确下采样：避免缩小时的高频细节丢失
        setMpvOption("correct-downscaling", "yes");
        // 信号混叠：改善交织内容的渲染
        setMpvOption("sigmoid-upscaling", "yes");
        // FBO 格式：使用 16-bit 浮点格式，提升 HDR 和 10-bit 内容的渲染精度
        // 避免中间渲染步骤的精度丢失导致色彩偏差
        if (gpuNext) {
            setMpvOption("opengl-fbo-format", "auto16f");
        }
        // 字幕混合：在视频帧之后混合字幕，确保字幕色彩不受 HDR tone mapping 影响
        // GitHub mpv#18286: gpu-next 下字幕继承 HDR 色彩空间导致色偏
        setMpvOption("blend-subtitles", "video");
        // 注意：不启用 deband 滤镜
        // GitHub mpv#10323 确认 deband 在 gpu-next 上会导致绿色色偏
        // GitHub mpv#9285 确认 deband 可能加剧绿色色偏问题
    }

    private String getMpvVo() {
        return PlayerSetting.isMpvGpuNext() ? "gpu-next" : "gpu";
    }

    private void applyProbeOptions() {
        setMpvOption("demuxer-lavf-probe-info", "yes");
        setMpvOption("demuxer-lavf-probesize", "10485760");
        setMpvOption("demuxer-lavf-analyzeduration", "10");
        setMpvOption("demuxer-lavf-allow-mimetype", "no");
    }

    private void applyMediaOptions(String url) {
        if (MpvMedia.isSpoofedSegment(url) || MpvMedia.isRadioAudio(url)) applyProbeOptions();
        // ISO 代理流需要增强探测以确保 FFmpeg 正确识别 M2TS/VOB 格式
        if (url.contains("/iso_stream")) {
            applyProbeOptions();
            setMpvOption("demuxer-lavf-analyzeduration", "5");
        }
        String device = MpvMedia.getBluRayDevice(url);
        if (!TextUtils.isEmpty(device)) setMpvOption("bluray-device", device);
    }

    private String getLoadOptions(long positionMs, boolean useStartOption, MediaItem item, String url) {
        // 使用 LinkedHashMap 保持插入顺序（与反编译 APK 一致）
        LinkedHashMap<String, String> opts = new LinkedHashMap<>();

        if (positionMs > 0 && useStartOption) opts.put("start", formatSeconds(positionMs));
        if (isHls(item, url)) {
            // HLS 直播流：强制 lavf demuxer + HLS 格式，快速起播
            opts.put("demuxer", "lavf");
            opts.put("demuxer-lavf-format", "hls");
            opts.put("demuxer-lavf-probesize", "32");
            opts.put("demuxer-lavf-analyzeduration", "0");
            opts.put("cache", "yes");
            opts.put("cache-secs", "10");
            opts.put("keep-open", "yes");
            opts.put("keep-open-pause", "no");
        } else if (MpvMedia.isAudioFile(url) || MpvMedia.isRadioAudio(url)) {
            // 音频文件：关闭视频轨，只保留音频
            opts.put("demuxer", "lavf");
            opts.put("vid", "no");
            opts.put("aid", "auto");
            opts.put("keep-open", "yes");
            opts.put("keep-open-pause", "no");
        } else {
            // 未知格式或视频文件：用 lavf demuxer 自动探测，同时允许纯音频播放
            opts.put("demuxer", "lavf");
            opts.put("keep-open", "yes");
            opts.put("keep-open-pause", "no");
            opts.put("vid", "auto");
            opts.put("aid", "auto");
        }

        // === 将 headers 作为 per-file options 传入 loadfile（与反编译 APK 一致）===
        // 1. 从 MediaItem 提取 Channel 级别的 headers
        Map<String, String> headers = new HashMap<>(ExoUtil.extractHeaders(item));
        // 2. 从配置文件的 headers 数组中按 host 匹配获取额外的 headers
        Map<String, String> configHeaders = OkHttp.responseInterceptor().getMatchedHeaders(url);
        headers.putAll(configHeaders);

        String userAgent = null;
        String referer = null;
        StringBuilder fields = new StringBuilder();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (TextUtils.isEmpty(entry.getKey()) || TextUtils.isEmpty(entry.getValue())) continue;
            if (HttpHeaders.USER_AGENT.equalsIgnoreCase(entry.getKey())) {
                userAgent = entry.getValue();
            } else if (HttpHeaders.REFERER.equalsIgnoreCase(entry.getKey())) {
                referer = entry.getValue();
            } else {
                if (fields.length() > 0) fields.append(',');
                fields.append(entry.getKey()).append(": ").append(entry.getValue().replace(",", "\\,"));
            }
        }
        // 确保 UA 始终设置
        if (TextUtils.isEmpty(userAgent)) {
            String defaultUa = com.fongmi.android.tv.setting.Setting.getUa();
            if (TextUtils.isEmpty(defaultUa)) defaultUa = com.fongmi.android.tv.player.PlayerHelper.getDefaultUa();
            userAgent = defaultUa;
        }

        if (!TextUtils.isEmpty(userAgent)) opts.put("user-agent", userAgent);
        if (!TextUtils.isEmpty(referer)) opts.put("referrer", referer);
        if (fields.length() > 0) opts.put("http-header-fields", fields.toString());

        Log.d(TAG, "getLoadOptions url=" + url + " headers=" + headers + " userAgent=" + userAgent + " headerFields=" + fields + " opts=" + opts);

        // 使用 MPV 的 per-file option 编码格式：%keyLen%key=%valLen%value
        // 这种格式能正确处理值中包含逗号和等号的情况
        List<String> encoded = new ArrayList<>();
        for (Map.Entry<String, String> entry : opts.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            byte[] keyBytes = key.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] valBytes = val.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            encoded.add("%" + keyBytes.length + "%" + key + "=%" + valBytes.length + "%" + val);
        }
        return TextUtils.join(",", encoded);
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
        // 色彩空间提示：通知显示设备切换到视频的色彩空间
        setMpvOption("target-colorspace-hint", "yes");
        // HDR 峰值计算：用于 HDR 色调映射
        setMpvOption("hdr-compute-peak", "yes");
        // 自动色调映射
        setMpvOption("tone-mapping", "auto");
        // 色调映射模式：自动选择最佳算法
        setMpvOption("tone-mapping-mode", "auto");
        // 色调映射参数：控制色调映射的强度
        setMpvOption("tone-mapping-param", "default");
        // HDR 峰值衰减率：控制峰值检测的平滑度
        setMpvOption("hdr-peak-decay-rate", "100");
        // HDR 峰值参考值：用于场景切换时的峰值重置
        setMpvOption("hdr-scene-threshold", "0");
        // 色域映射模式：自动处理 BT.2020 → BT.709 等色域转换
        setMpvOption("gamut-mapping-mode", "auto");
        // 目标对比度：inf 适用于有全局色彩管理的显示器，避免发白
        setMpvOption("target-contrast", "inf");
        // === 色彩空间强制设置（修复绿屏的核心参数）===
        // GitHub #9285 确认：target-trc=auto 在部分设备上选择错误的 gamma 曲线导致绿色色偏
        // GitHub #1088 确认：mediacodec-copy 的色彩范围检测错误导致绿屏
        //
        // 强制 BT.709 色彩原色：HD/4K 内容标准色域，覆盖 99% 的现代视频内容
        setMpvOption("target-prim", "bt.709");
        // 强制 sRGB 传输函数：GitHub #9285 推荐的修复方案
        // sRGB 是显示器标准传输函数，确保正确的 gamma 转换，避免发白
        // HDR 内容通过 tone-mapping 自动转换到 sRGB 输出
        setMpvOption("target-trc", "srgb");
        // 强制 limited（TV）色彩范围：视频内容标准范围 16-235
        // auto 检测在 mediacodec-copy 模式下可能错误识别为 full range，导致暗部发绿
        setMpvOption("video-colorspace-range", "limited");
        setMpvOption("target-range", "limited");
    }

    private void applyOffsets() {
        setTextOffsetMs(textOffsetMs);
        setAudioOffsetMs(audioOffsetMs);
    }

    private void setSubtitleScale(double scale) {
        subtitleScale = Math.max(0.5, Math.min(scale, 3.0));
        setMpvProperty("sub-scale", subtitleScale);
        setMpvOption("sub-scale-signs", "yes");
        com.fongmi.android.tv.setting.PlayerSetting.putMpvSubtitleScale((float) subtitleScale);
    }

    private void setSubtitlePosition(double position) {
        subtitlePosition = Math.max(0.0, Math.min(position, 150.0));
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
            else if ("demuxer-cache-duration".equals(property)) {
                if (value > 0) bufferedPositionMs = Math.max(positionMs, positionMs + secondsToMs(value));
            }
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
        return "video-params".equals(property) || "video-out-params".equals(property)
                || "video-params/aspect".equals(property) || "video-params/rotate".equals(property)
                || "width".equals(property) || "height".equals(property);
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
        boolean hasDovi = false;
        for (int index = 0; index < count; index++) {
            Integer mpvId = safeGetInt("track-list/" + index + "/id");
            String type = safeGetString("track-list/" + index + "/type");
            int trackType = getTrackType(type);
            if (mpvId == null || mpvId <= 0 || trackType == C.TRACK_TYPE_UNKNOWN) continue;
            if (trackType == C.TRACK_TYPE_AUDIO) hasAudio = true;
            if (trackType == C.TRACK_TYPE_VIDEO) {
                hasVideo = true;
                String codec = safeGetString("track-list/" + index + "/codec");
                if (isDolbyVisionCodec(codec)) hasDovi = true;
            }
            Format format = buildFormat(index, mpvId, trackType);
            boolean selected = Boolean.TRUE.equals(safeGetBoolean("track-list/" + index + "/selected"));
            groups.add(new Tracks.Group(new TrackGroup("mpv-" + type + "-" + mpvId, format), false, new int[]{C.FORMAT_HANDLED}, new boolean[]{selected}));
        }
        currentTracks = groups.isEmpty() ? Tracks.EMPTY : new Tracks(groups);
        applyDoviFallback(hasDovi);
        applyAudioOnlyFallback(hasAudio, hasVideo);
    }

    private static boolean isDolbyVisionCodec(String codec) {
        if (TextUtils.isEmpty(codec)) return false;
        String lower = codec.toLowerCase(Locale.ROOT);
        return lower.startsWith("dovi") || lower.startsWith("dvhe") || lower.startsWith("dvh1")
                || lower.startsWith("dvav") || lower.startsWith("dav1") || lower.contains("dolby-vision");
    }

    private static boolean isDoviProperty(String property) {
        return "video-params/primaries".equals(property)
                || "video-params/colormatrix".equals(property)
                || "video-params/transfer".equals(property);
    }

    private void applyDoviFallback(boolean hasDovi) {
        if (!hasDovi || doviFallbackApplied || doviReloadPending) return;
        doviFallbackApplied = true;
        doviReloadPending = true;
        Log.w(TAG, "Dolby Vision detected, switching to gpu-next + software decode to fix green screen");
        // 保存原始渲染器，以便非 DoVi 视频恢复
        if (originalVo == null) {
            originalVo = PlayerSetting.isMpvGpuNext() ? "gpu-next" : "gpu";
        }
        // 切换到 gpu-next 渲染器：libplacebo 内置 IPTPQc2 色彩空间转换，是修复 DV Profile 5 绿屏的关键
        setMpvProperty("vo", "gpu-next");
        // 软件解码：硬件解码器无法传递 DV 元数据给 gpu-next
        setMpvProperty("hwdec", "no");
        // 清除视频滤镜，避免干扰色彩转换
        setMpvProperty("vf", "");
        // 重新加载视频以使 gpu-next + 软解生效
        if (mediaItem != null && playbackState != Player.STATE_IDLE) {
            handler.post(() -> {
                if (released) return;
                loadMediaItem(positionMs, true);
                doviReloadPending = false;
            });
        } else {
            doviReloadPending = false;
        }
    }

    private void checkDoviPrimaries() {
        if (doviFallbackApplied) return;
        // 检查色彩原色（primaries）：DoVi Profile 5 使用 IPT 色彩空间
        String primaries = safeGetString("video-params/primaries");
        if (primaries != null && (primaries.contains("ipt") || primaries.contains("dovi"))) {
            applyDoviFallback(true);
            return;
        }
        // 检查色彩矩阵（colormatrix）：DoVi 内容可能报告 "dovi" 或 "ipt"
        String colormatrix = safeGetString("video-params/colormatrix");
        if (colormatrix != null && (colormatrix.contains("ipt") || colormatrix.contains("dovi"))) {
            applyDoviFallback(true);
            return;
        }
        // 检查传输特性（transfer）：DoVi 内容可能报告 "dovi"
        String transfer = safeGetString("video-params/transfer");
        if (transfer != null && transfer.contains("dovi")) {
            applyDoviFallback(true);
        }
    }

    /**
     * 检测 10-bit 内容使用 mediacodec-copy 时的绿屏风险，执行多级降级策略。
     * <p>
     * GitHub issue mpv-android#540/#853/#1088/#1206 确认：
     * mediacodec-copy 模式在部分设备上会强制 10-bit → 8-bit NV12 转换，
     * 导致 stride mismatch 产生绿色伪影。此问题源于 FFmpeg 层面，mpv 维护者标记为
     * "can't fix"（#1088）。
     * <p>
     * 多级降级策略：
     * Stage 1: 尝试 mediacodec 非copy模式 — 帧直接在 GPU 内存中传输，避免拷贝导致的
     *          格式降级。部分设备上此模式可作为真正 HW 解码工作（#1088 测试确认）；
     *          另一些设备上会静默回退到 SW 解码，但色彩仍然正确。
     * Stage 2: 降级到软件解码 — 完全正确处理 10-bit 色彩，支持 tone mapping，
     *          但 CPU 负载较高。启用多线程软解以缓解性能问题。
     * <p>
     * 检测条件：硬件解码模式 + mediacodec-copy + 10-bit 像素格式
     */
    private void checkHwdecFallback() {
        if (hwdecFallbackApplied || doviFallbackApplied) return;
        if (decode != com.fongmi.android.tv.player.engine.PlayerEngine.HARD) return;
        if (TextUtils.isEmpty(currentHwdec) || "no".equals(currentHwdec)) return;
        // 仅当实际使用 mediacodec-copy 时才检测（mediacodec 非拷贝模式不会有此问题）
        if (!currentHwdec.contains("copy")) return;
        // 检测 10-bit 像素格式：yuv420p10/yuv422p10/yuv444p10 等
        if (TextUtils.isEmpty(currentPixelformat)) return;
        boolean is10bit = currentPixelformat.contains("10") || currentPixelformat.contains("p10");
        if (!is10bit) return;

        if (!hwdecMediacodecTried) {
            // Stage 1: 尝试 mediacodec 非copy模式
            // GitHub #1088 测试: hwdec=mediacodec + vo=gpu 在部分设备上可作为HW非copy工作，
            // 在其他设备上静默回退到SW解码 — 两种情况都能避免绿屏
            hwdecMediacodecTried = true;
            Log.w(TAG, "10-bit + mediacodec-copy detected (Stage 1): trying mediacodec non-copy mode. hwdec=" + currentHwdec + " pixelformat=" + currentPixelformat);
            setMpvProperty("hwdec", "mediacodec");
            setMpvProperty("vf", "");
            // 重新加载以使新的 hwdec 模式生效
            if (mediaItem != null && playbackState != Player.STATE_IDLE) {
                handler.post(() -> {
                    if (released) return;
                    loadMediaItem(positionMs, true);
                });
            }
            return;
        }

        // Stage 2: mediacodec 非copy模式仍然回退到了 mediacodec-copy，降级到软件解码
        // 启用多线程软解以缓解 CPU 负载（#1088 报告 SW 解码 CPU 200-350%）
        hwdecFallbackApplied = true;
        Log.w(TAG, "10-bit content fallback (Stage 2): switching to multi-threaded software decode. hwdec=" + currentHwdec + " pixelformat=" + currentPixelformat);
        setMpvProperty("hwdec", "no");
        setMpvProperty("vf", "");
        // 多线程软解：0 = 自动检测 CPU 核心数
        setMpvOption("vd-lavc-threads", "0");
        // 软解时增大缓存以平滑播放
        setMpvOption("cache-secs", "20");
        // 重新加载以使软解生效
        if (mediaItem != null && playbackState != Player.STATE_IDLE) {
            handler.post(() -> {
                if (released) return;
                loadMediaItem(positionMs, true);
            });
        }
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
        boolean hadSurface = currentSurface != null;
        currentSurface = surface;
        currentSurfaceWidth = width;
        currentSurfaceHeight = height;
        if (hadSurface && hasReplaceSurface) {
            try {
                MPVLib.replaceSurface(surface);
            } catch (Throwable e) {
                hasReplaceSurface = false;
                MPVLib.attachSurface(surface);
            }
        } else {
            MPVLib.attachSurface(surface);
        }
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
        if (hasReplaceSurface) {
            try {
                MPVLib.replaceSurface(currentSurface);
            } catch (Throwable e) {
                hasReplaceSurface = false;
                MPVLib.attachSurface(currentSurface);
            }
        } else {
            MPVLib.attachSurface(currentSurface);
        }
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
        // DoVi 回退结束，恢复原始渲染器
        if (doviFallbackApplied && originalVo != null && initialized) {
            setMpvProperty("vo", originalVo);
        }
        doviFallbackApplied = false;
        doviReloadPending = false;
        // 重置硬件解码降级标志：新视频加载时清除之前的降级状态
        hwdecFallbackApplied = false;
        hwdecMediacodecTried = false;
        currentHwdec = null;
        currentPixelformat = null;
        isoResolving = false;
        isoOriginalUrl = null;
        isoProxyUrl = null;
        endFileReason = 0;
        endFileError = 0;
        endFileErrorString = null;
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
        isoResolving = false;
        isoOriginalUrl = null;
        isoProxyUrl = null;
        endFileReason = 0;
        endFileError = 0;
        endFileErrorString = null;
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
        } catch (Throwable e) {
            Log.w(TAG, "setMpvProperty failed: " + name + "=" + value + ", error=" + e.getMessage());
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

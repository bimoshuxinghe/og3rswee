package com.fongmi.android.tv.player.vlc;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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

import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.github.catvod.net.OkHttp;
import com.google.common.collect.ImmutableList;
import com.google.common.net.HttpHeaders;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.interfaces.IVLCVout;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@UnstableApi
public final class VlcSimplePlayer extends SimpleBasePlayer implements MediaPlayer.EventListener {

    private static final String TAG = "VlcSimplePlayer";

    private static final Player.Commands COMMANDS = new Player.Commands.Builder()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SET_SPEED_AND_PITCH)
            .add(Player.COMMAND_SET_VOLUME)
            .add(Player.COMMAND_SET_VIDEO_SURFACE)
            .add(Player.COMMAND_SET_MEDIA_ITEM)
            .add(Player.COMMAND_GET_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_GET_TRACKS)
            .add(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)
            .build();

    private final Context context;
    private final Handler handler;
    private LibVLC libVLC;
    private MediaPlayer mediaPlayer;
    private MediaItem mediaItem;
    private PlaybackParameters playbackParameters;
    private PlaybackException playerError;
    private Tracks currentTracks;
    private VideoSize videoSize;
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
    private boolean manualStop;
    private boolean isSeeking;
    private float volume = 1.0f;
    private int playbackState = Player.STATE_IDLE;
    private int decode;
    private double subtitleScale = 1.0;
    private double subtitlePosition = 100.0;
    private long textOffsetMs;
    private long audioOffsetMs;
    private long pendingStartPositionMs;
    private long durationMs = C.TIME_UNSET;
    private long positionMs;
    private long bufferedPositionMs = C.TIME_UNSET;
    private int currentSurfaceWidth;
    private int currentSurfaceHeight;
    private String lastErrorMessage;
    private boolean voutAttached;

    private static volatile boolean availabilityChecked;
    private static boolean nativeAvailable = true;

    public VlcSimplePlayer(Context context, int decode) {
        super(Looper.getMainLooper());
        this.context = context.getApplicationContext();
        this.handler = new Handler(Looper.getMainLooper());
        this.decode = decode;
        this.playbackState = Player.STATE_IDLE;
        this.currentTracks = Tracks.EMPTY;
        this.videoSize = VideoSize.UNKNOWN;
        this.playbackParameters = PlaybackParameters.DEFAULT;
        this.playWhenReady = true;
        this.volume = 1.0f;
        this.durationMs = C.TIME_UNSET;
        this.bufferedPositionMs = C.TIME_UNSET;
        this.pendingStartPositionMs = C.TIME_UNSET;
        initialize();
    }

    public static boolean isAvailable() {
        if (!availabilityChecked) {
            try {
                System.loadLibrary("vlc");
                nativeAvailable = true;
            } catch (Throwable e) {
                nativeAvailable = false;
            }
            availabilityChecked = true;
        }
        return nativeAvailable;
    }

    public static String getAvailabilityError() {
        return isAvailable() ? null : "VLC native library not available";
    }

    public void setDecode(int decode) {
        if (this.decode == decode) return;
        this.decode = decode;
        releaseInternal();
        initialize();
        if (mediaItem != null) {
            setMediaItem(mediaItem);
            try { prepare(); } catch (Exception ignored) {}
        }
    }

    // ========== SimpleBasePlayer overrides ==========

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
        if (mediaPlayer == null) return Futures.immediateVoidFuture();
        if (playWhenReady) {
            try { mediaPlayer.play(); } catch (Exception ignored) {}
        } else {
            try { mediaPlayer.pause(); } catch (Exception ignored) {}
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handlePrepare() {
        if (mediaItem == null) return Futures.immediateVoidFuture();
        loadMedia();
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleStop() {
        manualStop = true;
        stopPlayback();
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
        if (mediaPlayer != null) {
            try { mediaPlayer.setRate((float) playbackParameters.speed); } catch (Exception ignored) {}
        }
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSetVolume(float volume, int volumeOperationType) {
        this.volume = Math.min(Math.max(volume, 0.0f), 1.0f);
        if (mediaPlayer != null) {
            try { mediaPlayer.setVolume(Math.round(this.volume * 100.0f)); } catch (Exception ignored) {}
        }
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
        stopPlayback();
        resetMediaState();
        playbackState = Player.STATE_IDLE;
        return Futures.immediateVoidFuture();
    }

    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        long target = positionMs == C.TIME_UNSET ? 0 : Math.max(0, positionMs);
        this.positionMs = target;
        if (mediaPlayer != null) {
            try {
                isSeeking = true;
                mediaPlayer.setTime(target);
                playbackState = Player.STATE_BUFFERING;
                loading = true;
                invalidateState();
            } catch (Exception ignored) {}
        }
        return Futures.immediateVoidFuture();
    }

    // ========== MediaPlayer.EventListener ==========

    @Override
    public void onEvent(MediaPlayer.Event event) {
        handler.post(() -> {
            if (released) return;
            switch (event.type) {
                case MediaPlayer.Event.Playing:
                    onPlaying();
                    break;
                case MediaPlayer.Event.Paused:
                    onPaused();
                    break;
                case MediaPlayer.Event.Stopped:
                    onStopped();
                    break;
                case MediaPlayer.Event.EndReached:
                    onEndReached();
                    break;
                case MediaPlayer.Event.EncounteredError:
                    onError();
                    break;
                case MediaPlayer.Event.TimeChanged:
                    onTimeChanged(event);
                    break;
                case MediaPlayer.Event.LengthChanged:
                    onLengthChanged(event);
                    break;
                case MediaPlayer.Event.Buffering:
                    onBuffering(event);
                    break;
                case MediaPlayer.Event.Vout:
                    onVout(event);
                    break;
                case MediaPlayer.Event.ESAdded:
                case MediaPlayer.Event.ESDeleted:
                case MediaPlayer.Event.ESSelected:
                    onTracksChanged();
                    break;
                case MediaPlayer.Event.MediaChanged:
                    onMediaChanged();
                    break;
                case MediaPlayer.Event.SeekableChanged:
                case MediaPlayer.Event.PausableChanged:
                    invalidateState();
                    break;
            }
        });
    }

    // ========== Internal methods ==========

    private void initialize() {
        if (initialized) releaseInternal();
        try {
            List<String> options = new ArrayList<>();
            options.add("--aout=opensles");
            options.add("--http-reconnect");
            options.add("--network-caching=1500");
            options.add("--file-caching=1500");
            options.add("--live-caching=3000");
            options.add("--stats");
            options.add("--no-video-title-show");
            options.add("--sub-autodetect-file");
            options.add("--no-sub-autodetect-fuzzy");
            options.add("--sub-scale=1.0");
            options.add("--verbose=0");
            options.add("--no-drop-late-frames");
            options.add("--no-skip-frames");
            libVLC = new LibVLC(context, options);
            mediaPlayer = new MediaPlayer(libVLC);
            mediaPlayer.setEventListener(this);
            if (currentSurface != null && currentSurface.isValid()) {
                try {
                    mediaPlayer.getVLCVout().setVideoSurface(currentSurface, null);
                    mediaPlayer.getVLCVout().attachViews(videoLayoutListener);
                    voutAttached = true;
                } catch (Exception ignored) {}
            }
            initialized = true;
        } catch (Throwable e) {
            playerError = new PlaybackException(e.getMessage(), e, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK);
            playbackState = Player.STATE_IDLE;
        }
    }

    private void loadMedia() {
        if (mediaItem == null || mediaItem.localConfiguration == null) return;
        String url = mediaItem.localConfiguration.uri.toString();
        if (mediaPlayer == null || libVLC == null) return;

        try {
            if (mediaPlayer.getMedia() != null) {
                mediaPlayer.getMedia().release();
            }
        } catch (Exception ignored) {}

        try {
            Media media = new Media(libVLC, url);
            // 设置硬解
            if (decode == com.fongmi.android.tv.player.engine.PlayerEngine.HARD) {
                media.setHWDecoderEnabled(true, false);
            } else {
                media.setHWDecoderEnabled(false, true);
            }
            // 设置 headers
            applyHeadersToMedia(media, url);
            // 初始定位：libvlc 的 setTime 在 play 前不生效，用 option 设置起播位置
            if (pendingStartPositionMs > 0) {
                positionMs = pendingStartPositionMs;
                media.addOption(":start-time=" + pendingStartPositionMs);
            }
            mediaPlayer.setMedia(media);
            media.release(); // libVLC 内部引用，可释放 Java 引用
            // 应用偏移
            if (audioOffsetMs != 0) mediaPlayer.setAudioDelay(audioOffsetMs * 1000L);
            if (textOffsetMs != 0) mediaPlayer.setSpuDelay(textOffsetMs * 1000L);
            // 恢复播放状态
            playbackState = Player.STATE_BUFFERING;
            loading = true;
            manualStop = false;
            renderedFirstFrame = false;
            reportRenderedFirstFrame = false;
            isSeeking = false;
            videoSize = VideoSize.UNKNOWN;
            playerError = null;
            lastErrorMessage = null;
            invalidateState();
            mediaPlayer.play();
        } catch (Exception e) {
            setError("VLC load failed: " + e.getMessage());
        }
    }

    private void applyHeadersToMedia(Media media, String url) {
        try {
            java.util.Map<String, String> headers = new java.util.HashMap<>(ExoUtil.extractHeaders(mediaItem));
            java.util.Map<String, String> configHeaders = OkHttp.responseInterceptor().getMatchedHeaders(url);
            headers.putAll(configHeaders);

            String userAgent = null;
            String referer = null;
            for (java.util.Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                if (HttpHeaders.USER_AGENT.equalsIgnoreCase(entry.getKey())) {
                    userAgent = entry.getValue();
                } else if (HttpHeaders.REFERER.equalsIgnoreCase(entry.getKey())) {
                    referer = entry.getValue();
                }
            }
            if (userAgent == null) {
                String defaultUa = com.fongmi.android.tv.setting.Setting.getUa();
                if (defaultUa == null) defaultUa = com.fongmi.android.tv.player.PlayerHelper.getDefaultUa();
                userAgent = defaultUa;
            }
            if (userAgent != null) media.addOption(":http-user-agent=" + userAgent);
            if (referer != null) media.addOption(":http-referrer=" + referer);
        } catch (Exception ignored) {}
    }

    private void stopPlayback() {
        manualStop = true;
        loading = false;
        playbackState = Player.STATE_IDLE;
        positionMs = 0;
        bufferedPositionMs = C.TIME_UNSET;
        renderedFirstFrame = false;
        reportRenderedFirstFrame = false;
        playerError = null;
        lastErrorMessage = null;
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
        }
        invalidateState();
    }

    private void releaseInternal() {
        if (released) return;
        released = true;
        try {
            if (mediaPlayer != null) {
                mediaPlayer.setEventListener(null);
                mediaPlayer.stop();
                try {
                    mediaPlayer.getVLCVout().detachViews();
                } catch (Exception ignored) {}
                mediaPlayer.release();
            }
        } catch (Exception ignored) {}
        try {
            if (libVLC != null) libVLC.release();
        } catch (Exception ignored) {}
        mediaPlayer = null;
        libVLC = null;
        initialized = false;
        voutAttached = false;
        detachVideoOutput();
    }

    private void resetMediaState() {
        playerError = null;
        loading = false;
        durationMs = C.TIME_UNSET;
        positionMs = 0;
        bufferedPositionMs = C.TIME_UNSET;
        currentTracks = Tracks.EMPTY;
        videoSize = VideoSize.UNKNOWN;
        manualStop = false;
        lastErrorMessage = null;
        renderedFirstFrame = false;
        reportRenderedFirstFrame = false;
        isSeeking = false;
        playbackState = mediaItem == null ? Player.STATE_IDLE : Player.STATE_BUFFERING;
    }

    // ========== Event handlers ==========

    private void onPlaying() {
        if (released) return;
        if (loading && !isSeeking) {
            renderedFirstFrame = true;
            reportRenderedFirstFrame = true;
        }
        if (playerError == null) {
            playbackState = Player.STATE_READY;
            loading = false;
        }
        isSeeking = false;
        updateVideoSize();
        invalidateState();
    }

    private void onPaused() {
        if (released) return;
        playbackState = Player.STATE_READY;
        loading = false;
        invalidateState();
    }

    private void onStopped() {
        if (released) return;
        playbackState = Player.STATE_IDLE;
        loading = false;
        invalidateState();
    }

    private void onEndReached() {
        if (released) return;
        playbackState = Player.STATE_ENDED;
        loading = false;
        invalidateState();
    }

    private void onError() {
        if (released) return;
        String msg = lastErrorMessage != null ? lastErrorMessage : "VLC 播放失败";
        setError(msg);
    }

    private void onTimeChanged(MediaPlayer.Event event) {
        if (released) return;
        long time = event.getTimeChanged();
        if (time >= 0) {
            positionMs = time;
            if (loading && !isSeeking && positionMs > 0) {
                markRenderedFirstFrame();
            }
            invalidateState();
        }
    }

    private void onLengthChanged(MediaPlayer.Event event) {
        if (released) return;
        long length = event.getLengthChanged();
        if (length >= 0) {
            durationMs = length;
            invalidateState();
        }
    }

    private void onBuffering(MediaPlayer.Event event) {
        if (released) return;
        float buffering = event.getBuffering();
        if (buffering >= 100.0f) {
            if (loading && !isSeeking) {
                playbackState = Player.STATE_READY;
                loading = false;
            }
            bufferedPositionMs = durationMs != C.TIME_UNSET ? durationMs : positionMs;
        } else if (buffering >= 0) {
            if (playbackState != Player.STATE_BUFFERING) {
                playbackState = Player.STATE_BUFFERING;
                loading = true;
            }
        }
        invalidateState();
    }

    private void onVout(MediaPlayer.Event event) {
        if (released) return;
        int count = event.getVoutCount();
        if (count > 0 && !renderedFirstFrame) {
            markRenderedFirstFrame();
        }
        updateVideoSize();
        invalidateState();
    }

    private void onTracksChanged() {
        if (released || mediaPlayer == null) return;
        if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) return;
        buildTracks();
        invalidateState();
    }

    private void onMediaChanged() {
        if (released || mediaPlayer == null) return;
        renderedFirstFrame = false;
        reportRenderedFirstFrame = false;
        videoSize = VideoSize.UNKNOWN;
        currentTracks = Tracks.EMPTY;
        durationMs = C.TIME_UNSET;
        positionMs = 0;
        bufferedPositionMs = C.TIME_UNSET;
        loading = true;
        playbackState = Player.STATE_BUFFERING;
        invalidateState();
    }

    // ========== Video output ==========

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
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surfaceTexture, int width, int height) {
                attachSurface(new Surface(surfaceTexture), width, height);
            }
            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surfaceTexture, int width, int height) {
                setSurfaceSize(width, height);
            }
            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surfaceTexture) {
                detachSurface();
                return false;
            }
            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surfaceTexture) {}
        };
        view.setSurfaceTextureListener(textureListener);
        if (view.isAvailable()) {
            attachSurface(new Surface(view.getSurfaceTexture()), view.getWidth(), view.getHeight());
        }
    }

    private void attachSurface(@Nullable Surface surface, int width, int height) {
        if (surface == null || !surface.isValid()) return;
        currentSurface = surface;
        currentSurfaceWidth = width;
        currentSurfaceHeight = height;
        if (mediaPlayer != null) {
            try {
                IVLCVout vout = mediaPlayer.getVLCVout();
                vout.addCallback(voutCallback);
                vout.setVideoSurface(surface, null);
                if (!voutAttached) {
                    vout.attachViews(videoLayoutListener);
                    voutAttached = true;
                }
            } catch (Exception e) {
                Log.w(TAG, "attachSurface failed: " + e.getMessage());
            }
        }
        setSurfaceSize(width, height);
    }

    private void detachSurface() {
        if (currentSurface == null) return;
        if (mediaPlayer != null && voutAttached) {
            try {
                mediaPlayer.getVLCVout().removeCallback(voutCallback);
                mediaPlayer.getVLCVout().detachViews();
            } catch (Exception ignored) {}
            voutAttached = false;
        }
        currentSurface = null;
        currentSurfaceWidth = 0;
        currentSurfaceHeight = 0;
    }

    private void detachVideoOutput() {
        if (currentSurfaceHolder != null && surfaceCallback != null)
            currentSurfaceHolder.removeCallback(surfaceCallback);
        if (currentTextureView != null && currentTextureView.getSurfaceTextureListener() == textureListener)
            currentTextureView.setSurfaceTextureListener(null);
        currentSurfaceHolder = null;
        currentTextureView = null;
        surfaceCallback = null;
        textureListener = null;
        currentVideoOutput = null;
        detachSurface();
    }

    private void setSurfaceSize(int width, int height) {
        currentSurfaceWidth = width;
        currentSurfaceHeight = height;
    }

    // ========== Track management ==========

    private void buildTracks() {
        if (mediaPlayer == null) {
            currentTracks = Tracks.EMPTY;
            return;
        }
        List<Tracks.Group> groups = new ArrayList<>();
        boolean hasVideo = false;
        int currentVideoId = mediaPlayer.getVideoTrack();
        MediaPlayer.TrackDescription[] videoTracks = mediaPlayer.getVideoTracks();
        if (videoTracks != null) {
            for (MediaPlayer.TrackDescription td : videoTracks) {
                if (td.id < 0) continue;
                hasVideo = true;
                Format format = new Format.Builder()
                        .setId("vlc:video:" + td.id)
                        .setLabel(td.name)
                        .setSampleMimeType(MimeTypes.VIDEO_UNKNOWN)
                        .build();
                groups.add(new Tracks.Group(new TrackGroup("vlc-video-" + td.id, format), false,
                        new int[]{C.FORMAT_HANDLED}, new boolean[]{td.id == currentVideoId}));
            }
        }
        int currentAudioId = mediaPlayer.getAudioTrack();
        MediaPlayer.TrackDescription[] audioTracks = mediaPlayer.getAudioTracks();
        if (audioTracks != null) {
            for (MediaPlayer.TrackDescription td : audioTracks) {
                if (td.id < 0) continue;
                Format format = new Format.Builder()
                        .setId("vlc:audio:" + td.id)
                        .setLabel(td.name)
                        .setSampleMimeType(MimeTypes.AUDIO_UNKNOWN)
                        .build();
                groups.add(new Tracks.Group(new TrackGroup("vlc-audio-" + td.id, format), false,
                        new int[]{C.FORMAT_HANDLED}, new boolean[]{td.id == currentAudioId}));
            }
        }
        int currentTextId = mediaPlayer.getSpuTrack();
        MediaPlayer.TrackDescription[] textTracks = mediaPlayer.getSpuTracks();
        if (textTracks != null) {
            for (MediaPlayer.TrackDescription td : textTracks) {
                if (td.id < 0) continue;
                Format.Builder fb = new Format.Builder()
                        .setId("vlc:text:" + td.id)
                        .setLabel(td.name)
                        .setSampleMimeType(MimeTypes.TEXT_UNKNOWN);
                if (td.name != null) {
                    String lower = td.name.toLowerCase(Locale.ROOT);
                    if (lower.contains("ass") || lower.contains("ssa"))
                        fb.setSampleMimeType(MimeTypes.TEXT_SSA);
                    else if (lower.contains("srt") || lower.contains("subrip"))
                        fb.setSampleMimeType(MimeTypes.APPLICATION_SUBRIP);
                    else if (lower.contains("vtt") || lower.contains("webvtt"))
                        fb.setSampleMimeType(MimeTypes.TEXT_VTT);
                }
                groups.add(new Tracks.Group(new TrackGroup("vlc-text-" + td.id, fb.build()), false,
                        new int[]{C.FORMAT_HANDLED}, new boolean[]{td.id == currentTextId}));
            }
        }
        currentTracks = groups.isEmpty() ? Tracks.EMPTY : new Tracks(groups);
        if (!hasVideo) videoSize = VideoSize.UNKNOWN;
    }

    private void updateVideoSize() {
        // libvlc 3.6 无法直接查询视频尺寸，尺寸由 videoLayoutListener.onNewVideoLayout 上报
    }

    private final IVLCVout.Callback voutCallback = new IVLCVout.Callback() {
        @Override
        public void onSurfacesCreated(IVLCVout vlcVout) {
        }
        @Override
        public void onSurfacesDestroyed(IVLCVout vlcVout) {
        }
    };

    private final IVLCVout.OnNewVideoLayoutListener videoLayoutListener = (vlcVout, width, height, visibleWidth, visibleHeight, sarNum, sarDen) -> {
        if (width > 0 && height > 0) {
            videoSize = new VideoSize(width, height);
            invalidateState();
        }
    };

    // ========== Public API for PlayerEngine ==========

    public void setTrack(List<Track> tracks) {
        if (mediaPlayer == null) return;
        for (Track track : tracks) {
            String[] parts = parseTrackFormat(track.getFormat());
            if (parts == null) continue;
            try {
                int id = Integer.parseInt(parts[2]);
                if (track.getType() == C.TRACK_TYPE_AUDIO) {
                    if (track.isSelected()) mediaPlayer.setAudioTrack(id);
                    else if (mediaPlayer.getAudioTrack() == id) mediaPlayer.setAudioTrack(-1);
                } else if (track.getType() == C.TRACK_TYPE_TEXT) {
                    if (track.isSelected()) mediaPlayer.setSpuTrack(id);
                    else if (mediaPlayer.getSpuTrack() == id) mediaPlayer.setSpuTrack(-1);
                } else if (track.getType() == C.TRACK_TYPE_VIDEO) {
                    if (track.isSelected()) mediaPlayer.setVideoTrack(id);
                }
            } catch (NumberFormatException ignored) {}
        }
        buildTracks();
        invalidateState();
    }

    public void resetTrack() {
        if (mediaPlayer == null) return;
        try {
            mediaPlayer.setAudioTrack(-1);
            mediaPlayer.setSpuTrack(-1);
        } catch (Exception ignored) {}
        buildTracks();
        invalidateState();
    }

    public long getTextOffsetMs() {
        return textOffsetMs;
    }

    public void setTextOffsetMs(long offsetMs) {
        this.textOffsetMs = offsetMs;
        if (mediaPlayer != null) {
            try { mediaPlayer.setSpuDelay(offsetMs * 1000L); } catch (Exception ignored) {}
        }
    }

    public long getAudioOffsetMs() {
        return audioOffsetMs;
    }

    public void setAudioOffsetMs(long offsetMs) {
        this.audioOffsetMs = offsetMs;
        if (mediaPlayer != null) {
            try { mediaPlayer.setAudioDelay(offsetMs * 1000L); } catch (Exception ignored) {}
        }
    }

    public void addSubtitleSize() {
        // libvlc 3.6.2 不支持运行时字幕缩放
    }

    public void subSubtitleSize() {
        // libvlc 3.6.2 不支持运行时字幕缩放
    }

    public void addSubtitlePosition() {
        // libvlc 3.6.2 不支持运行时字幕位置调整
    }

    public void subSubtitlePosition() {
        // libvlc 3.6.2 不支持运行时字幕位置调整
    }

    public void resetSubtitleStyle() {
        // libvlc 3.6.2 不支持运行时字幕样式重置
    }

    // ========== Helpers ==========

    @Nullable
    private String[] parseTrackFormat(String format) {
        if (format == null) return null;
        String id = format.split(",", 2)[0];
        String[] parts = id.split(":", 3);
        return parts.length == 3 && "vlc".equals(parts[0]) ? parts : null;
    }

    private SimpleBasePlayer.MediaItemData buildMediaItemData() {
        long durationUs = durationMs == C.TIME_UNSET ? C.TIME_UNSET : Util.msToUs(durationMs);
        return new SimpleBasePlayer.MediaItemData.Builder("vlc")
                .setMediaItem(mediaItem)
                .setMediaMetadata(mediaItem.mediaMetadata)
                .setTracks(currentTracks)
                .setIsSeekable(durationMs > 0)
                .setIsDynamic(durationMs == C.TIME_UNSET)
                .setDurationUs(durationUs)
                .build();
    }

    private void markRenderedFirstFrame() {
        if (!renderedFirstFrame) {
            renderedFirstFrame = true;
            reportRenderedFirstFrame = true;
        }
        if (!isSeeking && playerError == null && playbackState == Player.STATE_BUFFERING) {
            playbackState = Player.STATE_READY;
            loading = false;
        }
    }

    private boolean consumeRenderedFirstFrame() {
        boolean value = reportRenderedFirstFrame;
        reportRenderedFirstFrame = false;
        return value;
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
        if (currentSurfaceWidth > 0 && currentSurfaceHeight > 0) return new Size(currentSurfaceWidth, currentSurfaceHeight);
        return Size.UNKNOWN;
    }

    private long sanitizePosition(long positionMs) {
        return positionMs == C.TIME_UNSET ? 0 : Math.max(0, positionMs);
    }

    private void setError(String message) {
        playerError = new PlaybackException(message, null, PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK);
        playbackState = Player.STATE_IDLE;
        loading = false;
        invalidateState();
    }
}
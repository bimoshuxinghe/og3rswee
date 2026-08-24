package com.fongmi.android.tv.player.exo;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Display;
import android.view.WindowManager;
import android.view.accessibility.CaptioningManager;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.exoplayer.RenderersFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.TrackSelector;
import androidx.media3.exoplayer.util.EventLogger;
import androidx.media3.ui.CaptionStyleCompat;
import androidx.media3.ui.PlayerView;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.bean.Drm;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.UrlUtil;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class ExoUtil {

    public static void setPlayerView(PlayerView view) {
        view.setRender(PlayerSetting.getRender());
        view.getSubtitleView().setStyle(getCaptionStyle());
        view.getSubtitleView().setApplyEmbeddedStyles(true);
        view.getSubtitleView().setApplyEmbeddedFontSizes(false);
        if (PlayerSetting.getSubtitlePosition() != 0) view.getSubtitleView().setBottomPosition(PlayerSetting.getSubtitlePosition());
        if (PlayerSetting.getSubtitleTextSize() != 0) view.getSubtitleView().setFractionalTextSize(PlayerSetting.getSubtitleTextSize());
    }

    public static ExoPlayer buildPlayer(int decode, Player.Listener listener) {
        ExoPlayer player = new ExoPlayer.Builder(App.get()).setLoadControl(buildLoadControl()).setTrackSelector(buildTrackSelector()).setRenderersFactory(buildRenderersFactory(getRenderMode(decode))).setMediaSourceFactory(buildMediaSourceFactory()).build();
        if (BuildConfig.DEBUG) player.addAnalyticsListener(new EventLogger());
        player.setAudioAttributes(AudioAttributes.DEFAULT, true);
        player.setHandleAudioBecomingNoisy(true);
        player.setPlayWhenReady(true);
        player.addListener(listener);
        return player;
    }

    public static void applyDolbyVisionPolicy(Player player) {
        if (allowDolbyVision()) return;
        Tracks tracks = player.getCurrentTracks();
        TrackGroup bestGroup = null;
        int bestIndex = -1;
        long bestScore = -1;
        boolean hasDolbyVision = false;
        boolean selectedDolbyVision = false;
        boolean selectedNonDolbyVision = false;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < group.length; i++) {
                Format format = group.getTrackFormat(i);
                boolean dolbyVision = isDolbyVision(format);
                hasDolbyVision |= dolbyVision;
                selectedDolbyVision |= dolbyVision && group.isTrackSelected(i);
                selectedNonDolbyVision |= !dolbyVision && group.isTrackSelected(i);
                if (dolbyVision || !group.isTrackSupported(i)) continue;
                long score = videoScore(format);
                if (score > bestScore) {
                    bestScore = score;
                    bestGroup = group.getMediaTrackGroup();
                    bestIndex = i;
                }
            }
        }
        if (!hasDolbyVision || bestGroup == null || (selectedNonDolbyVision && !selectedDolbyVision)) return;
        player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon().setOverrideForType(new TrackSelectionOverride(bestGroup, List.of(bestIndex))).build());
    }

    public static boolean hasSelectedDolbyVision(Player player) {
        for (Tracks.Group group : player.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) continue;
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i) && isDolbyVision(group.getTrackFormat(i))) return true;
            }
        }
        return false;
    }

    public static MediaItem getMediaItem(PlaySpec spec, int decode) {
        MediaItem.Builder builder = new MediaItem.Builder().setUri(spec.getUri());
        builder.setSubtitleConfigurations(buildSubtitleConfigs(spec.getSubs()));
        builder.setDrmConfiguration(buildDrmConfig(spec.getDrm()));
        builder.setRequestMetadata(buildRequestMetadata(spec));
        builder.setMediaMetadata(spec.getMetadata());
        builder.setAdblock(Setting.isAdblock());
        builder.setMimeType(spec.getFormat());
        builder.setImageDurationMs(15000);
        builder.setMediaId(spec.getKey());
        builder.setDecode(decode);
        return builder.build();
    }

    public static String getMimeType(int errorCode) {
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED || errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED) return MimeTypes.APPLICATION_M3U8;
        if (errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED || errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED) return MimeTypes.APPLICATION_OCTET_STREAM;
        return null;
    }

    public static Map<String, String> extractHeaders(MediaItem item) {
        Bundle extras = item.requestMetadata.extras;
        if (extras == null) return new HashMap<>();
        return extras.keySet().stream().filter(key -> extras.getString(key) != null).collect(Collectors.toMap(key -> key, extras::getString));
    }

    private static int getRenderMode(int decode) {
        return decode == PlayerEngine.HARD ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER;
    }

    private static CaptionStyleCompat getCaptionStyle() {
        return PlayerSetting.isCaption() ? CaptionStyleCompat.createFromCaptionStyle(((CaptioningManager) App.get().getSystemService(Context.CAPTIONING_SERVICE)).getUserStyle()) : new CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_OUTLINE, Color.BLACK, null);
    }

    private static LoadControl buildLoadControl() {
        int factor = Math.max(PlayerSetting.getBuffer(), 2);
        int minBufferMs = DefaultLoadControl.DEFAULT_MIN_BUFFER_MS * factor;
        int maxBufferMs = DefaultLoadControl.DEFAULT_MAX_BUFFER_MS * factor;
        return new DefaultLoadControl.Builder().setBufferDurationsMs(minBufferMs, maxBufferMs, 500, 1500).build();
    }

    private static TrackSelector buildTrackSelector() {
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(App.get());
        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        if (PlayerSetting.isPreferAAC()) builder.setPreferredAudioMimeType(MimeTypes.AUDIO_AAC);
        if (!allowDolbyVision()) builder.setPreferredVideoMimeTypes(MimeTypes.VIDEO_H265, MimeTypes.VIDEO_H264, MimeTypes.VIDEO_AV1, MimeTypes.VIDEO_VP9, MimeTypes.VIDEO_VP8);
        builder.setPreferredTextLanguage(Locale.getDefault().getISO3Language());
        builder.setTunnelingEnabled(PlayerSetting.isTunnel());
        builder.setForceHighestSupportedBitrate(allowDolbyVision());
        trackSelector.setParameters(builder.build());
        return trackSelector;
    }

    private static RenderersFactory buildRenderersFactory(int renderMode) {
        return new DefaultRenderersFactory(App.get()) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput, boolean enableAudioOutputPlaybackParameters) {
                DefaultAudioSink.Builder builder = new DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParameters);
                AudioSink sink = builder.build();
                if (com.fongmi.android.tv.setting.Setting.isVoskEnabled()) {
                    // 独立旁路采集：不向播放链路注册任何 AudioProcessor，
                    // 由 VoskAudioSink 在 handleBuffer 入口复制 PCM，播放链路完全解耦
                    sink = new com.fongmi.android.tv.player.VoskAudioSink(sink);
                }
                return sink;
            }
        }.setEnableDecoderFallback(true).setExtensionRendererMode(renderMode);
    }

    private static MediaSource.Factory buildMediaSourceFactory() {
        return new MediaSourceFactory();
    }

    private static boolean allowDolbyVision() {
        // Removed hasDolbyVisionDisplay() check: allow DV playback when device has
        // DV hardware decoder, even if display doesn't report DV support. This enables
        // DV on devices with DV decoders but non-DV-certified displays. The renderer's
        // fallback mechanism handles decoder failures gracefully.
        return PlayerSetting.isExoDolbyVisionPassthrough() && hasDolbyVisionDecoder();
    }

    private static boolean hasDolbyVisionDecoder() {
        try {
            return !MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, PlayerSetting.isTunnel()).isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasDolbyVisionDisplay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;
        WindowManager manager = (WindowManager) App.get().getSystemService(Context.WINDOW_SERVICE);
        if (manager == null) return false;
        Display display = manager.getDefaultDisplay();
        if (display == null) return false;
        for (int type : display.getHdrCapabilities().getSupportedHdrTypes()) {
            if (type == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) return true;
        }
        return false;
    }

    private static boolean isDolbyVision(Format format) {
        if (MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) return true;
        String codecs = format.codecs == null ? "" : format.codecs.toLowerCase(Locale.US);
        return codecs.contains("dvhe") || codecs.contains("dvh1") || codecs.contains("dva1") || codecs.contains("dvav");
    }

    private static long videoScore(Format format) {
        long width = Math.max(format.width, 0);
        long height = Math.max(format.height, 0);
        long bitrate = Math.max(format.bitrate, 0);
        return width * height * 1_000_000L + bitrate;
    }

    private static MediaItem.RequestMetadata buildRequestMetadata(PlaySpec spec) {
        return new MediaItem.RequestMetadata.Builder().setMediaUri(spec.getUri()).setExtras(PlayerHelper.toBundle(spec.getHeaders())).build();
    }

    private static List<MediaItem.SubtitleConfiguration> buildSubtitleConfigs(List<Sub> subs) {
        List<MediaItem.SubtitleConfiguration> configs = new ArrayList<>();
        if (subs != null) for (Sub sub : subs) configs.add(buildSubConfig(sub));
        return configs;
    }

    private static MediaItem.SubtitleConfiguration buildSubConfig(Sub sub) {
        return new MediaItem.SubtitleConfiguration.Builder(Uri.parse(UrlUtil.convert(sub.getUrl()))).setLabel(sub.getName()).setMimeType(sub.getFormat()).setSelectionFlags(sub.getFlag()).setLanguage(sub.getLang()).build();
    }

    private static MediaItem.DrmConfiguration buildDrmConfig(Drm drm) {
        return drm == null ? null : new MediaItem.DrmConfiguration.Builder(drm.getUUID()).setMultiSession(!C.CLEARKEY_UUID.equals(drm.getUUID())).setForceDefaultLicenseUri(drm.isForceKey()).setLicenseRequestHeaders(drm.getHeader()).setLicenseUri(drm.getKey()).build();
    }
}

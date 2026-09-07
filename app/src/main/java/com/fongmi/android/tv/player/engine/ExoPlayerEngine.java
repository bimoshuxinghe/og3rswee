package com.fongmi.android.tv.player.engine;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MediaTitle;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.exo.DurationProbe;
import com.fongmi.android.tv.player.exo.ErrorMsgProvider;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.player.exo.TrackUtil;
import com.fongmi.android.tv.player.mpv.MpvMedia;
import com.fongmi.android.tv.server.process.IsoStream;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ExoPlayerEngine implements PlayerEngine {

    private static final String TAG = "ExoPlayerEngine";
    /** format 型恢复的尝试上限；换 MIME 不解决根因，超过即停止重播。 */
    private static final int MAX_FORMAT_RETRY = 2;

    private final ErrorMsgProvider provider;
    private final Handler handler;
    private PlaySpec spec;
    private Player player;
    private int decode;
    private boolean isRtspStream;
    /**
     * 同一份 spec 已尝试过的 format 型恢复次数。
     *
     * <p>{@code retryFormat} 只是换个 MIME 重新起播，并不改变失败根因：HLS 列表解析
     * 失败时改成 octet-stream 只会再次失败。而宿主把 RECOVERED 当作「已恢复」，
     * 既不计数也不上报，于是同一错误可以无限次重播当前位置——用户看到的就是
     * 进度条在 0 附近反复横跳。这里给出次数上限，超过即判 FATAL，让宿主能报错收场。
     */
    private int formatRetry;
    private volatile boolean isoResolving;
    private volatile String isoOriginalUrl;
    private volatile String isoProxyUrl;

    public ExoPlayerEngine(int decode, Player.Listener listener) {
        this.player = ExoUtil.buildPlayer(decode, listener);
        this.provider = new ErrorMsgProvider();
        this.decode = decode;
        this.handler = new Handler(Looper.getMainLooper());
        this.player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                ExoUtil.applyDolbyVisionPolicy(player);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) maybeProbeDuration();
            }
        });
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void release() {
        DurationProbe.clear();
        player.release();
    }

    @Override
    public Player rebuild(Player.Listener listener) {
        DurationProbe.clear();
        player.release();
        player = ExoUtil.buildPlayer(decode, listener);
        player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                ExoUtil.applyDolbyVisionPolicy(player);
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) maybeProbeDuration();
            }
        });
        return player;
    }

    @Override
    public boolean isRepeatOne() {
        return player.getRepeatMode() == Player.REPEAT_MODE_ONE;
    }

    @Override
    public void setRepeatOne(boolean repeat) {
        player.setRepeatMode(repeat ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
    }

    @Override
    public int getDecode() {
        return decode;
    }

    @Override
    public void setDecode(int decode) {
        this.decode = decode;
    }

    @Override
    public boolean isHard() {
        return decode == HARD;
    }

    @Override
    public String getDecodeText() {
        return ResUtil.getStringArray(R.array.select_decode)[decode];
    }

    @Override
    public void start(PlaySpec spec) {
        start(spec, C.TIME_UNSET);
    }

    @Override
    public void start(PlaySpec spec, long positionMs) {
        this.spec = spec;
        // 换源/换集是一次全新尝试，format 型恢复计数必须清零，
        // 否则上一次播放用掉的配额会让新起播还没重试就被判 FATAL。
        this.formatRetry = 0;
        // 换源后旧的无时长估算已失效，必须清掉（新源 STATE_READY 后会重新探测）
        DurationProbe.clear();
        // 检测是否为 RTSP 流
        this.isRtspStream = spec.getUrl() != null && spec.getUrl().startsWith("rtsp://");
        // 检测是否为 ISO 镜像，需要先解析文件系统再通过代理播放
        String url = spec.getUrl();
        if (url != null && MpvMedia.isBluRayIso(url)) {
            if (isoResolving) return;
            if (TextUtils.equals(url, isoOriginalUrl) && !TextUtils.isEmpty(isoProxyUrl)) {
                startInternal(positionMs);
            } else if (!TextUtils.equals(url, isoOriginalUrl)) {
                resolveIso(url, positionMs);
                return;
            } else {
                startInternal(positionMs);
            }
        } else {
            isoOriginalUrl = null;
            isoProxyUrl = null;
            startInternal(positionMs);
        }
    }

    /**
     * 异步解析 ISO 镜像文件系统，找到内部视频文件并注册代理 URL。
     * 解析完成后在主线程调用 startInternal 进行播放。
     */
    private void resolveIso(String url, long positionMs) {
        isoResolving = true;
        isoOriginalUrl = null;
        isoProxyUrl = null;
        new Thread(() -> {
            try {
                Map<String, String> hdrs = spec != null ? spec.getHeaders() : null;
                String proxyUrl = IsoStream.register(url, hdrs);
                if (!TextUtils.isEmpty(proxyUrl)) {
                    isoOriginalUrl = url;
                    isoProxyUrl = proxyUrl;
                }
            } catch (Exception e) {
                Log.e("ExoPlayerEngine", "ISO resolution failed: " + e.getMessage(), e);
            } finally {
                isoResolving = false;
                handler.post(() -> {
                    if (spec == null) return;
                    String currentUrl = spec.getUrl();
                    if (!TextUtils.equals(currentUrl, url)) return;
                    startInternal(positionMs);
                });
            }
        }, "exo-iso-resolver").start();
    }

    @Override
    public void setMetadata(MediaMetadata data) {
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(data).build());
    }

    @Override
    public boolean isLive() {
        return effectiveDuration() < TimeUnit.MINUTES.toMillis(1) || player.isCurrentMediaItemLive();
    }

    @Override
    public boolean isVod() {
        return effectiveDuration() > TimeUnit.MINUTES.toMillis(1) && !player.isCurrentMediaItemLive();
    }

    /** 有效时长：EXO 报告的时长；无时长流（TS 直链等）退回 DurationProbe 的估算时长 */
    private long effectiveDuration() {
        long d = player.getDuration();
        if (d == C.TIME_UNSET || d <= 0) d = DurationProbe.getEstimated();
        return d;
    }

    /**
     * 无时长流探测：EXO 对 TS 直链等 progressive 源无法给出时长，进度条被禁用。
     * 此时启动 DurationProbe（总大小探测 + 码率采样）为进度条提供估算时长。
     * <p>
     * 重要：直播流与本地代理地址一律不探测——
     * <ul>
     *   <li>直播时长本就无限（TIME_UNSET 是其正常状态），不需要估算；</li>
     *   <li>央视频等本地代理地址为 127.0.0.1:{@code /ysp?id=...}，对其发 HEAD/Range
     *       探测会穿透到代理内部状态机，干扰直播取址与分片拉取，导致 403/起播失败。</li>
     * </ul>
     */
    private void maybeProbeDuration() {
        if (player.getDuration() != C.TIME_UNSET) {
            DurationProbe.clear();
            return;
        }
        // 直播流不探测：时长无限是其正常状态，估算无意义
        if (player.isCurrentMediaItemLive()) {
            DurationProbe.clear();
            return;
        }
        String url = spec != null ? spec.getUrl() : null;
        // 本地代理地址（央视频 /ysp、ISO 代理等）不探测：探测请求会穿透代理状态机
        if (url == null || !url.startsWith("http") || isLocalProxy(url) || DurationProbe.isTracking(url)) return;
        Map<String, String> headers = spec.getHeaders() != null ? spec.getHeaders() : new HashMap<>();
        DurationProbe.start(url, headers, () -> {
            long buffered = player.getBufferedPosition();
            return buffered > 0 ? Long.valueOf(buffered) : null;
        }, () -> player.getCurrentPosition());
    }

    /** 是否为本地代理地址（127.0.0.1 / localhost / 局域网内网地址） */
    private static boolean isLocalProxy(String url) {
        String lower = url.toLowerCase();
        if (lower.contains("://127.") || lower.contains("://localhost") || lower.contains("://[::1]")) return true;
        int schemeEnd = lower.indexOf("://");
        if (schemeEnd < 0) return false;
        int hostEnd = lower.indexOf('/', schemeEnd + 3);
        String host = hostEnd < 0 ? lower.substring(schemeEnd + 3) : lower.substring(schemeEnd + 3, hostEnd);
        int colon = host.indexOf(':');
        if (colon >= 0) host = host.substring(0, colon);
        // 10.x / 192.168.x / 172.16-31.x 均视为本地/内网代理
        if (host.startsWith("10.") || host.startsWith("192.168.")) return true;
        if (host.startsWith("172.")) {
            try {
                String[] p = host.split("\\.");
                if (p.length >= 2) {
                    int second = Integer.parseInt(p[1]);
                    if (second >= 16 && second <= 31) return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    @Override
    public void setTrack(List<Track> tracks) {
        TrackUtil.setTrackSelection(player, tracks);
    }

    @Override
    public void resetTrack() {
        TrackUtil.reset(player);
    }

    @Override
    public boolean haveTrack(int type) {
        return TrackUtil.count(getCurrentTracks(), type) > 0;
    }

    @Override
    public Tracks getCurrentTracks() {
        return player.getCurrentTracks();
    }

    @Override
    public boolean haveTitle() {
        return !player.getCurrentMediaTitles().isEmpty();
    }

    @Override
    public List<MediaTitle> getCurrentMediaTitles() {
        return player.getCurrentMediaTitles();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return provider.get(e);
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        // 对于 RTSP 流，如果是硬解码失败，直接尝试软解码
        if (isRtspStream && (e.errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED || 
                            e.errorCode == PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED || 
                            e.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED)) {
            if (decode == HARD) {
                return ErrorAction.DECODE;
            }
        }
        
        return switch (e.errorCode) {
            case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> seekToDefaultPosition();
            case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED, PlaybackException.ERROR_CODE_DECODING_FAILED -> retryDolbyVisionOrDecode();
            case PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> retryFormat(e.errorCode);
            default -> ErrorAction.FATAL;
        };
    }

    private void startInternal(long position) {
        // ISO 代理 URL 替换：如果已解析出代理 URL，临时替换 spec 的 URL
        String savedUrl = null;
        if (spec != null && !TextUtils.isEmpty(isoProxyUrl) && TextUtils.equals(spec.getUrl(), isoOriginalUrl)) {
            savedUrl = spec.getUrl();
            spec.setUrl(isoProxyUrl);
        }
        try {
            // 对于 RTSP 流，可能需要特殊处理
            MediaItem item = ExoUtil.getMediaItem(spec, decode);
            if (isRtspStream) {
                MediaItem.Builder builder = item.buildUpon();
                // 确保 RTSP 流使用正确的 MIME 类型
                if (spec.getFormat() == null) {
                    builder.setMimeType(MimeTypes.APPLICATION_RTSP);
                }
                item = builder.build();
            }

            // 先确保播放器处于允许 setMediaItem(empty playlist) 的合法状态
            ensureIdleOrEnded(player);
            try {
                player.setMediaItem(item, position);
                player.prepare();
                player.play();
            } catch (Exception e) {
                Log.w("ExoPlayerEngine", "startInternal failed, retry after stop+clear.", e);
                try {
                    player.stop();
                } catch (Exception ignored) {
                }
                try {
                    player.clearMediaItems();
                } catch (Exception ignored) {
                }
                try {
                    player.setMediaItem(item, position);
                    player.prepare();
                    player.play();
                } catch (Exception e2) {
                    Log.e("ExoPlayerEngine", "startInternal retry failed.", e2);
                }
            }
        } finally {
            if (savedUrl != null) spec.setUrl(savedUrl);
        }
    }

    private static void ensureIdleOrEnded(Player player) {
        if (player == null) return;
        int state = player.getPlaybackState();
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) return;
        try {
            player.stop();
        } catch (Exception ignored) {
        }
        state = player.getPlaybackState();
        if (state == Player.STATE_IDLE || state == Player.STATE_ENDED) return;
        try {
            player.clearMediaItems();
        } catch (Exception ignored) {
        }
    }

    private ErrorAction seekToDefaultPosition() {
        try {
            player.seekToDefaultPosition();
            player.prepare();
        } catch (Exception e) {
            Log.w("ExoPlayerEngine", "seekToDefaultPosition failed.", e);
        }
        return ErrorAction.RECOVERED;
    }

    private ErrorAction retryDolbyVisionOrDecode() {
        if (PlayerSetting.isExoDolbyVisionPassthrough() && ExoUtil.hasSelectedDolbyVision(player)) {
            long position = player.getCurrentPosition();
            // 直播：按独立直播开关关闭 DV 透传，避免影响点播总开关
            PlayerSetting.putExoDolbyVisionPassthroughLive(false);
            PlayerSetting.putExoDolbyVisionPassthrough(false);
            startInternal(position);
            return ErrorAction.RECOVERED;
        }
        return ErrorAction.DECODE;
    }

    private ErrorAction retryFormat(int errorCode) {
        // 恢复次数用尽后不再重播：换 MIME 无法修复「列表本身有问题」这类失败，
        // 继续重播只会让播放器在同一个位置反复重启（进度条在 0 附近横跳），
        // 且宿主永远等不到 FATAL，错误弹不出来、用户只能杀进程。
        if (formatRetry >= MAX_FORMAT_RETRY) {
            Log.w(TAG, "format 型恢复已尝试 " + formatRetry + " 次仍失败，停止重试");
            return ErrorAction.FATAL;
        }
        formatRetry++;
        spec.setFormat(ExoUtil.getMimeType(errorCode));
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }
}

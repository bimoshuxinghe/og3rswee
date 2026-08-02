package com.fongmi.android.tv.player.engine;

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
import com.fongmi.android.tv.player.exo.ErrorMsgProvider;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.player.exo.TrackUtil;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;
import java.util.concurrent.TimeUnit;

import android.util.Log;

public class ExoPlayerEngine implements PlayerEngine {

    private final ErrorMsgProvider provider;
    private PlaySpec spec;
    private Player player;
    private int decode;
    private boolean isRtspStream;

    public ExoPlayerEngine(int decode, Player.Listener listener) {
        this.player = ExoUtil.buildPlayer(decode, listener);
        this.provider = new ErrorMsgProvider();
        this.decode = decode;
        this.player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                ExoUtil.applyDolbyVisionPolicy(player);
            }
        });
    }

    @Override
    public Player getPlayer() {
        return player;
    }

    @Override
    public void release() {
        player.release();
    }

    @Override
    public Player rebuild(Player.Listener listener) {
        player.release();
        player = ExoUtil.buildPlayer(decode, listener);
        player.addListener(new Player.Listener() {
            @Override
            public void onTracksChanged(Tracks tracks) {
                ExoUtil.applyDolbyVisionPolicy(player);
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
        // 检测是否为 RTSP 流
        this.isRtspStream = spec.getUrl() != null && spec.getUrl().startsWith("rtsp://");
        startInternal(positionMs);
    }

    @Override
    public void setMetadata(MediaMetadata data) {
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(data).build());
    }

    @Override
    public boolean isLive() {
        return player.getDuration() < TimeUnit.MINUTES.toMillis(1) || player.isCurrentMediaItemLive();
    }

    @Override
    public boolean isVod() {
        return player.getDuration() > TimeUnit.MINUTES.toMillis(1) && !player.isCurrentMediaItemLive();
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
            PlayerSetting.putExoDolbyVisionPassthrough(false);
            startInternal(position);
            return ErrorAction.RECOVERED;
        }
        return ErrorAction.DECODE;
    }

    private ErrorAction retryFormat(int errorCode) {
        spec.setFormat(ExoUtil.getMimeType(errorCode));
        startInternal(player.getCurrentPosition());
        return ErrorAction.RECOVERED;
    }
}

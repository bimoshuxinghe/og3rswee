package com.fongmi.android.tv.player.engine;

import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MediaTitle;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.player.exo.ExoUtil;
import com.fongmi.android.tv.player.mpv.MpvSimplePlayer;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class MpvPlayerEngine implements PlayerEngine {

    private MpvSimplePlayer player;
    private int decode;

    public MpvPlayerEngine(int decode, Player.Listener listener) {
        this.decode = decode;
        this.player = buildPlayer(listener);
    }

    public static boolean isAvailable() {
        return MpvSimplePlayer.isAvailable();
    }

    public static String getAvailabilityError() {
        return MpvSimplePlayer.getAvailabilityError();
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
        return player = buildPlayer(listener);
    }

    @Override
    public int getDecode() {
        return decode;
    }

    @Override
    public void setDecode(int decode) {
        this.decode = decode;
        player.setDecode(decode);
    }

    @Override
    public boolean canSetDecodeWithoutRebuild() {
        return true;
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
        player.setMediaItem(ExoUtil.getMediaItem(spec, decode), positionMs);
        player.prepare();
        player.play();
    }

    @Override
    public void setMetadata(MediaMetadata data) {
        MediaItem current = player.getCurrentMediaItem();
        if (current != null) player.replaceMediaItem(player.getCurrentMediaItemIndex(), current.buildUpon().setMediaMetadata(data).build());
    }

    @Override
    public boolean isLive() {
        return player.getDuration() < TimeUnit.MINUTES.toMillis(1);
    }

    @Override
    public boolean isVod() {
        return player.getDuration() > TimeUnit.MINUTES.toMillis(1);
    }

    @Override
    public void setTrack(List<Track> tracks) {
        player.setTrack(tracks);
    }

    @Override
    public void resetTrack() {
        player.resetTrack();
    }

    @Override
    public long getTextOffsetMs() {
        return player.getTextOffsetMs();
    }

    @Override
    public void setTextOffsetMs(long offsetMs) {
        player.setTextOffsetMs(offsetMs);
    }

    @Override
    public long getAudioOffsetMs() {
        return player.getAudioOffsetMs();
    }

    @Override
    public void setAudioOffsetMs(long offsetMs) {
        player.setAudioOffsetMs(offsetMs);
    }

    @Override
    public boolean canSetSubtitleStyle() {
        return true;
    }

    @Override
    public void addSubtitleSize() {
        player.addSubtitleSize();
    }

    @Override
    public void subSubtitleSize() {
        player.subSubtitleSize();
    }

    @Override
    public void addSubtitlePosition() {
        player.addSubtitlePosition();
    }

    @Override
    public void subSubtitlePosition() {
        player.subSubtitlePosition();
    }

    @Override
    public void resetSubtitleStyle() {
        player.resetSubtitleStyle();
    }

    @Override
    public boolean haveTrack(int type) {
        return player.getCurrentTracks().containsType(type);
    }

    @Override
    public Tracks getCurrentTracks() {
        return player.getCurrentTracks();
    }

    @Override
    public List<MediaTitle> getCurrentMediaTitles() {
        return Collections.emptyList();
    }

    @Override
    public String getErrorMessage(PlaybackException e) {
        return e.getMessage() == null ? "MPV 播放失败" : e.getMessage();
    }

    @Override
    public ErrorAction handleError(PlaybackException e) {
        return ErrorAction.FATAL;
    }

    private MpvSimplePlayer buildPlayer(Player.Listener listener) {
        MpvSimplePlayer player = new MpvSimplePlayer(App.get(), decode);
        player.addListener(listener);
        player.setPlayWhenReady(true);
        return player;
    }
}

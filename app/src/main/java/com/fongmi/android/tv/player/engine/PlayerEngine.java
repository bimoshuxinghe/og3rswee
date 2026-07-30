package com.fongmi.android.tv.player.engine;

import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MediaTitle;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;

import com.fongmi.android.tv.bean.Track;

import java.util.Collections;
import java.util.List;

public interface PlayerEngine {

    int SOFT = 0;
    int HARD = 1;

    Player getPlayer();

    void release();

    Player rebuild(Player.Listener listener);

    int getDecode();

    void setDecode(int decode);

    default boolean canSetDecodeWithoutRebuild() {
        return false;
    }

    boolean isHard();

    String getDecodeText();

    void start(PlaySpec spec);

    default void start(PlaySpec spec, long positionMs) {
        start(spec);
        if (positionMs > 0) getPlayer().seekTo(positionMs);
    }

    void setMetadata(MediaMetadata data);

    boolean isLive();

    boolean isVod();

    void setTrack(List<Track> tracks);

    void resetTrack();

    boolean haveTrack(int type);

    Tracks getCurrentTracks();

    default boolean haveTitle() {
        return false;
    }

    default boolean isRepeatOne() {
        return false;
    }

    default void setRepeatOne(boolean repeat) {
    }

    default long getTextOffsetMs() {
        return 0;
    }

    default void setTextOffsetMs(long offsetMs) {
    }

    default long getAudioOffsetMs() {
        return 0;
    }

    default void setAudioOffsetMs(long offsetMs) {
    }

    default boolean canSetSubtitleStyle() {
        return false;
    }

    default void addSubtitleSize() {
    }

    default void subSubtitleSize() {
    }

    default void addSubtitlePosition() {
    }

    default void subSubtitlePosition() {
    }

    default void resetSubtitleStyle() {
    }

    default List<MediaTitle> getCurrentMediaTitles() {
        return Collections.emptyList();
    }

    String getErrorMessage(PlaybackException e);

    ErrorAction handleError(PlaybackException e);

    enum ErrorAction {
        RECOVERED,
        DECODE,
        FATAL
    }
}

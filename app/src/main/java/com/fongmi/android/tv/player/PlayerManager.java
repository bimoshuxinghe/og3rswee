package com.fongmi.android.tv.player;

import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import com.github.catvod.crawler.SpiderDebug;
import java.io.IOException;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MediaTitle;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Tracks;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.danmaku.DanmakuConfig;
import androidx.media3.ui.danmaku.DanmakuController;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.impl.ParseCallback;
import com.fongmi.android.tv.ads.AdInterceptor;
import com.fongmi.android.tv.player.engine.ExoPlayerEngine;
import com.fongmi.android.tv.player.engine.MpvPlayerEngine;
import com.fongmi.android.tv.player.engine.PlaySpec;
import com.fongmi.android.tv.player.engine.PlayerEngine;
import com.fongmi.android.tv.player.mpv.MpvMedia;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;
import com.google.common.net.HttpHeaders;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PlayerManager implements ParseCallback {

    private static final String TAG = "PlayerManager";
    private final Runnable runnable;
    private final Callback callback;
    private DanmakuController danmakuController;
    private PlayerEngine engine;
    private VideoSize videoSize;
    private ParseJob parseJob;
    private PlaySpec spec;
    private Player player;
    private long pendingStartPositionMs;
    private String currentDanmakuUrl;
    private String currentDanmakuKey;
    private String loadingDanmakuKey;
    private long danmakuLoadStartedAtMs;
    private boolean danmakuLoadInProgress;
    private static final long DANMAKU_FORCE_RELOAD_DEBOUNCE_MS = 10000;

    private WsDanmakuClient wsDanmakuClient;
    private boolean initTrack;
    private int retry;
    private AdInterceptor adInterceptor;

    public PlayerManager(Callback callback) {
        this.runnable = () -> callback.onError(ResUtil.getString(R.string.error_play_timeout));
        this.engine = createEngine(PlayerEngine.HARD);
        this.player = engine.getPlayer();
        this.callback = callback;
        this.pendingStartPositionMs = C.TIME_UNSET;

        // 初始化智能广告拦截器
        if (com.fongmi.android.tv.setting.Setting.isAdblock()) {
            this.adInterceptor = new AdInterceptor(App.get(), this);
            this.engine.addAdListener(adInterceptor);
        }
    }

    public void release() {
        try { if (player != null) player.removeListener(listener); } catch (Exception e) { e.printStackTrace(); }
        App.removeCallbacks(runnable);
        releaseDanmakuController();
        if (engine == null) return;
        try { engine.release(); } catch (Exception e) { e.printStackTrace(); }
        engine = null;
        player = null;
    }

    public Player getPlayer() {
        return player;
    }

    public Tracks getCurrentTracks() {
        return engine.getCurrentTracks();
    }

    public List<MediaTitle> getCurrentMediaTitles() {
        return engine.getCurrentMediaTitles();
    }

    public MediaItem getCurrentMediaItem() {
        return player.getCurrentMediaItem();
    }

    public int getPlaybackState() {
        return player.getPlaybackState();
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    public boolean isReleased() {
        return player == null;
    }

    public String getUrl() {
        return spec != null ? spec.getUrl() : null;
    }

    public String getKey() {
        return spec != null ? spec.getKey() : null;
    }

    public List<Danmaku> getDanmakus() {
        return spec != null ? spec.getDanmakus() : null;
    }

    public MediaMetadata getMetadata() {
        return spec != null ? spec.getMetadata() : null;
    }

    public Map<String, String> getHeaders() {
        return spec == null || spec.getHeaders() == null ? new HashMap<>() : spec.getHeaders();
    }

    public float getSpeed() {
        return player.getPlaybackParameters().speed;
    }

    public boolean isEmpty() {
        return spec == null || TextUtils.isEmpty(spec.getUrl());
    }

    public boolean isPortrait() {
        return getVideoHeight() > getVideoWidth();
    }

    public boolean isLandscape() {
        return getVideoWidth() > getVideoHeight();
    }

    public boolean isLive() {
        return engine.isLive();
    }

    public boolean isVod() {
        return engine.isVod();
    }

    public boolean haveTrack(int type) {
        return engine.haveTrack(type);
    }

    public boolean haveTitle() {
        return engine.haveTitle();
    }

    public boolean haveDanmaku() {
        return getDanmakus() != null && getDanmakus().stream().anyMatch(Danmaku::isSelected);
    }

    public boolean canSetOpening(long position, long duration) {
        return position > 0 && duration > 0 && position <= Constant.getOpEdLimit(duration);
    }

    public boolean canSetEnding(long position, long duration) {
        return position > 0 && duration > 0 && duration - position <= Constant.getOpEdLimit(duration);
    }

    public int getVideoWidth() {
        return videoSize == null ? 0 : videoSize.width;
    }

    public int getVideoHeight() {
        return videoSize == null ? 0 : videoSize.height;
    }

    public long getPosition() {
        return player.getCurrentPosition();
    }

    public String getSizeText() {
        return (getVideoWidth() == 0 && getVideoHeight() == 0) ? "" : getVideoWidth() + " x " + getVideoHeight();
    }

    public String getSpeedText() {
        return String.format(Locale.getDefault(), "%.2f", getSpeed());
    }

    public String getDecodeText() {
        return engine.getDecodeText();
    }

    public String getEngineText() {
        return ResUtil.getString(isMpvEngine() ? R.string.play_mpv : R.string.play_exo);
    }

    public String getPositionTime(long delta) {
        long time = Math.max(0, Math.min(getPosition() + delta, Math.max(0, getDuration())));
        return Util.timeMs(time);
    }

    public long getDuration() {
        return player.getDuration();
    }

    public String getDurationTime() {
        return Util.timeMs(Math.max(0, getDuration()));
    }

    public void setSub(Sub sub) {
        if (spec != null) spec.setSub(sub);
        setMediaItem(Constant.TIMEOUT_PLAY, getSwitchPosition());
    }

    public void setFormat(String format) {
        if (spec != null) spec.setFormat(format);
        setMediaItem();
    }

    public void setTitle(MediaTitle title) {
        if (spec != null) spec.setUrl(spec.getUri().buildUpon().fragment("title=" + title.index).build().toString());
        setMediaItem();
        seekTo(0);
    }

    public static MediaMetadata buildMetadata(String title, String artist, String artUri) {
        Uri artwork = TextUtils.isEmpty(artUri) ? null : Uri.parse(artUri);
        return new MediaMetadata.Builder().setTitle(title).setArtist(artist).setArtworkUri(artwork).build();
    }

    public void setMetadata(MediaMetadata data) {
        if (spec != null) spec.setMetadata(data);
        try { engine.setMetadata(data); } catch (Exception e) { e.printStackTrace(); }
    }

    public void setDanmakuController(DanmakuController controller) {
        releaseDanmakuController();
        danmakuController = controller;
        if (danmakuController == null) return;
        danmakuController.setOkHttpClient(OkHttp.player());
        danmakuController.setConfig(DanmakuSetting.getConfig());
        danmakuController.setListener(new DanmakuController.Listener() {
            @Override
            public void onLoadCompleted(Uri uri, int count) {
                logDanmakuLoad("completed", uri, count, null);
                finishDanmakuLoad(uri);
            }

            @Override
            public void onLoadError(Uri uri, IOException error) {
                logDanmakuLoad("error", uri, -1, error);
                finishDanmakuLoad(uri);
            }
        });
    }

    public void setDanmakuConfig(DanmakuConfig config) {
        if (danmakuController != null) danmakuController.setConfig(config);
    }

    public void setDanmakuEnabled(boolean enabled) {
        if (danmakuController != null) danmakuController.setEnabled(enabled);
    }

    public void sendDanmaku(String text) {
        if (danmakuController != null) danmakuController.sendNow(text);
    }

    public String setSpeed(float speed) {
        try {
            if (!player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return getSpeedText();
            player.setPlaybackParameters(player.getPlaybackParameters().withSpeed(speed));
        } catch (Exception e) { e.printStackTrace(); }
        return getSpeedText();
    }

    public String addSpeed() {
        float speed = getSpeed();
        float addon = speed >= 2 ? 1f : 0.25f;
        speed = speed >= 5 ? 0.25f : Math.min(speed + addon, 5.0f);
        return setSpeed(speed);
    }

    public String addSpeed(float value) {
        return setSpeed(Math.min(getSpeed() + value, 5));
    }

    public String subSpeed(float value) {
        return setSpeed(Math.max(getSpeed() - value, 0.25f));
    }

    public String toggleSpeed() {
        return setSpeed(getSpeed() == 1 ? PlayerSetting.getSpeed() : 1);
    }

    public void setTrack(List<Track> tracks) {
        if (!tracks.isEmpty()) try { engine.setTrack(tracks); } catch (Exception e) { e.printStackTrace(); }
    }

    public void play() {
        try { player.play(); } catch (Exception e) { e.printStackTrace(); }
    }

    public void pause() {
        try { player.pause(); } catch (Exception e) { e.printStackTrace(); }
    }

    public void stop() {
        try { player.stop(); } catch (Exception e) { e.printStackTrace(); }
        stopParse();
    }

    public void clearMediaItems() {
        try { player.clearMediaItems(); } catch (Exception e) { e.printStackTrace(); }
    }

    public boolean isRepeatOne() {
        return engine.isRepeatOne();
    }

    public void setRepeatOne(boolean repeat) {
        try { engine.setRepeatOne(repeat); } catch (Exception e) { e.printStackTrace(); }
    }

    public void seekTo(long time) {
        try { player.seekTo(time); } catch (Exception e) { e.printStackTrace(); }
    }

    public long getTextOffsetMs() {
        try { return engine.getTextOffsetMs(); } catch (Exception e) { e.printStackTrace(); return 0; }
    }

    public void setTextOffsetMs(long offsetMs) {
        try { engine.setTextOffsetMs(offsetMs); } catch (Exception e) { e.printStackTrace(); }
    }

    public long getAudioOffsetMs() {
        try { return engine.getAudioOffsetMs(); } catch (Exception e) { e.printStackTrace(); return 0; }
    }

    public void setAudioOffsetMs(long offsetMs) {
        try { engine.setAudioOffsetMs(offsetMs); } catch (Exception e) { e.printStackTrace(); }
    }

    public boolean canSetSubtitleStyle() {
        try { return engine.canSetSubtitleStyle(); } catch (Exception e) { e.printStackTrace(); return false; }
    }

    public void addSubtitleSize() {
        try { engine.addSubtitleSize(); } catch (Exception e) { e.printStackTrace(); }
    }

    public void subSubtitleSize() {
        try { engine.subSubtitleSize(); } catch (Exception e) { e.printStackTrace(); }
    }

    public void addSubtitlePosition() {
        try { engine.addSubtitlePosition(); } catch (Exception e) { e.printStackTrace(); }
    }

    public void subSubtitlePosition() {
        try { engine.subSubtitlePosition(); } catch (Exception e) { e.printStackTrace(); }
    }

    public void resetSubtitleStyle() {
        try { engine.resetSubtitleStyle(); } catch (Exception e) { e.printStackTrace(); }
    }

    public void reset() {
        App.removeCallbacks(runnable);
        retry = 0;
    }

    public void clear() {
        spec = null;
    }

    public void resetTrack() {
        try { engine.resetTrack(); } catch (Exception e) { e.printStackTrace(); }
    }

    public void toggleDecode() {
        try { engine.setDecode(engine.isHard() ? PlayerEngine.SOFT : PlayerEngine.HARD); } catch (Exception e) { e.printStackTrace(); }
        if (engine.canSetDecodeWithoutRebuild()) return;
        rebuildPlayer();
        setMediaItem();
    }

    public void toggleEngine() {
        setEngine(isMpvEngine() ? PlayerSetting.ENGINE_EXO : PlayerSetting.ENGINE_MPV);
    }

    public void setEngine(int target) {
        String reason = target == PlayerSetting.ENGINE_MPV ? getMpvUnsupportedReason(spec) : "";
        if (!TextUtils.isEmpty(reason)) {
            Log.e(TAG, "MPV unsupported: " + reason + ", url=" + (spec == null ? "null" : spec.getUrl()));
            Notify.show("MPV 不可用：" + reason);
            PlayerSetting.putEngine(PlayerSetting.ENGINE_EXO);
            return;
        }
        int old = isMpvEngine() ? PlayerSetting.ENGINE_MPV : PlayerSetting.ENGINE_EXO;
        PlayerSetting.putEngine(target);
        if (old == target) return;
        switchEngine();
    }

    private void rebuildPlayer() {
        try {
            player = engine.rebuild(listener);
            callback.onPlayerRebuild(player);
        } catch (Exception e) {
            e.printStackTrace();
            if (callback != null) callback.onError(engine != null && e instanceof PlaybackException ? engine.getErrorMessage((PlaybackException) e) : e.getMessage());
        }
    }

    private void switchEngine() {
        long position = getSwitchPosition();
        switchEngineOnly();
        if (spec != null && spec.getUrl() != null) setMediaItem(Constant.TIMEOUT_PLAY, position);
    }

    private void switchEngineOnly() {
        PlayerEngine old = engine;
        try { if (player != null) player.removeListener(listener); } catch (Exception e) { e.printStackTrace(); }
        try {
            engine = createEngine(old == null ? PlayerEngine.HARD : old.getDecode());
            player = engine.getPlayer();
            callback.onPlayerRebuild(player);
        } catch (Exception e) {
            e.printStackTrace();
            if (callback != null) callback.onError(e.getMessage());
        }
        try { if (old != null) old.release(); } catch (Exception e) { e.printStackTrace(); }
    }

    private long getSwitchPosition() {
        if (player == null) return C.TIME_UNSET;
        long position = player.getCurrentPosition();
        return position > 0 ? position : C.TIME_UNSET;
    }

    private PlayerEngine createEngine(int decode) {
        return canUseMpv(spec) ? new MpvPlayerEngine(decode, listener) : new ExoPlayerEngine(decode, listener);
    }

    private boolean isMpvEngine() {
        return engine instanceof MpvPlayerEngine;
    }

    public boolean isMpv() {
        return isMpvEngine();
    }

    private boolean canUseMpv(PlaySpec spec) {
        return (PlayerSetting.isMpv() || MpvMedia.shouldPreferMpv(spec == null ? null : spec.getUrl())) && isMpvSupported(spec);
    }

    private boolean isMpvSupported(PlaySpec spec) {
        return TextUtils.isEmpty(getMpvUnsupportedReason(spec));
    }

    private String getMpvUnsupportedReason(PlaySpec spec) {
        if (!MpvPlayerEngine.isAvailable()) {
            String error = MpvPlayerEngine.getAvailabilityError();
            return TextUtils.isEmpty(error) ? "native 库加载失败" : error;
        }
        if (spec == null) return "";
        if (spec.getDrm() != null) return "暂不支持 DRM 源";
        if ("smb".equals(UrlUtil.scheme(spec.getUrl()))) return "暂不支持 SMB 源";
        return "";
    }

    public void browse(PlaySpec spec) {
        reset();
        clear();
        stopParse();
        start(spec, Constant.TIMEOUT_PLAY);
    }

    public void start(PlaySpec spec, long timeout) {
        start(spec, timeout, C.TIME_UNSET);
    }

    public void start(PlaySpec spec, long timeout, long positionMs) {
        this.spec = spec;
        setMediaItem(timeout, positionMs);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata) {
        parse(key, result, useParse, metadata, C.TIME_UNSET);
    }

    public void parse(String key, Result result, boolean useParse, MediaMetadata metadata, long positionMs) {
        stopParse();
        spec = PlaySpec.fromParse(result, key, metadata);
        pendingStartPositionMs = positionMs;
        parseJob = ParseJob.create(this).start(result, useParse);
    }

    private void stopParse() {
        if (parseJob != null) parseJob.stop();
        parseJob = null;
    }

    public void setMediaItem() {
        setMediaItem(Constant.TIMEOUT_PLAY);
    }

    private void setMediaItem(long timeout) {
        setMediaItem(timeout, C.TIME_UNSET);
    }

    private void setMediaItem(long timeout, long positionMs) {
        if (spec == null || spec.getUrl() == null) return;
        ensureEngineForSpec();
        setDanmakus(spec.getDanmakus());
        try {
            engine.start(spec.checkUa(), positionMs);
        } catch (Exception e) {
            e.printStackTrace();
            if (callback != null) callback.onError(engine != null && e instanceof PlaybackException ? engine.getErrorMessage((PlaybackException) e) : e.getMessage());
        }
        pendingStartPositionMs = C.TIME_UNSET;
        App.post(runnable, timeout);
        callback.onPrepare();
        initTrack = false;
    }

    private void ensureEngineForSpec() {
        boolean useMpv = canUseMpv(spec);
        if (useMpv != isMpvEngine()) switchEngineOnly();
    }

    private void setDanmakus(List<Danmaku> items) {
        setDanmaku(items == null || items.isEmpty() ? Danmaku.empty() : items.get(0));
    }

    public void setDanmaku(Danmaku item) {
        setDanmaku(item, false);
    }

    public void reloadDanmaku(Danmaku item) {
        setDanmaku(item, true);
    }

    private void setDanmaku(Danmaku item, boolean force) {
        if (danmakuController == null) return;
        if (item.isEmpty()) {
            if (spec != null) spec.setDanmaku(item);
            SpiderDebug.log("danmaku", "clear current=%s", summarizeUrl(currentDanmakuUrl));
            stopWsDanmaku();
            if (currentDanmakuUrl != null) danmakuController.clearItems();
            clearDanmakuState();
            return;
        }
        String url = item.getRealUrl();
        String key = normalizeDanmakuKey(url);
        if (!force && TextUtils.equals(currentDanmakuUrl, url)) {
            SpiderDebug.log("danmaku", "skip same url=%s", summarizeUrl(url));
            return;
        }
        if (force && shouldSkipForcedDanmakuReload(key)) {
            SpiderDebug.log("danmaku", "skip duplicate reload key=%s url=%s", summarizeUrl(key), summarizeUrl(url));
            return;
        }
        if (spec != null) spec.setDanmaku(item);
        // 切换弹幕源前先停止旧的 WS 连接
        stopWsDanmaku();
        if (force && currentDanmakuUrl != null) danmakuController.clearItems();
        currentDanmakuUrl = url;
        currentDanmakuKey = key;
        loadingDanmakuKey = key;
        danmakuLoadStartedAtMs = SystemClock.elapsedRealtime();
        danmakuLoadInProgress = true;
        SpiderDebug.log("danmaku", "%s name=%s url=%s key=%s", force ? "reload" : "load", item.getName(), summarizeUrl(url), summarizeUrl(key));

        // 检测 ws:// 开头的弹幕地址，建立 WebSocket 实时弹幕连接
        if (url.startsWith("ws://") || url.startsWith("wss://")) {
            startWsDanmaku(url);
        } else {
            danmakuController.setDataSource(Uri.parse(url));
        }
    }

    private void startWsDanmaku(String url) {
        if (wsDanmakuClient == null) wsDanmakuClient = new WsDanmakuClient();
        wsDanmakuClient.connect(url, (text, color) -> {
            if (danmakuController == null) return;
            androidx.media3.ui.danmaku.Danmaku dm = new androidx.media3.ui.danmaku.Danmaku(
                text, 0, androidx.media3.ui.danmaku.Danmaku.TYPE_SCROLL, color);
            danmakuController.sendNow(dm);
        });
        // WS 弹幕是实时的，不需要等待加载完成
        danmakuLoadInProgress = false;
        loadingDanmakuKey = null;
    }

    private void stopWsDanmaku() {
        if (wsDanmakuClient != null) {
            wsDanmakuClient.disconnect();
            wsDanmakuClient = null;
        }
    }

    private boolean shouldSkipForcedDanmakuReload(String key) {
        if (TextUtils.isEmpty(key) || !TextUtils.equals(currentDanmakuKey, key) || danmakuLoadStartedAtMs <= 0) return false;
        if (danmakuLoadInProgress && (TextUtils.isEmpty(loadingDanmakuKey) || TextUtils.equals(loadingDanmakuKey, key))) return true;
        long elapsed = SystemClock.elapsedRealtime() - danmakuLoadStartedAtMs;
        return elapsed >= 0 && elapsed < DANMAKU_FORCE_RELOAD_DEBOUNCE_MS;
    }

    private void finishDanmakuLoad(Uri uri) {
        String key = normalizeDanmakuKey(uri == null ? "" : uri.toString());
        if (!TextUtils.isEmpty(loadingDanmakuKey) && !TextUtils.equals(loadingDanmakuKey, key)) return;
        danmakuLoadInProgress = false;
        loadingDanmakuKey = null;
    }

    private void clearDanmakuState() {
        currentDanmakuUrl = null;
        currentDanmakuKey = null;
        loadingDanmakuKey = null;
        danmakuLoadStartedAtMs = 0;
        danmakuLoadInProgress = false;
    }

    private void logDanmakuLoad(String event, Uri uri, int count, IOException error) {
        long elapsed = danmakuLoadStartedAtMs <= 0 ? -1 : SystemClock.elapsedRealtime() - danmakuLoadStartedAtMs;
        if (error == null) {
            SpiderDebug.log("danmaku", "load %s count=%d elapsed=%dms url=%s", event, count, elapsed, summarizeUrl(uri == null ? "" : uri.toString()));
        } else {
            SpiderDebug.log("danmaku", "load %s elapsed=%dms url=%s error=%s", event, elapsed, summarizeUrl(uri == null ? "" : uri.toString()), error.getMessage());
        }
    }

    private static String normalizeDanmakuKey(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String value = url.trim();
        try {
            Uri uri = Uri.parse(value);
            String nested = getNestedDanmakuUrl(uri);
            return TextUtils.isEmpty(nested) ? value : normalizeDanmakuKey(nested);
        } catch (Throwable e) {
            return value;
        }
    }

    private static String getNestedDanmakuUrl(Uri uri) {
        if (uri == null) return "";
        String path = uri.getPath();
        if (TextUtils.isEmpty(path) || !path.endsWith("/danmaku")) return "";
        return uri.getQueryParameter("url");
    }

    private static String summarizeUrl(String url) {
        if (TextUtils.isEmpty(url)) return "";
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        int port = uri.getPort();
        String path = uri.getPath();
        StringBuilder builder = new StringBuilder();
        builder.append(uri.getScheme()).append("://");
        builder.append(TextUtils.isEmpty(host) ? "unknown" : host);
        if (port > 0) builder.append(':').append(port);
        if (!TextUtils.isEmpty(path)) builder.append(path.length() > 48 ? path.substring(0, 48) + "..." : path);
        builder.append(" len=").append(url.length());
        return builder.toString();
    }

    private void releaseDanmakuController() {
        stopWsDanmaku();
        if (danmakuController == null) return;
        danmakuController.release();
        danmakuController = null;
        clearDanmakuState();
    }

    public void addDanmaku(Danmaku item) {
        if (danmakuController == null || item.isEmpty()) return;
        if (spec != null) spec.addDanmaku(item);
    }

    @Override
    public void onParseSuccess(Map<String, String> headers, String url, String from) {
        if (!TextUtils.isEmpty(from)) Notify.show(ResUtil.getString(R.string.parse_from, from));
        if (headers != null) headers.remove(HttpHeaders.RANGE);
        if (spec != null) spec.setHeaders(headers);
        if (spec != null) spec.setUrl(url);
        setMediaItem(Constant.TIMEOUT_PLAY, pendingStartPositionMs);
    }

    @Override
    public void onParseError() {
        callback.onError(ResUtil.getString(R.string.error_play_parse));
    }

    public interface Callback {

        void onPrepare();

        void onTracksChanged();

        void onTitlesChanged();

        void onError(String msg);

        void onPlayerRebuild(Player newPlayer);
    }

    private final Player.Listener listener = new Player.Listener() {

        @Override
        public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_READY || state == Player.STATE_ENDED) App.removeCallbacks(runnable);
        }

        @Override
        public void onVideoSizeChanged(@NonNull VideoSize size) {
            videoSize = size;
        }

        @Override
        public void onTracksChanged(@NonNull Tracks tracks) {
            if (tracks.isEmpty() || initTrack) return;
            setTrack(Track.find(getKey()));
            callback.onTracksChanged();
            initTrack = true;
        }

        @Override
        public void onMediaTitlesChanged(@NonNull List<MediaTitle> titles) {
            callback.onTitlesChanged();
        }

        @Override
        public void onPlayerError(@NonNull PlaybackException e) {
            PlayerEngine.ErrorAction action = engine.handleError(e);
            if (action == PlayerEngine.ErrorAction.RECOVERED) {
                setDanmakus(spec.getDanmakus());
            } else if (action == PlayerEngine.ErrorAction.FATAL) {
                callback.onError(engine.getErrorMessage(e));
            } else if (++retry > 1) {
                callback.onError(engine.getErrorMessage(e));
            } else {
                toggleDecode();
            }
        }
    };
}

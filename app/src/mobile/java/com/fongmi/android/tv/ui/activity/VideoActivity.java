package com.fongmi.android.tv.ui.activity;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.C;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.VideoSize;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.transition.ChangeBounds;
import androidx.transition.TransitionManager;
import androidx.viewbinding.ViewBinding;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DanmakuApi;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.CastVideo;
import com.fongmi.android.tv.bean.Danmaku;
import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.bean.Flag;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Parse;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Sub;
import com.fongmi.android.tv.bean.Track;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityVideoBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.CustomTarget;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.player.PlayerHelper;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.player.PreloadManager;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.custom.LrcView;
import com.fongmi.android.tv.setting.DanmakuSetting;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.FlagAdapter;
import com.fongmi.android.tv.ui.adapter.ParseAdapter;
import com.fongmi.android.tv.ui.adapter.QualityAdapter;
import com.fongmi.android.tv.ui.adapter.QuickAdapter;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.custom.CustomKeyDown;
import com.fongmi.android.tv.ui.custom.CustomMovement;
import com.fongmi.android.tv.ui.custom.CustomSeekView;
import com.fongmi.android.tv.ui.custom.VodReader;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.ui.dialog.CastDialog;
import com.fongmi.android.tv.ui.dialog.ControlDialog;
import com.fongmi.android.tv.ui.dialog.DanmakuDialog;
import com.fongmi.android.tv.ui.dialog.EpisodeGridDialog;
import com.fongmi.android.tv.ui.dialog.EpisodeListDialog;
import com.fongmi.android.tv.ui.dialog.InfoDialog;
import com.fongmi.android.tv.ui.dialog.ReceiveDialog;
import com.fongmi.android.tv.ui.dialog.SkipDialog;
import com.fongmi.android.tv.ui.dialog.SubtitleDialog;
import com.fongmi.android.tv.ui.dialog.TitleDialog;
import com.fongmi.android.tv.ui.dialog.TrackDialog;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PiP;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Sniffer;
import com.fongmi.android.tv.utils.Task;
import com.fongmi.android.tv.utils.Timer;
import com.fongmi.android.tv.utils.Traffic;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class VideoActivity extends PlaybackActivity implements Clock.Callback, CustomKeyDown.Listener, TrackDialog.Listener, ControlDialog.Listener, SkipDialog.Listener, FlagAdapter.OnClickListener, EpisodeAdapter.OnClickListener, QualityAdapter.OnClickListener, QuickAdapter.OnClickListener, ParseAdapter.OnClickListener, CastDialog.Listener, InfoDialog.Listener {

    private ActivityVideoBinding mBinding;
    private ViewGroup.LayoutParams mFrameParams;
    private Observer<Result> mObserveDetail;
    private Observer<Result> mObservePlayer;
    private Observer<Result> mObserveSearch;
    private EpisodeAdapter mEpisodeAdapter;
    private QualityAdapter mQualityAdapter;
    private QuickAdapter mQuickAdapter;
    private ParseAdapter mParseAdapter;
    private SiteViewModel mViewModel;
    private FlagAdapter mFlagAdapter;
    private ValueAnimator mAnimator;
    private CustomKeyDown mKeyDown;
    private List<String> mBroken;
    private History mHistory;
    private boolean fullscreen;
    private boolean initAuto;
    private boolean autoMode;
    private boolean useParse;
    private boolean leavingPlayback;
    private boolean rotate;
    private Runnable mR1;
    private Runnable mR2;
    private Runnable mR3;
    private Runnable mR4;
    private Clock mClock;
    private PiP mPiP;
    private int layoutMode = 0;
    private VodReader mReader;

    public static void push(FragmentActivity activity, String text) {
        if (FileChooser.isValid(activity, Uri.parse(text))) file(activity, FileChooser.getPathFromUri(Uri.parse(text)));
        else start(activity, Sniffer.getUrl(text));
    }

    public static void file(FragmentActivity activity, String path) {
        if (TextUtils.isEmpty(path)) return;
        String name = new File(path).getName();
        start(activity, SiteApi.PUSH, "file://" + path, name);
    }

    public static void cast(Activity activity, History history) {
        start(activity, history.getSiteKey(), history.getVodId(), history.getVodName(), history.getVodPic());
    }

    public static void collect(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null, true);
    }

    public static void start(Activity activity, String url) {
        start(activity, SiteApi.PUSH, url, url);
    }

    public static void start(Activity activity, String key, String id, String name) {
        start(activity, key, id, name, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic) {
        start(activity, key, id, name, pic, null);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark) {
        start(activity, key, id, name, pic, mark, false);
    }

    public static void start(Activity activity, String key, String id, String name, String pic, String mark, boolean collect) {
        Intent intent = new Intent(activity, VideoActivity.class);
        intent.putExtra("collect", collect);
        intent.putExtra("mark", mark);
        intent.putExtra("name", name);
        intent.putExtra("pic", pic);
        intent.putExtra("key", key);
        intent.putExtra("id", id);
        activity.startActivity(intent);
    }

    private String getName() {
        return Objects.toString(getIntent().getStringExtra("name"), "");
    }

    private String getPic() {
        return Objects.toString(getIntent().getStringExtra("pic"), "");
    }

    private String getMark() {
        return Objects.toString(getIntent().getStringExtra("mark"), "");
    }

    private String getKey() {
        return Objects.toString(getIntent().getStringExtra("key"), "");
    }

    private String getId() {
        return Objects.toString(getIntent().getStringExtra("id"), "");
    }

    private String getHistoryKey() {
        return getKey().concat(AppDatabase.SYMBOL).concat(getId()).concat(AppDatabase.SYMBOL) + VodConfig.getCid();
    }

    private Site getSite() {
        return VodConfig.get().getSite(getKey());
    }

    private Flag getFlag() {
        return mFlagAdapter.getActivated();
    }

    private Episode getEpisode() {
        return mEpisodeAdapter.isEmpty() ? new Episode() : mEpisodeAdapter.getActivated();
    }

    private int getScale() {
        return mHistory != null && mHistory.getScale() != -1 ? mHistory.getScale() : PlayerSetting.getScale();
    }

    private boolean isReplay() {
        return Setting.getReset() == 1;
    }

    private boolean isFromCollect() {
        return getIntent().getBooleanExtra("collect", false);
    }

    private boolean isAutoRotate() {
        return Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0) == 1;
    }

    private boolean isLand() {
        return mBinding.getRoot().getTag().equals("land");
    }

    private boolean isPort() {
        return mBinding.getRoot().getTag().equals("port");
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityVideoBinding.inflate(getLayoutInflater());
    }

    @Override
    protected PlaybackService.NavigationCallback getNavigationCallback() {
        return mNavigationCallback;
    }

    @Override
    protected PlayerView getExoView() {
        return mBinding.exo;
    }

    @Override
    protected CustomSeekView getSeekView() {
        return mBinding.control.seek;
    }

    @Override
    protected void onServiceConnected() {
        player().setDanmakuController(mBinding.exo.getDanmakuController());
        player().setDanmakuEnabled(DanmakuSetting.isShow());
        checkLand();
        checkId();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        String oldId = getId();
        super.onNewIntent(intent);
        String id = Objects.toString(intent.getStringExtra("id"), "");
        if (TextUtils.isEmpty(id) || id.equals(oldId)) return;
        mBinding.swipeLayout.setRefreshing(true);
        getIntent().putExtras(intent);
        saveHistory();
        setOrient();
        checkId();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        super.initView(savedInstanceState);
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> setStatusBar(insets));
        mKeyDown = CustomKeyDown.create(this, mBinding.exo);
        mFrameParams = mBinding.video.getLayoutParams();
        mBinding.progressLayout.showProgress();
        mBinding.swipeLayout.setEnabled(false);
        mObserveDetail = this::setDetail;
        mObservePlayer = this::setPlayer;
        mObserveSearch = this::setSearch;
        mBroken = new ArrayList<>();
        mClock = Clock.create();
        mR1 = this::hideControl;
        mR2 = this::setTraffic;
        mR3 = this::setOrient;
        mR4 = this::showEmpty;
        mPiP = new PiP();
        checkDanmakuImg();
        setRecyclerView();
        setVideoView();
        mReader = new VodReader(this, mBinding.reader, new VodReader.Listener() {
            @Override public void onSingleTap() {
                if (isFullscreen()) {
                    if (isVisible(mBinding.control.getRoot())) hideControl();
                    else showControl();
                }
            }
            @Override public void onDoubleTap() { toggleFullscreen(); }
            @Override public void onPrevious() { checkPrev(); }
            @Override public void onNext() { checkNext(); }
            @Override public void onDirectory() { onEpisodes(); }
            @Override public void onPageChanged(int current, int total) {}
        });
        setViewModel();
        showProgress();
        setAnimator();
    }

    @Override
    @SuppressLint("ClickableViewAccessibility")
    protected void initEvent() {
        mBinding.name.setOnClickListener(view -> onName());
        mBinding.more.setOnClickListener(view -> onMore());
        mBinding.actor.setOnClickListener(view -> onActor());
        mBinding.content.setOnClickListener(view -> onContent());
        mBinding.reverse.setOnClickListener(view -> onReverse());
        if (mBinding.download != null) mBinding.download.setOnClickListener(view -> onDownload());
        mBinding.director.setOnClickListener(view -> onDirector());
        mBinding.name.setOnLongClickListener(view -> onChange());
        mBinding.content.setOnLongClickListener(view -> onCopy());
        mBinding.control.back.setOnClickListener(view -> onBack());
        mBinding.control.cast.setOnClickListener(view -> onCast());
        mBinding.control.info.setOnClickListener(view -> onInfo());
        mBinding.control.keep.setOnClickListener(view -> onKeep());
        mBinding.control.play.setOnClickListener(view -> checkPlay());
        mBinding.control.next.setOnClickListener(view -> checkNext());
        mBinding.control.prev.setOnClickListener(view -> checkPrev());
        mBinding.control.setting.setOnClickListener(view -> onSetting());
        mBinding.control.title.setOnLongClickListener(view -> onChange());
        mBinding.control.right.lock.setOnClickListener(view -> onLock());
        mBinding.control.right.rotate.setOnClickListener(view -> onRotate());
        mBinding.control.right.pip.setOnClickListener(view -> enterPiP());
        mBinding.control.fullscreen.setOnClickListener(view -> toggleFullscreen());
        mBinding.control.danmaku.setOnClickListener(view -> onDanmakuShow());
        mBinding.control.action.text.setOnClickListener(this::onTrack);
        mBinding.control.action.audio.setOnClickListener(this::onTrack);
        mBinding.control.action.video.setOnClickListener(this::onTrack);
        mBinding.control.action.scale.setOnClickListener(view -> onScale());
        mBinding.control.action.speed.setOnClickListener(view -> onSpeed());
        mBinding.control.action.reset.setOnClickListener(view -> onReset());
        mBinding.control.action.title.setOnClickListener(view -> onTitle());
        mBinding.control.action.player.setOnClickListener(view -> onChoose());
        mBinding.control.action.player.setOnLongClickListener(view -> onChooseExternal());
        mBinding.control.action.decode.setOnClickListener(view -> onDecode());
        mBinding.control.action.ending.setOnClickListener(view -> onEnding());
        mBinding.control.action.repeat.setOnClickListener(view -> onRepeat());
        mBinding.control.action.opening.setOnClickListener(view -> onOpening());
        mBinding.control.action.danmaku.setOnClickListener(view -> onDanmaku());
        mBinding.control.action.episodes.setOnClickListener(view -> onEpisodes());
        mBinding.control.action.text.setOnLongClickListener(view -> onTextLong());
        mBinding.control.action.speed.setOnLongClickListener(view -> onSpeedLong());
        mBinding.control.action.reset.setOnLongClickListener(view -> onResetToggle());
        mBinding.control.action.ending.setOnLongClickListener(view -> onEndingReset());
        mBinding.control.action.opening.setOnLongClickListener(view -> onOpeningReset());
        mBinding.video.setOnTouchListener((view, event) -> mKeyDown.onTouchEvent(event));
        mBinding.control.action.getRoot().setOnTouchListener(this::onActionTouch);
        mBinding.swipeLayout.setOnRefreshListener(this::onSwipeRefresh);
    }

    private WindowInsetsCompat setStatusBar(WindowInsetsCompat insets) {
        int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
        ViewGroup.LayoutParams lp = mBinding.statusBar.getLayoutParams();
        lp.height = top;
        mBinding.statusBar.setLayoutParams(lp);
        return insets;
    }

    private void setRecyclerView() {
        mBinding.flag.setHasFixedSize(true);
        mBinding.flag.setItemAnimator(null);
        mBinding.flag.addItemDecoration(new SpaceItemDecoration(8));
        mBinding.flag.setAdapter(mFlagAdapter = new FlagAdapter(this));
        mBinding.quick.setAdapter(mQuickAdapter = new QuickAdapter(this));
        layoutMode = Setting.getLayoutMode();
        if (layoutMode != 0) Setting.putLayoutMode(layoutMode = 0);
        mBinding.episode.setHasFixedSize(true);
        mBinding.episode.setItemAnimator(null);
        if (layoutMode == 0) {
            mBinding.episode.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 2));
            mBinding.episode.addItemDecoration(new SpaceItemDecoration(2, 8));
            mBinding.episode.setAdapter(mEpisodeAdapter = new EpisodeAdapter(this, ViewType.GRID));
        } else {
            mBinding.episode.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.VERTICAL, false));
            mBinding.episode.addItemDecoration(new SpaceItemDecoration(1, 8));
            mBinding.episode.setAdapter(mEpisodeAdapter = new EpisodeAdapter(this, ViewType.LIST));
        }
        mBinding.quality.setHasFixedSize(true);
        mBinding.quality.setItemAnimator(null);
        mBinding.quality.addItemDecoration(new SpaceItemDecoration(8));
        mBinding.quality.setAdapter(mQualityAdapter = new QualityAdapter(this));
        mBinding.control.parse.setHasFixedSize(true);
        mBinding.control.parse.setItemAnimator(null);
        mBinding.control.parse.addItemDecoration(new SpaceItemDecoration(8));
        mBinding.control.parse.setAdapter(mParseAdapter = new ParseAdapter(this, ViewType.DARK));
    }

    private void setVideoView() {
        mBinding.control.action.danmaku.setVisibility(DanmakuSetting.isLoad() ? View.VISIBLE : View.GONE);
        mBinding.control.action.reset.setText(ResUtil.getStringArray(R.array.select_reset)[Setting.getReset()]);
        mBinding.video.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> mPiP.update(this, view));
    }

    private void setVideoView(boolean isInPictureInPictureMode) {
        if (isInPictureInPictureMode) {
            mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        } else {
            mBinding.video.setLayoutParams(mFrameParams);
        }
    }

    private void setReaderVisible(boolean visible) {
        if (visible) {
            hideControl();
            hideProgress();
            mBinding.exo.setVisibility(View.GONE);
            mBinding.widget.getRoot().setVisibility(View.GONE);
            mBinding.lrcView.setVisibility(View.GONE);
            if (!isFullscreen()) enterFullscreen();
        } else {
            mReader.clear();
            mBinding.exo.setVisibility(View.VISIBLE);
            mBinding.widget.getRoot().setVisibility(View.VISIBLE);
            if (mBinding.lrcView.hasLrc()) mBinding.lrcView.setVisibility(View.VISIBLE);
        }
    }

    private void setAnimator() {
        mAnimator = new ValueAnimator();
        mAnimator.setInterpolator(new DecelerateInterpolator());
        mAnimator.addUpdateListener(animation -> {
            if (isLand() || isFullscreen() || isInPictureInPictureMode()) return;
            mFrameParams.height = (int) animation.getAnimatedValue();
            mBinding.video.setLayoutParams(mFrameParams);
        });
    }

    private void setDecode() {
        mBinding.control.action.decode.setText(player().getDecodeText());
    }

    private void setEngine() {
        mBinding.control.action.player.setText(player().getEngineText());
    }

    private void setScale(int scale) {
        mHistory.setScale(scale);
        mBinding.exo.setResizeMode(scale);
        mBinding.control.action.scale.setText(ResUtil.getStringArray(R.array.select_scale)[scale]);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observeForever(mObserveDetail);
        mViewModel.getPlayer().observeForever(mObservePlayer);
        mViewModel.getSearch().observeForever(mObserveSearch);
    }

    private void checkId() {
        if (getId().startsWith("push://")) getIntent().putExtra("key", SiteApi.PUSH).putExtra("id", getId().substring(7));
        if (getId().isEmpty() || getId().startsWith("msearch:")) setEmpty(false);
        else getDetail();
    }

    private void checkLand() {
        if (isPort() && ResUtil.isLand(this)) enterFullscreen();
    }

    private void getDetail() {
        mViewModel.detailContent(getKey(), getId());
    }

    private void getDetail(Vod item) {
        getIntent().putExtra("key", item.getSiteKey());
        getIntent().putExtra("pic", item.getPic());
        getIntent().putExtra("id", item.getId());
        mBinding.swipeLayout.setRefreshing(true);
        mBinding.swipeLayout.setEnabled(false);
        mBinding.scroll.scrollTo(0, 0);
        mClock.setCallback(null);
        updateNavigationKey();
        player().reset();
        player().stop();
        if (mReader.isActive()) mReader.clear();
        saveHistory();
        getDetail();
    }

    private void setDetail(Result result) {
        mBinding.swipeLayout.setRefreshing(false);
        if (result.getList().isEmpty()) setEmpty(result.hasMsg());
        else setDetail(result.getVod());
        Notify.show(result.getMsg());
    }

    private void setEmpty(boolean finish) {
        if (isFromCollect() || finish) {
            finish();
        } else if (getName().isEmpty()) {
            showEmpty();
        } else {
            mBinding.name.setText(getName());
            App.post(mR4, 10000);
            checkSearch(false);
        }
    }

    private void showEmpty() {
        showError(getString(R.string.error_detail));
        mBinding.swipeLayout.setEnabled(true);
        mBinding.progressLayout.showEmpty();
    }

    private void setDetail(Vod item) {
        item.checkPic(getPic());
        item.checkName(getName());
        mBinding.progressLayout.showContent();
        mBinding.name.setText(item.getName());
        mFlagAdapter.addAll(item.getFlags());
        App.removeCallbacks(mR4);
        checkHistory(item);
        checkFlag(item);
        checkKeepImg();
        setText(item);
        updateKeep();
    }

    private void setText(Vod item) {
        setText(mBinding.site, R.string.detail_site, getSite().getName());
        setText(mBinding.director, R.string.detail_director, item.getDirector());
        setText(mBinding.actor, R.string.detail_actor, item.getActor());
        setText(mBinding.content, 0, item.getContent());
        setText(mBinding.remark, 0, item.getRemarks().isEmpty() ? "" : "[" + item.getRemarks() + "]");
        setOther(mBinding.other, item);
    }

    private void setText(TextView view, int resId, String text) {
        if (TextUtils.isEmpty(text) && !TextUtils.isEmpty(view.getText())) return;
        view.setText(Sniffer.buildClickable(resId > 0 ? getString(resId, text) : text, this::clickableSpan), TextView.BufferType.SPANNABLE);
        view.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        if (view == mBinding.content) setContentVisible();
        view.setLinkTextColor(Color.WHITE);
        CustomMovement.bind(view);
    }

    private void setContentVisible() {
        mBinding.contentLayout.setVisibility(mBinding.content.getVisibility());
    }

    private ClickableSpan clickableSpan(Result result) {
        return new ClickableSpan() {
            @Override
            public void onClick(@NonNull View view) {
                FolderActivity.start(getActivity(), getKey(), result);
                ((TextView) view).setMaxLines(Integer.MAX_VALUE);
                setRedirect(true);
            }
        };
    }

    private void setOther(TextView view, Vod item) {
        StringBuilder sb = new StringBuilder();
        if (!item.getYear().isEmpty()) sb.append(getString(R.string.detail_year, item.getYear())).append("  ");
        if (!item.getArea().isEmpty()) sb.append(getString(R.string.detail_area, item.getArea())).append("  ");
        if (!item.getTypeName().isEmpty()) sb.append(getString(R.string.detail_type, item.getTypeName())).append("  ");
        view.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
        view.setText(Util.substring(sb.toString(), 2));
    }

    private void getPlayer(Flag flag, Episode episode) {
        mBinding.control.title.setText(getString(R.string.detail_title, mBinding.name.getText(), episode.getName()));
        Result preload = PreloadManager.get().get(getKey(), flag.getFlag(), episode);
        if (preload != null) {
            mBinding.control.title.setSelected(true);
            updateHistory(episode);
            hideProgress();
            setPlayer(preload);
            return;
        }
        mViewModel.playerContent(getKey(), flag.getFlag(), episode.getUrl());
        mBinding.control.title.setSelected(true);
        updateHistory(episode);
        showProgress();
    }

    private void setPlayer(Result result) {
        if (isFinishing() || isDestroyed()) return;
        mQualityAdapter.addAll(result);
        setUseParse(result.shouldUseParse());
        mBinding.swipeLayout.setRefreshing(false);
        setQualityVisible(result.getUrl().isMulti());
        result.getUrl().set(mQualityAdapter.getPosition());
        if (result.hasArtwork()) setArtwork(result.getArtwork());
        if (result.hasPosition()) mHistory.setPosition(result.getPosition());
        if (result.hasDesc()) setText(mBinding.content, 0, result.getDesc());
        setLrc(result);
        mBinding.control.parse.setVisibility(isUseParse() ? View.VISIBLE : View.GONE);
        boolean wasReader = mReader.isActive();
        String readerTitle = mHistory != null ? mHistory.getVodName() : "";
        if (mReader.set(result, readerTitle)) {
            player().stop();
            player().clear();
            setReaderVisible(true);
            return;
        }
        if (wasReader) {
            setReaderVisible(false);
        }
        startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata(), getResumePosition());
        if (DanmakuApi.canSearch()) DanmakuApi.search(mHistory.getVodName(), getEpisode().getName(), danmaku -> {
            if (DanmakuSetting.isSpiderFirst() && !result.getDanmaku().isEmpty()) player().addDanmaku(danmaku);
            else player().setDanmaku(danmaku);
        });
    }

    private void setLrc(Result result) {
        if (result.hasLrc()) {
            mBinding.lrcView.setTextSize(PlayerSetting.getLrcTextSize());
            mBinding.lrcView.setCallback(() -> {
                try { return player().getPosition(); } catch (Exception e) { return 0L; }
            });
            mBinding.lrcView.setData(result.getLrc());
            mBinding.lrcView.setVisibility(View.VISIBLE);
        } else {
            mBinding.lrcView.clear();
            mBinding.lrcView.setVisibility(View.GONE);
        }
    }

    @Override
    public void onItemClick(Flag item) {
        if (item.isSelected()) return;
        mFlagAdapter.setSelected(item);
        scrollToPosition(mBinding.flag, mFlagAdapter.getPosition());
        setEpisodeAdapter(item.getEpisodes());
        setQualityVisible(false);
        seamless(item);
    }

    @Override
    public void onItemClick(Episode item) {
        if (shouldEnterFullscreen(item)) return;
        mFlagAdapter.toggle(item);
        notifyItemChanged(mBinding.episode, mEpisodeAdapter);
        scrollToPosition(mBinding.episode, mEpisodeAdapter.getPosition());
        if (isFullscreen()) Notify.show(getString(R.string.play_ready, item.getName()));
        onRefresh();
    }

    @Override
    public void onItemClick(Result result) {
        updateHistoryProgress();
        startPlayer(getHistoryKey(), result, isUseParse(), getSite().getTimeout(), buildMetadata(), getResumePosition());
    }

    @Override
    public void onItemClick(Vod item) {
        setAutoMode(false);
        getDetail(item);
    }

    @Override
    public void onItemClick(Parse item) {
        setParse(item);
        onRefresh();
    }

    private void setParse(Parse item) {
        VodConfig.get().setParse(item);
        notifyItemChanged(mBinding.control.parse, mParseAdapter);
    }

    private void setEpisodeAdapter(List<Episode> items) {
        mBinding.control.action.episodes.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.control.next.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.control.prev.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        mBinding.reverse.setVisibility(items.size() < 2 ? View.GONE : View.VISIBLE);
        if (mBinding.download != null) mBinding.download.setVisibility(items.isEmpty() || !Setting.isHomeDownload() ? View.GONE : View.VISIBLE);
        mBinding.episode.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.more.setVisibility(items.isEmpty() ? View.GONE : View.VISIBLE);
        mEpisodeAdapter.addAll(items);
    }

    private void seamless(Flag flag) {
        Episode episode = flag.find(mHistory.getVodRemarks(), getMark().isEmpty());
        setQualityVisible(episode != null && episode.isSelected() && mQualityAdapter.getItemCount() > 1);
        if (episode == null || episode.isSelected()) return;
        mHistory.setVodRemarks(episode.getName());
        onItemClick(episode);
    }

    private void setQualityVisible(boolean visible) {
        mBinding.qualityText.setVisibility(visible ? View.VISIBLE : View.GONE);
        mBinding.quality.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void reverseEpisode(boolean scroll) {
        mFlagAdapter.reverse();
        setEpisodeAdapter(getFlag().getEpisodes());
        if (scroll) scrollToPosition(mBinding.episode, mEpisodeAdapter.getPosition());
    }

    private void onName() {
        String name = mBinding.name.getText().toString();
        Notify.show(getString(R.string.detail_search, name));
        initSearch(name, false);
    }

    private void onMore() {
        layoutMode = layoutMode == 0 ? 2 : 0;
        Setting.putLayoutMode(layoutMode);
        List<Episode> items = new ArrayList<>(mEpisodeAdapter.getItems());
        while (mBinding.episode.getItemDecorationCount() > 0) mBinding.episode.removeItemDecorationAt(0);
        if (layoutMode == 0) {
            mBinding.episode.setLayoutManager(new androidx.recyclerview.widget.GridLayoutManager(this, 2));
            mBinding.episode.addItemDecoration(new SpaceItemDecoration(2, 8));
            mBinding.episode.setAdapter(mEpisodeAdapter = new EpisodeAdapter(this, ViewType.GRID));
        } else {
            mBinding.episode.setLayoutManager(new androidx.recyclerview.widget.LinearLayoutManager(this, androidx.recyclerview.widget.LinearLayoutManager.VERTICAL, false));
            mBinding.episode.addItemDecoration(new SpaceItemDecoration(1, 8));
            mBinding.episode.setAdapter(mEpisodeAdapter = new EpisodeAdapter(this, ViewType.LIST));
        }
        mEpisodeAdapter.addAll(items);
        mBinding.episode.scrollToPosition(mEpisodeAdapter.getPosition());
    }

    private void onActor() {
        mBinding.actor.setMaxLines(mBinding.actor.getMaxLines() == 1 ? Integer.MAX_VALUE : 1);
    }

    private void onDirector() {
        mBinding.director.setMaxLines(mBinding.director.getMaxLines() == 1 ? Integer.MAX_VALUE : 1);
    }

    private void onContent() {
        mBinding.content.setMaxLines(mBinding.content.getMaxLines() == 3 ? Integer.MAX_VALUE : 3);
    }

    private void onReverse() {
        mHistory.setRevSort(!mHistory.isRevSort());
        reverseEpisode(false);
    }

    private void onDownload() {
        com.fongmi.android.tv.bean.Flag flag = mFlagAdapter.getActivated();
        if (flag == null || flag.getEpisodes().isEmpty()) return;
        List<Episode> episodes = flag.getEpisodes();
        boolean[] checked = new boolean[episodes.size()];

        List<com.fongmi.android.tv.bean.Download> allDownloads = com.fongmi.android.tv.bean.Download.getAll();
        java.util.Map<String, com.fongmi.android.tv.bean.Download> downloadMap = new java.util.HashMap<>();
        for (com.fongmi.android.tv.bean.Download d : allDownloads) {
            if (getName().equals(d.getVodName()) && getKey().equals(d.getKey())) {
                downloadMap.put(d.getEpisodeName(), d);
            }
        }

        class DownloadSelectAdapter extends android.widget.ArrayAdapter<Episode> {
            private final java.util.Map<String, com.fongmi.android.tv.bean.Download> dMap;
            private final boolean[] chk;

            public DownloadSelectAdapter(android.content.Context context, List<Episode> objects, java.util.Map<String, com.fongmi.android.tv.bean.Download> dMap, boolean[] chk) {
                super(context, 0, objects);
                this.dMap = dMap;
                this.chk = chk;
            }

            @NonNull
            @Override
            public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
                if (convertView == null) {
                    convertView = android.view.LayoutInflater.from(getContext()).inflate(R.layout.adapter_download_select, parent, false);
                }
                Episode episode = getItem(position);
                androidx.appcompat.widget.AppCompatCheckBox checkbox = convertView.findViewById(R.id.checkbox);
                com.google.android.material.textview.MaterialTextView nameText = convertView.findViewById(R.id.name);
                androidx.appcompat.widget.AppCompatImageView checkImg = convertView.findViewById(R.id.check);

                String name = episode.getName();
                boolean isDownloaded = false;
                boolean isDownloading = false;

                if (dMap.containsKey(name)) {
                    com.fongmi.android.tv.bean.Download d = dMap.get(name);
                    if (d.getStatus() == com.fongmi.android.tv.bean.Download.STATUS_COMPLETED) {
                        isDownloaded = true;
                        name += " (已下载)";
                    } else if (d.getStatus() == com.fongmi.android.tv.bean.Download.STATUS_DOWNLOADING || d.getStatus() == com.fongmi.android.tv.bean.Download.STATUS_WAIT) {
                        isDownloading = true;
                        name += " (下载中)";
                    } else if (d.getStatus() == com.fongmi.android.tv.bean.Download.STATUS_PAUSE) {
                        name += " (已暂停)";
                    } else if (d.getStatus() == com.fongmi.android.tv.bean.Download.STATUS_ERROR) {
                        name += " (下载失败)";
                    }
                }

                nameText.setText(name);

                if (isDownloaded) {
                    checkbox.setVisibility(View.GONE);
                    checkImg.setVisibility(View.VISIBLE);
                    convertView.setAlpha(0.6f);
                } else if (isDownloading) {
                    checkbox.setVisibility(View.GONE);
                    checkImg.setVisibility(View.GONE);
                    convertView.setAlpha(0.6f);
                } else {
                    checkbox.setVisibility(View.VISIBLE);
                    checkImg.setVisibility(View.GONE);
                    checkbox.setChecked(chk[position]);
                    convertView.setAlpha(1.0f);
                }

                final boolean finalIsDownloaded = isDownloaded;
                final boolean finalIsDownloading = isDownloading;
                convertView.setOnClickListener(v -> {
                    if (finalIsDownloaded || finalIsDownloading) {
                        Notify.show("该剧集已下载或正在下载中");
                    } else {
                        chk[position] = !chk[position];
                        checkbox.setChecked(chk[position]);
                    }
                });

                return convertView;
            }
        }

        DownloadSelectAdapter adapter = new DownloadSelectAdapter(this, episodes, downloadMap, checked);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("选择下载剧集")
                .setAdapter(adapter, null)
                .setPositiveButton("开始下载", (dialog, which) -> {
                    boolean added = false;
                    for (int i = 0; i < checked.length; i++) {
                        if (checked[i]) {
                            Episode episode = episodes.get(i);
                            com.fongmi.android.tv.bean.Download d = downloadMap.get(episode.getName());
                            if (d != null) {
                                com.fongmi.android.tv.db.AppDatabase.get().getDownloadDao().delete(d.getId());
                            }
                            com.fongmi.android.tv.bean.Download download = new com.fongmi.android.tv.bean.Download();
                            download.setVodName(getName());
                            download.setVodPic(getPic());
                            download.setEpisodeName(episode.getName());
                            download.setKey(getKey());
                            download.setFlag(flag.getFlag());
                            download.setEpisodeUrl(episode.getUrl());
                            download.setDownloadPath(new java.io.File(com.github.catvod.utils.Path.root("download"), getName() + "/" + episode.getName()).getAbsolutePath());
                            download.setCreateTime(System.currentTimeMillis());
                            com.fongmi.android.tv.utils.DownloadManager.get().startDownload(download);
                            added = true;
                        }
                    }
                    if (added) Notify.show("已加入下载队列");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private boolean onChange() {
        checkSearch(true);
        return true;
    }

    private boolean onCopy() {
        Util.copy(mBinding.content.getText().toString());
        return true;
    }

    private void onBack() {
        if (isFullscreen()) exitFullscreen();
        else {
            leavingPlayback = true;
            stopPlaybackIfLeaving();
            finish();
        }
    }

    private void onCast() {
        CastDialog.create().history(mHistory).video(new CastVideo(mBinding.name.getText().toString(), player().getUrl(), player().getPosition(), player().getHeaders())).fm(true).show(this);
    }

    private void onInfo() {
        InfoDialog.create().title(mBinding.control.title.getText()).headers(player().getHeaders()).url(player().getUrl()).show(this);
    }

    private void onKeep() {
        Keep keep = Keep.find(getHistoryKey());
        Notify.show(keep != null ? R.string.keep_del : R.string.keep_add);
        if (keep != null) keep.delete();
        else createKeep();
        checkKeepImg();
        com.fongmi.android.tv.utils.WebDavSync.upload(null);
    }

    private void checkPlay() {
        setR1Callback();
        if (mReader.isActive()) {
            if (isVisible(mBinding.control.getRoot())) hideControl();
            else showControl();
            return;
        }
        if (player().isPlaying()) onPaused();
        else if (player().isEmpty()) onRefresh();
        else onPlay();
    }

    private void checkNext() {
        checkNext(true);
    }

    private void checkNext(boolean notify) {
        setR1Callback();
        Episode item = mEpisodeAdapter.getNext();
        if (!item.isSelected()) onItemClick(item);
        else if (notify) Notify.show(R.string.error_play_next);
    }

    private void checkPrev() {
        setR1Callback();
        Episode item = mEpisodeAdapter.getPrev();
        if (!item.isSelected()) onItemClick(item);
        else Notify.show(R.string.error_play_prev);
    }

    private void onSetting() {
        ControlDialog.create().parent(mBinding).history(mHistory).parse(isUseParse()).player(player()).show(this);
    }

    private void onLock() {
        setLock(!isLock());
        setRequestedOrientation(getLockOrient());
        mKeyDown.setLock(isLock());
        checkLockImg();
        showControl();
    }

    private void onRotate() {
        setR1Callback();
        setRotate(!isRotate());
        setRequestedOrientation(ResUtil.isLand(this) ? ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void onTrack(View view) {
        TrackDialog.create().type(Integer.parseInt(view.getTag().toString())).player(player()).show(this);
        hideControl();
    }

    private void onTitle() {
        TitleDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onDanmaku() {
        DanmakuDialog.create().player(player()).show(this);
        hideControl();
    }

    private void onDanmakuShow() {
        DanmakuSetting.putShow(!DanmakuSetting.isShow());
        checkDanmakuImg();
        showDanmaku();
    }

    private void onRepeat() {
        player().setRepeatOne(!player().isRepeatOne());
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {
        mBinding.control.action.repeat.setSelected(player().isRepeatOne());
    }

    private void onScale() {
        int index = getScale();
        String[] array = ResUtil.getStringArray(R.array.select_scale);
        if (mKeyDown.getScale() != 1.0f) mKeyDown.resetScale();
        else setScale(index == array.length - 1 ? 0 : ++index);
        setR1Callback();
    }

    private void onSpeed() {
        mBinding.control.action.speed.setText(player().addSpeed());
        mHistory.setSpeed(player().getSpeed());
        setR1Callback();
    }

    private boolean onSpeedLong() {
        mBinding.control.action.speed.setText(player().toggleSpeed());
        mHistory.setSpeed(player().getSpeed());
        setR1Callback();
        return true;
    }

    private void onReset() {
        if (isReplay()) onReplay();
        else onRefresh();
    }

    private void onReplay() {
        mHistory.setPosition(C.TIME_UNSET);
        if (player().isEmpty()) onRefresh();
        else player().setMediaItem();
    }

    private void onRefresh() {
        saveHistory();
        player().stop();
        player().clear();
        mClock.setCallback(null);
        if (mReader.isActive()) mReader.clear();
        if (mFlagAdapter.isEmpty()) return;
        if (mEpisodeAdapter.isEmpty()) return;
        getPlayer(getFlag(), getEpisode());
    }

    private boolean onResetToggle() {
        Setting.putReset(Math.abs(Setting.getReset() - 1));
        mBinding.control.action.reset.setText(ResUtil.getStringArray(R.array.select_reset)[Setting.getReset()]);
        return true;
    }

    private void onDecode() {
        mClock.setCallback(null);
        player().toggleDecode();
        setR1Callback();
        setDecode();
    }

    private void onChoose() {
        mClock.setCallback(null);
        player().toggleEngine();
        setR1Callback();
        setEngine();
        setDecode();
    }

    private boolean onChooseExternal() {
        PlayerHelper.choose(this, player().getUrl(), player().getHeaders(), player().isVod(), player().getPosition(), mBinding.control.title.getText());
        setRedirect(true);
        return true;
    }

    private void onEnding() {
        showSkipDialog();
        setR1Callback();
    }

    private boolean onEndingReset() {
        setR1Callback();
        setEnding(0);
        return true;
    }

    private void setEnding(long ending) {
        mHistory.setEnding(ending);
        mBinding.control.action.ending.setText(getSkipText(R.string.play_ed, ending));
        setSkipText();
    }

    private void onOpening() {
        showSkipDialog();
        setR1Callback();
    }

    private boolean onOpeningReset() {
        setR1Callback();
        setOpening(0);
        setEnding(0);
        return true;
    }

    private void setOpening(long opening) {
        mHistory.setOpening(opening);
        setSkipText();
    }

    private void showSkipDialog() {
        SkipDialog.create().skip(mHistory.getOpening(), mHistory.getEnding()).show(this);
    }

    private String getSkipText(int resId, long timeMs) {
        return timeMs <= 0 ? getString(resId) : getString(resId) + " " + SkipDialog.format(timeMs);
    }

    private void setSkipText() {
        long opening = mHistory.getOpening();
        long ending = mHistory.getEnding();
        String text = getString(R.string.play_op);
        if (opening > 0 || ending > 0) text += " " + SkipDialog.format(opening) + " / " + SkipDialog.format(ending);
        mBinding.control.action.opening.setText(text);
    }

    @Override
    public void onSkipChanged(long opening, long ending) {
        setOpening(opening);
        setEnding(ending);
        if (opening > 0 && player().getPosition() < opening && player().canSetOpening(player().getPosition(), player().getDuration())) player().seekTo(opening);
        syncHistory(true);
    }

    private void onEpisodes() {
        EpisodeListDialog.create().episodes(mEpisodeAdapter.getItems()).show(this);
    }

    private boolean onTextLong() {
        if (!player().haveTrack(C.TRACK_TYPE_TEXT) && !player().canSetSubtitleStyle()) return false;
        onSubtitleClick();
        return true;
    }

    private boolean onActionTouch(View v, MotionEvent e) {
        setR1Callback();
        return false;
    }

    private void onSwipeRefresh() {
        if (mBinding.progressLayout.isEmpty()) getDetail();
        else onRefresh();
    }

    private boolean shouldEnterFullscreen(Episode item) {
        boolean enter = !isFullscreen() && item.isSelected();
        if (enter) enterFullscreen();
        return enter;
    }

    private void toggleFullscreen() {
        if (isFullscreen()) exitFullscreen();
        else enterFullscreen();
    }

    private void enterPiP() {
        if (service() == null) return;
        if (!player().haveTrack(C.TRACK_TYPE_VIDEO)) return;
        hideControl();
        mPiP.enter(this, player().getVideoWidth(), player().getVideoHeight(), getScale(), true);
    }

    private void enterFullscreen() {
        if (isFullscreen()) return;
        setFullscreen(true);
        if (isLand() && !player().isPortrait()) setTransition();
        mBinding.video.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
        setRequestedOrientation(player().isPortrait() ? ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        mBinding.control.title.setVisibility(View.VISIBLE);
        setRotate(player().isPortrait());
        mKeyDown.resetScale();
        App.post(mR3, 2000);
        hideControl();
    }

    private void exitFullscreen() {
        if (!isFullscreen()) return;
        setFullscreen(false);
        if (isLand() && !player().isPortrait()) setTransition();
        setRequestedOrientation(isPort() ? ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        mBinding.episode.postDelayed(() -> mBinding.episode.scrollToPosition(mEpisodeAdapter.getPosition()), 100);
        mBinding.control.title.setVisibility(View.VISIBLE);
        mBinding.video.setLayoutParams(mFrameParams);
        mKeyDown.resetScale();
        App.post(mR3, 2000);
        setRotate(false);
        hideControl();
    }

    private void setTransition() {
        ChangeBounds transition = new ChangeBounds();
        transition.setDuration(150);
        ViewGroup parent = (ViewGroup) mBinding.video.getParent();
        TransitionManager.beginDelayedTransition(parent, transition);
    }

    private int getLockOrient() {
        if (isLock()) {
            return ResUtil.getScreenOrientation(this);
        } else if (isRotate()) {
            return ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        } else if (isPort() && isAutoRotate()) {
            return ActivityInfo.SCREEN_ORIENTATION_FULL_USER;
        } else {
            return ResUtil.isLand(this) ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE : ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT;
        }
    }

    private void showProgress() {
        if (isPlayingNow()) {
            hideProgress();
            return;
        }
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
        App.post(mR2, 0);
        hideError();
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR2);
        Traffic.reset();
    }

    private void showError(String text) {
        mBinding.widget.error.setVisibility(View.VISIBLE);
        mBinding.widget.error.setText(text);
        hideProgress();
    }

    private void hideError() {
        mBinding.widget.error.setVisibility(View.GONE);
        mBinding.widget.error.setText("");
    }

    private void showDanmaku() {
        player().setDanmakuEnabled(DanmakuSetting.isShow());
    }

    private void hideDanmaku() {
        player().setDanmakuEnabled(false);
    }

    private void showControl() {
        if (service() == null || isInPictureInPictureMode()) return;
        mBinding.control.danmaku.setVisibility(isLock() || !player().haveDanmaku() ? View.GONE : View.VISIBLE);
        mBinding.control.setting.setVisibility(mHistory == null || isFullscreen() ? View.GONE : View.VISIBLE);
        mBinding.control.right.rotate.setVisibility(isFullscreen() && !isLock() ? View.VISIBLE : View.GONE);
        mBinding.control.keep.setVisibility(mHistory == null || isFullscreen() ? View.GONE : View.VISIBLE);
        mBinding.control.parse.setVisibility(isFullscreen() && isUseParse() ? View.VISIBLE : View.GONE);
        mBinding.control.action.getRoot().setVisibility(isFullscreen() ? View.VISIBLE : View.GONE);
        mBinding.control.right.lock.setVisibility(isFullscreen() ? View.VISIBLE : View.GONE);
        mBinding.control.fullscreen.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.right.pip.setVisibility(isLock() || PiP.noPiP() ? View.GONE : View.VISIBLE);
        mBinding.control.info.setVisibility(player().isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.control.cast.setVisibility(player().isEmpty() ? View.GONE : View.VISIBLE);
        mBinding.control.center.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.bottom.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.back.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.top.setVisibility(isLock() ? View.GONE : View.VISIBLE);
        mBinding.control.getRoot().setVisibility(View.VISIBLE);
        setR1Callback();
        updateAlwaysProgress();
    }

    private void hideControl() {
        mBinding.control.getRoot().setVisibility(View.GONE);
        App.removeCallbacks(mR1);
        updateAlwaysProgress();
    }

    private void updateAlwaysProgress() {
        if (mBinding.alwaysProgressText == null) return;
        boolean show = com.fongmi.android.tv.setting.Setting.isAlwaysProgress()
                && isFullscreen()
                && !isVisible(mBinding.control.getRoot());
        if (show && player().getDuration() > 0) {
            mBinding.alwaysProgressText.setVisibility(View.VISIBLE);
            long position = player().getPosition();
            long duration = player().getDuration();
            long remaining = duration - position;
            if (remaining < 0) remaining = 0;

            String progress = player().getPositionTime(0);
            String total = player().getDurationTime();

            long finishTime = System.currentTimeMillis() + remaining;
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault());
            String finish = sdf.format(new java.util.Date(finishTime));

            mBinding.alwaysProgressText.setText(getString(R.string.always_progress_format, progress, total, finish));
        } else {
            mBinding.alwaysProgressText.setVisibility(View.GONE);
        }
    }

    private void hideSheet() {
        getSupportFragmentManager().getFragments().stream().filter(fragment -> fragment instanceof BottomSheetDialogFragment).map(fragment -> (BottomSheetDialogFragment) fragment).forEach(BottomSheetDialogFragment::dismiss);
    }

    private void setTraffic() {
        if (isPlayingNow()) {
            hideProgress();
            return;
        }
        Traffic.setSpeed(mBinding.progress.traffic);
        App.post(mR2, 1000);
    }

    private boolean isPlayingNow() {
        if (service() == null || !isOwner()) return false;
        return player().isPlaying() || player().getPlaybackState() == Player.STATE_READY || player().getPosition() > 0;
    }

    private void setOrient() {
        if (isRotate()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            return;
        }
        if (isPort() && isAutoRotate()) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_USER);
        if (isLand() && isAutoRotate()) setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
    }

    private void setR1Callback() {
        App.post(mR1, Constant.INTERVAL_HIDE);
    }

    private void setArtwork(String url) {
        mHistory.setVodPic(url);
        setArtwork();
    }

    private void setArtwork() {
        setBackdrop();
        if (!PlayerSetting.isDetailPoster()) {
            if (mBinding.bgPoster != null) {
                mBinding.bgPoster.setVisibility(View.GONE);
                mBinding.bgOverlay.setVisibility(View.GONE);
            }
            return;
        }
        ImgUtil.load(this, mHistory.getVodPic(), new CustomTarget<>() {
            @Override
            public void onResourceReady(@NonNull Drawable resource, @Nullable Transition<? super Drawable> transition) {
                mBinding.exo.setDefaultArtwork(resource);
                if (mBinding.bgPoster != null) {
                    mBinding.bgPoster.setVisibility(View.VISIBLE);
                    mBinding.bgOverlay.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                mBinding.exo.setDefaultArtwork(errorDrawable);
                if (mBinding.bgPoster != null) {
                    mBinding.bgPoster.setVisibility(View.GONE);
                    mBinding.bgOverlay.setVisibility(View.GONE);
                }
            }
        });
    }

    private void setBackdrop() {
        if (mBinding.bgPoster == null) return;
        if (!PlayerSetting.isDetailPoster() || TextUtils.isEmpty(mHistory.getVodPic())) {
            mBinding.bgPoster.setVisibility(View.GONE);
            mBinding.bgOverlay.setVisibility(View.GONE);
            return;
        }
        try {
            mBinding.bgPoster.setVisibility(View.VISIBLE);
            mBinding.bgOverlay.setVisibility(View.VISIBLE);
            int width = Math.max(1080, ResUtil.getScreenWidth());
            int height = Math.max(1920, ResUtil.getScreenHeight());
            Glide.with(this)
                    .load(ImgUtil.getUrl(mHistory.getVodPic()))
                    .override(width, height)
                    .error(R.drawable.artwork)
                    .into(mBinding.bgPoster);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void checkFlag(Vod item) {
        boolean empty = item.getFlags().isEmpty();
        mBinding.flag.setVisibility(empty ? View.GONE : View.VISIBLE);
        if (empty) {
            startFlow();
        } else {
            onItemClick(mHistory.getFlag());
            if (mHistory.isRevSort()) reverseEpisode(true);
        }
    }

    private void checkHistory(Vod item) {
        mHistory = History.find(getHistoryKey());
        mHistory = mHistory == null ? createHistory(item) : mHistory;
        if (!TextUtils.isEmpty(getMark())) mHistory.setVodRemarks(getMark());
        if (Setting.isIncognito() && mHistory.getKey().equals(getHistoryKey())) mHistory.delete();
        setSkipText();
        mBinding.control.action.ending.setText(getSkipText(R.string.play_ed, mHistory.getEnding()));
        mBinding.control.action.speed.setText(player().setSpeed(mHistory.getSpeed()));
        mHistory.setVodName(item.getName());
        setArtwork(item.getPic());
        setScale(getScale());
    }

    private History createHistory(Vod item) {
        History history = new History();
        history.setKey(getHistoryKey());
        history.setCid(VodConfig.getCid());
        history.setVodName(item.getName());
        history.findEpisode(item.getFlags());
        return history;
    }

    private void saveHistory() {
        saveHistory(false);
    }

    private void saveHistory(boolean exit) {
        updateHistoryProgress();
        if (mHistory != null && mHistory.canSave() && !Setting.isIncognito()) Task.execute(() -> {
            mHistory.merge().save();
            if (exit) {
                RefreshEvent.history();
                com.fongmi.android.tv.utils.WebDavSync.upload(null);
            }
        });
    }

    private void syncHistory(boolean force) {
        if (mHistory != null && !Setting.isIncognito()) Task.execute(() -> {
            mHistory.save();
            if (force || Setting.getSyncInterval() > 0) {
                com.fongmi.android.tv.utils.WebDavSync.upload(null);
            }
        });
    }

    private void updateHistory(Episode item) {
        mHistory.setPosition(item.matchesName(mHistory.getEpisode()) ? mHistory.getPosition() : C.TIME_UNSET);
        mHistory.setVodFlag(getFlag().getFlag());
        mHistory.setVodRemarks(item.getName());
        mHistory.setEpisodeUrl(item.getUrl());
    }

    private void checkControl() {
        if (isVisible(mBinding.control.getRoot())) showControl();
    }

    private void checkKeepImg() {
        mBinding.control.keep.setImageResource(Keep.find(getHistoryKey()) == null ? R.drawable.ic_control_keep_off : R.drawable.ic_control_keep_on);
    }

    private void checkLockImg() {
        mBinding.control.right.lock.setImageResource(isLock() ? R.drawable.ic_control_lock_on : R.drawable.ic_control_lock_off);
    }

    private void checkDanmakuImg() {
        mBinding.control.danmaku.setImageResource(DanmakuSetting.isShow() ? R.drawable.ic_control_danmaku_on : R.drawable.ic_control_danmaku_off);
    }

    private void createKeep() {
        Keep keep = new Keep();
        keep.setKey(getHistoryKey());
        keep.setCid(VodConfig.getCid());
        keep.setVodPic(mHistory.getVodPic());
        keep.setVodName(mHistory.getVodName());
        keep.setSiteName(getSite().getName());
        keep.setCreateTime(System.currentTimeMillis());
        keep.save();
    }

    private void updateKeep() {
        Keep keep = Keep.find(getHistoryKey());
        if (keep != null) {
            keep.setVodName(mHistory.getVodName());
            keep.setVodPic(mHistory.getVodPic());
            keep.save();
        }
    }

    private void updateVod(Vod item) {
        boolean id = !item.getId().isEmpty();
        boolean pic = !item.getPic().isEmpty();
        boolean name = !item.getName().isEmpty();
        if (id) getIntent().putExtra("id", item.getId());
        if (id) mHistory.replace(getHistoryKey());
        if (name) mHistory.setVodName(item.getName());
        if (name) mBinding.name.setText(item.getName());
        if (name) mBinding.control.title.setText(item.getName());
        updateFlag(getFlag(), item.getFlags());
        if (pic) setArtwork(item.getPic());
        if (pic || name) setMetadata();
        if (pic || name) syncHistory(true);
        if (pic || name) updateKeep();
        if (id) updateNavigationKey();
        setText(item);
    }

    private void updateFlag(Flag activated, List<Flag> items) {
        items.forEach(item -> mFlagAdapter.getItems().stream()
                .filter(item::equals).findFirst().ifPresentOrElse(target -> {
                    target.mergeEpisodes(item.getEpisodes(), mHistory.isRevSort());
                    if (target.equals(activated)) setEpisodeAdapter(target.getEpisodes());
                }, () -> mFlagAdapter.add(item)));
    }

    private final PlaybackService.NavigationCallback mNavigationCallback = new PlaybackService.NavigationCallback() {
        @Override
        public void onNext() {
            checkNext();
        }

        @Override
        public void onPrev() {
            checkPrev();
        }

        @Override
        public void onStop() {
            finish();
        }

        @Override
        public void onReplay() {
            VideoActivity.this.onReplay();
        }

        @Override
        public void onAudio() {
            moveTaskToBack(true);
            setAudioOnly(true);
        }
    };

    @Override
    protected String getPlaybackKey() {
        return getHistoryKey();
    }

    @Override
    protected void onPrepare() {
        setEngine();
        setDecode();
    }

    @Override
    protected void onTracksChanged() {
        setTrackVisible();
        mClock.setCallback(this);
    }

    @Override
    protected void onTitlesChanged() {
        setTitleVisible();
    }

    @Override
    protected void onError(String msg) {
        mBinding.swipeLayout.setEnabled(true);
        Track.delete(player().getKey());
        mClock.setCallback(null);
        player().resetTrack();
        player().reset();
        player().stop();
        showError(msg);
        startFlow();
    }

    @Override
    protected void onReclaim() {
        Result result = mViewModel.getPlayer().getValue();
        if (result != null) setPlayer(result);
    }

    @Override
    protected void onStateChanged(int state) {
        switch (state) {
            case Player.STATE_BUFFERING:
                if (isPlayingNow()) hideProgress();
                else showProgress();
                break;
            case Player.STATE_READY:
                hideProgress();
                checkControl();
                player().reset();
                mBinding.control.resolution.setText(player().getSizeText());
                break;
            case Player.STATE_ENDED:
                checkEnded(true);
                break;
        }
    }

    @Override
    protected void onRenderedFirstFrameChanged() {
        hideProgress();
    }

    @Override
    protected void onPlayingChanged(boolean isPlaying) {
        if (isPlaying) {
            hideProgress();
            mPiP.update(this, true);
            mBinding.control.play.setImageResource(androidx.media3.ui.R.drawable.exo_icon_pause);
            mBinding.lrcView.start();
        } else if (isPaused()) {
            mPiP.update(this, false);
            mBinding.control.play.setImageResource(androidx.media3.ui.R.drawable.exo_icon_play);
            mBinding.lrcView.stop();
        }
    }

    @Override
    protected void onSizeChanged(VideoSize size) {
        changeHeight();
        checkOrientation();
        mBinding.control.resolution.setText(player().getSizeText());
    }

    @Override
    public void onSubtitleClick() {
        SubtitleDialog.create().player(player()).view(mBinding.exo.getSubtitleView()).show(this);
        hideControl();
    }

    @Override
    public void onTimeChanged(long time) {
        if (!isOwner()) return;
        if (!player().isPlaying()) return;
        hideProgress();
        long position, duration;
        mHistory.setCreateTime(time);
        mHistory.setPosition(position = player().getPosition());
        mHistory.setDuration(duration = player().getDuration());
        if (mHistory.canSave() && mHistory.canSync()) syncHistory(false);
        if (mHistory.getEnding() > 0 && duration > 0 && mHistory.getEnding() + position >= duration) {
            checkEnded(false);
        }
        if (duration > 0 && duration - position <= PlayerSetting.getPreloadSeconds() * 1000L) preloadNext();
        updateAlwaysProgress();
    }

    private void preloadNext() {
        Flag flag = getFlag();
        Episode episode = mEpisodeAdapter.getNext();
        if (flag == null || episode == null || episode.isSelected()) return;
        PreloadManager.get().preload(getKey(), flag.getFlag(), episode, isUseParse());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (isRedirect()) return;
        ReceiveDialog.create().event(event).show(this);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (isRedirect()) return;
        if (event.getType() == RefreshEvent.Type.DETAIL) getDetail();
        else if (event.getType() == RefreshEvent.Type.PLAYER) onRefresh();
        else if (event.getType() == RefreshEvent.Type.VOD) updateVod(event.getVod());
        else if (event.getType() == RefreshEvent.Type.SUBTITLE) {
            mBinding.lrcView.setTextSize(PlayerSetting.getLrcTextSize());
            if (!event.getPath().isEmpty()) player().setSub(Sub.from(event.getPath()));
        }
        else if (event.getType() == RefreshEvent.Type.DANMAKU) player().setDanmaku(Danmaku.from(event.getPath()));
    }

    private void setPosition() {
        if (mHistory != null) player().seekTo(Math.max(mHistory.getOpening(), mHistory.getPosition()));
    }

    private long getResumePosition() {
        if (mHistory == null) return C.TIME_UNSET;
        long position = Math.max(mHistory.getOpening(), mHistory.getPosition());
        return position > 0 ? position : C.TIME_UNSET;
    }

    private void updateHistoryProgress() {
        if (mHistory == null || player().isEmpty()) return;
        long position = player().getPosition();
        long duration = player().getDuration();
        if (position > 0) mHistory.setPosition(position);
        if (duration > 0) mHistory.setDuration(duration);
        if (position > 0 || duration > 0) mHistory.setCreateTime(System.currentTimeMillis());
    }

    private void checkOrientation() {
        if (player().getVideoWidth() == 0 || player().getVideoHeight() == 0) return;
        if (isFullscreen() && !isRotate() && player().isPortrait()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT);
            setRotate(true);
        } else if (isFullscreen() && isRotate() && player().isLandscape()) {
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE);
            setRotate(false);
        }
    }

    private void changeHeight() {
        if (isLand() || isFullscreen() || isInPictureInPictureMode()) return;
        int videoWidth = player().getVideoWidth();
        int videoHeight = player().getVideoHeight();
        if (videoWidth == 0 || videoHeight == 0) return;
        int viewWidth = ResUtil.getScreenWidth();
        int minHeight = ResUtil.dp2px(220);
        int maxHeight = ResUtil.getScreenHeight() / 2;
        int calculated = (int) (viewWidth * ((float) videoHeight / videoWidth));
        int finalHeight = Math.max(minHeight, Math.min(maxHeight, calculated));
        if (finalHeight == mBinding.video.getHeight()) return;
        if (mAnimator.isRunning()) mAnimator.cancel();
        mAnimator.setIntValues(mBinding.video.getHeight(), finalHeight);
        mAnimator.setDuration(300);
        mAnimator.start();
    }

    private void checkEnded(boolean notify) {
        checkNext(notify);
    }

    private void setTrackVisible() {
        mBinding.control.action.text.setVisibility(player().haveTrack(C.TRACK_TYPE_TEXT) || player().canSetSubtitleStyle() || player().isVod() ? View.VISIBLE : View.GONE);
        mBinding.control.action.audio.setVisibility(player().haveTrack(C.TRACK_TYPE_AUDIO) ? View.VISIBLE : View.GONE);
        mBinding.control.action.video.setVisibility(player().haveTrack(C.TRACK_TYPE_VIDEO) ? View.VISIBLE : View.GONE);
    }

    private void setTitleVisible() {
        mBinding.control.action.title.setVisibility(player().haveTitle() ? View.VISIBLE : View.GONE);
    }

    private MediaMetadata buildMetadata() {
        String title = mHistory.getVodName();
        String episode = getEpisode().getName();
        boolean empty = episode.isEmpty() || title.equals(episode);
        String artist = empty ? "" : episode;
        return PlayerManager.buildMetadata(title, artist, mHistory.getVodPic());
    }

    private void setMetadata() {
        player().setMetadata(buildMetadata());
    }

    private void startFlow() {
        if (!getSite().isChangeable()) return;
        if (isUseParse()) checkParse();
        else checkFlag();
    }

    private void checkParse() {
        int position = mParseAdapter.getPosition();
        boolean last = position == mParseAdapter.getItemCount() - 1;
        boolean pass = position == 0 || last;
        if (last) initParse();
        if (pass) checkFlag();
        else nextParse(position);
    }

    private void initParse() {
        if (mParseAdapter.isEmpty()) return;
        setParse(mParseAdapter.first());
    }

    private void checkFlag() {
        int position = isGone(mBinding.flag) ? -1 : mFlagAdapter.getPosition();
        if (position == mFlagAdapter.getItemCount() - 1) checkSearch(false);
        else nextFlag(position);
    }

    private void checkSearch(boolean force) {
        if (mQuickAdapter.isEmpty()) initSearch(mBinding.name.getText().toString(), true);
        else if (isAutoMode() || force) nextSite();
    }

    private void initSearch(String keyword, boolean auto) {
        setAutoMode(auto);
        setInitAuto(auto);
        startSearch(keyword);
    }

    private boolean isPass(Site item) {
        if (isAutoMode() && !item.isChangeable()) return false;
        return item.isSearchable();
    }

    private void startSearch(String keyword) {
        mQuickAdapter.clear();
        List<Site> sites = new ArrayList<>();
        for (Site item : VodConfig.get().getSites()) if (isPass(item)) sites.add(item);
        mViewModel.searchContent(sites, keyword, true);
    }

    private void setSearch(Result result) {
        List<Vod> items = result.getList();
        items.removeIf(this::mismatch);
        mBinding.quick.setVisibility(View.VISIBLE);
        mQuickAdapter.addAll(items);
        if (isInitAuto()) nextSite();
        if (items.isEmpty()) return;
        App.removeCallbacks(mR4);
    }

    private boolean mismatch(Vod item) {
        if (getId().equals(item.getId())) return true;
        if (mBroken.contains(item.getId())) return true;
        String keyword = mBinding.name.getText().toString();
        if (isAutoMode()) return !item.getName().equals(keyword);
        else return !item.getName().contains(keyword);
    }

    private void nextParse(int position) {
        Parse parse = mParseAdapter.get(position + 1);
        Notify.show(getString(R.string.play_switch_parse, parse.getName()));
        onItemClick(parse);
    }

    private void nextFlag(int position) {
        Flag flag = mFlagAdapter.get(position + 1);
        Notify.show(getString(R.string.play_switch_flag, flag.getFlag()));
        onItemClick(flag);
    }

    private void nextSite() {
        if (mQuickAdapter.isEmpty()) return;
        Vod item = mQuickAdapter.get(0);
        Notify.show(getString(R.string.play_switch_site, item.getSiteName()));
        mQuickAdapter.remove(0);
        mBroken.add(getId());
        setInitAuto(false);
        getDetail(item);
    }

    private void onPaused() {
        controller().pause();
        syncHistory(true);
    }

    private void onPlay() {
        if (mHistory != null && isEnded()) controller().seekTo(mHistory.getOpening());
        if (!player().isEmpty() && isIdle()) controller().prepare();
        controller().play();
        syncHistory(true);
    }

    private boolean isFullscreen() {
        return fullscreen;
    }

    private void setFullscreen(boolean fullscreen) {
        Util.toggleFullscreen(this, this.fullscreen = fullscreen);
    }

    private boolean isInitAuto() {
        return initAuto;
    }

    private void setInitAuto(boolean initAuto) {
        this.initAuto = initAuto;
    }

    private boolean isAutoMode() {
        return autoMode;
    }

    private void setAutoMode(boolean autoMode) {
        this.autoMode = autoMode;
    }

    public boolean isUseParse() {
        return useParse;
    }

    public void setUseParse(boolean useParse) {
        this.useParse = useParse;
    }

    public boolean isRotate() {
        return rotate;
    }

    public void setRotate(boolean rotate) {
        this.rotate = rotate;
        if (fullscreen && !rotate) setPadding(mBinding.control.getRoot());
        else noPadding(mBinding.control.getRoot());
    }

    private void notifyItemChanged(RecyclerView view, RecyclerView.Adapter<?> adapter) {
        view.post(() -> adapter.notifyItemRangeChanged(0, adapter.getItemCount()));
    }

    private void scrollToPosition(RecyclerView view, int position) {
        view.post(() -> view.scrollToPosition(position));
    }

    @Override
    public void onCasted() {
        player().stop();
    }

    @Override
    public void onScale(int tag) {
        mKeyDown.resetScale();
        setScale(tag);
    }

    @Override
    public void onParse(Parse item) {
        onItemClick(item);
    }

    @Override
    public void onSpeedUp() {
        if (!player().isPlaying()) return;
        mBinding.widget.speed.setVisibility(View.VISIBLE);
        mBinding.widget.speed.startAnimation(ResUtil.getAnim(R.anim.forward));
        mBinding.control.action.speed.setText(player().setSpeed(PlayerSetting.getSpeed()));
    }

    @Override
    public void onSpeedEnd() {
        mBinding.widget.speed.clearAnimation();
        mBinding.control.action.speed.setText(player().setSpeed(mHistory.getSpeed()));
    }

    @Override
    public void onBright(int progress) {
        mBinding.widget.bright.setVisibility(View.VISIBLE);
        mBinding.widget.brightProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_low);
        else if (progress < 70) mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_medium);
        else mBinding.widget.brightIcon.setImageResource(R.drawable.ic_widget_bright_high);
    }

    @Override
    public void onVolume(int progress) {
        mBinding.widget.volume.setVisibility(View.VISIBLE);
        mBinding.widget.volumeProgress.setProgress(progress);
        if (progress < 35) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_low);
        else if (progress < 70) mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_medium);
        else mBinding.widget.volumeIcon.setImageResource(R.drawable.ic_widget_volume_high);
    }

    @Override
    public void onFlingUp() {
        if (mEpisodeAdapter.getItemCount() == 1) onRefresh();
        else checkNext();
    }

    @Override
    public void onFlingDown() {
        if (mEpisodeAdapter.getItemCount() == 1) onRefresh();
        else checkPrev();
    }

    @Override
    public void onSeeking(long time) {
        mBinding.widget.action.setImageResource(time > 0 ? R.drawable.ic_widget_forward : R.drawable.ic_widget_rewind);
        mBinding.widget.time.setText(player().getPositionTime(time));
        mBinding.widget.seek.setVisibility(View.VISIBLE);
        hideProgress();
    }

    @Override
    public void onSeekEnd(long time) {
        seekTo(time);
        syncHistory(true);
    }

    @Override
    public void onSingleTap() {
        if (isVisible(mBinding.control.getRoot())) hideControl();
        else showControl();
    }

    @Override
    public void onDoubleTap() {
        if (isLock()) return;
        if (!isFullscreen()) {
            enterFullscreen();
        } else if (player().isPlaying()) {
            showControl();
            onPaused();
        } else {
            hideControl();
            onPlay();
        }
    }

    @Override
    public void onTouchEnd() {
        mBinding.widget.seek.setVisibility(View.GONE);
        mBinding.widget.speed.setVisibility(View.GONE);
        mBinding.widget.bright.setVisibility(View.GONE);
        mBinding.widget.volume.setVisibility(View.GONE);
    }

    @Override
    public void onShare(CharSequence title) {
        PlayerHelper.share(this, player().getUrl(), player().getHeaders(), title);
        setRedirect(true);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == RESULT_OK && requestCode == 1001) PlayerHelper.onExternalResult(data, service()::dispatchNext, controller()::seekTo);
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        if (leavingPlayback) return;
        if (isRedirect()) return;
        if (isLock()) App.post(this::onLock, 500);
        if (service() != null && player().haveTrack(C.TRACK_TYPE_VIDEO)) mPiP.enter(this, player().getVideoWidth(), player().getVideoHeight(), getScale());
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, @NonNull Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);
        if (!isFullscreen()) setVideoView(isInPictureInPictureMode);
        if (isInPictureInPictureMode) {
            hideControl();
            hideDanmaku();
            hideSheet();
            saveHistory(true);
        } else {
            showDanmaku();
            if (isStop()) finish();
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (isAutoRotate() && isPort() && newConfig.orientation == Configuration.ORIENTATION_PORTRAIT && !isRotate() && !isLock()) exitFullscreen();
        if (isAutoRotate() && isPort() && newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) enterFullscreen();
        if (isFullscreen()) Util.hideSystemUI(this);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (isFullscreen() && hasFocus) Util.hideSystemUI(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mClock.stop().start();
        setAudioOnly(false);
        setStop(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        saveHistory(true);
        stopPlaybackIfLeaving();
        if (PlayerSetting.isBackgroundOff()) mClock.stop();
        if (!isAudioOnly()) setStop(true);
    }

    private void stopPlaybackIfLeaving() {
        if (isRedirect() || isInPictureInPictureMode() || isAudioOnly()) return;
        if (PlayerSetting.isBackgroundOn() && !leavingPlayback && !isFinishing()) return;
        if (!leavingPlayback && !isFinishing() && hasWindowFocus()) return;
        if (service() == null || !isOwner()) return;
        player().stop();
        player().clear();
        service().shutdown();
    }

    @Override
    protected void onBackInvoked() {
        if (isVisible(mBinding.control.getRoot())) {
            hideControl();
        } else if (mReader.isActive() && !isLock()) {
            leavingPlayback = true;
            stopPlaybackIfLeaving();
            mViewModel.stopSearch();
            if (isTaskRoot()) startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            super.onBackInvoked();
        } else if (isFullscreen() && !isLock()) {
            exitFullscreen();
        } else if (!isLock()) {
            leavingPlayback = true;
            stopPlaybackIfLeaving();
            mViewModel.stopSearch();
            if (isTaskRoot()) startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        stopPlaybackIfLeaving();
        mClock.release();
        if (mReader != null) mReader.clear();
        mBinding.lrcView.clear();
        saveHistory(true);
        Timer.get().reset();
        DanmakuApi.cancel();
        RefreshEvent.keep();
        App.removeCallbacks(mR1, mR2, mR3, mR4);
        mViewModel.getResult().removeObserver(mObserveDetail);
        mViewModel.getPlayer().removeObserver(mObservePlayer);
        mViewModel.getSearch().removeObserver(mObserveSearch);
        super.onDestroy();
    }
}

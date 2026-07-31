package com.fongmi.android.tv.ui.activity;

import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.TextUtils;
import android.graphics.drawable.Drawable;
import android.widget.RelativeLayout;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.fongmi.android.tv.utils.ResUtil;


import androidx.annotation.NonNull;
import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ScrollEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.receiver.ShortcutReceiver;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.activity.DownloadActivity;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.FragmentStateManager;
import com.fongmi.android.tv.ui.fragment.LocalFragment;
import com.fongmi.android.tv.ui.fragment.HotFragment;
import com.fongmi.android.tv.ui.fragment.SettingDanmakuFragment;
import com.fongmi.android.tv.ui.fragment.SettingFragment;
import com.fongmi.android.tv.ui.fragment.SettingPreloadFragment;
import com.fongmi.android.tv.ui.fragment.SettingPlayerFragment;
import com.fongmi.android.tv.ui.fragment.SettingHomeFragment;
import com.fongmi.android.tv.ui.fragment.VodFragment;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.google.android.material.navigation.NavigationBarView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class HomeActivity extends BaseActivity implements NavigationBarView.OnItemSelectedListener {

    private FragmentStateManager mManager;
    private ActivityHomeBinding mBinding;
    private int orientation;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityHomeBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        checkAction(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        orientation = getResources().getConfiguration().orientation;
        mBinding.navigation.setOnItemSelectedListener(this);
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.navigation, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            boolean capsule = com.fongmi.android.tv.setting.Setting.isHomeCapsule();
            RelativeLayout.LayoutParams navParams = (RelativeLayout.LayoutParams) v.getLayoutParams();
            RelativeLayout.LayoutParams blurParams = (RelativeLayout.LayoutParams) mBinding.blurView.getLayoutParams();
            if (capsule) {
                navParams.height = ResUtil.dp2px(56);
                navParams.setMargins(ResUtil.dp2px(16), 0, ResUtil.dp2px(16), bottom + ResUtil.dp2px(16));
                v.setPadding(ResUtil.dp2px(12), 0, ResUtil.dp2px(12), 0);
            } else {
                navParams.height = ResUtil.dp2px(56) + bottom;
                navParams.setMargins(0, 0, 0, 0);
                v.setPadding(0, 0, 0, bottom);
            }
            v.setLayoutParams(navParams);

            blurParams.height = navParams.height;
            blurParams.width = navParams.width;
            blurParams.setMargins(navParams.leftMargin, navParams.topMargin, navParams.rightMargin, navParams.bottomMargin);
            mBinding.blurView.setLayoutParams(blurParams);
            mBinding.blurView.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), v.getPaddingBottom());

            return insets;
        });
        PermissionUtil.requestNotify(this);
        setupBlurView();
        Updater.create().start(this);
        if (com.fongmi.android.tv.setting.Setting.isSyncAutoSync()) {
            checkSync(savedInstanceState);
        } else {
            initFragment(savedInstanceState);
            initConfig();
        }
    }

    private boolean mSyncDone;

    private void checkSync(Bundle savedInstanceState) {
        mSyncDone = false;
        Notify.show(R.string.sync_syncing);
        com.fongmi.android.tv.utils.WebDavSync.download(new Callback() {
            @Override
            public void success() {
                if (mSyncDone) return;
                mSyncDone = true;
                Notify.show(R.string.sync_success);
                initFragment(savedInstanceState);
                initConfig();
            }

            @Override
            public void error() {
                if (mSyncDone) return;
                mSyncDone = true;
                Notify.show(R.string.sync_fail);
                initFragment(savedInstanceState);
                initConfig();
            }
        });
        App.post(() -> {
            if (mSyncDone) return;
            mSyncDone = true;
            Notify.show(R.string.sync_fail);
            initFragment(savedInstanceState);
            initConfig();
        }, 3000);
    }

    @Override
    protected void initEvent() {
        mBinding.navigation.findViewById(R.id.live).setOnLongClickListener(this::addShortcut);
    }

    private void checkAction(Intent intent) {
        if (Intent.ACTION_SEND.equals(intent.getAction())) {
            VideoActivity.push(this, intent.getStringExtra(Intent.EXTRA_TEXT));
        } else if (Intent.ACTION_VIEW.equals(intent.getAction()) && intent.getData() != null) {
            PermissionUtil.requestFile(this, allGranted -> checkType(intent));
        } else if (Intent.ACTION_SEARCH.equals(intent.getAction())) {
            String keyword = intent.getStringExtra(SearchManager.QUERY);
            if (!TextUtils.isEmpty(keyword)) SearchActivity.start(this, keyword);
        }
    }

    private void checkType(Intent intent) {
        if ("text/plain".equals(intent.getType()) || UrlUtil.path(intent.getData()).endsWith(".m3u")) {
            loadLive("file:/" + FileChooser.getPathFromUri(intent.getData()));
        } else {
            VideoActivity.push(this, intent.getData().toString());
        }
    }

    private void initFragment(Bundle savedInstanceState) {
        mManager = new FragmentStateManager(mBinding.container, getSupportFragmentManager(), position -> switch (position) {
            case 0 -> VodFragment.newInstance();
            case 1 -> SettingFragment.newInstance();
            case 2 -> SettingPlayerFragment.newInstance();
            case 3 -> SettingDanmakuFragment.newInstance();
            case 4 -> LocalFragment.newInstance();
            case 5 -> SettingHomeFragment.newInstance();
            case 6 -> SettingPreloadFragment.newInstance();
            case 7 -> HotFragment.newInstance();
            default -> null;
        });
        if (savedInstanceState == null) change(0);
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init();
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void success() {
                checkAction(getIntent());
            }

            @Override
            public void error(String msg) {
                checkAction(getIntent());
                StateEvent.empty();
                Notify.show(msg);
            }
        };
    }

    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                openLive();
            }
        });
    }

    private int getHomePosition() {
        if (com.fongmi.android.tv.setting.Setting.isHomeVod()) return 0;
        if (com.fongmi.android.tv.setting.Setting.isHomeLocal()) return 4;
        return 1;
    }

    private void setNavigation() {
        boolean capsule = com.fongmi.android.tv.setting.Setting.isHomeCapsule();

        mBinding.navigation.getMenu().findItem(R.id.vod).setVisible(com.fongmi.android.tv.setting.Setting.isHomeVod());
        mBinding.navigation.getMenu().findItem(R.id.hot).setVisible(com.fongmi.android.tv.setting.Setting.isHomeHot());
        mBinding.navigation.getMenu().findItem(R.id.setting).setVisible(true);
        mBinding.navigation.getMenu().findItem(R.id.local).setVisible(com.fongmi.android.tv.setting.Setting.isHomeLocal());
        mBinding.navigation.getMenu().findItem(R.id.download).setVisible(com.fongmi.android.tv.setting.Setting.isHomeDownload());
        mBinding.navigation.getMenu().findItem(R.id.live).setVisible(com.fongmi.android.tv.setting.Setting.isHomeLive() && LiveConfig.hasUrl());

        int visibleCount = 0;
        for (int i = 0; i < mBinding.navigation.getMenu().size(); i++) {
            if (mBinding.navigation.getMenu().getItem(i).isVisible()) {
                visibleCount++;
            }
        }

        RelativeLayout.LayoutParams navParams = (RelativeLayout.LayoutParams) mBinding.navigation.getLayoutParams();
        RelativeLayout.LayoutParams containerParams = (RelativeLayout.LayoutParams) mBinding.container.getLayoutParams();

        if (capsule) {
            containerParams.removeRule(RelativeLayout.ABOVE);
            navParams.width = visibleCount * ResUtil.dp2px(64) + ResUtil.dp2px(24);
            navParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
            
            mBinding.blurView.setVisibility(View.VISIBLE);
            mBinding.blurView.setBackgroundResource(R.drawable.shape_capsule);
            mBinding.blurView.setElevation(ResUtil.dp2px(4));

            mBinding.navigation.setBackgroundResource(android.R.color.transparent);
            mBinding.navigation.setElevation(ResUtil.dp2px(5));

            RelativeLayout.LayoutParams blurParams = (RelativeLayout.LayoutParams) mBinding.blurView.getLayoutParams();
            blurParams.width = navParams.width;
            blurParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
            mBinding.blurView.setLayoutParams(blurParams);
        } else {
            containerParams.addRule(RelativeLayout.ABOVE, R.id.navigation);
            navParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            navParams.removeRule(RelativeLayout.CENTER_HORIZONTAL);

            mBinding.navigation.setBackgroundResource(android.R.color.transparent);
            mBinding.navigation.setElevation(0);

            mBinding.blurView.setVisibility(View.GONE);
            mBinding.blurView.setElevation(0);

            RelativeLayout.LayoutParams blurParams = (RelativeLayout.LayoutParams) mBinding.blurView.getLayoutParams();
            blurParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            blurParams.removeRule(RelativeLayout.CENTER_HORIZONTAL);
            mBinding.blurView.setLayoutParams(blurParams);
        }

        mBinding.container.setLayoutParams(containerParams);
        mBinding.navigation.setLayoutParams(navParams);

        int currentId = mBinding.navigation.getSelectedItemId();
        if (currentId == R.id.vod && !com.fongmi.android.tv.setting.Setting.isHomeVod()) {
            change(getHomePosition());
        } else if (currentId == R.id.hot) {
            change(getHomePosition());
        } else if (currentId == R.id.local && !com.fongmi.android.tv.setting.Setting.isHomeLocal()) {
            change(getHomePosition());
        } else if (currentId == R.id.download && !com.fongmi.android.tv.setting.Setting.isHomeDownload()) {
            change(getHomePosition());
        } else if (currentId == R.id.live && (!com.fongmi.android.tv.setting.Setting.isHomeLive() || !LiveConfig.hasUrl())) {
            change(getHomePosition());
        }

        ViewCompat.requestApplyInsets(mBinding.navigation);
    }

    private boolean openDownload() {
        DownloadActivity.start(this);
        return false;
    }

    private boolean openLive() {
        LiveActivity.start(this);
        return false;
    }

    private boolean addShortcut(View view) {
        ShortcutInfoCompat info = new ShortcutInfoCompat.Builder(this, getString(R.string.nav_live)).setIcon(IconCompat.createWithResource(this, R.drawable.ic_launcher)).setIntent(new Intent(Intent.ACTION_VIEW, null, this, LiveActivity.class)).setShortLabel(getString(R.string.nav_live)).build();
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(this, ShortcutReceiver.class).setAction(ShortcutReceiver.ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        ShortcutManagerCompat.requestPinShortcut(this, info, pendingIntent.getIntentSender());
        return true;
    }

    public void change(int position) {
        if (position == 0 && !com.fongmi.android.tv.setting.Setting.isHomeVod()) {
            position = getHomePosition();
        }
        if (position == 7 && !com.fongmi.android.tv.setting.Setting.isHomeHot()) {
            position = getHomePosition();
        }
        if (position == 4) mBinding.navigation.setSelectedItemId(R.id.local);
        else if (position < 2) mBinding.navigation.setSelectedItemId(position == 0 ? R.id.vod : R.id.setting);
        else mManager.change(position);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.home();
                break;
            case COMMON:
                setNavigation();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (event.getType() == RefreshEvent.Type.THEME) recreate();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() == ServerEvent.Type.PUSH) VideoActivity.push(this, event.text());
        if (event.type() == ServerEvent.Type.SEARCH) SearchActivity.start(this, event.text());
    }

    private boolean mCapsuleVisible = true;
    private boolean mCapsuleAnimating = false;

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onScrollEvent(ScrollEvent event) {
        if (!com.fongmi.android.tv.setting.Setting.isHomeCapsule()) return;
        if (event.getDy() > 0 && mCapsuleVisible && !mCapsuleAnimating) {
            hideCapsule();
        } else if (event.getDy() < 0 && !mCapsuleVisible && !mCapsuleAnimating) {
            showCapsule();
        }
    }

    private void hideCapsule() {
        mCapsuleVisible = false;
        mCapsuleAnimating = true;
        mBinding.navigation.animate().cancel();
        mBinding.blurView.animate().cancel();
        RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) mBinding.navigation.getLayoutParams();
        int hideDistance = mBinding.navigation.getHeight() + params.bottomMargin + ResUtil.dp2px(24);
        mBinding.navigation.animate().translationY(hideDistance).setDuration(250).withEndAction(() -> mCapsuleAnimating = false).start();
        mBinding.blurView.animate().translationY(hideDistance).setDuration(250).start();
    }

    private void showCapsule() {
        mCapsuleVisible = true;
        mCapsuleAnimating = true;
        mBinding.navigation.animate().cancel();
        mBinding.blurView.animate().cancel();
        mBinding.navigation.animate().translationY(0).setDuration(250).withEndAction(() -> mCapsuleAnimating = false).start();
        mBinding.blurView.animate().translationY(0).setDuration(250).start();
    }

    @Override
    public boolean onNavigationItemSelected(@NonNull MenuItem item) {
        showCapsule();
        if (item.getItemId() == R.id.setting) return mManager.change(1);
        if (item.getItemId() == R.id.vod) return mManager.change(0);
        if (item.getItemId() == R.id.hot) return mManager.change(7);
        if (item.getItemId() == R.id.local) return mManager.change(4);
        if (item.getItemId() == R.id.download) return openDownload();
        if (item.getItemId() == R.id.live) return openLive();
        return false;
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        App.post(() -> checkOrientation(newConfig), 100);
    }

    private void checkOrientation(Configuration newConfig) {
        if (orientation != newConfig.orientation) {
            orientation = newConfig.orientation;
            RefreshEvent.home();
        }
    }

    @Override
    protected void onBackInvoked() {
        int homePosition = getHomePosition();
        if (!mBinding.navigation.getMenu().findItem(R.id.setting).isVisible()) {
            setNavigation();
        } else if (mManager.isVisible(6)) {
            change(2);
        } else if (mManager.isVisible(2) || mManager.isVisible(3) || mManager.isVisible(5)) {
            change(1);
        } else if (mManager.isVisible(1) || mManager.isVisible(4) || mManager.isVisible(7)) {
            if (mManager.isVisible(homePosition)) {
                if (PlaybackService.isRunning()) moveTaskToBack(true);
                else super.onBackInvoked();
            } else {
                change(homePosition);
            }
        } else if (mManager.canBack(0)) {
            if (PlaybackService.isRunning()) moveTaskToBack(true);
            else super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        LiveConfig.get().clear();
        VodConfig.get().clear();
        AppDatabase.backup();
        OkHttp.get().clear();
        Source.get().exit();
        Server.get().stop();
        super.onDestroy();
    }

    private void setupBlurView() {
        float radius = 15f;
        View decorView = getWindow().getDecorView();
        ViewGroup rootView = decorView.findViewById(android.R.id.content);
        Drawable windowBackground = decorView.getBackground();

        eightbitlab.com.blurview.BlurAlgorithm algorithm;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            algorithm = new eightbitlab.com.blurview.RenderEffectBlur();
        } else {
            algorithm = new eightbitlab.com.blurview.RenderScriptBlur(this);
        }

        mBinding.blurView.setupWith(rootView, algorithm)
                .setFrameClearDrawable(windowBackground)
                .setBlurRadius(radius);
        mBinding.blurView.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), ResUtil.dp2px(28));
            }
        });
        mBinding.blurView.setClipToOutline(true);
    }
}

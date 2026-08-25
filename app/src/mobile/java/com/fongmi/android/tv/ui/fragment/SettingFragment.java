package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.BuildConfig;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Live;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.FragmentSettingBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.LiveListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LiveDialog;
import com.fongmi.android.tv.ui.dialog.ProxySubscriptionDialog;
import com.fongmi.android.tv.ui.dialog.RestoreDialog;
import com.fongmi.android.tv.ui.dialog.WebDavDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.dialog.ThemeDialog;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.bean.Doh;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class SettingFragment extends BaseFragment implements ConfigListener, SiteListener, LiveListener, ThemeDialog.Listener {

    private FragmentSettingBinding mBinding;
    private String[] size;
    private String[] transition;
    private boolean showAuthor;

    public static SettingFragment newInstance() {
        return new SettingFragment();
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    private String getThemeText() {
        int color = Setting.getThemeColor();
        if (color == -1) return getString(R.string.setting_off);
        return getString(color == 0 ? R.string.setting_auto : R.string.setting_custom);
    }

    private int getDohIndex() {
        return Math.max(0, VodConfig.get().getDoh().indexOf(Doh.objectFrom(Setting.getDoh())));
    }

    private String[] getDohList() {
        List<String> list = new ArrayList<>();
        for (Doh item : VodConfig.get().getDoh()) list.add(item.getName());
        return list.toArray(new String[0]);
    }

    private HomeActivity getRoot() {
        return (HomeActivity) requireActivity();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        mBinding.wallUrl.setText(WallConfig.getDesc());
        setVersionText();
        setOtherText();
        setCacheText();
    }

    private void setVersionText() {
        mBinding.versionText.setText(showAuthor ? "by困困兔" : BuildConfig.VERSION_NAME);
    }

    private void setOtherText() {
        mBinding.themeColorText.setText(getThemeText());
        mBinding.dohText.setText(getDohList()[getDohIndex()]);
        mBinding.incognitoText.setText(getSwitch(Setting.isIncognito()));
        mBinding.adblockText.setText(getSwitch(Setting.isAdblock()));
        mBinding.aiAdblockText.setText(getSwitch(Setting.isAiAdblock()));
        mBinding.sizeText.setText((size = ResUtil.getStringArray(R.array.select_size))[PlayerSetting.getSize()]);
        mBinding.transitionText.setText((transition = ResUtil.getStringArray(R.array.select_transition))[Setting.getTransition()]);
        mBinding.proxySubText.setText(com.fongmi.android.tv.proxy.ProxySubscriptionManager.get().getSummary());
        mBinding.tmdbText.setText(maskTmdbKey());
    }

    private void setCacheText() {
        FileUtil.getCacheSize(new Callback() {
            @Override
            public void success(String result) {
                mBinding.cacheText.setText(result);
            }
        });
    }

    @Override
    protected void initEvent() {
        mBinding.vod.setOnClickListener(this::onVod);
        mBinding.doh.setOnClickListener(this::setDoh);
        mBinding.proxySub.setOnClickListener(view -> ProxySubscriptionDialog.show(this, () -> mBinding.proxySubText.setText(com.fongmi.android.tv.proxy.ProxySubscriptionManager.get().getSummary())));
        mBinding.autoSite.setOnClickListener(view -> getRoot().change(9));
        mBinding.tmdb.setOnClickListener(this::onTmdb);
        mBinding.tmdb.setOnLongClickListener(this::onTmdbClear);
        mBinding.live.setOnClickListener(this::onLive);
        mBinding.wall.setOnClickListener(this::onWall);
        mBinding.size.setOnClickListener(this::setSize);
        mBinding.cache.setOnClickListener(this::onCache);
        mBinding.downloadManager.setOnClickListener(this::onDownloadManager);
        mBinding.backup.setOnClickListener(this::onBackup);
        mBinding.player.setOnClickListener(this::onPlayer);
        mBinding.danmaku.setOnClickListener(this::onDanmaku);
        mBinding.home.setOnClickListener(this::onHome);
        mBinding.restore.setOnClickListener(this::onRestore);
        mBinding.sync.setOnClickListener(view -> onSync());
        mBinding.version.setOnClickListener(this::onVersion);
        mBinding.debug.setOnClickListener(this::onDebug);
        mBinding.vod.setOnLongClickListener(this::onVodEdit);
        mBinding.vodHome.setOnClickListener(this::onVodHome);
        mBinding.live.setOnLongClickListener(this::onLiveEdit);
        mBinding.liveHome.setOnClickListener(this::onLiveHome);
        mBinding.wall.setOnLongClickListener(this::onWallEdit);
        mBinding.incognito.setOnClickListener(this::setIncognito);
        mBinding.adblock.setOnClickListener(view -> getRoot().change(8));
        mBinding.transition.setOnClickListener(this::setTransition);
        mBinding.vodHistory.setOnClickListener(this::onVodHistory);
        mBinding.themeColor.setOnClickListener(this::onThemeColor);
        mBinding.liveHistory.setOnClickListener(this::onLiveHistory);
        mBinding.wallDefault.setOnClickListener(this::setWallDefault);
        mBinding.wallRefresh.setOnClickListener(this::setWallRefresh);
        mBinding.wallRefresh.setOnLongClickListener(this::onWallHistory);

        ((NestedScrollView) mBinding.getRoot().findViewById(R.id.scrollView)).setOnScrollChangeListener((android.view.View.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            com.fongmi.android.tv.event.ScrollEvent.post(scrollY - oldScrollY);
        });
    }

    @Override
    public void setConfig(Config config) {
        if (config.getUrl().startsWith("file")) {
            requireView().post(() -> PermissionUtil.requestFile(this, allGranted -> load(config)));
        } else {
            load(config);
        }
    }

    private void load(Config config) {
        switch (config.getType()) {
            case 0:
                VodConfig.load(config, getCallback());
                break;
            case 1:
                LiveConfig.load(config, getCallback());
                break;
            case 2:
                Setting.putWall(0);
                WallConfig.load(config, getCallback());
                break;
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(requireActivity());
            }

            @Override
            public void success() {
                Notify.dismiss();
                setCacheText();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void setLive(Live item) {
        LiveConfig.get().setHome(item);
    }

    @Override
    public void setTheme(int color) {
        Setting.putThemeColor(color);
        RefreshEvent.theme();
    }

    private void onVod(View view) {
        ConfigDialog.create().vod().show(this);
    }

    private void onLive(View view) {
        ConfigDialog.create().live().show(this);
    }

    private void onWall(View view) {
        ConfigDialog.create().wall().show(this);
    }

    private boolean onVodEdit(View view) {
        ConfigDialog.create().vod().edit().show(this);
        return true;
    }

    private boolean onLiveEdit(View view) {
        ConfigDialog.create().live().edit().show(this);
        return true;
    }

    private boolean onWallEdit(View view) {
        ConfigDialog.create().wall().edit().show(this);
        return true;
    }

    private void onVodHome(View view) {
        SiteDialog.create().search().change().show(this);
    }

    private void onLiveHome(View view) {
        LiveDialog.show(this);
    }

    private void onVodHistory(View view) {
        HistoryDialog.create().vod().show(this);
    }

    private void onLiveHistory(View view) {
        HistoryDialog.create().live().show(this);
    }

    private void onPlayer(View view) {
        getRoot().change(2);
    }

    private void onDanmaku(View view) {
        getRoot().change(3);
    }

    private void onHome(View view) {
        getRoot().change(5);
    }

    private void onThemeColor(View view) {
        ThemeDialog.show(this);
    }

    private void onVersion(View view) {
        showAuthor = !showAuthor;
        setVersionText();
    }

    private void onDebug(View view) {
        if (com.fongmi.android.tv.server.DebugServer.isRunning()) {
            com.fongmi.android.tv.server.DebugServer.stopServer();
            mBinding.debugText.setText("未启动");
            Notify.show("调试服务已停止");
        } else {
            com.fongmi.android.tv.server.DebugServer.startServer();
            mBinding.debugText.setText("运行中 127.0.0.1:1314");
            Notify.show("调试页: http://127.0.0.1:1314  局域网: http://" + getLocalIp() + ":1314");
        }
    }

    private String getLocalIp() {
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                java.net.NetworkInterface ni = en.nextElement();
                for (java.util.Enumeration<java.net.InetAddress> ee = ni.getInetAddresses(); ee.hasMoreElements(); ) {
                    java.net.InetAddress ia = ee.nextElement();
                    if (!ia.isLoopbackAddress() && ia instanceof java.net.Inet4Address) return ia.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private void onTmdb(View view) {
        android.view.View dialogView = LayoutInflater.from(requireActivity()).inflate(R.layout.dialog_tmdb, null);
        androidx.appcompat.widget.AppCompatEditText apiUrlEdit = dialogView.findViewById(R.id.tmdbApiUrl);
        androidx.appcompat.widget.AppCompatEditText imageUrlEdit = dialogView.findViewById(R.id.tmdbImageUrl);
        androidx.appcompat.widget.AppCompatEditText apiKeyEdit = dialogView.findViewById(R.id.tmdbApiKey);

        // 填入已保存的值
        apiUrlEdit.setText(Setting.getTmdbApiUrl());
        imageUrlEdit.setText(Setting.getTmdbImageUrl());
        apiKeyEdit.setText(Setting.getTmdbApiKey());

        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.setting_tmdb)
                .setView(dialogView)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    Setting.putTmdbApiUrl(apiUrlEdit.getText().toString());
                    Setting.putTmdbImageUrl(imageUrlEdit.getText().toString());
                    Setting.putTmdbApiKey(apiKeyEdit.getText().toString());
                    mBinding.tmdbText.setText(maskTmdbKey());
                    Notify.show(Setting.hasTmdbApiKey() ? R.string.setting_tmdb_logged_in : R.string.setting_tmdb_not_set);
                })
                .show();
    }

    /** 脱敏显示 TMDB Key：有值时只显示前8位+...，未配置显示"未配置" */
    private String maskTmdbKey() {
        String key = Setting.getTmdbApiKey();
        if (key.isEmpty()) return getString(R.string.setting_tmdb_not_set);
        return key.length() > 8 ? key.substring(0, 8) + "..." : key;
    }

    private boolean onTmdbClear(View view) {
        Setting.putTmdbApiUrl("");
        Setting.putTmdbImageUrl("");
        Setting.putTmdbApiKey("");
        mBinding.tmdbText.setText(getString(R.string.setting_tmdb_not_set));
        Notify.show(R.string.setting_tmdb_cleared);
        return true;
    }

    private void setWallDefault(View view) {
        Setting.putWall(Setting.getWall() == 4 ? 1 : Setting.getWall() + 1);
        Setting.putWallType(0);
        ConfigEvent.wall();
    }

    private void setWallRefresh(View view) {
        Setting.putWall(0);
        WallConfig.get().load(getCallback());
    }

    private boolean onWallHistory(View view) {
        HistoryDialog.create().wall().show(this);
        return true;
    }

    private void setIncognito(View view) {
        Setting.putIncognito(!Setting.isIncognito());
        mBinding.incognitoText.setText(getSwitch(Setting.isIncognito()));
    }

    private void setSize(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_size).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(size, PlayerSetting.getSize(), (dialog, which) -> {
            mBinding.sizeText.setText(size[which]);
            PlayerSetting.putSize(which);
            RefreshEvent.size();
            dialog.dismiss();
        }).show();
    }

    private void setTransition(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_transition).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(transition, Setting.getTransition(), (dialog, which) -> {
            mBinding.transitionText.setText(transition[which]);
            Setting.putTransition(which);
            dialog.dismiss();
        }).show();
    }

    private void setDoh(View view) {
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.setting_doh).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(getDohList(), getDohIndex(), (dialog, which) -> {
            setDoh(VodConfig.get().getDoh().get(which));
            dialog.dismiss();
        }).show();
    }

    private void setDoh(Doh doh) {
        OkHttp.dns().setDoh(doh);
        Setting.putDoh(doh.toString());
        mBinding.dohText.setText(doh.getName());
    }

    private void onCache(View view) {
        FileUtil.clearCache(new Callback() {
            @Override
            public void success() {
                setCacheText();
            }
        });
    }

    private void onDownloadManager(View view) {
        com.fongmi.android.tv.ui.activity.DownloadActivity.start(requireActivity());
    }

    private void onBackup(View view) {
        PermissionUtil.requestFile(this, allGranted -> AppDatabase.backup(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.backup_success);
            }

            @Override
            public void error() {
                Notify.show(R.string.backup_fail);
            }
        }));
    }

    private void onRestore(View view) {
        PermissionUtil.requestFile(this, allGranted -> RestoreDialog.create().show(requireActivity(), new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.restore_success);
                setOtherText();
                initConfig();
            }

            @Override
            public void error() {
                Notify.show(R.string.restore_fail);
            }
        }));
    }

    private void onSync() {
        WebDavDialog.create().show(requireActivity(), new Callback() {
            @Override
            public void success() {
                setOtherText();
                initConfig();
            }
        });
    }

    private void initConfig() {
        VodConfig.get().init().load(getCallback());
        LiveConfig.get().init().load();
        WallConfig.get().init().load();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() != ConfigEvent.Type.COMMON) return;
        mBinding.vodUrl.setText(VodConfig.getDesc());
        mBinding.liveUrl.setText(LiveConfig.getDesc());
        mBinding.wallUrl.setText(WallConfig.getDesc());
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (hidden) return;
        setCacheText();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        EventBus.getDefault().unregister(this);
    }
}

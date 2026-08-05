package com.fongmi.android.tv.ui.activity;

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.splashscreen.SplashScreen;
import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.player.PlayerManager;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.Updater;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Cache;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Func;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.ActivityHomeBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.player.Source;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.service.DLNARendererService;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.ui.adapter.BaseDiffCallback;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.custom.CustomRowPresenter;
import com.fongmi.android.tv.ui.custom.CustomSelector;
import com.fongmi.android.tv.ui.custom.CustomTitleView;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.ui.presenter.FuncPresenter;
import com.fongmi.android.tv.ui.presenter.HeaderPresenter;
import com.fongmi.android.tv.ui.presenter.HistoryPresenter;
import com.fongmi.android.tv.ui.presenter.ProgressPresenter;
import com.fongmi.android.tv.ui.presenter.VodPresenter;
import com.fongmi.android.tv.utils.Clock;
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.UrlUtil;
import com.fongmi.android.tv.utils.Util;
import com.github.catvod.net.OkHttp;
import com.google.common.collect.Lists;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import com.fongmi.android.tv.bean.HomeBanner;
import com.fongmi.android.tv.ui.presenter.HomeBannerPresenter;
import android.content.ServiceConnection;
import android.content.ComponentName;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import android.view.ViewGroup;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.viewpager.widget.ViewPager;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.fragment.FolderFragment;

public class HomeActivity extends BaseActivity implements CustomTitleView.Listener, VodPresenter.OnClickListener, FuncPresenter.OnClickListener, HistoryPresenter.OnClickListener, TypeAdapter.OnClickListener {

    private static final int HOME_RECOMMEND_LIMIT = 15;
    private static final int HOME_RECOMMEND_SOURCE_LIMIT = 8;

    private ActivityHomeBinding mBinding;
    private ArrayObjectAdapter mHistoryAdapter;
    private ArrayObjectAdapter mFuncAdapter;
    private ArrayObjectAdapter mAdapter;
    private HistoryPresenter mPresenter;
    private SiteViewModel mViewModel;
    private Result mResult;
    private Clock mClock;
    private TypeAdapter mTypeAdapter;
    private View mOldView;
    private ServiceConnection mPlaybackServiceConnection;
    private PlaybackService mPlaybackService;
    private final BroadcastReceiver mNetworkReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateNetworkState();
        }
    };

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

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

    private final List<Vod> mHomeRecommends = new ArrayList<>();

    private void loadHomeRecommends() {
        List<Site> sites = getRecommendSites();
        mHomeRecommends.clear();
        for (Site site : sites) addCachedRecommends(mHomeRecommends, site);
        if (!mHomeRecommends.isEmpty()) setHomeBanner(mHomeRecommends);

        com.fongmi.android.tv.utils.Task.executor().submit(() -> {
            List<Vod> recommends = new ArrayList<>();
            for (Site site : sites) {
                try {
                    com.fongmi.android.tv.bean.Result result = com.fongmi.android.tv.api.SiteApi.homeContent(site);
                    if (result != null && result.getList() != null && !result.getList().isEmpty()) {
                        com.fongmi.android.tv.setting.Setting.putHomeRecommend(site.getKey(), result.toString());
                        addRecommends(recommends, site, result);
                    } else {
                        addCachedRecommends(recommends, site);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    addCachedRecommends(recommends, site);
                }
            }
            if (!recommends.isEmpty()) {
                mHomeRecommends.clear();
                mHomeRecommends.addAll(recommends);
                runOnUiThread(() -> setHomeBanner(mHomeRecommends));
            }
        });
    }

    private List<Site> getRecommendSites() {
        return Arrays.asList(
                createRecommendSite("iqiyi", "爱奇艺首页", "iqiyi.py"),
                createRecommendSite("tencent", "Tencent Video", "tencent.py")
        );
    }

    private Site createRecommendSite(String key, String name, String api) {
        Site site = new Site();
        site.setKey(key);
        site.setName(name);
        site.setApi(api);
        site.setType(3);
        return site;
    }

    private void addCachedRecommends(List<Vod> recommends, Site site) {
        try {
            String cache = com.fongmi.android.tv.setting.Setting.getHomeRecommend(site.getKey());
            if (cache.isEmpty()) cache = "iqiyi".equals(site.getKey()) ? com.fongmi.android.tv.setting.Setting.getIqiyiRecommends() : "";
            if (!cache.isEmpty()) addRecommends(recommends, site, com.fongmi.android.tv.bean.Result.fromJson(cache));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addRecommends(List<Vod> recommends, Site site, com.fongmi.android.tv.bean.Result result) {
        if (result == null || result.getList() == null) return;
        int count = 0;
        for (Vod vod : result.getList()) {
            if (recommends.size() >= HOME_RECOMMEND_LIMIT || count >= HOME_RECOMMEND_SOURCE_LIMIT) break;
            vod.setSite(site);
            recommends.add(vod);
            count++;
        }
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mClock = Clock.create(mBinding.clock).format("MM/dd E HH:mm");
        mBinding.progressLayout.showProgress();
        PermissionUtil.requestNotify(this);
        DLNARendererService.start(this);
        Updater.create().start(this);
        setRecyclerView();
        setTypeAdapter();
        setViewModel();
        setAdapter();
        // 先尝试从 savedInstanceState 恢复保存的接口数据（避免播放返回后数据丢失）
        restoreSavedState(savedInstanceState);
        if (com.fongmi.android.tv.setting.Setting.isSyncAutoSync()) {
            checkSync();
        } else {
            initConfig();
        }
        setTitle();
        setLogo();
        bindPlaybackService();
        loadHomeRecommends();
    }

    /**
     * 从 onSaveInstanceState 保存的数据中恢复首页结果与推荐海报
     * 用于：进入播放页面后系统回收HomeActivity，返回时直接恢复上次数据而不用等新的网络请求
     */
    private void restoreSavedState(Bundle savedInstanceState) {
        if (savedInstanceState == null) {
            mResult = Result.empty();
            return;
        }
        boolean restored = false;
        try {
            String savedResult = savedInstanceState.getString("saved_home_result");
            if (savedResult != null && !savedResult.isEmpty()) {
                Result cached = Result.fromJson(savedResult);
                if (cached != null && cached.getList() != null && !cached.getList().isEmpty()) {
                    addVideo(mResult = cached);
                    restored = true;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            String savedRecommends = savedInstanceState.getString("saved_home_recommends");
            if (savedRecommends != null && !savedRecommends.isEmpty()) {
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<java.util.List<Vod>>() {}.getType();
                java.util.List<Vod> list = com.fongmi.android.tv.App.gson().fromJson(savedRecommends, type);
                if (list != null && !list.isEmpty()) {
                    mHomeRecommends.clear();
                    mHomeRecommends.addAll(list);
                    setHomeBanner(mHomeRecommends);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (!restored) mResult = Result.empty();
    }

    private boolean mSyncDone;

    private void checkSync() {
        mSyncDone = false;
        Notify.show(R.string.sync_syncing);
        com.fongmi.android.tv.utils.WebDavSync.download(new Callback() {
            @Override
            public void success() {
                if (mSyncDone) return;
                mSyncDone = true;
                Notify.show(R.string.sync_success);
                initConfig();
            }

            @Override
            public void error() {
                if (mSyncDone) return;
                mSyncDone = true;
                Notify.show(R.string.sync_fail);
                initConfig();
            }
        });
        App.post(() -> {
            if (mSyncDone) return;
            mSyncDone = true;
            Notify.show(R.string.sync_fail);
            initConfig();
        }, 3000);
    }

    @Override
    protected void initEvent() {
        mBinding.title.setListener(this);
        mBinding.search.setOnClickListener(view -> SearchActivity.start(this));
        mBinding.keep.setOnClickListener(view -> KeepActivity.start(this));
        mBinding.live.setOnClickListener(view -> LiveActivity.start(this));
        mBinding.push.setOnClickListener(view -> PushActivity.start(this));
        if (mBinding.history != null) mBinding.history.setOnClickListener(view -> HistoryActivity.start(this));
        mBinding.setting.setOnClickListener(view -> SettingActivity.start(this));
        mBinding.net.setOnClickListener(view -> {
            try {
                startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS));
            } catch (Exception e) {
                try {
                    startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS));
                } catch (Exception e2) {
                    try {
                        startActivity(new Intent(Settings.ACTION_SETTINGS));
                    } catch (Exception e3) {
                        // ignore
                    }
                }
            }
        });
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                boolean isTop = (position == 0);
                mBinding.toolbar.setVisibility(isTop ? View.VISIBLE : View.GONE);
                mBinding.recyclerType.setVisibility((isTop && mTypeAdapter != null && mTypeAdapter.getItemCount() > 0) ? View.VISIBLE : View.GONE);
                if (mPresenter.isDelete()) setHistoryDelete(false);
            }
        });
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mBinding.recyclerType.setSelectedPosition(position + 1);
                mBinding.recyclerType.requestFocus();
            }
        });
        mBinding.recyclerType.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                onChildSelected(child);
            }
        });
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

    @SuppressLint("RestrictedApi")
    private void setRecyclerView() {
        CustomSelector selector = new CustomSelector();
        selector.addPresenter(HomeBanner.class, new HomeBannerPresenter(this));
        selector.addPresenter(Integer.class, new HeaderPresenter());
        selector.addPresenter(String.class, new ProgressPresenter());
        selector.addPresenter(Vod.class, new VodPresenter(this, Style.list()));
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), VodPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), FuncPresenter.class);
        selector.addPresenter(ListRow.class, new CustomRowPresenter(16), HistoryPresenter.class);
        mBinding.recycler.setAdapter(new ItemBridgeAdapter(mAdapter = new ArrayObjectAdapter(selector)));
        mBinding.recycler.setVerticalSpacing(ResUtil.dp2px(16));
     }

    private void setTypeAdapter() {
        mBinding.recyclerType.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.recyclerType.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.recyclerType.setAdapter(mTypeAdapter = new TypeAdapter(this));

        List<Class> items = new ArrayList<>();
        Class home = new Class();
        home.setTypeName(ResUtil.getString(R.string.vod_home));
        home.setTypeId("home");
        items.add(home);
        mTypeAdapter.addAll(items);
        mBinding.recyclerType.setVisibility(View.VISIBLE);
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(this, result -> {
            mAdapter.remove("progress");
            boolean hasValidResult = result != null && result.getList() != null && !result.getList().isEmpty();
            boolean hasValidSavedData = mResult != null && mResult.getList() != null && !mResult.getList().isEmpty();

            if (hasValidResult) {
                int index = getRecommendIndex();
                if (mAdapter.size() > index) {
                    mAdapter.removeItems(index, mAdapter.size() - index);
                }
                addVideo(mResult = result);
                Cache.clear().put(result);
                setTypes(result.getTypes());

                Result cacheResult = new Result();
                cacheResult.setList(result.getList());
                com.fongmi.android.tv.setting.Setting.putHomeRecommend(getHome().getKey(), cacheResult.toString());
            } else if (!hasValidSavedData) {
                // 既无新数据也无已保存的 savedInstanceState 数据，至少尝试设置分类
                if (result != null) {
                    setTypes(result.getTypes());
                }
            }
            // else: result 为空但 mResult 有 savedInstanceState 恢复的有效数据，保留现有数据，仅移除 progress
        });
    }

    private void setAdapter() {
        mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        setHomeBanner(new ArrayList<>());
        mAdapter.add(R.string.home_recommend);
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
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
                showContent();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
                showContent();
            }
        };
    }

    private void showContent() {
        mBinding.progressLayout.showContent();
        checkAction(getIntent());
        setFocus();
    }

    private void loadLive(String url) {
        LiveConfig.load(Config.find(url, 1), new Callback() {
            @Override
            public void success() {
                LiveActivity.start(getActivity());
            }
        });
    }

    private void setFocus() {
        mBinding.title.setSelected(true);
        App.post(() -> mBinding.title.setFocusable(true), 500);
        if (!mBinding.title.hasFocus()) {
            App.post(() -> {
                RecyclerView.ViewHolder holder = mBinding.recycler.findViewHolderForAdapterPosition(0);
                if (holder instanceof ItemBridgeAdapter.ViewHolder) {
                    View middleCard = ((ItemBridgeAdapter.ViewHolder) holder).itemView.findViewById(R.id.middleCard);
                    if (middleCard != null && middleCard.isFocusable()) {
                        middleCard.requestFocus();
                        return;
                    }
                }
                mBinding.recycler.requestFocus();
            }, 200);
        }
    }

    private void getVideo() {
        mResult = Result.empty();
        int index = getRecommendIndex();
        boolean gone = mAdapter.indexOf("progress") == -1;
        boolean hasItem = gone && mAdapter.size() > index;
        if (hasItem) mAdapter.removeItems(index, mAdapter.size() - index);

        if (mTypeAdapter != null) {
            List<Class> items = new ArrayList<>();
            Class home = new Class();
            home.setTypeName(ResUtil.getString(R.string.vod_home));
            home.setTypeId("home");
            items.add(home);
            mTypeAdapter.addAll(items);
            mBinding.recyclerType.setVisibility(View.VISIBLE);
        }

        String cache = com.fongmi.android.tv.setting.Setting.getHomeRecommend(getHome().getKey());
        if (!cache.isEmpty()) {
            try {
                Result cachedResult = Result.fromJson(cache);
                if (cachedResult != null && cachedResult.getList() != null && !cachedResult.getList().isEmpty()) {
                    addVideo(mResult = cachedResult);
                    gone = false;
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (gone) mAdapter.add("progress");
        mViewModel.homeContent();
    }

    private void addVideo(Result result) {
        List<Vod> list = result.getList();
        List<Vod> gridList = new ArrayList<>(list);
        
        setHomeBanner(mHomeRecommends);

        Style style = result.getStyle(getHome().getStyle());
        if (style.isList()) mAdapter.addAll(mAdapter.size(), gridList);
        else addGrid(gridList, style);
    }

    private void addGrid(List<Vod> items, Style style) {
        List<ListRow> rows = new ArrayList<>();
        VodPresenter presenter = new VodPresenter(this, style);
        for (List<Vod> part : Lists.partition(items, Product.getColumn(style))) {
            ArrayObjectAdapter adapter = new ArrayObjectAdapter(presenter);
            adapter.addAll(0, part);
            rows.add(new ListRow(adapter));
        }
        mAdapter.addAll(mAdapter.size(), rows);
    }

    private void setFunc() {
        setHomeBanner(mHomeRecommends);
    }

    private void getHistory() {
        getHistory(false);
    }

    private void getHistory(boolean renew) {
        List<History> items = History.get();
        int historyIndex = getHistoryIndex();
        boolean exist = historyIndex != -1;
        if (renew) mHistoryAdapter = new ArrayObjectAdapter(mPresenter = new HistoryPresenter(this));
        if (items.isEmpty() && exist) {
            removeHistory(historyIndex);
        } else if (!items.isEmpty()) {
            if (!exist) {
                mAdapter.add(1, R.string.home_history);
                mAdapter.add(2, new ListRow(mHistoryAdapter));
            } else if (renew) {
                removeHistory(historyIndex);
                mAdapter.add(historyIndex, R.string.home_history);
                mAdapter.add(historyIndex + 1, new ListRow(mHistoryAdapter));
            }
        }
        mHistoryAdapter.setItems(items, new BaseDiffCallback<History>());
    }

    private void setHistoryDelete(boolean delete) {
        mPresenter.setDelete(delete);
        mHistoryAdapter.notifyArrayItemRangeChanged(0, mHistoryAdapter.size());
    }

    private void clearHistory() {
        removeHistory(getHistoryIndex());
        History.delete(VodConfig.getCid());
        mPresenter.setDelete(false);
        mHistoryAdapter.clear();
        com.fongmi.android.tv.utils.WebDavSync.upload(null);
    }

    private int getHistoryIndex() {
        return mAdapter.indexOf(R.string.home_history);
    }

    private void removeHistory(int index) {
        if (index == -1) return;
        int count = mAdapter.size() > index + 1 && mAdapter.get(index + 1) instanceof ListRow ? 2 : 1;
        mAdapter.removeItems(index, count);
    }

    private int getRecommendIndex() {
        return mAdapter.indexOf(R.string.home_recommend) + 1;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        switch (event.type()) {
            case VOD:
                RefreshEvent.history();
                RefreshEvent.home();
                setLogo();
                break;
            case COMMON:
                setFunc();
                break;
            case BOOT:
                LiveActivity.start(this);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        switch (event.getType()) {
            case HOME:
                getVideo();
                setTitle();
                break;
            case HISTORY:
                getHistory();
                break;
            case SIZE:
                getVideo();
                getHistory(true);
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        switch (event.type()) {
            case SEARCH:
                SearchActivity.start(this, event.text());
                break;
            case PUSH:
                VideoActivity.push(this, event.text());
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (VodConfig.get().getConfig().equals(event.config())) {
            VideoActivity.cast(this, event.history().save(VodConfig.getCid()));
        } else {
            VodConfig.load(event.config(), getCallback(event));
        }
    }

    private Callback getCallback(CastEvent event) {
        return new Callback() {
            @Override
            public void success() {
                onCastEvent(event);
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    @Override
    public void onItemClick(Func item) {
        if (item.getResId() == R.string.home_vod) VodActivity.start(this, mResult);
        else if (item.getResId() == R.string.home_live) LiveActivity.start(this);
        else if (item.getResId() == R.string.home_keep) KeepActivity.start(this);
        else if (item.getResId() == R.string.home_push) PushActivity.start(this);
        else if (item.getResId() == R.string.home_search) SearchActivity.start(this);
        else if (item.getResId() == R.string.home_setting) SettingActivity.start(this);
    }

    @Override
    public void onItemClick(Vod item) {
        if (item.isAction()) mViewModel.action(getHome().getKey(), item.getAction());
        else if (getHome().isIndex()) CollectActivity.start(this, item.getName());
        else {
            String siteKey = TextUtils.isEmpty(item.getSiteKey()) ? getHome().getKey() : item.getSiteKey();
            if ("iqiyi".equals(siteKey) || "tencent".equals(siteKey)) {
                SearchActivity.start(this, item.getName());
            } else {
                VideoActivity.start(this, siteKey, item.getId(), item.getName(), item.getPic());
            }
        }
    }

    @Override
    public boolean onLongClick(Vod item) {
        if (item.isAction()) return false;
        CollectActivity.start(this, item.getName());
        return true;
    }

    @Override
    public void onItemClick(History item) {
        VideoActivity.start(this, item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
    }

    @Override
    public void onItemDelete(History item) {
        mHistoryAdapter.remove(item.delete());
        if (mHistoryAdapter.size() > 0) return;
        removeHistory(getHistoryIndex());
        mPresenter.setDelete(false);
        com.fongmi.android.tv.utils.WebDavSync.upload(null);
    }

    @Override
    public boolean onLongClick() {
        if (mPresenter.isDelete()) clearHistory();
        else setHistoryDelete(true);
        return true;
    }

    @Override
    public void showDialog() {
        SiteDialog.create().show(this);
    }

    @Override
    public void onRefresh() {
        getVideo();
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    private void updateNetworkState() {
        int state = Util.getNetworkState();
        if (state == 1) {
            mBinding.net.setImageResource(R.drawable.ic_net_wifi);
            mBinding.net.setVisibility(View.VISIBLE);
        } else if (state == 2) {
            mBinding.net.setImageResource(R.drawable.ic_net_ethernet);
            mBinding.net.setVisibility(View.VISIBLE);
        } else {
            mBinding.net.setImageResource(R.drawable.ic_net_disconnected);
            mBinding.net.setVisibility(View.VISIBLE);
        }
    }

    private boolean isToolbarFocused() {
        View focus = getCurrentFocus();
        return focus == mBinding.title || focus == mBinding.search || focus == mBinding.keep || focus == mBinding.live || focus == mBinding.push || focus == mBinding.history || focus == mBinding.setting || focus == mBinding.net;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (KeyUtil.isMenuKey(event)) showDialog();
        if (KeyUtil.isActionDown(event) && KeyUtil.isDownKey(event) && isToolbarFocused()) {
            if (mBinding.recyclerType.getVisibility() == View.VISIBLE) {
                return mBinding.recyclerType.requestFocus();
            } else if (mBinding.recycler.getChildCount() > 0) {
                return mBinding.recycler.getChildAt(0).requestFocus();
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mClock.start();
        registerReceiver(mNetworkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        updateNetworkState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mClock.stop();
        unregisterReceiver(mNetworkReceiver);
    }

    private FolderFragment getFragment() {
        if (mBinding.pager.getAdapter() == null || mBinding.pager.getAdapter().getCount() == 0) return null;
        return (FolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, mBinding.pager.getCurrentItem());
    }

    @Override
    protected void onBackInvoked() {
        FolderFragment folder = getFragment();
        if (mBinding.progressLayout.isProgress()) {
            showContent();
        } else if (mPresenter.isDelete()) {
            setHistoryDelete(false);
        } else if (mBinding.pager.getVisibility() == View.VISIBLE && folder != null && folder.canBack()) {
            folder.goBack();
        } else if (mBinding.recyclerType.getVisibility() == View.VISIBLE && mBinding.recyclerType.getSelectedPosition() != 0) {
            mBinding.recyclerType.setSelectedPosition(0);
            onCategoryClick(0);
        } else if (mBinding.recycler.getVisibility() == View.VISIBLE && mBinding.recycler.getSelectedPosition() != 0) {
            mBinding.recycler.scrollToPosition(0);
        } else {
            exitApp();
        }
    }

    private void exitApp() {
        if (mPlaybackService != null) mPlaybackService.shutdown();
        else stopService(new Intent(this, PlaybackService.class));
        super.onBackInvoked();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // 保存首页API数据与推荐海报，防止系统回收Activity后返回丢失
        try {
            if (mResult != null && mResult.getList() != null && !mResult.getList().isEmpty()) {
                outState.putString("saved_home_result", mResult.toString());
            }
            if (mHomeRecommends != null && !mHomeRecommends.isEmpty()) {
                outState.putString("saved_home_recommends", com.fongmi.android.tv.App.gson().toJson(mHomeRecommends, new com.google.gson.reflect.TypeToken<java.util.List<Vod>>() {}.getType()));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onDestroy() {
        unbindPlaybackService();
        DLNARendererService.stop(this);
        // 仅在用户真正退出App时才清空单例配置与数据源
        // 否则系统回收Activity（如播放视频时内存不足）后返回首页，VodConfig/LiveConfig为空会导致接口无数据
        if (isFinishing() || isChangingConfigurations()) {
            LiveConfig.get().clear();
            VodConfig.get().clear();
            OkHttp.get().clear();
            Source.get().exit();
            Server.get().stop();
        }
        AppDatabase.backup();
        super.onDestroy();
    }

    private void setHomeBanner(List<Vod> recommends) {
        mBinding.live.setVisibility(LiveConfig.hasUrl() ? View.VISIBLE : View.GONE);

        HomeBanner banner = new HomeBanner(new ArrayList<>(), recommends);
        if (mAdapter.size() > 0 && mAdapter.get(0) instanceof HomeBanner) {
            mAdapter.replace(0, banner);
        } else {
            mAdapter.add(0, banner);
        }
    }

    private void bindPlaybackService() {
        bindService(new Intent(this, PlaybackService.class).setAction(PlaybackService.LOCAL_BIND_ACTION), mPlaybackServiceConnection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder binder) {
                mPlaybackService = ((PlaybackService.LocalBinder) binder).getService();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                mPlaybackService = null;
            }
        }, BIND_AUTO_CREATE);
    }

    private void unbindPlaybackService() {
        if (mPlaybackServiceConnection != null) {
            unbindService(mPlaybackServiceConnection);
            mPlaybackServiceConnection = null;
            mPlaybackService = null;
        }
    }

    private void setTypes(List<Class> types) {
        if (types.isEmpty()) {
            mBinding.recyclerType.setVisibility(View.GONE);
            mBinding.pager.setVisibility(View.GONE);
            mBinding.progressLayout.setVisibility(View.VISIBLE);
            return;
        }

        List<Class> items = new ArrayList<>();
        Class home = new Class();
        home.setTypeName(ResUtil.getString(R.string.vod_home));
        home.setTypeId("home");
        items.add(home);
        items.addAll(types);

        mTypeAdapter.addAll(items);
        mBinding.recyclerType.setVisibility(View.VISIBLE);
        mBinding.pager.setAdapter(new PageAdapter(getSupportFragmentManager(), types));

        onCategoryClick(0);
    }

    private void onCategoryClick(int position) {
        if (position == 0) {
            mBinding.progressLayout.setVisibility(View.VISIBLE);
            mBinding.pager.setVisibility(View.GONE);
        } else {
            mBinding.progressLayout.setVisibility(View.GONE);
            mBinding.pager.setVisibility(View.VISIBLE);
            mBinding.pager.setCurrentItem(position - 1, false);
        }
    }

    private void updateFilter(Class item) {
        if (Cache.get(item).isEmpty()) return;
        item.setFilter(!item.getFilter());
        getFragment().toggleFilter(item.getFilter());
        mTypeAdapter.notifyItemChanged(mTypeAdapter.indexOf(item));
    }

    private void onChildSelected(@Nullable RecyclerView.ViewHolder child) {
        if (mOldView != null) mOldView.setSelected(false);
        if ((mOldView = child != null ? child.itemView : null) == null) return;
        mOldView.setSelected(true);
        App.post(mRunnable, 100);
    }

    private final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            int position = mBinding.recyclerType.getSelectedPosition();
            onCategoryClick(position);
        }
    };

    @Override
    public void onItemClick(Class item) {
        int position = mTypeAdapter.indexOf(item);
        onCategoryClick(position);
        if (position > 0) updateFilter(item);
    }

    @Override
    public void onRefresh(Class item) {
        if (mBinding.pager.getAdapter() != null) {
            int pagePos = mTypeAdapter.indexOf(item) - 1;
            if (pagePos >= 0 && pagePos < mBinding.pager.getAdapter().getCount()) {
                FolderFragment fragment = (FolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, pagePos);
                fragment.onRefresh();
            }
        }
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        private final List<Class> mTypes;

        public PageAdapter(@NonNull FragmentManager fm, List<Class> types) {
            super(fm);
            this.mTypes = types;
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            Class type = mTypes.get(position);
            return FolderFragment.newInstance(getHome().getKey(), type);
        }

        @Override
        public int getCount() {
            return mTypes.size();
        }
    }
}

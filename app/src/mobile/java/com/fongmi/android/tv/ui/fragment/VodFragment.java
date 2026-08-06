package com.fongmi.android.tv.ui.fragment;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;
import androidx.viewpager2.widget.ViewPager2;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.bean.Value;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.AdapterBannerItemBinding;
import com.fongmi.android.tv.databinding.AdapterHistoryHorizontalBinding;
import com.fongmi.android.tv.databinding.FragmentVodBinding;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.StateEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.FilterListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.DownloadActivity;
import com.fongmi.android.tv.ui.activity.HistoryActivity;
import com.fongmi.android.tv.ui.activity.KeepActivity;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.activity.VideoActivity;
import com.fongmi.android.tv.ui.adapter.TypeAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.FilterDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.LinkDialog;
import com.fongmi.android.tv.ui.dialog.ReceiveDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
import com.fongmi.android.tv.utils.ImgUtil;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.appbar.AppBarLayout;
import android.widget.LinearLayout;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public class VodFragment extends BaseFragment implements ConfigListener, SiteListener, FilterListener, TypeAdapter.OnClickListener {

    private static final int HOME_RECOMMEND_LIMIT = 15;
    private static final int HOME_RECOMMEND_SOURCE_LIMIT = 8;

    private FragmentVodBinding mBinding;
    private SiteViewModel mViewModel;
    private TypeAdapter mAdapter;
    private Result mResult;
    private final List<Vod> mHomeRecommends = new ArrayList<>();
    private BannerAdapter mBannerAdapter;
    private HistoryHorizontalAdapter mHistoryAdapter;
    private boolean mAppBarCollapsed = false;
    private int mAppBarOffset = 0;
    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isViewReady() || mBannerAdapter == null) return;
            int count = mBannerAdapter.getItemCount();
            if (count > 1) {
                int current = mBinding.banner.getCurrentItem();
                int next = (current + 1) % count;
                mBinding.banner.setCurrentItem(next, true);
                mHandler.postDelayed(this, 5000);
            }
        }
    };

    public static VodFragment newInstance() {
        return new VodFragment();
    }

    private FolderFragment getFragment() {
        return (FolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, mBinding.pager.getCurrentItem());
    }

    private Site getHome() {
        return VodConfig.get().getHome();
    }

    private Config getConfig() {
        return VodConfig.get().getConfig();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentVodBinding.inflate(inflater, container, false);
    }

    private boolean isViewReady() {
        return isAdded() && mBinding != null && getContext() != null;
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        mBinding.appBar.setBackground(null);
        mBinding.collapsingToolbar.setBackground(null);
        mBinding.title.setSelected(true);
        setRecyclerView();
        mBinding.banner.setAdapter(mBannerAdapter = new BannerAdapter(mHomeRecommends));
        mBinding.recentRecycler.setAdapter(mHistoryAdapter = new HistoryHorizontalAdapter());
        setViewModel();
        showProgress();
        setTitle();
        setLogo();
        applyHomeCarousel();
        loadHomeRecommends();
        loadHistory();
    }

    @Override
    protected void initEvent() {
        mBinding.top.setOnClickListener(this::onTop);
        mBinding.logo.setOnClickListener(this::onLogo);
        mBinding.logo.setOnLongClickListener(this::onLogoLongPress);
        mBinding.link.setOnClickListener(this::onLink);
        mBinding.title.setOnClickListener(this::onSite);
        mBinding.filter.setOnClickListener(this::onFilter);
        mBinding.filter.setOnLongClickListener(this::onLink);
        mBinding.searchBar.setOnClickListener(v -> SearchActivity.start(requireActivity()));
        mBinding.keep.setOnClickListener(v -> KeepActivity.start(requireActivity()));
        mBinding.history.setOnClickListener(v -> HistoryActivity.start(requireActivity()));
        
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> {
            int top = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
            int bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            mBinding.topBar.setPadding(
                mBinding.topBar.getPaddingLeft(),
                ResUtil.dp2px(12) + top,
                mBinding.topBar.getPaddingRight(),
                mBinding.topBar.getPaddingBottom()
            );
            mBinding.topBar.post(() -> {
                if (isViewReady()) {
                    updateCollapsingSize();
                    updateTypePinnedMargin();
                    applyHomeCarousel();
                }
            });
            boolean capsule = com.fongmi.android.tv.setting.Setting.isHomeCapsule();
            int margin = ResUtil.dp2px(16);
            if (capsule) {
                margin += bottom + ResUtil.dp2px(72);
            }
            setFabMargin(mBinding.filter, margin);
            setFabMargin(mBinding.link, margin);
            setFabMargin(mBinding.top, margin);
            return insets;
        });
        View bannerRecycler = mBinding.banner.getChildAt(0);
        if (bannerRecycler != null) {
            bannerRecycler.setNestedScrollingEnabled(true);
        }
        
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (!isViewReady()) return;
                mBinding.type.smoothScrollToPosition(position);
                mAdapter.setSelected(position);
                setFabVisible(position);
            }
        });
        mBinding.banner.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                if (!isViewReady()) return;
                updateBannerDots(position);
            }

            @Override
            public void onPageScrollStateChanged(int state) {
                if (!isViewReady()) return;
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    stopAutoScroll();
                } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    startAutoScroll();
                }
            }
        });

        mBinding.appBar.addOnOffsetChangedListener((appBarLayout, verticalOffset) -> {
            if (!isViewReady()) return;
            mAppBarOffset = verticalOffset;
            int totalRange = appBarLayout.getTotalScrollRange();
            if (totalRange > 0) {
                updateHomeHeaderVisibility(verticalOffset);
                updateTypePinnedMargin(verticalOffset, totalRange);
            }
            if (verticalOffset < 0 && !mAppBarCollapsed) {
                mAppBarCollapsed = true;
                com.fongmi.android.tv.event.ScrollEvent.post(100);
            } else if (verticalOffset == 0 && mAppBarCollapsed) {
                mAppBarCollapsed = false;
                com.fongmi.android.tv.event.ScrollEvent.post(-100);
            }
        });
    }

    private void updateHomeHeaderVisibility(int verticalOffset) {
        if (!PlayerSetting.isHomeCarousel()) {
            mBinding.bannerContainer.setAlpha(0f);
            mBinding.bannerShadow.setAlpha(0f);
            mBinding.recentLayout.setAlpha(0f);
            return;
        }
        // Only use alpha for fading — never change visibility during scroll
        // to avoid AppBarLayout layout recalculation that causes gaps/jumps
        boolean hasRecent = Setting.isHomeHistory() && mHistoryAdapter != null && mHistoryAdapter.getItemCount() > 0;
        int bannerRange = Math.max(1, mBinding.collapsingToolbar.getHeight() - mBinding.collapsingToolbar.getMinimumHeight());
        float progress = Math.min(1f, Math.max(0f, -verticalOffset / (float) bannerRange));
        float alpha = 1f - progress;
        mBinding.bannerContainer.setAlpha(alpha);
        mBinding.bannerShadow.setAlpha(alpha);
        mBinding.recentLayout.setAlpha(hasRecent ? alpha : 0f);
    }

    private void updateRecentVisibility() {
        boolean show = PlayerSetting.isHomeCarousel() && Setting.isHomeHistory() && mHistoryAdapter != null && mHistoryAdapter.getItemCount() > 0;
        mBinding.recentLayout.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) mBinding.recentLayout.setAlpha(1f - Math.min(1f, Math.max(0f, -mAppBarOffset / (float) Math.max(1, mBinding.collapsingToolbar.getHeight() - mBinding.collapsingToolbar.getMinimumHeight()))));
    }

    private void applyHomeCarousel() {
        if (!isViewReady()) return;
        boolean show = PlayerSetting.isHomeCarousel();
        updateCollapsingSize();
        // Keep CollapsingToolbarLayout always visible for the pinned header effect
        mBinding.collapsingToolbar.setVisibility(View.VISIBLE);
        mBinding.bannerContainer.setVisibility(show ? View.VISIBLE : View.GONE);
        mBinding.bannerShadow.setVisibility(show ? View.VISIBLE : View.GONE);
        mBinding.bannerContainer.setAlpha(show ? 1f : 0f);
        mBinding.bannerShadow.setAlpha(show ? 1f : 0f);
        if (!show) mBinding.recentLayout.setVisibility(View.GONE);
        updateHomeHeaderVisibility(mAppBarOffset);
        updateTypePinnedMargin();
        if (show) startAutoScroll();
        else stopAutoScroll();
    }

    private void updateCollapsingSize() {
        com.google.android.material.appbar.AppBarLayout.LayoutParams params =
                (com.google.android.material.appbar.AppBarLayout.LayoutParams) mBinding.collapsingToolbar.getLayoutParams();
        boolean carousel = PlayerSetting.isHomeCarousel();
        int targetHeight = carousel ? ResUtil.dp2px(240) : 1;
        if (params.height != targetHeight) {
            params.height = targetHeight;
            mBinding.collapsingToolbar.setLayoutParams(params);
        }
    }

    private void updateTypePinnedMargin() {
        if (mBinding.contentLayout == null || mBinding.topBar == null) return;
        int topBarHeight = mBinding.topBar.getHeight();
        boolean carousel = PlayerSetting.isHomeCarousel();
        float translation = carousel ? 0f : topBarHeight;
        if (mBinding.contentLayout.getTranslationY() != translation) {
            mBinding.contentLayout.setTranslationY(translation);
        }
        // 补偿translationY导致的底部裁剪：用paddingBottom缩小内容区域
        int padBottom = Math.round(translation);
        if (mBinding.contentLayout.getPaddingBottom() != padBottom) {
            mBinding.contentLayout.setPadding(
                mBinding.contentLayout.getPaddingLeft(),
                mBinding.contentLayout.getPaddingTop(),
                mBinding.contentLayout.getPaddingRight(),
                padBottom
            );
        }
        // 当topBar还未测量时(topBarHeight<=0)，延迟重试
        if (topBarHeight <= 0) {
            mBinding.topBar.postDelayed(this::updateTypePinnedMargin, 50);
        }
    }

    private void updateTypePinnedMargin(int verticalOffset, int totalRange) {
        if (mBinding.contentLayout == null || mBinding.topBar == null) return;
        int topBarHeight = mBinding.topBar.getHeight();
        if (topBarHeight <= 0) return;
        boolean carousel = PlayerSetting.isHomeCarousel();
        float translation;
        if (carousel) {
            if (totalRange <= 0) return;
            float progress = Math.min(1f, Math.max(0f, -verticalOffset / (float) totalRange));
            translation = topBarHeight * progress;
        } else {
            // 关闭轮播时，不论totalRange如何，始终将分类标签推到topBar下方
            translation = topBarHeight;
        }
        if (mBinding.contentLayout.getTranslationY() != translation) {
            mBinding.contentLayout.setTranslationY(translation);
        }
        int padBottom = Math.round(translation);
        if (mBinding.contentLayout.getPaddingBottom() != padBottom) {
            mBinding.contentLayout.setPadding(
                mBinding.contentLayout.getPaddingLeft(),
                mBinding.contentLayout.getPaddingTop(),
                mBinding.contentLayout.getPaddingRight(),
                padBottom
            );
        }
    }

    private void setRecyclerView() {
        mBinding.type.setHasFixedSize(true);
        mBinding.type.setItemAnimator(null);
        mBinding.type.setAdapter(mAdapter = new TypeAdapter(this));
        mBinding.type.post(() -> {
            if (isViewReady()) applyHomeCarousel();
        });
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getResult().observe(getViewLifecycleOwner(), this::setAdapter);
    }

    private void setAdapter(Result result) {
        if (!isViewReady()) return;
        if (result != null && result.getList() != null && !result.getList().isEmpty()) {
            Result cacheResult = new Result();
            cacheResult.setList(result.getList());
            com.fongmi.android.tv.setting.Setting.putHomeRecommend(getHome().getKey(), cacheResult.toString());
        }
        mResult = result;
        mAdapter.setItems(result);
        // Always create a new PageAdapter instead of notifyDataSetChanged().
        // FragmentStatePagerAdapter does not reliably create new fragments
        // when item count changes (especially 0 → N), causing blank screens
        // after config refresh.
        mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
        if (mAdapter.getItemCount() > 0) {
            Fragment fragment = (Fragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, 0);
            if (fragment instanceof FolderFragment) {
                ((FolderFragment) fragment).setResult(result);
            }
        }
        setFabVisible(0);
        hideProgress();
        showContent();
    }

    private void setFabVisible(int position) {
        if (mAdapter.getItemCount() == 0) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.VISIBLE);
            mBinding.filter.setVisibility(View.GONE);
        } else if (!mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.link.setVisibility(View.GONE);
            mBinding.filter.show();
        } else if (position == 0 || mAdapter.get(position).getFilters().isEmpty()) {
            mBinding.top.setVisibility(View.INVISIBLE);
            mBinding.filter.setVisibility(View.GONE);
            mBinding.link.show();
        }
    }

    private void setTitle() {
        List<String> items = Arrays.asList(getHome().getName(), getConfig().getName(), getString(R.string.app_name));
        Optional<String> optional = items.stream().filter(s -> !TextUtils.isEmpty(s)).findFirst();
        optional.ifPresent(s -> mBinding.title.setText(s));
    }

    private void onTop(View view) {
        getFragment().scrollToTop();
        mBinding.top.setVisibility(View.INVISIBLE);
        if (mBinding.filter.getVisibility() == View.INVISIBLE) mBinding.filter.show();
        else if (mBinding.link.getVisibility() == View.INVISIBLE) mBinding.link.show();
    }

    private boolean onLink(View view) {
        LinkDialog.show(this);
        return true;
    }

    private void onLogo(View view) {
        HistoryDialog.create().vod().readOnly().show(this);
    }

    private boolean onLogoLongPress(View view) {
        VodConfig.load(getConfig(), new Callback() {
            @Override
            public void start() {
                if (!isViewReady()) return;
                showProgress();
                hideContent();
            }

            @Override
            public void success() {
                if (!isViewReady()) return;
                // Don't call homeContent() here — ConfigEvent.VOD will trigger
                // RefreshEvent.home() → homeContent() automatically.
                // Calling it twice causes ViewPager/FragmentManager corruption.
                loadHomeRecommends();
            }

            @Override
            public void error(String msg) {
                if (!isViewReady()) return;
                Notify.show(msg);
                showContent();
            }
        });
        return true;
    }

    private void onSite(View view) {
        SiteDialog.create().change().show(this);
    }

    private void onFilter(View view) {
        if (mAdapter.getItemCount() > 0) FilterDialog.create().filter(mAdapter.get(mBinding.pager.getCurrentItem()).getFilters()).show(this);
    }

    private boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.keep) KeepActivity.start(requireActivity());
        else if (item.getItemId() == R.id.search) SearchActivity.start(requireActivity());
        else if (item.getItemId() == R.id.history) HistoryActivity.start(requireActivity());
        else if (item.getItemId() == R.id.download) DownloadActivity.start(requireActivity());
        return true;
    }

    private void showProgress() {
        mBinding.progress.getRoot().setVisibility(View.VISIBLE);
    }

    private void hideProgress() {
        mBinding.progress.getRoot().setVisibility(View.GONE);
    }

    private void hideContent() {
        mBinding.type.setVisibility(View.INVISIBLE);
        mBinding.pager.setVisibility(View.INVISIBLE);
    }

    private void showContent() {
        mBinding.type.setVisibility(View.VISIBLE);
        mBinding.pager.setVisibility(View.VISIBLE);
    }

    private void homeContent() {
        showProgress();
        setFabVisible(0);
        // Detach old PageAdapter BEFORE clearing mAdapter data.
        // PageAdapter.getCount() reads from mAdapter.getItemCount().
        // If we clear mAdapter while the old adapter is still attached,
        // ViewPager.onMeasure() sees expected N items but adapter returns 0,
        // causing "IllegalStateException: PagerAdapter changed contents
        // without calling notifyDataSetChanged".
        mBinding.pager.setAdapter(null);
        mAdapter.clear();
        String cache = com.fongmi.android.tv.setting.Setting.getHomeRecommend(getHome().getKey());
        if (!cache.isEmpty()) {
            try {
                Result cachedResult = Result.fromJson(cache);
                if (cachedResult != null && cachedResult.getList() != null && !cachedResult.getList().isEmpty()) {
                    Result tempResult = new Result();
                    tempResult.setList(cachedResult.getList());
                    mAdapter.addAll(mResult = tempResult);
                    // 有缓存时先显示内容，但保持进度条直到网络请求完成
                    mBinding.pager.setAdapter(new PageAdapter(getChildFragmentManager()));
                    showContent();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        mViewModel.homeContent();
    }

    public Result getResult() {
        return mResult == null ? new Result() : mResult;
    }

    private void setLogo() {
        ImgUtil.logo(mBinding.logo);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (!isViewReady()) return;
        if (event.type() == ConfigEvent.Type.VOD) setLogo();
        if (event.type() == ConfigEvent.Type.COMMON) loadHistory();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onRefreshEvent(RefreshEvent event) {
        if (!isViewReady()) return;
        switch (event.getType()) {
            case HOME:
                setTitle();
            case SIZE:
                // Defer homeContent() to the next main thread message cycle.
                // When triggered from config reload (e.g. long-press logo),
                // EventBus delivers RefreshEvent.HOME synchronously inside
                // ConfigEvent.VOD dispatch. Calling setAdapter() inside that
                // synchronous chain causes "FragmentManager is already
                // executing transactions" crash because commitNowAllowingStateLoss()
                // cannot run while FragmentManager is mid-transaction.
                App.post(() -> { if (isViewReady()) homeContent(); });
                break;
            case CATEGORY:
                getFragment().onRefresh();
                break;
            case HISTORY:
                loadHistory();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onStateEvent(StateEvent event) {
        if (!isViewReady()) return;
        switch (event.type()) {
            case EMPTY:
                hideProgress();
                break;
            case PROGRESS:
                showProgress();
                break;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onCastEvent(CastEvent event) {
        if (!isViewReady()) return;
        ReceiveDialog.create().event(event).show(this);
    }

    @Override
    public void setConfig(Config config) {
        VodConfig.load(config, new Callback() {
            @Override
            public void start() {
                if (!isViewReady()) return;
                showProgress();
                hideContent();
                setTitle();
                setLogo();
            }

            @Override
            public void error(String msg) {
                if (!isViewReady()) return;
                Notify.dismiss();
                Notify.show(msg);
                showContent();
            }
        });
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    @Override
    public void onItemClick(int position, Class item) {
        mBinding.pager.setCurrentItem(position);
        mAdapter.setSelected(position);
    }

    @Override
    public void setFilter(String key, Value value) {
        getFragment().setFilter(key, value);
    }

    @Override
    public boolean canBack() {
        if (!isViewReady()) return true;
        if (mBinding.pager.getAdapter() == null || mBinding.pager.getAdapter().getCount() == 0) return true;
        if (!getFragment().canBack()) return true;
        getFragment().goBack();
        return false;
    }

    private void startAutoScroll() {
        stopAutoScroll();
        if (isViewReady() && PlayerSetting.isHomeCarousel() && mHomeRecommends.size() > 1) {
            mHandler.postDelayed(mRunnable, 5000);
        }
    }

    private void stopAutoScroll() {
        mHandler.removeCallbacks(mRunnable);
    }

    @Override
    public void onResume() {
        super.onResume();
        applyHomeCarousel();
        startAutoScroll();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopAutoScroll();
    }

    @Override
    public void onDestroyView() {
        stopAutoScroll();
        mHandler.removeCallbacksAndMessages(null);
        super.onDestroyView();
        EventBus.getDefault().unregister(this);
        mBinding = null;
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            Class type = mAdapter.get(position);
            return FolderFragment.newInstance(getHome().getKey(), type, 4);
        }

        @Override
        public int getCount() {
            return mAdapter.getItemCount();
        }
    }

    private void loadHomeRecommends() {
        if (!isViewReady()) return;
        List<Site> sites = getRecommendSites();
        mHomeRecommends.clear();
        for (Site site : sites) addCachedRecommends(mHomeRecommends, site);
        if (!mHomeRecommends.isEmpty()) updateBanner();

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
                App.post(() -> {
                    if (!isViewReady()) return;
                    mHomeRecommends.clear();
                    mHomeRecommends.addAll(recommends);
                    updateBanner();
                });
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

    private void updateBanner() {
        if (!isViewReady() || mBannerAdapter == null) return;
        mBannerAdapter.notifyDataSetChanged();
        setBannerDots(mHomeRecommends.size());
        updateBannerDots(0);
        startAutoScroll();
    }

    private void loadHistory() {
        if (!Setting.isHomeHistory() || !PlayerSetting.isHomeCarousel()) {
            if (isViewReady()) {
                mBinding.recentLayout.setVisibility(View.GONE);
                mBinding.recentLayout.setAlpha(0f);
            }
            return;
        }
        com.fongmi.android.tv.utils.Task.executor().submit(() -> {
            try {
                List<History> historyList = History.get();
                App.post(() -> {
                    if (!isViewReady() || mHistoryAdapter == null) return;
                    if (!Setting.isHomeHistory() || !PlayerSetting.isHomeCarousel()) {
                        mBinding.recentLayout.setVisibility(View.GONE);
                        mBinding.recentLayout.setAlpha(0f);
                        return;
                    }
                    if (historyList != null && !historyList.isEmpty()) {
                        mHistoryAdapter.setItems(historyList);
                        mBinding.recentLayout.setVisibility(View.VISIBLE);
                        mBinding.recentLayout.setAlpha(1f);
                        updateHomeHeaderVisibility(mAppBarOffset);
                    } else {
                        mBinding.recentLayout.setVisibility(View.GONE);
                    }
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    private void setBannerDots(int count) {
        Context context = getContext();
        if (!isViewReady() || context == null) return;
        mBinding.dots.removeAllViews();
        if (count <= 1) return;
        for (int i = 0; i < count; i++) {
            View dot = new View(context);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ResUtil.dp2px(6), ResUtil.dp2px(6));
            params.setMargins(ResUtil.dp2px(4), 0, ResUtil.dp2px(4), 0);
            dot.setLayoutParams(params);
            dot.setBackgroundResource(R.drawable.shape_dot_inactive);
            mBinding.dots.addView(dot);
        }
    }

    private void updateBannerDots(int position) {
        if (!isViewReady()) return;
        int count = mBinding.dots.getChildCount();
        for (int i = 0; i < count; i++) {
            View dot = mBinding.dots.getChildAt(i);
            dot.setBackgroundResource(i == position ? R.drawable.shape_dot_active : R.drawable.shape_dot_inactive);
        }
    }

    class BannerAdapter extends RecyclerView.Adapter<BannerAdapter.ViewHolder> {

        private final List<Vod> items;

        public BannerAdapter(List<Vod> items) {
            this.items = items;
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterBannerItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Vod item = items.get(position);
            holder.binding.title.setText(item.getName());
            holder.binding.subtitle.setText(item.getRemarks());
            
            String logoUrl = item.getTag();
            if (!TextUtils.isEmpty(logoUrl)) {
                holder.binding.logo.setVisibility(View.VISIBLE);
                ImgUtil.load(item.getName(), logoUrl, holder.binding.logo, false);
            } else {
                holder.binding.logo.setVisibility(View.GONE);
            }
            
            ImgUtil.load(item.getName(), item.getPic(), holder.binding.image);
            holder.itemView.setOnClickListener(v -> {
                SearchActivity.start(requireActivity(), item.getName());
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private final AdapterBannerItemBinding binding;

            public ViewHolder(@NonNull AdapterBannerItemBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    class HistoryHorizontalAdapter extends RecyclerView.Adapter<HistoryHorizontalAdapter.ViewHolder> {

        private final List<History> items = new ArrayList<>();

        public void setItems(List<History> newItems) {
            items.clear();
            if (newItems.size() > 10) {
                items.addAll(newItems.subList(0, 10));
            } else {
                items.addAll(newItems);
            }
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterHistoryHorizontalBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            History item = items.get(position);
            holder.binding.name.setText(item.getVodName());
            holder.binding.progress.setMax((int) item.getDuration());
            holder.binding.progress.setProgress((int) item.getPosition());
            ImgUtil.load(item.getVodName(), item.getVodPic(), holder.binding.image);
            holder.itemView.setOnClickListener(v -> {
                VideoActivity.start(requireActivity(), item.getSiteKey(), item.getVodId(), item.getVodName(), item.getVodPic());
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private final AdapterHistoryHorizontalBinding binding;

            public ViewHolder(@NonNull AdapterHistoryHorizontalBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

    private void setFabMargin(View view, int bottomMargin) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        lp.bottomMargin = bottomMargin;
        view.setLayoutParams(lp);
    }
}

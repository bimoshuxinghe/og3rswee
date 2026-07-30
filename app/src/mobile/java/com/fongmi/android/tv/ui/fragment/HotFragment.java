package com.fongmi.android.tv.ui.fragment;

import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Product;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.DoubanApi;
import com.fongmi.android.tv.bean.Style;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.databinding.FragmentHotBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HotDetailActivity;
import com.fongmi.android.tv.ui.activity.SearchActivity;
import com.fongmi.android.tv.ui.adapter.VodAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HotFragment extends BaseFragment implements VodAdapter.OnClickListener, SwipeRefreshLayout.OnRefreshListener {

    private static final int MORE_THRESHOLD = 10;
    private static final int CATEGORY_FADE_DURATION = 130;
    private static final int CATEGORY_ENTER_DURATION = 220;
    private static final Map<String, List<Vod>> HOT_CACHE = new HashMap<>();

    private FragmentHotBinding mBinding;
    private VodAdapter mAdapter;
    private final List<Vod> mItems = new ArrayList<>();
    private final Map<String, Integer> mScrolls = new HashMap<>();
    private String mType = "radar";
    private int mPage = 1;
    private boolean mLoading;
    private boolean mNoMore;
    private boolean mCategoryChanging;

    public static HotFragment newInstance() {
        return new HotFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentHotBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setRecyclerView();
        setInsets();
        setTab();
        loadHot(false);
    }

    @Override
    protected void initEvent() {
        mBinding.swipeLayout.setOnRefreshListener(this);
        mBinding.movie.setOnClickListener(view -> select("movie"));
        mBinding.tv.setOnClickListener(view -> select("tv"));
        mBinding.show.setOnClickListener(view -> select("show"));
        mBinding.anime.setOnClickListener(view -> select("anime"));
        mBinding.radar.setOnClickListener(view -> select("radar"));
        mBinding.progressLayout.setOnClickListener(view -> {
            if (mBinding.progressLayout.isEmpty()) loadHot(false);
        });
        mBinding.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (dy <= 0) return;
                RecyclerView.LayoutManager layoutManager = view.getLayoutManager();
                if (layoutManager instanceof GridLayoutManager) {
                    GridLayoutManager manager = (GridLayoutManager) layoutManager;
                    if (manager.getItemCount() - manager.findLastVisibleItemPosition() <= MORE_THRESHOLD) loadMore();
                } else if (!view.canScrollVertically(1)) {
                    loadMore();
                }
            }
        });
    }

    private void setRecyclerView() {
        Style style = Style.rect();
        int spanCount = Product.getColumn(requireActivity(), style);
        mBinding.recycler.setHasFixedSize(true);
        mBinding.recycler.setAdapter(mAdapter = new VodAdapter(this, style, Product.getSpec(requireActivity(), style)));
        mBinding.recycler.setLayoutManager(new GridLayoutManager(getContext(), spanCount));
    }

    private void setInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> {
            int top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int bottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            mBinding.toolbar.setPadding(mBinding.toolbar.getPaddingLeft(), top, mBinding.toolbar.getPaddingRight(), 0);
            mBinding.toolbar.getLayoutParams().height = ResUtil.dp2px(56) + top;
            int paddingBottom = ResUtil.dp2px(16) + (Setting.isHomeCapsule() ? bottom + ResUtil.dp2px(72) : 0);
            mBinding.recycler.setPadding(mBinding.recycler.getPaddingLeft(), mBinding.recycler.getPaddingTop(), mBinding.recycler.getPaddingRight(), paddingBottom);
            return insets;
        });
    }

    private void select(String type) {
        if (mType.equals(type)) {
            mBinding.recycler.smoothScrollToPosition(0);
            return;
        }
        rememberScroll();
        mType = type;
        mCategoryChanging = true;
        setTab();
        animateCategoryOut();
        loadHot(false);
    }

    private void setTab() {
        setSelected(mBinding.movie, "movie".equals(mType));
        setSelected(mBinding.tv, "tv".equals(mType));
        setSelected(mBinding.show, "show".equals(mType));
        setSelected(mBinding.anime, "anime".equals(mType));
        setSelected(mBinding.radar, "radar".equals(mType));
    }

    private void setSelected(View view, boolean selected) {
        view.setSelected(selected);
        if (view instanceof TextView) ((TextView) view).setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
    }

    private void loadHot(boolean refresh) {
        String type = mType;
        List<Vod> cached = HOT_CACHE.get(type);
        if (!refresh && cached != null && !cached.isEmpty()) {
            mPage = 1;
            mNoMore = false;
            mLoading = false;
            mItems.clear();
            mItems.addAll(cached);
            setAdapter();
            return;
        }
        mPage = 1;
        mNoMore = false;
        mLoading = true;
        if (!refresh && !mCategoryChanging) mBinding.progressLayout.showProgress();
        Task.executor().submit(() -> {
            List<Vod> items;
            boolean success = true;
            try {
                items = DoubanApi.hot(type, 1);
            } catch (Exception e) {
                items = Collections.emptyList();
                success = false;
            }
            List<Vod> resultItems = items;
            boolean ok = success;
            App.post(() -> {
                if (!type.equals(mType)) return;
                mLoading = false;
                mBinding.swipeLayout.setRefreshing(false);
                if (!ok) {
                    mNoMore = mItems.isEmpty();
                    if (mItems.isEmpty()) {
                        mBinding.progressLayout.showEmpty();
                        mCategoryChanging = false;
                    }
                    Notify.show(R.string.hot_load_failed);
                    return;
                }
                mItems.clear();
                mItems.addAll(resultItems);
                HOT_CACHE.put(type, new ArrayList<>(resultItems));
                mNoMore = resultItems.isEmpty();
                setAdapter();
            });
        });
    }

    private void loadMore() {
        if (mLoading || mNoMore) return;
        mLoading = true;
        String type = mType;
        int page = mPage + 1;
        Task.executor().submit(() -> {
            List<Vod> items;
            boolean success = true;
            try {
                items = DoubanApi.hot(type, page);
            } catch (Exception e) {
                items = Collections.emptyList();
                success = false;
            }
            List<Vod> resultItems = items;
            boolean ok = success;
            App.post(() -> {
                if (!type.equals(mType)) return;
                mLoading = false;
                if (!ok) {
                    Notify.show(R.string.hot_load_failed);
                    return;
                }
                if (resultItems.isEmpty()) {
                    mNoMore = true;
                    return;
                }
                mPage = page;
                mItems.addAll(resultItems);
                HOT_CACHE.put(type, new ArrayList<>(mItems));
                setAdapter();
            });
        });
    }

    private void setAdapter() {
        List<Vod> items = new ArrayList<>();
        for (Vod item : mItems) if (mType.equals(item.getTag())) items.add(item);
        mBinding.progressLayout.showContent(true, items.size());
        boolean animate = mCategoryChanging;
        mCategoryChanging = false;
        mAdapter.setItems(items, hasChange -> {
            if (animate) {
                restoreScroll();
                animateCategoryIn();
            }
            checkMore();
        });
    }

    private void rememberScroll() {
        RecyclerView.LayoutManager layoutManager = mBinding.recycler.getLayoutManager();
        if (layoutManager instanceof GridLayoutManager) {
            mScrolls.put(mType, ((GridLayoutManager) layoutManager).findFirstVisibleItemPosition());
        }
    }

    private void restoreScroll() {
        Integer position = mScrolls.get(mType);
        mBinding.recycler.scrollToPosition(position == null ? 0 : Math.max(position, 0));
    }

    private void animateCategoryOut() {
        mBinding.recycler.animate().cancel();
        mBinding.recycler.animate()
                .alpha(0.45f)
                .translationY(ResUtil.dp2px(6))
                .setDuration(CATEGORY_FADE_DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void animateCategoryIn() {
        mBinding.recycler.animate().cancel();
        mBinding.recycler.setAlpha(0f);
        mBinding.recycler.setTranslationY(ResUtil.dp2px(10));
        mBinding.recycler.animate()
                .alpha(1f)
                .translationY(0)
                .setDuration(CATEGORY_ENTER_DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    private void checkMore() {
        mBinding.recycler.post(() -> {
            if (!mBinding.recycler.canScrollVertically(1)) loadMore();
        });
    }

    @Override
    public void onRefresh() {
        loadHot(true);
    }

    @Override
    public void onItemClick(Vod item) {
        HotDetailActivity.start(requireActivity(), item.getId(), item.getName(), item.getPic(), item.getTag());
    }

    @Override
    public boolean onLongClick(Vod item) {
        SearchActivity.start(requireActivity(), item.getName());
        return true;
    }
}

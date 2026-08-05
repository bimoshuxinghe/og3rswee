package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Parcelable;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Collect;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityCollectBinding;
import com.fongmi.android.tv.model.SiteViewModel;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.CollectAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.fragment.CollectFragment;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.List;

public class CollectActivity extends BaseActivity {

    private ActivityCollectBinding mBinding;
    private CollectAdapter mAdapter;
    private SiteViewModel mViewModel;
    private List<Site> mSites;
    private View mOldView;

    public static void start(Activity activity, String keyword) {
        Intent intent = new Intent(activity, CollectActivity.class);
        intent.putExtra("keyword", keyword);
        activity.startActivity(intent);
    }

    private CollectFragment getFragment() {
        return (CollectFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, 0);
    }

    private String getKeyword() {
        return getIntent().getStringExtra("keyword");
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityCollectBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        getIntent().putExtras(intent);
        mAdapter.clear();
        setPager();
        search();
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setRecyclerView();
        setViewModel();
        saveKeyword();
        setSites();
        setPager();
        search();
    }

    @Override
    protected void initEvent() {
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                if (Setting.getSearchMode() == 0) {
                    mBinding.recycler.setSelectedPosition(position);
                    mBinding.recycler.requestFocus();
                } else {
                    mBinding.recyclerV.setSelectedPosition(position);
                    mBinding.recyclerV.requestFocus();
                }
            }
        });
        OnChildViewHolderSelectedListener listener = new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                onChildSelected(child);
            }
        };
        mBinding.recycler.addOnChildViewHolderSelectedListener(listener);
        mBinding.recyclerV.addOnChildViewHolderSelectedListener(listener);
    }

    private void setRecyclerView() {
        mBinding.pager.setClipChildren(true);
        mBinding.pager.setClipToPadding(true);
        int mode = Setting.getSearchMode();
        mBinding.contentLayout.setClipChildren(mode != 0);
        if (mode == 0) {
            mBinding.recycler.setVisibility(View.VISIBLE);
            mBinding.recyclerV.setVisibility(View.GONE);
            mBinding.contentLayout.setOrientation(androidx.appcompat.widget.LinearLayoutCompat.VERTICAL);
            
            ViewGroup.LayoutParams pagerParams = mBinding.pager.getLayoutParams();
            pagerParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            pagerParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            if (pagerParams instanceof android.widget.LinearLayout.LayoutParams) {
                ((android.widget.LinearLayout.LayoutParams) pagerParams).weight = 0;
            }
            mBinding.pager.setLayoutParams(pagerParams);
            
            mBinding.recycler.setHorizontalSpacing(ResUtil.dp2px(16));
            mBinding.recycler.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
            mBinding.recycler.setAdapter(mAdapter = new CollectAdapter());
        } else {
            mBinding.recycler.setVisibility(View.GONE);
            mBinding.recyclerV.setVisibility(View.VISIBLE);
            mBinding.contentLayout.setOrientation(androidx.appcompat.widget.LinearLayoutCompat.HORIZONTAL);
            
            ViewGroup.LayoutParams pagerParams = mBinding.pager.getLayoutParams();
            pagerParams.width = 0;
            pagerParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            if (pagerParams instanceof android.widget.LinearLayout.LayoutParams) {
                ((android.widget.LinearLayout.LayoutParams) pagerParams).weight = 1;
            }
            mBinding.pager.setLayoutParams(pagerParams);
            
            mBinding.recyclerV.setVerticalSpacing(ResUtil.dp2px(16));
            mBinding.recyclerV.setColumnWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
            mBinding.recyclerV.setAdapter(mAdapter = new CollectAdapter());
        }
    }

    private void setViewModel() {
        mViewModel = new ViewModelProvider(this).get(SiteViewModel.class);
        mViewModel.getSearch().observe(this, result -> {
            if (result.getList().isEmpty()) return;
            getFragment().addVideo(result.getList());
            mAdapter.add(Collect.create(result.getList()));
            int pos = mAdapter.getList().size() - 1;
            mBinding.pager.getAdapter().notifyItemInserted(pos);
        });
    }

    private void saveKeyword() {
        List<String> items = Setting.getKeyword().isEmpty() ? new ArrayList<>() : App.gson().fromJson(Setting.getKeyword(), new TypeToken<List<String>>() {}.getType());
        items.remove(getKeyword());
        items.add(0, getKeyword());
        if (items.size() > 9) items.remove(9);
        Setting.putKeyword(App.gson().toJson(items));
    }

    private void setSites() {
        mSites = VodConfig.get().getSites().stream().filter(Site::isSearchable).toList();
    }

    private void setPager() {
        mBinding.pager.setAdapter(new PageAdapter(getSupportFragmentManager()));
    }

    private void search() {
        if (mSites.isEmpty()) return;
        mAdapter.add(Collect.all());
        mBinding.pager.getAdapter().notifyDataSetChanged();
        mBinding.result.setText(getString(R.string.collect_result, getKeyword()));
        mViewModel.searchContent(mSites, getKeyword(), false);
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
            int pos = Setting.getSearchMode() == 0 ? mBinding.recycler.getSelectedPosition() : mBinding.recyclerV.getSelectedPosition();
            mBinding.pager.setCurrentItem(pos);
        }
    };

    @Override
    protected void onBackInvoked() {
        mViewModel.stopSearch();
        super.onBackInvoked();
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            return CollectFragment.newInstance(getKeyword(), mAdapter.get(position));
        }

        @Override
        public int getCount() {
            return mAdapter.getItemCount();
        }

        @Nullable
        @Override
        public Parcelable saveState() {
            return null;
        }

        @Override
        public void restoreState(@Nullable Parcelable state, @Nullable ClassLoader loader) {
        }
    }
}

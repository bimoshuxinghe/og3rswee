package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentStatePagerAdapter;
import androidx.leanback.widget.OnChildViewHolderSelectedListener;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;
import androidx.viewpager.widget.ViewPager;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityLocalResourceBinding;
import com.fongmi.android.tv.databinding.AdapterTypeBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.NasEditDialog;
import com.fongmi.android.tv.ui.fragment.NasManageFragment;
import com.fongmi.android.tv.ui.fragment.FolderFragment;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class LocalResourceActivity extends BaseActivity implements LocalTypeAdapter.OnClickListener {

    private ActivityLocalResourceBinding mBinding;
    private LocalTypeAdapter mAdapter;
    private List<Site> mSites;
    private View mOldView;
    private Site mHistory;
    private String mApiFilter;
    private String mSiteKey;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, LocalResourceActivity.class));
    }

    public static void start(Activity activity, String apiFilter) {
        start(activity, apiFilter, null);
    }

    public static void start(Activity activity, String apiFilter, String siteKey) {
        Intent intent = new Intent(activity, LocalResourceActivity.class);
        intent.putExtra("apiFilter", apiFilter);
        intent.putExtra("siteKey", siteKey);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityLocalResourceBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mHistory = VodConfig.get().getHome();
        mApiFilter = getIntent().getStringExtra("apiFilter");
        mSiteKey = getIntent().getStringExtra("siteKey");
        setRecyclerView();
        PermissionUtil.requestFile(this, allGranted -> loadDevices());
    }

    @Override
    protected void initEvent() {
        mBinding.pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                mBinding.recycler.setSelectedPosition(position);
                mBinding.recycler.requestFocus();
                if (position < mSites.size()) {
                    VodConfig.get().setHome(mSites.get(position));
                }
            }
        });
        mBinding.recycler.addOnChildViewHolderSelectedListener(new OnChildViewHolderSelectedListener() {
            @Override
            public void onChildViewHolderSelected(@NonNull RecyclerView parent, @Nullable RecyclerView.ViewHolder child, int position, int subposition) {
                onChildSelected(child);
            }
        });
    }

    private void setRecyclerView() {
        mBinding.recycler.requestFocus();
        mBinding.recycler.setHorizontalSpacing(ResUtil.dp2px(16));
        mBinding.recycler.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mBinding.recycler.setAdapter(mAdapter = new LocalTypeAdapter(this));
    }

    public void loadDevices() {
        mSites = new ArrayList<>();

        // Find active spider jar robustly from any existing non-local/non-nas site
        String spider = "";
        for (Site site : VodConfig.get().getSites()) {
            if (!"local_file_system".equals(site.getKey()) && (site.getKey() == null || !site.getKey().startsWith("local_nas_")) && !TextUtils.isEmpty(site.getJar())) {
                spider = site.getJar();
                break;
            }
        }

        if (TextUtils.isEmpty(mApiFilter)) {
            Site localFile = VodConfig.get().getSite("local_file_system");
            if (localFile.isEmpty()) {
                localFile = new Site();
                localFile.setKey("local_file_system");
                localFile.setName(ResUtil.getString(R.string.home_local));
                localFile.setApi("csp_LocalFile");
                localFile.setExt("file:///" + com.github.catvod.utils.Path.root().getAbsolutePath());
                localFile.setType(3);
            }
            if (TextUtils.isEmpty(localFile.getJar())) localFile.setJar(spider);
            mSites.add(localFile);
            upsertSite(localFile);
        }

        // 2. Local NAS list from DB
        List<Site> allDbSites = Site.findAll();
        for (Site site : allDbSites) {
            if (site.getKey() != null && site.getKey().startsWith("local_nas_") && (TextUtils.isEmpty(mApiFilter) || mApiFilter.equals(site.getApi()))) {
                if (TextUtils.isEmpty(site.getJar())) {
                    site.setJar(spider);
                }
                mSites.add(site);
                upsertSite(site);
            }
        }

        // Build Class list for tabs
        List<Class> types = new ArrayList<>();
        for (Site site : mSites) {
            Class type = new Class();
            type.setTypeId("root");
            type.setTypeName(site.getName());
            type.setTypeFlag("1"); // marks as folder
            types.add(type);
        }

        // Manage Connections Tab
        Class addType = new Class();
        addType.setTypeId("manage_connections_dummy");
        addType.setTypeName(ResUtil.getString(R.string.nas_manage));
        addType.setTypeFlag("0");
        types.add(addType);

        mAdapter.addAll(types);

        // Setup ViewPager adapter
        mBinding.pager.setAdapter(new PageAdapter(getSupportFragmentManager()));

        // Set selected position to current VOD home site if it is one of the local resource sites
        int index = findSelectedIndex();
        if (index == -1) index = 0;
        mBinding.pager.setCurrentItem(index);
        mBinding.recycler.setSelectedPosition(index);
    }

    private int findSelectedIndex() {
        if (!TextUtils.isEmpty(mSiteKey)) {
            for (int i = 0; i < mSites.size(); i++) {
                if (mSiteKey.equals(mSites.get(i).getKey())) return i;
            }
        }
        return mSites.indexOf(VodConfig.get().getHome());
    }

    private void upsertSite(Site site) {
        if (!VodConfig.get().getSites().contains(site)) {
            VodConfig.get().getSites().add(site);
        } else {
            int idx = VodConfig.get().getSites().indexOf(site);
            if (idx != -1) VodConfig.get().getSites().set(idx, site);
        }
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
            mBinding.pager.setCurrentItem(mBinding.recycler.getSelectedPosition());
        }
    };

    private FolderFragment getFragment() {
        if (mBinding.pager.getAdapter() == null || mBinding.pager.getCurrentItem() >= mSites.size()) return null;
        return (FolderFragment) mBinding.pager.getAdapter().instantiateItem(mBinding.pager, mBinding.pager.getCurrentItem());
    }

    @Override
    public void onItemClick(Class item) {
    }

    @Override
    public void onItemLongClick(Class item) {
        if ("manage_connections_dummy".equals(item.getTypeId())) return;
        
        int position = mAdapter.indexOf(item);
        if (position < 0 || position >= mSites.size()) return;
        Site site = mSites.get(position);
        if ("local_file_system".equals(site.getKey())) return; // Cannot delete local file system

        new MaterialAlertDialogBuilder(this)
                .setTitle(site.getName())
                .setMessage(R.string.nas_delete_confirm)
                .setPositiveButton(R.string.dialog_delete, (dialog, which) -> {
                    site.delete();
                    VodConfig.get().getSites().remove(site);
                    loadDevices();
                })
                .setNegativeButton(R.string.dialog_edit, (dialog, which) -> {
                    NasEditDialog.create().edit(site).setCallback(this::onNasSaved).show(this);
                })
                .setNeutralButton(R.string.dialog_negative, null)
                .show();
    }

    private void onNasSaved() {
        String spider = "";
        for (Site site : VodConfig.get().getSites()) {
            if (!"local_file_system".equals(site.getKey()) && (site.getKey() == null || !site.getKey().startsWith("local_nas_")) && !TextUtils.isEmpty(site.getJar())) {
                spider = site.getJar();
                break;
            }
        }
        List<Site> dbSites = Site.findAll();
        for (Site site : dbSites) {
            if (site.getKey() != null && site.getKey().startsWith("local_nas_")) {
                if (TextUtils.isEmpty(site.getJar())) {
                    site.setJar(spider);
                }
                if (!VodConfig.get().getSites().contains(site)) {
                    VodConfig.get().getSites().add(site);
                } else {
                    int idx = VodConfig.get().getSites().indexOf(site);
                    if (idx != -1) {
                        VodConfig.get().getSites().set(idx, site);
                    }
                }
            }
        }
        loadDevices();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.VOD) {
            loadDevices();
        }
    }

    @Override
    protected void onBackInvoked() {
        if (getFragment() != null && getFragment().canBack()) {
            getFragment().goBack();
        } else {
            super.onBackInvoked();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mHistory != null) {
            VodConfig.get().setHome(mHistory);
            RefreshEvent.home();
        }
    }

    class PageAdapter extends FragmentStatePagerAdapter {

        public PageAdapter(@NonNull FragmentManager fm) {
            super(fm);
        }

        @NonNull
        @Override
        public Fragment getItem(int position) {
            if (position < mSites.size()) {
                Site site = mSites.get(position);
                Class type = new Class();
                type.setTypeId("root");
                type.setTypeName(site.getName());
                type.setTypeFlag("1"); // marks as folder
                return FolderFragment.newInstance(site.getKey(), type);
            }
            return NasManageFragment.newInstance();
        }

        @Override
        public int getCount() {
            return mAdapter.getItemCount();
        }
    }
}

class LocalTypeAdapter extends RecyclerView.Adapter<LocalTypeAdapter.ViewHolder> {

        private final OnClickListener mListener;
        private final List<Class> mItems;

        public LocalTypeAdapter(OnClickListener listener) {
            mListener = listener;
            mItems = new ArrayList<>();
        }

        public void addAll(List<Class> items) {
            mItems.clear();
            mItems.addAll(items);
            notifyDataSetChanged();
        }

        public Class get(int position) {
            return mItems.get(position);
        }

        public int indexOf(Class item) {
            return mItems.indexOf(item);
        }

        @Override
        public int getItemCount() {
            return mItems.size();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterTypeBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Class item = mItems.get(position);
            holder.binding.text.setText(item.getTypeName());
            holder.binding.getRoot().setOnClickListener(v -> mListener.onItemClick(item));
            holder.binding.getRoot().setOnLongClickListener(v -> {
                mListener.onItemLongClick(item);
                return true;
            });
        }

        public interface OnClickListener {
            void onItemClick(Class item);
            void onItemLongClick(Class item);
        }

        public static class ViewHolder extends RecyclerView.ViewHolder {
            private final AdapterTypeBinding binding;

            ViewHolder(@NonNull AdapterTypeBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }

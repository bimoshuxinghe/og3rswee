package com.fongmi.android.tv.ui.fragment;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.FragmentLocalBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.ui.activity.LocalFileActivity;
import com.fongmi.android.tv.ui.adapter.LocalAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.NasEditDialog;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

public class LocalFragment extends BaseFragment implements LocalAdapter.OnClickListener {

    private FragmentLocalBinding mBinding;
    private LocalAdapter mAdapter;
    private List<Site> mSites;

    public static LocalFragment newInstance() {
        return new LocalFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentLocalBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        EventBus.getDefault().register(this);
        mSites = new ArrayList<>();
        mAdapter = new LocalAdapter(this);
        mBinding.recycler.setAdapter(mAdapter);
        loadDevices();
    }

    @Override
    protected void initEvent() {
        mBinding.fab.setOnClickListener(v -> NasEditDialog.create().setCallback(this::loadDevices).show(this));
        mBinding.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                if (dy != 0) {
                    com.fongmi.android.tv.event.ScrollEvent.post(dy);
                }
            }
        });
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(mBinding.getRoot(), (v, insets) -> {
            int bottom = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom;
            boolean capsule = com.fongmi.android.tv.setting.Setting.isHomeCapsule();
            int margin = ResUtil.dp2px(24);
            if (capsule) {
                margin += bottom + ResUtil.dp2px(72);
            }
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) mBinding.fab.getLayoutParams();
            lp.bottomMargin = margin;
            mBinding.fab.setLayoutParams(lp);
            return insets;
        });
    }

    public void loadDevices() {
        mSites.clear();

        // Find active spider jar robustly from any existing non-local/non-nas site
        String spider = "";
        for (Site site : VodConfig.get().getSites()) {
            if (!"local_file_system".equals(site.getKey()) && (site.getKey() == null || !site.getKey().startsWith("local_nas_")) && !TextUtils.isEmpty(site.getJar())) {
                spider = site.getJar();
                break;
            }
        }

        // 1. Local File Site
        Site localFile = VodConfig.get().getSite("local_file_system");
        if (localFile.isEmpty()) {
            localFile = new Site();
            localFile.setKey("local_file_system");
            localFile.setName(ResUtil.getString(R.string.home_local));
            localFile.setApi("csp_LocalFile");
            localFile.setExt("file:///" + com.github.catvod.utils.Path.root().getAbsolutePath());
            localFile.setType(3);
        }
        if (TextUtils.isEmpty(localFile.getJar())) {
            localFile.setJar(spider);
        }
        mSites.add(localFile);

        // Ensure it's registered in VodConfig sites so TypeFragment can find it
        if (!VodConfig.get().getSites().contains(localFile)) {
            VodConfig.get().getSites().add(localFile);
        } else {
            int idx = VodConfig.get().getSites().indexOf(localFile);
            if (idx != -1) {
                VodConfig.get().getSites().set(idx, localFile);
            }
        }

        // 2. Local NAS list from DB
        List<Site> allDbSites = Site.findAll();
        for (Site site : allDbSites) {
            if (site.getKey() != null && site.getKey().startsWith("local_nas_")) {
                if (TextUtils.isEmpty(site.getJar())) {
                    site.setJar(spider);
                }
                mSites.add(site);

                // Ensure it's registered in VodConfig sites so TypeFragment can find it
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

        mAdapter.addAll(mSites);
    }

    @Override
    public void onItemClick(Site item) {
        LocalFileActivity.start(requireActivity(), item.getKey());
    }

    @Override
    public boolean onItemLongClick(Site item) {
        if ("local_file_system".equals(item.getKey())) return false; // Cannot delete local file system

        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(item.getName())
                .setMessage(R.string.nas_delete_confirm)
                .setPositiveButton(R.string.dialog_delete, (dialog, which) -> {
                    item.delete();
                    VodConfig.get().getSites().remove(item);
                    loadDevices();
                })
                .setNegativeButton(R.string.nas_title_edit, (dialog, which) -> {
                    NasEditDialog.create().edit(item).setCallback(this::loadDevices).show(this);
                })
                .setNeutralButton(R.string.dialog_negative, null)
                .show();
        return true;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onConfigEvent(ConfigEvent event) {
        if (event.type() == ConfigEvent.Type.VOD) {
            loadDevices();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        EventBus.getDefault().unregister(this);
    }
}

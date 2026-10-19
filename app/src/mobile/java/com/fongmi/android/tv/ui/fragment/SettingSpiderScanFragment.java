package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.FragmentSettingSpiderScanBinding;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.LocalSpider;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.SpiderScanner;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 扫描设备存储里的 .js / .py 文件，勾选后登记为播放线路（首页「本地」分组）。
 */
public class SettingSpiderScanFragment extends BaseFragment {

    private FragmentSettingSpiderScanBinding mBinding;
    private ArrayAdapter<String> mAdapter;
    private final List<SpiderScanner.Item> mItems = new ArrayList<>();
    private final Set<String> mImported = new HashSet<>();
    private boolean scanning;

    public static SettingSpiderScanFragment newInstance() {
        return new SettingSpiderScanFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingSpiderScanBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_multiple_choice, new ArrayList<>());
        mBinding.list.setAdapter(mAdapter);
        mBinding.list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < mItems.size()) mItems.get(position).checked = !mItems.get(position).checked;
        });
        refreshImported();
    }

    @Override
    protected void initEvent() {
        mBinding.scan.setOnClickListener(this::onScan);
        mBinding.selectAll.setOnClickListener(this::onSelectAll);
        mBinding.importSpider.setOnClickListener(this::onImport);
        mBinding.manage.setOnClickListener(this::onManage);
    }

    /** 扫描前先要文件权限，PermissionUtil 会按系统版本选择对应授权方式。 */
    private void onScan(View view) {
        if (scanning) return;
        PermissionUtil.requestFile(this, granted -> {
            if (!granted) {
                Notify.show(R.string.spider_scan_perm);
                return;
            }
            startScan();
        });
    }

    private void startScan() {
        scanning = true;
        mItems.clear();
        mAdapter.clear();
        mBinding.scan.setEnabled(false);
        mBinding.progress.setText(R.string.spider_scan_scanning);
        SpiderScanner.scan(new SpiderScanner.Callback() {
            @Override
            public void onUpdate(int count) {
                mBinding.progress.setText(getString(R.string.spider_scan_found, count));
            }

            @Override
            public void onFinish(List<SpiderScanner.Item> items) {
                scanning = false;
                mBinding.scan.setEnabled(true);
                mItems.addAll(items);
                mAdapter.clear();
                mAdapter.addAll(SpiderScanner.labels(items));
                mAdapter.notifyDataSetChanged();
                mBinding.progress.setText(items.isEmpty()
                        ? getString(R.string.spider_scan_none)
                        : getString(R.string.spider_scan_summary, items.size(), SpiderScanner.summary(items)));
            }
        });
    }

    private void onSelectAll(View view) {
        boolean target = false;
        for (SpiderScanner.Item item : mItems) {
            if (!item.checked) {
                target = true;
                break;
            }
        }
        SpiderScanner.checkAll(mItems, target);
        for (int i = 0; i < mItems.size(); i++) mBinding.list.setItemChecked(i, target);
    }

    private void onImport(View view) {
        List<SpiderScanner.Item> checked = SpiderScanner.checked(mItems);
        if (checked.isEmpty()) {
            Notify.show(R.string.spider_scan_none_checked);
            return;
        }
        int ok = 0;
        for (SpiderScanner.Item item : checked) {
            if (LocalSpider.add(item.file) != null) ok++;
        }
        refreshImported();
        Notify.show(getString(R.string.spider_scan_imported, ok));
    }

    private void onManage(View view) {
        List<Site> sites = LocalSpider.all();
        if (sites.isEmpty()) {
            Notify.show(R.string.spider_scan_empty);
            return;
        }
        String[] names = new String[sites.size()];
        for (int i = 0; i < sites.size(); i++) names[i] = sites.get(i).getName();
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.spider_scan_imported_title)
                .setItems(names, (dialog, which) -> confirmRemove(sites.get(which)))
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void confirmRemove(Site site) {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.spider_scan_remove_title)
                .setMessage(getString(R.string.spider_scan_remove_msg, site.getName()))
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    LocalSpider.remove(site);
                    refreshImported();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void refreshImported() {
        mImported.clear();
        for (Site site : LocalSpider.all()) mImported.add(site.getName());
        mBinding.manage.setText(getString(R.string.spider_scan_manage, mImported.size()));
    }
}

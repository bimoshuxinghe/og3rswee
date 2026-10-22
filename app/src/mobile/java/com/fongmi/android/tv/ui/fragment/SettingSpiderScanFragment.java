package com.fongmi.android.tv.ui.fragment;

import android.content.Intent;
import android.text.TextUtils;
import android.content.UriPermission;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import android.widget.EditText;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.FragmentSettingSpiderScanBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.adapter.SpiderFileAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.LocalSpider;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
import com.fongmi.android.tv.utils.SpiderScanner;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.github.catvod.utils.Prefers;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫描设备存储里的 .js / .py 文件，勾选后登记为播放线路（首页「本地」分组）。
 *
 * <p>勾选语义即线路开关：勾选=加入线路，取消勾选=移出线路，
 * 点「应用」后按差异同步，列表右侧徽标显示当前是否已导入。
 */
public class SettingSpiderScanFragment extends BaseFragment {

    private static final String KEY_DIRS = "spider_scan_dirs";
    private static final String KEY_KIND = "spider_scan_kind";

    private FragmentSettingSpiderScanBinding mBinding;
    private SpiderFileAdapter mAdapter;
    private final List<SpiderScanner.Item> mItems = new ArrayList<>();
    private final List<SpiderScanner.Item> mAll = new ArrayList<>();
    private boolean scanning;

    private final ActivityResultLauncher<Uri> dirLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                saveDir(uri);
                scanTree(uri);
            });

    public static SettingSpiderScanFragment newInstance() {
        return new SettingSpiderScanFragment();
    }

    private HomeActivity getRoot() {
        return (HomeActivity) requireActivity();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingSpiderScanBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.toolbar.setNavigationOnClickListener(v -> getRoot().change(1));
        // 系统返回键回到设置页，而不是直接退出应用
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new androidx.activity.OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        getRoot().change(1);
                    }
                });
        mAdapter = new SpiderFileAdapter(mItems, this::updateSummary, this::showItemMenu);
        int saved = Prefers.getInt(KEY_KIND);
        mBinding.kind.check(saved == 1 ? R.id.kindJs : saved == 2 ? R.id.kindPy : R.id.kindAll);
        mBinding.list.setLayoutManager(new LinearLayoutManager(requireContext()));
        mBinding.list.setAdapter(mAdapter);
        updateSummary();
    }

    @Override
    protected void initEvent() {
        mBinding.scan.setOnClickListener(v -> onScan());
        mBinding.pickDir.setOnClickListener(v -> dirLauncher.launch(null));
        mBinding.invert.setOnClickListener(v -> onToggle());
        mBinding.apply.setOnClickListener(v -> onApply());
        mBinding.kind.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            Prefers.put(KEY_KIND, checkedId == R.id.kindJs ? 1 : checkedId == R.id.kindPy ? 2 : 0);
            applyKindFilter();
        });
    }

    /** 扫描常用目录 + 已授权的自定义目录。 */
    private void onScan() {
        if (scanning) return;
        PermissionUtil.requestFile(this, granted -> {
            if (!granted) {
                Notify.show(R.string.spider_scan_perm);
                return;
            }
            List<Uri> dirs = loadDirs();
            // 选过目录就只扫这些目录，没选过才扫常用目录
            startScan(dirs, dirs.isEmpty());
        });
    }

    /** 用户通过系统选择器授权一个目录树。 */
    private void onPickDir() {
        dirLauncher.launch(null);
    }

    private void scanTree(Uri treeUri) {
        if (scanning) return;
        try {
            App.get().getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        startScan(List.of(treeUri), false);
    }

    /**
     * 发起扫描。
     *
     * @param dirs          已授权的自定义目录
     * @param includeCommon 是否连常用目录（存储根/Download/…）一起扫；
     *                      用户指定过目录时为 false，避免每次都全盘
     */
    private void startScan(@Nullable List<Uri> dirs, boolean includeCommon) {
        scanning = true;
        mBinding.scan.setEnabled(false);
        mBinding.pickDir.setEnabled(false);
        mBinding.progress.setText(R.string.spider_scan_scanning);
        List<SpiderScanner.Item> found = new ArrayList<>();
        if (!includeCommon) {
            if (dirs == null || dirs.isEmpty()) {
                finishScan(found);
                return;
            }
            scanNext(found, dirs, 0);
            return;
        }
        // 常用目录先扫，再叠加自定义目录
        SpiderScanner.scan(new SpiderScanner.Callback() {
            @Override
            public void onUpdate(int count) {
                mBinding.progress.setText(getString(R.string.spider_scan_found, count));
            }

            @Override
            public void onFinish(List<SpiderScanner.Item> items) {
                found.addAll(items);
                if (dirs == null || dirs.isEmpty()) {
                    finishScan(found);
                } else {
                    scanNext(found, dirs, 0);
                }
            }
        });
    }

    private void scanNext(List<SpiderScanner.Item> found, List<Uri> dirs, int index) {
        if (index >= dirs.size()) {
            finishScan(found);
            return;
        }
        SpiderScanner.scanTree(App.get(), dirs.get(index), new SpiderScanner.Callback() {
            @Override
            public void onUpdate(int count) {
                mBinding.progress.setText(getString(R.string.spider_scan_found, found.size() + count));
            }

            @Override
            public void onFinish(List<SpiderScanner.Item> items) {
                found.addAll(items);
                scanNext(found, dirs, index + 1);
            }
        });
    }

    private void finishScan(List<SpiderScanner.Item> items) {
        scanning = false;
        mBinding.scan.setEnabled(true);
        mBinding.pickDir.setEnabled(true);
        mAll.clear();
        mAll.addAll(SpiderScanner.dedup(items));
        applyKindFilter();
        mBinding.progress.setText(mItems.isEmpty()
                ? getString(R.string.spider_scan_none)
                : getString(R.string.spider_scan_summary, mItems.size(), SpiderScanner.summary(mItems)));
        updateSummary();
    }

    /** 智能选择：没有任何勾选时全选，否则反选（按钮文字随状态切换）。 */
    private void onToggle() {
        boolean anyChecked = false;
        for (SpiderScanner.Item item : mItems) {
            if (item.checked) {
                anyChecked = true;
                break;
            }
        }
        if (anyChecked) mAdapter.invert();
        else mAdapter.checkAll(true);
        updateSummary();
    }

    /** 按勾选状态与已导入状态的差异同步线路：勾选=加入，取消=移除。 */
    private void onApply() {
        if (scanning) return;
        int add = 0, remove = 0;
        for (SpiderScanner.Item item : mItems) {
            if (item.checked && !item.imported) {
                if (addItem(item)) {
                    item.imported = true;
                    add++;
                }
            } else if (!item.checked && item.imported) {
                LocalSpider.removeByName(item.name);
                item.imported = false;
                remove++;
            }
        }
        mAdapter.notifyDataSetChanged();
        updateSummary();
        if (add == 0 && remove == 0) {
            Notify.show(R.string.spider_scan_no_change);
        } else {
            Notify.show(getString(R.string.spider_scan_applied, add, remove));
        }
    }

    private boolean addItem(SpiderScanner.Item item) {
        if (item.file != null) return LocalSpider.add(item.file) != null;
        return LocalSpider.add(App.get(), item.uri, item.name) != null;
    }

    private void updateSummary() {
        int imported = 0, checkedCount = 0;
        for (SpiderScanner.Item item : mItems) {
            if (item.imported) imported++;
            if (item.checked) checkedCount++;
        }
        mBinding.summary.setText(getString(R.string.spider_scan_count, mItems.size(), imported, mAdapter.pendingCount()));
        // 没有勾选时显示"全选"，已有勾选则显示"反选"
        mBinding.invert.setText(checkedCount == 0 ? R.string.spider_scan_select_all : R.string.spider_scan_invert);
    }

    // ---------------- 自定义目录持久化 ----------------

    private List<Uri> loadDirs() {
        List<Uri> list = new ArrayList<>();
        try {
            String raw = Prefers.getString(KEY_DIRS);
            if (!raw.isEmpty()) {
                JSONArray array = new JSONArray(raw);
                for (int i = 0; i < array.length(); i++) list.add(Uri.parse(array.optString(i)));
            }
        } catch (Exception ignored) {
        }
        // 只保留仍然持久授权的那部分
        List<Uri> valid = new ArrayList<>();
        try {
            for (UriPermission permission : App.get().getContentResolver().getPersistedUriPermissions()) {
                if (permission.isReadPermission() && list.contains(permission.getUri())) valid.add(permission.getUri());
            }
        } catch (Exception ignored) {
        }
        return valid;
    }

    private void saveDir(Uri uri) {
        try {
            List<Uri> list = loadDirs();
            if (!list.contains(uri)) list.add(uri);
            JSONArray array = new JSONArray();
            for (Uri item : list) array.put(item.toString());
            Prefers.put(KEY_DIRS, array.toString());
        } catch (Exception ignored) {
        }
    }

    /** 按当前类型过滤全量结果，无需重新扫描。 */
    private void applyKindFilter() {
        SpiderScanner.Kind kind = currentKind();
        List<SpiderScanner.Item> filtered = SpiderScanner.filter(mAll, kind);
        mItems.clear();
        for (SpiderScanner.Item item : filtered) {
            item.imported = LocalSpider.exists(item.name);
            Site site = item.imported ? LocalSpider.siteFor(item.name) : null;
            item.hasExt = site != null && !TextUtils.isEmpty(site.getExt());
            if (!item.imported) item.checked = false;
            mItems.add(item);
        }
        mAdapter.notifyDataSetChanged();
        updateSummary();
    }

    private SpiderScanner.Kind currentKind() {
        int checked = mBinding.kind.getCheckedButtonId();
        if (checked == R.id.kindJs) return SpiderScanner.Kind.JS;
        if (checked == R.id.kindPy) return SpiderScanner.Kind.PY;
        return SpiderScanner.Kind.ALL;
    }

    /** 长按条目：编辑 ext 参数 / 移除线路。 */
    private void showItemMenu(SpiderScanner.Item item) {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(item.name)
                .setItems(new CharSequence[]{getString(R.string.spider_scan_edit_ext), getString(R.string.spider_scan_remove)}, (dialog, which) -> {
                    if (which == 0) editExt(item);
                    else {
                        if (item.imported) {
                            LocalSpider.removeByName(item.name);
                            item.imported = false;
                            item.checked = false;
                            item.hasExt = false;
                            mAdapter.notifyDataSetChanged();
                            updateSummary();
                            Notify.show(R.string.spider_scan_removed);
                        }
                    }
                })
                .show();
    }

    /** 编辑导入爬虫的 ext 参数（部分 js/py 爬虫需要 ext 传入配置）。 */
    private void editExt(SpiderScanner.Item item) {
        if (!item.imported) {
            Notify.show(R.string.spider_scan_need_import);
            return;
        }
        Site site = LocalSpider.siteFor(item.name);
        if (site == null) return;
        EditText input = new EditText(requireContext());
        input.setMinLines(3);
        input.setMaxLines(10);
        input.setTextSize(13);
        input.setText(site.getExt() == null ? "" : site.getExt());
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.spider_scan_edit_ext)
                .setMessage(R.string.spider_scan_edit_ext_hint)
                .setView(input)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                    String ext = input.getText().toString().trim();
                    site.setExt(ext);
                    site.save();
                    item.hasExt = !TextUtils.isEmpty(ext);
                    mAdapter.notifyDataSetChanged();
                    RefreshEvent.home();
                    Notify.show(R.string.spider_scan_ext_saved);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }
}

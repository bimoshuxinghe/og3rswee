package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.text.InputFilter;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogSiteBinding;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.ui.adapter.SiteAdapter;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class SiteDialog extends BaseAlertDialog implements SiteAdapter.OnClickListener {

    private static final int MAX_GROUPS = 5;
    private static final int MAX_GROUP_NAME_LEN = 2;
    private static final String PREFS_GROUPS = "site_custom_groups";
    private static final String KEY_GROUPS = "groups";

    private DialogSiteBinding binding;
    private SiteListener listener;
    private SiteAdapter adapter;
    private String currentGroup = "";
    private boolean search;
    private boolean change;

    public static SiteDialog create() {
        return new SiteDialog();
    }

    public SiteDialog search() {
        search = true;
        return this;
    }

    public SiteDialog change() {
        change = true;
        return this;
    }

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
        if (fragment instanceof SiteListener) listener = (SiteListener) fragment;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSiteBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        adapter = new SiteAdapter(this);
        if (isTablet()) {
            binding.recycler.setLayoutManager(new GridLayoutManager(requireContext(), 2));
        }
        binding.recycler.setAdapter(adapter);
        adapter.search(search).change(change);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setHasFixedSize(true);
        binding.recycler.addItemDecoration(new SpaceItemDecoration(isTablet() ? 2 : 1, 8));
        
        binding.search.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                adapter.filter(s.toString());
            }
        });

        renderGroups();

        int selectedIndex = -1;
        for (int i = 0; i < adapter.getItemCount(); i++) {
            if (adapter.getItems().get(i).isSelected()) {
                selectedIndex = i;
                break;
            }
        }
        if (selectedIndex != -1) {
            int finalSelectedIndex = selectedIndex;
            binding.recycler.post(() -> binding.recycler.scrollToPosition(finalSelectedIndex));
        }
    }

    /** 是否平板（最小宽度 >= 600dp）。 */
    private boolean isTablet() {
        return (getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) >= Configuration.SCREENLAYOUT_SIZE_LARGE
                || getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    /** 收集全部自定义分组名（站源分组优先，再补独立持久化分组，保持出现顺序、去重）。 */
    private List<String> collectGroups() {
        Set<String> set = new LinkedHashSet<>();
        for (Site site : VodConfig.get().getSites()) {
            String group = site.getGroup();
            if (!TextUtils.isEmpty(group)) set.add(group);
        }
        set.addAll(loadCustomGroups());
        return new ArrayList<>(set);
    }

    private SharedPreferences groupPrefs() {
        return requireContext().getSharedPreferences(PREFS_GROUPS, Context.MODE_PRIVATE);
    }

    /** 读取独立持久化的分组名（JSON 数组字符串，保持创建顺序）。 */
    private List<String> loadCustomGroups() {
        List<String> list = new ArrayList<>();
        try {
            String raw = groupPrefs().getString(KEY_GROUPS, "");
            if (raw == null || raw.isEmpty()) return list;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                String name = arr.optString(i);
                if (!TextUtils.isEmpty(name)) list.add(name);
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    private void saveCustomGroups(List<String> list) {
        JSONArray arr = new JSONArray();
        for (String name : list) arr.put(name);
        groupPrefs().edit().putString(KEY_GROUPS, arr.toString()).apply();
    }

    private void addCustomGroup(String name) {
        List<String> list = loadCustomGroups();
        if (!list.contains(name)) {
            list.add(name);
            saveCustomGroups(list);
        }
    }

    private void removeCustomGroup(String name) {
        List<String> list = loadCustomGroups();
        if (list.remove(name)) saveCustomGroups(list);
    }

    private void renameCustomGroup(String oldName, String newName) {
        List<String> list = loadCustomGroups();
        int idx = list.indexOf(oldName);
        if (idx >= 0) {
            list.set(idx, newName);
            saveCustomGroups(list);
        } else {
            addCustomGroup(newName);
        }
    }

    /** 渲染分组栏：全部 + 各分组 + 新建按钮。 */
    private void renderGroups() {
        binding.groupBar.removeAllViews();
        addGroupButton("全部", currentGroup.isEmpty());
        for (String group : collectGroups()) {
            addGroupButton(group, group.equals(currentGroup));
        }
        addAddButton();
    }

    private void addGroupButton(String name, boolean selected) {
        TextView button = new TextView(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ResUtil.dp2px(34));
        lp.rightMargin = ResUtil.dp2px(8);
        button.setLayoutParams(lp);
        button.setText(name);
        button.setTextSize(13);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        int padH = isTablet() ? 20 : 14;
        button.setPadding(ResUtil.dp2px(padH), ResUtil.dp2px(6), ResUtil.dp2px(padH), ResUtil.dp2px(6));
        button.setBackgroundResource(selected ? R.drawable.shape_site_group_selected : R.drawable.shape_site_group);
        button.setTextColor(0xFF141414);
        button.setClickable(true);
        button.setOnClickListener(v -> selectGroup(name));
        button.setOnLongClickListener(v -> {
            if (!"全部".equals(name)) showGroupManage(name);
            return true;
        });
        binding.groupBar.addView(button);
    }

    private void addAddButton() {
        if (collectGroups().size() >= MAX_GROUPS) return;
        TextView button = new TextView(requireContext());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ResUtil.dp2px(34));
        button.setLayoutParams(lp);
        button.setText("+");
        button.setTextSize(14);
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        int padH = isTablet() ? 18 : 12;
        button.setPadding(ResUtil.dp2px(padH), ResUtil.dp2px(6), ResUtil.dp2px(padH), ResUtil.dp2px(6));
        button.setBackgroundResource(R.drawable.shape_site_group);
        button.setTextColor(0xFF141414);
        button.setClickable(true);
        button.setOnClickListener(v -> showCreateGroup());
        binding.groupBar.addView(button);
    }

    private void selectGroup(String name) {
        currentGroup = "全部".equals(name) ? "" : name;
        adapter.setGroup(currentGroup);
        adapter.filter(binding.search.getText() == null ? "" : binding.search.getText().toString());
        renderGroups();
    }

    /** 新建分组：弹输入框，名称限 2 字。 */
    private void showCreateGroup() {
        EditText editText = new EditText(requireContext());
        editText.setHint("分组名（最多2字）");
        editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_GROUP_NAME_LEN)});
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle("新建分组")
                .setView(editText)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String name = editText.getText().toString().trim();
                    if (name.isEmpty()) return;
                    if (collectGroups().size() >= MAX_GROUPS) {
                        com.fongmi.android.tv.utils.Notify.show("最多" + MAX_GROUPS + "个分组");
                        return;
                    }
                    if (collectGroups().contains(name)) {
                        com.fongmi.android.tv.utils.Notify.show("分组已存在");
                        return;
                    }
                    addCustomGroup(name);
                    selectGroup(name);
                })
                .show();
    }

    /** 长按分组：重命名或删除。 */
    private void showGroupManage(String group) {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(group)
                .setItems(new String[]{"重命名", "删除分组"}, (dialog, which) -> {
                    if (which == 0) showRenameGroup(group);
                    else if (which == 1) deleteGroup(group);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void showRenameGroup(String group) {
        EditText editText = new EditText(requireContext());
        editText.setText(group);
        editText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_GROUP_NAME_LEN)});
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle("重命名分组")
                .setView(editText)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String name = editText.getText().toString().trim();
                    if (name.isEmpty() || name.equals(group)) return;
                    if (collectGroups().contains(name)) {
                        com.fongmi.android.tv.utils.Notify.show("分组已存在");
                        return;
                    }
                    for (Site site : VodConfig.get().getSites()) {
                        if (group.equals(site.getGroup())) {
                            site.setGroup(name);
                            site.save();
                        }
                    }
                    renameCustomGroup(group, name);
                    selectGroup(name);
                })
                .show();
    }

    private void deleteGroup(String group) {
        for (Site site : VodConfig.get().getSites()) {
            if (group.equals(site.getGroup())) {
                site.setGroup("");
                site.save();
            }
        }
        removeCustomGroup(group);
        if (group.equals(currentGroup)) selectGroup("全部");
        else renderGroups();
    }

    @Override
    public void onTextClick(Site item) {
        if (listener != null) listener.setSite(item);
        dismiss();
    }

    @Override
    public void onTextLongClick(Site item) {
        List<String> groups = collectGroups();
        if (groups.isEmpty()) {
            com.fongmi.android.tv.utils.Notify.show("请先新建分组");
            return;
        }
        String[] names = groups.toArray(new String[0]);
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle("将「" + item.getName() + "」移动到")
                .setItems(names, (dialog, which) -> {
                    item.setGroup(names[which]);
                    item.save();
                    adapter.filter(binding.search.getText() == null ? "" : binding.search.getText().toString());
                    renderGroups();
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    @Override
    public void onSearchClick(int position, Site item) {
        item.setSearchable(!item.isSearchable()).save();
        adapter.notifyItemChanged(position);
    }

    @Override
    public void onChangeClick(int position, Site item) {
        item.setChangeable(!item.isChangeable()).save();
        adapter.notifyItemChanged(position);
    }

    @Override
    public boolean onSearchLongClick(Site item) {
        boolean result = !item.isSearchable();
        adapter.getItems().forEach(site -> site.setSearchable(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }

    @Override
    public boolean onChangeLongClick(Site item) {
        boolean result = !item.isChangeable();
        adapter.getItems().forEach(site -> site.setChangeable(result).save());
        adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        return true;
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                getDialog().getWindow().setBackgroundBlurRadius(80);
            }
        }
        if (adapter.getItemCount() == 0) dismiss();
        else if (isTablet()) setWidth(0.85f);
        else if (ResUtil.isLand(requireContext())) setWidth(0.5f);
        else setWidthDp(search && change ? 344 : 320);
    }

    private void setWidthDp(int widthDp) {
        if (getDialog() == null || getDialog().getWindow() == null) return;
        int width = ResUtil.dp2px(widthDp);
        ViewGroup.LayoutParams layoutParams = binding.getRoot().getLayoutParams();
        layoutParams.width = width;
        binding.getRoot().setLayoutParams(layoutParams);
        getDialog().getWindow().setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}

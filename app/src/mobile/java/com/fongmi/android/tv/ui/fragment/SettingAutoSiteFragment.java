package com.fongmi.android.tv.ui.fragment;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.FragmentSettingAutoSiteBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.activity.HomeActivity;
import com.fongmi.android.tv.ui.adapter.AutoSiteListAdapter;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.ui.dialog.AiStatusDialog;
import com.fongmi.android.tv.utils.AiSiteStore;
import com.fongmi.android.tv.utils.AiSkillStore;
import com.fongmi.android.tv.utils.AutoSiteHelper;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.UrlUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 自动站点二级菜单页：网址输入 + AI 配置 + AI 识别 + 手动添加。
 * AI 识别结果先预览（可编辑）再应用；配置持久化落库，重启后由
 * VodConfig 回填；每次生成自动存档，可从历史回看/重应用。
 */
public class SettingAutoSiteFragment extends BaseFragment {

    private static final int MAX_SKILL_BYTES = 512 * 1024;

    private FragmentSettingAutoSiteBinding mBinding;
    private AiStatusDialog mStatusDialog;
    private AutoSiteListAdapter mAdapter;
    private String mLastUrl = "";

    private final ActivityResultLauncher<Intent> skillLauncher =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                Uri uri = result.getData() == null ? null : result.getData().getData();
                if (uri != null) importSkill(uri);
            });

    public static SettingAutoSiteFragment newInstance() {
        return new SettingAutoSiteFragment();
    }

    private HomeActivity getRoot() {
        return (HomeActivity) requireActivity();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingAutoSiteBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.aiUrl.setText(Setting.getAiUrl());
        mBinding.aiKey.setText(Setting.getAiKey());
        mBinding.aiModel.setText(Setting.getAiModel());
        mAdapter = new AutoSiteListAdapter(new AutoSiteListAdapter.OnSiteListener() {
            @Override
            public void onMenu(Site item) {
                showSiteMenu(item);
            }

            @Override
            public void onToggle(Site item, boolean enabled) {
                toggleSite(item, enabled);
            }

            @Override
            public void onDelete(Site item) {
                onDeleteSite(item);
            }
        });
        mBinding.siteList.setAdapter(mAdapter);
        mBinding.skills.setText(getString(R.string.auto_site_skills, AiSkillStore.all().size()));
        updateEmptyTip();
    }

    @Override
    protected void initEvent() {
        mBinding.toolbar.setNavigationOnClickListener(v -> getRoot().change(1));
        mBinding.url.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onConfirm();
            return true;
        });
        mBinding.aiDetect.setOnClickListener(v -> onAiDetect());
        mBinding.confirm.setOnClickListener(v -> onConfirm());
        mBinding.history.setOnClickListener(v -> showHistory());
        mBinding.skills.setOnClickListener(v -> showSkills());
    }

    private void onConfirm() {
        saveAiConfig();
        String input = mBinding.url.getText().toString().trim();
        if (TextUtils.isEmpty(input)) {
            Notify.show(R.string.auto_site_empty);
            return;
        }
        if (input.startsWith("{")) {
            addFromJson(input);
        } else {
            addFromUrl(input);
        }
    }

    private void onAiDetect() {
        saveAiConfig();
        String input = mBinding.url.getText().toString().trim();
        if (TextUtils.isEmpty(input)) {
            Notify.show(R.string.auto_site_empty);
            return;
        }
        if (!Setting.hasAiKey()) {
            Notify.show(R.string.auto_site_ai_no_key);
            return;
        }
        if (!input.startsWith("http://") && !input.startsWith("https://")) input = "https://" + input;
        String url = input;
        mLastUrl = url;
        mBinding.aiDetect.setEnabled(false);
        mStatusDialog = AiStatusDialog.show(getChildFragmentManager());
        AutoSiteHelper.get().detect(url, text -> {
            if (mStatusDialog != null) mStatusDialog.updateStatus(text);
        }, new AutoSiteHelper.Callback() {
            @Override
            public void onSuccess(String config) {
                mBinding.aiDetect.setEnabled(true);
                if (mStatusDialog != null) {
                    mStatusDialog.finish();
                    mStatusDialog.dismiss();
                    mStatusDialog = null;
                }
                if (TextUtils.isEmpty(config)) {
                    Notify.show(R.string.auto_site_ai_failed);
                    return;
                }
                AiSiteStore.addHistory(url, config);
                // 先预览（可手工微调）再应用，不再直接写入
                showPreview(getString(R.string.auto_site_preview_title), config, -1);
            }

            @Override
            public void onError(String message) {
                mBinding.aiDetect.setEnabled(true);
                if (mStatusDialog != null) {
                    mStatusDialog.finish();
                    mStatusDialog.dismiss();
                    mStatusDialog = null;
                }
                Notify.show("AI识别失败：" + message);
            }
        });
    }

    // ---------------- 结果预览（可编辑后应用） ----------------

    /**
     * JSON 预览对话框：可直接编辑配置，确认后应用；
     * historyTime >= 0 表示来自历史记录，额外提供"删除此记录"。
     */
    private void showPreview(String title, String json, long historyTime) {
        EditText input = new EditText(requireContext());
        input.setMinLines(6);
        input.setMaxLines(16);
        input.setTextSize(13);
        input.setHorizontallyScrolling(true);
        input.setText(json);
        input.setSelection(input.getText().length());
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(title)
                .setMessage(R.string.auto_site_preview_hint)
                .setView(input)
                .setPositiveButton(R.string.auto_site_apply, (d, w) -> {
                    String text = input.getText().toString().trim();
                    if (!TextUtils.isEmpty(text)) addFromJson(text);
                })
                .setNegativeButton(R.string.dialog_negative, null);
        if (historyTime >= 0) {
            builder.setNeutralButton(R.string.auto_site_history_delete, (d, w) -> {
                AiSiteStore.removeHistory(historyTime);
                Notify.show(R.string.auto_site_history_deleted);
            });
        }
        builder.show();
    }

    // ---------------- 生成历史 ----------------

    private void showHistory() {
        List<AiSiteStore.Record> records = AiSiteStore.history();
        if (records.isEmpty()) {
            Notify.show(R.string.auto_site_history_empty);
            return;
        }
        String[] labels = new String[records.size()];
        for (int i = 0; i < records.size(); i++) labels[i] = records.get(i).label();
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.auto_site_history)
                .setItems(labels, (dialog, which) -> {
                    AiSiteStore.Record record = records.get(which);
                    showPreview(getString(R.string.auto_site_preview_title), record.config, record.time);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    // ---------------- 技能管理 ----------------

    private void showSkills() {
        List<AiSkillStore.Skill> skills = AiSkillStore.all();
        String[] names = new String[skills.size()];
        boolean[] checked = new boolean[skills.size()];
        for (int i = 0; i < skills.size(); i++) {
            names[i] = skills.get(i).name;
            checked[i] = skills.get(i).enabled;
        }
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.auto_site_skills_title)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> {
                    skills.get(which).enabled = isChecked;
                    AiSkillStore.save(skills);
                    mBinding.skills.setText(getString(R.string.auto_site_skills, AiSkillStore.all().size()));
                })
                .setPositiveButton(R.string.dialog_positive, null)
                .setNeutralButton(R.string.auto_site_skill_import, (d, w) -> importSkillFile())
                .setNegativeButton(R.string.auto_site_skill_delete, (d, w) -> deleteSkill())
                .show();
    }

    private void importSkillFile() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        skillLauncher.launch(intent);
    }

    private void importSkill(Uri uri) {
        try (Cursor cursor = App.get().getContentResolver().query(uri, null, null, null, null)) {
            String name = null;
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) name = cursor.getString(index);
            }
            if (TextUtils.isEmpty(name)) name = "skill_" + System.currentTimeMillis();
            byte[] bytes = readAll(uri);
            if (bytes == null || bytes.length == 0) {
                Notify.show(R.string.auto_site_skill_failed);
                return;
            }
            if (bytes.length > MAX_SKILL_BYTES) {
                Notify.show(R.string.auto_site_skill_too_large);
                return;
            }
            AiSkillStore.add(name, new String(bytes, StandardCharsets.UTF_8));
            mBinding.skills.setText(getString(R.string.auto_site_skills, AiSkillStore.all().size()));
            Notify.show(getString(R.string.auto_site_skill_added, name));
        } catch (Exception e) {
            Notify.show(R.string.auto_site_skill_failed);
        }
    }

    private byte[] readAll(Uri uri) throws Exception {
        try (InputStream in = App.get().getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int len;
            while ((len = in.read(buffer)) > 0) out.write(buffer, 0, len);
            return out.toByteArray();
        }
    }

    private void deleteSkill() {
        List<AiSkillStore.Skill> skills = AiSkillStore.all();
        if (skills.isEmpty()) {
            Notify.show(R.string.auto_site_skill_empty);
            return;
        }
        String[] names = new String[skills.size()];
        for (int i = 0; i < skills.size(); i++) names[i] = skills.get(i).name;
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.auto_site_skill_delete)
                .setItems(names, (dialog, which) -> {
                    AiSkillStore.remove(names[which]);
                    mBinding.skills.setText(getString(R.string.auto_site_skills, AiSkillStore.all().size()));
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    // ---------------- 站点操作 ----------------

    private void saveAiConfig() {
        Setting.putAiUrl(mBinding.aiUrl.getText().toString().trim());
        Setting.putAiKey(mBinding.aiKey.getText().toString().trim());
        Setting.putAiModel(mBinding.aiModel.getText().toString().trim());
    }

    private void addFromUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        String host = UrlUtil.host(url);
        if (TextUtils.isEmpty(host)) {
            Notify.show(R.string.auto_site_empty);
            return;
        }
        String ext = url + ";;";
        if (exists(ext)) return;
        Site site = new Site();
        site.setKey("xbpq_" + host.replace(".", "_"));
        site.setName(host);
        site.setApi(AutoSiteHelper.API);
        site.setExt(ext);
        site.setJar(AutoSiteHelper.JAR);
        site.setType(3);
        save(site);
    }

    private void addFromJson(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String key = obj.has("key") ? obj.get("key").getAsString() : "";
            String name = obj.has("name") ? obj.get("name").getAsString() : "";
            String api = obj.has("api") ? obj.get("api").getAsString() : AutoSiteHelper.API;
            String ext = "";
            if (obj.has("ext")) {
                JsonElement extEl = obj.get("ext");
                ext = extEl.isJsonObject() ? App.gson().toJson(extEl.getAsJsonObject()) : extEl.getAsString();
            }
            int type = obj.has("type") ? obj.get("type").getAsInt() : 3;
            if (TextUtils.isEmpty(ext)) {
                Notify.show(R.string.auto_site_empty);
                return;
            }
            if (TextUtils.isEmpty(key)) {
                String host = UrlUtil.host(ext);
                key = "xbpq_" + host.replace(".", "_");
            }
            if (TextUtils.isEmpty(name)) {
                name = UrlUtil.host(ext);
            }
            if (exists(ext)) return;
            Site site = new Site();
            site.setKey(key);
            site.setName(name);
            site.setApi(api);
            site.setExt(ext);
            site.setJar(AutoSiteHelper.JAR);
            site.setType(type);
            save(site);
        } catch (Exception e) {
            Notify.show(R.string.auto_site_failed);
        }
    }

    private boolean exists(String ext) {
        for (Site site : VodConfig.get().getSites()) {
            if (ext.equals(site.getExt())) {
                Notify.show(R.string.auto_site_exist);
                return true;
            }
        }
        return false;
    }

    /**
     * 保存站点（AI 识别与手动添加走同一条落库路径）。
     * 按 key 去重：同一站点重复识别时更新配置而不是堆积重复条目；
     * 落库后由 VodConfig.initSite 在启动时回填 xbpq_ 前缀站点。
     */
    private void save(Site site) {
        Site exist = Site.find(site.getKey());
        if (exist != null) {
            VodConfig.get().getSites().remove(exist);
            if (mAdapter != null) mAdapter.remove(exist);
        }
        site.save();
        VodConfig.get().getSites().add(site);
        if (mAdapter != null && AutoSiteListAdapter.isAutoSite(site)) {
            mAdapter.add(site);
            updateEmptyTip();
        }
        RefreshEvent.home();
        Notify.show(exist != null ? R.string.auto_site_updated : R.string.auto_site_success);
    }

    private void onDeleteSite(Site item) {
        item.delete();
        VodConfig.get().getSites().remove(item);
        AiSiteStore.setDisabled(item.getKey(), false);
        if (mAdapter != null) mAdapter.remove(item);
        updateEmptyTip();
        RefreshEvent.home();
        Notify.show(R.string.auto_site_deleted);
    }

    /** 启停：禁用的站点保留在数据库但不出现在线路列表（VodConfig 回填时过滤）。 */
    private void toggleSite(Site item, boolean enabled) {
        AiSiteStore.setDisabled(item.getKey(), !enabled);
        if (enabled) {
            if (!VodConfig.get().getSites().contains(item)) VodConfig.get().getSites().add(item);
            mAdapter.add(item);
        } else {
            VodConfig.get().getSites().remove(item);
            mAdapter.remove(item);
        }
        RefreshEvent.home();
        updateEmptyTip();
        Notify.show(enabled ? R.string.auto_site_enabled : R.string.auto_site_disabled);
    }

    /** 行点击菜单：编辑配置 / 导出 JSON / 删除。 */
    private void showSiteMenu(Site item) {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(item.getName())
                .setItems(new CharSequence[]{getString(R.string.auto_site_edit), getString(R.string.auto_site_export), getString(R.string.auto_site_delete)}, (dialog, which) -> {
                    if (which == 0) editSite(item);
                    else if (which == 1) exportSite(item);
                    else onDeleteSite(item);
                })
                .show();
    }

    private void editSite(Site item) {
        EditText input = new EditText(requireContext());
        input.setMinLines(4);
        input.setMaxLines(12);
        input.setTextSize(13);
        input.setText(item.getExt());
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.auto_site_edit)
                .setView(input)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                    String ext = input.getText().toString().trim();
                    if (TextUtils.isEmpty(ext)) return;
                    item.setExt(ext);
                    item.save();
                    RefreshEvent.home();
                    Notify.show(R.string.auto_site_updated);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void exportSite(Site item) {
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("key", item.getKey());
            obj.addProperty("name", item.getName());
            obj.addProperty("api", item.getApi());
            obj.addProperty("type", item.getType());
            obj.addProperty("jar", item.getJar());
            try {
                obj.add("ext", JsonParser.parseString(item.getExt()));
            } catch (Exception e) {
                obj.addProperty("ext", item.getExt());
            }
            File dir = new File(Environment.getExternalStorageDirectory(), "Download");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, item.getKey() + ".json");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(App.gson().toJson(obj).getBytes(StandardCharsets.UTF_8));
            }
            Notify.show(getString(R.string.auto_site_exported, out.getAbsolutePath()));
        } catch (Exception e) {
            Notify.show(R.string.auto_site_export_failed);
        }
    }

    private void updateEmptyTip() {
        if (mAdapter == null) return;
        mBinding.emptyTip.setVisibility(mAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
    }
}

package com.fongmi.android.tv.ui.fragment;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;

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
import com.fongmi.android.tv.utils.AutoSiteHelper;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.UrlUtil;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 自动站点二级菜单页：网址输入 + AI Key 输入 + AI 识别（独立状态弹窗）+ 手动添加。
 */
public class SettingAutoSiteFragment extends BaseFragment {

    private FragmentSettingAutoSiteBinding mBinding;
    private AiStatusDialog mStatusDialog;
    private AutoSiteListAdapter mAdapter;

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
        mBinding.aiKey.setText(Setting.getAiKey());
        mAdapter = new AutoSiteListAdapter(this::onDeleteSite);
        mBinding.siteList.setAdapter(mAdapter);
        updateEmptyTip();
    }

    private void onDeleteSite(Site item) {
        item.delete();
        VodConfig.get().getSites().remove(item);
        if (mAdapter != null) mAdapter.remove(item);
        updateEmptyTip();
        RefreshEvent.home();
        Notify.show(R.string.auto_site_deleted);
    }

    private void updateEmptyTip() {
        if (mAdapter == null) return;
        mBinding.emptyTip.setVisibility(mAdapter.getItemCount() == 0 ? View.VISIBLE : View.GONE);
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
    }

    private void onConfirm() {
        saveAiKey();
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
        saveAiKey();
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
                mBinding.url.setText("");
                addFromJson(config);
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

    private void saveAiKey() {
        Setting.putAiKey(mBinding.aiKey.getText().toString().trim());
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

    private void save(Site site) {
        site.save();
        VodConfig.get().getSites().add(site);
        if (mAdapter != null && AutoSiteListAdapter.isAutoSite(site)) {
            mAdapter.add(site);
            updateEmptyTip();
        }
        RefreshEvent.home();
        Notify.show(R.string.auto_site_success);
    }
}

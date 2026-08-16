package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogXbpqBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.UrlUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class XbpqDialog extends BaseAlertDialog {

    private static final String JAR = "assets://1118.jar";
    private static final String API = "csp_XBPQ";

    private DialogXbpqBinding binding;

    public static void show(Fragment fragment) {
        new XbpqDialog().show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogXbpqBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.url.requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.url.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onConfirm();
            return true;
        });
        binding.cancel.setOnClickListener(v -> dismiss());
        binding.confirm.setOnClickListener(v -> onConfirm());
    }

    private void onConfirm() {
        String url = binding.url.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            Notify.show(R.string.auto_site_empty);
            return;
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        String host = UrlUtil.host(url);
        if (TextUtils.isEmpty(host)) {
            Notify.show(R.string.auto_site_empty);
            return;
        }
        String ext = url + ";;";
        for (Site site : VodConfig.get().getSites()) {
            if (ext.equals(site.getExt())) {
                Notify.show(R.string.auto_site_exist);
                dismiss();
                return;
            }
        }
        Site site = new Site();
        site.setKey("xbpq_" + host.replace(".", "_"));
        site.setName(host);
        site.setApi(API);
        site.setExt(ext);
        site.setJar(JAR);
        site.setType(3);
        site.save();
        VodConfig.get().getSites().add(site);
        RefreshEvent.home();
        Notify.show(R.string.auto_site_success);
        dismiss();
    }
}

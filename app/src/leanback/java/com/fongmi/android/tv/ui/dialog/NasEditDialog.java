package com.fongmi.android.tv.ui.dialog;

import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogNasBinding;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class NasEditDialog extends BaseAlertDialog {

    private DialogNasBinding binding;
    private Site site;
    private Callback callback;

    private String protocol = "SMB";
    private String name = "";
    private String host = "";
    private String port = "445";
    private String path = "";
    private String user = "";
    private String pass = "";

    public interface Callback {
        void onNasSaved();
    }

    public static NasEditDialog create() {
        return new NasEditDialog();
    }

    public NasEditDialog edit(Site site) {
        this.site = site;
        if (site != null) {
            this.name = site.getName();
            parseExt(site.getExt());
        }
        return this;
    }

    public NasEditDialog setCallback(Callback callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogNasBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.title.setText(site == null ? R.string.nas_title_add : R.string.nas_title_edit);
        binding.protocol.setText(protocol);
        binding.name.setText(name);
        binding.host.setText(host);
        binding.port.setText(port);
        binding.path.setText(path);
        binding.user.setText(user);
        binding.pass.setText(pass);
        
        binding.name.setSelection(name.length());
    }

    @Override
    protected void initEvent() {
        binding.protocol.setOnClickListener(this::toggleProtocol);
        binding.positive.setOnClickListener(this::onPositive);
        binding.negative.setOnClickListener(this::onNegative);
        
        binding.pass.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                binding.positive.performClick();
                return true;
            }
            return false;
        });
    }

    private void toggleProtocol(View view) {
        if ("SMB".equalsIgnoreCase(protocol)) {
            protocol = "WebDAV";
            if ("445".equals(binding.port.getText().toString().trim()) || TextUtils.isEmpty(binding.port.getText().toString().trim())) {
                binding.port.setText("80");
            }
        } else {
            protocol = "SMB";
            if ("80".equals(binding.port.getText().toString().trim()) || "443".equals(binding.port.getText().toString().trim()) || TextUtils.isEmpty(binding.port.getText().toString().trim())) {
                binding.port.setText("445");
            }
        }
        binding.protocol.setText(protocol);
    }

    private void parseExt(String ext) {
        if (TextUtils.isEmpty(ext)) return;
        try {
            Uri uri = Uri.parse(ext);
            String scheme = uri.getScheme();
            if ("smb".equalsIgnoreCase(scheme)) {
                protocol = "SMB";
            } else {
                protocol = "WebDAV";
            }

            String userInfo = uri.getUserInfo();
            if (!TextUtils.isEmpty(userInfo)) {
                int colonIdx = userInfo.indexOf(':');
                if (colonIdx != -1) {
                    user = userInfo.substring(0, colonIdx);
                    pass = userInfo.substring(colonIdx + 1);
                } else {
                    user = userInfo;
                }
            }

            host = uri.getHost();
            int portVal = uri.getPort();
            if (portVal != -1) {
                port = String.valueOf(portVal);
            } else {
                port = "";
            }

            path = uri.getPath();
            if (path != null && path.startsWith("/")) {
                path = path.substring(1);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void onPositive(View view) {
        String nameInput = binding.name.getText().toString().trim();
        String hostInput = binding.host.getText().toString().trim();
        String portInput = binding.port.getText().toString().trim();
        String pathInput = binding.path.getText().toString().trim();
        String userInput = binding.user.getText().toString().trim();
        String passInput = binding.pass.getText().toString().trim();

        if (TextUtils.isEmpty(nameInput) || TextUtils.isEmpty(hostInput)) {
            Notify.show(ResUtil.getString(R.string.nas_empty_fields));
            return;
        }

        StringBuilder extUrl = new StringBuilder();
        if ("SMB".equalsIgnoreCase(protocol)) {
            extUrl.append("smb://");
        } else {
            if (!hostInput.startsWith("http://") && !hostInput.startsWith("https://")) {
                extUrl.append("http://");
            }
        }

        if (!TextUtils.isEmpty(userInput)) {
            extUrl.append(userInput);
            if (!TextUtils.isEmpty(passInput)) {
                extUrl.append(":").append(passInput);
            }
            extUrl.append("@");
        }

        String cleanHost = hostInput.replace("smb://", "").replace("http://", "").replace("https://", "");
        extUrl.append(cleanHost);

        if (!TextUtils.isEmpty(portInput)) {
            extUrl.append(":").append(portInput);
        } else if ("SMB".equalsIgnoreCase(protocol)) {
            extUrl.append(":445");
        }

        if (!TextUtils.isEmpty(pathInput)) {
            if (!pathInput.startsWith("/")) extUrl.append("/");
            extUrl.append(pathInput);
        }

        Site targetSite = site != null ? site : new Site();
        if (site == null) {
            targetSite.setKey("local_nas_" + System.currentTimeMillis());
        }
        targetSite.setName(nameInput);
        targetSite.setType(3);
        targetSite.setApi("SMB".equalsIgnoreCase(protocol) ? "csp_Smb" : "csp_WebDav");
        targetSite.setExt(extUrl.toString());
        targetSite.save();

        if (callback != null) {
            callback.onNasSaved();
        }
        dismiss();
    }

    private void onNegative(View view) {
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.55f);
    }
}

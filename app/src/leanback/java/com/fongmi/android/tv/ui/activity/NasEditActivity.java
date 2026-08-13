package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityNasEditBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.NasInputDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

public class NasEditActivity extends BaseActivity {

    private ActivityNasEditBinding binding;
    private Site site;
    private String siteKey;

    private String protocol = "SMB";
    private String name = "";
    private String host = "";
    private String port = "445";
    private String path = "";
    private String user = "";
    private String pass = "";
    private String style = "list";

    public static void start(Activity activity) {
        start(activity, null);
    }

    public static void start(Activity activity, String siteKey) {
        start(activity, siteKey, null);
    }

    public static void start(Activity activity, String siteKey, String protocol) {
        Intent intent = new Intent(activity, NasEditActivity.class);
        if (siteKey != null) {
            intent.putExtra("siteKey", siteKey);
        }
        if (protocol != null) intent.putExtra("protocol", protocol);
        activity.startActivity(intent);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = ActivityNasEditBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        siteKey = getIntent().getStringExtra("siteKey");
        if (siteKey != null) {
            site = Site.find(siteKey);
            if (site != null) {
                name = site.getName();
                parseExt(site.getExt());
            }
            binding.title.setText(R.string.nas_title_edit);
        } else {
            String value = getIntent().getStringExtra("protocol");
            if (!TextUtils.isEmpty(value)) {
                protocol = value;
                port = "WebDAV".equalsIgnoreCase(protocol) ? "80" : "445";
            }
            binding.title.setText(R.string.nas_title_add);
        }
        refreshUi();
    }

    @Override
    protected void initEvent() {
        binding.rowProtocol.setOnClickListener(v -> toggleProtocol());
        binding.rowName.setOnClickListener(v -> editName());
        binding.rowHost.setOnClickListener(v -> editHost());
        binding.rowPort.setOnClickListener(v -> editPort());
        binding.rowPath.setOnClickListener(v -> editPath());
        binding.rowUser.setOnClickListener(v -> editUser());
        binding.rowPass.setOnClickListener(v -> editPass());
        binding.rowStyle.setOnClickListener(v -> toggleStyle());
        binding.positive.setOnClickListener(v -> onSave());
        binding.negative.setOnClickListener(v -> finish());
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
            // Parse style from query parameter
            try {
                Uri extUri = Uri.parse(ext);
                String styleParam = extUri.getQueryParameter("style");
                if (!TextUtils.isEmpty(styleParam)) {
                    style = styleParam;
                }
            } catch (Exception ignored) {}
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void refreshUi() {
        binding.protocol.setText(protocol);
        binding.name.setText(name);
        binding.host.setText(host);
        binding.port.setText(port);
        binding.path.setText(path);
        binding.user.setText(user);
        binding.pass.setText(TextUtils.isEmpty(pass) ? "" : "******");
        binding.style.setText(getStyleLabel());
    }

    private String getStyleLabel() {
        switch (style) {
            case "rect": return ResUtil.getString(R.string.nas_style_rect);
            case "oval": return ResUtil.getString(R.string.nas_style_oval);
            default:     return ResUtil.getString(R.string.nas_style_list);
        }
    }

    private void toggleProtocol() {
        if ("SMB".equalsIgnoreCase(protocol)) {
            protocol = "WebDAV";
            if ("445".equals(port) || TextUtils.isEmpty(port)) {
                port = "80";
            }
        } else {
            protocol = "SMB";
            if ("80".equals(port) || "443".equals(port) || TextUtils.isEmpty(port)) {
                port = "445";
            }
        }
        refreshUi();
    }

    private void toggleStyle() {
        switch (style) {
            case "list": style = "rect"; break;
            case "rect": style = "oval"; break;
            default:     style = "list"; break;
        }
        refreshUi();
    }

    private void editName() {
        NasInputDialog.create()
                .title(ResUtil.getString(R.string.nas_name))
                .text(name)
                .callback(value -> {
                    name = value;
                    refreshUi();
                })
                .show(this);
    }

    private void editHost() {
        NasInputDialog.create()
                .title(ResUtil.getString(R.string.nas_host))
                .text(host)
                .callback(value -> {
                    host = value;
                    refreshUi();
                })
                .show(this);
    }

    private void editPort() {
        NasInputDialog.create()
                .title(ResUtil.getString(R.string.nas_port))
                .text(port)
                .callback(value -> {
                    port = value;
                    refreshUi();
                })
                .show(this);
    }

    private void editPath() {
        NasInputDialog.create()
                .title(ResUtil.getString(R.string.nas_path))
                .text(path)
                .callback(value -> {
                    path = value;
                    refreshUi();
                })
                .show(this);
    }

    private void editUser() {
        NasInputDialog.create()
                .title(ResUtil.getString(R.string.nas_user))
                .text(user)
                .callback(value -> {
                    user = value;
                    refreshUi();
                })
                .show(this);
    }

    private void editPass() {
        NasInputDialog.create()
                .title(ResUtil.getString(R.string.nas_pass))
                .text(pass)
                .isPassword(true)
                .callback(value -> {
                    pass = value;
                    refreshUi();
                })
                .show(this);
    }

    private void onSave() {
        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(host)) {
            Notify.show(ResUtil.getString(R.string.nas_empty_fields));
            return;
        }

        StringBuilder extUrl = new StringBuilder();
        if ("SMB".equalsIgnoreCase(protocol)) {
            extUrl.append("smb://");
        } else {
            if (!host.startsWith("http://") && !host.startsWith("https://")) {
                extUrl.append("http://");
            }
        }

        if (!TextUtils.isEmpty(userInputHasValue())) {
            extUrl.append(user);
            if (!TextUtils.isEmpty(pass)) {
                extUrl.append(":").append(pass);
            }
            extUrl.append("@");
        }

        String cleanHost = host.replace("smb://", "").replace("http://", "").replace("https://", "");
        extUrl.append(cleanHost);

        if (!TextUtils.isEmpty(port)) {
            extUrl.append(":").append(port);
        } else if ("SMB".equalsIgnoreCase(protocol)) {
            extUrl.append(":445");
        }

        if (!TextUtils.isEmpty(path)) {
            if (!path.startsWith("/")) extUrl.append("/");
            extUrl.append(path);
        }

        // Append the view style as a query parameter
        extUrl.append("?style=").append(style);

        Site targetSite = site != null ? site : new Site();
        if (site == null) {
            targetSite.setKey("local_nas_" + System.currentTimeMillis());
        }
        targetSite.setName(name);
        targetSite.setType(3);
        targetSite.setApi("SMB".equalsIgnoreCase(protocol) ? "csp_Smb" : "csp_WebDav");
        targetSite.setExt(extUrl.toString());
        targetSite.save();

        String spider = "";
        for (Site siteItem : VodConfig.get().getSites()) {
            if (!"local_file_system".equals(siteItem.getKey()) && (siteItem.getKey() == null || !siteItem.getKey().startsWith("local_nas_")) && !TextUtils.isEmpty(siteItem.getJar())) {
                spider = siteItem.getJar();
                break;
            }
        }
        if (TextUtils.isEmpty(targetSite.getJar())) {
            targetSite.setJar(spider);
        }

        if (!VodConfig.get().getSites().contains(targetSite)) {
            VodConfig.get().getSites().add(targetSite);
        } else {
            int idx = VodConfig.get().getSites().indexOf(targetSite);
            if (idx != -1) {
                VodConfig.get().getSites().set(idx, targetSite);
            }
        }

        finish();
    }

    private String userInputHasValue() {
        return user;
    }
}

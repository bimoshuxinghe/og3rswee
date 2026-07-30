package com.fongmi.android.tv.ui.dialog;

import android.net.Uri;
import android.text.TextUtils;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogNasBinding;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import okhttp3.Credentials;
import okhttp3.Request;
import okhttp3.Response;

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

    public void show(Fragment fragment) {
        show(fragment.getChildFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogNasBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder()
                .setView(getBinding().getRoot())
                .setPositiveButton(site == null ? R.string.dialog_positive : R.string.dialog_edit, (dialog, which) -> onPositive())
                .setNegativeButton(R.string.dialog_negative, null);
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
        binding.test.setOnClickListener(this::onTest);
    }

    private void onTest(View view) {
        String hostInput = binding.host.getText().toString().trim();
        String portInput = binding.port.getText().toString().trim();
        String userInput = binding.user.getText().toString().trim();
        String passInput = binding.pass.getText().toString().trim();

        if (TextUtils.isEmpty(hostInput)) {
            Notify.show(R.string.nas_empty_fields);
            return;
        }

        Notify.show(R.string.sync_connecting);

        if ("SMB".equalsIgnoreCase(protocol)) {
            testSmb(hostInput, portInput, userInput, passInput);
        } else {
            testWebDav(hostInput, portInput, userInput, passInput);
        }
    }

    private void testSmb(String hostInput, String portInput, String userInput, String passInput) {
        new Thread(() -> {
            try {
                String cleanHost = hostInput.replace("smb://", "");
                int port = TextUtils.isEmpty(portInput) ? 445 : Integer.parseInt(portInput);
                com.hierynomus.smbj.SMBClient client = new com.hierynomus.smbj.SMBClient();
                com.hierynomus.smbj.connection.Connection connection = client.connect(cleanHost, port);
                String user = TextUtils.isEmpty(userInput) ? "guest" : userInput;
                String pass = passInput == null ? "" : passInput;
                com.hierynomus.smbj.session.Session session = connection.authenticate(
                        new com.hierynomus.smbj.auth.AuthenticationContext(user, pass.toCharArray(), ""));
                session.close();
                connection.close();
                client.close();
                App.post(() -> Notify.show(R.string.sync_test_success));
            } catch (Exception e) {
                e.printStackTrace();
                App.post(() -> Notify.show(R.string.sync_test_fail));
            }
        }).start();
    }

    private void testWebDav(String hostInput, String portInput, String userInput, String passInput) {
        new Thread(() -> {
            try {
                StringBuilder urlBuilder = new StringBuilder();
                
                // 处理协议
                String scheme = "http://";
                String hostPart = hostInput;
                if (hostInput.startsWith("https://")) {
                    scheme = "https://";
                    hostPart = hostInput.substring(8);
                } else if (hostInput.startsWith("http://")) {
                    hostPart = hostInput.substring(7);
                }
                
                // 分离 host 和 path
                int pathIndex = hostPart.indexOf('/');
                String host = pathIndex != -1 ? hostPart.substring(0, pathIndex) : hostPart;
                String path = pathIndex != -1 ? hostPart.substring(pathIndex) : "";
                
                // 构建 URL
                urlBuilder.append(scheme).append(host);
                if (!TextUtils.isEmpty(portInput) && !"80".equals(portInput) && !"443".equals(portInput)) {
                    urlBuilder.append(":").append(portInput);
                }
                urlBuilder.append(path);
                if (!path.endsWith("/")) {
                    urlBuilder.append("/");
                }

                String url = urlBuilder.toString();
                Request.Builder requestBuilder = new Request.Builder().url(url);
                if (!TextUtils.isEmpty(userInput)) {
                    String credentials = Credentials.basic(userInput, passInput == null ? "" : passInput);
                    requestBuilder.header("Authorization", credentials);
                }
                Response response = OkHttp.client(5000).newCall(requestBuilder.build()).execute();
                int code = response.code();
                response.close();
                if (code >= 200 && code < 500) {
                    App.post(() -> Notify.show(R.string.sync_test_success));
                } else {
                    App.post(() -> Notify.show(R.string.sync_test_fail));
                }
            } catch (Exception e) {
                e.printStackTrace();
                App.post(() -> Notify.show(R.string.sync_test_fail));
            }
        }).start();
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

    private void onPositive() {
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
}

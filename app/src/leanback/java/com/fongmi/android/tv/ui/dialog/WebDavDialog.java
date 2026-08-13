package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.View;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogWebdavBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.WebDavSync;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class WebDavDialog extends BaseAlertDialog {

    private DialogWebdavBinding binding;
    private Callback callback;

    public static WebDavDialog create() {
        return new WebDavDialog();
    }

    public WebDavDialog callback(Callback callback) {
        this.callback = callback;
        return this;
    }

    public static void show(FragmentActivity activity, Callback callback) {
        create().callback(callback).show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogWebdavBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        setWidth(0.55f);
        binding.url.setText(Setting.getSyncUrl());
        binding.user.setText(Setting.getSyncUser());
        binding.pass.setText(Setting.getSyncPass());
        updateBackupText();
        updateSyncText();
    }

    private void updateBackupText() {
        int interval = Setting.getSyncInterval();
        String text = interval == 0 ? ResUtil.getString(R.string.setting_off) : interval + "s";
        binding.autoBackupText.setText(text);
    }

    private void updateSyncText() {
        binding.autoSyncText.setText(ResUtil.getString(Setting.isSyncAutoSync() ? R.string.setting_on : R.string.setting_off));
    }

    @Override
    protected void initEvent() {
        binding.autoBackup.setOnClickListener(view -> {
            int[] intervals = {0, 10, 30, 60};
            int current = Setting.getSyncInterval();
            int index = 0;
            for (int i = 0; i < intervals.length; i++) {
                if (intervals[i] == current) {
                    index = (i + 1) % intervals.length;
                    break;
                }
            }
            Setting.putSyncInterval(intervals[index]);
            updateBackupText();
        });
        binding.autoSync.setOnClickListener(view -> {
            Setting.putSyncAutoSync(!Setting.isSyncAutoSync());
            updateSyncText();
        });
        binding.test.setOnClickListener(this::onTest);
        binding.backup.setOnClickListener(this::onBackup);
        binding.restore.setOnClickListener(this::onRestore);
    }

    private void onTest(View view) {
        String url = binding.url.getText().toString().trim();
        String user = binding.user.getText().toString().trim();
        String pass = binding.pass.getText().toString().trim();
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            Notify.show(R.string.sync_empty);
            return;
        }
        Notify.show(R.string.sync_connecting);
        WebDavSync.test(url, user, pass, new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.sync_test_success);
                Setting.putSyncUrl(url);
                Setting.putSyncUser(user);
                Setting.putSyncPass(pass);
            }

            @Override
            public void error() {
                Notify.show(R.string.sync_test_fail);
            }
        });
    }

    private void onBackup(View view) {
        String url = binding.url.getText().toString().trim();
        String user = binding.user.getText().toString().trim();
        String pass = binding.pass.getText().toString().trim();
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            Notify.show(R.string.sync_empty);
            return;
        }
        Setting.putSyncUrl(url);
        Setting.putSyncUser(user);
        Setting.putSyncPass(pass);
        Notify.show(R.string.sync_syncing);
        WebDavSync.upload(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.sync_success);
            }

            @Override
            public void error() {
                Notify.show(R.string.sync_fail);
            }
        });
    }

    private void onRestore(View view) {
        String url = binding.url.getText().toString().trim();
        String user = binding.user.getText().toString().trim();
        String pass = binding.pass.getText().toString().trim();
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            Notify.show(R.string.sync_empty);
            return;
        }
        Setting.putSyncUrl(url);
        Setting.putSyncUser(user);
        Setting.putSyncPass(pass);
        Notify.show(R.string.sync_syncing);
        WebDavSync.download(new Callback() {
            @Override
            public void success() {
                Notify.show(R.string.sync_success);
                if (callback != null) callback.success();
                dismiss();
            }

            @Override
            public void error() {
                Notify.show(R.string.sync_fail);
            }
        });
    }
}

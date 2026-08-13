package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogWebdavBinding;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.WebDavSync;

public class WebDavDialog extends BaseBottomSheetDialog {

    private DialogWebdavBinding binding;
    private Callback callback;

    public static WebDavDialog create() {
        return new WebDavDialog();
    }

    public WebDavDialog callback(Callback callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity, Callback callback) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof WebDavDialog) return;
        create().callback(callback).show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogWebdavBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.url.setText(Setting.getSyncUrl());
        binding.user.setText(Setting.getSyncUser());
        binding.pass.setText(Setting.getSyncPass());
        updateBackupText();
        binding.autoSync.setChecked(Setting.isSyncAutoSync());
    }

    private void updateBackupText() {
        int interval = Setting.getSyncInterval();
        String text = interval == 0 ? ResUtil.getString(R.string.setting_off) : interval + "s";
        binding.autoBackupText.setText(text);
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
        binding.autoSync.setOnCheckedChangeListener((buttonView, isChecked) -> Setting.putSyncAutoSync(isChecked));
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

            @Override
            public void error(String msg) {
                Notify.show(getString(R.string.sync_test_fail) + ": " + msg);
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

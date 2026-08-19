package com.fongmi.android.tv.ui.fragment;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingAdblockBinding;
import com.fongmi.android.tv.player.AdProbeManager;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class SettingAdblockFragment extends BaseFragment {

    private FragmentSettingAdblockBinding mBinding;

    public static SettingAdblockFragment newInstance() {
        return new SettingAdblockFragment();
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingAdblockBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        mBinding.adblockText.setText(getSwitch(Setting.isAdblock()));
        mBinding.aiAdblockText.setText(getSwitch(Setting.isAiAdblock()));
        updateSkipModeText();
        updateRulesPathText();
    }

    @Override
    protected void initEvent() {
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.aiAdblock.setOnClickListener(this::setAiAdblock);
        mBinding.adSkipMode.setOnClickListener(this::cycleSkipMode);
        mBinding.adRulesUrl.setOnClickListener(view -> showRulesPathDialog());
    }

    private void updateSkipModeText() {
        int mode = Setting.getAdSkipMode();
        int resId;
        if (mode == Setting.AD_SKIP_MODE_NOTICE) {
            resId = R.string.ad_skip_mode_notice;
        } else if (mode == Setting.AD_SKIP_MODE_SKIP_ONLY) {
            resId = R.string.ad_skip_mode_skip_only;
        } else {
            resId = R.string.ad_skip_mode_notice_and_skip;
        }
        mBinding.adSkipModeText.setText(resId);
    }

    private void cycleSkipMode(View view) {
        int mode = Setting.getAdSkipMode();
        if (mode == Setting.AD_SKIP_MODE_NOTICE) {
            Setting.putAdSkipMode(Setting.AD_SKIP_MODE_NOTICE_AND_SKIP);
        } else if (mode == Setting.AD_SKIP_MODE_NOTICE_AND_SKIP) {
            Setting.putAdSkipMode(Setting.AD_SKIP_MODE_SKIP_ONLY);
        } else {
            Setting.putAdSkipMode(Setting.AD_SKIP_MODE_NOTICE);
        }
        updateSkipModeText();
    }

    private void setAdblock(View view) {
        Setting.putAdblock(!Setting.isAdblock());
        mBinding.adblockText.setText(getSwitch(Setting.isAdblock()));
    }

    private void setAiAdblock(View view) {
        boolean enabled = !Setting.isAiAdblock();
        Setting.putAiAdblock(enabled);
        mBinding.aiAdblockText.setText(getSwitch(enabled));
        AdProbeManager.get().setEnabled(enabled, requireContext());
    }

    private void updateRulesPathText() {
        String path = Setting.getAdRulesPath();
        // 显示简短路径（如果太长则只显示文件名）
        String display = TextUtils.isEmpty(path) ? getString(R.string.ad_rules_path_empty)
                : (path.length() > 60 ? "..." + path.substring(path.length() - 57) : path);
        mBinding.adRulesUrlText.setText(display);
    }

    private void showRulesPathDialog() {
        EditText editText = new EditText(requireContext());
        editText.setText(Setting.getAdRulesPath());
        editText.setHint(R.string.ad_rules_path_hint);
        editText.setSingleLine(false);
        editText.setMinLines(2);
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.ad_rules_path_title)
                .setView(editText)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String path = editText.getText().toString().trim();
                    AdProbeManager.get().setRulesPath(path);  // 内部会自动 putAdRulesPath + reload
                    updateRulesPathText();
                    Notify.show(R.string.ad_rules_path_saved);
                })
                .show();
    }
}

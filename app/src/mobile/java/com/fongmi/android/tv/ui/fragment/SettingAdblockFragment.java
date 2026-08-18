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
        updateRulesUrlText();
    }

    @Override
    protected void initEvent() {
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.aiAdblock.setOnClickListener(this::setAiAdblock);
        mBinding.adRulesUrl.setOnClickListener(view -> showRulesUrlDialog());
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

    private void updateRulesUrlText() {
        String url = Setting.getAdRulesUrl();
        mBinding.adRulesUrlText.setText(TextUtils.isEmpty(url) ? getString(R.string.ad_rules_url_empty) : url);
    }

    private void showRulesUrlDialog() {
        EditText editText = new EditText(requireContext());
        editText.setText(Setting.getAdRulesUrl());
        editText.setHint(R.string.ad_rules_url_hint);
        editText.setSingleLine(true);
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.ad_rules_url_title)
                .setView(editText)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String url = editText.getText().toString().trim();
                    Setting.putAdRulesUrl(url);
                    AdProbeManager.get().setRulesUrl(url, requireContext());
                    updateRulesUrlText();
                    Notify.show(R.string.ad_rules_url_saved);
                })
                .show();
    }
}

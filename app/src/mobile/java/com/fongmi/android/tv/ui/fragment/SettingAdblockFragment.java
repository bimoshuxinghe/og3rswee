package com.fongmi.android.tv.ui.fragment;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingAdblockBinding;
import com.fongmi.android.tv.player.vosk.VoskAdblock;
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
        mBinding.aiAdblockKeywordsText.setText(Setting.getAiAdblockKeywords());
        mBinding.aiAdblockSkipSecondsText.setText(String.valueOf(Setting.getAiAdblockSkipSeconds()));
        mBinding.aiAdblockModelStatusText.setText(getModelStatus());
    }

    @Override
    protected void initEvent() {
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.aiAdblock.setOnClickListener(this::setAiAdblock);
        mBinding.aiAdblock.setOnLongClickListener(view -> {
            editAiAdblockKeywords();
            return true;
        });
        mBinding.aiAdblockKeywords.setOnClickListener(view -> editAiAdblockKeywords());
        mBinding.aiAdblockKeywords.setOnLongClickListener(view -> {
            showAiAdblockModelStatus();
            return true;
        });
        mBinding.aiAdblockSkipSeconds.setOnClickListener(view -> showAiAdblockSkipSecondsDialog());
        mBinding.aiAdblockModelStatus.setOnClickListener(view -> showAiAdblockModelStatus());
    }

    private void setAdblock(View view) {
        Setting.putAdblock(!Setting.isAdblock());
        mBinding.adblockText.setText(getSwitch(Setting.isAdblock()));
    }

    private void setAiAdblock(View view) {
        boolean enabled = !Setting.isAiAdblock();
        Setting.putAiAdblock(enabled);
        mBinding.aiAdblockText.setText(getSwitch(enabled));
        VoskAdblock vosk = VoskAdblock.get();
        vosk.setEnabled(enabled);
        if (enabled && !vosk.isModelDownloaded()) {
            vosk.downloadModel((success, error) -> {
                Notify.dismiss();
                Notify.show(success ? R.string.ai_adblock_downloaded : R.string.ai_adblock_download_failed);
            });
        }
    }

    private void editAiAdblockKeywords() {
        android.widget.EditText editText = new android.widget.EditText(requireContext());
        editText.setText(Setting.getAiAdblockKeywords());
        editText.setHint(R.string.ai_adblock_keywords_hint);
        editText.setSingleLine(false);
        editText.setMinLines(3);
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.ai_adblock_keywords_title)
                .setView(editText)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String keywords = editText.getText().toString().trim();
                    Setting.putAiAdblockKeywords(keywords);
                    mBinding.aiAdblockKeywordsText.setText(keywords);
                    Notify.show(R.string.ai_adblock_keywords_saved);
                })
                .show();
    }

    private void showAiAdblockSkipSecondsDialog() {
        android.widget.EditText editText = new android.widget.EditText(requireContext());
        editText.setText(String.valueOf(Setting.getAiAdblockSkipSeconds()));
        editText.setHint(R.string.ai_adblock_skip_seconds_hint);
        editText.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.ai_adblock_skip_seconds_title)
                .setView(editText)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    String value = editText.getText().toString().trim();
                    try {
                        int seconds = Integer.parseInt(value);
                        if (seconds <= 0) {
                            Notify.show(R.string.ai_adblock_skip_seconds_invalid);
                            return;
                        }
                        Setting.putAiAdblockSkipSeconds(seconds);
                        mBinding.aiAdblockSkipSecondsText.setText(String.valueOf(seconds));
                        Notify.show(R.string.ai_adblock_skip_seconds_saved);
                    } catch (NumberFormatException e) {
                        Notify.show(R.string.ai_adblock_skip_seconds_invalid);
                    }
                })
                .show();
    }

    private String getModelStatus() {
        VoskAdblock vosk = VoskAdblock.get();
        if (!vosk.isModelDownloaded()) {
            return getString(R.string.ai_adblock_model_not_downloaded);
        } else if (vosk.isReady()) {
            return getString(R.string.ai_adblock_model_ready);
        } else {
            return getString(R.string.ai_adblock_model_not_ready);
        }
    }

    private void showAiAdblockModelStatus() {
        String status = getModelStatus();
        String text = VoskAdblock.get().getLastRecognizedText();
        if (!TextUtils.isEmpty(text)) status += "\n" + getString(R.string.ai_adblock_recognized, text);
        Notify.show(status);
    }
}

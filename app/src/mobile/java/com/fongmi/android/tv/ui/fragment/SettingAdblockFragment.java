package com.fongmi.android.tv.ui.fragment;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

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
    private boolean mDownloading;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mStatusRunnable = new Runnable() {
        @Override
        public void run() {
            if (mBinding == null || isDetached() || isRemoving()) return;
            mBinding.aiAdblockModelStatusText.setText(getModelStatus());
            updateModelDownloadUi();
            // 模型异步加载中，持续刷新直到就绪
            if (Setting.isAiAdblock() && !VoskAdblock.get().isReady()) {
                mHandler.postDelayed(this, 1000);
            }
        }
    };

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
        updateModelDownloadUi();
        // 兜底：AI 去广已开启且模型已下载但未就绪时，主动触发加载，避免需手动开关才就绪
        if (Setting.isAiAdblock() && VoskAdblock.get().isModelDownloaded() && !VoskAdblock.get().isReady()) {
            VoskAdblock.get().setEnabled(true);
        }
        mHandler.post(mStatusRunnable);
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
        mBinding.aiAdblockModelStatus.setOnClickListener(view -> {
            mBinding.aiAdblockModelStatusText.setText(getModelStatus());
            showAiAdblockModelStatus();
        });
        mBinding.aiAdblockModelDownload.setOnClickListener(view -> startModelDownload());
    }

    @Override
    public void onDestroyView() {
        mHandler.removeCallbacks(mStatusRunnable);
        super.onDestroyView();
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
        if (enabled) {
            mHandler.post(mStatusRunnable);
            if (!vosk.isModelDownloaded()) {
                startModelDownload();
            }
        }
    }

    private void updateModelDownloadUi() {
        if (mBinding == null) return;
        boolean downloaded = VoskAdblock.get().isModelDownloaded();
        mBinding.aiAdblockModelDownload.setVisibility(downloaded ? View.GONE : View.VISIBLE);
    }

    private void startModelDownload() {
        if (mDownloading) return;
        mDownloading = true;
        if (mBinding != null) {
            mBinding.aiAdblockModelDownload.setVisibility(View.GONE);
        }
        // 弹出下载进度对话框
        ProgressBar progressBar = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(true);
        TextView progressText = new TextView(requireContext());
        progressText.setText(R.string.ai_adblock_model_downloading);
        progressText.setTextColor(Color.WHITE);
        progressText.setTextSize(14);
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 16, 24, 0);
        layout.addView(progressBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = 12;
        layout.addView(progressText, textParams);
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(R.string.ai_adblock_model_downloading)
                .setView(layout)
                .setCancelable(false)
                .create();
        dialog.show();
        VoskAdblock.get().downloadModel((success, error) -> {
            mDownloading = false;
            if (dialog.isShowing()) dialog.dismiss();
            Notify.dismiss();
            if (success) {
                if (mBinding != null) {
                    mBinding.aiAdblockModelStatusText.setText(getModelStatus());
                    mBinding.aiAdblockModelDownload.setVisibility(View.GONE);
                }
                Notify.show(R.string.ai_adblock_downloaded);
            } else {
                if (mBinding != null) {
                    mBinding.aiAdblockModelDownload.setVisibility(View.VISIBLE);
                }
                Notify.show(R.string.ai_adblock_download_failed);
            }
        }, (downloaded, total) -> {
            if (total > 0) {
                progressBar.setIndeterminate(false);
                int percent = (int) (downloaded * 100 / total);
                progressBar.setProgress(percent);
                progressText.setText(percent + "%");
            } else {
                progressBar.setIndeterminate(true);
                progressText.setText(R.string.ai_adblock_model_downloading);
            }
        });
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

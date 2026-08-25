package com.fongmi.android.tv.ui.fragment;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingAdblockBinding;
import com.fongmi.android.tv.player.AdCloudSyncManager;
import com.fongmi.android.tv.player.AdProbeManager;
import com.fongmi.android.tv.player.TextAdRuleManager;
import com.fongmi.android.tv.player.VoskModelManager;
import com.fongmi.android.tv.player.vosk.VoskAdblock;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

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
        mBinding.adAutoCollectText.setText(getSwitch(Setting.isAutoCollect()));
        mBinding.adTextRuleText.setText(getSwitch(Setting.isTextAdRule()));
        updateSkipModeText();
        updateSkipSecondsText();
        updateVoskText();
        updateVoskStatus();
    }

    @Override
    protected void initEvent() {
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.aiAdblock.setOnClickListener(this::setAiAdblock);
        mBinding.adAutoCollect.setOnClickListener(this::setAutoCollect);
        mBinding.adTextRule.setOnClickListener(this::showTextRuleDialog);
        mBinding.adTextSkipSeconds.setOnClickListener(this::showSkipSecondsDialog);
        mBinding.adSkipMode.setOnClickListener(this::cycleSkipMode);
        mBinding.adVosk.setOnClickListener(view -> toggleVosk());
        mBinding.adVoskButton.setOnClickListener(view -> onVoskButton());
    }

    private void updateSkipSecondsText() {
        mBinding.adTextSkipSecondsText.setText(getString(R.string.ad_text_skip_seconds_value, Setting.getAdTextSkipSeconds()));
    }

    private void showSkipSecondsDialog(View view) {
        EditText editText = new EditText(requireContext());
        editText.setText(String.valueOf(Setting.getAdTextSkipSeconds()));
        editText.setHint(R.string.ad_text_skip_seconds_hint);
        editText.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.ad_text_skip_seconds)
                .setMessage(R.string.ad_text_skip_seconds_summary)
                .setView(editText)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    try {
                        int seconds = Integer.parseInt(editText.getText().toString().trim());
                        Setting.putAdTextSkipSeconds(seconds);
                        updateSkipSecondsText();
                        Notify.show(R.string.ad_text_skip_seconds_saved);
                    } catch (NumberFormatException e) {
                        Notify.show(R.string.ad_text_skip_seconds_invalid);
                    }
                })
                .show();
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
        if (enabled) {
            // 用户手动开启：同步云端广告规则并弹窗告知云端已载入
            AdCloudSyncManager.get().syncFromCloud(new AdCloudSyncManager.SyncCallback() {
                @Override
                public void onLoaded(int audioCount, int textRuleCount, int added) {
                    new MaterialAlertDialogBuilder(requireActivity())
                            .setTitle(R.string.ad_cloud_loaded_title)
                            .setMessage(getString(R.string.ad_cloud_loaded_msg, audioCount, textRuleCount, added))
                            .setPositiveButton(R.string.dialog_positive, null)
                            .show();
                }

                @Override
                public void onNoUrl() {
                    Notify.show(R.string.ad_cloud_no_url);
                }

                @Override
                public void onError(@NonNull String message) {
                    Notify.show(getString(R.string.ad_cloud_sync_failed, message));
                }
            });
        }
    }

    private void setAutoCollect(View view) {
        boolean enabled = !Setting.isAutoCollect();
        Setting.putAutoCollect(enabled);
        mBinding.adAutoCollectText.setText(getSwitch(enabled));
    }

    private void showTextRuleDialog(View view) {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, 0);

        SwitchCompat switchCompat = new SwitchCompat(requireContext());
        switchCompat.setText(R.string.ad_text_rule_enable);
        switchCompat.setChecked(Setting.isTextAdRule());
        layout.addView(switchCompat);

        EditText editText = new EditText(requireContext());
        editText.setHint(R.string.ad_text_rule_hint);
        editText.setSingleLine(false);
        editText.setMinLines(8);
        editText.setTextSize(14);
        editText.setText(loadTextRules());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (12 * getResources().getDisplayMetrics().density);
        layout.addView(editText, lp);

        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.ad_text_rule)
                .setView(layout)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    Setting.putTextAdRule(switchCompat.isChecked());
                    mBinding.adTextRuleText.setText(getSwitch(switchCompat.isChecked()));
                    saveTextRules(editText.getText().toString());
                    TextAdRuleManager.get().reload();
                    Notify.show(R.string.ad_text_rule_saved);
                })
                .show();
    }

    /** 从 RULES.JSON 读取 textRules 数组，每行一条。 */
    private String loadTextRules() {
        StringBuilder sb = new StringBuilder();
        try {
            File file = new File(Setting.getAdRulesPath());
            if (!file.exists() || !file.isFile() || !file.canRead()) return "";
            StringBuilder raw = new StringBuilder();
            try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                char[] buf = new char[8192];
                int len;
                while ((len = reader.read(buf)) != -1) raw.append(buf, 0, len);
            }
            JSONObject root = new JSONObject(raw.toString().trim());
            JSONArray arr = root.optJSONArray("textRules");
            if (arr == null) return "";
            for (int i = 0; i < arr.length(); i++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(arr.optString(i));
            }
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    /** 把每行一条规则写回 RULES.JSON 的 textRules 数组（保留其他字段）。 */
    private void saveTextRules(String content) {
        try {
            String path = Setting.getAdRulesPath();
            File file = new File(path);
            JSONObject root = new JSONObject();
            if (file.exists() && file.isFile() && file.canRead()) {
                StringBuilder raw = new StringBuilder();
                try (InputStreamReader reader = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                    char[] buf = new char[8192];
                    int len;
                    while ((len = reader.read(buf)) != -1) raw.append(buf, 0, len);
                }
                root = new JSONObject(raw.toString().trim());
            }
            JSONArray arr = new JSONArray();
            if (content != null) {
                String[] lines = content.split("\\n");
                for (String line : lines) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("//")) continue;
                    arr.put(trimmed);
                }
            }
            root.put("textRules", arr);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
                writer.write(root.toString(2));
            }
        } catch (Exception e) {
            Notify.show(R.string.ad_text_rule_save_failed);
        }
    }

    /** 模型目录是否真实存在（用于状态展示与开启校验）。 */
    private boolean voskModelExists() {
        String path = Setting.getVoskModelPath();
        if (path == null || path.isEmpty()) return false;
        return new File(path).isDirectory();
    }

    /** 模型目录是否包含 Vosk 必需文件（缺关键文件会导致 native 加载崩溃）。 */
    private boolean voskModelValid() {
        String path = Setting.getVoskModelPath();
        if (path == null || path.isEmpty()) return false;
        File modelDir = new File(path);
        if (!modelDir.isDirectory()) return false;
        // Vosk 加载模型必需 conf/model.conf + am/final.mdl；graph/ivector 缺失也会导致 native 加载失败
        boolean hasConf = new File(modelDir, "conf/model.conf").isFile();
        boolean hasAm = new File(modelDir, "am/final.mdl").isFile();
        boolean hasLang = new File(modelDir, "language_model.mat").isFile();
        if (hasLang && !hasConf && !hasAm) return false; // 只有 language_model.mat 而无核心文件，视为无效
        return hasConf && hasAm
                && new File(modelDir, "graph/Gr.fst").isFile()
                && new File(modelDir, "ivector/final.ie").isFile();
    }

    private void updateVoskText() {
        if (VoskModelManager.get().isDownloading()) {
            mBinding.adVoskText.setText(getString(R.string.ad_vosk_downloading, 0));
            mBinding.adVoskButton.setEnabled(false);
            mBinding.adVoskButton.setText(R.string.ad_vosk_downloading_confirm);
            return;
        }
        mBinding.adVoskButton.setEnabled(true);
        if (!voskModelExists()) {
            mBinding.adVoskText.setText(R.string.ad_vosk_not_downloaded);
            mBinding.adVoskButton.setText(R.string.ad_vosk_download);
        } else if (!voskModelValid()) {
            mBinding.adVoskText.setText(R.string.ad_vosk_model_invalid);
            mBinding.adVoskButton.setText(R.string.ad_vosk_delete);
        } else if (Setting.isVoskEnabled()) {
            mBinding.adVoskText.setText(R.string.ad_vosk_enabled);
            mBinding.adVoskButton.setText(R.string.ad_vosk_delete);
        } else {
            mBinding.adVoskText.setText(R.string.ad_vosk_download_ok);
            mBinding.adVoskButton.setText(R.string.ad_vosk_download);
        }
    }

    /** 刷新识别诊断状态：模型状态 / 识别器就绪 / 最近识别文本（VoskAdblock 1b42276 方案）。 */
    private void updateVoskStatus() {
        if (mBinding == null) return;
        VoskAdblock vosk = VoskAdblock.get();
        String state;
        if (!voskModelExists()) state = "未下载";
        else if (!voskModelValid()) state = "模型无效";
        else if (!Setting.isVoskEnabled()) state = "未开启";
        else state = vosk.isReady() ? "识别中" : "加载中";
        String text = "状态: " + state;
        if (Setting.isVoskEnabled() && vosk.isReady()) {
            String last = vosk.getLastRecognizedText();
            if (!TextUtils.isEmpty(last)) text += "\n最近识别: " + last;
        }
        mBinding.adVoskStatus.setText(text);
    }

    /** 点击整行：开启/关闭开关（仅模型已存在且有效时生效）。 */
    private void toggleVosk() {
        if (VoskModelManager.get().isDownloading()) return;
        if (!voskModelExists()) {
            Notify.show(R.string.ad_vosk_model_missing);
            return;
        }
        if (!voskModelValid()) {
            Notify.show(R.string.ad_vosk_model_invalid);
            return;
        }
        boolean enabled = !Setting.isVoskEnabled();
        Setting.putVoskEnabled(enabled);
        VoskAdblock.get().setEnabled(enabled);
        if (enabled) {
            Notify.show(R.string.ad_vosk_enabled);
            // 模型异步加载，稍后刷新一次状态
            if (mBinding != null) mBinding.getRoot().postDelayed(this::updateVoskStatus, 1500);
        } else {
            Notify.show(R.string.ad_vosk_disabled);
        }
        updateVoskText();
        updateVoskStatus();
    }

    /** 点击按钮：未下载 -> 确认下载；已下载未开启 -> 提示点击整行开启；已开启/无效 -> 删除模型。 */
    private void onVoskButton() {
        if (VoskModelManager.get().isDownloading()) return;
        if (!voskModelExists()) {
            confirmDownload();
        } else if (Setting.isVoskEnabled() || !voskModelValid()) {
            confirmDeleteVosk();
        } else {
            Notify.show(R.string.ad_vosk_download_ok);
            toggleVosk();
        }
    }

    private void confirmDownload() {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.ad_vosk)
                .setMessage(R.string.ad_vosk_downloading_confirm)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> startVoskDownload())
                .show();
    }

    private void startVoskDownload() {
        mBinding.adVoskText.setText(getString(R.string.ad_vosk_downloading, 0));
        mBinding.adVoskButton.setEnabled(false);
        mBinding.adVoskButton.setText(R.string.ad_vosk_downloading_confirm);
        VoskModelManager.get().download(requireContext(), new VoskModelManager.ModelCallback() {
            @Override
            public void onProgress(int percent) {
                if (!isAdded()) return;
                mBinding.adVoskText.setText(getString(R.string.ad_vosk_downloading, percent));
            }

            @Override
            public void onSuccess(@NonNull File modelDir) {
                if (!isAdded()) return;
                Notify.show(R.string.ad_vosk_download_ok);
                updateVoskText();
            }

            @Override
            public void onError(@NonNull String message) {
                if (!isAdded()) return;
                Notify.show(R.string.ad_vosk_download_failed);
                updateVoskText();
            }
        });
    }

    private void confirmDeleteVosk() {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.ad_vosk_delete)
                .setMessage(R.string.ad_vosk_delete_confirm)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    VoskAdblock.get().setEnabled(false);
                    VoskModelManager.get().delete(requireContext());
                    Notify.show(R.string.ad_vosk_deleted);
                    updateVoskText();
                })
                .show();
    }
}

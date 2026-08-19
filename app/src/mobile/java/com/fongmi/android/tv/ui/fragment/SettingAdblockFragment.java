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
import com.fongmi.android.tv.player.AdProbeManager;
import com.fongmi.android.tv.player.TextAdRuleManager;
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
        updateRulesPathText();
    }

    @Override
    protected void initEvent() {
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.aiAdblock.setOnClickListener(this::setAiAdblock);
        mBinding.adAutoCollect.setOnClickListener(this::setAutoCollect);
        mBinding.adTextRule.setOnClickListener(this::showTextRuleDialog);
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

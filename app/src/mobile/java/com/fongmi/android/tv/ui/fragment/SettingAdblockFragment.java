package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingAdblockBinding;
import com.fongmi.android.tv.player.AdCloudSyncManager;
import com.fongmi.android.tv.player.AdProbeManager;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;
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
        mBinding.adAutoCollectText.setText(getSwitch(Setting.isAutoCollect()));
        mBinding.adRuleLibUrlText.setText(Setting.getRuleLibraryUrl());
        updateSkipModeText();
    }

    @Override
    protected void initEvent() {
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.aiAdblock.setOnClickListener(this::setAiAdblock);
        mBinding.adAutoCollect.setOnClickListener(this::setAutoCollect);
        mBinding.adSkipMode.setOnClickListener(this::cycleSkipMode);
        mBinding.adProbeCheck.setOnClickListener(this::showProbeCheck);
        mBinding.adRuleLibUrl.setOnClickListener(this::editRuleLibUrl);
        mBinding.adPullLibrary.setOnClickListener(this::pullLibraryClick);
    }

    /**
     * 音纹去广告自检。探针是 fail-open 设计，出错时完全静默，
     * 用户只能看到「没反应」。这里把开关、探针实例、规则库、最近错误
     * 一次性摊开，不用 adb 也能定位问题。
     */
    private void showProbeCheck(View view) {
        // 每次点开都重新加载一次规则文件，用户手动换文件后立刻能看到条数变化
        AdProbeManager.get().reloadRulesForCheck();
        String report = AdProbeManager.get().getDiagnosticReport();
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.ad_probe_check_title)
                .setMessage(report)
                .setPositiveButton(R.string.dialog_positive, null)
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
            // 开启音纹去广告：外部 Download 目录在 Android 11+ 必须授予“所有文件访问权限”
            // 才能写入，否则规则库落盘会因 EACCES 失败（声纹去广告静默失效）。
            // 未授权时先引导授权，授权成功后再拉取规则库（仅下载、不上传）。
            FragmentActivity activity = requireActivity();
            if (Setting.needsAllFilesAccess()) {
                PermissionUtil.requestFile(activity, granted -> {
                    if (granted) {
                        pullLibrary(activity);
                    } else {
                        Notify.show(R.string.ad_cloud_storage_perm_required);
                    }
                });
            } else {
                pullLibrary(activity);
            }
        }
    }

    /** 手动拉取规则库按钮：先检查存储权限，再执行拉取。 */
    private void pullLibraryClick(View view) {
        FragmentActivity activity = requireActivity();
        if (Setting.needsAllFilesAccess()) {
            PermissionUtil.requestFile(activity, granted -> {
                if (granted) {
                    pullLibrary(activity);
                } else {
                    Notify.show(R.string.ad_cloud_storage_perm_required);
                }
            });
        } else {
            pullLibrary(activity);
        }
    }

    /** 拉取广告规则库（仅下载、不上传），弹窗告知结果。 */
    private void pullLibrary(FragmentActivity activity) {
        AdCloudSyncManager.get().syncFromCloud(new AdCloudSyncManager.SyncCallback() {
            @Override
            public void onLoaded(int audioCount, int textRuleCount, int added) {
                new MaterialAlertDialogBuilder(activity)
                        .setTitle(R.string.ad_rule_lib_loaded_title)
                        .setMessage(getString(R.string.ad_rule_lib_loaded_msg, audioCount, textRuleCount, added))
                        .setPositiveButton(R.string.dialog_positive, null)
                        .show();
            }

            @Override
            public void onNoUrl() {
                Notify.show(R.string.ad_rule_lib_no_url);
            }

            @Override
            public void onError(@NonNull String message) {
                Notify.show(getString(R.string.ad_rule_lib_sync_failed, message));
            }
        });
    }

    /** 编辑广告规则库地址（仅用于拉取，不会上传任何数据）。 */
    private void editRuleLibUrl(View view) {
        EditText input = new EditText(requireContext());
        input.setSingleLine(false);
        input.setMaxLines(3);
        input.setText(Setting.getRuleLibraryUrl());
        input.setSelection(input.getText().length());
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.ad_rule_lib_url_title)
                .setMessage(R.string.ad_rule_lib_url_hint)
                .setView(input)
                .setPositiveButton(R.string.dialog_positive, (d, w) -> {
                    Setting.putRuleLibraryUrl(input.getText().toString().trim());
                    mBinding.adRuleLibUrlText.setText(Setting.getRuleLibraryUrl());
                    Notify.show(R.string.ad_rule_lib_saved);
                })
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void setAutoCollect(View view) {
        boolean enabled = !Setting.isAutoCollect();
        Setting.putAutoCollect(enabled);
        mBinding.adAutoCollectText.setText(getSwitch(enabled));
    }
}

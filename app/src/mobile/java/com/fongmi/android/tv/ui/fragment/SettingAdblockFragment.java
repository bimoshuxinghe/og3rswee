package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingAdblockBinding;
import com.fongmi.android.tv.player.AdCloudSyncManager;
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
        mBinding.adAutoCollectText.setText(getSwitch(Setting.isAutoCollect()));
        updateSkipModeText();
    }

    @Override
    protected void initEvent() {
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.aiAdblock.setOnClickListener(this::setAiAdblock);
        mBinding.adAutoCollect.setOnClickListener(this::setAutoCollect);
        mBinding.adSkipMode.setOnClickListener(this::cycleSkipMode);
        mBinding.adProbeCheck.setOnClickListener(this::showProbeCheck);
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
}

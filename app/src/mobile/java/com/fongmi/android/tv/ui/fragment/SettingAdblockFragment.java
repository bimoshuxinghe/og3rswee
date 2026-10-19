package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.AdBlockStats;
import com.fongmi.android.tv.bean.HlsAdRule;
import com.fongmi.android.tv.databinding.FragmentSettingAdblockBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.HlsAdRuleStore;
import com.fongmi.android.tv.utils.Notify;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Map;

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
        mBinding.hlsAdFeatureText.setText(getSwitch(Setting.isHlsAdFeatureFilter()));
        mBinding.hlsAdRulesText.setText(getString(R.string.hls_ad_rules_manage_summary, countRules()));
        updateStatsText();
    }

    @Override
    protected void initEvent() {
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.hlsAdFeature.setOnClickListener(this::setFeatureFilter);
        mBinding.hlsAdRules.setOnClickListener(this::manageRules);
        mBinding.hlsAdStatsReset.setOnClickListener(this::resetStats);
    }

    private int countRules() {
        return HlsAdRuleStore.getRules().size();
    }

    private void setAdblock(View view) {
        Setting.putAdblock(!Setting.isAdblock());
        mBinding.adblockText.setText(getSwitch(Setting.isAdblock()));
    }

    private void setFeatureFilter(View view) {
        boolean enabled = !Setting.isHlsAdFeatureFilter();
        Setting.putHlsAdFeatureFilter(enabled);
        mBinding.hlsAdFeatureText.setText(getSwitch(enabled));
    }

    /**
     * 贴片规则管理：多选对话框逐条启停。
     * 规则引擎是主战引擎，特征引擎（hlsAdFeature）是兜底；
     * 停用某条规则只影响规则命中，不影响兜底与安全回退。
     */
    private void manageRules(View view) {
        List<HlsAdRule> rules = HlsAdRuleStore.getRules();
        String[] names = new String[rules.size()];
        boolean[] checked = new boolean[rules.size()];
        for (int i = 0; i < rules.size(); i++) {
            HlsAdRule rule = rules.get(i);
            names[i] = rule.name();
            checked[i] = !HlsAdRuleStore.isDisabled(rule.id());
        }
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.hls_ad_rule_manage_title)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> {
                    HlsAdRule rule = rules.get(which);
                    HlsAdRuleStore.setDisabled(rule.id(), !isChecked);
                })
                .setPositiveButton(R.string.dialog_positive, (d, w) -> refreshRuleText())
                .setNegativeButton(R.string.dialog_negative, null)
                .show();
    }

    private void refreshRuleText() {
        mBinding.hlsAdRulesText.setText(getString(R.string.hls_ad_rules_manage_summary, countRules()));
    }

    private void updateStatsText() {
        Map<String, String> s = AdBlockStats.summary();
        long total = Long.parseLong(s.get("totalStripped"));
        if (total == 0 && Long.parseLong(s.get("totalUnchanged")) == 0 && Long.parseLong(s.get("totalFallback")) == 0) {
            mBinding.hlsAdStatsText.setText(R.string.hls_ad_stats_empty);
            return;
        }
        mBinding.hlsAdStatsText.setText(getString(R.string.hls_ad_stats_summary,
                s.get("totalStripped"), s.get("ruleHits"), s.get("featureHits"), s.get("totalFallback")));
    }

    private void resetStats(View view) {
        AdBlockStats.get().reset();
        updateStatsText();
        Notify.show(R.string.hls_ad_stats_reset);
    }
}

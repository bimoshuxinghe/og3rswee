package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingPreloadBinding;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.base.BaseActivity;

public class SettingPreloadActivity extends BaseActivity {

    private ActivitySettingPreloadBinding mBinding;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingPreloadActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingPreloadBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.preload.requestFocus();
        setPreloadText();
        setPreloadNextText();
        setPreloadThreadText();
        setPreloadCapacityText();
        setPreloadSecondsText();
        mBinding.preloadThreadSlider.setValue(PlayerSetting.getPreloadThread());
        mBinding.preloadCapacitySlider.setValue(PlayerSetting.getPreloadCapacity());
        mBinding.preloadSecondsSlider.setValue(PlayerSetting.getPreloadSeconds());
    }

    @Override
    protected void initEvent() {
        mBinding.preload.setOnClickListener(this::setPreload);
        mBinding.preloadNext.setOnClickListener(this::setPreloadNext);
        mBinding.preloadThreadSlider.addOnChangeListener((slider, value, fromUser) -> {
            PlayerSetting.putPreloadThread((int) value);
            setPreloadThreadText();
        });
        mBinding.preloadCapacitySlider.addOnChangeListener((slider, value, fromUser) -> {
            PlayerSetting.putPreloadCapacity((int) value);
            setPreloadCapacityText();
        });
        mBinding.preloadSecondsSlider.addOnChangeListener((slider, value, fromUser) -> {
            PlayerSetting.putPreloadSeconds((int) value);
            setPreloadSecondsText();
        });
    }

    private void setPreload(View view) {
        PlayerSetting.putPreload(!PlayerSetting.isPreload());
        setPreloadText();
    }

    private void setPreloadNext(View view) {
        PlayerSetting.putPreloadNext(!PlayerSetting.isPreloadNext());
        setPreloadNextText();
    }

    private void setPreloadText() {
        mBinding.preloadText.setText(getSwitch(PlayerSetting.isPreload()));
    }

    private void setPreloadNextText() {
        mBinding.preloadNextText.setText(getSwitch(PlayerSetting.isPreloadNext()));
    }

    private void setPreloadThreadText() {
        mBinding.preloadThreadText.setText(PlayerSetting.getPreloadThread() + " " + getString(R.string.preload_thread_unit));
    }

    private void setPreloadCapacityText() {
        mBinding.preloadCapacityText.setText(PlayerSetting.getPreloadCapacity() + " MB");
    }

    private void setPreloadSecondsText() {
        mBinding.preloadSecondsText.setText(PlayerSetting.getPreloadSeconds() + " " + getString(R.string.second));
    }
}

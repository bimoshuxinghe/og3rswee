package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingPreloadBinding;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.ui.base.BaseFragment;

public class SettingPreloadFragment extends BaseFragment {

    private FragmentSettingPreloadBinding mBinding;

    public static SettingPreloadFragment newInstance() {
        return new SettingPreloadFragment();
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingPreloadBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
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

        ((NestedScrollView) mBinding.getRoot().findViewById(R.id.scrollView)).setOnScrollChangeListener((android.view.View.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            com.fongmi.android.tv.event.ScrollEvent.post(scrollY - oldScrollY);
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

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) initView();
    }
}

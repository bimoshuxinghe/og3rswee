package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.ActivitySettingPlayerBinding;
import com.fongmi.android.tv.impl.BufferListener;
import com.fongmi.android.tv.impl.SpeedListener;
import com.fongmi.android.tv.impl.UaListener;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.BufferDialog;
import com.fongmi.android.tv.ui.dialog.SpeedDialog;
import com.fongmi.android.tv.ui.dialog.UaDialog;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DecimalFormat;

public class SettingPlayerActivity extends BaseActivity implements UaListener, BufferListener, SpeedListener {

    private ActivitySettingPlayerBinding mBinding;
    private DecimalFormat format;
    private String[] caption;
    private String[] render;
    private String[] scale;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, SettingPlayerActivity.class));
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySettingPlayerBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setVisible();
        format = new DecimalFormat("0.#");
        mBinding.render.requestFocus();
        mBinding.uaText.setText(Setting.getUa());
        mBinding.aacText.setText(getSwitch(PlayerSetting.isPreferAAC()));
        mBinding.tunnelText.setText(getSwitch(PlayerSetting.isTunnel()));
        mBinding.exoDolbyVisionPassthroughText.setText(getSwitch(PlayerSetting.isExoDolbyVisionPassthrough()));
        mBinding.adblockText.setText(getSwitch(Setting.isAdblock()));
        mBinding.speedText.setText(format.format(PlayerSetting.getSpeed()));
        mBinding.bufferText.setText(String.valueOf(PlayerSetting.getBuffer()));
        mBinding.preloadText.setText(getPreloadText());
        mBinding.controllerAlphaText.setText(getControllerTransparencyText());
        mBinding.controllerAlphaSlider.setValue(PlayerSetting.getControllerTransparency());
        mBinding.backgroundText.setText(getSwitch(PlayerSetting.isBackgroundOn()));
        mBinding.homeMuteText.setText(getSwitch(PlayerSetting.isHomeMute()));
        mBinding.audioDecodeText.setText(getSwitch(PlayerSetting.isAudioPrefer()));
        mBinding.videoDecodeText.setText(getSwitch(PlayerSetting.isVideoPrefer()));
        mBinding.scaleText.setText((scale = ResUtil.getStringArray(R.array.select_scale))[PlayerSetting.getScale()]);
        mBinding.renderText.setText((render = ResUtil.getStringArray(R.array.select_render))[PlayerSetting.getRender()]);
        mBinding.captionText.setText((caption = ResUtil.getStringArray(R.array.select_caption))[PlayerSetting.isCaption() ? 1 : 0]);
        mBinding.alwaysTimeText.setText(getSwitch(Setting.isAlwaysTime()));
        mBinding.alwaysProgressText.setText(getSwitch(Setting.isAlwaysProgress()));
    }

    @Override
    protected void initEvent() {
        mBinding.ua.setOnClickListener(this::onUa);
        mBinding.aac.setOnClickListener(this::setAAC);
        mBinding.scale.setOnClickListener(this::setScale);
        mBinding.speed.setOnClickListener(this::onSpeed);
        mBinding.buffer.setOnClickListener(this::onBuffer);
        mBinding.preload.setOnClickListener(this::onPreload);
        mBinding.controllerAlphaSlider.addOnChangeListener((slider, value, fromUser) -> {
            PlayerSetting.putControllerTransparency((int) value);
            mBinding.controllerAlphaText.setText(getControllerTransparencyText());
        });
        mBinding.render.setOnClickListener(this::setRender);
        mBinding.tunnel.setOnClickListener(this::setTunnel);
        mBinding.exoDolbyVisionPassthrough.setOnClickListener(this::setExoDolbyVisionPassthrough);
        mBinding.caption.setOnClickListener(this::setCaption);
        mBinding.adblock.setOnClickListener(this::setAdblock);
        mBinding.caption.setOnLongClickListener(this::onCaption);
        mBinding.background.setOnClickListener(this::onBackground);
        mBinding.homeMute.setOnClickListener(this::onHomeMute);
        mBinding.audioDecode.setOnClickListener(this::setAudioDecode);
        mBinding.videoDecode.setOnClickListener(this::setVideoDecode);
        mBinding.alwaysTime.setOnClickListener(this::setAlwaysTime);
        mBinding.alwaysProgress.setOnClickListener(this::setAlwaysProgress);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBinding != null) mBinding.preloadText.setText(getPreloadText());
    }

    private void setVisible() {
        if (PlayerSetting.getBackground() == 2) PlayerSetting.putBackground(1);
        mBinding.caption.setVisibility(PlayerSetting.hasCaption() ? View.VISIBLE : View.GONE);
    }

    private String getPreloadText() {
        return getSwitch(PlayerSetting.isPreload()) + " / " + PlayerSetting.getPreloadThread() + " " + getString(R.string.preload_thread_unit) + " / " + PlayerSetting.getPreloadCapacity() + " MB / " + PlayerSetting.getPreloadSeconds() + " " + getString(R.string.second);
    }

    private String getControllerTransparencyText() {
        return PlayerSetting.getControllerTransparency() + "%";
    }

    private String[] getPreloadItems() {
        return new String[]{
                getString(R.string.player_preload) + "：" + getSwitch(PlayerSetting.isPreload()),
                getString(R.string.player_preload_next) + "：" + getSwitch(PlayerSetting.isPreloadNext()),
                getString(R.string.player_preload_thread) + "：" + PlayerSetting.getPreloadThread() + " " + getString(R.string.preload_thread_unit),
                getString(R.string.player_preload_capacity) + "：" + PlayerSetting.getPreloadCapacity() + " MB",
                getString(R.string.player_preload_seconds) + "：" + PlayerSetting.getPreloadSeconds() + " " + getString(R.string.second)
        };
    }

    private void onUa(View view) {
        UaDialog.show(this);
    }

    @Override
    public void setUa(String ua) {
        mBinding.uaText.setText(ua);
        Setting.putUa(ua);
    }

    private void setAAC(View view) {
        PlayerSetting.putPreferAAC(!PlayerSetting.isPreferAAC());
        mBinding.aacText.setText(getSwitch(PlayerSetting.isPreferAAC()));
    }

    private void setScale(View view) {
        int index = (PlayerSetting.getScale() + 1) % scale.length;
        mBinding.scaleText.setText(scale[index]);
        PlayerSetting.putScale(index);
    }

    private void onSpeed(View view) {
        SpeedDialog.show(this);
    }

    @Override
    public void setSpeed(float speed) {
        mBinding.speedText.setText(format.format(speed));
        PlayerSetting.putSpeed(speed);
    }

    private void onBuffer(View view) {
        BufferDialog.show(this);
    }

    @Override
    public void setBuffer(int times) {
        mBinding.bufferText.setText(String.valueOf(times));
        PlayerSetting.putBuffer(times);
    }

    private void onPreload(View view) {
        SettingPreloadActivity.start(this);
    }

    private int nextPreloadCapacity() {
        int value = PlayerSetting.getPreloadCapacity();
        if (value < 64) return 64;
        if (value < 128) return 128;
        if (value < 256) return 256;
        if (value < 512) return 512;
        return 32;
    }

    private int nextPreloadSeconds() {
        int value = PlayerSetting.getPreloadSeconds();
        if (value < 30) return 30;
        if (value < 60) return 60;
        if (value < 120) return 120;
        if (value < 180) return 180;
        if (value < 300) return 300;
        return 10;
    }

    private void setRender(View view) {
        if (PlayerSetting.isTunnel() && PlayerSetting.getRender() == 0) setTunnel(view);
        int index = (PlayerSetting.getRender() + 1) % render.length;
        mBinding.renderText.setText(render[index]);
        PlayerSetting.putRender(index);
    }

    private void setTunnel(View view) {
        PlayerSetting.putTunnel(!PlayerSetting.isTunnel());
        mBinding.tunnelText.setText(getSwitch(PlayerSetting.isTunnel()));
        if (PlayerSetting.isTunnel() && PlayerSetting.getRender() == 1) setRender(view);
    }

    private void setExoDolbyVisionPassthrough(View view) {
        PlayerSetting.putExoDolbyVisionPassthrough(!PlayerSetting.isExoDolbyVisionPassthrough());
        mBinding.exoDolbyVisionPassthroughText.setText(getSwitch(PlayerSetting.isExoDolbyVisionPassthrough()));
    }

    private void setCaption(View view) {
        PlayerSetting.putCaption(!PlayerSetting.isCaption());
        mBinding.captionText.setText(caption[PlayerSetting.isCaption() ? 1 : 0]);
    }

    private void setAdblock(View view) {
        Setting.putAdblock(!Setting.isAdblock());
        mBinding.adblockText.setText(getSwitch(Setting.isAdblock()));
    }

    private boolean onCaption(View view) {
        if (PlayerSetting.isCaption()) startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));
        return PlayerSetting.isCaption();
    }

    private void setAudioDecode(View view) {
        PlayerSetting.putAudioPrefer(!PlayerSetting.isAudioPrefer());
        mBinding.audioDecodeText.setText(getSwitch(PlayerSetting.isAudioPrefer()));
    }

    private void setVideoDecode(View view) {
        PlayerSetting.putVideoPrefer(!PlayerSetting.isVideoPrefer());
        mBinding.videoDecodeText.setText(getSwitch(PlayerSetting.isVideoPrefer()));
    }

    private void onBackground(View view) {
        PlayerSetting.putBackground(PlayerSetting.isBackgroundOn() ? 0 : 1);
        mBinding.backgroundText.setText(getSwitch(PlayerSetting.isBackgroundOn()));
    }

    private void onHomeMute(View view) {
        PlayerSetting.putHomeMute(!PlayerSetting.isHomeMute());
        mBinding.homeMuteText.setText(getSwitch(PlayerSetting.isHomeMute()));
    }

    private void setAlwaysTime(View view) {
        Setting.putAlwaysTime(!Setting.isAlwaysTime());
        mBinding.alwaysTimeText.setText(getSwitch(Setting.isAlwaysTime()));
    }

    private void setAlwaysProgress(View view) {
        Setting.putAlwaysProgress(!Setting.isAlwaysProgress());
        mBinding.alwaysProgressText.setText(getSwitch(Setting.isAlwaysProgress()));
    }
}

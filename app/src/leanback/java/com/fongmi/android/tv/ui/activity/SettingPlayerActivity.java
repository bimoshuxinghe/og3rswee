package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
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
import com.fongmi.android.tv.utils.FileChooser;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DecimalFormat;

public class SettingPlayerActivity extends BaseActivity implements UaListener, BufferListener, SpeedListener {

    private ActivitySettingPlayerBinding mBinding;
    private DecimalFormat format;
    private String[] caption;
    private String[] mpvRender;
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
        mBinding.mpvConfigText.setText(getMpvConfigText());
        mBinding.mpvAudioPassthroughText.setText(getSwitch(PlayerSetting.isMpvAudioPassthrough()));
        mBinding.mpvDolbyPassthroughText.setText(getSwitch(PlayerSetting.isMpvDolbyPassthrough()));
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
        mBinding.mpvRenderText.setText((mpvRender = ResUtil.getStringArray(R.array.select_mpv_render))[PlayerSetting.getMpvRender()]);
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
        mBinding.mpvConfig.setOnClickListener(this::onMpvConfig);
        mBinding.mpvConfig.setOnLongClickListener(this::clearMpvConfig);
        mBinding.mpvRender.setOnClickListener(this::setMpvRender);
        mBinding.mpvAudioPassthrough.setOnClickListener(this::setMpvAudioPassthrough);
        mBinding.mpvDolbyPassthrough.setOnClickListener(this::setMpvDolbyPassthrough);
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

    private String getMpvConfigText() {
        return PlayerSetting.hasMpvConfig() ? PlayerSetting.getMpvConfigName() : getString(R.string.player_mpv_config_default);
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

    private void onMpvConfig(View view) {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.player_mpv_config).setNegativeButton(R.string.dialog_negative, null).setItems(new String[]{"本地文件", "在线地址", "清除配置"}, (dialog, which) -> {
            if (which == 0) selectMpvConfigFile();
            else if (which == 1) inputMpvConfigUrl();
            else clearMpvConfig(view);
        }).show();
    }

    private void selectMpvConfigFile() {
        FileChooser.from(mpvConfigLauncher).show(new String[]{"text/*", "application/octet-stream", "*/*"});
    }

    private void inputMpvConfigUrl() {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("https://.../mpv.conf");
        if (PlayerSetting.getMpvConfigName().startsWith("http")) input.setText(PlayerSetting.getMpvConfigName());
        new MaterialAlertDialogBuilder(this).setTitle("在线 MPV 配置").setView(input).setNegativeButton(R.string.dialog_negative, null).setPositiveButton(R.string.dialog_positive, (dialog, which) -> importMpvConfigUrl(input.getText().toString())).show();
    }

    private void importMpvConfigUrl(String url) {
        if (TextUtils.isEmpty(url) || (!url.startsWith("http://") && !url.startsWith("https://"))) {
            Notify.show("MPV 配置地址无效");
            return;
        }
        Notify.progress(this);
        Task.execute(() -> {
            boolean ok = PlayerSetting.importMpvConfigUrl(url);
            App.post(() -> {
                Notify.dismiss();
                if (isFinishing()) return;
                if (ok) {
                    mBinding.mpvConfigText.setText(getMpvConfigText());
                    Notify.show("MPV 配置已导入");
                } else {
                    Notify.show("MPV 配置导入失败");
                }
            });
        });
    }

    private boolean clearMpvConfig(View view) {
        if (!PlayerSetting.hasMpvConfig()) return false;
        PlayerSetting.clearMpvConfig();
        mBinding.mpvConfigText.setText(getMpvConfigText());
        Notify.show("MPV 配置已清除");
        return true;
    }

    private void setMpvRender(View view) {
        int index = (PlayerSetting.getMpvRender() + 1) % mpvRender.length;
        mBinding.mpvRenderText.setText(mpvRender[index]);
        PlayerSetting.putMpvRender(index);
    }

    private void setMpvAudioPassthrough(View view) {
        PlayerSetting.putMpvAudioPassthrough(!PlayerSetting.isMpvAudioPassthrough());
        mBinding.mpvAudioPassthroughText.setText(getSwitch(PlayerSetting.isMpvAudioPassthrough()));
    }

    private void setMpvDolbyPassthrough(View view) {
        PlayerSetting.putMpvDolbyPassthrough(!PlayerSetting.isMpvDolbyPassthrough());
        mBinding.mpvDolbyPassthroughText.setText(getSwitch(PlayerSetting.isMpvDolbyPassthrough()));
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

    private final ActivityResultLauncher<Intent> mpvConfigLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        String path = FileChooser.getPathFromUri(result.getData().getData());
        if (TextUtils.isEmpty(path) || !PlayerSetting.importMpvConfig(path)) {
            Notify.show("MPV 配置导入失败");
            return;
        }
        mBinding.mpvConfigText.setText(getMpvConfigText());
        Notify.show("MPV 配置已导入");
    });
}

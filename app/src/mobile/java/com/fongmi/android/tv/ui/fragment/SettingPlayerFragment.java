package com.fongmi.android.tv.ui.fragment;

import android.app.Activity;

import android.content.Intent;

import android.provider.Settings;

import android.text.TextUtils;

import android.view.LayoutInflater;

import android.view.View;

import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;

import androidx.activity.result.contract.ActivityResultContracts;

import androidx.annotation.NonNull;

import androidx.annotation.Nullable;

import androidx.core.widget.NestedScrollView;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;

import com.fongmi.android.tv.App;

import com.fongmi.android.tv.databinding.DialogMpvConfigBinding;

import com.fongmi.android.tv.databinding.FragmentSettingPlayerBinding;

import com.fongmi.android.tv.impl.BufferListener;

import com.fongmi.android.tv.impl.SpeedListener;

import com.fongmi.android.tv.impl.UaListener;

import com.fongmi.android.tv.setting.LiveSetting;

import com.fongmi.android.tv.setting.PlayerSetting;

import com.fongmi.android.tv.setting.Setting;

import com.fongmi.android.tv.ui.base.BaseFragment;

import com.fongmi.android.tv.ui.dialog.BufferDialog;

import com.fongmi.android.tv.ui.dialog.SpeedDialog;

import com.fongmi.android.tv.ui.dialog.UaDialog;

import com.fongmi.android.tv.utils.FileChooser;

import com.fongmi.android.tv.utils.Notify;

import com.fongmi.android.tv.utils.ResUtil;

import com.fongmi.android.tv.utils.Task;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DecimalFormat;

public class SettingPlayerFragment extends BaseFragment implements UaListener, BufferListener, SpeedListener {

    private FragmentSettingPlayerBinding mBinding;

    private DecimalFormat format;

    private String[] background;

    private String[] caption;

    private String[] mpvRender;

    private String[] render;

    private String[] scale;

    public static SettingPlayerFragment newInstance() {

        return new SettingPlayerFragment();

    }

    private String getSwitch(boolean value) {

        return getString(value ? R.string.setting_on : R.string.setting_off);

    }

    @Override

    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {

        return mBinding = FragmentSettingPlayerBinding.inflate(inflater, container, false);

    }

    @Override

    protected void initView() {

        format = new DecimalFormat("0.#");

        mBinding.uaText.setText(Setting.getUa());

        mBinding.aacText.setText(getSwitch(PlayerSetting.isPreferAAC()));

        mBinding.tunnelText.setText(getSwitch(PlayerSetting.isTunnel()));

        mBinding.mpvConfigText.setText(getMpvConfigText());

        mBinding.mpvAudioPassthroughText.setText(getSwitch(PlayerSetting.isMpvAudioPassthrough()));

        mBinding.mpvDolbyPassthroughText.setText(getSwitch(PlayerSetting.isMpvDolbyPassthrough()));

        mBinding.exoDolbyVisionPassthroughText.setText(getSwitch(PlayerSetting.isExoDolbyVisionPassthrough()));

        mBinding.alwaysProgressText.setText(getSwitch(Setting.isAlwaysProgress()));

        mBinding.liveBootText.setText(getSwitch(LiveSetting.isBootGlobal()));

        mBinding.speedText.setText(format.format(PlayerSetting.getSpeed()));

        mBinding.bufferText.setText(String.valueOf(PlayerSetting.getBuffer()));

        mBinding.preloadText.setText(getPreloadText());

        mBinding.audioDecodeText.setText(getSwitch(PlayerSetting.isAudioPrefer()));

        mBinding.videoDecodeText.setText(getSwitch(PlayerSetting.isVideoPrefer()));

        mBinding.caption.setVisibility(PlayerSetting.hasCaption() ? View.VISIBLE : View.GONE);

        mBinding.scaleText.setText((scale = ResUtil.getStringArray(R.array.select_scale))[PlayerSetting.getScale()]);

        mBinding.renderText.setText((render = ResUtil.getStringArray(R.array.select_render))[PlayerSetting.getRender()]);

        mBinding.mpvRenderText.setText((mpvRender = ResUtil.getStringArray(R.array.select_mpv_render))[PlayerSetting.getMpvRender()]);

        mBinding.captionText.setText((caption = ResUtil.getStringArray(R.array.select_caption))[PlayerSetting.isCaption() ? 1 : 0]);

        mBinding.backgroundText.setText((background = ResUtil.getStringArray(R.array.select_background))[PlayerSetting.getBackground()]);

        mBinding.lrcSizeText.setText(String.valueOf((int) PlayerSetting.getLrcTextSize()));

    }

    @Override

    protected void initEvent() {

        mBinding.ua.setOnClickListener(this::onUa);

        mBinding.aac.setOnClickListener(this::setAAC);

        mBinding.scale.setOnClickListener(this::onScale);

        mBinding.speed.setOnClickListener(this::onSpeed);

        mBinding.buffer.setOnClickListener(this::onBuffer);

        mBinding.preload.setOnClickListener(this::onPreload);

        mBinding.render.setOnClickListener(this::setRender);

        mBinding.tunnel.setOnClickListener(this::setTunnel);

        mBinding.mpvConfig.setOnClickListener(this::onMpvConfig);

        mBinding.mpvConfig.setOnLongClickListener(this::clearMpvConfig);

        mBinding.mpvRender.setOnClickListener(this::onMpvRender);

        mBinding.mpvAudioPassthrough.setOnClickListener(this::setMpvAudioPassthrough);

        mBinding.mpvDolbyPassthrough.setOnClickListener(this::setMpvDolbyPassthrough);

        mBinding.exoDolbyVisionPassthrough.setOnClickListener(this::setExoDolbyVisionPassthrough);

        mBinding.caption.setOnClickListener(this::setCaption);

        mBinding.alwaysProgress.setOnClickListener(this::setAlwaysProgress);

        mBinding.liveBoot.setOnClickListener(this::setLiveBoot);

        mBinding.caption.setOnLongClickListener(this::onCaption);

        mBinding.background.setOnClickListener(this::onBackground);

        mBinding.lrcSize.setOnClickListener(this::onLrcSize);

        mBinding.audioDecode.setOnClickListener(this::setAudioDecode);

        mBinding.videoDecode.setOnClickListener(this::setVideoDecode);

        ((NestedScrollView) mBinding.getRoot().findViewById(R.id.scrollView)).setOnScrollChangeListener((android.view.View.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {

            com.fongmi.android.tv.event.ScrollEvent.post(scrollY - oldScrollY);

        });

    }

    private String getMpvConfigText() {

        return PlayerSetting.hasMpvConfig() ? PlayerSetting.getMpvConfigName() : getString(R.string.player_mpv_config_default);

    }

    private String getPreloadText() {

        return getSwitch(PlayerSetting.isPreload()) + " / " + PlayerSetting.getPreloadThread() + " " + getString(R.string.preload_thread_unit) + " / " + PlayerSetting.getPreloadCapacity() + " MB / " + PlayerSetting.getPreloadSeconds() + " " + getString(R.string.second);

    }

    private String[] getPreloadItems() {
        return new String[]{
                getString(R.string.player_preload) + "\uFF1A" + getSwitch(PlayerSetting.isPreload()),
                getString(R.string.player_preload_next) + "\uFF1A" + getSwitch(PlayerSetting.isPreloadNext()),
                getString(R.string.player_preload_thread) + "\uFF1A" + PlayerSetting.getPreloadThread() + " " + getString(R.string.preload_thread_unit),
                getString(R.string.player_preload_capacity) + "\uFF1A" + PlayerSetting.getPreloadCapacity() + " MB",
                getString(R.string.player_preload_seconds) + "\uFF1A" + PlayerSetting.getPreloadSeconds() + " " + getString(R.string.second)
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

    private void onScale(View view) {

        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_scale).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(scale, PlayerSetting.getScale(), (dialog, which) -> {

            mBinding.scaleText.setText(scale[which]);

            PlayerSetting.putScale(which);

            dialog.dismiss();

        }).show();

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

        ((com.fongmi.android.tv.ui.activity.HomeActivity) requireActivity()).change(6);

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

        DialogMpvConfigBinding binding = DialogMpvConfigBinding.inflate(getLayoutInflater());

        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireActivity()).setView(binding.getRoot()).create();

        binding.current.setText("\u5F53\u524D\uFF1A" + getMpvConfigText());

        binding.clear.setEnabled(PlayerSetting.hasMpvConfig());

        binding.clear.setAlpha(PlayerSetting.hasMpvConfig() ? 1.0f : 0.45f);

        if (PlayerSetting.getMpvConfigName().startsWith("http")) binding.input.setText(PlayerSetting.getMpvConfigName());

        binding.local.setOnClickListener(v -> {

            dialog.dismiss();

            selectMpvConfigFile();

        });

        binding.url.setOnClickListener(v -> {

            String url = binding.input.getText() == null ? "" : binding.input.getText().toString().trim();

            if (TextUtils.isEmpty(url) || (!url.startsWith("http://") && !url.startsWith("https://"))) {

                Notify.show("MPV \u914D\u7F6E\u5730\u5740\u65E0\u6548");

                return;

            }

            dialog.dismiss();

            importMpvConfigUrl(url);

        });

        binding.clear.setOnClickListener(v -> {

            clearMpvConfig(view);

            dialog.dismiss();

        });

        dialog.setOnShowListener(d -> {

            if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        });

        dialog.show();

    }

    private void selectMpvConfigFile() {

        FileChooser.from(mpvConfigLauncher).show(new String[]{"text/*", "application/octet-stream", "*/*"});

    }

    private void importMpvConfigUrl(String url) {

        Notify.progress(requireActivity());

        Task.execute(() -> {

            boolean ok = PlayerSetting.importMpvConfigUrl(url);

            App.post(() -> {

                Notify.dismiss();

                if (!isAdded()) return;

                if (ok) {

                    mBinding.mpvConfigText.setText(getMpvConfigText());

                    Notify.show("MPV \u914D\u7F6E\u5DF2\u5BFC\u5165");

                } else {

                    Notify.show("MPV \u914D\u7F6E\u5730\u5740\u65E0\u6548");

                }

            });

        });

    }

    private boolean clearMpvConfig(View view) {

        if (!PlayerSetting.hasMpvConfig()) return false;

        PlayerSetting.clearMpvConfig();

        mBinding.mpvConfigText.setText(getMpvConfigText());

        Notify.show("MPV \u914D\u7F6E\u5DF2\u6E05\u9664");

        return true;

    }

    private void onMpvRender(View view) {

        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_mpv_render).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(mpvRender, PlayerSetting.getMpvRender(), (dialog, which) -> {

            mBinding.mpvRenderText.setText(mpvRender[which]);

            PlayerSetting.putMpvRender(which);

            dialog.dismiss();

        }).show();

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

    private boolean onCaption(View view) {

        if (PlayerSetting.isCaption()) startActivity(new Intent(Settings.ACTION_CAPTIONING_SETTINGS));

        return PlayerSetting.isCaption();

    }

    private void setAlwaysProgress(View view) {

        Setting.putAlwaysProgress(!Setting.isAlwaysProgress());

        mBinding.alwaysProgressText.setText(getSwitch(Setting.isAlwaysProgress()));

    }

    private void setLiveBoot(View view) {

        LiveSetting.putBootGlobal(!LiveSetting.isBootGlobal());

        mBinding.liveBootText.setText(getSwitch(LiveSetting.isBootGlobal()));

    }

    private void onBackground(View view) {

        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.player_background).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(background, PlayerSetting.getBackground(), (dialog, which) -> {

            mBinding.backgroundText.setText(background[which]);

            PlayerSetting.putBackground(which);

            dialog.dismiss();

        }).show();

    }

    private void onLrcSize(View view) {

        com.fongmi.android.tv.databinding.DialogLrcSizeBinding binding =
                com.fongmi.android.tv.databinding.DialogLrcSizeBinding.inflate(getLayoutInflater());

        float current = PlayerSetting.getLrcTextSize();
        int progress = (int) (current - 24f);
        binding.lrcSizeSeek.setMax(56);
        binding.lrcSizeSeek.setProgress(progress);
        binding.lrcSizeValue.setText(String.valueOf((int) current));

        binding.lrcSizeSeek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(android.widget.SeekBar seekBar, int prog, boolean fromUser) {
                float size = 24f + prog;
                binding.lrcSizeValue.setText(String.valueOf((int) size));
            }
            @Override
            public void onStartTrackingTouch(android.widget.SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(android.widget.SeekBar seekBar) {}
        });

        // 歌词颜色选择器
        int[] lrcColors = {0xFFFFD700, 0xFF4CAF50, 0xFF00BCD4, 0xFF2196F3, 0xFF9C27B0, 0xFFF44336, 0xFFFF9800, 0xFFFFFFFF};
        int savedColor = PlayerSetting.getLrcColor();
        final int[] selectedColor = {savedColor};
        float density = getResources().getDisplayMetrics().density;
        for (int c : lrcColors) {
            View circle = new View(requireContext());
            int size = (int) (32 * density);
            int margin = (int) (5 * density);
            android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(size, size);
            params.setMargins(margin, 0, margin, 0);
            circle.setLayoutParams(params);
            circle.setTag(c);
            applyColorCircle(circle, c, c == selectedColor[0], density);
            final int color = c;
            circle.setOnClickListener(v -> {
                selectedColor[0] = color;
                for (int j = 0; j < binding.lrcColorContainer.getChildCount(); j++) {
                    View child = binding.lrcColorContainer.getChildAt(j);
                    int childColor = (int) child.getTag();
                    applyColorCircle(child, childColor, childColor == color, density);
                }
            });
            binding.lrcColorContainer.addView(circle);
        }

        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.player_lrc_size)
                .setView(binding.getRoot())
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    float size = 24f + binding.lrcSizeSeek.getProgress();
                    PlayerSetting.putLrcTextSize(size);
                    PlayerSetting.putLrcColor(selectedColor[0]);
                    mBinding.lrcSizeText.setText(String.valueOf((int) size));
                    com.fongmi.android.tv.event.RefreshEvent.subtitle("");
                })
                .show();

    }

    private void applyColorCircle(View circle, int color, boolean selected, float density) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        drawable.setColor(color);
        if (selected) {
            drawable.setStroke((int) (3 * density), android.graphics.Color.WHITE);
        } else {
            drawable.setStroke((int) (1 * density), 0x33FFFFFF);
        }
        circle.setBackground(drawable);
    }

    private void setAudioDecode(View view) {

        PlayerSetting.putAudioPrefer(!PlayerSetting.isAudioPrefer());

        mBinding.audioDecodeText.setText(getSwitch(PlayerSetting.isAudioPrefer()));

    }

    private void setVideoDecode(View view) {

        PlayerSetting.putVideoPrefer(!PlayerSetting.isVideoPrefer());

        mBinding.videoDecodeText.setText(getSwitch(PlayerSetting.isVideoPrefer()));

    }

    @Override

    public void onHiddenChanged(boolean hidden) {

        if (!hidden) initView();

    }

    private final ActivityResultLauncher<Intent> mpvConfigLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null || result.getData().getData() == null) return;
        String path = FileChooser.getPathFromUri(result.getData().getData());
        if (TextUtils.isEmpty(path) || !PlayerSetting.importMpvConfig(path)) {
            Notify.show("MPV \u914D\u7F6E\u5BFC\u5165\u5931\u8D25");
            return;
        }
        mBinding.mpvConfigText.setText(getMpvConfigText());
        Notify.show("MPV \u914D\u7F6E\u5DF2\u5BFC\u5165");
    });

}

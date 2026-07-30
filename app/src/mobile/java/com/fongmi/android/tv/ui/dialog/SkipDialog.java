package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSkipBinding;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class SkipDialog extends BaseAlertDialog {

    private DialogSkipBinding binding;
    private long opening;
    private long ending;

    public static SkipDialog create() {
        return new SkipDialog();
    }

    public SkipDialog skip(long opening, long ending) {
        this.opening = Math.max(0, opening);
        this.ending = Math.max(0, ending);
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof SkipDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogSkipBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setTitle(R.string.skip_setting).setView(getBinding().getRoot()).setPositiveButton(R.string.dialog_positive, this::onPositive).setNegativeButton(R.string.dialog_negative, null);
    }

    @Override
    protected void initView() {
        binding.openingSlider.setValue(toSeconds(opening));
        binding.endingSlider.setValue(toSeconds(ending));
        setOpeningValue(binding.openingSlider.getValue());
        setEndingValue(binding.endingSlider.getValue());
    }

    @Override
    protected void initEvent() {
        binding.openingSlider.addOnChangeListener((slider, value, fromUser) -> setOpeningValue(value));
        binding.endingSlider.addOnChangeListener((slider, value, fromUser) -> setEndingValue(value));
    }

    private void onPositive(DialogInterface dialog, int which) {
        long opening = toMillis(binding.openingSlider);
        long ending = toMillis(binding.endingSlider);
        ((Listener) requireActivity()).onSkipChanged(opening, ending);
    }

    private void setOpeningValue(float value) {
        binding.openingValue.setText(format(TimeUnit.SECONDS.toMillis((long) value)));
    }

    private void setEndingValue(float value) {
        binding.endingValue.setText(format(TimeUnit.SECONDS.toMillis((long) value)));
    }

    private float toSeconds(long timeMs) {
        return Math.min(Math.max(TimeUnit.MILLISECONDS.toSeconds(timeMs), 0), 300);
    }

    private long toMillis(Slider slider) {
        return TimeUnit.SECONDS.toMillis((long) slider.getValue());
    }

    public static String format(long timeMs) {
        long seconds = Math.max(0, TimeUnit.MILLISECONDS.toSeconds(timeMs));
        if (seconds >= 3600) return Util.timeMs(timeMs);
        return String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60);
    }

    public interface Listener {

        void onSkipChanged(long opening, long ending);
    }
}

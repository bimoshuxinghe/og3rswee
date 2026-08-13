package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.view.KeyEvent;
import android.view.View;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogSkipBinding;
import com.fongmi.android.tv.utils.KeyUtil;
import com.fongmi.android.tv.utils.Util;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

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
        setText();
        binding.openingRow.requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.openingRow.setOnKeyListener((view, keyCode, event) -> onAdjust(event, true));
        binding.endingRow.setOnKeyListener((view, keyCode, event) -> onAdjust(event, false));
        binding.clear.setOnClickListener(view -> clear());
    }

    private boolean onAdjust(KeyEvent event, boolean intro) {
        if (!KeyUtil.isActionDown(event)) return false;
        if (!KeyUtil.isLeftKey(event) && !KeyUtil.isRightKey(event)) return false;
        long diff = TimeUnit.SECONDS.toMillis(getStep(event));
        if (KeyUtil.isLeftKey(event)) diff = -diff;
        if (intro) opening = clamp(opening + diff);
        else ending = clamp(ending + diff);
        setText();
        return true;
    }

    private int getStep(KeyEvent event) {
        int repeat = event.getRepeatCount();
        if (repeat >= 24) return 10;
        if (repeat >= 10) return 5;
        return 1;
    }

    private long clamp(long value) {
        return Math.min(Math.max(value, 0), TimeUnit.MINUTES.toMillis(10));
    }

    private void clear() {
        opening = 0;
        ending = 0;
        setText();
    }

    private void setText() {
        binding.openingValue.setText(format(opening));
        binding.endingValue.setText(format(ending));
    }

    private void onPositive(DialogInterface dialog, int which) {
        ((Listener) requireActivity()).onSkipChanged(opening, ending);
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

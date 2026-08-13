package com.fongmi.android.tv.ui.dialog;

import android.view.inputmethod.EditorInfo;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.databinding.DialogNasInputBinding;

public class NasInputDialog extends BaseAlertDialog {

    private DialogNasInputBinding binding;
    private String title;
    private String text;
    private boolean isPassword;
    private Callback callback;

    public interface Callback {
        void onInput(String value);
    }

    public static NasInputDialog create() {
        return new NasInputDialog();
    }

    public NasInputDialog title(String title) {
        this.title = title;
        return this;
    }

    public NasInputDialog text(String text) {
        this.text = text;
        return this;
    }

    public NasInputDialog isPassword(boolean isPassword) {
        this.isPassword = isPassword;
        return this;
    }

    public NasInputDialog callback(Callback callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogNasInputBinding.inflate(getLayoutInflater());
    }

    @Override
    protected com.google.android.material.dialog.MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.title.setText(title);
        binding.text.setText(text);
        binding.text.setSelection(text == null ? 0 : text.length());
        if (isPassword) {
            binding.text.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
    }

    @Override
    protected void initEvent() {
        binding.positive.setOnClickListener(v -> onPositive());
        binding.negative.setOnClickListener(v -> dismiss());
        binding.text.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                onPositive();
                return true;
            }
            return false;
        });
    }

    private void onPositive() {
        String input = binding.text.getText().toString().trim();
        if (callback != null) {
            callback.onInput(input);
        }
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.45f);
    }
}

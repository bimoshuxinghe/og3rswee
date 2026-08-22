package com.fongmi.android.tv.ui.holder;

import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;

import androidx.annotation.NonNull;
import androidx.core.widget.TextViewCompat;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeGridBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;

public class EpisodeGridHolder extends BaseEpisodeHolder {

    private final EpisodeAdapter.OnClickListener listener;
    private final AdapterEpisodeGridBinding binding;

    public EpisodeGridHolder(@NonNull AdapterEpisodeGridBinding binding, EpisodeAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
    }

    @Override
    public void initView(Episode item) {
        binding.card.setSelected(item.isSelected());
        binding.text.setSelected(false);
        binding.text.setTextColor(Color.parseColor(item.isSelected() ? "#5EF2C2" : "#E8EDF4"));
        binding.text.setTypeface(null, item.isSelected() ? Typeface.BOLD : Typeface.NORMAL);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(binding.text, 8, 14, 1, TypedValue.COMPLEX_UNIT_SP);
        if (Setting.isShortShow() && item.getNumber() > 0) {
            binding.text.setText("第" + item.getNumber() + "集");
        } else {
            binding.card.setPadding(dp(12), dp(6), dp(12), dp(6));
            binding.text.setText(item.getDesc().concat(item.getName()));
        }
        binding.card.setOnClickListener(v -> listener.onItemClick(item));
    }

    private int dp(int value) {
        return Math.round(value * binding.card.getResources().getDisplayMetrics().density);
    }
}

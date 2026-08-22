package com.fongmi.android.tv.ui.holder;

import android.graphics.Color;
import android.graphics.Typeface;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeListBinding;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;

public class EpisodeListHolder extends BaseEpisodeHolder {

    private final EpisodeAdapter.OnClickListener listener;
    private final AdapterEpisodeListBinding binding;

    public EpisodeListHolder(@NonNull AdapterEpisodeListBinding binding, EpisodeAdapter.OnClickListener listener) {
        super(binding.getRoot());
        this.binding = binding;
        this.listener = listener;
    }

    @Override
    public void initView(Episode item) {
        binding.card.setSelected(item.isSelected());
        binding.text.setSelected(true);
        binding.text.setTextColor(Color.parseColor(item.isSelected() ? "#5EF2C2" : "#E8EDF4"));
        binding.text.setTypeface(null, item.isSelected() ? Typeface.BOLD : Typeface.NORMAL);
        binding.text.setText(getShowText(item));
        binding.card.setOnClickListener(v -> listener.onItemClick(item));
    }

    private String getShowText(Episode item) {
        if (Setting.isShortShow() && item.getNumber() > 0) return "第" + item.getNumber() + "集";
        return item.getDesc().concat(item.getName());
    }
}

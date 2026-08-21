package com.fongmi.android.tv.ui.holder;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.AdapterEpisodeGridBinding;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.BaseEpisodeHolder;
import com.google.android.flexbox.FlexboxLayoutManager;

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
        String text = item.getDesc().concat(item.getName());
        binding.text.setText(text);
        binding.card.setOnClickListener(v -> listener.onItemClick(item));
        ViewGroup.LayoutParams lp = binding.card.getLayoutParams();
        if (lp instanceof FlexboxLayoutManager.LayoutParams) {
            FlexboxLayoutManager.LayoutParams flp = (FlexboxLayoutManager.LayoutParams) lp;
            int len = text.length();
            flp.setFlexBasisPercent(len <= 3 ? 0.25f : (len >= 10 ? 1.0f : 0.5f));
            binding.card.setLayoutParams(flp);
        }
    }
}

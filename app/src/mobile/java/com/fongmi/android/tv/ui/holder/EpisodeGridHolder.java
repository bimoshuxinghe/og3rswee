package com.fongmi.android.tv.ui.holder;

import android.graphics.Color;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;

import androidx.annotation.NonNull;

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
        binding.text.setGravity(Gravity.CENTER);
        binding.text.setSelected(item.isSelected());
        binding.text.setTextColor(Color.parseColor(item.isSelected() ? "#5EF2C2" : "#E8EDF4"));
        binding.text.setTypeface(null, item.isSelected() ? Typeface.BOLD : Typeface.NORMAL);
        if (Setting.isShortShow() && item.getNumber() > 0) {
            binding.text.setPadding(dp(6), dp(3), dp(6), dp(3));
            binding.text.setText("第" + item.getNumber() + "集");
        } else {
            binding.text.setPadding(dp(12), dp(6), dp(12), dp(6));
            binding.text.setText(item.getDesc().concat(item.getName()));
        }
        fitTextSize();
        binding.text.setOnClickListener(v -> listener.onItemClick(item));
    }

    /**
     * 布局完成后按实际可用宽度精确缩放字号（8~14sp），保证集数完整显示。
     */
    private void fitTextSize() {
        binding.text.post(() -> {
            int avail = binding.text.getWidth() - binding.text.getPaddingLeft() - binding.text.getPaddingRight();
            if (avail <= 0) return;
            float textW = binding.text.getPaint().measureText(binding.text.getText().toString());
            if (textW <= 0) return;
            float targetPx = binding.text.getTextSize() * avail / textW;
            float minPx = dp(8);
            float maxPx = dp(14);
            binding.text.setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.max(minPx, Math.min(maxPx, targetPx)));
        });
    }

    private int dp(int value) {
        return Math.round(value * binding.text.getResources().getDisplayMetrics().density);
    }
}

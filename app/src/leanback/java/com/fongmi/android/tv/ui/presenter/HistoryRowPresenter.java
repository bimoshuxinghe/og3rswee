package com.fongmi.android.tv.ui.presenter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.leanback.widget.HorizontalGridView;
import androidx.leanback.widget.ItemBridgeAdapter;
import androidx.leanback.widget.Presenter;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.HistoryRow;
import com.fongmi.android.tv.databinding.AdapterHomeHistoryBinding;
import com.fongmi.android.tv.utils.ResUtil;

public class HistoryRowPresenter extends Presenter {

    @NonNull
    @Override
    public Presenter.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.adapter_home_history, parent, false);
        return new ViewHolder(view);
    }

    private int getCardHeight() {
        int space = ResUtil.dp2px(200);
        int width = (ResUtil.getScreenWidth() - space) / 5;
        return (int) (width * 0.45f);
    }

    @Override
    public void onBindViewHolder(@NonNull Presenter.ViewHolder viewHolder, Object item) {
        ViewHolder holder = (ViewHolder) viewHolder;
        HistoryRow row = (HistoryRow) item;

        int cardHeight = getCardHeight();

        ViewGroup.LayoutParams leftParams = holder.binding.leftBar.getLayoutParams();
        if (leftParams != null) {
            leftParams.height = cardHeight;
            holder.binding.leftBar.setLayoutParams(leftParams);
        }

        ViewGroup.LayoutParams rightParams = holder.binding.rightBar.getLayoutParams();
        if (rightParams != null) {
            rightParams.height = cardHeight;
            holder.binding.rightBar.setLayoutParams(rightParams);
        }

        holder.binding.grid.setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        holder.binding.grid.setHorizontalSpacing(ResUtil.dp2px(16));
        holder.binding.grid.setAdapter(new ItemBridgeAdapter(row.getAdapter()));
    }

    @Override
    public void onUnbindViewHolder(@NonNull Presenter.ViewHolder viewHolder) {
        ViewHolder holder = (ViewHolder) viewHolder;
        holder.binding.grid.setAdapter(null);
    }

    public static class ViewHolder extends Presenter.ViewHolder {

        private final AdapterHomeHistoryBinding binding;

        public ViewHolder(@NonNull View view) {
            super(view);
            this.binding = AdapterHomeHistoryBinding.bind(view);
        }
    }
}

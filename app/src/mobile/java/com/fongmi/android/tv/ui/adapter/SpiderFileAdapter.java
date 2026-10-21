package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterSpiderFileBinding;
import com.fongmi.android.tv.utils.SpiderScanner;

import java.util.List;

/**
 * 本地爬虫候选列表：整行可点切换勾选，右侧徽标显示是否已加入线路。
 */
public class SpiderFileAdapter extends RecyclerView.Adapter<SpiderFileAdapter.ViewHolder> {

    private final List<SpiderScanner.Item> mItems;
    private final Runnable mListener;

    public SpiderFileAdapter(List<SpiderScanner.Item> items, Runnable listener) {
        this.mItems = items;
        this.mListener = listener;
    }

    public void toggle(int position) {
        SpiderScanner.Item item = mItems.get(position);
        item.checked = !item.checked;
        notifyItemChanged(position);
        if (mListener != null) mListener.run();
    }

    public void checkAll(boolean checked) {
        for (SpiderScanner.Item item : mItems) item.checked = checked;
        notifyDataSetChanged();
        if (mListener != null) mListener.run();
    }

    public void invert() {
        for (SpiderScanner.Item item : mItems) item.checked = !item.checked;
        notifyDataSetChanged();
        if (mListener != null) mListener.run();
    }

    /** 与"已导入"状态不一致的条目数量（点应用后的变更数）。 */
    public int pendingCount() {
        int count = 0;
        for (SpiderScanner.Item item : mItems) {
            if (item.checked != item.imported) count++;
        }
        return count;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterSpiderFileBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        SpiderScanner.Item item = mItems.get(position);
        holder.binding.name.setText(item.name);
        holder.binding.desc.setText(item.sizeText() + " · " + (item.isPy() ? "py" : "js"));
        holder.binding.check.setChecked(item.checked);
        holder.binding.badge.setVisibility(item.imported ? android.view.View.VISIBLE : android.view.View.GONE);
        holder.binding.row.setOnClickListener(v -> toggle(position));
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterSpiderFileBinding binding;

        ViewHolder(AdapterSpiderFileBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.databinding.AdapterPicBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

public class PicAdapter extends RecyclerView.Adapter<PicAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<String> items = new ArrayList<>();

    public PicAdapter(OnClickListener listener) {
        this.listener = listener;
    }

    public interface OnClickListener {
        void onItemClick(String url);
    }

    public void setItems(List<String> items) {
        this.items.clear();
        this.items.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterPicBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String url = items.get(position);
        ImgUtil.load("", url, holder.binding.image, false);
        holder.binding.image.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(url);
        });
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterPicBinding binding;

        ViewHolder(@NonNull AdapterPicBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

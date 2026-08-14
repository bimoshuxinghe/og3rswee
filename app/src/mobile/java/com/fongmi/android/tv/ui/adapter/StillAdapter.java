package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterStillBinding;

import java.util.ArrayList;
import java.util.List;

public class StillAdapter extends RecyclerView.Adapter<StillAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<String> items = new ArrayList<>();

    public StillAdapter(OnClickListener listener) {
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
        return new ViewHolder(AdapterStillBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String url = items.get(position);
        Glide.with(holder.binding.getRoot())
                .load(url)
                .centerCrop()
                .error(R.drawable.artwork)
                .into(holder.binding.image);
        holder.binding.getRoot().setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(url);
        });
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterStillBinding binding;

        ViewHolder(@NonNull AdapterStillBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

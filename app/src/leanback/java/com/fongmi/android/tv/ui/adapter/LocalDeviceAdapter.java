package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.AdapterLocalDeviceBinding;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class LocalDeviceAdapter extends RecyclerView.Adapter<LocalDeviceAdapter.ViewHolder> {

    private final OnClickListener listener;
    private final List<Site> mItems;
    private int width, height;

    public LocalDeviceAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        setLayoutSize();
    }

    public interface OnClickListener {
        void onItemClick(Site item);
        void onItemLongClick(Site item);
    }

    private void setLayoutSize() {
        int space = ResUtil.dp2px(48) + ResUtil.dp2px(16 * 3); // 4 columns grid space
        int base = ResUtil.getScreenWidth() - space;
        width = base / 4;
        height = (int) (width * 0.75f);
    }

    public void setItems(List<Site> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    private String getDisplayUrl(String url) {
        if (url == null) return "";
        try {
            int schemeIdx = url.indexOf("://");
            int lastAtIdx = url.lastIndexOf("@");
            if (lastAtIdx != -1) {
                if (schemeIdx != -1 && lastAtIdx > schemeIdx) {
                    return url.substring(0, schemeIdx + 3) + url.substring(lastAtIdx + 1);
                } else if (schemeIdx == -1) {
                    return url.substring(lastAtIdx + 1);
                }
            }
        } catch (Exception ignored) {}
        return url;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ViewHolder holder = new ViewHolder(AdapterLocalDeviceBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        holder.binding.getRoot().getLayoutParams().width = width;
        holder.binding.getRoot().getLayoutParams().height = height;
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Site item = mItems.get(position);
        holder.binding.name.setText(item.getName());
        
        if ("local_file_system".equals(item.getKey())) {
            holder.binding.icon.setImageResource(R.drawable.ic_folder);
            holder.binding.detail.setText(R.string.home_local);
            holder.binding.getRoot().setOnLongClickListener(null);
        } else if ("add_connection_dummy".equals(item.getKey())) {
            holder.binding.icon.setImageResource(R.drawable.ic_action_choose);
            holder.binding.detail.setText("");
            holder.binding.getRoot().setOnLongClickListener(null);
        } else {
            holder.binding.icon.setImageResource(R.drawable.ic_net_ethernet);
            holder.binding.detail.setText(getDisplayUrl(item.getExt()));
            holder.binding.getRoot().setOnLongClickListener(v -> {
                listener.onItemLongClick(item);
                return true;
            });
        }

        holder.binding.getRoot().setOnClickListener(v -> listener.onItemClick(item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterLocalDeviceBinding binding;

        public ViewHolder(@NonNull AdapterLocalDeviceBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            setFocusListener();
        }

        private void setFocusListener() {
            itemView.setOnFocusChangeListener((v, hasFocus) -> {
                if (hasFocus) {
                    v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(150).start();
                    v.setTranslationZ(10f);
                    v.setSelected(true);
                } else {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    v.setTranslationZ(0f);
                    v.setSelected(false);
                }
            });
        }
    }
}

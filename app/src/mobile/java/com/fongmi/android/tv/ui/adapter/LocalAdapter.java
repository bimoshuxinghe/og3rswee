package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.AdapterLocalBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

public class LocalAdapter extends RecyclerView.Adapter<LocalAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<Site> mItems;

    public LocalAdapter(OnClickListener listener) {
        mListener = listener;
        mItems = new ArrayList<>();
    }

    public interface OnClickListener {
        void onItemClick(Site item);
        boolean onItemLongClick(Site item);
    }

    public void addAll(List<Site> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterLocalBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Site item = mItems.get(position);
        holder.binding.name.setText(item.getName());
        
        if ("local_file_system".equals(item.getKey())) {
            holder.binding.ext.setText(R.string.home_local_brief);
        } else {
            // Displays protocol and host
            String typeStr = "csp_Smb".equals(item.getApi()) ? "SMB" : "WebDAV";
            String extUrl = item.getExt();
            holder.binding.ext.setText(String.format("%s: %s", typeStr, getDisplayUrl(extUrl)));
        }
        ImgUtil.load(item.getName(), "", holder.binding.icon);

        holder.itemView.setOnClickListener(v -> mListener.onItemClick(item));
        holder.itemView.setOnLongClickListener(v -> mListener.onItemLongClick(item));
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

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterLocalBinding binding;

        ViewHolder(@NonNull AdapterLocalBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.AdapterAutoSiteBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动站点二级菜单的站点列表适配器：展示已添加的站点（主要是 AI/手动添加的 xbpq 站点），支持删除。
 */
public class AutoSiteListAdapter extends RecyclerView.Adapter<AutoSiteListAdapter.ViewHolder> {

    private final OnDeleteListener listener;
    private final List<Site> mItems;

    public interface OnDeleteListener {
        void onDelete(Site item);
    }

    public AutoSiteListAdapter(OnDeleteListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        addAll();
    }

    /** 只展示自动站点（api 为 csp_XBPQ 的站点，即 AI/手动通过本页添加的站点） */
    private void addAll() {
        for (Site site : VodConfig.get().getSites()) {
            if (isAutoSite(site)) mItems.add(site);
        }
    }

    public static boolean isAutoSite(Site site) {
        return "csp_XBPQ".equals(site.getApi()) || (site.getKey() != null && site.getKey().startsWith("xbpq_"));
    }

    public void remove(Site site) {
        int index = mItems.indexOf(site);
        if (index >= 0) {
            mItems.remove(index);
            notifyItemRemoved(index);
        }
    }

    public void add(Site site) {
        if (mItems.contains(site)) return;
        mItems.add(site);
        notifyItemInserted(mItems.size() - 1);
    }

    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterAutoSiteBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Site item = mItems.get(position);
        holder.binding.name.setText(item.getName());
        holder.binding.delete.setOnClickListener(v -> listener.onDelete(item));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        private final AdapterAutoSiteBinding binding;

        ViewHolder(@NonNull AdapterAutoSiteBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

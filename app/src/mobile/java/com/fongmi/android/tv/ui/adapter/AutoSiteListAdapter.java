package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.AdapterAutoSiteBinding;
import com.fongmi.android.tv.utils.AiSiteStore;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动站点二级菜单的站点列表：展示已添加的站点（AI/手动添加的 xbpq 站点）。
 * 行内 Switch 控制启停（持久化，禁用的站点不出现在线路列表），
 * 点击行弹出操作菜单（编辑配置 / 导出 JSON / 删除）。
 */
public class AutoSiteListAdapter extends RecyclerView.Adapter<AutoSiteListAdapter.ViewHolder> {

    private final OnSiteListener listener;
    private final List<Site> mItems;

    public interface OnSiteListener {
        void onMenu(Site item);

        void onToggle(Site item, boolean enabled);

        void onDelete(Site item);
    }

    public AutoSiteListAdapter(OnSiteListener listener) {
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
        if (mItems.contains(site)) {
            int index = mItems.indexOf(site);
            notifyItemChanged(index);
            return;
        }
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
        boolean enabled = !AiSiteStore.isDisabled(item.getKey());
        holder.binding.name.setText(item.getName());
        holder.binding.state.setText(enabled ? R.string.auto_site_enabled : R.string.auto_site_disabled);
        holder.binding.switcher.setOnCheckedChangeListener(null);
        holder.binding.switcher.setChecked(enabled);
        holder.binding.switcher.setOnCheckedChangeListener((v, checked) -> listener.onToggle(item, checked));
        holder.binding.name.setOnClickListener(v -> listener.onMenu(item));
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

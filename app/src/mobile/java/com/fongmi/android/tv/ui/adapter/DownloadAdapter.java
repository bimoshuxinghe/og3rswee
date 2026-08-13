package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Download;
import com.fongmi.android.tv.bean.DownloadGroup;
import com.fongmi.android.tv.databinding.AdapterDownloadBinding;
import com.fongmi.android.tv.databinding.AdapterDownloadChildBinding;
import com.fongmi.android.tv.utils.ImgUtil;

import java.util.ArrayList;
import java.util.List;

public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.ViewHolder> {

    private final OnClickListener mListener;
    private final List<DownloadGroup> mItems;

    public DownloadAdapter(OnClickListener listener) {
        this.mListener = listener;
        this.mItems = new ArrayList<>();
    }

    public interface OnClickListener {
        void onEpisodeAction(Download child);
        void onEpisodeDelete(Download child);
    }

    public void addAll(List<DownloadGroup> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public List<DownloadGroup> getItems() {
        return mItems;
    }

    public void updateItem(RecyclerView recyclerView, Download item) {
        for (int i = 0; i < mItems.size(); i++) {
            DownloadGroup group = mItems.get(i);
            for (int j = 0; j < group.getDownloads().size(); j++) {
                Download d = group.getDownloads().get(j);
                if (d.getId() == item.getId()) {
                    group.getDownloads().set(j, item);
                    
                    // 局部更新大条目上的统计数字
                    RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(i);
                    if (holder instanceof ViewHolder) {
                        ViewHolder vh = (ViewHolder) holder;
                        
                        int total = group.getDownloads().size();
                        int completed = 0;
                        for (Download child : group.getDownloads()) {
                            if (child.getStatus() == Download.STATUS_COMPLETED) completed++;
                        }
                        vh.binding.summary.setText(String.format("共 %d 集，已完成 %d 集", total, completed));

                        // 局部更新子 View
                        int childCount = vh.binding.childContainer.getChildCount();
                        for (int c = 0; c < childCount; c++) {
                            View childView = vh.binding.childContainer.getChildAt(c);
                            if (childView.getTag() != null && (int) childView.getTag() == item.getId()) {
                                updateChildView(childView, item);
                                return;
                            }
                        }
                    }
                    return;
                }
            }
        }
    }

    private void updateChildView(View childView, Download child) {
        ProgressBar progress = childView.findViewById(R.id.progress);
        com.google.android.material.textview.MaterialTextView status = childView.findViewById(R.id.status);
        ImageView actionControl = childView.findViewById(R.id.actionControl);

        if (progress != null) progress.setProgress(child.getProgress());
        if (status != null) {
            String statusText = "";
            switch (child.getStatus()) {
                case Download.STATUS_WAIT:
                    statusText = "等待中";
                    break;
                case Download.STATUS_DOWNLOADING:
                    statusText = "下载中: " + child.getProgress() + "%";
                    break;
                case Download.STATUS_PAUSE:
                    statusText = "已暂停: " + child.getProgress() + "%";
                    break;
                case Download.STATUS_COMPLETED:
                    statusText = "下载完成";
                    break;
                case Download.STATUS_ERROR:
                    statusText = "下载失败";
                    break;
            }
            status.setText(statusText);
        }
        if (actionControl != null) {
            int controlIcon = androidx.media3.ui.R.drawable.exo_icon_play;
            switch (child.getStatus()) {
                case Download.STATUS_WAIT:
                case Download.STATUS_DOWNLOADING:
                    controlIcon = androidx.media3.ui.R.drawable.exo_icon_pause;
                    break;
                default:
                    controlIcon = androidx.media3.ui.R.drawable.exo_icon_play;
                    break;
            }
            actionControl.setImageResource(controlIcon);
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterDownloadBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        DownloadGroup group = mItems.get(position);
        holder.binding.name.setText(group.getVodName());
        ImgUtil.load(group.getVodName(), group.getVodPic(), holder.binding.image);

        int total = group.getDownloads().size();
        int completed = 0;
        for (Download d : group.getDownloads()) {
            if (d.getStatus() == Download.STATUS_COMPLETED) completed++;
        }
        holder.binding.summary.setText(String.format("共 %d 集，已完成 %d 集", total, completed));

        // 展开与折叠控制
        if (group.isExpanded()) {
            holder.binding.childContainer.setVisibility(View.VISIBLE);
            holder.binding.arrow.setRotation(180);
        } else {
            holder.binding.childContainer.setVisibility(View.GONE);
            holder.binding.arrow.setRotation(0);
        }

        // 动态添加子 View
        holder.binding.childContainer.removeAllViews();
        for (Download child : group.getDownloads()) {
            AdapterDownloadChildBinding childBinding = AdapterDownloadChildBinding.inflate(
                    LayoutInflater.from(holder.itemView.getContext()), holder.binding.childContainer, false
            );
            childBinding.getRoot().setTag(child.getId());
            childBinding.episodeName.setText(child.getEpisodeName());
            childBinding.progress.setProgress(child.getProgress());

            String statusText = "";
            int controlIcon = androidx.media3.ui.R.drawable.exo_icon_play;
            switch (child.getStatus()) {
                case Download.STATUS_WAIT:
                    statusText = "等待中";
                    controlIcon = androidx.media3.ui.R.drawable.exo_icon_pause;
                    break;
                case Download.STATUS_DOWNLOADING:
                    statusText = "下载中: " + child.getProgress() + "%";
                    controlIcon = androidx.media3.ui.R.drawable.exo_icon_pause;
                    break;
                case Download.STATUS_PAUSE:
                    statusText = "已暂停: " + child.getProgress() + "%";
                    controlIcon = androidx.media3.ui.R.drawable.exo_icon_play;
                    break;
                case Download.STATUS_COMPLETED:
                    statusText = "下载完成";
                    controlIcon = androidx.media3.ui.R.drawable.exo_icon_play;
                    break;
                case Download.STATUS_ERROR:
                    statusText = "下载失败";
                    controlIcon = androidx.media3.ui.R.drawable.exo_icon_play;
                    break;
            }
            childBinding.status.setText(statusText);
            childBinding.actionControl.setImageResource(controlIcon);

            childBinding.actionControl.setOnClickListener(v -> mListener.onEpisodeAction(child));
            childBinding.actionDelete.setOnClickListener(v -> mListener.onEpisodeDelete(child));

            holder.binding.childContainer.addView(childBinding.getRoot());
        }

        holder.binding.parentLayout.setOnClickListener(v -> {
            group.setExpanded(!group.isExpanded());
            notifyItemChanged(position);
        });
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final AdapterDownloadBinding binding;

        public ViewHolder(@NonNull AdapterDownloadBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

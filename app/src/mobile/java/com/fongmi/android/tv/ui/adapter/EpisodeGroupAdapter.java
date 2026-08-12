package com.fongmi.android.tv.ui.adapter;

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.AdapterEpisodeGroupBinding;

import java.util.ArrayList;
import java.util.List;

/**
 * 选集数字分组跳转条适配器。
 * 每个分组对应剧集列表里连续的一段（如 1-50、51-100），点击后跳转到该段起始位置。
 */
public class EpisodeGroupAdapter extends RecyclerView.Adapter<EpisodeGroupAdapter.ViewHolder> {

    public interface OnGroupClickListener {
        void onGroupClick(int startPosition);
    }

    public static class Group {
        public final int start; // 0-based 剧集起始索引
        public final int end;   // 0-based 剧集结束索引（含）
        public final String label;

        public Group(int start, int end, String label) {
            this.start = start;
            this.end = end;
            this.label = label;
        }
    }

    private final List<Group> mGroups = new ArrayList<>();
    private final OnGroupClickListener mListener;
    private int mSelected = 0;

    public EpisodeGroupAdapter(OnGroupClickListener listener) {
        this.mListener = listener;
    }

    public void setGroups(List<Group> groups) {
        mGroups.clear();
        mGroups.addAll(groups);
        mSelected = 0;
        notifyDataSetChanged();
    }

    public void setSelected(int position) {
        if (position < 0 || position >= mGroups.size() || position == mSelected) return;
        int old = mSelected;
        mSelected = position;
        notifyItemChanged(old);
        notifyItemChanged(mSelected);
    }

    public int getSelected() {
        return mSelected;
    }

    /** 根据剧集在列表中的绝对位置，返回其所属分组的索引。 */
    public int getGroupIndexForEpisode(int episodePosition) {
        for (int i = 0; i < mGroups.size(); i++) {
            Group g = mGroups.get(i);
            if (episodePosition >= g.start && episodePosition <= g.end) return i;
        }
        return mGroups.isEmpty() ? 0 : mGroups.size() - 1;
    }

    /**
     * 根据总集数生成数字分组（如 1-50 / 51-100 / …）。
     * 分组粒度自适应：<=1000 集按 50 一组，>1000 按 100，>2000 按 200。
     */
    public static List<Group> buildGroups(int total) {
        List<Group> groups = new ArrayList<>();
        if (total <= 0) return groups;
        int size = total > 2000 ? 200 : (total > 1000 ? 100 : 50);
        int idx = 0;
        while (idx < total) {
            int start = idx;
            int end = Math.min(idx + size, total) - 1;
            groups.add(new Group(start, end, (start + 1) + "-" + (end + 1)));
            idx = end + 1;
        }
        return groups;
    }

    @Override
    public int getItemCount() {
        return mGroups.size();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolder(AdapterEpisodeGroupBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Group group = mGroups.get(position);
        boolean selected = position == mSelected;
        holder.binding.text.setText(group.label);
        holder.binding.card.setBackgroundResource(selected ? R.drawable.shape_episode_group_selected : R.drawable.shape_episode);
        holder.binding.text.setTextColor(selected ? Color.parseColor("#0B0F14") : Color.parseColor("#EEF2F8"));
        holder.binding.getRoot().setOnClickListener(v -> {
            setSelected(position);
            mListener.onGroupClick(group.start);
        });
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        private final AdapterEpisodeGroupBinding binding;

        ViewHolder(AdapterEpisodeGroupBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}

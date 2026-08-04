package com.fongmi.android.tv.ui.adapter;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.fongmi.android.tv.bean.Channel;
import com.fongmi.android.tv.bean.Epg;
import com.fongmi.android.tv.bean.EpgData;
import com.fongmi.android.tv.databinding.AdapterChannelBinding;
import com.fongmi.android.tv.databinding.AdapterChannelPortraitBinding;

import java.util.ArrayList;
import java.util.List;

public class ChannelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private final OnClickListener listener;
    private final List<Channel> mItems;
    private boolean isPortrait;

    public ChannelAdapter(OnClickListener listener) {
        this.listener = listener;
        this.mItems = new ArrayList<>();
        this.isPortrait = false;
    }

    public void setPortrait(boolean portrait) {
        this.isPortrait = portrait;
    }

    public interface OnClickListener {

        void onItemClick(Channel item);

        boolean onLongClick(Channel item);
    }

    public void clear() {
        mItems.clear();
        notifyDataSetChanged();
    }

    public void addAll(List<Channel> items) {
        mItems.clear();
        mItems.addAll(items);
        notifyDataSetChanged();
    }

    public void remove(Channel item) {
        int position = mItems.indexOf(item);
        if (position == -1) return;
        mItems.remove(position);
        notifyItemRemoved(position);
    }

    public void setSelected(int position) {
        if (position == -1) return;
        for (int i = 0; i < mItems.size(); i++) mItems.get(i).setSelected(i == position);
        notifyItemRangeChanged(0, getItemCount());
    }

    public int setSelected(Channel channel) {
        int position = mItems.indexOf(channel);
        setSelected(position);
        return position;
    }

    @Override
    public int getItemCount() {
        return mItems.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (isPortrait) {
            return new PortraitViewHolder(AdapterChannelPortraitBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        } else {
            return new ViewHolder(AdapterChannelBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Channel item = mItems.get(position);
        if (isPortrait) {
            bindPortrait((PortraitViewHolder) holder, item);
        } else {
            bindLandscape((ViewHolder) holder, item);
        }
    }

    private void bindLandscape(ViewHolder holder, Channel item) {
        item.loadLogo(holder.binding.logo);
        holder.binding.name.setText(item.getShow());
        holder.binding.number.setText(item.getNumber());
        holder.binding.getRoot().setSelected(item.isSelected());
        holder.binding.getRoot().setOnClickListener(view -> listener.onItemClick(item));
        holder.binding.getRoot().setOnLongClickListener(view -> listener.onLongClick(item));

        android.content.Context context = holder.binding.getRoot().getContext();
        int textColorRes = com.fongmi.android.tv.R.color.selector_text;
        holder.binding.name.setTextColor(androidx.appcompat.content.res.AppCompatResources.getColorStateList(context, textColorRes));
        holder.binding.number.setTextColor(androidx.appcompat.content.res.AppCompatResources.getColorStateList(context, textColorRes));
        holder.binding.epg.setTextColor(android.graphics.Color.parseColor("#CCFFFFFF"));

        Epg epg = item.getData();
        EpgData currentEpg = null;
        for (EpgData data : epg.getList()) {
            if (data.isInRange()) {
                currentEpg = data;
                break;
            }
        }
        if (currentEpg != null) {
            String titleText = currentEpg.getTitle();
            holder.binding.epg.setSingleLine(true);
            long total = currentEpg.getEndTime() - currentEpg.getStartTime();
            if (total > 0) {
                long elapsed = System.currentTimeMillis() - currentEpg.getStartTime();
                int percent = (int) (elapsed * 100 / total);
                percent = Math.max(0, Math.min(100, percent));
                holder.binding.epg.setText(titleText);
                holder.binding.epg.setVisibility(android.view.View.VISIBLE);
                holder.binding.progress.setProgress(percent);
                holder.binding.progress.setVisibility(android.view.View.VISIBLE);
            } else {
                holder.binding.epg.setText(titleText);
                holder.binding.epg.setVisibility(android.view.View.VISIBLE);
                holder.binding.progress.setVisibility(android.view.View.GONE);
            }
        } else {
            holder.binding.epg.setVisibility(android.view.View.GONE);
            holder.binding.progress.setVisibility(android.view.View.GONE);
        }
    }

    private void bindPortrait(PortraitViewHolder holder, Channel item) {
        item.loadLogo(holder.binding.logo);
        holder.binding.name.setText(item.getShow());
        holder.binding.number.setText(item.getNumber());
        holder.binding.getRoot().setSelected(item.isSelected());
        holder.binding.getRoot().setOnClickListener(view -> listener.onItemClick(item));
        holder.binding.getRoot().setOnLongClickListener(view -> listener.onLongClick(item));
        holder.binding.playing.setVisibility(item.isSelected() ? android.view.View.VISIBLE : android.view.View.GONE);

        Epg epg = item.getData();
        EpgData currentEpg = null;
        for (EpgData data : epg.getList()) {
            if (data.isInRange()) {
                currentEpg = data;
                break;
            }
        }
        if (currentEpg != null) {
            holder.binding.epg.setText(currentEpg.getTitle() + "  " + currentEpg.getTime());
            holder.binding.epg.setVisibility(android.view.View.VISIBLE);
        } else {
            holder.binding.epg.setText("精彩节目");
            holder.binding.epg.setVisibility(android.view.View.VISIBLE);
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof ViewHolder) {
            Glide.with(((ViewHolder) holder).binding.logo).clear(((ViewHolder) holder).binding.logo);
        } else if (holder instanceof PortraitViewHolder) {
            Glide.with(((PortraitViewHolder) holder).binding.logo).clear(((PortraitViewHolder) holder).binding.logo);
        }
    }

    public class ViewHolder extends RecyclerView.ViewHolder {

        private final AdapterChannelBinding binding;

        ViewHolder(@NonNull AdapterChannelBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    public class PortraitViewHolder extends RecyclerView.ViewHolder {

        private final AdapterChannelPortraitBinding binding;

        PortraitViewHolder(@NonNull AdapterChannelPortraitBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }
}
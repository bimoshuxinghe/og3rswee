package com.fongmi.android.tv.ui.dialog;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.DialogEpisodeListBinding;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.List;

public class EpisodeListDialog extends BaseSideSheetDialog implements EpisodeAdapter.OnClickListener {

    private DialogEpisodeListBinding binding;
    private EpisodeAdapter adapter;
    private List<Episode> episodes;
    private int spanCount;

    public static EpisodeListDialog create() {
        return new EpisodeListDialog();
    }

    public EpisodeListDialog episodes(List<Episode> episodes) {
        this.episodes = episodes;
        return this;
    }

    public void show(FragmentActivity activity) {
        for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof EpisodeListDialog) return;
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogEpisodeListBinding.inflate(inflater, container, false);
    }

    @Override
    protected int getWidth() {
        if (ResUtil.isLand(requireActivity())) return ResUtil.getScreenWidth() / 3;
        int minWidth = ResUtil.dp2px(320);
        int maxWidth = ResUtil.getScreenWidth() / 2;
        for (Episode item : episodes) minWidth = Math.max(minWidth, ResUtil.getTextWidth(item.getName(), 14) * 2);
        return Math.min(minWidth, maxWidth);
    }

    @Override
    protected void initView() {
        if (ResUtil.isLand(requireActivity())) binding.getRoot().setBackgroundResource(R.drawable.bg_side_sheet_land);
        setRecyclerView();
        adapter.addAll(episodes);
        binding.recycler.scrollToPosition(adapter.getPosition());
    }

    private void setRecyclerView() {
        spanCount = ResUtil.isLand(requireActivity()) ? 1 : 2;
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setLayoutManager(new GridLayoutManager(getContext(), spanCount));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(spanCount, 8));
        binding.recycler.setAdapter(adapter = new EpisodeAdapter(this, ViewType.GRID));
    }

    @Override
    public void onItemClick(Episode item) {
        ((EpisodeAdapter.OnClickListener) requireActivity()).onItemClick(item);
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        Window window = getDialog().getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.getDecorView().setBackgroundColor(Color.TRANSPARENT);
        }
        FrameLayout sheet = getDialog().findViewById(com.google.android.material.R.id.m3_side_sheet);
        if (sheet != null) {
            clearBackground(sheet);
            sheet.setBackgroundResource(ResUtil.isLand(requireActivity()) ? R.drawable.bg_side_sheet_land : R.drawable.bg_side_sheet);
        }
    }

    private void clearBackground(View view) {
        View current = view;
        while (current != null) {
            current.setBackgroundColor(Color.TRANSPARENT);
            if (!(current.getParent() instanceof View)) break;
            current = (View) current.getParent();
        }
    }
}

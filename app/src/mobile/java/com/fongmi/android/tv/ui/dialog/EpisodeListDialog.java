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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.bean.Episode;
import com.fongmi.android.tv.databinding.DialogEpisodeListBinding;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.ui.adapter.EpisodeAdapter;
import com.fongmi.android.tv.ui.adapter.EpisodeGroupAdapter;
import com.fongmi.android.tv.ui.base.ViewType;
import com.fongmi.android.tv.ui.custom.SpaceItemDecoration;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.ArrayList;
import java.util.List;

public class EpisodeListDialog extends BaseSideSheetDialog implements EpisodeAdapter.OnClickListener {

    private DialogEpisodeListBinding binding;
    private EpisodeAdapter adapter;
    private EpisodeGroupAdapter mGroupAdapter;
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
        for (Episode item : episodes) minWidth = Math.max(minWidth, ResUtil.getTextWidth(item.getName(), 14) + ResUtil.dp2px(48));
        return Math.min(minWidth, maxWidth);
    }

    @Override
    protected void initView() {
        if (ResUtil.isLand(requireActivity())) binding.getRoot().setBackgroundResource(R.drawable.bg_side_sheet_land);
        setRecyclerView();
        adapter.addAll(episodes);
        binding.recycler.scrollToPosition(adapter.getPosition());
        setGroupBar();
    }

    private void setRecyclerView() {
        spanCount = 1;
        binding.recycler.setHasFixedSize(true);
        binding.recycler.setItemAnimator(null);
        binding.recycler.setLayoutManager(new GridLayoutManager(getContext(), spanCount));
        binding.recycler.addItemDecoration(new SpaceItemDecoration(spanCount, 8));
        binding.recycler.setAdapter(adapter = new EpisodeAdapter(this, ViewType.GRID));
    }

    /**
     * 当剧集数量较多时，在选集列表顶部显示数字分组跳转条（如 1-50 / 51-100 / …）。
     * 点击分组直接跳转到该段起始位置，并随列表滚动自动高亮当前所在分组。
     */
    private void setGroupBar() {
        int total = episodes.size();
        int size = getGroupSize(total);
        int count = (total + size - 1) / size;
        if (count <= 1) {
            binding.groups.setVisibility(View.GONE);
            return;
        }
        List<EpisodeGroupAdapter.Group> groups = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int start = i * size;
            int end = Math.min(start + size, total) - 1;
            groups.add(new EpisodeGroupAdapter.Group(start, end, (start + 1) + "-" + (end + 1)));
        }
        binding.groups.setLayoutManager(new LinearLayoutManager(requireActivity(), LinearLayoutManager.HORIZONTAL, false));
        mGroupAdapter = new EpisodeGroupAdapter(start -> binding.recycler.scrollToPosition(start));
        binding.groups.setAdapter(mGroupAdapter);
        mGroupAdapter.setGroups(groups);
        int selected = mGroupAdapter.getGroupIndexForEpisode(adapter.getPosition());
        mGroupAdapter.setSelected(selected);
        binding.groups.scrollToPosition(selected);
        binding.groups.setVisibility(View.VISIBLE);
        binding.recycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                LinearLayoutManager lm = (LinearLayoutManager) recyclerView.getLayoutManager();
                if (lm == null || mGroupAdapter == null) return;
                int idx = mGroupAdapter.getGroupIndexForEpisode(lm.findFirstVisibleItemPosition());
                if (idx != mGroupAdapter.getSelected()) {
                    mGroupAdapter.setSelected(idx);
                    binding.groups.smoothScrollToPosition(idx);
                }
            }
        });
    }

    /** 根据总集数自适应分组粒度：少量剧集按 50 一组，上千集自动加大到 100/200。 */
    private int getGroupSize(int total) {
        if (total > 2000) return 200;
        if (total > 1000) return 100;
        return 50;
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

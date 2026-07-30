package com.fongmi.android.tv.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.FragmentNasManageBinding;
import com.fongmi.android.tv.databinding.AdapterNasManageItemBinding;
import com.fongmi.android.tv.ui.activity.LocalResourceActivity;
import com.fongmi.android.tv.ui.activity.NasEditActivity;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class NasManageFragment extends Fragment {

    private FragmentNasManageBinding binding;
    private NasManageAdapter adapter;

    public static NasManageFragment newInstance() {
        return new NasManageFragment();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentNasManageBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        binding.recycler.setLayoutManager(new LinearLayoutManager(getContext()));
        binding.recycler.setAdapter(adapter = new NasManageAdapter());
        binding.addConnection.setOnClickListener(v -> NasEditActivity.start(getActivity()));
        loadNasList();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadNasList();
    }

    private void loadNasList() {
        List<Site> nasSites = new ArrayList<>();
        for (Site site : Site.findAll()) {
            if (site.getKey() != null && site.getKey().startsWith("local_nas_")) {
                nasSites.add(site);
            }
        }
        adapter.setItems(nasSites);
    }

    class NasManageAdapter extends RecyclerView.Adapter<NasManageAdapter.ViewHolder> {
        private final List<Site> items = new ArrayList<>();

        public void setItems(List<Site> items) {
            this.items.clear();
            this.items.addAll(items);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new ViewHolder(AdapterNasManageItemBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            Site site = items.get(position);
            holder.binding.name.setText(site.getName());
            holder.binding.detail.setText(site.getExt());
            
            holder.binding.edit.setOnClickListener(v -> {
                NasEditActivity.start(getActivity(), site.getKey());
            });

            holder.binding.delete.setOnClickListener(v -> {
                new MaterialAlertDialogBuilder(getContext())
                        .setTitle(site.getName())
                        .setMessage(R.string.nas_delete_confirm)
                        .setPositiveButton(R.string.dialog_delete, (dialog, which) -> {
                            site.delete();
                            VodConfig.get().getSites().remove(site);
                            loadNasList();
                            if (getActivity() instanceof LocalResourceActivity) {
                                ((LocalResourceActivity) getActivity()).loadDevices();
                            }
                        })
                        .setNegativeButton(R.string.dialog_negative, null)
                        .show();
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        class ViewHolder extends RecyclerView.ViewHolder {
            private final AdapterNasManageItemBinding binding;

            public ViewHolder(AdapterNasManageItemBinding binding) {
                super(binding.getRoot());
                this.binding = binding;
            }
        }
    }
}

package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogProxySubscriptionBinding;
import com.fongmi.android.tv.proxy.ProxyNode;
import com.fongmi.android.tv.proxy.ProxySubscriptionManager;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.stream.Collectors;

public class ProxySubscriptionDialog extends BaseBottomSheetDialog {

    private DialogProxySubscriptionBinding binding;
    private Runnable callback;

    public static ProxySubscriptionDialog create() {
        return new ProxySubscriptionDialog();
    }

    public static void show(Fragment fragment, Runnable callback) {
        for (Fragment item : fragment.getChildFragmentManager().getFragments()) if (item instanceof ProxySubscriptionDialog) return;
        create().callback(callback).show(fragment.getChildFragmentManager(), null);
    }

    public ProxySubscriptionDialog callback(Runnable callback) {
        this.callback = callback;
        return this;
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return binding = DialogProxySubscriptionBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        binding.url.setText(Setting.getProxySubscriptionUrl());
        binding.enable.setChecked(Setting.isProxySubscriptionEnabled());
        setStatus();
    }

    @Override
    protected void initEvent() {
        binding.enable.setOnCheckedChangeListener((button, checked) -> {
            Setting.putProxySubscriptionEnabled(checked);
            if (checked) ProxySubscriptionManager.get().applySaved();
            else ProxySubscriptionManager.get().disable();
            setStatus();
            notifyChanged();
        });
        binding.update.setOnClickListener(this::onUpdate);
        binding.test.setOnClickListener(this::onTest);
        binding.auto.setOnClickListener(this::onAuto);
        binding.nodes.setOnClickListener(this::onNodes);
    }

    private void onUpdate(View view) {
        String url = binding.url.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            Notify.show(R.string.proxy_sub_empty);
            return;
        }
        Setting.putProxySubscriptionUrl(url);
        Notify.progress(requireActivity());
        Task.execute(() -> {
            try {
                List<ProxyNode> nodes = ProxySubscriptionManager.get().refresh(url);
                ProxyNode selected = ProxySubscriptionManager.get().autoSelect();
                App.post(() -> {
                    Notify.dismiss();
                    setStatus();
                    notifyChanged();
                    if (selected == null) Notify.show(getString(R.string.proxy_sub_no_supported, nodes.size()));
                    else Notify.show(getString(R.string.proxy_sub_selected, selected.getDisplay()));
                });
            } catch (Throwable e) {
                App.post(() -> {
                    Notify.dismiss();
                    Notify.show(Notify.getError(R.string.proxy_sub_fail, e));
                });
            }
        });
    }

    private void onTest(View view) {
        if (ProxySubscriptionManager.get().getNodes().isEmpty()) {
            Notify.show(R.string.proxy_sub_no_node);
            return;
        }
        Notify.progress(requireActivity());
        Task.execute(() -> {
            List<ProxyNode> nodes = ProxySubscriptionManager.get().testAll();
            ProxyNode fastest = getFastest(nodes);
            if (fastest != null) ProxySubscriptionManager.get().select(fastest);
            App.post(() -> {
                Notify.dismiss();
                setStatus();
                notifyChanged();
                if (fastest != null) Notify.show(getString(R.string.proxy_sub_test_done, fastest.getDisplay()));
                else Notify.show(R.string.proxy_sub_test_fail);
            });
        });
    }

    private void onAuto(View view) {
        if (ProxySubscriptionManager.get().getNodes().isEmpty()) {
            onUpdate(view);
            return;
        }
        Notify.progress(requireActivity());
        Task.execute(() -> {
            ProxyNode selected = ProxySubscriptionManager.get().autoSelect();
            App.post(() -> {
                Notify.dismiss();
                setStatus();
                notifyChanged();
                Notify.show(selected == null ? getString(R.string.proxy_sub_no_node) : getString(R.string.proxy_sub_selected, selected.getDisplay()));
            });
        });
    }

    private void onNodes(View view) {
        List<ProxyNode> nodes = ProxySubscriptionManager.get().getNodes();
        if (nodes.isEmpty()) {
            Notify.show(R.string.proxy_sub_no_node);
            return;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireActivity(), android.R.layout.simple_list_item_single_choice, getDisplays(nodes));
        new MaterialAlertDialogBuilder(requireActivity()).setTitle(R.string.proxy_sub_nodes).setNegativeButton(R.string.dialog_negative, null).setSingleChoiceItems(adapter, getSelectedIndex(nodes), (dialog, which) -> selectNode(dialog, nodes, adapter, which)).show();
    }

    private void selectNode(android.content.DialogInterface dialog, List<ProxyNode> nodes, ArrayAdapter<String> adapter, int which) {
        ProxyNode node = nodes.get(which);
        Notify.progress(requireActivity());
        Task.execute(() -> {
            long latency = ProxySubscriptionManager.get().testOne(node);
            boolean ok = latency > 0 && ProxySubscriptionManager.get().select(node);
            App.post(() -> {
                Notify.dismiss();
                if (!ok) {
                    adapter.clear();
                    adapter.addAll(getDisplays(nodes));
                    adapter.notifyDataSetChanged();
                    setStatus();
                    Notify.show(R.string.proxy_sub_no_node);
                    return;
                }
                binding.enable.setChecked(true);
                adapter.clear();
                adapter.addAll(getDisplays(nodes));
                adapter.notifyDataSetChanged();
                setStatus();
                notifyChanged();
            });
        });
    }

    private int getSelectedIndex(List<ProxyNode> nodes) {
        String selected = Setting.getProxySubscriptionSelected();
        String coreName = Setting.getProxySubscriptionCoreName();
        for (int i = 0; i < nodes.size(); i++) if (selected.equals(nodes.get(i).getUrl())) return i;
        for (int i = 0; i < nodes.size(); i++) if (!TextUtils.isEmpty(coreName) && coreName.equals(nodes.get(i).getName())) return i;
        return -1;
    }

    private ProxyNode getFastest(List<ProxyNode> nodes) {
        ProxyNode fastest = null;
        for (ProxyNode node : nodes) if (node.getLatency() > 0 && (fastest == null || node.getLatency() < fastest.getLatency())) fastest = node;
        return fastest;
    }

    private List<String> getDisplays(List<ProxyNode> nodes) {
        return nodes.stream().map(ProxyNode::getDisplay).collect(Collectors.toList());
    }

    private void setStatus() {
        ProxySubscriptionManager manager = ProxySubscriptionManager.get();
        List<ProxyNode> nodes = manager.getNodes();
        long tested = nodes.stream().filter(node -> node.getLatency() != -1).count();
        binding.status.setText(getString(R.string.proxy_sub_status, nodes.size(), tested));
        binding.hint.setVisibility(View.GONE);
    }

    private void notifyChanged() {
        if (callback != null) callback.run();
    }
}

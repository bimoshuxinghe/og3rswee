package com.fongmi.android.tv.ui.dialog;

import android.content.Context;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogProxySubscriptionNodesBinding;
import com.fongmi.android.tv.proxy.ProxyNode;
import com.fongmi.android.tv.proxy.ProxySubscriptionManager;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

public class ProxySubscriptionNodeDialog extends BaseAlertDialog {

    private DialogProxySubscriptionNodesBinding binding;
    private NodeAdapter adapter;
    private Runnable callback;
    private List<ProxyNode> nodes;
    private int current;

    public static ProxySubscriptionNodeDialog create() {
        return new ProxySubscriptionNodeDialog();
    }

    public ProxySubscriptionNodeDialog callback(Runnable callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogProxySubscriptionNodesBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        nodes = ProxySubscriptionManager.get().getNodes();
        current = Math.max(0, getSelectedIndex());
        adapter = new NodeAdapter(requireActivity(), nodes);
        binding.list.setAdapter(adapter);
        if (!nodes.isEmpty()) {
            binding.list.setItemChecked(current, true);
            binding.list.setSelection(current);
            binding.list.requestFocus();
        }
    }

    @Override
    protected void initEvent() {
        binding.list.setOnItemClickListener((parent, view, position, id) -> {
            current = position;
            binding.list.setItemChecked(current, true);
            adapter.notifyDataSetChanged();
            onSelect(view);
        });
        binding.list.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                current = position;
                binding.list.setItemChecked(current, true);
                adapter.notifyDataSetChanged();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        binding.list.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus || nodes.isEmpty()) return;
            current = binding.list.getSelectedItemPosition() >= 0 ? binding.list.getSelectedItemPosition() : current;
            binding.list.setItemChecked(current, true);
            adapter.notifyDataSetChanged();
        });
        binding.list.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_UP) return false;
            if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER) return false;
            onSelect(view);
            return true;
        });
        binding.select.setOnClickListener(this::onSelect);
        binding.test.setOnClickListener(this::onTest);
        binding.negative.setOnClickListener(view -> dismiss());
    }

    private ProxyNode getCurrentNode() {
        if (nodes == null || nodes.isEmpty()) return null;
        if (current < 0 || current >= nodes.size()) current = 0;
        return nodes.get(current);
    }

    private void onSelect(View view) {
        ProxyNode node = getCurrentNode();
        if (node == null) {
            Notify.show(R.string.proxy_sub_no_node);
            return;
        }
        Notify.progress(getActivity());
        Task.execute(() -> {
            long latency = ProxySubscriptionManager.get().testOne(node);
            boolean ok = ProxySubscriptionManager.get().select(node);
            App.post(() -> {
                Notify.dismiss();
                adapter.notifyDataSetChanged();
                if (ok) {
                    notifyChanged();
                    Notify.show(getString(R.string.proxy_sub_selected, node.getDisplay()) + (latency > 0 ? " · " + getString(R.string.proxy_sub_test_ok, latency) : " · " + getString(R.string.proxy_sub_test_fail)));
                } else {
                    Notify.show(R.string.proxy_sub_no_node);
                }
            });
        });
    }

    private void onTest(View view) {
        ProxyNode node = getCurrentNode();
        if (node == null) {
            Notify.show(R.string.proxy_sub_no_node);
            return;
        }
        Notify.progress(getActivity());
        Task.execute(() -> {
            long latency = ProxySubscriptionManager.get().testOne(node);
            App.post(() -> {
                Notify.dismiss();
                adapter.notifyDataSetChanged();
                notifyChanged();
                Notify.show(latency > 0 ? getString(R.string.proxy_sub_test_ok, latency) : getString(R.string.proxy_sub_test_fail));
            });
        });
    }

    private int getSelectedIndex() {
        String selected = Setting.getProxySubscriptionSelected();
        String coreName = Setting.getProxySubscriptionCoreName();
        for (int i = 0; i < nodes.size(); i++) if (selected.equals(nodes.get(i).getUrl())) return i;
        for (int i = 0; i < nodes.size(); i++) if (!TextUtils.isEmpty(coreName) && coreName.equals(nodes.get(i).getName())) return i;
        return -1;
    }

    private boolean isSelected(ProxyNode node) {
        String selected = Setting.getProxySubscriptionSelected();
        String coreName = Setting.getProxySubscriptionCoreName();
        return selected.equals(node.getUrl()) || (!TextUtils.isEmpty(coreName) && coreName.equals(node.getName()));
    }

    private void notifyChanged() {
        if (callback != null) callback.run();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.55f);
        if (binding != null && !nodes.isEmpty()) {
            binding.list.requestFocus();
            binding.list.setSelection(current);
            binding.list.setItemChecked(current, true);
        }
    }

    private class NodeAdapter extends ArrayAdapter<ProxyNode> {

        NodeAdapter(@NonNull Context context, List<ProxyNode> nodes) {
            super(context, R.layout.adapter_proxy_node, android.R.id.text1, nodes);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            ProxyNode node = getItem(position);
            boolean selected = node != null && isSelected(node);
            view.setSelected(selected);
            TextView text = view.findViewById(android.R.id.text1);
            if (node != null) {
                String prefix = (selected ? "* " : "  ");
                String display = node.getDisplay();
                String fullText = prefix + display;
                if (node.getLatency() != 0) {
                    // 使用 SpannableString 给延迟部分着色
                    android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(fullText);
                    String latencyStr = node.getLatency() > 0 ? " · " + node.getLatency() + "ms"
                            : node.getLatency() == -2 ? " · timeout" : "";
                    if (!latencyStr.isEmpty()) {
                        int start = fullText.length() - latencyStr.length();
                        int end = fullText.length();
                        ssb.setSpan(new android.text.style.ForegroundColorSpan(node.getLatencyColor()), start, end,
                                android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    text.setText(ssb);
                } else {
                    text.setText(fullText);
                }
            }
            return view;
        }
    }
}

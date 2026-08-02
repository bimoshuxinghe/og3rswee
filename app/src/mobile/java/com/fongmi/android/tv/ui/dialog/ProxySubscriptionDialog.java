package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogProxySubscriptionBinding;
import com.fongmi.android.tv.proxy.MihomoManager;
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
        binding.url.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onUpdate(v);
            return true;
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
                    if (selected != null) {
                        binding.enable.setChecked(true);
                        Notify.show(getString(R.string.proxy_sub_selected, selected.getDisplay()));
                    } else {
                        if (binding.enable.isChecked()) binding.enable.setChecked(false);
                        String error = MihomoManager.get().getLastError();
                        if (TextUtils.isEmpty(error)) error = "节点连接测试失败，未启用代理";
                        showErrorDialog("代理不可用", "已解析" + nodes.size() + "个节点，但连接测试失败\n\n" + error);
                    }
                    setStatus();
                    notifyChanged();
                    showNodeList(nodes);
                });
            } catch (Throwable e) {
                App.post(() -> {
                    Notify.dismiss();
                    showErrorDialog("订阅更新失败", e.getMessage() != null ? e.getMessage() : e.toString());
                });
            }
        });
    }

    private void onTest(View view) {
        if (ProxySubscriptionManager.get().isTesting()) {
            Notify.show("正在测速中...");
            return;
        }
        if (!ProxySubscriptionManager.get().hasNodes()) {
            Notify.show(R.string.proxy_sub_no_node);
            return;
        }
        Notify.progress(requireActivity());
        Task.execute(() -> {
            List<ProxyNode> nodes = ProxySubscriptionManager.get().testAll();
            ProxyNode fastest = getFastest(nodes);
            boolean applied = false;
            if (fastest != null) applied = ProxySubscriptionManager.get().select(fastest);
            boolean finalApplied = applied;
            App.post(() -> {
                Notify.dismiss();
                setStatus();
                notifyChanged();
                if (fastest != null && finalApplied) Notify.show(getString(R.string.proxy_sub_test_done, fastest.getDisplay()));
                else if (fastest != null && !finalApplied) showErrorDialog("连接失败: " + fastest.getName(), "测速通过但代理应用失败\n\n" + MihomoManager.get().getLastError());
                else showErrorDialog("测速失败", "所有节点均无法连接\n\n" + MihomoManager.get().getLastError());
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
                if (selected != null) {
                    Notify.show(getString(R.string.proxy_sub_selected, selected.getDisplay()));
                } else {
                    showErrorDialog("自动选择失败", "无法选择可用节点\n\n" + MihomoManager.get().getLastError());
                }
            });
        });
    }

    private void onNodes(View view) {
        List<ProxyNode> nodes = ProxySubscriptionManager.get().getNodes();
        if (nodes.isEmpty()) {
            Notify.show(R.string.proxy_sub_no_node);
            return;
        }
        showNodeList(nodes);
    }

    private void showNodeList(List<ProxyNode> nodes) {
        ArrayAdapter<ProxyNode> adapter = new ArrayAdapter<ProxyNode>(requireActivity(), android.R.layout.simple_list_item_single_choice, nodes) {
            @NonNull
            @Override
            public View getView(int position, View convertView, @NonNull ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                ProxyNode node = getItem(position);
                TextView text = view.findViewById(android.R.id.text1);
                if (node != null) {
                    String display = node.getDisplay();
                    if (node.getLatency() != 0) {
                        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder(display);
                        String latencyStr = node.getLatency() > 0 ? " · " + node.getLatency() + "ms"
                                : node.getLatency() == -2 ? " · timeout" : "";
                        if (!latencyStr.isEmpty()) {
                            int start = display.length() - latencyStr.length();
                            int end = display.length();
                            ssb.setSpan(new android.text.style.ForegroundColorSpan(node.getLatencyColor()), start, end,
                                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        text.setText(ssb);
                    } else {
                        text.setText(display);
                    }
                }
                return view;
            }
        };
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.proxy_sub_nodes)
                .setNegativeButton(R.string.dialog_negative, null)
                .setSingleChoiceItems(adapter, getSelectedIndex(nodes), (dialog, which) -> selectNode(dialog, nodes, adapter, which))
                .show();
    }

    private void selectNode(android.content.DialogInterface dialog, List<ProxyNode> nodes, ArrayAdapter<?> adapter, int which) {
        ProxyNode node = nodes.get(which);
        dialog.dismiss();
        Notify.progress(requireActivity());
        Task.execute(() -> {
            long latency = ProxySubscriptionManager.get().testOne(node);
            boolean ok = false;
            if (latency > 0) {
                ok = ProxySubscriptionManager.get().select(node);
            } else if (node.isSupported()) {
                ok = ProxySubscriptionManager.get().select(node);
            }
            boolean finalOk = ok;
            App.post(() -> {
                Notify.dismiss();
                if (!finalOk) {
                    String error = MihomoManager.get().getLastError();
                    if (TextUtils.isEmpty(error)) error = "延迟=" + latency + "ms\n节点连接测试失败，未应用代理";
                    showErrorDialog("连接失败: " + node.getName(), error);
                    setStatus();
                    return;
                }
                binding.enable.setChecked(true);
                setStatus();
                notifyChanged();
                Notify.show(getString(R.string.proxy_sub_selected, node.getDisplay()));
            });
        });
    }

    private void showErrorDialog(String title, String message) {
        if (!isAdded()) return;
        String display = TextUtils.isEmpty(message) ? "未知错误" : message;
        if (display.length() > 3000) display = display.substring(0, 3000) + "\n...(日志过长已截断)";
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(title)
                .setMessage(display)
                .setPositiveButton("复制日志", (d, w) -> {
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) requireActivity().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("mihomo_log", MihomoManager.get().getLog()));
                    Notify.show("日志已复制到剪贴板");
                })
                .setNegativeButton("关闭", null)
                .show();
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

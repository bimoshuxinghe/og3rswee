package com.fongmi.android.tv.ui.dialog;

import android.text.TextUtils;
import android.view.View;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogProxySubscriptionBinding;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.proxy.ProxyNode;
import com.fongmi.android.tv.proxy.ProxySubscriptionManager;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.List;

public class ProxySubscriptionDialog extends BaseAlertDialog {

    private DialogProxySubscriptionBinding binding;
    private Runnable callback;

    public static ProxySubscriptionDialog create() {
        return new ProxySubscriptionDialog();
    }

    public ProxySubscriptionDialog callback(Runnable callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogProxySubscriptionBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.url.setText(Setting.getProxySubscriptionUrl());
        binding.url.setSelection(TextUtils.isEmpty(binding.url.getText()) ? 0 : binding.url.length());
        refreshUi();
    }

    @Override
    protected void initEvent() {
        binding.push.setOnClickListener(this::onPush);
        binding.enable.setOnClickListener(this::setEnable);
        binding.update.setOnClickListener(this::onUpdate);
        binding.auto.setOnClickListener(this::onAuto);
        binding.nodes.setOnClickListener(this::onNodes);
        binding.negative.setOnClickListener(view -> dismiss());
        binding.url.setOnEditorActionListener((textView, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) binding.update.performClick();
            return true;
        });
    }

    private void onPush(View view) {
        ProxySubscriptionPushDialog.create().callback(() -> {
            refreshUi();
            notifyChanged();
        }).show(requireActivity());
    }

    private String getSwitch() {
        return getString(Setting.isProxySubscriptionEnabled() ? R.string.setting_on : R.string.setting_off);
    }

    private void setEnable(View view) {
        Setting.putProxySubscriptionEnabled(!Setting.isProxySubscriptionEnabled());
        if (Setting.isProxySubscriptionEnabled()) ProxySubscriptionManager.get().applySaved();
        else ProxySubscriptionManager.get().disable();
        binding.enableText.setText(getSwitch());
        setStatus();
        notifyChanged();
    }

    private void onUpdate(View view) {
        String url = binding.url.getText().toString().trim();
        if (TextUtils.isEmpty(url)) {
            Notify.show(R.string.proxy_sub_empty);
            return;
        }
        Setting.putProxySubscriptionUrl(url);
        Notify.progress(getActivity());
        Task.execute(() -> {
            try {
                List<ProxyNode> nodes = ProxySubscriptionManager.get().refresh(url);
                ProxyNode selected = ProxySubscriptionManager.get().autoSelect();
                App.post(() -> {
                    Notify.dismiss();
                    binding.enableText.setText(getSwitch());
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

    private void onAuto(View view) {
        if (ProxySubscriptionManager.get().getNodes().isEmpty()) {
            onUpdate(view);
            return;
        }
        Notify.progress(getActivity());
        Task.execute(() -> {
            ProxyNode selected = ProxySubscriptionManager.get().autoSelect();
            App.post(() -> {
                Notify.dismiss();
                binding.enableText.setText(getSwitch());
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
        ProxySubscriptionNodeDialog.create().callback(() -> {
            refreshUi();
            notifyChanged();
        }).show(requireActivity());
    }

    private void setStatus() {
        ProxySubscriptionManager manager = ProxySubscriptionManager.get();
        List<ProxyNode> nodes = manager.getNodes();
        long tested = nodes.stream().filter(node -> node.getLatency() != -1).count();
        binding.status.setText(getString(R.string.proxy_sub_status, nodes.size(), tested));
        binding.hint.setVisibility(View.GONE);
    }

    private void refreshUi() {
        binding.url.setText(Setting.getProxySubscriptionUrl());
        binding.url.setSelection(TextUtils.isEmpty(binding.url.getText()) ? 0 : binding.url.length());
        binding.enableText.setText(getSwitch());
        setStatus();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.PROXY_SUB) return;
        refreshUi();
        notifyChanged();
    }

    private void notifyChanged() {
        if (callback != null) callback.run();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.62f);
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }
}

package com.fongmi.android.tv.ui.dialog;

import androidx.fragment.app.FragmentActivity;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogProxySubscriptionPushBinding;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.QRCode;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

public class ProxySubscriptionPushDialog extends BaseAlertDialog {

    private DialogProxySubscriptionPushBinding binding;
    private Runnable callback;

    public static ProxySubscriptionPushDialog create() {
        return new ProxySubscriptionPushDialog();
    }

    public ProxySubscriptionPushDialog callback(Runnable callback) {
        this.callback = callback;
        return this;
    }

    public void show(FragmentActivity activity) {
        show(activity.getSupportFragmentManager(), null);
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogProxySubscriptionPushBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        String url = Server.get().getAddress() + "/sub.html";
        binding.code.setImageBitmap(QRCode.getBitmap(url, 200, 0));
        binding.info.setText(ResUtil.getString(R.string.push_info, url).replace("\uff0c", "\n"));
    }

    @Override
    protected void initEvent() {
        binding.negative.setOnClickListener(view -> dismiss());
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (event.type() != ServerEvent.Type.PROXY_SUB) return;
        if (callback != null) callback.run();
        Notify.show(getString(R.string.proxy_sub_selected, event.text()));
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.5f);
        EventBus.getDefault().register(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
    }
}

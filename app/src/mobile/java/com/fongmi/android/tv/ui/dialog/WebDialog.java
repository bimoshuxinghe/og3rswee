package com.fongmi.android.tv.ui.dialog;

import android.content.DialogInterface;
import android.view.View;

import androidx.appcompat.app.AlertDialog;

import com.fongmi.android.tv.App;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class WebDialog {

    private final AlertDialog dialog;

    public static WebDialog create(View view) {
        return new WebDialog(view);
    }

    private WebDialog(View view) {
        this.dialog = new MaterialAlertDialogBuilder(App.activity()).setView(view).create();
        // 注意：CustomWebView 实现了 DialogInterface.OnDismissListener 才能传 view，
        // 调用方会在调用前判空，这里不强制转换避免 R8 优化阶段产生 ClassCastException。
        this.dialog.setOnDismissListener(view instanceof DialogInterface.OnDismissListener
                ? (DialogInterface.OnDismissListener) view : null);
    }

    public WebDialog show() {
        initDialog();
        return this;
    }

    public void dismiss() {
        dialog.setOnDismissListener(null);
        dialog.dismiss();
    }

    private void initDialog() {
        dialog.show();
    }
}

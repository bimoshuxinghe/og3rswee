package com.fongmi.android.tv.ui.dialog;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.DialogAiStatusBinding;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * AI 识别独立状态弹窗：实时展示"AI 正在干什么"（抓取首页 / 分析分类 / 抓取分类页 / 分析影片列表 / 抓取详情页 / 分析播放线路 / 生成配置）。
 */
public class AiStatusDialog extends BaseAlertDialog {

    private DialogAiStatusBinding binding;

    public static AiStatusDialog show(androidx.fragment.app.FragmentManager fm) {
        AiStatusDialog dialog = new AiStatusDialog();
        dialog.show(fm, null);
        return dialog;
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogAiStatusBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        setCancelable(false);
    }

    @Override
    public void onStart() {
        super.onStart();
        setWidth(0.8f);
    }

    /** 更新状态文本（主线程调用） */
    public void updateStatus(@NonNull String text) {
        if (binding != null) {
            binding.aiStatus.setVisibility(View.VISIBLE);
            binding.aiStatus.setText(text);
        }
    }

    /** 识别结束，允许用户关闭 */
    public void finish() {
        setCancelable(true);
    }
}

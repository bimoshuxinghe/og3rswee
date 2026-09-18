package com.fongmi.android.tv.ui.activity;

import android.content.Intent;
import android.os.Bundle;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.databinding.ActivitySplashBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;

import androidx.viewbinding.ViewBinding;

public class SplashActivity extends BaseActivity {

    private ActivitySplashBinding mBinding;

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivitySplashBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        // 极轻启动页（微信式结构）：全屏星落图首帧极快，系统启动屏只在它前面停留极短时间；
        // 停留约 1.2 秒后进入主页，期间主页在后台完成初始化。
        App.post(this::next, 1200);
    }

    private void next() {
        if (isFinishing() || isDestroyed()) return;
        startActivity(new Intent(this, HomeActivity.class));
        finish();
    }
}

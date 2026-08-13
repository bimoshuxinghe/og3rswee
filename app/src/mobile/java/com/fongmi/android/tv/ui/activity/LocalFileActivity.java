package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Class;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.ActivityLocalFileBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.fragment.FolderFragment;

public class LocalFileActivity extends BaseActivity {

    private ActivityLocalFileBinding mBinding;

    public static void start(Activity activity, String key) {
        Intent intent = new Intent(activity, LocalFileActivity.class);
        intent.putExtra("key", key);
        activity.startActivity(intent);
    }

    private String getKey() {
        return getIntent().getStringExtra("key");
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityLocalFileBinding.inflate(getLayoutInflater());
    }

    @Override
    public void setSupportActionBar(@Nullable Toolbar toolbar) {
        super.setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        setSupportActionBar(mBinding.toolbar);
        Site site = VodConfig.get().getSite(getKey());
        setTitle(site.getName());

        Class type = new Class();
        type.setTypeId("root");
        type.setTypeName(site.getName());
        type.setTypeFlag("1"); // marks as folder

        getSupportFragmentManager().beginTransaction().replace(R.id.container, FolderFragment.newInstance(getKey(), type, 8), "0").commit();
    }

    private FolderFragment getFragment() {
        return (FolderFragment) getSupportFragmentManager().findFragmentByTag("0");
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackInvoked();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onBackInvoked() {
        if (getFragment() != null && getFragment().canBack()) {
            getFragment().goBack();
        } else {
            super.onBackInvoked();
        }
    }
}

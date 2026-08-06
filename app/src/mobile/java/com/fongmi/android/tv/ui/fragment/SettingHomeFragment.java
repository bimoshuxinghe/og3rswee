package com.fongmi.android.tv.ui.fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.widget.NestedScrollView;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.databinding.FragmentSettingHomeBinding;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.setting.PlayerSetting;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;

public class SettingHomeFragment extends BaseFragment {

    private FragmentSettingHomeBinding mBinding;
    private String[] homeStyle;
    private String[] searchFilterModes;
    private String[] searchThreadCounts;

    public static SettingHomeFragment newInstance() {
        return new SettingHomeFragment();
    }

    @Override
    protected ViewBinding getBinding(@NonNull LayoutInflater inflater, @Nullable ViewGroup container) {
        return mBinding = FragmentSettingHomeBinding.inflate(inflater, container, false);
    }

    @Override
    protected void initView() {
        setHomeMenuText();
        setHomeStyleText();
        setHomeCarouselText();
        setDetailPosterText();
        setSearchFilterText();
        setSearchThreadText();
    }

    @Override
    protected void initEvent() {
        mBinding.homeMenu.setOnClickListener(this::onHomeMenu);
        mBinding.homeStyle.setOnClickListener(this::onHomeStyle);
        mBinding.homeCarousel.setOnClickListener(this::setHomeCarousel);
        mBinding.detailPoster.setOnClickListener(this::setDetailPoster);
        mBinding.searchFilter.setOnClickListener(this::onSearchFilter);
        mBinding.searchThread.setOnClickListener(this::onSearchThread);

        ((NestedScrollView) mBinding.getRoot().findViewById(R.id.scrollView)).setOnScrollChangeListener((android.view.View.OnScrollChangeListener) (v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            com.fongmi.android.tv.event.ScrollEvent.post(scrollY - oldScrollY);
        });
    }

    private String getSwitch(boolean value) {
        return getString(value ? R.string.setting_on : R.string.setting_off);
    }

    private void setHomeMenuText() {
        List<String> items = new ArrayList<>();
        if (Setting.isHomeVod()) items.add(getString(R.string.setting_vod));
        if (Setting.isHomeHot()) items.add(getString(R.string.nav_hot));
        if (Setting.isHomeLive()) items.add(getString(R.string.setting_live));
        if (Setting.isHomeLocal()) items.add(getString(R.string.home_local));
        if (Setting.isHomeHistory()) items.add(getString(R.string.home_continue));
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(item);
        }
        mBinding.homeMenuText.setText(sb.toString());
    }

    private void onHomeMenu(View view) {
        String[] items = new String[] {
                getString(R.string.setting_vod),
                getString(R.string.nav_hot),
                getString(R.string.setting_live),
                getString(R.string.home_local),
                getString(R.string.home_continue)
        };
        boolean[] checkedItems = new boolean[] {
                Setting.isHomeVod(),
                Setting.isHomeHot(),
                Setting.isHomeLive(),
                Setting.isHomeLocal(),
                Setting.isHomeHistory()
        };
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.setting_home_menu)
                .setNegativeButton(R.string.dialog_negative, null)
                .setPositiveButton(R.string.dialog_positive, (dialog, which) -> {
                    Setting.putHomeVod(checkedItems[0]);
                    Setting.putHomeHot(checkedItems[1]);
                    Setting.putHomeLive(checkedItems[2]);
                    Setting.putHomeLocal(checkedItems[3]);
                    Setting.putHomeHistory(checkedItems[4]);
                    setHomeMenuText();
                    ConfigEvent.common();
                })
                .setMultiChoiceItems(items, checkedItems, (dialog, which, isChecked) -> {
                    checkedItems[which] = isChecked;
                })
                .show();
    }

    private void setHomeStyleText() {
        mBinding.homeStyleText.setText((homeStyle = ResUtil.getStringArray(R.array.select_home_style))[Setting.getHomeStyle()]);
    }

    private void onHomeStyle(View view) {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.setting_home_style)
                .setNegativeButton(R.string.dialog_negative, null)
                .setSingleChoiceItems(homeStyle, Setting.getHomeStyle(), (dialog, which) -> {
                    mBinding.homeStyleText.setText(homeStyle[which]);
                    Setting.putHomeStyle(which);
                    ConfigEvent.common();
                    dialog.dismiss();
                })
                .show();
    }

    private void setHomeCarouselText() {
        mBinding.homeCarouselText.setText(getSwitch(PlayerSetting.isHomeCarousel()));
    }

    private void setHomeCarousel(View view) {
        PlayerSetting.putHomeCarousel(!PlayerSetting.isHomeCarousel());
        setHomeCarouselText();
        Notify.show("重启后生效");
    }

    private void setDetailPosterText() {
        mBinding.detailPosterText.setText(getSwitch(PlayerSetting.isDetailPoster()));
    }

    private void setDetailPoster(View view) {
        PlayerSetting.putDetailPoster(!PlayerSetting.isDetailPoster());
        setDetailPosterText();
        Notify.show("重启后生效");
    }

    private void setSearchFilterText() {
        searchFilterModes = ResUtil.getStringArray(R.array.select_search_filter);
        mBinding.searchFilterText.setText(searchFilterModes[Setting.getSearchFilter()]);
    }

    private void onSearchFilter(View view) {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.setting_search_filter)
                .setNegativeButton(R.string.dialog_negative, null)
                .setSingleChoiceItems(searchFilterModes, Setting.getSearchFilter(), (dialog, which) -> {
                    mBinding.searchFilterText.setText(searchFilterModes[which]);
                    Setting.putSearchFilter(which);
                    dialog.dismiss();
                })
                .show();
    }

    private void setSearchThreadText() {
        searchThreadCounts = ResUtil.getStringArray(R.array.select_search_thread);
        mBinding.searchThreadText.setText(String.valueOf(Setting.getSearchThread()));
    }

    private void onSearchThread(View view) {
        new MaterialAlertDialogBuilder(requireActivity())
                .setTitle(R.string.setting_search_thread)
                .setNegativeButton(R.string.dialog_negative, null)
                .setSingleChoiceItems(searchThreadCounts, getSearchThreadIndex(), (dialog, which) -> {
                    int threads = getSearchThreadValue(which);
                    Setting.putSearchThread(threads);
                    mBinding.searchThreadText.setText(String.valueOf(threads));
                    dialog.dismiss();
                })
                .show();
    }

    private int getSearchThreadIndex() {
        int current = Setting.getSearchThread();
        int[] values = {5, 8, 10, 12, 15};
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) return i;
        }
        return 2;
    }

    private int getSearchThreadValue(int index) {
        int[] values = {5, 8, 10, 12, 15};
        return values[Math.min(Math.max(index, 0), values.length - 1)];
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        if (!hidden) initView();
    }
}

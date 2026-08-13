package com.fongmi.android.tv.ui.custom;

import android.view.ViewGroup;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.fongmi.android.tv.ui.base.BaseFragment;
import com.fongmi.android.tv.utils.TransitionUtil;

import java.util.function.IntFunction;

public class FragmentStateManager {

    private final ViewGroup container;
    private final FragmentManager fm;
    private final IntFunction<Fragment> factory;
    private int currentPosition = -1;

    public FragmentStateManager(ViewGroup container, FragmentManager fm, IntFunction<Fragment> factory) {
        this.container = container;
        this.factory = factory;
        this.fm = fm;
    }

    public boolean change(int position) {
        String tag = getTag(position);
        Fragment existing = fm.findFragmentByTag(tag);
        boolean adding = existing == null;
        Fragment fragment = adding ? factory.apply(position) : existing;
        FragmentTransaction ft = fm.beginTransaction();
        boolean forward = currentPosition < 0 || position >= currentPosition;
        int[] anims = TransitionUtil.getFragmentAnims(forward);
        // 仅对首次 add 的 Fragment 使用转场动画。已存在的 Fragment 之间用 show/hide
        // 切换时若套用自定义动画，开启系统/过渡动画后视图会停在动画初始的不可见态
        // （alpha=0 / scale=0）导致返回时出现空白界面。因此 show/hide 切换不再设动画。
        if (anims != null && adding) {
            ft.setCustomAnimations(anims[0], anims[1], anims[2], anims[3]);
        }
        if (adding) ft.add(container.getId(), fragment, tag);
        Fragment current = fm.getPrimaryNavigationFragment();
        if (current != null && current != fragment) ft.hide(current);
        ft.show(fragment).setPrimaryNavigationFragment(fragment).setReorderingAllowed(true).commitNowAllowingStateLoss();
        currentPosition = position;
        return true;
    }

    private String getTag(int position) {
        return "android:switcher:" + position;
    }

    public BaseFragment getFragment(int position) {
        return (BaseFragment) fm.findFragmentByTag(getTag(position));
    }

    public boolean isVisible(int position) {
        Fragment fragment = getFragment(position);
        return fragment != null && fragment.isVisible();
    }

    public boolean canBack(int position) {
        BaseFragment fragment = getFragment(position);
        return fragment != null && fragment.canBack();
    }
}

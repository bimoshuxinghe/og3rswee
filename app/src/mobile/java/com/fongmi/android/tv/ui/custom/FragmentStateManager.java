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
        Fragment fragment = fm.findFragmentByTag(tag);
        fragment = (fragment == null) ? factory.apply(position) : fragment;
        FragmentTransaction ft = fm.beginTransaction();
        boolean forward = currentPosition < 0 || position >= currentPosition;
        int[] anims = TransitionUtil.getFragmentAnims(forward);
        if (anims != null) {
            ft.setCustomAnimations(anims[0], anims[1], anims[2], anims[3]);
        }
        if (fm.findFragmentByTag(tag) == null) ft.add(container.getId(), fragment, tag);
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

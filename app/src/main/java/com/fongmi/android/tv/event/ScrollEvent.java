package com.fongmi.android.tv.event;

import org.greenrobot.eventbus.EventBus;

public class ScrollEvent {

    private final int dy;

    public static void post(int dy) {
        EventBus.getDefault().post(new ScrollEvent(dy));
    }

    private ScrollEvent(int dy) {
        this.dy = dy;
    }

    public int getDy() {
        return dy;
    }
}
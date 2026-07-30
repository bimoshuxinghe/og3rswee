package com.fongmi.android.tv.bean;

import java.util.List;

public class HomeBanner {

    private final List<Func> funcs;
    private final List<Vod> recommends;
    private final boolean livePreview;

    public HomeBanner(List<Func> funcs, List<Vod> recommends, boolean livePreview) {
        this.funcs = funcs;
        this.recommends = recommends;
        this.livePreview = livePreview;
    }

    public List<Func> getFuncs() {
        return funcs;
    }

    public List<Vod> getRecommends() {
        return recommends;
    }

    public boolean isLivePreview() {
        return livePreview;
    }
}

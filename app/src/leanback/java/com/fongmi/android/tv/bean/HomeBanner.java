package com.fongmi.android.tv.bean;

import java.util.List;

public class HomeBanner {

    private final List<Func> funcs;
    private final List<Vod> recommends;

    public HomeBanner(List<Func> funcs, List<Vod> recommends) {
        this.funcs = funcs;
        this.recommends = recommends;
    }

    public List<Func> getFuncs() {
        return funcs;
    }

    public List<Vod> getRecommends() {
        return recommends;
    }
}

package com.fongmi.android.tv.bean;

import androidx.leanback.widget.ArrayObjectAdapter;

public class HistoryRow {

    private final ArrayObjectAdapter adapter;

    public HistoryRow(ArrayObjectAdapter adapter) {
        this.adapter = adapter;
    }

    public ArrayObjectAdapter getAdapter() {
        return adapter;
    }
}

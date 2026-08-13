package com.fongmi.android.tv.event;

import com.fongmi.android.tv.bean.Download;
import org.greenrobot.eventbus.EventBus;

public class DownloadEvent {

    private final Download download;

    public DownloadEvent(Download download) {
        this.download = download;
    }

    public static void post(Download download) {
        EventBus.getDefault().post(new DownloadEvent(download));
    }

    public Download getDownload() {
        return download;
    }
}

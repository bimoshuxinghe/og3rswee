package com.fongmi.android.tv.bean;

import java.util.ArrayList;
import java.util.List;

public class DownloadGroup {

    private String vodName;
    private String vodPic;
    private boolean expanded;
    private List<Download> downloads;

    public DownloadGroup(String vodName, String vodPic) {
        this.vodName = vodName;
        this.vodPic = vodPic;
        this.downloads = new ArrayList<>();
        this.expanded = false;
    }

    public String getVodName() {
        return vodName;
    }

    public void setVodName(String vodName) {
        this.vodName = vodName;
    }

    public String getVodPic() {
        return vodPic;
    }

    public void setVodPic(String vodPic) {
        this.vodPic = vodPic;
    }

    public boolean isExpanded() {
        return expanded;
    }

    public void setExpanded(boolean expanded) {
        this.expanded = expanded;
    }

    public List<Download> getDownloads() {
        return downloads;
    }

    public void setDownloads(List<Download> downloads) {
        this.downloads = downloads;
    }
}

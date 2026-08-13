package com.fongmi.android.tv.bean;

import androidx.room.Entity;
import androidx.room.PrimaryKey;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.db.AppDatabase;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;

@Entity
public class Download {

    @PrimaryKey(autoGenerate = true)
    @SerializedName("id")
    private int id;
    @SerializedName("vodName")
    private String vodName;
    @SerializedName("vodPic")
    private String vodPic;
    @SerializedName("episodeName")
    private String episodeName;
    @SerializedName("key")
    private String key;
    @SerializedName("flag")
    private String flag;
    @SerializedName("episodeUrl")
    private String episodeUrl;
    @SerializedName("url")
    private String url;
    @SerializedName("headers")
    private String headers;
    @SerializedName("downloadPath")
    private String downloadPath;
    @SerializedName("totalTs")
    private int totalTs;
    @SerializedName("downloadedTs")
    private int downloadedTs;
    @SerializedName("progress")
    private int progress;
    @SerializedName("status")
    private int status;
    @SerializedName("createTime")
    private long createTime;

    public static final int STATUS_WAIT = 0;
    public static final int STATUS_DOWNLOADING = 1;
    public static final int STATUS_PAUSE = 2;
    public static final int STATUS_COMPLETED = 3;
    public static final int STATUS_ERROR = 4;

    public Download() {
    }

    public static List<Download> getAll() {
        return AppDatabase.get().getDownloadDao().getAll();
    }

    public static List<Download> getByStatus(int status) {
        return AppDatabase.get().getDownloadDao().getByStatus(status);
    }

    public static Download find(int id) {
        return AppDatabase.get().getDownloadDao().find(id);
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
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

    public String getEpisodeName() {
        return episodeName;
    }

    public void setEpisodeName(String episodeName) {
        this.episodeName = episodeName;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getFlag() {
        return flag;
    }

    public void setFlag(String flag) {
        this.flag = flag;
    }

    public String getEpisodeUrl() {
        return episodeUrl;
    }

    public void setEpisodeUrl(String episodeUrl) {
        this.episodeUrl = episodeUrl;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getHeaders() {
        return headers;
    }

    public void setHeaders(String headers) {
        this.headers = headers;
    }

    public String getDownloadPath() {
        return downloadPath;
    }

    public void setDownloadPath(String downloadPath) {
        this.downloadPath = downloadPath;
    }

    public int getTotalTs() {
        return totalTs;
    }

    public void setTotalTs(int totalTs) {
        this.totalTs = totalTs;
    }

    public int getDownloadedTs() {
        return downloadedTs;
    }

    public void setDownloadedTs(int downloadedTs) {
        this.downloadedTs = downloadedTs;
    }

    public int getProgress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }
}

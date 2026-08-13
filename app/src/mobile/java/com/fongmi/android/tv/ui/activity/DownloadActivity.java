package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.R;
import com.fongmi.android.tv.bean.Download;
import com.fongmi.android.tv.bean.DownloadGroup;
import com.fongmi.android.tv.databinding.ActivityDownloadBinding;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.DownloadEvent;
import com.fongmi.android.tv.ui.adapter.DownloadAdapter;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.DownloadManager;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.Task;
import com.github.catvod.utils.Path;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DownloadActivity extends BaseActivity implements DownloadAdapter.OnClickListener {

    private ActivityDownloadBinding mBinding;
    private DownloadAdapter mAdapter;

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, DownloadActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityDownloadBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        mBinding.toolbar.setNavigationIcon(R.drawable.ic_control_back);
        mBinding.toolbar.setNavigationOnClickListener(v -> finish());
        mBinding.recycler.setLayoutManager(new LinearLayoutManager(this));
        mBinding.recycler.setAdapter(mAdapter = new DownloadAdapter(this));
        getDownloads();
    }

    private void getDownloads() {
        Task.execute(() -> {
            List<Download> items = Download.getAll();
            
            // 内存 Group By
            Map<String, DownloadGroup> groupMap = new LinkedHashMap<>();
            for (Download d : items) {
                String key = d.getVodName();
                DownloadGroup group = groupMap.get(key);
                if (group == null) {
                    group = new DownloadGroup(d.getVodName(), d.getVodPic());
                    groupMap.put(key, group);
                }
                group.getDownloads().add(d);
            }
            List<DownloadGroup> groups = new ArrayList<>(groupMap.values());
            
            // 默认展开第一个，体验更佳
            if (!groups.isEmpty()) {
                groups.get(0).setExpanded(true);
            }
            
            runOnUiThread(() -> mAdapter.addAll(groups));
        });
    }

    @Override
    public void onEpisodeAction(Download child) {
        if (child.getStatus() == Download.STATUS_COMPLETED) {
            playDownload(child);
        } else if (child.getStatus() == Download.STATUS_DOWNLOADING || child.getStatus() == Download.STATUS_WAIT) {
            DownloadManager.get().pauseDownload(child.getId());
            child.setStatus(Download.STATUS_PAUSE);
            mAdapter.updateItem(mBinding.recycler, child);
            Notify.show("已暂停下载");
        } else {
            DownloadManager.get().resumeDownload(child);
            child.setStatus(Download.STATUS_WAIT);
            mAdapter.updateItem(mBinding.recycler, child);
            Notify.show("继续下载");
        }
    }

    private void playDownload(Download item) {
        File downloadDir = new File(item.getDownloadPath());
        File m3u8File = new File(downloadDir, "local.m3u8");
        String playUrl;
        if (m3u8File.exists()) {
            playUrl = "http://127.0.0.1:" + com.github.catvod.Proxy.getPort() + "/local_play" + m3u8File.getAbsolutePath();
        } else {
            // 查找目录下的视频文件（mp4/mkv），兼容旧命名 video.mp4 和新命名 片名-集数.mp4
            File videoFile = null;
            if (downloadDir.exists() && downloadDir.isDirectory()) {
                File[] files = downloadDir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        String name = f.getName().toLowerCase();
                        if (name.endsWith(".mp4") || name.endsWith(".mkv")) {
                            if (!name.endsWith(".tmp")) {
                                videoFile = f;
                                break;
                            }
                        }
                    }
                }
            }
            if (videoFile == null || !videoFile.exists()) {
                Notify.show("文件不存在");
                return;
            }
            playUrl = "http://127.0.0.1:" + com.github.catvod.Proxy.getPort() + "/local_play" + videoFile.getAbsolutePath();
        }

        String compositeId = playUrl;
        try {
            compositeId = "local_play_media://detail"
                    + "?current_url=" + java.net.URLEncoder.encode(playUrl, "UTF-8")
                    + "&vod_name=" + java.net.URLEncoder.encode(item.getVodName(), "UTF-8");
        } catch (Exception e) {
            e.printStackTrace();
        }

        VideoActivity.start(this, "local", compositeId, item.getVodName(), item.getVodPic(), item.getEpisodeName());
    }

    @Override
    public void onEpisodeDelete(Download child) {
        new AlertDialog.Builder(this)
                .setTitle("确认删除")
                .setMessage("是否确认删除 " + child.getEpisodeName() + " 及其本地文件？")
                .setPositiveButton("删除", (dialog, which) -> deleteEpisode(child))
                .setNegativeButton("取消", null)
                .show();
    }

    private void deleteEpisode(Download child) {
        Task.execute(() -> {
            // 先在下载器中将其置为暂停/取消
            DownloadManager.get().pauseDownload(child.getId());
            AppDatabase.get().getDownloadDao().delete(child.getId());
            Path.clear(new File(child.getDownloadPath()));
            runOnUiThread(() -> {
                Notify.show("已删除任务");
                getDownloads(); // 简单重拉分组刷新，确保空分组时被彻底移除
            });
        });
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(DownloadEvent event) {
        mAdapter.updateItem(mBinding.recycler, event.getDownload());
    }
}

package com.fongmi.android.tv.utils;

import android.os.StatFs;
import android.text.TextUtils;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Download;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.DownloadEvent;
import com.github.catvod.net.OkHttp;
import com.google.gson.reflect.TypeToken;
import com.google.common.net.HttpHeaders;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

public class DownloadManager {

    private static volatile DownloadManager instance;
    private final ExecutorService taskExecutor = Executors.newFixedThreadPool(1);
    private final ExecutorService tsDownloadExecutor = Executors.newFixedThreadPool(4);
    private final java.util.Map<Integer, Boolean> pausedTasks = new java.util.concurrent.ConcurrentHashMap<>();

    public static DownloadManager get() {
        if (instance == null) {
            synchronized (DownloadManager.class) {
                if (instance == null) instance = new DownloadManager();
            }
        }
        return instance;
    }

    public static long getAvailableSpace() {
        try {
            File path = App.get().getFilesDir();
            StatFs stat = new StatFs(path.getPath());
            long blockSize = stat.getBlockSizeLong();
            long availableBlocks = stat.getAvailableBlocksLong();
            return availableBlocks * blockSize;
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    public void pauseDownload(int id) {
        pausedTasks.put(id, true);
    }

    public void resumeDownload(Download download) {
        pausedTasks.remove(download.getId());
        download.setStatus(Download.STATUS_WAIT);
        updateStatus(download);
        taskExecutor.submit(() -> executeTask(download));
    }

    public boolean isPaused(int id) {
        return pausedTasks.getOrDefault(id, false);
    }

    public void startDownload(Download download) {
        download.setStatus(Download.STATUS_WAIT);
        Long id = AppDatabase.get().getDownloadDao().insert(download);
        if (id != null && id > 0) download.setId(id.intValue());
        DownloadEvent.post(download);
        taskExecutor.submit(() -> executeTask(download));
    }

    private void executeTask(Download download) {
        try {
            download.setStatus(Download.STATUS_DOWNLOADING);
            updateStatus(download);

            if (isPaused(download.getId())) {
                download.setStatus(Download.STATUS_PAUSE);
                updateStatus(download);
                return;
            }

            // 1. 刷新直链
            refreshPlayUrl(download);

            if (isPaused(download.getId())) {
                download.setStatus(Download.STATUS_PAUSE);
                updateStatus(download);
                return;
            }

            File downloadDir = new File(download.getDownloadPath());
            if (!downloadDir.exists()) downloadDir.mkdirs();

            // 2. 检查是否为 M3U8 协议视频
            if (isM3u8(download.getUrl(), download.getHeaders())) {
                executeM3u8Task(download, downloadDir);
            } else {
                executeSingleFileTask(download, downloadDir);
            }

        } catch (Throwable t) {
            t.printStackTrace();
            if (isPaused(download.getId()) || "Paused".equals(t.getMessage())) {
                download.setStatus(Download.STATUS_PAUSE);
            } else {
                download.setStatus(Download.STATUS_ERROR);
            }
            updateStatus(download);
        }
    }

    private boolean isM3u8(String url, String headersJson) {
        if (url.contains(".m3u8") || url.contains(".M3U8")) return true;
        try {
            Map<String, String> headers = App.gson().fromJson(headersJson, new TypeToken<Map<String, String>>() {}.getType());
            try (Response response = OkHttp.newCall(url, headers).execute()) {
                String contentType = response.header("Content-Type");
                if (contentType != null && (contentType.contains("mpegurl") || contentType.contains("m3u8"))) {
                    return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // 针对 M3U8 (流媒体) 的下载核心
    private void executeM3u8Task(Download download, File downloadDir) throws Exception {
        Map<String, String> headers = App.gson().fromJson(download.getHeaders(), new TypeToken<Map<String, String>>() {}.getType());
        String m3u8Content;
        try (Response res = OkHttp.newCall(download.getUrl(), headers).execute()) {
            m3u8Content = res.body().string();
        }

        // 广告清洗
        List<String> cleanM3u8Lines = cleanM3u8Ads(m3u8Content);

        List<String> tsUrls = new ArrayList<>();
        List<String> localM3u8Lines = new ArrayList<>();
        int tsIndex = 0;

        for (String line : cleanM3u8Lines) {
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-KEY")) {
                    String cleanKeyLine = handleKeyDownload(line, download.getUrl(), download.getHeaders(), downloadDir);
                    localM3u8Lines.add(cleanKeyLine);
                } else {
                    localM3u8Lines.add(line);
                }
            } else {
                String absoluteTsUrl = UrlUtil.resolve(download.getUrl(), line.trim());
                tsUrls.add(absoluteTsUrl);
                localM3u8Lines.add(tsIndex + ".ts");
                tsIndex++;
            }
        }

        download.setTotalTs(tsUrls.size());
        updateStatus(download);

        // 并发且同步阻塞下载 TS 切片
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(tsUrls.size());
        downloadAllTs(download, tsUrls, downloadDir, latch);
        latch.await();

        if (isPaused(download.getId())) {
            download.setStatus(Download.STATUS_PAUSE);
            updateStatus(download);
            return;
        }

        if (download.getStatus() == Download.STATUS_ERROR) {
            return;
        }

        int downloadedCount = download.getDownloadedTs();
        if (downloadedCount == tsUrls.size()) {
            writeLocalM3u8(localM3u8Lines, new File(downloadDir, "local.m3u8"));
            download.setStatus(Download.STATUS_COMPLETED);
            download.setProgress(100);
            updateStatus(download);
        } else {
            download.setStatus(Download.STATUS_ERROR);
            updateStatus(download);
        }
    }

    // 针对单视频文件（网盘 MP4/MKV）的下载核心
    private void executeSingleFileTask(Download download, File downloadDir) {
        String suffix = ".mp4";
        if (download.getUrl().contains(".mkv") || download.getUrl().contains(".MKV")) suffix = ".mkv";
        // 使用片名+集数作为文件名
        String baseName = "";
        if (!TextUtils.isEmpty(download.getVodName()) && !TextUtils.isEmpty(download.getEpisodeName())) {
            baseName = download.getVodName() + " - " + download.getEpisodeName();
        } else if (!TextUtils.isEmpty(download.getEpisodeName())) {
            baseName = download.getEpisodeName();
        } else if (!TextUtils.isEmpty(download.getVodName())) {
            baseName = download.getVodName();
        } else {
            baseName = "video";
        }
        baseName = baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
        File targetFile = new File(downloadDir, baseName + suffix);
        File tempFile = new File(downloadDir, baseName + suffix + ".tmp");

        long startPosition = tempFile.exists() ? tempFile.length() : 0;
        long totalLength = getRemoteContentLength(download.getUrl(), download.getHeaders());

        // 预检空间
        if (totalLength > 0 && getAvailableSpace() < (totalLength - startPosition) + 100 * 1024 * 1024) {
            download.setStatus(Download.STATUS_ERROR);
            updateStatus(download);
            return;
        }

        try {
            Map<String, String> headers = App.gson().fromJson(download.getHeaders(), new TypeToken<Map<String, String>>() {}.getType());
            if (startPosition > 0) {
                headers.put("Range", "bytes=" + startPosition + "-");
            }

            try (Response response = OkHttp.newCall(download.getUrl(), headers).execute()) {
                if (response.code() == 403 || response.code() == 410) {
                    // 直链过期，触发刷新并重新请求
                    refreshPlayUrl(download);
                    executeSingleFileTask(download, downloadDir);
                    return;
                }
                if (!response.isSuccessful()) {
                    download.setStatus(Download.STATUS_ERROR);
                    updateStatus(download);
                    return;
                }

                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(tempFile, startPosition > 0)) {
                    byte[] buffer = new byte[16384];
                    int len;
                    long totalBytes = startPosition;
                    while ((len = is.read(buffer)) != -1) {
                        if (isPaused(download.getId())) {
                            throw new Exception("Paused");
                        }
                        fos.write(buffer, 0, len);
                        totalBytes += len;
                        if (totalLength > 0) {
                            download.setProgress((int) (totalBytes * 100 / totalLength));
                            AppDatabase.get().getDownloadDao().update(download);
                            DownloadEvent.post(download);
                        }
                    }
                }
            }

            // 重命名为完成文件
            if (tempFile.renameTo(targetFile)) {
                download.setStatus(Download.STATUS_COMPLETED);
                download.setProgress(100);
                updateStatus(download);
            } else {
                download.setStatus(Download.STATUS_ERROR);
                updateStatus(download);
            }

        } catch (Exception e) {
            e.printStackTrace();
            if ("Paused".equals(e.getMessage()) || isPaused(download.getId())) {
                download.setStatus(Download.STATUS_PAUSE);
            } else {
                download.setStatus(Download.STATUS_ERROR);
            }
            updateStatus(download);
        }
    }

    private long getRemoteContentLength(String url, String headersJson) {
        try {
            Map<String, String> headers = App.gson().fromJson(headersJson, new TypeToken<Map<String, String>>() {}.getType());
            try (Response res = OkHttp.newCall(url, headers).execute()) {
                String size = res.header("Content-Length");
                return size != null ? Long.parseLong(size) : -1;
            }
        } catch (Exception e) {
            return -1;
        }
    }

    private void refreshPlayUrl(Download download) throws Exception {
        Result result = SiteApi.playerContent(download.getKey(), download.getFlag(), download.getEpisodeUrl());
        if (result != null && !result.getRealUrl().isEmpty()) {
            download.setUrl(result.getRealUrl());
            download.setHeaders(App.gson().toJson(result.getHeader()));
            AppDatabase.get().getDownloadDao().update(download);
        }
    }

    private List<String> cleanM3u8Ads(String content) {
        String[] lines = content.split("\n");
        List<String> result = new ArrayList<>();
        int i = 0;
        int n = lines.length;
        while (i < n) {
            String line = lines[i].trim();
            if (line.equals("#EXT-X-DISCONTINUITY")) {
                int j = i + 1;
                boolean isAd = false;
                List<String> block = new ArrayList<>();
                block.add(lines[i]);
                while (j < n) {
                    if (j >= n) break;
                    String nextLine = lines[j].trim();
                    block.add(lines[j]);
                    if (nextLine.equals("#EXT-X-DISCONTINUITY")) {
                        break;
                    }
                    if (nextLine.contains("/adjump/") || nextLine.contains("ad.com")) {
                        isAd = true;
                    }
                    j++;
                }
                if (isAd) {
                    i = j + 1;
                    continue;
                }
            }
            result.add(lines[i]);
            i++;
        }
        return result;
    }

    private void downloadAllTs(Download download, List<String> urls, File downloadDir, java.util.concurrent.CountDownLatch latch) {
        int[] successCount = {0};
        for (int i = 0; i < urls.size(); i++) {
            final int index = i;
            final String tsUrl = urls.get(i);
            tsDownloadExecutor.submit(() -> {
                try {
                    if (isPaused(download.getId())) {
                        return;
                    }
                    if (download.getStatus() == Download.STATUS_ERROR) {
                        return;
                    }
                    // 空间熔断检测：若剩余空间不足 200MB，停止下载
                    if (getAvailableSpace() < 200 * 1024 * 1024) {
                        download.setStatus(Download.STATUS_ERROR);
                        updateStatus(download);
                        return;
                    }

                    File target = new File(downloadDir, index + ".ts");
                    if (target.exists() && target.length() > 0) {
                        synchronized (successCount) {
                            successCount[0]++;
                            download.setDownloadedTs(successCount[0]);
                            download.setProgress(successCount[0] * 100 / download.getTotalTs());
                            AppDatabase.get().getDownloadDao().update(download);
                            DownloadEvent.post(download);
                        }
                        return;
                    }

                    boolean downloaded = downloadSingleFile(tsUrl, target, download.getHeaders(), 3);
                    if (downloaded) {
                        synchronized (successCount) {
                            successCount[0]++;
                            download.setDownloadedTs(successCount[0]);
                            download.setProgress(successCount[0] * 100 / download.getTotalTs());
                            AppDatabase.get().getDownloadDao().update(download);
                            DownloadEvent.post(download);
                        }
                    } else {
                        if (!isPaused(download.getId())) {
                            download.setStatus(Download.STATUS_ERROR);
                            updateStatus(download);
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }
    }

    private boolean downloadSingleFile(String fileUrl, File targetFile, String headersJson, int retryCount) {
        try {
            Map<String, String> headers = App.gson().fromJson(headersJson, new TypeToken<Map<String, String>>() {}.getType());
            try (Response response = OkHttp.newCall(fileUrl, headers).execute()) {
                if (response.code() == 403 || response.code() == 410) {
                    throw new Exception("Link expired, need refresh");
                }
                if (!response.isSuccessful()) return false;
                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    return true;
                }
            }
        } catch (Exception e) {
            if (retryCount > 0) {
                return downloadSingleFile(fileUrl, targetFile, headersJson, retryCount - 1);
            }
            return false;
        }
    }

    private String handleKeyDownload(String keyLine, String baseUrl, String headersJson, File downloadDir) {
        Matcher matcher = Pattern.compile("URI=\"([^\"]+)\"").matcher(keyLine);
        if (matcher.find()) {
            String keyUrl = matcher.group(1);
            String absoluteKeyUrl = UrlUtil.resolve(baseUrl, keyUrl);
            File keyFile = new File(downloadDir, "key.key");
            boolean success = downloadSingleFile(absoluteKeyUrl, keyFile, headersJson, 3);
            if (success) {
                return keyLine.replace(keyUrl, "key.key");
            }
        }
        return keyLine;
    }

    private void writeLocalM3u8(List<String> lines, File file) throws Exception {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            for (String line : lines) {
                fos.write((line + "\n").getBytes());
            }
        }
    }

    private void updateStatus(Download download) {
        AppDatabase.get().getDownloadDao().update(download);
        DownloadEvent.post(download);
    }
}

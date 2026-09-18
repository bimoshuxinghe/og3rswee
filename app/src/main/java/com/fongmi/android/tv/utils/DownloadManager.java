package com.fongmi.android.tv.utils;

import android.os.StatFs;
import android.text.TextUtils;
import com.fongmi.android.tv.App;
import com.fongmi.android.tv.api.SiteApi;
import com.fongmi.android.tv.bean.Download;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.db.AppDatabase;
import com.fongmi.android.tv.event.DownloadEvent;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;
import com.google.gson.reflect.TypeToken;
import com.google.common.net.HttpHeaders;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
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

    /**
     * 解析播放列表：master playlist（#EXT-X-STREAM-INF）自动跟随最高带宽子列表，最多 3 层。
     * 返回 {最终列表URL, 列表内容}。普通列表直接原样返回。
     */
    private String[] loadPlaylist(String url, Map<String, String> headers, int depth) throws Exception {
        String content;
        try (Response res = OkHttp.newCall(url, headers).execute()) {
            if (!res.isSuccessful()) throw new IOException("HTTP " + res.code() + " " + url);
            content = res.body().string();
        }
        if (depth < 3 && content.contains("#EXT-X-STREAM-INF") && !content.contains("#EXTINF")) {
            String best = null;
            long bestBw = -1;
            String[] lines = content.split("\n");
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].trim().startsWith("#EXT-X-STREAM-INF")) {
                    Matcher m = Pattern.compile("BANDWIDTH=(\\d+)").matcher(lines[i]);
                    long bw = m.find() ? Long.parseLong(m.group(1)) : 0;
                    if (bw >= bestBw && i + 1 < lines.length) {
                        bestBw = bw;
                        best = lines[i + 1].trim();
                    }
                }
            }
            if (best == null || best.isEmpty()) return new String[]{url, content}; // 交由上层 #EXTINF 校验报错
            return loadPlaylist(UrlUtil.resolve(url, best), headers, depth + 1);
        }
        return new String[]{url, content};
    }

    // 针对 M3U8 (流媒体) 的下载核心
    private void executeM3u8Task(Download download, File downloadDir) throws Exception {
        Map<String, String> headers = App.gson().fromJson(download.getHeaders(), new TypeToken<Map<String, String>>() {}.getType());
        // master playlist 支持：跟随嵌套列表到最终分片列表，并把 url 固化为二级列表（利于断点续传）
        String[] playlist = loadPlaylist(download.getUrl(), headers, 0);
        String playlistUrl = playlist[0];
        String m3u8Content = playlist[1];
        if (!playlistUrl.equals(download.getUrl())) {
            download.setUrl(playlistUrl);
            AppDatabase.get().getDownloadDao().update(download);
        }
        // 源内容校验：不含分片声明的响应多为错误页/验证页，不能当播放列表
        if (!m3u8Content.contains("#EXTINF")) {
            download.setStatus(Download.STATUS_ERROR);
            updateStatus(download);
            return;
        }

        // 广告清洗
        List<String> cleanM3u8Lines = cleanM3u8Ads(m3u8Content);

        List<String> tsUrls = new ArrayList<>();
        List<String> localM3u8Lines = new ArrayList<>();
        int tsIndex = 0;
        boolean isEncrypted = false;

        for (String line : cleanM3u8Lines) {
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-KEY")) {
                    isEncrypted = true;
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

        // 并发下载 TS 切片
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
            // 下载完成：优先合并为专属 .xhtv 单文件（加密流自动解密），合并失败回退分片模式
            boolean merged = mergeXhtv(download, downloadDir, localM3u8Lines, tsUrls.size());
            if (!merged) writeLocalM3u8(localM3u8Lines, new File(downloadDir, "local.m3u8"));
            download.setStatus(Download.STATUS_COMPLETED);
            download.setProgress(100);
            updateStatus(download);
        } else {
            download.setStatus(Download.STATUS_ERROR);
            updateStatus(download);
        }
    }

    /** 单集文件基础名：片名 - 集名（非法字符替换为下划线），无信息时用 video */
    private String buildBaseName(Download download) {
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
        return baseName.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /**
     * 将 TS 分片合并为专属 .xhtv 单文件（MPEG-TS 二进制顺序拼接，AES-128 加密流逐片解密）。
     * 成功后删除分片/local.m3u8/key.key 等中间文件，使目录仅保留合并文件（不再污染相册）。
     * 返回 false 时由调用方回退为 local.m3u8 分片模式。
     */
    private boolean mergeXhtv(Download download, File downloadDir, List<String> localM3u8Lines, int tsCount) {
        File merged = new File(downloadDir, buildBaseName(download) + ".xhtv");
        try {
            boolean encrypted = false;
            byte[] key = null;
            byte[] fixedIv = null;
            long sequence = 0;
            for (String line : localM3u8Lines) {
                String trimmed = line.trim();
                if (trimmed.startsWith("#EXT-X-KEY") && trimmed.contains("METHOD=AES-128")) {
                    encrypted = true;
                    File keyFile = new File(downloadDir, "key.key");
                    if (!keyFile.exists()) return false; // 密钥缺失无法解密，回退分片模式
                    key = readAllBytes(keyFile);
                    int idx = trimmed.indexOf("IV=0x");
                    if (idx < 0) idx = trimmed.indexOf("IV=0X");
                    if (idx >= 0) {
                        String hex = trimmed.substring(idx + 5).split("[,\"]")[0].trim();
                        fixedIv = hexToBytes(hex);
                    }
                } else if (trimmed.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    try {
                        sequence = Long.parseLong(trimmed.substring(trimmed.indexOf(':') + 1).trim());
                    } catch (Exception ignored) {}
                }
            }

            javax.crypto.Cipher cipher = null;
            if (encrypted) {
                cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding"); // 16 字节分组下 PKCS5 等价 PKCS7
                javax.crypto.spec.SecretKeySpec keySpec = new javax.crypto.spec.SecretKeySpec(key, "AES");
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keySpec, new javax.crypto.spec.IvParameterSpec(new byte[16]));
            }

            long mergedBytes = 0;
            try (java.io.OutputStream os = new java.io.BufferedOutputStream(new java.io.FileOutputStream(merged), 1 << 20)) {
                for (int i = 0; i < tsCount; i++) {
                    File seg = new File(downloadDir, i + ".ts");
                    if (!seg.exists() || seg.length() == 0) throw new IOException("missing segment " + i);
                    byte[] data = readAllBytes(seg);
                    if (encrypted) {
                        // 先解密，解密结果非 TS 或解密失败时回退明文（兼容假加密/混合加密源）
                        byte[] use = null;
                        try {
                            byte[] iv = fixedIv != null ? fixedIv : sequenceIv(sequence + i);
                            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,
                                    new javax.crypto.spec.SecretKeySpec(key, "AES"),
                                    new javax.crypto.spec.IvParameterSpec(iv));
                            byte[] dec = cipher.doFinal(data);
                            if (dec.length > 0 && (dec[0] & 0xFF) == 0x47) use = dec; // 解密成功且为合法 TS
                            else if (data.length > 0 && (data[0] & 0xFF) == 0x47) use = data; // 解出非 TS 但原文明文
                        } catch (Exception bad) {
                            if (data.length > 0 && (data[0] & 0xFF) == 0x47) use = data; // 解密失败但原文明文
                            else throw bad;
                        }
                        if (use == null) throw new IOException("segment " + i + " undecryptable and not plain TS");
                        data = use;
                    }
                    os.write(data);
                    mergedBytes += data.length;
                }
            }
            // 最小体校验：合并产物过小说明分片无效（错误页/空壳），回退分片模式
            if (mergedBytes < 10 * 1024) throw new IOException("merged too small: " + mergedBytes);

            // 时间戳重写：源流分段重置会使播放器时长识别错乱/进度条拖动失效，统一为单调时间轴
            File fixed = new File(downloadDir, buildBaseName(download) + ".xhtv.tmp");
            TsRewriter.fix(merged, fixed);
            if (!fixed.renameTo(merged)) {
                fixed.delete();
                throw new IOException("timestamp rewrite rename failed");
            }

            // 成品校验：MPEG-TS 流每个 188 字节包均以 0x47 同步字节开头，首包必须命中。
            // 保证交给播放器的 .xhtv 必为合法明文 TS（播放器按内容嗅探即可播放，无需解密）；
            // 校验不过视为合并失败，回退分片模式。
            byte[] firstPacket = new byte[188];
            int got = 0, read;
            try (java.io.InputStream is = new java.io.FileInputStream(merged)) {
                while (got < firstPacket.length && (read = is.read(firstPacket, got, firstPacket.length - got)) >= 0) got += read;
            }
            if (got < 188 || (firstPacket[0] & 0xFF) != 0x47) throw new IOException("merged file is not a valid TS stream");

            // 合并成功：清理分片与临时文件，目录仅保留 .xhtv
            File[] children = downloadDir.listFiles();
            if (children != null) {
                for (File f : children) {
                    if (f.getAbsolutePath().equals(merged.getAbsolutePath())) continue;
                    String name = f.getName();
                    if (name.endsWith(".ts") || name.equals("local.m3u8") || name.equals("key.key")) f.delete();
                }
            }
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            merged.delete(); // 半成品一并清理，回退分片模式
            new File(downloadDir, buildBaseName(download) + ".xhtv.tmp").delete();
            return false;
        }
    }

    /** RFC 8216：未显式给出 IV 时，取 8 字节大端 Media Sequence Number 补齐 16 字节 */
    private byte[] sequenceIv(long seq) {
        byte[] iv = new byte[16];
        for (int i = 0; i < 8; i++) iv[15 - i] = (byte) (seq >>> (i * 8));
        return iv;
    }

    /**
     * 检测分片文件是否为 m3u8 文本残留（旧版 bug：master 子列表被当分片下载成文本）。
     * TS 分片为二进制流，头部出现 #EXTM3U/#EXTINF 文本特征的概率可忽略；命中即删除重下。
     */
    private boolean isM3u8TextGarbage(File f) {
        java.io.InputStream is = null;
        try {
            byte[] buf = new byte[1024];
            is = new java.io.FileInputStream(f);
            int got = 0, n;
            while (got < buf.length && (n = is.read(buf, got, buf.length - got)) >= 0) got += n;
            String head = new String(buf, 0, got, java.nio.charset.StandardCharsets.UTF_8);
            boolean garbage = head.contains("#EXTM3U") || head.contains("#EXTINF");
            if (garbage) f.delete();
            return garbage;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (is != null) is.close();
            } catch (Exception ignored) {}
        }
    }

    private byte[] hexToBytes(String hex) {
        if (hex.length() % 2 != 0) hex = "0" + hex;
        byte[] out = new byte[hex.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    private byte[] readAllBytes(File file) throws IOException {
        byte[] out = new byte[(int) file.length()];
        try (java.io.InputStream is = new java.io.BufferedInputStream(new FileInputStream(file))) {
            int read = 0, len;
            while ((len = is.read(out, read, out.length - read)) > 0) read += len;
        }
        return out;
    }

    // 针对单视频文件（网盘 MP4/MKV）的下载核心
    private void executeSingleFileTask(Download download, File downloadDir) {
        executeSingleFileTask(download, downloadDir, 0);
    }

    private void executeSingleFileTask(Download download, File downloadDir, int attempt) {
        String suffix = ".mp4";
        if (download.getUrl().contains(".mkv") || download.getUrl().contains(".MKV")) suffix = ".mkv";
        String baseName = buildBaseName(download);
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
                String contentType = response.header("Content-Type");
                boolean badContent = contentType != null && (contentType.contains("text/html") || contentType.contains("application/json") || contentType.contains("text/plain"));
                if (response.code() == 403 || response.code() == 410 || badContent) {
                    // 直链过期或源返回错误页：触发刷新并重新请求（限次防循环）
                    if (attempt < 2) {
                        refreshPlayUrl(download);
                        executeSingleFileTask(download, downloadDir, attempt + 1);
                        return;
                    }
                    download.setStatus(Download.STATUS_ERROR);
                    updateStatus(download);
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
                    // 已存在分片有效性：过小的残留文件（错误页/空响应）或 m3u8 文本残留（旧版把子列表当分片下载）视为无效，重新下载
                    if (target.exists() && target.length() > 512 && !isM3u8TextGarbage(target)) {
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
        return downloadSingleFile(fileUrl, targetFile, headersJson, retryCount, 512);
    }

    /** minBodySize：响应体最小字节数（TS 分片 512；AES 密钥合法地只有 16 字节，传 0） */
    private boolean downloadSingleFile(String fileUrl, File targetFile, String headersJson, int retryCount, int minBodySize) {
        try {
            Map<String, String> headers = App.gson().fromJson(headersJson, new TypeToken<Map<String, String>>() {}.getType());
            try (Response response = OkHttp.newCall(fileUrl, headers).execute()) {
                if (response.code() == 403 || response.code() == 410) {
                    throw new Exception("Link expired, need refresh");
                }
                if (!response.isSuccessful()) return false;
                // 响应内容校验：源站直链失效时常返回 200 + 错误页（HTML/JSON/纯文本）
                String contentType = response.header("Content-Type");
                if (contentType != null && (contentType.contains("text/html") || contentType.contains("application/json") || contentType.contains("text/plain"))) {
                    throw new Exception("Invalid content-type: " + contentType);
                }
                try (InputStream is = response.body().byteStream();
                     FileOutputStream fos = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = is.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                }
                // 过小的响应体视为错误页而非有效内容
                if (targetFile.length() < minBodySize) {
                    targetFile.delete();
                    throw new Exception("Response too small: " + targetFile.length());
                }
                return true;
            }
        } catch (Exception e) {
            if (retryCount > 0) {
                return downloadSingleFile(fileUrl, targetFile, headersJson, retryCount - 1, minBodySize);
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

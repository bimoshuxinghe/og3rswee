package com.fongmi.android.tv.player.exo;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.fongmi.android.tv.player.AdProbeManager;
import com.fongmi.android.tv.player.AdSegmentMemory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 在 DataSource 层拦截 m3u8 播放列表，按声纹记忆的广告区间删除分片。
 *
 * <p>内置的 {@code HlsAdsParser} 只按 URL 路径分组与块大小做启发式判断，
 * 广告与正片同目录时失效，且它是 AAR 内代码无法修改。这里改为在播放列表
 * 进入解析器之前就改写：分片根本不会进入播放列表，播放器也就不会去下载它，
 * 因此广告能做到一帧不播，且无需等探针实时检测。
 *
 * <p>只对 {@code .m3u8} 且当前视频存在已知广告区间时介入，其余请求原样透传。
 */
public final class HlsAdStrippingDataSource implements DataSource {

    private static final String TAG = "HlsAdStrip";

    /** 播放列表体积上限；真实 m3u8 只有几 KB～几百 KB，超限说明不是列表，直接透传。 */
    private static final long MAX_PLAYLIST_BYTES = 8L * 1024 * 1024;

    private final DataSource upstream;

    private byte[] rewritten;
    private int readPosition;

    public HlsAdStrippingDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public void addTransferListener(@NonNull TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        long length = upstream.open(dataSpec);
        rewritten = null;
        readPosition = 0;
        List<AdSegmentMemory.Range> ranges = rangesForCurrentVideo();
        if (ranges.isEmpty() || !looksLikePlaylist(dataSpec.uri, length)) return length;
        // 播放列表通常只有几 KB～几十 KB，直接全量读入后改写，
        // 避免在字节流上做增量解析（易错且难维护）。
        byte[] original = readFully(length);
        if (original == null || original.length == 0) return length;
        String m3u8 = new String(original, StandardCharsets.UTF_8);
        // 以 URL 粗筛、以魔数定案：伪造路径或带参的列表也能识别，
        // 又不会把 .ts 媒体分片误读进来。
        if (!m3u8.startsWith("#EXTM3U")) {
            log("命中候选但非 m3u8 内容，透传：" + dataSpec.uri);
            return length;
        }
        // 无论最终是否删除分片，都让预跳过知道这是 HLS——
        // 它必须在时间轴重排前后的坐标系里做出正确的取舍（见 AdSegmentMemory）。
        AdSegmentMemory.markHls();
        String stripped = HlsAdStripper.strip(m3u8, ranges);
        if (stripped == null || stripped.isEmpty() || stripped.equals(m3u8)) {
            log("m3u8 无命中分片可删（区间 " + ranges.size() + " 段），原样透传");
            return length;
        }
        rewritten = stripped.getBytes(StandardCharsets.UTF_8);
        // 时间轴自此整体前移，必须让记忆库停止按位置判断与写入（见 AdSegmentMemory）。
        AdSegmentMemory.markStripped();
        log("已删除广告分片：区间 " + ranges.size() + " 段，"
                + original.length + " → " + rewritten.length + " 字节");
        // 诊断：打印改写后的 m3u8 前 2500 字符，真机 Manifest Malformed 时据此定位畸形点
        // （不影响逻辑；fail-open 已保证最坏情况回退原文）。
        log("改写后 m3u8(前2500字符):\n" + (stripped.length() <= 2500 ? stripped : stripped.substring(0, 2500)));
        return rewritten.length;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        byte[] data = rewritten;
        if (data == null) return upstream.read(buffer, offset, length);
        if (readPosition >= data.length) return C.RESULT_END_OF_INPUT;
        int available = data.length - readPosition;
        int toRead = Math.min(length, available);
        System.arraycopy(data, readPosition, buffer, offset, toRead);
        readPosition += toRead;
        return toRead;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @NonNull
    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        rewritten = null;
        readPosition = 0;
        upstream.close();
    }

    private byte[] readFully(long length) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        if (length != C.LENGTH_UNSET) {
            long remaining = length;
            while (remaining > 0) {
                int read = upstream.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (read == C.RESULT_END_OF_INPUT) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        } else {
            while (true) {
                int read = upstream.read(buffer, 0, buffer.length);
                if (read == C.RESULT_END_OF_INPUT) break;
                out.write(buffer, 0, read);
            }
        }
        return out.toByteArray();
    }

    /** 诊断日志双写：Logcat + 调试页（DbgLog），与 PlayerManager 同一通道。 */
    private static void log(String msg) {
        Log.i(TAG, msg);
        try {
            Class<?> cls = Class.forName("com.fongmi.chaquo.DbgLog");
            cls.getMethod("log", String.class).invoke(null, "[HlsAdStrip] " + msg);
        } catch (Throwable ignored) {
        }
    }

    /** 当前正在播放的视频的已知广告区间；取不到时返回空列表，等于不介入。 */
    private static List<AdSegmentMemory.Range> rangesForCurrentVideo() {
        try {
            // 播放侧显式注入的地址优先；探针的 lastUrl 仅作兜底——它在探针未就绪时
            // 永远是 null，不能作为唯一来源。
            String url = AdSegmentMemory.getCurrentUrl();
            List<AdSegmentMemory.Range> ranges = AdSegmentMemory.get(url);
            if (!ranges.isEmpty()) return ranges;
            return AdSegmentMemory.get(AdProbeManager.get().getLastUrl());
        } catch (Throwable e) {
            return Collections.emptyList();
        }
    }

    /**
     * URL 粗筛：既要覆盖 {@code index.php?url=x.m3u8} 这类带参地址，
     * 又要用体积上限把媒体分片排除在外，避免无谓地全量读入。
     */
    private static boolean looksLikePlaylist(@Nullable Uri uri, long length) {
        if (uri == null) return false;
        if (length > MAX_PLAYLIST_BYTES) return false;
        return uri.toString().toLowerCase().contains("m3u8");
    }

    public static final class Factory implements DataSource.Factory {

        private final DataSource.Factory upstream;

        public Factory(DataSource.Factory upstream) {
            this.upstream = upstream;
        }

        @NonNull
        @Override
        public DataSource createDataSource() {
            return new HlsAdStrippingDataSource(upstream.createDataSource());
        }
    }
}

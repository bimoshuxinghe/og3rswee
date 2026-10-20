package com.fongmi.android.tv.player.exo;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import com.fongmi.android.tv.bean.AdBlockStats;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.HlsAdEngine;
import com.fongmi.android.tv.utils.HlsAdRuleStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * 在 DataSource 层拦截 m3u8 播放列表，调用 {@link HlsAdEngine} 净化后交给解析器。
 *
 * <p>与旧版（声纹记忆区间删除）的本质区别：判定不依赖任何播放历史，
 * 每次加载都从 manifest 正文独立得出结论——规则引擎（结构化多信号）主战，
 * 特征引擎（切片命名规律）兜底，统一安全框架裁决（比例/时长/结构/直播
 * 头部限制，任一不安全整体回退原文）。
 *
 * <p>只对 m3u8 介入，其余请求原样透传；任何异常都 fail-open 回原文。
 */
public final class HlsAdStrippingDataSource implements DataSource {

    private static final String TAG = "HlsAdStrip";

    /** 播放列表体积上限；真实 m3u8 只有几 KB～几百 KB，超限说明不是列表，直接透传。 */
    private static final long MAX_PLAYLIST_BYTES = 8L * 1024 * 1024;

    private final DataSource upstream;

    /**
     * 本次 open 的输出缓冲。凡是 readFully 已把 upstream 读空的路径
     * （净化命中、unchanged、非 m3u8 内容）都必须把已读到的字节放进
     * 这里供 read() 输出——upstream 已到 EOF，再走 upstream.read() 只会
     * 返回 END_OF_INPUT，播放器将收到空播放列表（Manifest Malformed）。
     * 只有完全未介入（非 m3u8 URL、总开关关闭）时才保持 null 走透传。
     */
    private byte[] data;
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
        data = null;
        readPosition = 0;
        if (!Setting.isAdblock() || !looksLikePlaylist(dataSpec.uri, length)) return length;
        // 播放列表通常只有几 KB～几十 KB，直接全量读入后改写
        byte[] original = readFully(length);
        if (original == null || original.length == 0) return length;
        String m3u8 = new String(original, StandardCharsets.UTF_8);
        // 以 URL 粗筛、以魔数定案：伪造路径或带参的列表也能识别，
        // 又不会把 .ts 媒体分片误读进来。内容不是 m3u8 时把已读
        // 字节原样回放，绝不能再走 upstream（已在 EOF）。
        if (!m3u8.startsWith("#EXTM3U")) {
            data = original;
            return original.length;
        }
        String url = dataSpec.uri.toString();
        HlsAdEngine.Result result = HlsAdEngine.clean(url, m3u8,
                HlsAdRuleStore.getRules(), Setting.isHlsAdFeatureFilter(), Setting.isHlsAdProbe());
        AdBlockStats.get().record(result.engine(), result.changed(), result.fallback(), result.removedSegments());
        if (!result.changed()) {
            if (result.fallback()) {
                log("净化回退原文（引擎 " + result.engine() + "，安全阈值或结构不满足）");
            }
            // unchanged 同样必须回放已读字节
            data = original;
            return original.length;
        }
        data = result.manifest().getBytes(StandardCharsets.UTF_8);
        log("已净化播放列表：引擎 " + result.engine() + "，删除 " + result.removedSegments()
                + " 分片 / " + Math.round(result.removedDurationSec()) + "s，"
                + original.length + " → " + data.length + " 字节");
        return data.length;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        byte[] out = data;
        if (out == null) return upstream.read(buffer, offset, length);
        if (readPosition >= out.length) return C.RESULT_END_OF_INPUT;
        int available = out.length - readPosition;
        int toRead = Math.min(length, available);
        System.arraycopy(out, readPosition, buffer, offset, toRead);
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
        data = null;
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

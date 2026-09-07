package com.fongmi.android.tv.player.exo;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.fongmi.android.tv.App;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 无时长流（TS 直链等 progressive 源）的估算时长支持。
 * <p>
 * 背景：百度网盘等直链常见 TS 裸流，{@code TsExtractor} 无法提供时长与索引，
 * ExoPlayer 的 {@code player.getDuration()} 恒为 TIME_UNSET，进度条因此被禁用；
 * 而 MPV（ffmpeg）会自行估算时长，故能正常拖动。本类为 EXO 补齐这一能力：
 * <ol>
 *   <li>探测文件总大小（HEAD，失败退回 Range 0-0 读 Content-Range）；</li>
 *   <li>通过播放器数据源拦截器累计实际读取字节；</li>
 *   <li>周期采样「字节增量 / 缓冲媒体时间增量」得到真实媒体码率（指数平滑）；</li>
 *   <li>估算总时长 = 总字节 × 8 ÷ 码率，供进度条渲染与拖动使用。</li>
 * </ol>
 * 注意：估算时长与 PTS 时间轴同域（均基于缓冲位置），拖动语义与普通流一致；
 * TS 无索引，向后 seek 时 EXO 会快速扫描到目标位置，属于能力边界而非缺陷。
 */
public class DurationProbe {

    private static final String TAG = "DurationProbe";
    private static final long SAMPLE_INTERVAL_MS = 3000;
    private static final long MIN_BYTE_DELTA = 512 * 1024;   // 采样窗口最小字节增量，避免小样本抖动
    private static final int PROBE_TIMEOUT_MS = 8000;

    private static volatile boolean active;
    private static volatile String url;
    private static volatile long totalLength = -1;
    private static volatile long bitrateBps = -1;
    private static volatile long estimatedMs;
    private static volatile boolean fromPlayResponse; // 大小是否来自播放响应（而非 HEAD）
    private static volatile boolean rangeSupported;   // 服务器是否声明支持 Range（决定能否真正 seek）

    private static final AtomicLong counter = new AtomicLong(); // 数据源实际读取的字节累计
    private static long lastBytes;
    private static long lastBufferedMs = -1;
    private static Supplier<Long> bufferedSupplier;
    private static Supplier<Long> positionSupplier;
    private static final Handler handler = new Handler(Looper.getMainLooper());

    private DurationProbe() {
    }

    /**
     * 对当前播放的无时长流启动估算。
     *
     * @param u        播放地址（http/https 直链）
     * @param headers  播放请求头（接口源下发的 UA 等）
     * @param buffered 缓冲位置（媒体时间域）供给器
     * @param position 当前播放位置供给器（仅用于日志诊断）
     */
    public static void start(String u, Map<String, String> headers, Supplier<Long> buffered, Supplier<Long> position) {
        // 注意：这里不能用 clear()——它会清掉拦截器刚记录的 totalLength（本次播放响应信息）
        active = false;
        handler.removeCallbacks(SAMPLE_RUNNABLE);
        if (u == null || !u.startsWith("http")) return;
        url = u;
        bufferedSupplier = buffered;
        positionSupplier = position;
        active = true;
        // 只清采样状态，保留拦截器记录的 totalLength/rangeSupported（属于本次播放）
        counter.set(0);
        lastBytes = 0;
        lastBufferedMs = -1;
        bitrateBps = -1;
        estimatedMs = 0;
        Log.i(TAG, "start probe: " + u + " totalFromResponse=" + fromPlayResponse + " length=" + totalLength + " range=" + rangeSupported);
        // 播放响应已给出大小则无需 HEAD（网盘直链常拒绝 HEAD）
        if (totalLength > 0) {
            estimate();
        } else {
            new Thread(() -> {
                totalLength = probeLength(u, headers);
                Log.i(TAG, "HEAD probe length=" + totalLength);
                estimate();
            }, "duration-probe").start();
        }
        handler.postDelayed(SAMPLE_RUNNABLE, SAMPLE_INTERVAL_MS);
    }

    /** 清除当前估算状态（换源/换集/释放时调用） */
    public static void clear() {
        active = false;
        url = null;
        totalLength = -1;
        bitrateBps = -1;
        estimatedMs = 0;
        fromPlayResponse = false;
        rangeSupported = false;
        counter.set(0);
        lastBytes = 0;
        lastBufferedMs = -1;
        bufferedSupplier = null;
        positionSupplier = null;
        handler.removeCallbacks(SAMPLE_RUNNABLE);
    }

    /** 数据源拦截器回调：累计实际读取的字节数 */
    public static void onBytes(long count) {
        if (active && count > 0) counter.addAndGet(count);
    }

    /** 当前估算时长（毫秒）；不可估算时返回 0 */
    public static long getEstimated() {
        return active ? estimatedMs : 0;
    }

    /** 是否已在对该地址进行估算探测（避免重复启动） */
    public static boolean isTracking(String u) {
        return active && u != null && u.equals(url);
    }

    /** 估算是否处于激活状态（拦截器据此决定是否包装响应体） */
    public static boolean isActive() {
        return active;
    }

    /**
     * 记录播放响应中的大小与 Range 信息（由拦截器调用）。
     * 网盘直链常拒绝 HEAD，播放响应的 Content-Length / Content-Range 才是可靠来源。
     */
    public static void noteResponse(Object response) {
        try {
            if (!(response instanceof okhttp3.Response)) return;
            okhttp3.Response r = (okhttp3.Response) response;
            String range = r.header("Content-Range");
            if (range != null && range.contains("/")) {
                String total = range.substring(range.lastIndexOf('/') + 1).trim();
                if (!"*".equals(total) && !total.isEmpty()) {
                    long parsed = Long.parseLong(total);
                    if (parsed > 0) {
                        totalLength = parsed;
                        fromPlayResponse = true;
                    }
                }
            }
            if (totalLength <= 0 && r.code() == 200) {
                okhttp3.ResponseBody body = r.body();
                if (body != null && body.contentLength() > 0) {
                    totalLength = body.contentLength();
                    fromPlayResponse = true;
                }
            }
            String acceptRanges = r.header("Accept-Ranges");
            if (acceptRanges != null) rangeSupported = "bytes".equalsIgnoreCase(acceptRanges.trim());
        } catch (Throwable ignored) {
        }
    }

    /** 服务器是否声明支持 Range 请求；不支持则 EXO 无法真正 seek（只能顺序播放） */
    public static boolean isRangeSupported() {
        return rangeSupported;
    }

    private static final Runnable SAMPLE_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            sample();
            handler.postDelayed(this, SAMPLE_INTERVAL_MS);
        }
    };

    private static void sample() {
        try {
            Supplier<Long> buffered = bufferedSupplier;
            long b = counter.get();
            long bufferedMs = buffered == null ? -1 : safeGet(buffered);
            long byteDelta = b - lastBytes;
            if (lastBufferedMs >= 0 && bufferedMs > lastBufferedMs && byteDelta > MIN_BYTE_DELTA) {
                long mediaDeltaMs = bufferedMs - lastBufferedMs;
                long windowBps = byteDelta * 8000L / mediaDeltaMs;
                // 指数平滑：新窗口 1/3 权重，避免网络抖动引起估算跳变
                bitrateBps = bitrateBps < 0 ? windowBps : (bitrateBps * 2 + windowBps) / 3;
                Log.d(TAG, "sample bytes=" + byteDelta + " media=" + mediaDeltaMs + "ms bitrate=" + bitrateBps);
                estimate();
            }
            lastBytes = b;
            if (bufferedMs >= 0) lastBufferedMs = bufferedMs;
        } catch (Throwable ignored) {
        }
    }

    private static void estimate() {
        if (totalLength > 0 && bitrateBps > 0) {
            long est = totalLength * 8000L / bitrateBps;
            // 合理范围校验：5 秒 ~ 24 小时
            if (est > 5000 && est < 24L * 3600 * 1000) {
                if (estimatedMs == 0) Log.i(TAG, "估算时长=" + est + "ms length=" + totalLength + " bitrate=" + bitrateBps + " rangeSupported=" + rangeSupported);
                estimatedMs = est;
                return;
            }
        }
        // 诊断：说明估算为何未产出，便于定位"拖不动"根因
        if (active) Log.d(TAG, "估算未就绪 length=" + totalLength + " bitrate=" + bitrateBps + " bytes=" + counter.get() + " rangeSupported=" + rangeSupported);
    }

    private static long safeGet(Supplier<Long> supplier) {
        try {
            Long v = supplier.get();
            return v == null ? -1 : v;
        } catch (Throwable e) {
            return -1;
        }
    }

    /** 探测文件总大小：先 HEAD，失败退回 Range 0-0 解析 Content-Range */
    private static long probeLength(String u, Map<String, String> headers) {
        long len = probeOnce(u, headers, true);
        if (len > 0) return len;
        return probeOnce(u, headers, false);
    }

    private static long probeOnce(String u, Map<String, String> headers, boolean head) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(u).openConnection();
            conn.setConnectTimeout(PROBE_TIMEOUT_MS);
            conn.setReadTimeout(PROBE_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            if (head) conn.setRequestMethod("HEAD");
            else conn.setRequestProperty("Range", "bytes=0-0");
            if (headers != null) {
                for (Map.Entry<String, String> e : headers.entrySet()) {
                    if (e.getValue() != null && !"Range".equalsIgnoreCase(e.getKey())) conn.setRequestProperty(e.getKey(), e.getValue());
                }
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                if (head) {
                    long len = conn.getContentLengthLong();
                    if (len > 0) return len;
                } else {
                    String range = conn.getHeaderField("Content-Range");
                    if (!TextUtils.isEmpty(range) && range.contains("/")) {
                        String total = range.substring(range.lastIndexOf('/') + 1).trim();
                        if (!"*".equals(total)) return Long.parseLong(total);
                    }
                    long len = conn.getContentLengthLong();
                    if (len > 0) return len; // 无 Content-Range 时只能退化为该段大小（无法估算时长）
                }
            }
            return -1;
        } catch (IOException e) {
            return -1;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }
}

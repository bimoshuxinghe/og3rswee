package com.fongmi.android.tv.utils;

import com.github.catvod.net.OkHttp;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 引擎三：DISCONTINUITY 段分辨率探测。
 * <p>
 * 有一类源会把广告整段转码后混进正片：manifest 里分片命名、时长、
 * DISCONTINUITY 分布与正片完全一致，无任何可识别特征，唯一差异是
 * 广告段的视频编码参数（分辨率）与正片不同。
 * <p>
 * 思路：对每个 DISCONTINUITY 段的首个分片发 32KB Range 请求，
 * 从 TS 流里解析 H.264 SPS 拿到分辨率，与全片主分辨率（众数）比对；
 * 面积明显偏小且总时长受控的段判为广告。
 * 探测失败、主分辨率不确定、广告总时长超限时一律放弃（fail-open）。
 */
public final class HlsResolutionProbe {

    private static final int RANGE_BYTES = 32 * 1024;
    private static final int MAX_GROUPS = 400;
    private static final int MIN_GROUPS = 4;
    private static final int THREADS = 12;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final double AREA_RATIO = 0.64;
    private static final double MAX_FAIL_RATIO = 0.3;
    private static final double MAX_AD_DURATION_SEC = 90;
    private static final int MAX_WIDTH = 8192;
    private static final int MIN_WIDTH = 64;

    private static final Map<String, Set<Integer>> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newFixedThreadPool(THREADS, r -> {
        Thread t = new Thread(r, "hls-probe");
        t.setDaemon(true);
        return t;
    });

    /** 一个 DISCONTINUITY 段：包含的分片序号、首分片绝对地址、段总时长。 */
    public record Group(int[] indexes, String probeUrl, double durationSec) {
    }

    private HlsResolutionProbe() {
    }

    /**
     * 探测并返回广告分片的下标集合；无法确定时返回空集合。
     *
     * @param cacheKey 结果缓存键（一般用播放列表地址 + 分片数）
     */
    public static Set<Integer> detect(String cacheKey, List<Group> groups) {
        if (cacheKey == null || groups == null) return new HashSet<>();
        Set<Integer> cached = CACHE.get(cacheKey);
        if (cached != null) return cached;
        Set<Integer> result = detectInternal(cacheKey, groups);
        if (CACHE.size() > 32) CACHE.clear();
        CACHE.put(cacheKey, result);
        return result;
    }

    private static Set<Integer> detectInternal(String cacheKey, List<Group> groups) {
        Set<Integer> empty = new HashSet<>();
        if (groups.size() < MIN_GROUPS || groups.size() > MAX_GROUPS) return empty;

        List<int[]> sizes = probeAll(groups);
        if (sizes == null) return empty;

        // 主分辨率 = 众数；探测失败率超限或众数并列时放弃
        Map<String, Integer> counter = new HashMap<>();
        int ok = 0;
        for (int[] size : sizes) {
            if (size == null) continue;
            ok++;
            counter.merge(size[0] + "x" + size[1], 1, Integer::sum);
        }
        if (ok < groups.size() * (1 - MAX_FAIL_RATIO) || counter.isEmpty()) return empty;

        String mainKey = null;
        int mainCount = 0;
        boolean tie = false;
        for (Map.Entry<String, Integer> e : counter.entrySet()) {
            if (e.getValue() > mainCount) {
                mainCount = e.getValue();
                mainKey = e.getKey();
                tie = false;
            } else if (e.getValue() == mainCount) {
                tie = true;
            }
        }
        if (mainKey == null || tie || mainCount < groups.size() / 2) return empty;

        int[] main = parseSize(mainKey);
        long mainArea = (long) main[0] * main[1];
        if (mainArea <= 0) return empty;

        // 面积明显偏小的段判为广告，总时长超限则整体放弃
        Set<Integer> ads = new HashSet<>();
        double adDuration = 0;
        for (int i = 0; i < groups.size(); i++) {
            int[] size = sizes.get(i);
            if (size == null) continue;
            long area = (long) size[0] * size[1];
            if (area >= mainArea * AREA_RATIO) continue;
            Group group = groups.get(i);
            adDuration += group.durationSec();
            for (int index : group.indexes()) ads.add(index);
        }
        if (adDuration > MAX_AD_DURATION_SEC) return empty;
        return ads;
    }

    private static List<int[]> probeAll(List<Group> groups) {
        try {
            List<Callable<int[]>> tasks = new ArrayList<>();
            for (Group group : groups) tasks.add(() -> fetchResolution(group.probeUrl()));
            List<Future<int[]>> futures = POOL.invokeAll(tasks);
            List<int[]> sizes = new ArrayList<>(groups.size());
            for (Future<int[]> future : futures) {
                int[] size = null;
                try {
                    size = future.get();
                } catch (Throwable ignored) {
                }
                sizes.add(size);
            }
            return sizes;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 下载分片头部并解析分辨率；失败返回 null。 */
    private static int[] fetchResolution(String url) {
        try {
            Request request = new Request.Builder().url(url)
                    .header("Range", "bytes=0-" + (RANGE_BYTES - 1))
                    .header("User-Agent", "Mozilla/5.0").build();
            Response response = OkHttp.client(CONNECT_TIMEOUT_MS).newCall(request).execute();
            try (ResponseBody body = response.body()) {
                if (!response.isSuccessful() || body == null) return null;
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int len;
                java.io.InputStream in = body.byteStream();
                while ((len = in.read(buffer)) > 0 && out.size() < RANGE_BYTES) out.write(buffer, 0, len);
                int[] size = SpsParser.find(out.toByteArray());
                if (size != null && valid(size)) return size;
            } finally {
                response.close();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean valid(int[] size) {
        return size[0] >= MIN_WIDTH && size[0] <= MAX_WIDTH && size[1] >= MIN_WIDTH && size[1] <= MAX_WIDTH;
    }

    private static int[] parseSize(String key) {
        String[] parts = key.split("x");
        try {
            return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
        } catch (Exception e) {
            return new int[]{0, 0};
        }
    }

    /**
     * 从 TS 字节流中定位 H.264 SPS 并解析出宽高。
     * 只依赖 AnnexB 起始码扫描，不需要完整的 PAT/PMT 解析；
     * 找不到 SPS 或非 H.264 流返回 null。
     */
    static final class SpsParser {

        static int[] find(byte[] data) {
            for (int i = 0; i + 4 < data.length; i++) {
                if (data[i] != 0 || data[i + 1] != 0) continue;
                int nalStart;
                if (data[i + 2] == 1) nalStart = i + 3;
                else if (data[i + 2] == 0 && data[i + 3] == 1) nalStart = i + 4;
                else continue;
                if (nalStart >= data.length) continue;
                int type = data[nalStart] & 0x1F;
                if (type != 7) continue;
                int nalEnd = nextStart(data, nalStart);
                byte[] rbsp = unescape(data, nalStart + 1, nalEnd);
                try {
                    return parse(rbsp);
                } catch (Throwable ignored) {
                    return null;
                }
            }
            return null;
        }

        private static int nextStart(byte[] data, int from) {
            for (int i = from; i + 2 < data.length; i++) {
                if (data[i] == 0 && data[i + 1] == 0 && (data[i + 2] == 1 || (data[i + 2] == 0 && i + 3 < data.length && data[i + 3] == 1))) return i;
            }
            return data.length;
        }

        /** EBSP → RBSP：去掉防竞争字节 0x03。 */
        private static byte[] unescape(byte[] data, int start, int end) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(end - start, 512));
            int zeros = 0;
            for (int i = start; i < end && i < data.length; i++) {
                if (zeros >= 2 && data[i] == 0x03) {
                    zeros = 0;
                    continue;
                }
                zeros = data[i] == 0 ? zeros + 1 : 0;
                out.write(data[i]);
            }
            return out.toByteArray();
        }

        private static int[] parse(byte[] rbsp) {
            BitReader r = new BitReader(rbsp, 1); // 跳过 NAL header
            int profileIdc = r.u(8);
            r.u(16); // constraint flags + reserved_zero_sixbits + level_idc
            r.ue(); // seq_parameter_set_id
            int chromaFormatIdc = 1;
            if (isHighProfile(profileIdc)) {
                chromaFormatIdc = r.ue();
                if (chromaFormatIdc == 3) r.u(1);
                r.ue();
                r.ue();
                r.u(1);
                if (r.u(1) == 1) {
                    int count = chromaFormatIdc != 3 ? 8 : 12;
                    for (int i = 0; i < count; i++) {
                        if (r.u(1) == 1) skipScalingList(r, i < 6 ? 16 : 64);
                    }
                }
            }
            r.ue(); // log2_max_frame_num_minus4
            int picOrderCntType = r.ue();
            if (picOrderCntType == 0) {
                r.ue();
            } else if (picOrderCntType == 1) {
                r.u(1);
                r.ue();
                r.ue();
                int n = r.ue();
                for (int i = 0; i < n; i++) r.ue();
            }
            r.ue(); // max_num_ref_frames
            r.u(1); // gaps_in_frame_num_value_allowed_flag
            int widthMbs = r.ue() + 1;
            int heightMapUnits = r.ue() + 1;
            int frameMbsOnly = r.u(1);
            if (frameMbsOnly == 0) r.u(1);
            r.u(1); // direct_8x8_inference_flag
            int cropL = 0, cropR = 0, cropT = 0, cropB = 0;
            if (r.u(1) == 1) {
                cropL = r.ue();
                cropR = r.ue();
                cropT = r.ue();
                cropB = r.ue();
            }
            int width = widthMbs * 16;
            int height = (2 - frameMbsOnly) * heightMapUnits * 16;
            // H.264 标准 CropUnitX/Y 表：0=单色, 1=4:2:0, 2=4:2:2, 3=4:4:4
            int cropUnitX;
            int cropUnitY;
            switch (chromaFormatIdc) {
                case 0:
                    cropUnitX = 1;
                    cropUnitY = 2 - frameMbsOnly;
                    break;
                case 2:
                    cropUnitX = 2;
                    cropUnitY = 2 - frameMbsOnly;
                    break;
                case 3:
                    cropUnitX = 1;
                    cropUnitY = 2 - frameMbsOnly;
                    break;
                default: // 4:2:0 最常见
                    cropUnitX = 2;
                    cropUnitY = 2 * (2 - frameMbsOnly);
                    break;
            }
            width -= (cropL + cropR) * cropUnitX;
            height -= (cropT + cropB) * cropUnitY;
            return new int[]{width, height};
        }

        private static boolean isHighProfile(int profileIdc) {
            return profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244
                    || profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118
                    || profileIdc == 128 || profileIdc == 138 || profileIdc == 139 || profileIdc == 134
                    || profileIdc == 135;
        }

        private static void skipScalingList(BitReader r, int size) {
            int lastScale = 8;
            int nextScale = 8;
            for (int j = 0; j < size; j++) {
                if (nextScale != 0) {
                    int delta = r.se();
                    nextScale = (lastScale + delta + 256) % 256;
                }
                if (nextScale != 0) lastScale = nextScale;
            }
        }
    }

    /** H.264 SPS 用的无符号/有符号指数哥伦布比特读取器。 */
    private static final class BitReader {

        private final byte[] data;
        private int pos;

        BitReader(byte[] data, int startBit) {
            this.data = data;
            this.pos = startBit * 8;
        }

        int u(int n) {
            int value = 0;
            for (int i = 0; i < n; i++) value = (value << 1) | bit();
            return value;
        }

        int ue() {
            int zeros = 0;
            while (bit() == 0) {
                zeros++;
                if (zeros > 31) throw new IllegalArgumentException("invalid exp-golomb");
            }
            return (1 << zeros) - 1 + (zeros == 0 ? 0 : u(zeros));
        }

        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }

        private int bit() {
            int index = pos >> 3;
            if (index >= data.length) throw new IllegalArgumentException("out of data");
            int b = (data[index] >> (7 - (pos & 7))) & 1;
            pos++;
            return b;
        }
    }
}

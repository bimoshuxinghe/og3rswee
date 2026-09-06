package com.fongmi.android.tv.server.process;

import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import android.text.TextUtils;

import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.impl.Process;
import com.github.catvod.net.OkHttp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * 央视频(YSP/CCTV)直播代理 —— PHP psy1.php 的 Java 原生实现。
 * <p>
 * 用法：
 * <pre>
 *   /ysp?id=cctv1                                直播：返回补全过 TS 路径的 m3u8（80s 缓存）
 *   /ysp?id=cctv1&playseek=YYYYMMDDHHMMSS-...    回看：302 跳转
 *   /ysp?id=cctv1&debug=1                        调试：返回上游原始 JSON
 *   /ysp                                         频道列表
 * </pre>
 * 算法链路：buildPacket(13 字段二进制包) → TEA-CBC(oi_symmetry_encrypt2) + 校验和 →
 * XOR(16字节循环) → 自定义 Base64 → cKey → 请求 bkliveinfo.ysp.cctv.cn → playurl。
 */
public class Ysp implements Process {

    private static final Pattern TS_PATTERN = Pattern.compile("(.*?\\.ts)", Pattern.CASE_INSENSITIVE);
    private static final String UA = "qqlive";
    private static final String API = "https://bkliveinfo.ysp.cctv.cn";
    private static final long CACHE_TIMEOUT = 80_000L; // 直播地址缓存 80s

    private final Random random = new Random();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private String guid = "";

    private static class CacheEntry {
        final String url;
        final long time;

        CacheEntry(String url) {
            this.url = url;
            this.time = System.currentTimeMillis();
        }

        boolean valid() {
            return System.currentTimeMillis() - time <= CACHE_TIMEOUT;
        }
    }

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/ysp");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        try {
            Map<String, String> parms = session.getParms();
            if ("live".equals(parms.get("list"))) return Nano.ok(listLive(session));
            String id = TextUtils.isEmpty(parms.get("id")) ? "cctv1" : parms.get("id");
            String playseek = TextUtils.isEmpty(parms.get("playseek")) ? null : parms.get("playseek");
            String[] channel = Channel.find(id);
            if (channel == null) return Nano.ok(Channel.list());
            String cnlid = channel[0], livepid = channel[1], defn = channel[2];

            if ("1".equals(parms.get("debug"))) return Nano.ok(upstreamRaw(cnlid, livepid, defn, playseek));

            boolean live = TextUtils.isEmpty(playseek);
            if (!live) {
                String playurl = getPlayUrl(cnlid, livepid, defn, playseek);
                if (playurl == null) return Nano.error("获取播放地址失败");
                return redirect(playurl);
            }

            // 直播：带 80s 缓存，失败清缓存重试
            CacheEntry entry = cache.get(id);
            boolean needRefresh = entry == null || !entry.valid();
            String playurl = needRefresh ? null : entry.url;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (needRefresh) {
                    playurl = getPlayUrl(cnlid, livepid, defn, null);
                    if (playurl == null) return Nano.error("获取播放地址失败");
                    cache.put(id, new CacheEntry(playurl));
                }
                String m3u8 = fetchM3u8(playurl);
                if (m3u8 != null) return m3u8Response(patchTs(m3u8, playurl));
                if (!needRefresh) { // 上一轮用了缓存
                    cache.remove(id);
                    needRefresh = true;
                } else break;
            }
            return Nano.error("无法获取 M3U8 内容，请稍后重试");
        } catch (Throwable e) {
            return Nano.error(e.getMessage());
        }
    }

    private Response m3u8Response(String body) {
        Response response = newFixedLengthResponse(Status.OK, "application/vnd.apple.mpegurl", body);
        response.addHeader("Access-Control-Allow-Origin", "*");
        return response;
    }

    /**
     * 动态输出 FongMi 直播源 txt（/ysp?list=live）。
     * Host 取请求头中的 host（客户端用什么地址访问，列表里就回什么地址），
     * 因此 127.0.0.1 / 局域网 IP / 端口顺延场景都天然正确。
     */
    private String listLive(IHTTPSession session) {
        String host = session.getHeaders().get("host");
        if (TextUtils.isEmpty(host)) host = "127.0.0.1:9978";
        StringBuilder sb = new StringBuilder();
        sb.append("央视频,#genre#\n");
        for (Map.Entry<String, String[]> e : Channel.MAP.entrySet()) {
            sb.append(Channel.NAMES.get(e.getKey())).append(",http://").append(host)
                    .append("/ysp?id=").append(e.getKey()).append("#\n");
        }
        return sb.toString();
    }

    private Response redirect(String url) {
        Response response = newFixedLengthResponse(Status.REDIRECT, Nano.MIME_PLAINTEXT, "");
        response.addHeader("Location", url);
        return response;
    }

    // ---------------- 频道表 ----------------

    private static class Channel {
        private static final Map<String, String[]> MAP = new LinkedHashMap<>();
        private static final Map<String, String> NAMES = new LinkedHashMap<>();

        static {
            put("cctv1", "2024078201", "600001859", "fhd", "CCTV-1");
            put("cctv2", "2024075401", "600001800", "fhd", "CCTV-2");
            put("cctv3", "2024068501", "600001801", "fhd", "CCTV-3");
            put("cctv4", "2029797101", "600001814", "fhd", "CCTV-4");
            put("cctv5", "2024078401", "600001818", "fhd", "CCTV-5");
            put("cctv5p", "2024078001", "600001817", "fhd", "CCTV-5+");
            put("cctv6", "2013693901", "600108442", "fhd", "CCTV-6");
            put("cctv7", "2024072001", "600004092", "fhd", "CCTV-7");
            put("cctv8", "2029793001", "600001803", "fhd", "CCTV-8");
            put("cctv9", "2024078601", "600004078", "fhd", "CCTV-9");
            put("cctv10", "2024078701", "600001805", "fhd", "CCTV-10");
            put("cctv11", "2027248701", "600001806", "fhd", "CCTV-11");
            put("cctv12", "2027248801", "600001807", "fhd", "CCTV-12");
            put("cctv13", "2029797201", "600001811", "fhd", "CCTV-13");
            put("cctv14", "2027248901", "600001809", "fhd", "CCTV-14");
            put("cctv15", "2027249001", "600001815", "fhd", "CCTV-15");
            put("cctv16", "2027249101", "600098637", "fhd", "CCTV-16");
            put("cctv164k", "2027249301", "600099502", "fhd", "CCTV-16(4K)");
            put("cctv17", "2027249401", "600001810", "fhd", "CCTV-17");
            put("cctv4k", "2029810301", "600002264", "fhd", "CCTV-4K");
            put("cctv8k", "2026774101", "600156816", "fhd", "CCTV-8K");
            put("cgtn", "2024181701", "600014550", "fhd", "CGTN");
            put("cgtnfy", "2024181801", "600084704", "fhd", "CGTN法语频道");
            put("cgtney", "2024181901", "600084758", "fhd", "CGTN俄语频道");
            put("cgtnalby", "2024182001", "600084782", "fhd", "CGTN阿拉伯语频道");
            put("cgtnxby", "2024182101", "600084744", "fhd", "CGTN西班牙语频道");
            put("cgtnwyjl", "2024182301", "600084781", "fhd", "CGTN外语纪录频道");
            put("cctvfyjc", "2025637103", "600099658", "shd", "CCTV风云剧场频道");
            put("cctvdyjc", "2026874203", "600099655", "shd", "CCTV第一剧场频道");
            put("cctvhjjc", "2026874303", "600099620", "shd", "CCTV怀旧剧场频道");
            put("cctvsjdl", "2026874403", "600099637", "shd", "CCTV世界地理频道");
            put("cctvfyyy", "2026874503", "600099660", "shd", "CCTV风云音乐频道");
            put("cctvbqkj", "2026874603", "600099649", "shd", "CCTV兵器科技频道");
            put("cctvfyzq", "2026966203", "600099636", "shd", "CCTV风云足球频道");
            put("cctvgeqwq", "2026874703", "600099659", "shd", "CCTV高尔夫·网球频道");
            put("cctvnxss", "2026874803", "600099650", "shd", "CCTV女性时尚频道");
            put("cctvyswhjp", "2026874903", "600099653", "shd", "CCTV央视文化精品频道");
            put("cctvystq", "2026875003", "600099652", "shd", "CCTV央视台球频道");
            put("cctvdszn", "2026875103", "600099656", "shd", "CCTV电视指南频道");
            put("cctvwsjk", "2025637003", "600099651", "shd", "CCTV卫生健康频道");
            put("bjws", "2024052703", "600002309", "fhd", "北京卫视");
            put("jsws", "2024171103", "600002521", "fhd", "江苏卫视");
            put("dfws", "2024054503", "600002483", "fhd", "东方卫视");
            put("zjws", "2024054703", "600002520", "fhd", "浙江卫视");
            put("hnws", "2024054803", "600002475", "fhd", "湖南卫视");
            put("hbws", "2024171203", "600002508", "fhd", "湖北卫视");
            put("gdws", "2024060903", "600002485", "fhd", "广东卫视");
            put("gxws", "2024060703", "600002509", "fhd", "广西卫视");
            put("hljws", "2029797003", "600002498", "fhd", "黑龙江卫视");
            put("hnws2", "2024055603", "600002506", "fhd", "海南卫视");
            put("cqws", "2024061103", "600002531", "fhd", "重庆卫视");
            put("szws", "2024061303", "600002481", "fhd", "深圳卫视");
            put("scws", "2024061403", "600002516", "fhd", "四川卫视");
            put("henanws", "2029797303", "600002525", "fhd", "河南卫视");
            put("fjdnhz", "2024061503", "600002484", "fhd", "福建东南卫视");
            put("gzhws", "2024061603", "600002490", "fhd", "贵州卫视");
            put("jxws", "2024061703", "600002503", "fhd", "江西卫视");
            put("lnws", "2024171303", "600002505", "fhd", "辽宁卫视");
            put("ahws", "2024171403", "600002532", "fhd", "安徽卫视");
            put("hbws2", "2024171503", "600002493", "fhd", "河北卫视");
            put("sdws", "2029787903", "600002513", "fhd", "山东卫视");
            put("tjws", "2019927003", "600152137", "fhd", "天津卫视");
            put("jlws", "2025561503", "600190405", "fhd", "吉林卫视");
            put("shanxiws", "2029795103", "600190400", "fhd", "陕西卫视");
            put("nxws", "2025608503", "600190737", "fhd", "宁夏卫视");
            put("nmgws", "2025561203", "600190401", "fhd", "内蒙古卫视");
            put("ynws", "2025561303", "600190402", "fhd", "云南卫视");
            put("shanxiws2", "2025560803", "600190407", "fhd", "山西卫视");
            put("qhws", "2025559103", "600190406", "fhd", "青海卫视");
            put("xzws", "2025558003", "600190403", "fhd", "西藏卫视");
            put("cetv1", "2022823801", "600171827", "fhd", "中国教育电视台1频道");
            put("gxpd", "2029360403", "600213139", "fhd", "国学频道");
            put("xjws", "2019927403", "600152138", "fhd", "新疆卫视");
        }

        static void put(String id, String cnlid, String livepid, String defn, String name) {
            MAP.put(id, new String[]{cnlid, livepid, defn});
            NAMES.put(id, name);
        }

        static String[] find(String id) {
            return MAP.get(id);
        }

        static String list() {
            StringBuilder sb = new StringBuilder("YSP 直播代理（PHP psy1 的 Java 版）\n用法：/ysp?id=<频道>&playseek=YYYYMMDDHHMMSS-YYYYMMDDHHMMSS\n\n可用频道：\n");
            for (Map.Entry<String, String> e : NAMES.entrySet()) sb.append("  ").append(String.format("%-12s", e.getKey())).append(e.getValue()).append('\n');
            return sb.toString();
        }
    }

    // ---------------- cKey 实现（TEA / OI-CBC / XOR / 自定义 Base64） ----------------

    private static final int ROUNDS = 16;
    private static final long DELTA = 0x9E3779B9L;
    private static final int SALT_LEN = 2;
    private static final int ZERO_LEN = 7;
    private static final byte[] TEA_CKEY = hex("59b2f7cf725ef43c34fdd7c123411ed3");
    private static final byte[] GUARD_TEA_KEY = hex("110DBEC10C23E7D2E56A1CAD6914EF1B");
    private static final int[] XOR_KEY = {0x84, 0x2E, 0xED, 0x08, 0xF0, 0x66, 0xE6, 0xEA, 0x48, 0xB4, 0xCA, 0xA9, 0x91, 0xED, 0x6F, 0xF3};
    private static final int[] GUARD_XOR_KEY = {0xB3, 0xC9, 0x53, 0xA0, 0x69, 0x13, 0xAD, 0x4D};
    private static final String STANDARD_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=";
    private static final String CUSTOM_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-=";

    private static byte[] hex(String s) {
        int len = s.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static long calcSignature(byte[] data) {
        long signature = 0;
        for (byte b : data) signature = (0x83L * signature + (b & 0xFF)) & 0x7FFFFFFFL;
        return signature;
    }

    /** 自定义字母表 Base64：'+'→'_', '/'→'-'，去掉 '=' */
    private static String customEncode(byte[] data) {
        StringBuilder sb = new StringBuilder(Base64.getEncoder().encodeToString(data));
        for (int i = 0; i < STANDARD_ALPHABET.length(); i++) {
            char from = STANDARD_ALPHABET.charAt(i), to = CUSTOM_ALPHABET.charAt(i);
            for (int idx = 0; idx < sb.length(); idx++) if (sb.charAt(idx) == from) sb.setCharAt(idx, to);
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '=') end--;
        return sb.substring(0, end);
    }

    /** TEA ECB 加密/解密（16 轮），key 16 字节 */
    private static byte[] teaCrypt(byte[] in, byte[] key, boolean encrypt) {
        long y, z;
        if (encrypt) {
            ByteBuffer buf = ByteBuffer.wrap(in.length < 8 ? expand(in) : in);
            y = buf.getInt() & 0xFFFFFFFFL;
            z = buf.getInt() & 0xFFFFFFFFL;
            long sum = 0;
            for (int i = 0; i < ROUNDS; i++) {
                sum = (sum + DELTA) & 0xFFFFFFFFL;
                y = (y + (((z << 4) + k(key, 0)) ^ (z + sum) ^ ((z >> 5) + k(key, 1)))) & 0xFFFFFFFFL;
                z = (z + (((y << 4) + k(key, 2)) ^ (y + sum) ^ ((y >> 5) + k(key, 3)))) & 0xFFFFFFFFL;
            }
        } else {
            ByteBuffer buf = ByteBuffer.wrap(in);
            y = buf.getInt() & 0xFFFFFFFFL;
            z = buf.getInt() & 0xFFFFFFFFL;
            long sum = (DELTA << 4) & 0xFFFFFFFFL;
            for (int i = 0; i < ROUNDS; i++) {
                z = (z - (((y << 4) + k(key, 2)) ^ (y + sum) ^ ((y >> 5) + k(key, 3)))) & 0xFFFFFFFFL;
                y = (y - (((z << 4) + k(key, 0)) ^ (z + sum) ^ ((z >> 5) + k(key, 1)))) & 0xFFFFFFFFL;
                sum = (sum - DELTA) & 0xFFFFFFFFL;
            }
        }
        ByteBuffer out = ByteBuffer.allocate(8);
        out.putInt((int) y);
        out.putInt((int) z);
        return out.array();
    }

    private static byte[] expand(byte[] in) {
        byte[] out = new byte[8];
        System.arraycopy(in, 0, out, 0, in.length);
        return out;
    }

    private static long k(byte[] key, int i) {
        return ((key[i * 4] & 0xFFL) << 24) | ((key[i * 4 + 1] & 0xFFL) << 16) | ((key[i * 4 + 2] & 0xFFL) << 8) | (key[i * 4 + 3] & 0xFFL);
    }

    /** 腾讯 oi_symmetry_encrypt2：首字节低 3 位存 pad 长度，SALT_LEN 随机，ZERO_LEN 零填充 */
    private byte[] oiEncrypt(byte[] in, byte[] key) {
        int padSaltBodyZero = in.length + 1 + SALT_LEN + ZERO_LEN;
        int padlen = padSaltBodyZero % 8;
        if (padlen != 0) padlen = 8 - padlen;

        List<Byte> out = new ArrayList<>();
        int[] src = new int[8];
        src[0] = (random.nextInt(256) & 0xF8) | padlen;
        int srcI = 1;
        int[] ivPlain = new int[8], ivCrypt = new int[8];

        // padding
        while (padlen > 0) {
            src[srcI++] = random.nextInt(256);
            padlen--;
            if (srcI == 8) { int[][] iv = flush(src, ivPlain, ivCrypt, out, key); ivPlain = iv[0]; ivCrypt = iv[1]; src = new int[8]; srcI = 0; }
        }
        // salt
        for (int i = 0; i < SALT_LEN; i++) {
            if (srcI < 8) src[srcI++] = random.nextInt(256);
            if (srcI == 8) { int[][] iv = flush(src, ivPlain, ivCrypt, out, key); ivPlain = iv[0]; ivCrypt = iv[1]; src = new int[8]; srcI = 0; }
        }
        // body
        for (byte b : in) {
            if (srcI < 8) src[srcI++] = b & 0xFF;
            if (srcI == 8) { int[][] iv = flush(src, ivPlain, ivCrypt, out, key); ivPlain = iv[0]; ivCrypt = iv[1]; src = new int[8]; srcI = 0; }
        }
        // zero
        for (int i = 0; i < ZERO_LEN; i++) {
            if (srcI < 8) src[srcI++] = 0;
            if (srcI == 8) { int[][] iv = flush(src, ivPlain, ivCrypt, out, key); ivPlain = iv[0]; ivCrypt = iv[1]; src = new int[8]; srcI = 0; }
        }
        // 尾块
        if (srcI > 0) {
            for (int j = srcI; j < 8; j++) src[j] = 0;
            int[] xored = new int[8];
            for (int j = 0; j < 8; j++) xored[j] = src[j] ^ ivCrypt[j];
            byte[] enc = teaCrypt(toBytes(xored), key, true);
            int[] tmp = toInts(enc);
            for (int j = 0; j < 8; j++) tmp[j] ^= ivPlain[j];
            for (int j = 0; j < 8; j++) out.add((byte) tmp[j]);
        }
        return toBytes(out);
    }

    /** 加密一块：src XOR ivCrypt → TEA 加密 → XOR ivPlain；返回新的 {ivPlain, ivCrypt}（ivPlain 取异或后的输入块） */
    private int[][] flush(int[] src, int[] ivPlain, int[] ivCrypt, List<Byte> out, byte[] key) {
        int[] xored = new int[8];
        for (int j = 0; j < 8; j++) xored[j] = src[j] ^ ivCrypt[j];
        byte[] enc = teaCrypt(toBytes(xored), key, true);
        int[] tmp = toInts(enc);
        for (int j = 0; j < 8; j++) tmp[j] ^= ivPlain[j];
        for (int j = 0; j < 8; j++) out.add((byte) tmp[j]);
        return new int[][]{xored, tmp};
    }

    private static int[] toInts(byte[] bytes) {
        int[] out = new int[bytes.length];
        for (int i = 0; i < bytes.length; i++) out[i] = bytes[i] & 0xFF;
        return out;
    }

    private static byte[] toBytes(List<Byte> list) {
        byte[] out = new byte[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private static byte[] toBytes(int[] ints) {
        byte[] out = new byte[ints.length];
        for (int i = 0; i < out.length; i++) out[i] = (byte) ints[i];
        return out;
    }

    private static byte[] xorArray(byte[] data) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) out[i] = (byte) ((data[i] & 0xFF) ^ XOR_KEY[i & 0xF]);
        return out;
    }

    // ---------------- 数据包构建 ----------------

    private static void putU16(ByteBuffer buf, int v) {
        buf.putShort((short) (v & 0xFFFF));
    }

    private static void putU32(ByteBuffer buf, long v) {
        buf.putInt((int) (v & 0xFFFFFFFFL));
    }

    private static void putStr16(ByteBuffer buf, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        putU16(buf, b.length);
        buf.put(b);
    }

    private byte[] buildPacket(Map<String, Object> p) {
        ByteBuffer buf = ByteBuffer.allocate(1024);
        buf.put(hex("0000004200000004000004d2"));   // 12 字节固定头
        putU32(buf, ((Number) p.get("Platform")).longValue());
        putU32(buf, 0);                              // Signature 占位
        putU32(buf, ((Number) p.get("Timestamp")).longValue());
        putStr16(buf, (String) p.get("Sdtfrom"));
        putStr16(buf, (String) p.get("randFlag"));
        putStr16(buf, (String) p.get("appVer"));
        putStr16(buf, (String) p.get("vid"));
        putStr16(buf, (String) p.get("guid"));
        putU32(buf, 1);                              // part1
        putU32(buf, 1);                              // isDlna
        putStr16(buf, "2622783A");                   // uid
        putStr16(buf, "nil");                        // bundleID
        putStr16(buf, (String) p.get("uuid4"));
        putStr16(buf, "nil");                        // bundleID1
        putStr16(buf, "v0.1.000");                   // ckeyVersion
        putStr16(buf, "com.cctv.yangshipin.app.iphone");
        putStr16(buf, "4330403");                    // platform_str
        putStr16(buf, "ex_json_bus");
        putStr16(buf, "ex_json_vs");
        putStr16(buf, (String) p.get("ck_guard_time"));

        byte[] data = new byte[buf.position()];
        System.arraycopy(buf.array(), 0, data, 0, data.length);

        ByteBuffer packet = ByteBuffer.allocate(data.length + 2);
        putU16(packet, data.length);
        packet.put(data);
        byte[] buffer = packet.array();
        long signature = calcSignature(buffer);
        // Signature 写在偏移 18（2 长度头 + 12 固定头 + 4 Platform）
        buffer[18] = (byte) (signature >>> 24);
        buffer[19] = (byte) (signature >>> 16);
        buffer[20] = (byte) (signature >>> 8);
        buffer[21] = (byte) signature;
        return buffer;
    }

    private String generateCkGuardTime(long timestamp) {
        byte[] body = u32Bytes(timestamp);
        for (String part : new String[]{lastFive(guid), lastFive("null"), lastFive("null"), "-1"}) {
            byte[] pb = part.getBytes(StandardCharsets.UTF_8);
            byte[] merged = new byte[body.length + 2 + pb.length];
            System.arraycopy(body, 0, merged, 0, body.length);
            merged[body.length] = (byte) ((pb.length >>> 8) & 0xFF);
            merged[body.length + 1] = (byte) (pb.length & 0xFF);
            System.arraycopy(pb, 0, merged, body.length + 2, pb.length);
            body = merged;
        }
        byte[] plain = new byte[body.length + 2];
        plain[0] = (byte) ((body.length >>> 8) & 0xFF);
        plain[1] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, plain, 2, body.length);

        byte[] encrypted = oiEncrypt(plain, GUARD_TEA_KEY);
        byte[] withSum = new byte[encrypted.length + 4];
        System.arraycopy(encrypted, 0, withSum, 0, encrypted.length);
        long checksum = calcSignature(plain);
        withSum[encrypted.length] = (byte) (checksum >>> 24);
        withSum[encrypted.length + 1] = (byte) (checksum >>> 16);
        withSum[encrypted.length + 2] = (byte) (checksum >>> 8);
        withSum[encrypted.length + 3] = (byte) checksum;

        byte[] out = new byte[withSum.length];
        for (int i = 0; i < out.length; i++) out[i] = (byte) ((withSum[i] & 0xFF) ^ GUARD_XOR_KEY[i & 7]);
        StringBuilder sb = new StringBuilder();
        for (byte b : out) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private static byte[] u32Bytes(long v) {
        return new byte[]{(byte) (v >>> 24), (byte) (v >>> 16), (byte) (v >>> 8), (byte) v};
    }

    private static String lastFive(String value) {
        return value != null && value.length() >= 5 ? value.substring(value.length() - 5) : "";
    }

    private String generateCKey(String cnlid, long timestamp) {
        if (guid.isEmpty() || guid.length() != 32) generateGuid();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("Platform", 4330403);
        params.put("Timestamp", timestamp);
        params.put("Sdtfrom", "dcgh");
        params.put("vid", cnlid);
        params.put("guid", guid);
        params.put("appVer", "V8.22.1035.3031");
        params.put("randFlag", "_zj1A5Gh6QYcxWjIUGos2w=="); // 与 PHP 版一致（硬编码）
        params.put("uuid4", "57eab0c4-2c58-44c6-8ae9-dd2757525dc5");
        params.put("ck_guard_time", generateCkGuardTime(timestamp));

        byte[] packet = buildPacket(params);
        byte[] encrypted = oiEncrypt(packet, TEA_CKEY);
        byte[] merged = new byte[encrypted.length + 4];
        System.arraycopy(encrypted, 0, merged, 0, encrypted.length);
        long checksum = calcSignature(packet);
        merged[encrypted.length] = (byte) (checksum >>> 24);
        merged[encrypted.length + 1] = (byte) (checksum >>> 16);
        merged[encrypted.length + 2] = (byte) (checksum >>> 8);
        merged[encrypted.length + 3] = (byte) checksum;
        return "--01" + customEncode(xorArray(merged));
    }

    private void generateGuid() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) sb.append(String.format("%02x", random.nextInt(256)));
        guid = sb.toString();
    }

    // ---------------- 上游请求 ----------------

    private String getPlayUrl(String cnlid, String livepid, String defn, String playseek) {
        generateGuid();
        long timestamp = System.currentTimeMillis() / 1000;
        Map<String, String> params = baseParams(cnlid, livepid, defn, timestamp);
        // 与 PHP 一致：直播和回看的多次尝试共用同一个 cKey
        params.put("cKey", generateCKey(cnlid, timestamp));

        if (playseek != null && !playseek.isEmpty()) {
            // 回看：第一次带 playbacktime，失败则去掉并改写域名 + starttime
            Long playbackTimestamp = parsePlaybackTimestamp(playseek);
            if (playbackTimestamp == null) return null;
            params.put("playbacktime", String.valueOf(playbackTimestamp));
            String url = extractPlayUrl(httpGetApi(params));
            if (url != null) return url;
            params.remove("playbacktime");
            url = extractPlayUrl(httpGetApi(params));
            return url == null ? null : processPlaybackUrl(url, playbackTimestamp);
        }

        params.put("playbacktime", "0");
        return extractPlayUrl(httpGetApi(params));
    }

    /** debug 模式：返回上游原始 JSON */
    private String upstreamRaw(String cnlid, String livepid, String defn, String playseek) {
        generateGuid();
        long timestamp = System.currentTimeMillis() / 1000;
        Map<String, String> params = baseParams(cnlid, livepid, defn, timestamp);
        params.put("cKey", generateCKey(cnlid, timestamp));
        if (playseek != null && !playseek.isEmpty()) {
            Long ts = parsePlaybackTimestamp(playseek);
            if (ts != null) params.put("playbacktime", String.valueOf(ts));
        } else {
            params.put("playbacktime", "0");
        }
        return httpGetApi(params);
    }

    private String httpGetApi(Map<String, String> params) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", UA);
        headers.put("Accept", "application/json");
        return OkHttp.string(API + "?" + buildQuery(params), headers);
    }

    private static String buildQuery(Map<String, String> params) {
        StringBuilder qs = new StringBuilder();
        try {
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (qs.length() > 0) qs.append('&');
                qs.append(java.net.URLEncoder.encode(e.getKey(), "UTF-8")).append('=').append(java.net.URLEncoder.encode(e.getValue(), "UTF-8"));
            }
        } catch (Exception ignored) {
        }
        return qs.toString();
    }

    private static Long parsePlaybackTimestamp(String playseek) {
        try {
            String start = playseek.split("-")[0];
            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(start,
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            return dt.toEpochSecond(java.time.ZoneOffset.ofHours(8));
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractPlayUrl(String json) {
        if (json == null || json.isEmpty() || !json.contains("\"playurl\"")) return null;
        Matcher m = Pattern.compile("\"playurl\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        return m.find() ? m.group(1).replace("\\/", "/") : null;
    }

    private static String processPlaybackUrl(String playurl, long playbackTimestamp) {
        String[] parts = playurl.split("/");
        if (parts.length >= 3) {
            parts[2] = "tlivecloud-playback-cdn.ysp.cctv.cn/tcloud.cctv.com";
            playurl = String.join("/", parts);
            playurl += (playurl.contains("?") ? "&" : "?") + "starttime=" + playbackTimestamp;
        }
        return playurl;
    }

    private Map<String, String> baseParams(String cnlid, String livepid, String defn, long timestamp) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("atime", "120");
        params.put("livepid", livepid);
        params.put("cnlid", cnlid);
        params.put("appVer", "V8.22.1035.3031");
        params.put("app_version", "300090");
        params.put("caplv", "1");
        params.put("cmd", "2");
        params.put("defn", defn);
        params.put("device", "iPhone");
        params.put("encryptVer", "4.2");
        params.put("getpreviewinfo", "0");
        params.put("hevclv", "33");
        params.put("lang", "zh-Hans_JP");
        params.put("livequeue", "0");
        params.put("logintype", "1");
        params.put("nettype", "1");
        params.put("newnettype", "1");
        params.put("newplatform", "4330403");
        params.put("platform", "4330403");
        params.put("sdtfrom", "v3021");
        params.put("spacode", "23");
        params.put("spaudio", "1");
        params.put("spdemuxer", "6");
        params.put("spdrm", "2");
        params.put("spdynamicrange", "7");
        params.put("spflv", "1");
        params.put("spflvaudio", "1");
        params.put("sphdrfps", "60");
        params.put("sphttps", "0");
        params.put("spvcode", "MSgzMDoyMTYwLDYwOjIxNjB8MzA6MjE2MCw2MDoyMTYwKTsyKDMwOjIxNjAsNjA6MjE2MHwzMDoyMTYwLDYwOjIxNjAp");
        params.put("spvideo", "4");
        params.put("stream", "1");
        params.put("system", "1");
        params.put("sysver", "ios18.2.1");
        params.put("uhd_flag", "4");
        params.put("guid", guid);
        params.put("fntick", String.valueOf(timestamp));
        params.put("flowid", uuid(true) + "_4330403");
        return params;
    }

    private String uuid(boolean upper) {
        String raw = String.format("%04x%04x-%04x-%04x-%04x-%04x%04x%04x",
                random.nextInt(0x10000), random.nextInt(0x10000), random.nextInt(0x10000),
                (random.nextInt(0x1000) | 0x4000), (random.nextInt(0x4000) | 0x8000),
                random.nextInt(0x10000), random.nextInt(0x10000), random.nextInt(0x10000));
        return upper ? raw.toUpperCase() : raw;
    }

    // ---------------- m3u8 处理 ----------------

    private String fetchM3u8(String url) {
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", UA);
            headers.put("Accept", "*/*");
            String body = OkHttp.string(url, headers);
            return body != null && body.contains("#EXTM3U") ? body : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** 补全 TS 相对路径（对应 PHP 的 preg_replace("/(.*?.ts)/i", $baseUrl."$1", ...)） */
    private static String patchTs(String m3u8, String playurl) {
        String baseUrl = playurl.substring(0, playurl.lastIndexOf('/') + 1);
        Matcher m = TS_PATTERN.matcher(m3u8);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(baseUrl + m.group(1)));
        m.appendTail(sb);
        return sb.toString();
    }
}

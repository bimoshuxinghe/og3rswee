package com.fongmi.android.tv.player;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * ISO 镜像文件系统解析器。
 * <p>
 * 通过 HTTP Range 请求读取 ISO 镜像中的 UDF 或 ISO 9660 文件系统，
 * 定位其中的主要视频文件（蓝光 M2TS、DVD VOB 或其它视频格式），
 * 返回该文件在 ISO 中的字节偏移量和大小，从而支持通过 subfile
 * 或代理方式直接播放 ISO 内部的视频流，而无需下载整个镜像。
 */
public final class IsoParser {

    private static final String TAG = "IsoParser";
    private static final int SECTOR_SIZE = 2048;

    /* UDF Descriptor Tag IDs */
    private static final int TAG_ANCHOR = 0x0002;
    private static final int TAG_PARTITION = 0x0004;
    private static final int TAG_LOGICAL_VOL = 0x0005;
    private static final int TAG_TERMINATING = 0x0007;
    private static final int TAG_FILE_SET = 0x0100;
    private static final int TAG_FILE_ID = 0x0101;
    private static final int TAG_ALLOC_EXTENT = 0x0102;
    private static final int TAG_FILE_ENTRY = 0x0105;
    private static final int TAG_EXT_FILE_ENTRY = 0x010A;

    /* ICB allocation descriptor types */
    private static final int AD_SHORT = 0;
    private static final int AD_LONG = 1;

    private static final int MAX_DEPTH = 6;

    private static final String[] VIDEO_EXT = {
            ".m2ts", ".vob", ".mp4", ".mkv", ".avi", ".mov", ".ts",
            ".flv", ".webm", ".wmv", ".mpg", ".mpeg", ".m4v", ".rmvb"
    };

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build();

    public static class VideoFile {
        public final long offset;
        public final long size;
        public final String name;
        public final String format;

        VideoFile(long offset, long size, String name, String format) {
            this.offset = offset;
            this.size = size;
            this.name = name;
            this.format = format;
        }

        @NonNull
        @Override
        public String toString() {
            return String.format(Locale.ROOT, "VideoFile{name='%s', offset=%d, size=%d, format='%s'}", name, offset, size, format);
        }
    }

    private static class DirEntry {
        final String name;
        final boolean directory;
        final long icbBlock;
        final int icbPartRef;

        DirEntry(String name, boolean directory, long icbBlock, int icbPartRef) {
            this.name = name;
            this.directory = directory;
            this.icbBlock = icbBlock;
            this.icbPartRef = icbPartRef;
        }
    }

    private static class FileEntryInfo {
        final long infoLength;
        final int adType;
        final byte[] adData;
        final int adOffset;
        final int adLength;

        FileEntryInfo(long infoLength, int adType, byte[] sectorData, int adOffset, int adLength) {
            this.infoLength = infoLength;
            this.adType = adType;
            this.adData = sectorData;
            this.adOffset = adOffset;
            this.adLength = adLength;
        }
    }

    private final String url;
    private final Map<String, String> headers;
    private final boolean isLocal;
    private RandomAccessFile raf;
    private long partitionStart;

    /**
     * 判断 URL 是否指向本地文件（file:// 协议或无 scheme 的路径）。
     */
    public static boolean isLocalFile(@NonNull String url) {
        if (TextUtils.isEmpty(url)) return false;
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        return scheme == null || "file".equalsIgnoreCase(scheme);
    }

    /**
     * 从 URL 中提取本地文件路径。
     */
    @NonNull
    public static String getLocalPath(@NonNull String url) {
        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if (scheme == null) return url;
        if ("file".equalsIgnoreCase(scheme)) return uri.getPath();
        return url;
    }

    @Nullable
    public static VideoFile findVideoFile(@NonNull String url, @Nullable Map<String, String> headers) {
        IsoParser parser = new IsoParser(url, headers);
        try {
            return parser.parse();
        } catch (Exception e) {
            Log.e(TAG, "Parse failed: " + e.getMessage(), e);
            return null;
        } finally {
            parser.close();
        }
    }

    private IsoParser(String url, Map<String, String> headers) {
        this.url = url;
        this.headers = headers;
        this.isLocal = isLocalFile(url);
    }

    private void close() {
        if (raf != null) {
            try { raf.close(); } catch (Exception ignored) {}
            raf = null;
        }
    }

    @Nullable
    private VideoFile parse() throws Exception {
        try {
            return parseUdf();
        } catch (Exception e) {
            Log.w(TAG, "UDF parse failed, trying ISO 9660: " + e.getMessage());
        }
        return parseIso9660();
    }

    // ======================== UDF ========================

    @Nullable
    private VideoFile parseUdf() throws Exception {
        byte[] avdp = readSector(256);
        if (getTagId(avdp) != TAG_ANCHOR) throw new Exception("AVDP not found at sector 256");
        int mvdsLen = readLe32(avdp, 16);
        int mvdsLoc = readLe32(avdp, 20);
        long fsdBlock = parseMvds(mvdsLoc, mvdsLen);
        long rootBlock = parseFsd(fsdBlock);
        return findVideoInUdfDir(rootBlock, "", 0);
    }

    private long parseMvds(int mvdsLoc, int mvdsLen) throws Exception {
        int count = (mvdsLen + SECTOR_SIZE - 1) / SECTOR_SIZE;
        long fsdBlock = -1;
        for (int i = 0; i < count; i++) {
            byte[] sec = readSector(mvdsLoc + i);
            int tag = getTagId(sec);
            if (tag == TAG_PARTITION) {
                partitionStart = readLe32(sec, 188);
                Log.d(TAG, "Partition start sector: " + partitionStart);
            } else if (tag == TAG_LOGICAL_VOL) {
                fsdBlock = readLe32(sec, 220);
                Log.d(TAG, "FSD block: " + fsdBlock);
            } else if (tag == TAG_TERMINATING) {
                break;
            }
        }
        if (partitionStart < 0 || fsdBlock < 0) throw new Exception("Partition or FSD not found");
        return fsdBlock;
    }

    private long parseFsd(long fsdBlock) throws Exception {
        byte[] sec = readSector(partitionStart + fsdBlock);
        if (getTagId(sec) != TAG_FILE_SET) throw new Exception("FSD tag mismatch: " + getTagId(sec));
        long rootIcbBlock = readLe32(sec, 168);
        Log.d(TAG, "Root dir ICB block: " + rootIcbBlock);
        return rootIcbBlock;
    }

    @Nullable
    private VideoFile findVideoInUdfDir(long dirBlock, String path, int depth) throws Exception {
        if (depth > MAX_DEPTH) return null;
        List<DirEntry> entries = readUdfDirectory(dirBlock);
        VideoFile best = null;

        // 优先查找 BDMV/STREAM 目录
        if (depth == 0) {
            for (DirEntry e : entries) {
                if (e.directory && "BDMV".equalsIgnoreCase(e.name)) {
                    for (DirEntry e2 : readUdfDirectory(e.icbBlock)) {
                        if (e2.directory && "STREAM".equalsIgnoreCase(e2.name)) {
                            Log.d(TAG, "Found BDMV/STREAM directory");
                            VideoFile vf = findLargestVideoInUdfDir(e2.icbBlock, ".m2ts");
                            if (vf != null) return vf;
                        }
                    }
                }
            }
            for (DirEntry e : entries) {
                if (e.directory && "VIDEO_TS".equalsIgnoreCase(e.name)) {
                    Log.d(TAG, "Found VIDEO_TS directory");
                    VideoFile vf = findLargestVideoInUdfDir(e.icbBlock, ".vob");
                    if (vf != null) return vf;
                }
            }
        }

        // 递归搜索
        for (DirEntry e : entries) {
            if (!e.directory || ".".equals(e.name) || "..".equals(e.name)) continue;
            String childPath = path.isEmpty() ? e.name : path + "/" + e.name;
            VideoFile vf = findVideoInUdfDir(e.icbBlock, childPath, depth + 1);
            if (vf != null && (best == null || vf.size > best.size)) best = vf;
        }
        return best;
    }

    @Nullable
    private VideoFile findLargestVideoInUdfDir(long dirBlock, String ext) throws Exception {
        List<DirEntry> entries = readUdfDirectory(dirBlock);
        List<VideoFile> videos = collectUdfVideos(entries, ext);
        return pickBestVideo(videos);
    }

    @NonNull
    private List<VideoFile> collectUdfVideos(List<DirEntry> entries, String ext) throws Exception {
        List<VideoFile> videos = new ArrayList<>();
        for (DirEntry e : entries) {
            if (e.directory || TextUtils.isEmpty(e.name)) continue;
            String lower = e.name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(ext) && !hasVideoExt(lower)) continue;
            FileEntryInfo fei = readUdfFileEntry(e.icbBlock);
            if (fei == null || fei.infoLength <= 0) continue;
            long absOffset = (partitionStart + getExtentBlock(fei)) * SECTOR_SIZE;
            String fmt = getFormat(lower);
            Log.d(TAG, "Found video: " + e.name + " offset=" + absOffset + " size=" + fei.infoLength);
            videos.add(new VideoFile(absOffset, fei.infoLength, e.name, fmt));
        }
        return videos;
    }

    /**
     * 从视频文件列表中选择最合适的一个。
     * <p>
     * 策略：
     * 1. 过滤掉过小的文件（<10MB，通常是菜单或 extras）
     * 2. 如果存在一个明显大于其他文件的文件（>2倍第二大），返回它（主feature）
     * 3. 如果多个文件大小相近，按文件名排序后返回第一个（分集场景下选择第一集）
     */
    @Nullable
    private static VideoFile pickBestVideo(List<VideoFile> videos) {
        if (videos == null || videos.isEmpty()) return null;
        if (videos.size() == 1) return videos.get(0);

        List<VideoFile> sorted = new ArrayList<>(videos);
        sorted.sort((a, b) -> a.name.compareToIgnoreCase(b.name));

        // 过滤掉过小的文件（<10MB）
        List<VideoFile> candidates = new ArrayList<>();
        for (VideoFile v : sorted) {
            if (v.size > 10L * 1024 * 1024) candidates.add(v);
        }
        if (candidates.isEmpty()) candidates = sorted;

        // 找到最大的文件
        VideoFile largest = candidates.get(0);
        for (VideoFile v : candidates) {
            if (v.size > largest.size) largest = v;
        }

        // 找到第二大文件
        long secondLargest = 0;
        for (VideoFile v : candidates) {
            if (v != largest && v.size > secondLargest) secondLargest = v.size;
        }

        // 如果最大文件远大于第二大文件（>2倍），说明是主feature，直接返回
        if (secondLargest > 0 && largest.size > 2 * secondLargest) {
            Log.d(TAG, "pickBestVideo: dominant file " + largest.name + " (" + largest.size + " bytes)");
            return largest;
        }

        // 多个文件大小相近，可能是分集，返回按名称排序的第一个
        Log.d(TAG, "pickBestVideo: similar-sized files, picking first: " + candidates.get(0).name);
        return candidates.get(0);
    }

    @NonNull
    private List<DirEntry> readUdfDirectory(long dirBlock) throws Exception {
        FileEntryInfo fei = readUdfFileEntry(dirBlock);
        if (fei == null) throw new Exception("Cannot read dir File Entry at block " + dirBlock);
        byte[] dirData = readExtent(fei);
        return parseFileIdentifiers(dirData);
    }

    @Nullable
    private FileEntryInfo readUdfFileEntry(long block) throws Exception {
        byte[] sec = readSector(partitionStart + block);
        int tag = getTagId(sec);
        if (tag != TAG_FILE_ENTRY && tag != TAG_EXT_FILE_ENTRY) return null;
        int fileType = sec[27] & 0xFF;
        int flags = readLe16(sec, 34);
        int adType = flags & 0x07;
        long infoLength = readLe64(sec, 56);
        int eaLen = readLe32(sec, 192);
        int adLen = readLe32(sec, 196);
        int adOffset = 200 + eaLen;
        if (adOffset + adLen > sec.length) adLen = sec.length - adOffset;
        return new FileEntryInfo(infoLength, adType, sec, adOffset, adLen);
    }

    @NonNull
    private List<DirEntry> parseFileIdentifiers(byte[] data) {
        List<DirEntry> entries = new ArrayList<>();
        int pos = 0;
        while (pos + 38 <= data.length) {
            int tag = readLe16(data, pos);
            if (tag != TAG_FILE_ID) {
                // 可能是 padding 或下一个 tag，按 4 字节对齐跳过
                pos += 4;
                continue;
            }
            int recLen = readLe16(data, pos + 8); // descCRCLength 不是 record length
            // File Identifier Descriptor 布局:
            // 0-15: tag(16), 16-17: fileVersion(2), 18: fileChar(1), 19: L_FI(1)
            // 20-35: ICB long_ad(16), 36-37: L_IU(2), 38+L_IU: fileIdent
            int fileChar = data[pos + 18] & 0xFF;
            int lFi = data[pos + 19] & 0xFF;
            long icbBlock = readLe32(data, pos + 24);
            int icbPartRef = readLe16(data, pos + 28);
            int lIu = readLe16(data, pos + 36);
            int nameOffset = pos + 38 + lIu;
            boolean isDir = (fileChar & 0x02) != 0;
            boolean isParent = (fileChar & 0x08) != 0;
            String name = "";
            if (!isParent && lFi > 0 && nameOffset + lFi <= data.length) {
                name = decodeDString(data, nameOffset, lFi);
            }
            if (!isParent && !name.isEmpty()) {
                entries.add(new DirEntry(name, isDir, icbBlock, icbPartRef));
            }
            // 计算下一条记录位置（4 字节对齐）
            int recSize = 38 + lIu + lFi;
            recSize = (recSize + 3) & ~3;
            if (recSize <= 0) recSize = 4;
            pos += recSize;
        }
        return entries;
    }

    @NonNull
    private byte[] readExtent(FileEntryInfo fei) throws Exception {
        if (fei.adType == AD_SHORT) {
            return readShortAdExtent(fei);
        } else if (fei.adType == AD_LONG) {
            return readLongAdExtent(fei);
        } else {
            // inline data
            int len = Math.min((int) fei.infoLength, fei.adLength);
            byte[] data = new byte[len];
            System.arraycopy(fei.adData, fei.adOffset, data, 0, len);
            return data;
        }
    }

    @NonNull
    private byte[] readShortAdExtent(FileEntryInfo fei) throws Exception {
        int pos = fei.adOffset;
        int remaining = fei.adLength;
        byte[] result = new byte[(int) Math.min(fei.infoLength, Integer.MAX_VALUE)];
        int writePos = 0;
        while (remaining >= 8 && writePos < result.length) {
            long len = readLe32(fei.adData, pos) & 0x3FFFFFFFL;
            long loc = readLe32(fei.adData, pos + 4);
            pos += 8;
            remaining -= 8;
            if (len <= 0) continue;
            int toRead = (int) Math.min(len * SECTOR_SIZE, result.length - writePos);
            byte[] data = readRange((partitionStart + loc) * SECTOR_SIZE, toRead);
            System.arraycopy(data, 0, result, writePos, Math.min(data.length, toRead));
            writePos += Math.min(data.length, toRead);
        }
        return result;
    }

    @NonNull
    private byte[] readLongAdExtent(FileEntryInfo fei) throws Exception {
        int pos = fei.adOffset;
        int remaining = fei.adLength;
        byte[] result = new byte[(int) Math.min(fei.infoLength, Integer.MAX_VALUE)];
        int writePos = 0;
        while (remaining >= 16 && writePos < result.length) {
            long len = readLe32(fei.adData, pos) & 0x3FFFFFFFL;
            long loc = readLe32(fei.adData, pos + 4);
            pos += 16;
            remaining -= 16;
            if (len <= 0) continue;
            int toRead = (int) Math.min(len * SECTOR_SIZE, result.length - writePos);
            byte[] data = readRange((partitionStart + loc) * SECTOR_SIZE, toRead);
            System.arraycopy(data, 0, result, writePos, Math.min(data.length, toRead));
            writePos += Math.min(data.length, toRead);
        }
        return result;
    }

    private long getExtentBlock(FileEntryInfo fei) {
        if (fei.adType == AD_SHORT && fei.adLength >= 8) {
            return readLe32(fei.adData, fei.adOffset + 4);
        } else if (fei.adType == AD_LONG && fei.adLength >= 16) {
            return readLe32(fei.adData, fei.adOffset + 4);
        }
        return 0;
    }

    // ======================== ISO 9660 ========================

    @Nullable
    private VideoFile parseIso9660() throws Exception {
        byte[] pvd = readSector(16);
        // 验证 PVD 签名: offset 1 = "CD001", offset 0 = 0x01
        if (pvd[0] != 0x01 || pvd[1] != 'C' || pvd[2] != 'D' || pvd[3] != '0' || pvd[4] != '0' || pvd[5] != '1') {
            throw new Exception("Not ISO 9660");
        }
        // 根目录记录在 PVD offset 156, 长度 34 字节
        int rootRecLen = pvd[156] & 0xFF;
        if (rootRecLen < 33) throw new Exception("Invalid root dir record");
        long rootLoc = readLe32(pvd, 158);
        long rootSize = readLe32(pvd, 166);
        Log.d(TAG, "ISO 9660 root dir: loc=" + rootLoc + " size=" + rootSize);
        byte[] rootDir = readRange(rootLoc * SECTOR_SIZE, (int) Math.min(rootSize, 65536));
        return findVideoInIsoDir(rootDir, "", 0);
    }

    @Nullable
    private VideoFile findVideoInIsoDir(byte[] dirData, String path, int depth) throws Exception {
        if (depth > MAX_DEPTH) return null;
        List<Iso9660Entry> entries = parseIso9660Dir(dirData);
        VideoFile best = null;

        if (depth == 0) {
            for (Iso9660Entry e : entries) {
                if (e.directory && "VIDEO_TS".equalsIgnoreCase(e.name)) {
                    byte[] subDir = readRange(e.location * SECTOR_SIZE, (int) Math.min(e.size, 65536));
                    VideoFile vf = findLargestVideoInIsoDir(subDir, ".VOB");
                    if (vf != null) return vf;
                }
            }
        }

        for (Iso9660Entry e : entries) {
            if (!e.directory) continue;
            if ("\0".equals(e.name) || "\1".equals(e.name)) continue;
            byte[] subDir = readRange(e.location * SECTOR_SIZE, (int) Math.min(e.size, 65536));
            VideoFile vf = findVideoInIsoDir(subDir, path + "/" + e.name, depth + 1);
            if (vf != null && (best == null || vf.size > best.size)) best = vf;
        }

        for (Iso9660Entry e : entries) {
            if (e.directory || TextUtils.isEmpty(e.name)) continue;
            String lower = e.name.toLowerCase(Locale.ROOT);
            if (!hasVideoExt(lower)) continue;
            String fmt = getFormat(lower);
            Log.d(TAG, "Found ISO9660 video: " + e.name + " offset=" + (e.location * SECTOR_SIZE) + " size=" + e.size);
            if (best == null || e.size > best.size) {
                best = new VideoFile(e.location * SECTOR_SIZE, e.size, e.name, fmt);
            }
        }
        return best;
    }

    @Nullable
    private VideoFile findLargestVideoInIsoDir(byte[] dirData, String ext) throws Exception {
        List<Iso9660Entry> entries = parseIso9660Dir(dirData);
        List<VideoFile> videos = new ArrayList<>();
        for (Iso9660Entry e : entries) {
            if (e.directory || TextUtils.isEmpty(e.name)) continue;
            String lower = e.name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(ext.toLowerCase(Locale.ROOT)) && !hasVideoExt(lower)) continue;
            String fmt = getFormat(lower);
            long offset = e.location * SECTOR_SIZE;
            Log.d(TAG, "Found ISO9660 video: " + e.name + " offset=" + offset + " size=" + e.size);
            videos.add(new VideoFile(offset, e.size, e.name, fmt));
        }
        return pickBestVideo(videos);
    }

    private static class Iso9660Entry {
        final String name;
        final boolean directory;
        final long location;
        final long size;

        Iso9660Entry(String name, boolean directory, long location, long size) {
            this.name = name;
            this.directory = directory;
            this.location = location;
            this.size = size;
        }
    }

    @NonNull
    private static List<Iso9660Entry> parseIso9660Dir(byte[] data) {
        List<Iso9660Entry> entries = new ArrayList<>();
        int pos = 0;
        while (pos < data.length) {
            int recLen = data[pos] & 0xFF;
            if (recLen == 0) {
                pos = (pos / SECTOR_SIZE + 1) * SECTOR_SIZE;
                if (pos >= data.length) break;
                continue;
            }
            if (pos + recLen > data.length) break;
            long loc = readLe32(data, pos + 2);
            long size = readLe32(data, pos + 10);
            int fileFlags = data[pos + 25] & 0xFF;
            int lFi = data[pos + 32] & 0xFF;
            boolean isDir = (fileFlags & 0x02) != 0;
            String name = "";
            if (lFi > 0 && pos + 33 + lFi <= data.length) {
                byte[] nameBytes = new byte[lFi];
                System.arraycopy(data, pos + 33, nameBytes, 0, lFi);
                name = new String(nameBytes, StandardCharsets.US_ASCII).trim();
                int sep = name.indexOf(';');
                if (sep >= 0) name = name.substring(0, sep);
                if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
            }
            if (!name.isEmpty() && !"\0".equals(name) && !"\1".equals(name)) {
                entries.add(new Iso9660Entry(name, isDir, loc, size));
            }
            pos += recLen;
        }
        return entries;
    }

    // ======================== Utility ========================

    @NonNull
    private byte[] readSector(long sector) throws Exception {
        return readRange(sector * SECTOR_SIZE, SECTOR_SIZE);
    }

    @NonNull
    private byte[] readRange(long offset, int length) throws Exception {
        if (isLocal) return readFileRange(offset, length);
        return readHttpRange(offset, length);
    }

    @NonNull
    private byte[] readFileRange(long offset, int length) throws Exception {
        if (raf == null) raf = new RandomAccessFile(getLocalPath(url), "r");
        raf.seek(offset);
        byte[] data = new byte[length];
        int read = raf.read(data);
        if (read < 0) return new byte[length];
        if (read < length) {
            byte[] padded = new byte[length];
            System.arraycopy(data, 0, padded, 0, read);
            return padded;
        }
        return data;
    }

    @NonNull
    private byte[] readHttpRange(long offset, int length) throws Exception {
        Request.Builder rb = new Request.Builder().url(url)
                .header("Range", "bytes=" + offset + "-" + (offset + length - 1));
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String k = entry.getKey();
                String v = entry.getValue();
                if (TextUtils.isEmpty(k) || TextUtils.isEmpty(v)) continue;
                if ("range".equalsIgnoreCase(k) || "host".equalsIgnoreCase(k)) continue;
                rb.header(k, v);
            }
        }
        try (Response resp = HTTP.newCall(rb.build()).execute()) {
            int code = resp.code();
            if (code != 206 && code != 200) {
                throw new Exception("HTTP " + code + " for range " + offset + "-" + (offset + length - 1));
            }
            byte[] body = resp.body() != null ? resp.body().bytes() : new byte[0];
            if (body.length >= length) return body;
            byte[] padded = new byte[length];
            System.arraycopy(body, 0, padded, 0, body.length);
            return padded;
        }
    }

    private static int getTagId(byte[] data) {
        return readLe16(data, 0);
    }

    private static int readLe16(byte[] d, int o) {
        if (o + 1 >= d.length) return 0;
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8);
    }

    private static int readLe32(byte[] d, int o) {
        if (o + 3 >= d.length) return 0;
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    private static long readLe64(byte[] d, int o) {
        if (o + 7 >= d.length) return 0;
        return (readLe32(d, o) & 0xFFFFFFFFL) | ((long) readLe32(d, o + 4) << 32);
    }

    @NonNull
    private static String decodeDString(byte[] data, int offset, int length) {
        if (length <= 1) return "";
        int compId = data[offset] & 0xFF;
        int actualLen = length - 1;
        if (compId == 8) {
            // UTF-16BE
            int charLen = actualLen / 2;
            char[] chars = new char[charLen];
            for (int i = 0; i < charLen; i++) {
                chars[i] = (char) ((data[offset + 1 + i * 2] << 8) | (data[offset + 1 + i * 2 + 1] & 0xFF));
            }
            return new String(chars);
        } else if (compId == 16) {
            // UTF-8
            return new String(data, offset + 1, actualLen, StandardCharsets.UTF_8);
        } else {
            return new String(data, offset + 1, actualLen, StandardCharsets.US_ASCII);
        }
    }

    private static boolean hasVideoExt(String name) {
        for (String ext : VIDEO_EXT) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    @NonNull
    private static String getFormat(String name) {
        int dot = name.lastIndexOf('.');
        if (dot >= 0 && dot < name.length() - 1) {
            return name.substring(dot + 1).toLowerCase(Locale.ROOT);
        }
        return "";
    }
}

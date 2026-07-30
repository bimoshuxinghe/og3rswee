package com.github.catvod.spider;

import android.content.Context;
import com.github.catvod.crawler.Spider;
import com.github.catvod.utils.LocalScraper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

public class Local extends Spider {

    private File root;

    private static final List<String> MEDIA_EXT = Arrays.asList(
            "mp4", "mkv", "avi", "flv", "mov", "webm", "ts", "m3u8", "3gp", "wmv",
            "mp3", "flac", "wav", "ogg", "m4a", "aac", "ape", "wma",
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg",
            "srt", "ass", "ssa", "vtt"
    );

    private boolean isMediaFile(File file) {
        if (file.isDirectory()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot == -1) return false;
        String ext = name.substring(dot + 1);
        return MEDIA_EXT.contains(ext);
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        super.init(context, extend);
        // Strip the ?style= query param from the path if present
        String path = extend;
        int queryIdx = path.indexOf('?');
        if (queryIdx != -1) path = path.substring(0, queryIdx);
        if (path.startsWith("file:///")) {
            path = path.substring(8);
        } else if (path.startsWith("file:/")) {
            path = path.substring(6);
        }
        this.root = new File(path);
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        File parent = tid.equals("root") || tid.isEmpty() ? root : new File(tid);
        List<File> files = new ArrayList<>();
        File[] list = parent.listFiles();

        // Collect all file names for poster matching
        List<String> allNames = new ArrayList<>();
        if (list != null) {
            for (File f : list) allNames.add(f.getName());
        }

        // Find the directory-level default poster (folder.jpg / poster.jpg)
        String defaultPoster = LocalScraper.getDefaultPoster(allNames);

        if (list != null) {
            for (File file : list) {
                if (file.isDirectory() && !file.getName().startsWith(".")) {
                    files.add(file);
                } else if (isMediaFile(file)) {
                    files.add(file);
                }
            }
            files.sort((o1, o2) -> {
                if (o1.isDirectory() && o2.isFile()) return -1;
                if (o1.isFile() && o2.isDirectory()) return 1;
                return o1.getName().compareToIgnoreCase(o2.getName());
            });
        }

        JSONArray listArr = new JSONArray();
        for (File file : files) {
            JSONObject vod = new JSONObject();
            vod.put("vod_id", file.getAbsolutePath());
            vod.put("vod_name", file.getName());
            if (file.isDirectory()) {
                // Try to find a companion poster for this folder
                String poster = LocalScraper.getCompanionPoster(allNames, file.getName());
                if (poster == null) {
                    // Look for a poster.jpg inside the subfolder itself
                    File[] subFiles = file.listFiles();
                    if (subFiles != null) {
                        List<String> subNames = new ArrayList<>();
                        for (File sf : subFiles) subNames.add(sf.getName());
                        String innerPoster = LocalScraper.getDefaultPoster(subNames);
                        if (innerPoster != null) {
                            poster = "file://" + file.getAbsolutePath() + "/" + innerPoster;
                        }
                    }
                } else {
                    poster = "file://" + parent.getAbsolutePath() + "/" + poster;
                }
                vod.put("vod_pic", poster != null ? poster : "push_folder");
                vod.put("vod_tag", "folder");
                vod.put("vod_remarks", "文件夹");
            } else {
                String itemBase = LocalScraper.stripExtension(file.getName());
                String poster = LocalScraper.getCompanionPoster(allNames, itemBase);
                if (poster == null && defaultPoster != null) {
                    poster = defaultPoster;
                }
                vod.put("vod_pic", poster != null
                        ? "file://" + parent.getAbsolutePath() + "/" + poster
                        : "push_video");
                vod.put("vod_remarks", formatSize(file.length()));
            }
            listArr.put(vod);
        }

        JSONObject result = new JSONObject();
        result.put("page", 1);
        result.put("pagecount", 1);
        result.put("limit", listArr.length());
        result.put("total", listArr.length());
        result.put("list", listArr);
        return result.toString();
    }

    private boolean isPlayableFile(File file) {
        if (!isMediaFile(file)) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot == -1) return false;
        String ext = name.substring(dot + 1);
        // 排除字幕和图片文件
        return !ext.equals("srt") && !ext.equals("ass") && !ext.equals("ssa") && !ext.equals("vtt")
                && !ext.equals("jpg") && !ext.equals("jpeg") && !ext.equals("png") 
                && !ext.equals("gif") && !ext.equals("webp") && !ext.equals("bmp") && !ext.equals("svg");
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String path = ids.get(0);
        File file = new File(path);

        JSONObject vod = new JSONObject();
        vod.put("vod_id", file.getAbsolutePath());
        vod.put("vod_name", file.getName());
        vod.put("vod_play_from", "本地");

        // 如果是图片文件，直接显示图片本身
        if (isImageFile(file)) {
            vod.put("vod_play_url", file.getName() + "$" + "file://" + file.getAbsolutePath());
            vod.put("vod_pic", "file://" + file.getAbsolutePath());
            JSONObject result = new JSONObject();
            result.put("list", new JSONArray(Collections.singletonList(vod)));
            return result.toString();
        }

        // Try to find companion poster and list playable sibling files
        File parent = file.getParentFile();
        if (parent != null) {
            File[] siblings = parent.listFiles();
            if (siblings != null) {
                List<String> siblingNames = new ArrayList<>();
                List<File> playables = new ArrayList<>();
                for (File s : siblings) {
                    siblingNames.add(s.getName());
                    if (isPlayableFile(s)) {
                        playables.add(s);
                    }
                }
                playables.sort((o1, o2) -> o1.getName().compareToIgnoreCase(o2.getName()));

                if (playables.isEmpty()) {
                    vod.put("vod_play_url", file.getName() + "$" + "file://" + file.getAbsolutePath());
                } else {
                    StringBuilder playUrl = new StringBuilder();
                    for (int i = 0; i < playables.size(); i++) {
                        File f = playables.get(i);
                        if (i > 0) playUrl.append("#");
                        playUrl.append(f.getName()).append("$").append("file://").append(f.getAbsolutePath());
                    }
                    vod.put("vod_play_url", playUrl.toString());
                }

                String itemBase = LocalScraper.stripExtension(file.getName());
                String poster = LocalScraper.getCompanionPoster(siblingNames, itemBase);
                if (poster == null) poster = LocalScraper.getDefaultPoster(siblingNames);
                vod.put("vod_pic", poster != null
                        ? "file://" + parent.getAbsolutePath() + "/" + poster
                        : "push_video");

                // Try to find and parse NFO file
                String nfoName = itemBase + ".nfo";
                File nfoFile = new File(parent, nfoName);
                if (!nfoFile.exists()) nfoFile = new File(parent, "movie.nfo");
                if (!nfoFile.exists()) nfoFile = new File(parent, "tvshow.nfo");
                if (nfoFile.exists() && nfoFile.isFile()) {
                    try {
                        byte[] bytes = readBytes(nfoFile);
                        String xml = new String(bytes, StandardCharsets.UTF_8);
                        LocalScraper.parseNfo(xml, vod);
                    } catch (Exception ignored) {}
                }
            } else {
                vod.put("vod_play_url", file.getName() + "$" + "file://" + file.getAbsolutePath());
                vod.put("vod_pic", "push_video");
            }
        } else {
            vod.put("vod_play_url", file.getName() + "$" + "file://" + file.getAbsolutePath());
            vod.put("vod_pic", "push_video");
        }

        JSONObject result = new JSONObject();
        result.put("list", new JSONArray(Collections.singletonList(vod)));
        return result.toString();
    }

    private boolean isImageFile(File file) {
        if (!file.isFile()) return false;
        String name = file.getName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot == -1) return false;
        String ext = name.substring(dot + 1);
        return ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png") 
                || ext.equals("gif") || ext.equals("webp") || ext.equals("bmp") || ext.equals("svg");
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSONObject result = new JSONObject();
        result.put("url", id);
        result.put("parse", 0);
        return result.toString();
    }

    private byte[] readBytes(File file) throws Exception {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[(int) file.length()];
            int read = 0;
            while (read < buf.length) {
                int r = fis.read(buf, read, buf.length - read);
                if (r == -1) break;
                read += r;
            }
            return buf;
        }
    }

    private String formatSize(long length) {
        if (length <= 0) return "0 B";
        final String[] units = new String[]{"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(length) / Math.log10(1024));
        return String.format(Locale.ROOT, "%.1f %s", length / Math.pow(1024, digitGroups), units[digitGroups]);
    }
}

package com.fongmi.android.tv.utils;

import android.os.Environment;

import com.fongmi.android.tv.App;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 扫描设备存储里的 .js / .py 爬虫文件。
 * 只走常用目录（存储根、Download、Documents、Movies 与外置 SD 卡），
 * 限制深度与体积，避免全盘扫描把界面卡死。
 */
public class SpiderScanner {

    private static final int MAX_DEPTH = 4;
    private static final long MAX_SIZE = 2 * 1024 * 1024;
    private static final int MAX_ITEMS = 2000;

    private static final Set<String> SKIP_DIR = Set.of(
            "Android", "data", "obb", "cache", "system", "proc", "sys", "dev",
            "node_modules", "__pycache__", ".git", ".svn", ".gradle",
            "Alarms", "Audiobooks", "Podcasts", "Ringtones", "Notifications"
    );

    public static class Item {

        public final File file;
        public boolean checked;

        public Item(File file) {
            this.file = file;
        }

        public String name() {
            return file.getName();
        }

        public String path() {
            return file.getAbsolutePath();
        }

        public boolean isPy() {
            return name().toLowerCase(Locale.getDefault()).endsWith(".py");
        }

        public String size() {
            long len = file.length();
            if (len < 1024) return len + " B";
            if (len < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", len / 1024f);
            return String.format(Locale.getDefault(), "%.1f MB", len / 1024f / 1024f);
        }

        public String label() {
            return name() + "\n" + size() + "  " + file.getParent();
        }
    }

    public interface Callback {
        void onUpdate(int count);

        void onFinish(List<Item> items);
    }

    /** 常用扫描根目录，去重后返回。 */
    public static List<File> roots() {
        List<File> list = new ArrayList<>();
        File root = Environment.getExternalStorageDirectory();
        add(list, root);
        if (root != null) {
            add(list, new File(root, "Download"));
            add(list, new File(root, "Documents"));
            add(list, new File(root, "Movies"));
        }
        File[] storages = new File("/storage").listFiles();
        if (storages != null) {
            for (File dir : storages) {
                String name = dir.getName();
                if (dir.isDirectory() && !"emulated".equals(name) && !"self".equals(name)) add(list, dir);
            }
        }
        return list;
    }

    private static void add(List<File> list, File dir) {
        if (dir == null || !dir.isDirectory()) return;
        try {
            String path = dir.getCanonicalPath();
            for (File exist : list) {
                if (path.equals(exist.getCanonicalPath())) return;
            }
            list.add(dir);
        } catch (Exception ignored) {
        }
    }

    /** 后台扫描，结果回主线程。 */
    public static void scan(Callback callback) {
        Task.submit(() -> {
            List<Item> items = new ArrayList<>();
            for (File root : roots()) walk(root, 0, items, callback);
            items.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
            App.post(() -> callback.onFinish(items));
        });
    }

    private static void walk(File dir, int depth, List<Item> items, Callback callback) {
        if (depth > MAX_DEPTH || items.size() >= MAX_ITEMS) return;
        if (dir == null || !dir.isDirectory() || dir.getName().startsWith(".")) return;
        if (SKIP_DIR.contains(dir.getName())) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        int found = 0;
        for (File file : files) {
            if (file.isDirectory()) {
                walk(file, depth + 1, items, callback);
            } else if (isSpider(file) && file.length() <= MAX_SIZE) {
                items.add(new Item(file));
                found++;
            }
        }
        if (found > 0) notifyCount(items.size(), callback);
    }

    private static boolean isSpider(File file) {
        String name = file.getName().toLowerCase(Locale.getDefault());
        return name.endsWith(".js") || name.endsWith(".py");
    }

    private static void notifyCount(int count, Callback callback) {
        App.post(() -> callback.onUpdate(count));
    }

    /** 供 UI 展示的摘要。 */
    public static String summary(List<Item> items) {
        int js = 0, py = 0;
        for (Item item : items) {
            if (item.isPy()) py++;
            else js++;
        }
        return String.format(Locale.getDefault(), "js %d · py %d", js, py);
    }

    public static List<String> labels(List<Item> items) {
        List<String> labels = new ArrayList<>();
        for (Item item : items) labels.add(item.label());
        return labels;
    }

    public static List<Item> checked(List<Item> items) {
        List<Item> result = new ArrayList<>();
        for (Item item : items) {
            if (item.checked) result.add(item);
        }
        return result;
    }

    public static void checkAll(List<Item> items, boolean checked) {
        for (Item item : items) item.checked = checked;
    }

    public static List<File> asFiles(List<Item> items) {
        List<File> files = new ArrayList<>();
        for (Item item : items) files.add(item.file);
        return Arrays.asList(files.toArray(new File[0]));
    }
}

package com.fongmi.android.tv.utils;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;

import com.fongmi.android.tv.App;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 扫描设备存储里的 .js / .py 爬虫文件。
 *
 * <p>两种来源：
 * <ul>
 *   <li>常用目录：存储根、Download、Documents、Movies 与外置 SD 卡，
 *       用 File 递归（依赖已授予的文件访问权限）；</li>
 *   <li>自定义目录：用户在系统文件选择器里授权的目录树（SAF Uri），
 *       用 DocumentsContract 递归，不依赖全文件访问权限。</li>
 * </ul>
 * 均限制深度与体积，避免全盘扫描把界面卡死。
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

    /** 一个候选爬虫文件；file 与 uri 二选一，取决于来源。 */
    public static class Item {

        public final String name;
        public final String path;
        public final long size;
        public final File file;
        public final Uri uri;
        public boolean checked;
        public boolean imported;

        Item(String name, String path, long size, File file, Uri uri) {
            this.name = name;
            this.path = path;
            this.size = size;
            this.file = file;
            this.uri = uri;
        }

        public boolean isPy() {
            return name.toLowerCase(Locale.getDefault()).endsWith(".py");
        }

        public String sizeText() {
            if (size < 1024) return size + " B";
            if (size < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", size / 1024f);
            return String.format(Locale.getDefault(), "%.1f MB", size / 1024f / 1024f);
        }
    }

    public interface Callback {
        void onUpdate(int count);

        void onFinish(List<Item> items);
    }

    public static Item of(File file) {
        return new Item(file.getName(), file.getAbsolutePath(), file.length(), file, null);
    }

    public static Item of(Uri uri, String name, long size) {
        return new Item(name, uri.toString(), size, null, uri);
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

    /** 后台扫描常用目录，结果回主线程。 */
    public static void scan(Callback callback) {
        Task.submit(() -> {
            List<Item> items = new ArrayList<>();
            for (File root : roots()) walk(root, 0, items, callback);
            sort(items);
            App.post(() -> callback.onFinish(items));
        });
    }

    /** 后台扫描用户授权的目录树（SAF），结果回主线程。 */
    public static void scanTree(Context context, Uri treeUri, Callback callback) {
        Task.submit(() -> {
            List<Item> items = new ArrayList<>();
            walkTree(context, treeUri, DocumentsContract.getTreeDocumentId(treeUri), 0, items, callback);
            sort(items);
            App.post(() -> callback.onFinish(items));
        });
    }

    private static void sort(List<Item> items) {
        items.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
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
            } else if (isSpider(file.getName()) && file.length() <= MAX_SIZE) {
                items.add(of(file));
                found++;
            }
        }
        if (found > 0) notifyCount(items.size(), callback);
    }

    private static void walkTree(Context context, Uri treeUri, String docId, int depth,
                                 List<Item> items, Callback callback) {
        if (depth > MAX_DEPTH || items.size() >= MAX_ITEMS) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (android.database.Cursor cursor = context.getContentResolver().query(children, projection, null, null, null)) {
            if (cursor == null) return;
            int found = 0;
            while (cursor.moveToNext()) {
                String id = cursor.getString(0);
                String name = cursor.getString(1);
                String mime = cursor.getString(2);
                long size = cursor.isNull(3) ? 0 : cursor.getLong(3);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    walkTree(context, treeUri, id, depth + 1, items, callback);
                } else if (isSpider(name) && size <= MAX_SIZE) {
                    items.add(of(DocumentsContract.buildDocumentUriUsingTree(treeUri, id), name, size));
                    found++;
                }
            }
            if (found > 0) notifyCount(items.size(), callback);
        } catch (Exception ignored) {
        }
    }

    private static boolean isSpider(String name) {
        String lower = name.toLowerCase(Locale.getDefault());
        return lower.endsWith(".js") || lower.endsWith(".py");
    }

    private static void notifyCount(int count, Callback callback) {
        App.post(() -> callback.onUpdate(count));
    }

    public static String summary(List<Item> items) {
        int js = 0, py = 0;
        for (Item item : items) {
            if (item.isPy()) py++;
            else js++;
        }
        return String.format(Locale.getDefault(), "js %d · py %d", js, py);
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

    /** 按文件名去重。 */
    public static List<Item> dedup(List<Item> items) {
        Set<String> seen = new LinkedHashSet<>();
        List<Item> result = new ArrayList<>();
        for (Item item : items) {
            if (seen.add(item.name)) result.add(item);
        }
        return result;
    }
}

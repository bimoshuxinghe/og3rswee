package com.fongmi.chaquo;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * 调试日志收集器（Python 侧 base.nsig 解密诊断日志）。
 *
 * 设计：Python 线路通过 Chaquopy jclass 调用本类静态方法 log(line)，
 * 日志同时进入内存环形缓冲（供本地 HTTP 调试页读取）与 App 私有文件
 * （/data/data/xinghe.tv/files/nsig_debug.log，持久化可追溯）。
 * 仅作诊断用途，任何失败都静默忽略，绝不影响业务解密流程。
 */
public class DbgLog {

    private static final String TAG = "DbgLog";
    private static final int MAX = 2000;

    private static volatile File file;
    private static final Deque<String> logs = new ArrayDeque<>();

    private DbgLog() {
    }

    /** 在 App 启动时调用一次，指定日志文件目录。 */
    public static void init(File dir) {
        if (file == null && dir != null) file = new File(dir, "nsig_debug.log");
    }

    /** Python 侧解密诊断日志入口。 */
    public static void log(String line) {
        if (line == null) return;
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String entry = "[" + ts + "] " + line;
        synchronized (logs) {
            logs.addLast(entry);
            while (logs.size() > MAX) logs.removeFirst();
        }
        if (file != null) {
            try (FileOutputStream fos = new FileOutputStream(file, true);
                 OutputStreamWriter w = new OutputStreamWriter(fos, StandardCharsets.UTF_8)) {
                w.write(entry + "\n");
            } catch (Exception ignored) {
            }
        }
        Log.i(TAG, line);
    }

    /** 返回全部缓冲日志（调试页 /api 使用）。 */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : logs) sb.append(s).append('\n');
        return sb.toString();
    }

    /** 清空内存缓冲与日志文件（调试页清空按钮）。 */
    public static synchronized void clear() {
        logs.clear();
        if (file != null && file.exists()) file.delete();
    }
}

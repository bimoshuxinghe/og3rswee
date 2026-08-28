package com.github.catvod.utils;

import android.util.Log;

import java.io.BufferedWriter;
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
 * 全局调试日志中枢：所有模块（app/quickjs/catvod/chaquo）统一从这里输出诊断日志。
 *
 * 日志三路输出：内存环形缓冲（调试页 /api 读取）+ 文件持久化（debug.log，超限轮转）+ Logcat。
 * 通过反射无法直接到达本类的模块（如 Python 侧）经由 chaquo.DbgLog 委托进来。
 * 任何日志失败都静默忽略，绝不影响业务流程。
 */
public class DebugLog {

    private static final String TAG = "DebugLog";
    private static final int MAX_LINES = 5000;
    private static final long MAX_FILE_SIZE = 5 * 1024 * 1024;

    private static volatile File file;
    private static volatile boolean enabled = true;
    private static final Deque<String> logs = new ArrayDeque<>();
    private static BufferedWriter writer;

    private DebugLog() {
    }

    /** App 启动时调用一次，指定日志文件目录。 */
    public static void init(File dir) {
        if (dir == null) return;
        synchronized (DebugLog.class) {
            try {
                closeWriter();
                file = new File(dir, "debug.log");
                writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        }
    }

    public static void setEnabled(boolean value) {
        enabled = value;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void i(String tag, String msg) {
        write("I", tag, msg);
    }

    public static void w(String tag, String msg) {
        write("W", tag, msg);
    }

    public static void e(String tag, String msg) {
        write("E", tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        write("E", tag, msg + " | " + Log.getStackTraceString(t).replace('\n', ' '));
    }

    private static void write(String level, String tag, String msg) {
        if (!enabled || msg == null) return;
        String ts = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date());
        String entry = "[" + ts + "][" + level + "][" + tag + "] " + msg;
        synchronized (logs) {
            logs.addLast(entry);
            while (logs.size() > MAX_LINES) logs.removeFirst();
        }
        writeFile(entry);
        Log.println(priority(level), TAG, tag + ": " + msg);
    }

    private static void writeFile(String entry) {
        synchronized (DebugLog.class) {
            try {
                if (writer == null) return;
                writer.write(entry);
                writer.write("\n");
                writer.flush();
                if (file != null && file.length() > MAX_FILE_SIZE) rotate();
            } catch (Exception ignored) {
            }
        }
    }

    private static void rotate() throws Exception {
        closeWriter();
        File old = new File(file.getParentFile(), "debug.log.1");
        if (old.exists()) old.delete();
        file.renameTo(old);
        writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8));
    }

    private static void closeWriter() {
        try {
            if (writer != null) writer.close();
        } catch (Exception ignored) {
        }
    }

    private static int priority(String level) {
        return switch (level) {
            case "W" -> Log.WARN;
            case "E" -> Log.ERROR;
            default -> Log.INFO;
        };
    }

    /** 返回全部缓冲日志（调试页 /api 使用）。 */
    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        for (String s : logs) sb.append(s).append('\n');
        return sb.toString();
    }

    /** 清空内存缓冲与日志文件（调试页清空按钮）。 */
    public static synchronized void clear() {
        synchronized (logs) {
            logs.clear();
        }
        synchronized (DebugLog.class) {
            try {
                if (writer != null) {
                    writer.close();
                    if (file != null && file.exists()) file.delete();
                    writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8));
                } else if (file != null && file.exists()) {
                    file.delete();
                }
            } catch (Exception ignored) {
            }
        }
    }
}

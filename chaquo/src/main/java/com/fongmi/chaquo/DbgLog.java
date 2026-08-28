package com.fongmi.chaquo;

import java.io.File;

/**
 * 兼容入口：历史上 Python 侧通过 jclass 调用本类，现全部委托给
 * catvod 的 DebugLog 统一处理（内存缓冲 + 文件 + Logcat）。
 * 保留原 API（log/dump/clear/init）确保 Python 与既有调用方无需改动。
 */
public class DbgLog {

    private DbgLog() {
    }

    /** App 启动时调用一次，指定日志文件目录。 */
    public static void init(File dir) {
        com.github.catvod.utils.DebugLog.init(dir);
    }

    /** 日志入口（兼容旧签名，级别 I）。 */
    public static void log(String line) {
        com.github.catvod.utils.DebugLog.i("DbgLog", line == null ? "null" : line);
    }

    /** 返回全部缓冲日志（调试页 /api 使用）。 */
    public static String dump() {
        return com.github.catvod.utils.DebugLog.dump();
    }

    /** 清空内存缓冲与日志文件（调试页清空按钮）。 */
    public static void clear() {
        com.github.catvod.utils.DebugLog.clear();
    }
}

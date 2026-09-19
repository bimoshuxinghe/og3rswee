package com.fongmi.android.tv.utils;

import android.os.Looper;
import android.util.Log;

import com.fongmi.android.tv.App;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 主线程崩溃护栏（Looper 级）。
 * <p>
 * 背景：站点的爬虫 jar 是运行时从站源配置远程下载的第三方代码（已混淆，作者可随时更换），
 * 其中不少实现会把回调直接 post 到主线程 Handler。一旦回调内部抛出 NPE 等异常，
 * 异常会从消息队列里冒出来 —— 此时调用栈上已经没有我们的 try/catch，只能一路穿出
 * {@link Looper#loop()} 把整个 App 干掉。典型栈：
 * <pre>
 *   java.lang.NullPointerException: Attempt to invoke virtual method
 *     'void com.github.catvod.spider.merge.n.f.c(java.lang.String)' on a null object reference
 *     at com.github.catvod.spider.merge.i.d.run(Unknown Source:408)
 *     at android.os.Handler.handleCallback(Handler.java:...)
 * </pre>
 * <p>
 * 做法：在主线程再套一层 {@code Looper.loop()}，把从消息队列里抛出的异常接住并继续取消息。
 * 这样第三方代码的崩溃只会让"那一次回调"失败，App 本身不退出。
 * <p>
 * 安全约束：
 * <ul>
 *   <li>带频率闸：{@link #WINDOW_MS} 内超过 {@link #MAX_IN_WINDOW} 次就不再吞，
 *       交给系统默认处理。避免某个每帧必抛的回调把主线程变成刷异常的死循环。</li>
 *   <li>只影响主线程消息队列，不改子线程崩溃行为；{@code Looper.quit()} 时正常退出。</li>
 * </ul>
 */
public class CrashGuard {

    private static final String TAG = "CrashGuard";
    private static final long WINDOW_MS = 5000L;
    private static final int MAX_IN_WINDOW = 20;

    private static final AtomicInteger COUNT = new AtomicInteger();

    private static volatile boolean installed;
    private static volatile long windowStart;
    private static volatile String last = "";

    /** 在 Application.onCreate 中调用一次即可 */
    public static synchronized void install() {
        if (installed) return;
        installed = true;
        windowStart = System.currentTimeMillis();
        App.post(() -> {
            while (true) {
                try {
                    Looper.loop();
                    return; // Looper 被 quit，正常结束护栏
                } catch (Throwable e) {
                    if (!absorb(e)) {
                        // 频率过高，说明有回调在持续抛异常，不再兜底，交给系统处理
                        Thread.UncaughtExceptionHandler h = Thread.getDefaultUncaughtExceptionHandler();
                        if (h != null) h.uncaughtException(Thread.currentThread(), e);
                        return;
                    }
                }
            }
        });
    }

    /** 记录并判断是否吞掉；超过频率闸返回 false */
    private static boolean absorb(Throwable e) {
        StackTraceElement[] st = e.getStackTrace();
        String top = st != null && st.length > 0 ? String.valueOf(st[0]) : e.toString();
        last = e.getClass().getName() + " @ " + top;
        Log.e(TAG, "已拦截消息队列异常，应用继续运行: " + last, e);
        long now = System.currentTimeMillis();
        if (now - windowStart > WINDOW_MS) {
            windowStart = now;
            COUNT.set(0);
        }
        return COUNT.incrementAndGet() <= MAX_IN_WINDOW;
    }

    /** 最近一次被拦截的崩溃摘要，便于用户反馈时定位是哪个站源的 jar 出问题 */
    public static String lastCrash() {
        return last;
    }
}

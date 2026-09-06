package com.fongmi.android.tv.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 远程服务开关（kill switch）。
 * <p>
 * 服务器返回一段异或编码的 HEX 字符串，解码后为：{@code 状态|宽限小时|文案|模式|时间戳}
 * <ul>
 *   <li>状态：0=放行，1=停止服务</li>
 *   <li>宽限小时：网络请求失败时，距上次成功放行在该时长内仍允许离线使用（0=断网立即失效）</li>
 *   <li>文案：停止服务时向用户展示的内容</li>
 *   <li>模式：1=弹窗提示后退出，2=静默退出（类似闪退）</li>
 *   <li>时间戳：服务器 Unix 秒，客户端只接受与本地时间偏差 10 分钟内的响应，防止静态伪造</li>
 * </ul>
 * <p>
 * 多服务器并行验证：启动时同时请求所有验证服务器（{@link #URLS}），任一台验证通过即放行；
 * 没有任何放行但有服务器明确停用则停用；全部连不上才走离线宽限裁决（海外用户连不上国内服务器时，
 * 海外备用服务器可正常放行，不受影响）。
 * <p>
 * 防绕过设计：
 * <ul>
 *   <li>默认拒绝：未取得有效放行前 {@link #soft()} 恒为 false，删除调用代码只会让软件不可用</li>
 *   <li>多点校验：Application 预取 + 主页裁决 + 播放页复核</li>
 *   <li>响应全文异或编码，代码与字符串池中无可搜索关键词</li>
 *   <li>时间窗校验，抓包得到的固定响应很快过期</li>
 * </ul>
 */
public class Guard {

    // ==================== 验证服务器：支持多台并行验证，任一台验证通过即放行 ====================
    // 国内主服务器；海外备用服务器（海外用户连不上国内时，走这台）
    private static final String[] URLS = {
            "http://itv666.cc/xingluo.php",
            "http://52xinghe.top/xingluo.php"
    };
    // ==================== 解码密钥（16 字节，需与服务器端保持一致） ====================
    private static final byte[] K = {0x5A, 0x27, (byte) 0xB9, (byte) 0xF1, 0x6E, 0x04, (byte) 0xD3, (byte) 0x8C,
            0x71, (byte) 0xE9, 0x2A, (byte) 0xC5, 0x08, (byte) 0xBD, (byte) 0xF4, 0x39};

    private static final int WAIT_MS = 5000;      // 主页裁决等待网络的最长时间（预取通常早已完成）
    private static final int REQ_TIMEOUT_MS = 4000; // 单台验证服务器的请求超时（全部并行，总耗时≈单台耗时）
    private static final long TS_TOLERANCE = 600; // 响应时间窗（秒）

    private static final AtomicReference<Outcome> pending = new AtomicReference<>();
    private static final CountDownLatch latch = new CountDownLatch(1);
    private static volatile boolean verified = false;

    private static class Outcome {
        final boolean allow;
        final String msg;
        final int mode;
        final int hours;

        Outcome(boolean allow, String msg, int mode, int hours) {
            this.allow = allow;
            this.msg = msg;
            this.mode = mode;
            this.hours = hours;
        }
    }

    /** Application 启动时后台预取一次服务器指令（并行请求所有验证服务器） */
    public static void prefetch() {
        try {
            if (unconfigured()) {
                pending.set(new Outcome(true, "", 0, 0));
            } else {
                decide();
            }
        } catch (Throwable ignored) {
            pending.set(offline());
        } finally {
            latch.countDown();
        }
    }

    /**
     * 并行请求全部验证服务器，裁决规则：
     * <ul>
     *   <li>任一台返回有效放行响应 → 放行（按"优先通过"策略，只要有一台放行就放行）</li>
     *   <li>没有任何放行、但有服务器明确下发停用 → 停用</li>
     *   <li>全部服务器连不上或响应无效 → 走离线宽限裁决（offline）</li>
     * </ul>
     */
    private static void decide() {
        final String[] results = new String[URLS.length];
        Thread[] threads = new Thread[URLS.length];
        for (int i = 0; i < URLS.length; i++) {
            final int index = i;
            threads[i] = new Thread(() -> results[index] = fetch(URLS[index]));
            threads[i].start();
        }
        for (Thread t : threads) {
            try {
                t.join(REQ_TIMEOUT_MS + 1500L);
            } catch (InterruptedException ignored) {
            }
        }
        String allow = null, deny = null;
        for (String r : results) {
            if (r == null) continue;
            Outcome o = decode(r);
            if (o == null) continue;
            if (o.allow) {
                if (allow == null) allow = r;
            } else if (deny == null) {
                deny = r;
            }
        }
        if (allow != null) {
            pref().edit().putString("a", allow).putLong("t", System.currentTimeMillis()).apply();
            pending.set(decode(allow));
        } else if (deny != null) {
            pref().edit().putString("b", deny).apply();
            pending.set(decode(deny));
        } else {
            pending.set(offline());
        }
    }

    /** 单台服务器请求：短超时；响应必须通过解码+时间窗校验才算成功，否则视为连不上 */
    private static String fetch(String url) {
        try {
            if (url == null || url.isEmpty()) return null;
            String resp = OkHttp.string(url, REQ_TIMEOUT_MS);
            return decode(resp) != null ? resp : null;
        } catch (Throwable e) {
            return null;
        }
    }

    /** 主页入口：同步裁决。返回 false 表示已执行停止流程，调用方应立即 return */
    public static boolean enforce(Activity activity) {
        Outcome o = resolve();
        verified = o.allow;
        if (!o.allow) {
            block(activity, o.msg, o.mode);
            return false;
        }
        return true;
    }

    /** 播放页等次级入口：仅复核内存裁决结果，不发网络请求 */
    public static boolean soft() {
        return verified;
    }

    private static Outcome resolve() {
        try {
            latch.await(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        Outcome o = pending.get();
        return o != null ? o : offline();
    }

    /** 网络失败时的裁决：离线宽限窗口内放行，否则停止服务 */
    private static Outcome offline() {
        Outcome cached = decode(pref().getString("a", null));
        boolean grace = cached != null && cached.allow
                && System.currentTimeMillis() - pref().getLong("t", 0L) <= cached.hours * 3600_000L;
        if (grace) return new Outcome(true, "", 0, 0);
        Outcome blocked = decode(pref().getString("b", null));
        return new Outcome(false, blocked != null && blocked.msg != null && !blocked.msg.isEmpty() ? blocked.msg : "服务已停止",
                blocked != null ? blocked.mode : 1, 0);
    }

    /** 解码服务器响应；格式或时间窗不合法一律返回 null（视为请求失败） */
    private static Outcome decode(String hex) {
        try {
            if (hex == null || hex.length() < 10 || hex.length() % 2 != 0) return null;
            byte[] data = new byte[hex.length() / 2];
            for (int i = 0; i < data.length; i++) {
                data[i] = (byte) (Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16) ^ K[i % K.length]);
            }
            String[] p = new String(data, "UTF-8").split("\\|", -1);
            if (p.length < 5) return null;
            long ts = Long.parseLong(p[4].trim());
            if (Math.abs(System.currentTimeMillis() / 1000 - ts) > TS_TOLERANCE) return null;
            String status = p[0].trim();
            if (!"0".equals(status) && !"1".equals(status)) return null;
            int hours = Integer.parseInt(p[1].trim());
            String m = p[3].trim();
            if (!"1".equals(m) && !"2".equals(m)) return null;
            return new Outcome("0".equals(status), p[2], "2".equals(m) ? 2 : 1, hours);
        } catch (Throwable e) {
            return null;
        }
    }

    /** 执行停止服务：模式 2 或无界面时静默退出，模式 1 弹窗提示后退出 */
    private static void block(final Context ctx, String msg, int mode) {
        if (mode == 2 || !(ctx instanceof Activity)) {
            quit();
            return;
        }
        App.post(() -> {
            try {
                new AlertDialog.Builder(ctx).setTitle("提示").setMessage(msg).setCancelable(false)
                        .setPositiveButton("确定", (d, w) -> quit()).show();
            } catch (Throwable t) {
                quit();
            }
        });
    }

    private static void quit() {
        Process.killProcess(Process.myPid());
        System.exit(10);
    }

    private static SharedPreferences pref() {
        return App.get().getSharedPreferences("gs", Context.MODE_PRIVATE);
    }

    private static boolean unconfigured() {
        for (String url : URLS) {
            if (url != null && !url.isEmpty() && !url.startsWith("REPLACE")) return false;
        }
        return true;
    }
}

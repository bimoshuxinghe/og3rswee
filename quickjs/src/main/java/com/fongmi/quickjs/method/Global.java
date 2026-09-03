package com.fongmi.quickjs.method;

import android.util.Log;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;

import com.fongmi.quickjs.bean.Req;
import com.fongmi.quickjs.utils.Connect;
import com.fongmi.quickjs.utils.Crypto;
import com.github.catvod.Proxy;
import com.github.catvod.utils.Trans;
import com.github.catvod.utils.UriUtil;
import com.orhanobut.logger.Logger;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSMethod;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import java.io.IOException;
import java.lang.reflect.Method;
import android.net.Uri;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Response;

public class Global {

    private final ExecutorService executor;
    private final QuickJSContext ctx;
    private final Timer timer;
    private final Map<Integer, Timeout> timers;
    private final AtomicInteger timerId;
    private final Thread ctxThread;
    private volatile boolean destroyed;

    private Global(QuickJSContext ctx, ExecutorService executor) {
        this.executor = executor;
        this.timerId = new AtomicInteger();
        this.timers = new ConcurrentHashMap<>();
        this.timer = new Timer("quickjs-timer", true);
        this.ctx = ctx;
        // Global 在 Spider.createFun() 内构造，与 QuickJSContext.create() 处于同一线程。
        this.ctxThread = Thread.currentThread();
        setProperty();
    }

    public static Global create(QuickJSContext ctx, ExecutorService executor) {
        return new Global(ctx, executor);
    }

    /**
     * 所有操作 ctx（创建 JS 对象、读 JS 属性）的调用都必须在创建 QuickJSContext 的线程上执行，
     * 否则 wrapper 会抛 "Must be call same thread in QuickJSContext.create!"。
     * JS 的异步代码（Promise/await 之后）可能运行在引擎的回调线程，
     * 因此这里按需把 ctx 操作切回 ctx 线程；当前已是 ctx 线程时直接执行，避免自等死锁。
     */
    private <T> T onCtx(Callable<T> call) throws Exception {
        if (Thread.currentThread() == ctxThread) return call.call();
        diag("ctx op from non-ctx thread '" + Thread.currentThread().getName() + "', dispatch to '" + ctxThread.getName() + "'");
        return executor.submit(call).get();
    }

    /** 日志双写：Logcat + 本地调试页（反射写 DbgLog 缓冲）。 */
    private static void diag(String msg) {
        android.util.Log.i("JsGlobal", msg);
        try {
            Class<?> cls = Class.forName("com.fongmi.chaquo.DbgLog");
            cls.getMethod("log", String.class).invoke(null, "[JsGlobal] " + msg);
        } catch (Throwable ignored) {
        }
    }

    public void destroy() {
        destroyed = true;
        for (Timeout timeout : timers.values()) timeout.cancelAndRelease();
        timers.clear();
        timer.cancel();
    }

    private void setProperty() {
        for (Method method : getClass().getMethods()) {
            if (!method.isAnnotationPresent(JSMethod.class)) continue;
            ctx.getGlobalObject().setProperty(method.getName(), args -> {
                try {
                    return method.invoke(this, args);
                } catch (Exception e) {
                    return null;
                }
            });
        }
    }

    private boolean submit(Runnable runnable) {
        try {
            if (destroyed) return false;
            if (executor.isShutdown()) return false;
            executor.submit(runnable);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Keep
    @JSMethod
    public String s2t(String text) {
        return Trans.s2t(false, text);
    }

    @Keep
    @JSMethod
    public String t2s(String text) {
        return Trans.t2s(false, text);
    }

    @Keep
    @JSMethod
    public Integer getPort() {
        return Proxy.getPort();
    }

    @Keep
    @JSMethod
    public String getProxy(Boolean local) {
        return Proxy.getUrl(local) + "?do=js";
    }

    @Keep
    @JSMethod
    public String js2Proxy(Boolean dynamic, Integer siteType, String siteKey, String url, JSObject headers) {
        return getProxy(!dynamic) + String.format("&from=catvod&siteType=%s&siteKey=%s&header=%s&url=%s", siteType, siteKey, Uri.encode(headers.stringify()), Uri.encode(url));
    }

    @Keep
    @JSMethod
    public Integer setTimeout(JSFunction func, Integer delay) {
        Timeout timeout = createTimeout(func);
        if (timeout == null) return 0;
        return schedule(timeout, delay) ? timeout.id : 0;
    }

    @Keep
    @JSMethod
    public Object clearTimeout(Integer id) {
        cancel(id);
        return null;
    }

    @Keep
    @JSMethod
    public JSObject _http(String url, JSObject options) {
        try {
            // getJSFunction 读取 JS 对象属性，同样必须切回 ctx 线程。
            JSFunction complete = onCtx(() -> options.getJSFunction("complete"));
            if (complete == null) return req(url, options);
            requestAsync(url, options, complete);
            return null;
        } catch (Exception e) {
            Logger.t("req").e("_http dispatch failed: %s", Log.getStackTraceString(e));
            return null;
        }
    }

    @Keep
    @JSMethod
    public JSObject req(String url, JSObject options) {
        try {
            // options.stringify()、Connect.success/error 都会操作 ctx，
            // 必须切回创建 QuickJSContext 的线程，否则异步 JS（await 之后）调用会跨线程报错。
            return onCtx(() -> {
                try {
                    Req req = Req.objectFrom(options.stringify());
                    Response res = Connect.to(url, req).execute();
                    return Connect.success(ctx, req, res);
                } catch (Exception e) {
                    // 必须留痕：req 失败在 JS 侧通常被 try/catch 吞掉，站点表现为
                    // 「init ok 但内容空白」，没有这条日志就无从定位（DNS/代理/证书等）。
                    Logger.t("req").e("spider request failed: %s\n%s", url, Log.getStackTraceString(e));
                    return Connect.error(ctx);
                }
            });
        } catch (Exception e) {
            Logger.t("req").e("req dispatch failed: %s", Log.getStackTraceString(e));
            return null;
        }
    }

    @Keep
    @JSMethod
    public String joinUrl(String parent, String child) {
        return UriUtil.resolve(parent, child);
    }

    @Keep
    @JSMethod
    public String md5X(String text) {
        String result = Crypto.md5(text);
        Logger.t("md5X").d("text:%s\nresult:\n%s", text, result);
        return result;
    }

    @Keep
    @JSMethod
    public String cdnDefendX(String code) {
        return Crypto.cdnDefend(code);
    }

    @Keep
    @JSMethod
    public String desX(String mode, boolean encrypt, String input, boolean inBase64, String key, String iv, boolean outBase64) {
        String result = Crypto.des(mode, encrypt, input, inBase64, key, iv, outBase64);
        Logger.t("desX").d("mode:%s\nencrypt:%s\ninBase64:%s\noutBase64:%s\nkey:%s\niv:%s\ninput:\n%s\nresult:\n%s", mode, encrypt, inBase64, outBase64, key, iv, input, result);
        return result;
    }

    @Keep
    @JSMethod
    public String aesX(String mode, boolean encrypt, String input, boolean inBase64, String key, String iv, boolean outBase64) {
        String result = Crypto.aes(mode, encrypt, input, inBase64, key, iv, outBase64);
        Logger.t("aesX").d("mode:%s\nencrypt:%s\ninBase64:%s\noutBase64:%s\nkey:%s\niv:%s\ninput:\n%s\nresult:\n%s", mode, encrypt, inBase64, outBase64, key, iv, input, result);
        return result;
    }

    @Keep
    @JSMethod
    public String rsaX(String mode, boolean pub, boolean encrypt, String input, boolean inBase64, String key, boolean outBase64) {
        String result = Crypto.rsa(mode, pub, encrypt, input, inBase64, key, outBase64);
        Logger.t("rsaX").d("mode:%s\npub:%s\nencrypt:%s\ninBase64:%s\noutBase64:%s\nkey:\n%s\ninput:\n%s\nresult:\n%s", mode, pub, encrypt, inBase64, outBase64, key, input, result);
        return result;
    }

    private void requestAsync(String url, JSObject options, JSFunction complete) {
        try {
            // complete.hold() 与 options.stringify() 均操作 JS 对象，需切回 ctx 线程。
            Req req = onCtx(() -> {
                complete.hold();
                return Req.objectFrom(options.stringify());
            });
            Connect.to(url, req).enqueue(getCallback(complete, req));
        } catch (Throwable e) {
            completeError(complete);
        }
    }

    private Callback getCallback(JSFunction complete, Req req) {
        return new Callback() {
            @Override
            public void onResponse(@NonNull Call call, @NonNull Response res) {
                completeSuccess(complete, req, res);
            }

            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                completeError(complete);
            }
        };
    }

    private void completeSuccess(JSFunction complete, Req req, Response res) {
        boolean posted = postCallback(complete, () -> complete.call(Connect.success(ctx, req, res)));
        if (!posted) res.close();
    }

    private void completeError(JSFunction complete) {
        postCallback(complete, () -> complete.call(Connect.error(ctx)));
    }

    private boolean postCallback(JSFunction callback, Runnable runnable) {
        boolean posted = submit(() -> callAndRelease(callback, runnable));
        if (!posted) callback.release();
        return posted;
    }

    private void callAndRelease(JSFunction callback, Runnable runnable) {
        try {
            if (!destroyed) runnable.run();
        } finally {
            callback.release();
        }
    }

    private Timeout createTimeout(JSFunction func) {
        if (func == null || destroyed) return null;
        Timeout timeout = new Timeout(timerId.incrementAndGet(), func);
        timers.put(timeout.id, timeout);
        func.hold();
        return timeout;
    }

    private boolean schedule(Timeout timeout, Integer delay) {
        try {
            timer.schedule(timeout, getDelay(delay));
            return true;
        } catch (Throwable e) {
            cancel(timeout.id);
            return false;
        }
    }

    private int getDelay(Integer delay) {
        return Math.max(0, delay == null ? 0 : delay);
    }

    private void cancel(Integer id) {
        if (id == null) return;
        Timeout timeout = timers.remove(id);
        if (timeout != null) timeout.cancelAndRelease();
    }

    private class Timeout extends TimerTask {

        private final JSFunction func;
        private final int id;
        private volatile boolean canceled;
        private boolean released;

        private Timeout(int id, JSFunction func) {
            this.func = func;
            this.id = id;
        }

        @Override
        public void run() {
            if (submit(this::fire)) return;
            Global.this.cancel(id);
        }

        private void fire() {
            if (canceled) return;
            try {
                func.call();
            } finally {
                Global.this.cancel(id);
            }
        }

        private synchronized void cancelAndRelease() {
            canceled = true;
            cancel();
            release();
        }

        private synchronized void release() {
            if (released) return;
            released = true;
            func.release();
        }
    }
}

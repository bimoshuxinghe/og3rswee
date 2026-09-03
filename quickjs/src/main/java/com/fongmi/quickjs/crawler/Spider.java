package com.fongmi.quickjs.crawler;

import android.content.Context;
import android.os.Looper;
import android.util.Log;

import com.fongmi.quickjs.bean.Res;
import com.fongmi.quickjs.method.Console;
import com.fongmi.quickjs.method.Global;
import com.fongmi.quickjs.method.Local;
import com.fongmi.quickjs.utils.Async;
import com.fongmi.quickjs.utils.JSUtil;
import com.fongmi.quickjs.utils.Module;
import com.github.catvod.utils.Asset;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.UriUtil;
import com.github.catvod.utils.Util;
import com.whl.quickjs.wrapper.JSArray;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

import org.json.JSONArray;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dalvik.system.DexClassLoader;

public class Spider extends com.github.catvod.crawler.Spider {

    private static final String TAG = "JsSpider";
    private static final long TIMEOUT = 20;

    /**
     * 模块顶层（行首无缩进）的 rule 声明，用于识别真正的 drpy 规则文件。
     * 不能用 contains("const rule") 之类的子串匹配：catjs 常在函数内部声明
     * 局部 rule（例如 init() 里的 const rule = await ...），子串匹配会误判。
     */
    private static final Pattern TOP_LEVEL_RULE =
            Pattern.compile("(?m)^(?:var|let|const)\\s+rule\\s*=");

    /**
     * 全局唯一的 JS 执行线程。
     *
     * QuickJS 的 Java wrapper 在构造 QuickJSContext 时记下 currentThreadId，
     * 之后每一次 ctx 操作（evaluate / call / freeValue / destroy）都会先 checkSameThread()，
     * 不匹配就抛 "Must be call same thread in QuickJSContext.create!"。
     *
     * 原先每个 Spider 各建一个单线程池，于是 N 个站点的 ctx 分别绑定在 N 个不同线程上。
     * 只要任何一次操作（OkHttp 回调、Timer 回调、destroy、上游并发调用）落在了
     * “另一个 Spider 的线程”上就会跨线程崩溃 —— 且崩溃点表现为「明明同线程 id 也报错」，
     * 因为报错的那个 ctx 根本不是日志里打印的那一个。
     *
     * 统一到全局单线程后，ctx 的归属线程在进程内恒定，从结构上消除这一类问题。
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "quickjs-js");
        thread.setDaemon(true);
        thread.setUncaughtExceptionHandler((t, e) -> Log.e(TAG, "js thread crashed", e));
        return thread;
    });

    private final DexClassLoader dex;
    private final String api;

    /** 创建 QuickJSContext 的线程：所有 JS 调用都必须发生在同一个线程上。 */
    private volatile Thread ctxThread;

    private QuickJSContext ctx;
    private JSObject jsObject;
    private Global global;
    private boolean cat;

    public Spider(String api, DexClassLoader dex) {
        this.api = api;
        this.dex = dex;
    }

    private <T> Future<T> submit(Callable<T> callable) {
        return EXECUTOR.submit(callable);
    }

    /** 日志双写：Logcat + 本地调试页（DebugServer 12138 /api，通过反射写 DbgLog 缓冲）。 */
    private static void log(String msg) {
        Log.i(TAG, msg);
        try {
            Class<?> cls = Class.forName("com.fongmi.chaquo.DbgLog");
            cls.getMethod("log", String.class).invoke(null, "[JsSpider] " + msg);
        } catch (Throwable ignored) {
        }
    }

    private static void logw(String msg) {
        Log.w(TAG, msg);
        try {
            Class<?> cls = Class.forName("com.fongmi.chaquo.DbgLog");
            cls.getMethod("log", String.class).invoke(null, "[JsSpider] " + msg);
        } catch (Throwable ignored) {
        }
    }

    /** 把异常压成单行可读文本（含最多 3 层 cause），供 DbgLog 输出定位。 */
    private static String describe(Throwable e) {
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getSimpleName()).append(": ").append(e.getMessage());
        Throwable cause = e.getCause();
        int depth = 0;
        while (cause != null && depth++ < 3) {
            sb.append(" | cause: ").append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
            cause = cause.getCause();
        }
        return sb.toString();
    }

    /**
     * 调用 JS 导出函数并等待结果。
     * 带超时保护：若 JS 端 Promise 永不 resolve（异步卡死），
     * 20 秒后降级返回空结果，避免单线程 executor 被永久占用
     * 导致后续所有调用排队（表现为"init 不执行、站点无数据"）。
     */
    private Object call(String func, Object... args) throws Exception {
        // 快照本实例身份，避免日志里 N 个 Spider 混在一起分不清是谁崩的。
        String who = api + "#" + System.identityHashCode(this);
        Future<CompletableFuture<Object>> future = submit(() -> {
            Thread t = Thread.currentThread();
            // ctx.getCurrentThreadId() 是 wrapper 内部真正参与校验的值，
            // 与当前线程 id 一并打印，可一锤定音区分「线程真的不同」与「ctx 不是这一个」。
            log("js call '" + func + "' [" + who + "] thread=" + t.getName() + "/" + t.getId()
                    + " ctxThread=" + (ctxThread == null ? "null" : ctxThread.getName() + "/" + ctxThread.getId())
                    + " ctx.getCurrentThreadId()=" + (ctx == null ? "null" : ctx.getCurrentThreadId()));
            return Async.run(jsObject, func, args);
        });
        try {
            Object result = future.get(TIMEOUT, TimeUnit.SECONDS).get(TIMEOUT, TimeUnit.SECONDS);
            log("js call '" + func + "' ok: " + (result == null ? "null" : result));
            return result;
        } catch (TimeoutException e) {
            logw("js call '" + func + "' timeout after " + TIMEOUT + "s, fallback");
            return fallback(func);
        } catch (Exception e) {
            // 必须写 DbgLog：call 失败（如 JS 端 init 抛 ReferenceError）若只走 Logcat，
            // 调试页完全不可见，表现为「js eval OK 但站点空白」无从定位。
            logw("js call '" + func + "' failed: " + describe(e));
            throw e;
        }
    }

    private Object fallback(String func) {
        if ("home".equals(func)) return "{\"class\":[],\"filters\":{}}";
        if ("homeVod".equals(func)) return "{\"list\":[]}";
        if ("category".equals(func)) return "{\"list\":[],\"page\":1,\"pagecount\":1}";
        if ("search".equals(func)) return "{\"list\":[],\"page\":1,\"pagecount\":1}";
        if ("detail".equals(func)) return "{\"list\":[]}";
        if ("play".equals(func)) return "{\"parse\":0,\"url\":\"\"}";
        if ("init".equals(func)) return "";
        return null;
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        initializeJS();
        Object ext;
        try {
            ext = submit(() -> getExt(extend)).get();
        } catch (Exception e) {
            // getExt 在 call 之外求值，失败不会走 call 的日志分支，这里补齐可见性。
            logw("getExt FAILED: " + describe(e));
            throw e;
        }
        call("init", ext);
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        return (String) call("home", filter);
    }

    @Override
    public String homeVideoContent() throws Exception {
        return (String) call("homeVod");
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        JSObject obj = submit(() -> JSUtil.toObject(ctx, extend)).get();
        return (String) call("category", tid, pg, filter, obj);
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        return (String) call("detail", ids.get(0));
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return (String) call("search", key, quick);
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        return (String) call("search", key, quick, pg);
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        JSArray array = submit(() -> JSUtil.toArray(ctx, vipFlags)).get();
        return (String) call("play", flag, id, array);
    }

    @Override
    public String liveContent(String url) throws Exception {
        return (String) call("live", url);
    }

    @Override
    public boolean manualVideoCheck() throws Exception {
        return (Boolean) call("sniffer");
    }

    @Override
    public boolean isVideoFormat(String url) throws Exception {
        return (Boolean) call("isVideo", url);
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        return "catvod".equals(params.get("from")) ? proxy2(params) : proxy1(params);
    }

    @Override
    public String action(String action) throws Exception {
        return (String) call("action", action);
    }

    @Override
    public void destroy() {
        try {
            call("destroy");
        } catch (Throwable e) {
            e.printStackTrace();
        }
        try {
            releaseJS();
        } catch (Throwable e) {
            // 必须留痕：releaseJS 失败会让 ctx/线程资源泄漏，
            // 表现为之后所有站点都卡在「加载不出来」。
            logw("releaseJS FAILED: " + describe(e));
        }
    }

    /**
     * 销毁顺序必须与 5.9.2 一致，且**整个流程都在 JS 线程内**完成：
     *   1) global.destroy() —— 停 Timer 并 release 挂起的 JSFunction
     *   2) jsObject.release()
     *   3) ctx.destroy()
     * 只要其中任何一步跑到非 ctx 线程，wrapper 的 checkSameThread() 就会抛
     * "Must be call same thread"，异常还会从这里冒泡出去打断外层的批量清理。
     */
    private void releaseJS() throws Exception {
        submit(() -> {
            try {
                if (global != null) global.destroy();
            } catch (Throwable e) {
                logw("global destroy failed: " + describe(e));
            }
            try {
                if (jsObject != null) jsObject.release();
            } catch (Throwable e) {
                logw("jsObject release failed: " + describe(e));
            }
            try {
                if (ctx != null) ctx.destroy();
            } catch (Throwable e) {
                logw("ctx destroy failed: " + describe(e));
            }
            global = null;
            jsObject = null;
            ctx = null;
            ctxThread = null;
            return null;
        }).get();
    }

    private void initializeJS() throws Exception {
        try {
            submit(() -> {
                long start = System.currentTimeMillis();
                createCtx();
                createFun();
                createObj();
                log("js init ok: " + api + " (" + (System.currentTimeMillis() - start) + "ms)");
                return null;
            }).get(TIMEOUT, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            logw("js init TIMEOUT after " + TIMEOUT + "s: " + api);
            throw new RuntimeException("JS init timeout: " + api);
        } catch (Exception e) {
            logw("js init FAILED: " + e);
            throw e;
        }
    }

    private void createCtx() {
        ctx = QuickJSContext.create();
        ctxThread = Thread.currentThread();
        Thread main = Looper.getMainLooper().getThread();
        log("ctx created on thread '" + ctxThread.getName() + "' id=" + ctxThread.getId()
                + " | main thread: " + main.getName() + "/" + main.getId()
                + " | same=" + (ctxThread == main));
        log("ctx created [" + api + "#" + System.identityHashCode(this) + "] thread=" + ctxThread.getName()
                + "/" + ctxThread.getId() + " ctx.getCurrentThreadId()=" + ctx.getCurrentThreadId()
                + " mainThread=" + main.getName() + "/" + main.getId() + " same=" + (ctxThread == main));
        ctx.setConsole(new Console());
        ctx.evaluate(Asset.read("js/lib/http.js"));
        ctx.evaluate(Asset.read("js/lib/crypto-js.js"));
        ctx.getGlobalObject().setProperty("local", Local.class);
        ctx.setModuleLoader(new QuickJSContext.BytecodeModuleLoader() {
            @Override
            public String moduleNormalizeName(String baseModuleName, String moduleName) {
                return UriUtil.resolve(baseModuleName, moduleName);
            }

            @Override
            public byte[] getModuleBytecode(String moduleName) {
                return ctx.compileModule(Module.get().fetch(moduleName), moduleName);
            }
        });
        loadDrpyHelpers();
    }

    private void loadDrpyHelpers() {
        try {
            ctx.evaluateModule(Asset.read("js/lib/drpy.js"), "lib/drpy.js");
        } catch (Throwable ignored) {
        }
    }

    private void createFun() {
        try {
            global = Global.create(ctx, EXECUTOR);
            Class<?> clz = dex.loadClass("com.github.catvod.js.Function");
            clz.getDeclaredConstructor(QuickJSContext.class).newInstance(ctx);
        } catch (Throwable ignored) {
        }
    }

    private void createObj() {
        String spider = "__JS_SPIDER__";
        String global = "globalThis." + spider;
        String content = Module.get().fetch(api);
        log("fetch js: " + api + " -> " + (content == null ? "null" : content.length() + " bytes"));
        if (content == null || content.isEmpty()) {
            throw new RuntimeException("JS spider file is empty or failed to load: " + api);
        }
        cat = content.contains("__jsEvalReturn");
        log("js style: " + (cat ? "__jsEvalReturn (module)" : "default/drpy"));
        // catjs（猫影视）自带 __jsEvalReturn 导出，绝不能追加 drpy 规则钩子：
        // 追加的 globalThis.__DRPY_RULE__ = rule 会在模块顶层求值，而多数 catjs
        // 只在函数内部声明局部 rule，顶层访问不到即抛 ReferenceError，
        // 导致整个模块加载失败（表现为站点加载不出来、内容空白）。
        if (!cat && isDrpyRule(content)) {
            content += "\nglobalThis.__DRPY_RULE__ = (typeof rule !== 'undefined') ? rule : null;";
        }
        try {
            ctx.evaluateModule(content.replace(spider, global), api);
            log("js eval user module OK");
        } catch (Throwable e) {
            logw("js eval user module FAILED: " + e);
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }
        try {
            String template = Asset.read("js/lib/spider.js");
            ctx.evaluateModule(String.format(template, api));
            log("js eval spider template OK");
        } catch (Throwable e) {
            logw("js eval spider template FAILED: " + e);
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }
        jsObject = (JSObject) ctx.getProperty(ctx.getGlobalObject(), spider);
        if (jsObject == null) {
            logw("js object is NULL (template did not set globalThis.__JS_SPIDER__): " + api);
            throw new RuntimeException("Failed to create JS spider object from: " + api);
        }
        log("js object created: " + api);
    }

    private boolean isDrpyRule(String content) {
        return TOP_LEVEL_RULE.matcher(content).find();
    }

    private Object getExt(String ext) {
        if (!cat) return Json.isObj(ext) ? ctx.parse(ext) : ext;
        JSObject obj = ctx.createNewJSObject();
        obj.setProperty("stype", 3);
        obj.setProperty("skey", siteKey);
        if (!Json.isObj(ext)) obj.setProperty("ext", ext);
        else obj.setProperty("ext", (JSObject) ctx.parse(ext));
        return obj;
    }

    private Object[] proxy1(Map<String, String> params) throws Exception {
        JSObject obj = submit(() -> JSUtil.toObject(ctx, params)).get();
        JSArray proxy = (JSArray) call("proxy", obj);
        String json = submit(proxy::stringify).get();
        JSONArray array = new JSONArray(json);
        Map<String, String> headers = array.length() > 3 ? Json.toMap(array.optString(3)) : null;
        boolean base64 = array.length() > 4 && array.optInt(4) == 1;
        Object[] result = new Object[4];
        result[0] = array.optInt(0);
        result[1] = array.optString(1);
        result[2] = getStream(array.opt(2), base64);
        result[3] = headers;
        return result;
    }

    private Object[] proxy2(Map<String, String> params) throws Exception {
        String url = params.get("url");
        String header = params.get("header");
        JSArray array = submit(() -> JSUtil.toArray(ctx, Arrays.asList(url.split("/")))).get();
        Object object = submit(() -> ctx.parse(header)).get();
        String proxy = (String) call("proxy", array, object);
        Res res = Res.objectFrom(proxy);
        Object[] result = new Object[3];
        result[0] = res.getCode();
        result[1] = res.getContentType();
        result[2] = res.getStream();
        return result;
    }

    private ByteArrayInputStream getStream(Object o, boolean base64) {
        if (o instanceof byte[]) {
            return new ByteArrayInputStream((byte[]) o);
        } else {
            String content = o.toString();
            if (base64 && content.contains("base64,")) content = content.split("base64,")[1];
            return new ByteArrayInputStream(base64 ? Util.decode(content) : content.getBytes());
        }
    }
}

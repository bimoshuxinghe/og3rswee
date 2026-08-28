package com.fongmi.quickjs.crawler;

import android.content.Context;
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

    private final ExecutorService executor;
    private final DexClassLoader dex;
    private final String api;

    private QuickJSContext ctx;
    private JSObject jsObject;
    private Global global;
    private boolean cat;

    public Spider(String api, DexClassLoader dex) {
        this.executor = Executors.newSingleThreadExecutor();
        this.api = api;
        this.dex = dex;
    }

    private <T> Future<T> submit(Callable<T> callable) {
        return executor.submit(callable);
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

    /**
     * 调用 JS 导出函数并等待结果。
     * 带超时保护：若 JS 端 Promise 永不 resolve（异步卡死），
     * 20 秒后降级返回空结果，避免单线程 executor 被永久占用
     * 导致后续所有调用排队（表现为"init 不执行、站点无数据"）。
     */
    private Object call(String func, Object... args) throws Exception {
        Future<CompletableFuture<Object>> future = submit(() -> Async.run(jsObject, func, args));
        try {
            Object result = future.get(TIMEOUT, TimeUnit.SECONDS).get(TIMEOUT, TimeUnit.SECONDS);
            log("js call '" + func + "' ok: " + (result == null ? "null" : result));
            return result;
        } catch (TimeoutException e) {
            logw("js call '" + func + "' timeout after " + TIMEOUT + "s, fallback");
            return fallback(func);
        } catch (Exception e) {
            Log.e(TAG, "js call '" + func + "' failed", e);
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
        call("init", submit(() -> getExt(extend)).get());
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
            e.printStackTrace();
        } finally {
            if (global != null) global.destroy();
            executor.shutdownNow();
        }
    }

    private void releaseJS() throws Exception {
        submit(() -> {
            jsObject.release();
            ctx.destroy();
            return null;
        }).get();
    }

    private void initializeJS() throws Exception {
        submit(() -> {
            long start = System.currentTimeMillis();
            createCtx();
            createFun();
            createObj();
            log("js init ok: " + api + " (" + (System.currentTimeMillis() - start) + "ms)");
            return null;
        }).get();
    }

    private void createCtx() {
        ctx = QuickJSContext.create();
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
            global = Global.create(ctx, executor);
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
        if (isDrpyRule(content)) content += "\nglobalThis.__DRPY_RULE__ = rule;";
        ctx.evaluateModule(content.replace(spider, global), api);
        ctx.evaluateModule(String.format(Asset.read("js/lib/spider.js"), api));
        jsObject = (JSObject) ctx.getProperty(ctx.getGlobalObject(), spider);
        if (jsObject == null) {
            throw new RuntimeException("Failed to create JS spider object from: " + api);
        }
        log("js object created: " + api);
    }

    private boolean isDrpyRule(String content) {
        return content.contains("var rule") || content.contains("let rule") || content.contains("const rule");
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

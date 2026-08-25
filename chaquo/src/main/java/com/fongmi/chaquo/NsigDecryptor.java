package com.fongmi.chaquo;

import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.JSFunction;
import com.whl.quickjs.wrapper.JSObject;
import com.whl.quickjs.wrapper.QuickJSContext;

/**
 * YouTube n-sig 解密服务（Java 侧 QuickJS 引擎执行）。
 *
 * 背景：YouTube 媒体 URL 中的 n 参数是加密签名（n-sig），未解密/解密失效时
 * CDN 拒绝请求（403），播放/快进弹 Bad HTTP Status。Python 端 js2py 对现代
 * JS（箭头函数/模板字符串）会静默失败，因此这里直接用壳子内置的 QuickJS
 * （ES2020+ 完整支持）执行 player.js 中提取的真实解密函数。
 *
 * 调用方：Python 线路（base.nsig）通过 Chaquopy jclass 调用本类。
 *   静态方法 decrypt(jsSource, funcName, nValue)：
 *     - jsSource: 已组装好的 JS 源码（依赖定义 + 主函数定义，仅定义不调用）
 *     - funcName: n-sig 解密主函数名
 *     - nValue:   URL 中的原始 n 参数值
 *     - 返回解密后的 n 值；任何失败返回 null（由 Python 侧决定兜底引擎）。
 */
public class NsigDecryptor {

    private static volatile QuickJSContext ctx;
    private static volatile boolean initFailed = false;

    // 大脚本缓存：完整 player.js + solver 只 evaluate 一次，之后直接复用已编译函数
    private static volatile String cachedSourceHash = null;
    private static volatile JSFunction cachedFn = null;

    private static QuickJSContext ctx() {
        if (initFailed) return null;
        if (ctx == null) {
            synchronized (NsigDecryptor.class) {
                if (ctx == null && !initFailed) {
                    try {
                        QuickJSLoader.init();
                        ctx = QuickJSContext.create();
                    } catch (Throwable t) {
                        initFailed = true;
                        t.printStackTrace();
                    }
                }
            }
        }
        return ctx;
    }

    /**
     * 预编译并缓存 jsSource 中的解密函数。
     * 只 evaluate 脚本并缓存函数引用，不实际调用——用于 Python 侧冒烟验证：
     * 能成功 evaluate 完整 player.js 且 __nsolver__ 存在，即认为该路径可信。
     * @return true 表示 evaluate 成功且函数存在；false 表示失败（含任何异常）。
     */
    public static synchronized boolean prepare(String jsSource, String funcName) {
        QuickJSContext c = ctx();
        if (c == null || jsSource == null || funcName == null) return false;
        try {
            if (cachedSourceHash != null && cachedSourceHash.equals(jsSource) && cachedFn != null) {
                return true;
            }
            c.evaluate(jsSource);
            JSObject global = c.getGlobalObject();
            if (global == null) return false;
            JSFunction fn = global.getJSFunction(funcName);
            if (fn == null) return false;
            cachedSourceHash = jsSource;
            cachedFn = fn;
            return true;
        } catch (Throwable t) {
            t.printStackTrace();
            return false;
        }
    }

    /**
     * 用 QuickJS 执行解密函数并返回解密后的 n 值。
     * 通过 synchronized 串行化 QuickJSContext 访问（QuickJS 非线程安全）。
     *
     * 性能优化：当 jsSource 与上次相同时（同一 player 版本），直接复用缓存的
     * JSFunction，不再重新 evaluate。完整 player.js 约 2.9MB，evaluate 一次约
     * 数百 ms~数秒；解密本身只是函数调用（微秒级），缓存收益巨大。
     */
    public static synchronized String decrypt(String jsSource, String funcName, String nValue) {
        if (!prepare(jsSource, funcName)) return null;
        try {
            Object res = cachedFn.call(new Object[]{nValue});
            if (res == null) return null;
            String out = String.valueOf(res);
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }
}

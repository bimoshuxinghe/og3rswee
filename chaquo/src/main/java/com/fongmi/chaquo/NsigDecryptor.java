package com.fongmi.chaquo;

import com.whl.quickjs.android.QuickJSLoader;
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
     * 用 QuickJS 执行解密函数并返回解密后的 n 值。
     * 每次调用重新 evaluate jsSource（保证函数定义最新），
     * 通过 synchronized 串行化 QuickJSContext 访问（QuickJS 非线程安全）。
     */
    public static synchronized String decrypt(String jsSource, String funcName, String nValue) {
        QuickJSContext c = ctx();
        if (c == null || jsSource == null || funcName == null || nValue == null) return null;
        try {
            c.evaluate(jsSource);
            Object raw = c.getProperty(c.getGlobalObject(), funcName);
            if (!(raw instanceof JSObject)) return null;
            JSObject fn = (JSObject) raw;
            Object res = fn.call(nValue);
            if (res == null) return null;
            String out = String.valueOf(res);
            return out.isEmpty() ? null : out;
        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }
}

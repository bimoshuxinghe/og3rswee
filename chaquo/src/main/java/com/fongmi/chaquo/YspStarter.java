package com.fongmi.chaquo;

import com.chaquo.python.Python;

/**
 * 内置 ysp-live 直播代理的 chaquo 侧启动器。
 *
 * <p>{@code com.chaquo.python.*} 由 chaquo 插件注入本模块类路径，app 模块
 * 不可见；因此把“启动 Python 并调用 ysp_boot.start”封装在这里，app 侧
 * （com.fongmi.android.tv.server.YspLive）只调本类的静态方法。
 */
public class YspStarter {

    /** 启动内嵌 Python（幂等）并运行 ysp-live 代理；返回 "ok" 或错误信息 */
    public static String start(String scriptDir, int port, String bind, String dataDir) {
        try {
            if (!Python.isStarted()) Python.start(Platform.create());
            Python.getInstance().getModule("ysp_boot").callAttr("start", scriptDir, port, bind, dataDir);
            return "ok";
        } catch (Throwable e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }
}

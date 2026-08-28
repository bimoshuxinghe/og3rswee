package com.fongmi.android.tv.server;

import java.io.IOException;

import fi.iki.elonen.NanoHTTPD;

/**
 * 本地调试日志服务：监听 127.0.0.1:12138（实际绑定所有网卡，局域网也可访问）。
 * 浏览器打开 http://127.0.0.1:12138 查看全局调试日志（JS 源、配置、网络、加密切点）。
 * 路由：
 *   GET /      调试页（HTML，自动轮询 /api）
 *   GET /api   返回全部日志（纯文本）
 *   GET /clear 清空日志
 */
public class DebugServer extends NanoHTTPD {

    private static final int PORT = 12138;
    private static volatile DebugServer instance;

    private DebugServer() {
        super(PORT);
    }

    public static synchronized void startServer() {
        if (instance != null) return;
        instance = new DebugServer();
        try {
            instance.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            com.github.catvod.utils.DebugLog.i("DebugServer", "debug page started at port " + PORT);
        } catch (IOException e) {
            instance = null;
            e.printStackTrace();
        }
    }

    public static boolean isRunning() {
        return instance != null && instance.wasStarted();
    }

    public static synchronized void stopServer() {
        if (instance != null) {
            instance.stop();
            instance = null;
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        if (uri == null) uri = "/";
        try {
            if ("/api".equals(uri)) {
                return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", com.github.catvod.utils.DebugLog.dump());
            }
            if ("/clear".equals(uri)) {
                com.github.catvod.utils.DebugLog.clear();
                return newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", "cleared");
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page());
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage());
        }
    }

    private String page() {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=0">
                    <title>全局调试日志</title>
                    <style>
                        :root{color-scheme:light dark}*{box-sizing:border-box}body{margin:0;font-family:ui-monospace,Menlo,Consolas,'Courier New',monospace;background:#0d1117;color:#c9d1d9;font-size:13px}
                        header{position:sticky;top:0;display:flex;align-items:center;gap:10px;padding:10px 14px;background:#161b22;border-bottom:1px solid #30363d;z-index:10;flex-wrap:wrap}
                        header h1{margin:0;font-size:15px;color:#58a6ff;font-weight:600}
                        .btn{border:1px solid #30363d;background:#21262d;color:#c9d1d9;padding:5px 14px;border-radius:6px;cursor:pointer;font-size:13px}
                        .btn:hover{background:#30363d}
                        .badge{margin-left:auto;color:#8b949e;font-size:12px}
                        #log{white-space:pre-wrap;word-break:break-all;padding:12px 14px;line-height:1.55}
                        .ok{color:#3fb950}.fail{color:#f85149}.warn{color:#d29922}.info{color:#58a6ff}
                    </style>
                </head>
                <body>
                    <header>
                        <h1>全局调试日志</h1>
                        <button class="btn" onclick="refresh()">刷新</button>
                        <button class="btn" onclick="clearLog()">清空</button>
                        <span class="badge" id="ts"></span>
                    </header>
                    <div id="log">加载中...</div>
                    <script>
                        async function refresh(){
                            try{
                                const res=await fetch('/api');
                                const text=await res.text();
                                const el=document.getElementById('log');
                                el.textContent=text||'(暂无日志)';
                                el.scrollTop=el.scrollHeight;
                                document.getElementById('ts').textContent=new Date().toLocaleTimeString()+'  '+text.split('\\n').length+' 行';
                            }catch(e){document.getElementById('log').textContent='读取失败: '+e.message;}
                        }
                        async function clearLog(){
                            await fetch('/clear');
                            document.getElementById('log').textContent='(已清空)';
                            setTimeout(refresh,500);
                        }
                        refresh();
                        setInterval(refresh,2000);
                    </script>
                </body>
                </html>
                """;
    }
}

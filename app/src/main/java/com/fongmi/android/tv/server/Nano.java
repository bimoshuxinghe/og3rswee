package com.fongmi.android.tv.server;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.server.process.Action;
import com.fongmi.android.tv.server.process.Cache;
import com.fongmi.android.tv.server.process.IsoStream;
import com.fongmi.android.tv.server.process.Local;
import com.fongmi.android.tv.server.process.M3u8;
import com.fongmi.android.tv.server.process.Media;
import com.fongmi.android.tv.server.process.Parse;
import com.fongmi.android.tv.server.process.Proxy;
import com.github.catvod.utils.Asset;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class Nano extends NanoHTTPD {

    private static final String INDEX = "index.html";

    private List<Process> process;

    public Nano(int port) {
        super(port);
        addProcess();
    }

    private void addProcess() {
        process = new ArrayList<>();
        process.add(new Action());
        process.add(new Cache());
        process.add(new IsoStream());
        process.add(new Local());
        process.add(new M3u8());
        process.add(new Media());
        process.add(new Parse());
        process.add(new Proxy());
    }

    public static Response ok() {
        return ok("OK");
    }

    public static Response ok(String text) {
        return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, text);
    }

    public static Response error(String text) {
        return error(Response.Status.INTERNAL_ERROR, text);
    }

    public static Response error(Response.Status status, String text) {
        return newFixedLengthResponse(status, MIME_PLAINTEXT, text);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String url = session.getUri().trim();
        Map<String, String> files = new HashMap<>();
        if (session.getMethod() == Method.POST) parse(session, files);
        if (url.startsWith("/tvbus")) return ok(LiveConfig.getResp());
        if (url.startsWith("/device")) return ok(Device.get().toString());
        for (Process process : process) if (process.isRequest(session, url)) return process.doResponse(session, url, files);
        return getAssets(url.substring(1));
    }

    private void parse(IHTTPSession session, Map<String, String> files) {
        try {
            String ct = session.getHeaders().get("content-type");
            if (ct != null) session.getHeaders().put("content-type", ct.replace("multipart/form-data", "multipart/form-data; charset=utf-8"));
            session.parseBody(files);
        } catch (Exception ignored) {
        }
    }

    private Response getAssets(String path) {
        try {
            if (path.isEmpty()) path = INDEX;
            if ("sub.html".equals(path)) return newFixedLengthResponse(Response.Status.OK, MIME_HTML, getProxyPage());
            InputStream is = Asset.open(path);
            if (is == null) return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found");
            return newFixedLengthResponse(Response.Status.OK, getMimeTypeForFile(path), is, -1);
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, e.getMessage());
        }
    }

    private String getProxyPage() {
        return """
                <!DOCTYPE html>
                <html lang="zh-CN">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=0">
                    <title>代理地址推送</title>
                    <style>
                        :root{color-scheme:light dark}*{box-sizing:border-box}body{margin:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;background:#101510;color:#eef4ee}main{min-height:100vh;display:flex;align-items:center;justify-content:center;padding:22px}.card{width:100%;max-width:520px;background:#1c211d;border:1px solid #354b40;border-radius:24px;padding:24px;box-shadow:0 16px 36px rgba(0,0,0,.25)}h2{margin:0 0 10px;text-align:center;font-size:24px}p{margin:0 0 22px;text-align:center;color:#cfe9d8;line-height:1.6}input{width:100%;height:54px;border:1px solid #6b756c;border-radius:14px;background:#101510;color:#fff;padding:0 14px;font-size:16px;outline:none}input:focus{border-color:#6cdbA4}button{width:100%;height:46px;margin-top:16px;border:0;border-radius:23px;background:#6cdba4;color:#003824;font-size:16px;font-weight:700}.toast{margin-top:14px;min-height:22px;text-align:center;color:#cfe9d8;word-break:break-all}
                    </style>
                </head>
                <body>
                    <main>
                        <div class="card">
                            <h2>代理地址推送</h2>
                            <p>只用于推送订阅地址。节点切换和测速请在电视 APP 里操作。</p>
                            <input id="url" placeholder="请输入代理订阅 URL" autofocus>
                            <button onclick="push()">推送代理订阅</button>
                            <div id="toast" class="toast"></div>
                        </div>
                    </main>
                    <script>
                        async function push(){
                            const toast=document.getElementById('toast');
                            const url=document.getElementById('url').value.trim();
                            if(!url){toast.textContent='订阅地址不能为空';return;}
                            toast.textContent='正在推送...';
                            try{
                                const body=new URLSearchParams({url});
                                const res=await fetch('/action?do=proxy_sub',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},body});
                                const text=await res.text();
                                let data;try{data=JSON.parse(text)}catch(e){}
                                toast.textContent=data?(data.msg||text):text;
                            }catch(e){toast.textContent='推送失败：'+e.message;}
                        }
                    </script>
                </body>
                </html>
                """;
    }
}

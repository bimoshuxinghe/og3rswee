package com.fongmi.android.tv.server.process;

import android.net.Uri;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.Constant;
import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Device;
import com.fongmi.android.tv.bean.History;
import com.fongmi.android.tv.bean.Keep;
import com.fongmi.android.tv.bean.Vod;
import com.fongmi.android.tv.event.CastEvent;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.proxy.ProxyNode;
import com.fongmi.android.tv.proxy.ProxySubscriptionManager;
import com.fongmi.android.tv.server.Nano;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.service.PlaybackService;
import com.fongmi.android.tv.utils.FileUtil;
import com.fongmi.android.tv.utils.Notify;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;

import java.util.concurrent.CompletableFuture;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;
import okhttp3.FormBody;

public class Action implements Process {

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/action");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        Map<String, String> params = session.getParms();
        String param = params.get("do");
        if (!TextUtils.isEmpty(param)) {
            String result = doJob(param, params);
            if (result != null) return Nano.ok(result);
        }
        return Nano.ok();
    }

    private String doJob(String param, Map<String, String> params) {
        switch (param) {
            case "file" -> onFile(params);
            case "push" -> onPush(params);
            case "cast" -> onCast(params);
            case "sync" -> onSync(params);
            case "search" -> onSearch(params);
            case "setting" -> { return onSetting(params); }
            case "proxy_sub" -> { return onProxySub(params); }
            case "add_nas" -> { return onAddNas(params); }
            case "add_media" -> { return onAddMedia(params); }
            case "refresh" -> onRefresh(params);
            case "control" -> onControl(params);
            case "danmaku" -> onDanmaku(params);
            case "webdav" -> { return onWebDav(params); }
        }
        return null;
    }

    private void onFile(Map<String, String> params) {
        String path = params.get("path");
        if (TextUtils.isEmpty(path)) return;
        if (path.endsWith(".apk")) FileUtil.openFile(Path.local(path));
        else if (path.endsWith(".srt") || path.endsWith(".ssa") || path.endsWith(".ass")) RefreshEvent.subtitle(path);
        else ServerEvent.setting(path);
    }

    private void onPush(Map<String, String> params) {
        String url = params.get("url");
        if (TextUtils.isEmpty(url)) return;
        ServerEvent.push(url);
    }

    private void onSearch(Map<String, String> params) {
        String word = params.get("word");
        if (TextUtils.isEmpty(word)) return;
        ServerEvent.search(word);
    }

    private String onProxySub(Map<String, String> params) {
        String url = params.get("url");
        if (TextUtils.isEmpty(url)) url = params.get("text");
        if (TextUtils.isEmpty(url)) return json(false, "proxy subscription url empty");
        try {
            Setting.putProxySubscriptionUrl(url.trim());
            List<ProxyNode> nodes = ProxySubscriptionManager.get().refresh(url.trim());
            ProxyNode selected = ProxySubscriptionManager.get().autoSelect();
            if (selected == null) return json(false, "parsed " + nodes.size() + " nodes, but no usable node");
            ServerEvent.proxySub(selected.getDisplay());
            return json(true, "proxy subscription updated: " + selected.getDisplay());
        } catch (Exception e) {
            return json(false, "proxy subscription update failed: " + e.getMessage());
        }
    }

    private String onSetting(Map<String, String> params) {
        String text = params.get("text");
        String name = params.get("name");
        String typeStr = params.get("type");
        int type = TextUtils.isEmpty(typeStr) ? 0 : Integer.parseInt(typeStr);
        if (TextUtils.isEmpty(text)) return json(false, "config url empty");
        Config config = Config.find(text, name, type).save();
        CompletableFuture<String> future = new CompletableFuture<>();
        if (type == 0) {
            VodConfig.load(config, new Callback() {
                @Override
                public void success() {
                    ServerEvent.settingSuccess();
                    future.complete(json(true, "vod config pushed"));
                }
                @Override
                public void error(String msg) {
                    future.complete(json(false, "vod config push failed: " + msg));
                }
            });
        } else if (type == 1) {
            LiveConfig.load(config, new Callback() {
                @Override
                public void success() {
                    ServerEvent.settingSuccess();
                    future.complete(json(true, "live config pushed"));
                }
                @Override
                public void error(String msg) {
                    future.complete(json(false, "live config push failed: " + msg));
                }
            });
        } else if (type == 2) {
            com.fongmi.android.tv.setting.Setting.putWall(0);
            WallConfig.load(config, new Callback() {
                @Override
                public void success() {
                    ServerEvent.settingSuccess();
                    future.complete(json(true, "wall config pushed"));
                }
                @Override
                public void error(String msg) {
                    future.complete(json(false, "wall config push failed: " + msg));
                }
            });
        }
        try {
            return future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return json(false, "request timeout");
        }
    }

    private String onAddNas(Map<String, String> params) {
        String type = params.get("type");
        String name = params.get("name");
        String host = params.get("host");
        String port = params.get("port");
        String path = params.get("path");
        String user = params.get("user");
        String pass = params.get("pass");

        if (TextUtils.isEmpty(name) || TextUtils.isEmpty(host)) {
            return json(false, "name and host cannot be empty");
        }

        String key = "local_nas_" + System.currentTimeMillis();
        StringBuilder extUrl = new StringBuilder();
        if ("smb".equalsIgnoreCase(type)) {
            extUrl.append("smb://");
        } else {
            if (!host.startsWith("http://") && !host.startsWith("https://")) {
                extUrl.append("http://");
            }
        }

        if (!TextUtils.isEmpty(user)) {
            extUrl.append(user);
            if (!TextUtils.isEmpty(pass)) {
                extUrl.append(":").append(pass);
            }
            extUrl.append("@");
        }

        String cleanHost = host.replace("smb://", "").replace("http://", "").replace("https://", "");
        extUrl.append(cleanHost);

        if (!TextUtils.isEmpty(port)) {
            extUrl.append(":").append(port);
        } else if ("smb".equalsIgnoreCase(type)) {
            extUrl.append(":445");
        }

        if (!TextUtils.isEmpty(path)) {
            if (!path.startsWith("/")) extUrl.append("/");
            extUrl.append(path);
        }

        com.fongmi.android.tv.bean.Site site = new com.fongmi.android.tv.bean.Site();
        site.setKey(key);
        site.setName(name);
        site.setType(3);
        site.setApi("smb".equalsIgnoreCase(type) ? "csp_Smb" : "csp_WebDav");
        site.setExt(extUrl.toString());
        site.save();

        CompletableFuture<String> future = new CompletableFuture<>();
        VodConfig.get().init().load(new Callback() {
            @Override
            public void success() {
                future.complete(json(true, "added: " + name));
            }
            @Override
            public void error(String msg) {
                future.complete(json(false, "add failed: " + msg));
            }
        });

        try {
            return future.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            return json(false, "request timeout");
        }
    }

    private String onAddMedia(Map<String, String> params) {
        String type = params.get("type");
        String name = params.get("name");
        String host = params.get("host");
        String user = params.get("user");
        String pass = params.get("pass");

        if (TextUtils.isEmpty(type) || TextUtils.isEmpty(name) || TextUtils.isEmpty(host)) {
            return json(false, "type, name and host cannot be empty");
        }

        String prefix = getMediaPrefix(type);
        if (TextUtils.isEmpty(prefix)) return json(false, "unsupported media type");

        com.fongmi.android.tv.bean.Site site = new com.fongmi.android.tv.bean.Site();
        site.setKey(prefix + System.currentTimeMillis());
        site.setName(name);
        site.setType(3);
        site.setApi("media_" + type);
        site.setExt(buildMediaExt(type, host, user, pass));
        site.save();

        return json(true, "added: " + name);
    }

    private String getMediaPrefix(String type) {
        if ("feiniu".equals(type)) return "local_media_feiniu_";
        if ("emby".equals(type)) return "local_media_emby_";
        if ("jellyfin".equals(type)) return "local_media_jellyfin_";
        return "";
    }

    private String buildMediaExt(String type, String host, String user, String pass) {
        return new Uri.Builder()
                .scheme("media")
                .authority(type)
                .appendQueryParameter("type", safe(type))
                .appendQueryParameter("host", normalizeHost(host))
                .appendQueryParameter("user", safe(user))
                .appendQueryParameter("pass", safe(pass))
                .build()
                .toString();
    }

    private String normalizeHost(String host) {
        host = safe(host);
        if (!host.startsWith("http://") && !host.startsWith("https://")) host = "http://" + host;
        while (host.endsWith("/")) host = host.substring(0, host.length() - 1);
        return host;
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private String escapeJson(String value) {
        return safe(value).replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void onRefresh(Map<String, String> params) {
        String type = params.get("type");
        String path = params.get("path");
        String json = params.get("json");
        if (TextUtils.isEmpty(type)) return;
        switch (type) {
            case "live" -> RefreshEvent.live();
            case "detail" -> RefreshEvent.detail();
            case "player" -> RefreshEvent.player();
            case "category" -> RefreshEvent.category();
            case "danmaku" -> RefreshEvent.danmaku(path);
            case "subtitle" -> RefreshEvent.subtitle(path);
            case "vod" -> RefreshEvent.vod(Vod.objectFrom(json));
        }
    }

    private void onControl(Map<String, String> params) {
        String type = params.get("type");
        PlaybackService service = Server.get().getService();
        if (service == null || TextUtils.isEmpty(type)) return;
        switch (type) {
            case "play" -> App.post(() -> service.player().play());
            case "pause" -> App.post(() -> service.player().pause());
            case "stop" -> App.post(service::dispatchStop);
            case "prev" -> App.post(service::dispatchPrev);
            case "next" -> App.post(service::dispatchNext);
            case "repeat" -> App.post(service::dispatchRepeat);
            case "replay" -> App.post(service::dispatchReplay);
        }
    }

    private void onDanmaku(Map<String, String> params) {
        String text = params.get("text");
        PlaybackService service = Server.get().getService();
        if (service == null || TextUtils.isEmpty(text)) return;
        App.post(() -> service.player().sendDanmaku(text));
    }

    private void onCast(Map<String, String> params) {
        Config config = Config.objectFrom(params.get("config"));
        Device device = Device.objectFrom(params.get("device"));
        History history = History.objectFrom(params.get("history"));
        CastEvent.post(Config.find(config), device, history);
    }

    private void onSync(Map<String, String> params) {
        String type = params.get("type");
        boolean force = Objects.equals(params.get("force"), "true");
        String mode = Objects.requireNonNullElse(params.get("mode"), "0");
        if (params.get("device") != null && (mode.equals("0") || mode.equals("2"))) {
            Device device = Device.objectFrom(params.get("device"));
            if ("history".equals(type)) sendHistory(device, params);
            else if ("keep".equals(type)) sendKeep(device);
        }
        if (mode.equals("0") || mode.equals("1")) {
            if ("history".equals(type)) syncHistory(params, force);
            else if ("keep".equals(type)) syncKeep(params, force);
        }
    }

    private void post(Device device, String type, FormBody.Builder body) {
        try {
            OkHttp.newCall(OkHttp.client(Constant.TIMEOUT_SYNC), device.getIp().concat("/action?do=sync&mode=0&type=" + type), body.build()).execute();
        } catch (Exception e) {
            App.post(() -> Notify.show(e.getMessage()));
        }
    }

    private void sendHistory(Device device, Map<String, String> params) {
        try {
            Config config = Config.find(Config.objectFrom(params.get("config")));
            if (config.getUrl() == null) config = Config.vod();
            FormBody.Builder body = new FormBody.Builder();
            body.add("config", config.toString());
            body.add("targets", App.gson().toJson(History.get(config.getId())));
            post(device, "history", body);
        } catch (Exception e) {
            App.post(() -> Notify.show(e.getMessage()));
        }
    }

    private void sendKeep(Device device) {
        try {
            FormBody.Builder body = new FormBody.Builder();
            body.add("targets", App.gson().toJson(Keep.getVod()));
            body.add("configs", App.gson().toJson(Config.findUrls()));
            post(device, "keep", body);
        } catch (Exception e) {
            App.post(() -> Notify.show(e.getMessage()));
        }
    }

    public void syncHistory(Map<String, String> params, boolean force) {
        Config config = Config.find(Config.objectFrom(params.get("config")));
        List<History> targets = History.arrayFrom(params.get("targets"));
        if (config.getUrl() == null) return;
        if (config.getUrl().equals(VodConfig.getUrl())) {
            if (force) History.delete(config.getId());
            History.sync(targets);
            RefreshEvent.history();
        } else {
            VodConfig.load(config, getCallback(targets, force, config.getId()));
        }
    }

    private Callback getCallback(List<History> targets, boolean force, int cid) {
        return new Callback() {
            @Override
            public void success() {
                if (force) History.delete(cid);
                History.sync(targets);
                RefreshEvent.history();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    private void syncKeep(Map<String, String> params, boolean force) {
        List<Keep> targets = Keep.arrayFrom(params.get("targets"));
        List<Config> configs = Config.arrayFrom(params.get("configs"));
        if (TextUtils.isEmpty(VodConfig.getUrl()) && !configs.isEmpty()) {
            VodConfig.load(Config.find(configs.get(0)), getCallback(configs, targets, force));
        } else {
            if (force) Keep.deleteAll();
            Keep.sync(configs, targets);
            RefreshEvent.keep();
        }
    }

    private Callback getCallback(List<Config> configs, List<Keep> targets, boolean force) {
        return new Callback() {
            @Override
            public void success() {
                if (force) Keep.deleteAll();
                Keep.sync(configs, targets);
                RefreshEvent.keep();
            }

            @Override
            public void error(String msg) {
                Notify.show(msg);
            }
        };
    }

    private String onWebDav(Map<String, String> params) {
        String url = params.get("url");
        String user = params.get("user");
        String pass = params.get("pass");
        String interval = params.get("interval");
        String autoSync = params.get("auto_sync");

        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(user) || TextUtils.isEmpty(pass)) {
            return json(false, "please enter complete config");
        }

        com.fongmi.android.tv.setting.Setting.putSyncUrl(url);
        com.fongmi.android.tv.setting.Setting.putSyncUser(user);
        com.fongmi.android.tv.setting.Setting.putSyncPass(pass);
        com.fongmi.android.tv.setting.Setting.putSyncInterval(Integer.parseInt(interval));
        com.fongmi.android.tv.setting.Setting.putSyncAutoSync(Boolean.parseBoolean(autoSync));

        return json(true, "webdav config pushed");
    }
    private String json(boolean success, String msg) {
        return "{\"success\":" + success + ",\"msg\":\"" + escapeJson(msg) + "\"}";
    }
}

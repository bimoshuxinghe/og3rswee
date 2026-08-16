package com.fongmi.android.tv.utils;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.Proxy;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 自动站点 AI 识别核心逻辑：三步抓取（首页 -> 分类页 -> 详情页）+ AI 解析生成配置。
 * 状态通过 {@link StatusListener} 实时回调，供独立状态弹窗展示"AI 正在干什么"。
 */
public class AutoSiteHelper {

    public static final String JAR = "assets://1118.jar";
    public static final String API = "csp_XBPQ";
    private static final String AI_URL = "https://api.siliconflow.cn/v1/chat/completions";
    private static final String AI_MODEL = "deepseek-ai/DeepSeek-R1-0528-Qwen3-8B";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private static final AutoSiteHelper INSTANCE = new AutoSiteHelper();

    public interface StatusListener {
        void onStatus(String text);
    }

    public interface Callback {
        void onSuccess(String config);

        void onError(String message);
    }

    public static AutoSiteHelper get() {
        return INSTANCE;
    }

    /** 直连 client：不走应用内代理，避免代理导致抓取/AI 调用超时 */
    private OkHttpClient directClient(long timeout) {
        return OkHttp.client().newBuilder()
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();
    }

    /** 三步 AI 识别：抓首页 -> 分类页 -> 详情页，最终回调生成好的配置 JSON */
    public void detect(String url, StatusListener listener, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                // Step1: 抓首页，AI 分析分类
                postStatus(listener, "正在抓取首页...");
                String homeHtml = fetchHtml(url);
                if (TextUtils.isEmpty(homeHtml)) {
                    postError(callback, "首页抓取失败");
                    return;
                }
                postStatus(listener, "AI 分析网站框架中...");
                JsonObject step1 = callAi(buildPrompt1(homeHtml, url));
                String cateUrl = getString(step1, "分类页链接");
                if (TextUtils.isEmpty(cateUrl)) cateUrl = extractLink(homeHtml, new String[]{"vodshow", "vodtype", "list", "show", "type", "cateId"});
                String cate = getString(step1, "分类");
                if (!TextUtils.isEmpty(cate)) postStatus(listener, "从框架提取到分类：" + cate);
                // Step2: 抓分类页，AI 分析影片列表并选一个影片
                postStatus(listener, "正在抓取分类影片列表...");
                String cateHtml = TextUtils.isEmpty(cateUrl) ? "" : fetchHtml(cateUrl);
                postStatus(listener, "AI 从列表选影片中...");
                JsonObject step2 = callAi(buildPrompt2(cateHtml, cateUrl));
                String detailUrl = getString(step2, "详情页链接");
                if (TextUtils.isEmpty(detailUrl)) detailUrl = extractLink(cateHtml, new String[]{"voddetail", "detail", "play", "vod", "id"});
                // Step3: 抓详情页，AI 分析播放线路与播放选集
                postStatus(listener, "正在抓取影片详情页...");
                String detailHtml = TextUtils.isEmpty(detailUrl) ? "" : fetchHtml(detailUrl);
                postStatus(listener, "AI 分析播放线路与选集...");
                JsonObject step3 = callAi(buildPrompt3(detailHtml, detailUrl));
                // 合并配置
                postStatus(listener, "正在生成配置...");
                String config = mergeConfig(url, step1, step2, step3);
                HANDLER.post(() -> callback.onSuccess(config));
            } catch (Exception e) {
                postError(callback, e.getMessage());
            }
        });
    }

    private void postStatus(StatusListener listener, String text) {
        if (listener == null) return;
        HANDLER.post(() -> listener.onStatus(text));
    }

    private void postError(Callback callback, String message) {
        HANDLER.post(() -> callback.onError(message));
    }

    /** 直连抓取页面 HTML，截断到 8000 字符 */
    private String fetchHtml(String url) throws Exception {
        try (Response res = OkHttp.newCall(directClient(30000), url).execute()) {
            String html = res.body() == null ? "" : res.body().string();
            if (html.length() > 8000) html = html.substring(0, 8000);
            return html;
        }
    }

    /** 从 HTML 中提取第一个含关键词的 http 链接，找不到则返回第一个非静态资源链接 */
    private String extractLink(String html, String[] keywords) {
        if (TextUtils.isEmpty(html)) return "";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("https?://[^\"'\\s<>]+");
        java.util.regex.Matcher m = p.matcher(html);
        String first = "";
        while (m.find()) {
            String link = m.group().replaceAll("[\"'<>\\\\,;)\\]}]+$", "");
            if (link.matches(".*\\.(css|js|png|jpg|jpeg|gif|ico|svg|webp|woff|woff2|ttf|eot|mp4|m3u8)(\\?.*)?$")) continue;
            if (first.isEmpty()) first = link;
            for (String kw : keywords) {
                if (link.contains(kw)) return link;
            }
        }
        return first;
    }

    private JsonObject callAi(String prompt) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", AI_MODEL);
        body.addProperty("temperature", 0.2);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", prompt);
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(msg);
        body.add("messages", messages);
        RequestBody requestBody = RequestBody.create(body.toString(), JSON);
        Request request = new Request.Builder()
                .url(AI_URL)
                .addHeader("Authorization", "Bearer " + Setting.getAiKey())
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build();
        try (Response response = directClient(180000).newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() == null ? "" : response.body().string();
                throw new Exception("HTTP " + response.code() + ": " + err);
            }
            String text = response.body().string();
            JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
            String content = obj.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
            String json = extractJson(content);
            if (TextUtils.isEmpty(json)) return new JsonObject();
            return JsonParser.parseString(json).getAsJsonObject();
        }
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        return obj.get(key).getAsString();
    }

    /** Step1: 先识别网站框架（导航/分类结构），再从框架中提取真实分类，生成分类配置并给出分类页链接 */
    private String buildPrompt1(String html, String url) {
        return "你是一个视频网站解析专家。下面是视频网站 " + url + " 的首页 HTML 源码。\n\n"
                + "请按以下步骤分析：\n"
                + "第一步【识别网站框架】：先找出网站的整体框架结构——顶部/侧边导航栏、分类入口、菜单项，理解这个网站是怎么组织内容的（如苹果CMS、苹果CMS-V10、海洋CMS等常见影视站框架）\n"
                + "第二步【从框架提取分类】：基于识别出的框架，从导航/分类入口中提取网站的真实内容分类，生成\"分类\"字段，格式为\"分类名$分类ID#分类名$分类ID\"（如\"电影$1#电视剧$2\"），分类ID从分类链接中提取数字部分，只保留真实内容分类（电影/电视剧/综艺/动漫/纪录片/短剧等），不要包含首页/搜索/登录/我的/排行等非内容分类\n"
                + "第三步【生成分类页链接模板】：根据框架中分类链接的规律，生成\"分类url\"字段，必须包含 {cateId}（分类ID）和 {catePg}（页码）占位符，如 https://example.com/vodshow/id/{cateId}/page/{catePg}.html\n"
                + "第四步【给出真实分类页】：从框架中挑一个真实分类，给出它的完整分类页链接（用于下一步抓取影片列表），生成\"分类页链接\"字段\n"
                + "同时识别网站名称（站名），生成\"站名\"字段，从网站标题、logo 或页脚文字中提取真实站名（如\"茄子影视\"），不要用域名\n\n"
                + "首页 HTML 源码（截断）：\n" + html + "\n\n"
                + "只返回一个 JSON 对象，不要输出任何解释文字、不要用 markdown 代码块包裹：\n"
                + "{\n"
                + "  \"站名\": \"...\",\n"
                + "  \"分类url\": \"...\",\n"
                + "  \"分类\": \"...\",\n"
                + "  \"分类页链接\": \"...\"\n"
                + "}";
    }

    /** Step2: 分析分类页，生成影片列表截取规则并给出详情页链接 */
    private String buildPrompt2(String html, String url) {
        return "你是一个视频网站解析专家。下面是视频网站 " + url + " 的一个分类页 HTML 源码。\n\n"
                + "请完成以下任务：\n"
                + "1. 找出影片列表的截取规则：\n"
                + "   - \"数组\"：影片列表容器截取规则（如 class=\"hl-list-item&&</li> 或 p:ul[class*=\"vodlist\"] li）\n"
                + "   - \"标题\"：影片标题截取规则\n"
                + "   - \"图片\"：影片图片截取规则\n"
                + "   - \"链接\"：影片详情链接截取规则\n"
                + "2. 给出一个真实的影片详情页完整链接（用于下一步抓取），生成\"详情页链接\"字段\n\n"
                + "XBPQ 截取规则说明：\n"
                + "- 起始标记&&结束标记：截取两个标记之间的内容，如 class=\"hl-list-item&&</li>\n"
                + "- p:选择器->属性：jsoup 选择器，如 p:a->href 取链接，p:a->text 取文字\n"
                + "- 数组用 p:选择器 或 起始&&结束 获取列表容器\n\n"
                + "分类页 HTML 源码（截断）：\n" + html + "\n\n"
                + "如果 HTML 源码为空或无法获取，所有字段返回空字符串。\n"
                + "只返回一个 JSON 对象，不要输出任何解释文字、不要用 markdown 代码块包裹：\n"
                + "{\n"
                + "  \"数组\": \"...\",\n"
                + "  \"标题\": \"...\",\n"
                + "  \"图片\": \"...\",\n"
                + "  \"链接\": \"...\",\n"
                + "  \"详情页链接\": \"...\"\n"
                + "}";
    }

    /** Step3: 分析详情页，生成播放线路与播放链接截取规则 */
    private String buildPrompt3(String html, String url) {
        return "你是一个视频网站解析专家。下面是视频网站 " + url + " 的一个影片详情页 HTML 源码。\n\n"
                + "请完成以下任务：\n"
                + "1. 找出播放线路的截取规则：\n"
                + "   - \"线路数组\"：线路列表容器截取规则\n"
                + "   - \"线路标题\"：线路名称截取规则\n"
                + "2. 找出剧集列表的截取规则：\n"
                + "   - \"播放数组\"：剧集列表容器截取规则\n"
                + "   - \"播放列表\"：剧集列表截取规则\n"
                + "   - \"播放标题\"：剧集标题截取规则\n"
                + "   - \"播放链接\"：剧集播放地址截取规则\n"
                + "3. 找出剧情简介的截取规则：\"简介\"\n\n"
                + "XBPQ 截取规则说明：\n"
                + "- 起始标记&&结束标记：截取两个标记之间的内容\n"
                + "- p:选择器->属性：jsoup 选择器，如 p:a->href 取链接，p:a->text 取文字\n"
                + "- 数组用 p:选择器 或 起始&&结束 获取列表容器\n"
                + "- 播放列表结构：播放数组（剧集容器）→ 播放列表（剧集列表）→ 播放标题（剧集名）+ 播放链接（剧集地址）\n"
                + "- 常见播放列表 HTML 结构：详情页中通常有多个线路 tab（如 class=\"hl-tabs-btn\"），每个线路对应一个剧集列表（如 class=\"hl-plays-list\" 或 id=\"playlist_1\"），列表内是 <a> 标签，href 是播放地址，文字是集数名\n\n"
                + "详情页 HTML 源码（截断）：\n" + html + "\n\n"
                + "如果 HTML 源码为空或无法获取，所有字段返回空字符串。\n"
                + "只返回一个 JSON 对象，不要输出任何解释文字、不要用 markdown 代码块包裹：\n"
                + "{\n"
                + "  \"简介\": \"...\",\n"
                + "  \"线路数组\": \"...\",\n"
                + "  \"线路标题\": \"...\",\n"
                + "  \"播放数组\": \"...\",\n"
                + "  \"播放列表\": \"...\",\n"
                + "  \"播放标题\": \"...\",\n"
                + "  \"播放链接\": \"...\"\n"
                + "}";
    }

    /** 合并三步 AI 结果生成最终配置 */
    private String mergeConfig(String url, JsonObject step1, JsonObject step2, JsonObject step3) {
        JsonObject ext = new JsonObject();
        ext.addProperty("主页url", url);
        String cateUrl = getString(step1, "分类url");
        if (!TextUtils.isEmpty(cateUrl)) ext.addProperty("分类url", cateUrl);
        String cate = getString(step1, "分类");
        if (!TextUtils.isEmpty(cate)) ext.addProperty("分类", cate);
        String[] keys2 = {"数组", "标题", "图片", "链接"};
        for (String k : keys2) {
            String v = getString(step2, k);
            if (!TextUtils.isEmpty(v)) ext.addProperty(k, v);
        }
        String[] keys3 = {"简介", "线路数组", "线路标题", "播放数组", "播放列表", "播放标题", "播放链接"};
        for (String k : keys3) {
            String v = getString(step3, k);
            if (!TextUtils.isEmpty(v)) ext.addProperty(k, v);
        }
        JsonObject root = new JsonObject();
        String host = UrlUtil.host(url);
        root.addProperty("key", "xbpq_" + host.replace(".", "_"));
        String name = getString(step1, "站名");
        if (TextUtils.isEmpty(name)) name = host;
        root.addProperty("name", name);
        root.addProperty("type", 3);
        root.addProperty("api", API);
        root.add("ext", ext);
        return App.gson().toJson(root);
    }

    private String extractJson(String content) {
        if (content == null) return "";
        content = content.replaceAll("(?s) 思考.*?/思考", "").trim();
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) return content.substring(start, end + 1);
        return "";
    }
}

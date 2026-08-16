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

    /** 三步 AI 识别：抓首页 -> 分类页 -> 详情页，最终回调生成好的配置 JSON。
     *  分类优先从【网站框架】(首页导航链接) 直接解析，AI 仅补充框架类型/URL 模板/站名，保证分类准确。 */
    public void detect(String url, StatusListener listener, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                // Step0: 抓首页，先从框架提取真实分类（不依赖 AI）
                postStatus(listener, "正在抓取首页...");
                String homeHtml = fetchHtml(url);
                if (TextUtils.isEmpty(homeHtml)) {
                    postError(callback, "首页抓取失败");
                    return;
                }
                CategoryInfo framework = extractCategories(homeHtml, url);
                if (framework != null && !TextUtils.isEmpty(framework.cate)) {
                    postStatus(listener, "已从网站框架识别分类：" + framework.cate.replace("#", "、"));
                } else {
                    postStatus(listener, "未从框架识别到分类，改用 AI 分析...");
                }
                // Step1: AI 仅识别框架类型 + 生成分类 URL 模板 + 站名
                postStatus(listener, "AI 分析网站框架中...");
                JsonObject step1 = callAi(buildPrompt1(homeHtml, url));
                String cate = getString(step1, "分类");
                String cateUrlTpl = getString(step1, "分类url");
                // 优先使用框架解析出的分类，AI 仅在框架解析失败时使用
                if (framework != null && !TextUtils.isEmpty(framework.cate)) {
                    cate = framework.cate;
                    if (TextUtils.isEmpty(cateUrlTpl) && framework.sampleUrl != null) {
                        cateUrlTpl = buildCateUrlTemplate(framework.sampleUrl, url);
                    }
                }
                String realCateUrl = framework != null ? framework.sampleUrl : getString(step1, "分类页链接");
                if (TextUtils.isEmpty(realCateUrl)) realCateUrl = extractLink(homeHtml, new String[]{"vodshow", "vodtype", "list", "show", "type", "cateId"});
                // Step2: 抓分类页，AI 分析影片列表并选一个影片
                postStatus(listener, "正在抓取分类影片列表...");
                String cateHtml = TextUtils.isEmpty(realCateUrl) ? "" : fetchHtml(realCateUrl);
                postStatus(listener, "AI 从列表选影片中...");
                JsonObject step2 = callAi(buildPrompt2(cateHtml, realCateUrl));
                String detailUrl = getString(step2, "详情页链接");
                if (TextUtils.isEmpty(detailUrl)) detailUrl = extractLink(cateHtml, new String[]{"voddetail", "detail", "play", "vod", "id"});
                // Step3: 抓详情页，AI 分析播放线路与播放选集
                postStatus(listener, "正在抓取影片详情页...");
                String detailHtml = TextUtils.isEmpty(detailUrl) ? "" : fetchHtml(detailUrl);
                postStatus(listener, "AI 分析播放线路与选集...");
                JsonObject step3 = callAi(buildPrompt3(detailHtml, detailUrl));
                // 合并配置
                postStatus(listener, "正在生成配置...");
                String config = mergeConfig(url, cate, cateUrlTpl, getString(step1, "站名"), step2, step3);
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

    /** 框架分类提取结果 */
    private static class CategoryInfo {
        final String cate;       // 分类字段：分类名$ID#分类名$ID
        final String sampleUrl;  // 一个真实分类页链接（用于后续抓取）

        CategoryInfo(String cate, String sampleUrl) {
            this.cate = cate;
            this.sampleUrl = sampleUrl;
        }
    }

    /** 从首页 HTML 框架（导航/分类链接）直接解析真实内容分类，不依赖 AI。 */
    private CategoryInfo extractCategories(String html, String baseUrl) {
        if (TextUtils.isEmpty(html)) return null;
        java.util.regex.Pattern aPat = java.util.regex.Pattern.compile(
                "<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>([^<]{1,12})</a>",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = aPat.matcher(html);
        java.util.LinkedHashMap<String, String> map = new java.util.LinkedHashMap<>();
        java.util.regex.Pattern cateKw = java.util.regex.Pattern.compile(
                "vodshow|vodtype|type|list|show|cate|class|category|sort|column|fenlei",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        while (m.find()) {
            String href = m.group(1).trim();
            String name = m.group(2).trim();
            if (TextUtils.isEmpty(href) || TextUtils.isEmpty(name)) continue;
            if (!href.startsWith("/") && !href.startsWith("http")) continue;
            if (!cateKw.matcher(href).find()) continue;
            if (name.matches(".*(首页|主页|搜索|排行|热门|最新|推荐|我的|个人|登录|注册|关于|客服|片单|专题|高清|APP|下载|网址|微信|留言|友链|公告).*")) continue;
            if (!map.containsKey(name)) map.put(name, href);
        }
        if (map.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        String sampleUrl = null;
        int count = 0;
        for (java.util.Map.Entry<String, String> e : map.entrySet()) {
            String id = extractId(e.getValue());
            if (TextUtils.isEmpty(id)) continue;
            if (sb.length() > 0) sb.append("#");
            sb.append(e.getKey()).append("$").append(id);
            if (sampleUrl == null) sampleUrl = toAbsolute(e.getValue(), baseUrl);
            if (++count >= 14) break;
        }
        if (sb.length() == 0) return null;
        return new CategoryInfo(sb.toString(), sampleUrl);
    }

    /** 从分类链接中提取数字 ID（取路径中最后一个数字段） */
    private String extractId(String href) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(href);
        String last = "";
        while (m.find()) last = m.group(1);
        return last;
    }

    /** 相对路径转绝对路径 */
    private String toAbsolute(String href, String baseUrl) {
        if (href.startsWith("http")) return href;
        String host = UrlUtil.host(baseUrl);
        String scheme = baseUrl.startsWith("https") ? "https://" : "http://";
        if (href.startsWith("/")) return scheme + host + href;
        return scheme + host + "/" + href;
    }

    /** 从真实分类页链接推导分类 URL 模板：数字 id -> {cateId}，页码 -> {catePg} */
    private String buildCateUrlTemplate(String sampleUrl, String baseUrl) {
        if (TextUtils.isEmpty(sampleUrl)) return "";
        String full = sampleUrl.startsWith("http") ? sampleUrl : toAbsolute(sampleUrl, baseUrl);
        String tpl = full.replaceAll("/(\\d+)(?=/|\\.html|\\.htm|$)", "/{cateId}");
        tpl = tpl.replaceAll("(page|p|pg)/(\\d+)", "$1/{catePg}");
        tpl = tpl.replaceAll("([?&]p=)\\d+", "$1{catePg}");
        if (!tpl.contains("{cateId}")) return "";
        if (!tpl.contains("{catePg}")) {
            tpl += tpl.contains("?") ? "&p={catePg}" : "?p={catePg}";
        }
        return tpl;
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

    /** Step1: 仅识别网站框架类型、生成分类 URL 模板、识别站名（分类已由框架直接解析，不依赖 AI） */
    private String buildPrompt1(String html, String url) {
        return "你是一个视频网站解析专家。下面是视频网站 " + url + " 的首页 HTML 源码。\n\n"
                + "请完成以下任务：\n"
                + "1. 识别网站的【框架类型】，如苹果CMS、苹果CMS-V10、海洋CMS、max|maccms、自定义PHP影视站等，生成\"框架\"字段（简短说明即可）\n"
                + "2. 根据首页中分类链接的规律，生成\"分类url\"字段（分类页链接模板），必须包含 {cateId}（分类ID占位符）和 {catePg}（页码占位符），例如 https://example.com/vodshow/id/{cateId}/page/{catePg}.html 或 https://example.com/index.php/vod/type/id/{cateId}.html?p={catePg}\n"
                + "3. 识别网站真实名称（站名），从网站标题、logo、页脚文字中提取（如\"茄子影视\"），不要用域名；生成\"站名\"字段\n\n"
                + "注意：分类（电影/电视剧/动漫等）已由程序从网站框架自动提取，你不需要生成\"分类\"字段。\n\n"
                + "首页 HTML 源码（截断）：\n" + html + "\n\n"
                + "只返回一个 JSON 对象，不要输出任何解释文字、不要用 markdown 代码块包裹：\n"
                + "{\n"
                + "  \"框架\": \"...\",\n"
                + "  \"站名\": \"...\",\n"
                + "  \"分类url\": \"...\"\n"
                + "}";
    }

    /** Step2: 分析分类页，生成影片列表截取规则并给出详情页链接 */
    private String buildPrompt2(String html, String url) {
        return "你是一个视频网站解析专家，熟悉 XBPQ(小暴脾气) 爬虫框架的截取规则语法。下面是视频网站 " + url + " 的一个分类页 HTML 源码。\n\n"
                + "XBPQ 截取规则语法（必须严格遵守）：\n"
                + "1. 数组(列表容器)：用 jsoup 选择器 p:父选择器 获取每个影片节点，例如 p:ul.vod-list li 或 p:div[class*=vodlist] a\n"
                + "2. 字段：用 起始标记&&结束标记 截取文本，或用 p:选择器->属性 取值。例如：\n"
                + "   - 标题：p:a->text 或 <a&&>标题</a>\n"
                + "   - 图片：p:img->src 或 p:img->data-original\n"
                + "   - 链接：p:a->href\n"
                + "3. 规则要能从 HTML 中真实取到值，不要编造。\n\n"
                + "示例 HTML 片段：\n"
                + "<ul class=\"vod-list\">\n"
                + "  <li><a href=\"/voddetail/id/123.html\" title=\"测试影片\"><img src=\"/upload/1.jpg\"></a></li>\n"
                + "  <li><a href=\"/voddetail/id/124.html\" title=\"另一部\"><img src=\"/upload/2.jpg\"></a></li>\n"
                + "</ul>\n"
                + "对应正确规则应为：数组=p:ul.vod-list li，标题=p:a->text，图片=p:img->src，链接=p:a->href\n\n"
                + "请完成任务：\n"
                + "1. 给出影片列表的四条截取规则：数组、标题、图片、链接\n"
                + "2. 给出分类页中【第一个真实影片】的详情页完整链接（用于下一步抓取），生成\"详情页链接\"字段\n\n"
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
        return "你是一个视频网站解析专家，熟悉 XBPQ(小暴脾气) 爬虫框架的截取规则语法。下面是视频网站 " + url + " 的一个影片详情页 HTML 源码。\n\n"
                + "XBPQ 播放相关规则语法（必须严格遵守）：\n"
                + "1. 线路数组：获取所有线路 tab 的容器，如 p:div[class*=tabs]||线路名 或 起始&&结束。每个线路是一个播放源（如\"线路一\"\"备用\"）。\n"
                + "2. 线路标题：从线路 tab 中提取线路名称，如 p:span->text。\n"
                + "3. 播放数组：单个线路下剧集列表容器，如 p:div[class*=plays]||<a\n"
                + "4. 播放列表：剧集节点，如 p:a（每个 <a> 是一项）\n"
                + "5. 播放标题：剧集名，如 p:a->text\n"
                + "6. 播放链接：剧集播放地址，如 p:a->href\n"
                + "7. 简介：剧情简介文本，用 起始&&结束 截取，或 p:div[class*=desc]->text\n\n"
                + "示例 HTML 片段：\n"
                + "<div class=\"play-tabs\"><span class=\"on\">线路一</span><span>线路二</span></div>\n"
                + "<div class=\"plays-list\"><a href=\"/play/123-1.html\">第01集</a><a href=\"/play/123-2.html\">第02集</a></div>\n"
                + "对应正确规则：线路数组=p:div.play-tabs span，线路标题=p:span->text，播放数组=p:div.plays-list，播放列表=p:a，播放标题=p:a->text，播放链接=p:a->href\n\n"
                + "请完成任务：\n"
                + "1. 给出播放线路的截取规则：线路数组、线路标题\n"
                + "2. 给出剧集列表的截取规则：播放数组、播放列表、播放标题、播放链接\n"
                + "3. 给出剧情简介的截取规则：简介\n"
                + "注意：播放链接必须是可直接访问的真实播放地址（.html/.m3u8等），不要编造。\n\n"
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

    /** 合并结果生成最终配置。分类与分类URL优先来自框架解析，AI 仅补充站名与列表/播放规则。 */
    private String mergeConfig(String url, String cate, String cateUrlTpl, String siteName, JsonObject step2, JsonObject step3) {
        JsonObject ext = new JsonObject();
        ext.addProperty("主页url", url);
        if (!TextUtils.isEmpty(cateUrlTpl)) ext.addProperty("分类url", cateUrlTpl);
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
        String name = TextUtils.isEmpty(siteName) ? host : siteName;
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

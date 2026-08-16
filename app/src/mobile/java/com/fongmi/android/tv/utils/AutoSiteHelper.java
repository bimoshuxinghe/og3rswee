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
     *  分类、分类url、分类页链接均由 AI 从首页框架解析生成（框架正则仅作兜底）；
     *  播放链接按 XBPQ 文档处理「直链/解析/跳转」，相对链接自动补全域名前缀。 */
    public void detect(String url, StatusListener listener, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                // Step0: 抓首页
                postStatus(listener, "正在抓取首页...");
                String homeHtml = fetchHtml(url, 24000);
                if (TextUtils.isEmpty(homeHtml)) {
                    postError(callback, "首页抓取失败");
                    return;
                }
                // Step1: 由 AI 从首页框架解析分类 / 分类url(含分页) / 分类页链接 / 站名 / 框架类型
                postStatus(listener, "AI 解析网站分类与框架中...");
                JsonObject step1 = callAi(buildPrompt1(homeHtml, url));
                String cate = getString(step1, "分类");
                String cateUrlTpl = getString(step1, "分类url");
                // 框架正则仅作兜底（特殊 UI 无法靠正则提取时，优先使用 AI 结果）
                if (TextUtils.isEmpty(cate) || TextUtils.isEmpty(cateUrlTpl)) {
                    CategoryInfo framework = extractCategories(homeHtml, url);
                    if (framework != null) {
                        if (TextUtils.isEmpty(cate)) cate = framework.cate;
                        if (TextUtils.isEmpty(cateUrlTpl) && framework.sampleUrl != null) {
                            cateUrlTpl = buildCateUrlTemplate(framework.sampleUrl, url);
                        }
                    }
                }
                String realCateUrl = getString(step1, "分类页链接");
                if (TextUtils.isEmpty(realCateUrl)) realCateUrl = extractLink(homeHtml, new String[]{"vodshow", "vodtype", "list", "show", "type", "cateId"});
                // Step2: 抓分类页，AI 分析影片列表并选一个影片
                postStatus(listener, "正在抓取分类影片列表...");
                String cateHtml = TextUtils.isEmpty(realCateUrl) ? "" : fetchHtml(realCateUrl, 24000);
                postStatus(listener, "AI 从列表选影片中...");
                JsonObject step2 = callAi(buildPrompt2(cateHtml, realCateUrl));
                String detailUrl = getString(step2, "详情页链接");
                if (TextUtils.isEmpty(detailUrl)) detailUrl = extractLink(cateHtml, new String[]{"voddetail", "detail", "play", "vod", "id"});
                // Step3: 抓详情页，AI 分析播放线路与播放选集
                postStatus(listener, "正在抓取影片详情页...");
                String detailHtml = TextUtils.isEmpty(detailUrl) ? "" : fetchHtml(detailUrl, 40000);
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

    /** 直连抓取页面 HTML，按 maxChars 截断（不同步骤给不同上限，详情页需更大以包含播放区） */
    private String fetchHtml(String url, int maxChars) throws Exception {
        try (Response res = OkHttp.newCall(directClient(30000), url).execute()) {
            String html = res.body() == null ? "" : res.body().string();
            if (html.length() > maxChars) html = html.substring(0, maxChars);
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

    /** Step1: 由 AI 从首页框架解析分类、分类url(含分页)、分类页链接、站名、框架类型。
     *  只给【分类/URL 字段的格式规范】，不给具体站点示例，强制 AI 基于下方真实 HTML 推导。 */
    private String buildPrompt1(String html, String url) {
        return "你是一个视频网站解析专家，熟悉 XBPQ(小暴脾气) 爬虫框架。下面是视频网站 " + url + " 的首页 HTML 源码。\n\n"
                + "【重要】严禁套用任何你记忆中的固定模板或示例。你必须基于下方这段真实 HTML 源码本身来分析，所有结论都要能在源码里找到对应证据。\n\n"
                + "请完成以下任务，输出 JSON：\n"
                + "1. \"框架\"：识别网站的程序框架类型(简短说明即可，例如苹果CMS V10 / 海洋CMS / 其他PHP影视程序)。\n"
                + "2. \"分类\"：从首页真实分类导航(<a>链接)中提取全部影视分类，格式严格为【分类名$分类ID】，多个用 # 连接。\n"
                + "   - 分类ID必须是下一条\"分类url\"里 {cateId} 占位符所对应的真实取值(就是分类链接路径中代表该分类的那一段，通常是数字，也可能是拼音/英文 slug)。\n"
                + "   - 只取影视分类(电影/电视剧/动漫/综艺/短剧/纪录片等)，排除 首页/搜索/排行/热门/最新/推荐/我的/登录/注册/关于/客服 等非内容入口。\n"
                + "3. \"分类url\"：分类页链接【模板】。规则：去首页真实分类链接里挑一条，把其中代表分类的那一段替换为占位符 {cateId}，把其中代表页码的那一段替换为占位符 {catePg}。\n"
                + "   - 必须同时包含 {cateId} 和 {catePg}。\n"
                + "   - 模板里的【路径结构、文件后缀(.html/.htm 等)、分页写法(/page/2.html 还是 ?p=2 还是 ?pg=2)】必须和首页真实分类链接的规律完全一致，绝不允许臆造。\n"
                + "4. \"分类页链接\"：取上面第一个真实分类对应的、可直接访问的完整分类页 URL(用于下一步抓取影片列表)。\n"
                + "5. \"站名\"：从网页标题/logo/页脚文字提取网站真实名称，不要使用域名。\n\n"
                + "首页 HTML 源码(截断)：\n" + html + "\n\n"
                + "只返回一个 JSON 对象，不要输出任何解释文字、不要用 markdown 代码块包裹：\n"
                + "{\n"
                + "  \"框架\": \"...\",\n"
                + "  \"站名\": \"...\",\n"
                + "  \"分类\": \"电影$1#电视剧$2#动漫$3\",\n"
                + "  \"分类url\": \"真实观察到的分类链接规律，其中分类段换为{cateId}、页码段换为{catePg}\",\n"
                + "  \"分类页链接\": \"https://真实域名/真实分类路径\"\n"
                + "}";
    }

    /** Step2: 分析分类页，生成影片列表截取规则并给出详情页链接。
     *  只给【选择器文法格式】，不给具体站点 HTML 示例，强制 AI 基于下方真实源码推导选择器。 */
    private String buildPrompt2(String html, String url) {
        return "你是一个视频网站解析专家，熟悉 XBPQ(小暴脾气) 爬虫框架的截取规则语法。下面是视频网站 " + url + " 的一个分类页 HTML 源码。\n\n"
                + "【重要】严禁套用任何固定的示例或模板。你必须基于下方这段真实 HTML 源码本身来观察影片列表的真实结构，所有选择器都要能在这段源码里匹配到实际节点。\n\n"
                + "XBPQ 选择器【文法格式】(只讲规则，不提供站点示例)：\n"
                + "1. \"数组\"(列表容器)：用 p:父选择器 获取影片节点集合，例如 p:某个ul或div选择器下的 li/a。要选到包裹每一部影片的最小重复单元。\n"
                + "2. 字段取值用两种写法之一：\n"
                + "   - 属性抓取：p:选择器->属性名 (如 p:a->href 抓取链接、p:a->text 抓取文字、p:img->src 抓取图片地址)。\n"
                + "   - 文本截取：起始标记&&结束标记 (截取两个标记之间的文本)。\n"
                + "3. 关于图片：很多站点用了图片懒加载，真实图片地址可能不在 src 属性，而在 data-original / data-src / data-lazy-src 等 data-* 属性里——你要看源码里图片节点【实际】用哪个属性承载地址。\n"
                + "4. \"链接\"指向的是该影片的详情页地址，取它源码里的真实 href。\n\n"
                + "请完成任务：\n"
                + "1. 给出影片列表的四条截取规则：数组、标题、图片、链接(选择器必须精确匹配这段源码里的真实 class/id/标签)。\n"
                + "2. 给出分类页中【第一个真实影片】的详情页完整链接(用于下一步抓取)，生成\"详情页链接\"字段。\n\n"
                + "分类页 HTML 源码（截断）：\n" + html + "\n\n"
                + "如果 HTML 源码为空或无法获取，所有字段返回空字符串。\n"
                + "只返回一个 JSON 对象，不要输出任何解释文字、不要用 markdown 代码块包裹：\n"
                + "{\n"
                + "  \"数组\": \"p:真实观察到的最小重复单元选择器\",\n"
                + "  \"标题\": \"p:真实选择器->text\",\n"
                + "  \"图片\": \"p:真实图片选择器->真实承载地址的属性\",\n"
                + "  \"链接\": \"p:真实选择器->href\",\n"
                + "  \"详情页链接\": \"https://真实域名/真实详情路径\"\n"
                + "}";
    }

    /** Step3: 分析详情页，生成播放线路与播放选集截取规则（只给文法+直链/解析规则，不给站点示例）。 */
    private String buildPrompt3(String html, String url) {
        return "你是一个视频网站解析专家，熟悉 XBPQ(小暴脾气) 爬虫框架的截取规则语法。下面是视频网站 " + url + " 的一个影片详情页 HTML 源码。\n\n"
                + "【重要】严禁套用任何固定的示例或模板。你必须基于下方这段真实 HTML 源码本身来观察播放区域/线路tab/选集列表的真实结构，所有选择器都要能在这段源码里匹配到实际节点。\n\n"
                + "XBPQ 播放相关【文法格式】(只讲规则，不提供站点示例)：\n"
                + "1. 线路数组：获取所有线路(播放源) tab 的容器选择器，每个线路是一个播放源(如\"线路一\"\"备用\")。\n"
                + "2. 线路标题：从线路 tab 中提取线路名称的选择器(如 p:span->text)。\n"
                + "3. 播放数组：单个线路下剧集列表容器的选择器。\n"
                + "4. 播放列表：剧集节点的选择器(每一个 <a> 或 <li> 是一项)。\n"
                + "5. 播放标题：剧集名的选择器(如 p:a->text)。\n"
                + "6. 播放链接：剧集对应地址的选择器(如 p:a->href)。\n"
                + "7. 简介：剧情简介文本，用 起始&&结束 截取，或 p:简介容器选择器->text。\n\n"
                + "关于【播放链接】的重要规则(严格按 XBPQ 文档)：详情页/选集列表里看到的 href 绝大多数是【播放页】链接(形如 /play/xxx.html)，并不是最终可播放的直链。\n"
                + "   - 若你能在源码里直接找到 .m3u8 或 .mp4 真实直链，则\"播放链接\"填直链。\n"
                + "   - 若只能拿到播放页链接，则\"播放链接\"填播放页 href，并尽量补充\"解析\"字段：一个能把播放页转换为直链的解析接口(形如 https://解析域名/api?url= )，没有可用解析接口时\"解析\"留空。\n"
                + "   - 若链接为相对路径，请补全为完整 http(s) 绝对路径。\n\n"
                + "请完成任务：\n"
                + "1. 给出播放线路的截取规则：线路数组、线路标题。\n"
                + "2. 给出剧集列表的截取规则：播放数组、播放列表、播放标题、播放链接。\n"
                + "3. 给出剧情简介的截取规则：简介。\n"
                + "4. 给出\"解析\"字段(按上面规则判断，有则填解析接口，无则留空)。\n\n"
                + "详情页 HTML 源码(截断)：\n" + html + "\n\n"
                + "如果 HTML 源码为空或无法获取，所有字段返回空字符串。\n"
                + "只返回一个 JSON 对象，不要输出任何解释文字、不要用 markdown 代码块包裹：\n"
                + "{\n"
                + "  \"简介\": \"...\",\n"
                + "  \"线路数组\": \"p:真实线路容器选择器\",\n"
                + "  \"线路标题\": \"p:真实线路名选择器->text\",\n"
                + "  \"播放数组\": \"p:真实剧集容器选择器\",\n"
                + "  \"播放列表\": \"p:真实剧集节点选择器\",\n"
                + "  \"播放标题\": \"p:真实选择器->text\",\n"
                + "  \"播放链接\": \"p:真实选择器->href\",\n"
                + "  \"解析\": \"\"\n"
                + "}";
    }

    /** 合并结果生成最终配置。分类/分类url/分类页链接优先来自 AI，框架正则仅兜底；播放链接按文档补解析与域名前缀。 */
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
        // 链接为相对路径时补全域名前缀
        String link = getString(step2, "链接");
        if (!TextUtils.isEmpty(link) && !link.startsWith("http")) {
            ext.addProperty("链接前缀", schemeHost(url));
        }
        String[] keys3 = {"简介", "线路数组", "线路标题", "播放数组", "播放列表", "播放标题", "播放链接", "解析"};
        for (String k : keys3) {
            String v = getString(step3, k);
            if (!TextUtils.isEmpty(v)) ext.addProperty(k, v);
        }
        // 播放链接为相对路径时补全域名前缀
        String playLink = getString(step3, "播放链接");
        if (!TextUtils.isEmpty(playLink) && !playLink.startsWith("http")) {
            ext.addProperty("播放链接前缀", schemeHost(url));
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

    /** 取 url 的 scheme+host，用于给相对链接补全绝对路径 */
    private String schemeHost(String url) {
        String scheme = url.startsWith("https") ? "https://" : "http://";
        return scheme + UrlUtil.host(url);
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

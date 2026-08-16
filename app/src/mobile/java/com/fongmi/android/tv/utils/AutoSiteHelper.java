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
     *  给【抽象格式模板】（占位符描述），让 AI 知道合法输出结构但不抄具体 class。 */
    private String buildPrompt1(String html, String url) {
        return "你是视频网站解析专家，熟悉 XBPQ 爬虫框架。下面是 " + url + " 的首页 HTML 源码。\n\n"
                + "【输出规则——必须严格遵守】\n"
                + "你的每个字段值都必须能在下方 HTML 源码里找到证据。禁止编造不存在的 class 名、id 名或标签。\n"
                + "合法的 jsoup 选择器格式只有以下几种，超出这些格式的输出一律视为错误：\n"
                + "  - 标签选择器：p:div, p:li, p:a, p:span, p:img, p:ul 等\n"
                + "  - class 选择器：p:div.类名, p:li.类名, p:a.类名（类名必须是源码中真实存在的）\n"
                + "  - 属性选择器：p:选择器->属性名（属性名只能是 src/href/text/title/data-original/data-src 之一）\n"
                + "  - 文本截取：起始文本&&结束文本\n"
                + "绝对不允许出现类似 p:lip.xxx 或任何你在源码中找不到的选择器。\n\n"
                + "【任务——输出 JSON】\n"
                + "1. \"框架\"：程序框架类型(如 苹果CMS V10 / 海洋CMS / 其他PHP影视站)。\n"
                + "2. \"分类\"：从首页 <a> 导航链接提取全部影视分类。格式：【分类名$分类ID】，# 分隔多个。\n"
                + "   分类ID = 分类链接路径中代表该分类的段(通常是数字)。排除 首页/搜索/登录/注册 等非内容入口。\n"
                + "3. \"分类url\"：挑一条真实分类链接，把分类段换为 {cateId}、页码段换为 {catePg}。路径结构和后缀必须与真实链接一致。\n"
                + "4. \"分类页链接\"：第一个分类的完整可访问 URL。\n"
                + "5. \"站名\"：从标题/logo/页脚提取的真实名称，不用域名。\n\n"
                + "首页 HTML 源码：\n" + html + "\n\n"
                + "只返回 JSON，不要解释文字、不要 markdown：\n"
                + "{\n"
                + "  \"框架\": \"...\",\n"
                + "  \"站名\": \"...\",\n"
                + "  \"分类\": \"电影$1#电视剧$2\",\n"
                + "  \"分类url\": \"观察到的规律，分类段→{cateId} 页码段→{catePg}\",\n"
                + "  \"分类页链接\": \"https://完整URL\"\n"
                + "}";
    }

    /** Step2: 分析分类页，生成影片列表截取规则并给出详情页链接。
     *  给【合法选择器格式清单 + 无效示例】，防止 AI 瞎编选择器。 */
    private String buildPrompt2(String html, String url) {
        return "你是视频网站解析专家，熟悉 XBPQ 爬虫框架。下面是 " + url + " 的分类页 HTML 源码。\n\n"
                + "【合法选择器格式——只允许以下写法】\n"
                + "  ✅ p:div.真实类名        —— 用 class 定位容器\n"
                + "  ✅ p:li.真实类名         —— 用 class 定位列表项\n"
                + "  ✅ p:a->text             —— 取 <a> 的文字内容\n"
                + "  ✅ p:a->href            —— 取 <a> 的链接地址\n"
                + "  ✅ p:img->src           —— 取 <img> 的 src 属性\n"
                + "  ✅ p:img->data-original —— 取懒加载图片的真实地址(若源码中 img 有此属性)\n"
                + "  ✅ p:img->data-src      —— 同上(另一种常见属性名)\n"
                + "  ✅ 起始文本&&结束文本     —— 截取两段文字之间的内容\n"
                + "  ❌ p:lip.xxx / p:namea   —— 这种格式是错误的！不存在这种选择器\n"
                + "  ❌ 编造源码中没有的类名  —— 绝对禁止！\n\n"
                + "【重要】你必须先在下方 HTML 中找到影片列表区域，观察真实的标签、class 名、属性名，然后用上面的合法格式写出选择器。\n"
                + "如果找不到明确的列表结构，所有字段返回空字符串。\n\n"
                + "【任务——输出 JSON】\n"
                + "1. \"数组\"：包裹每部影片的最小重复单元的选择器(如 p:div.某个类 或 p:li.某个类)。\n"
                + "2. \"标题\"：在数组单元内取影片名的选择器(通常 p:a->text)。\n"
                + "3. \"图片\"：在数组单元内取海报图的选择器(注意看 img 标签实际用 src 还是 data-original)。\n"
                + "4. \"链接\"：在数组单元内取详情页 href 的选择器(通常 p:a->href)。\n"
                + "5. \"详情页链接\"：页面中第一个影片的完整详情页 URL。\n\n"
                + "分类页 HTML 源码：\n" + html + "\n\n"
                + (html.isEmpty() ? "HTML 为空，所有字段返回空字符串。\n" : "")
                + "只返回 JSON，不要解释、不要 markdown：\n"
                + "{\n"
                + "  \"数组\": \"p:你在源码中看到的真实容器选择器\",\n"
                + "  \"标题\": \"p:真实选择器->text\",\n"
                + "  \"图片\": \"p:真实选择器->真实属性名\",\n"
                + "  \"链接\": \"p:真实选择器->href\",\n"
                + "  \"详情页链接\": \"https://完整URL\"\n"
                + "}";
    }

    /** Step3: 分析详情页，生成播放线路与播放选集截取规则。
     *  给【合法选择器格式清单 + 无效示例】，防止 AI 瞎编选择器。 */
    private String buildPrompt3(String html, String url) {
        return "你是视频网站解析专家，熟悉 XBPQ 爬虫框架。下面是 " + url + " 的详情页 HTML 源码。\n\n"
                + "【合法选择器格式——只允许以下写法】\n"
                + "  ✅ p:div.真实类名        —— 定位容器\n"
                + "  ✅ p:ul.真实类名 / p:li.真实类名 —— 列表相关\n"
                + "  ✅ p:span->text          —— 取文字\n"
                + "  ✅ p:a->text / p:a->href —— 取剧集名/链接\n"
                + "  ✅ 起始文本&&结束文本     —— 截取文本\n"
                + "  ❌ p:lip.xxx / 编造类名   —— 错误！禁止！\n\n"
                + "【重要】先在 HTML 中找到「线路/播放/选集」区域，观察真实的标签和 class 名，再用上面的格式写出选择器。\n"
                + "找不到则返回空字符串。\n\n"
                + "【播放链接规则】\n"
                + "  - 选集列表中的 href 通常是播放页(如 /play/xxx.html)，不是直链。\n"
                + "  - 若源码中有 .m3u8/.mp4 直链，直接用。\n"
                + "  - 若只有播放页 href，填该 href 并在\"解析\"字段给一个解析接口(如 https://xxx/api?url= )，无则留空。\n"
                + "  - 相对路径补全为完整 URL。\n\n"
                + "【任务——输出 JSON】\n"
                + "1. \"线路数组\"：所有线路 tab 的容器选择器。\n"
                + "2. \"线路标题\"：从 tab 取线路名的选择器。\n"
                + "3. \"播放数组\"：单线路下剧集列表容器。\n"
                + "4. \"播放列表\"：每个剧集节点(通常是 <a>)。\n"
                + "5. \"播放标题\"：剧集名称。\n"
                + "6. \"播放链接\"：剧集地址。\n"
                + "7. \"简介\"：剧情简介。\n"
                + "8. \"解析\"：解析接口(有则填，无则空)。\n\n"
                + "详情页 HTML 源码：\n" + html + "\n\n"
                + (html.isEmpty() ? "HTML 为空，所有字段返回空字符串。\n" : "")
                + "只返回 JSON，不要解释、不要 markdown：\n"
                + "{\n"
                + "  \"简介\": \"...\",\n"
                + "  \"线路数组\": \"p:源码中真实的线路容器\",\n"
                + "  \"线路标题\": \"p:真实选择器->text\",\n"
                + "  \"播放数组\": \"p:源码中真实的剧集容器\",\n"
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

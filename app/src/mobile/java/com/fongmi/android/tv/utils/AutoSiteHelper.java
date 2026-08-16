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
     *  分类名/ID 与框架类型由 AI 识别；分类url 模板则【程序实测抓取真实分类页】后从真实网址+翻页链接推导，
     *  不再让 AI 在首页 HTML 里猜模板（更准，且首页导航是 JS 渲染时也能命中）；
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
                // Step1: AI 识别框架类型 + 分类(名称$ID) + 一个真实分类页链接。
                // 注意：分类url 模板【不靠 AI 在首页猜】，改由程序“真的去点一下分类页”实证推导。
                postStatus(listener, "AI 识别网站框架与分类中...");
                JsonObject step1 = callAi(buildPrompt1(homeHtml, url));
                String cate = getString(step1, "分类");
                String siteName = getString(step1, "站名");
                String framework = getString(step1, "框架");
                // 分类兜底：框架正则从首页取（仍作为兜底）
                if (TextUtils.isEmpty(cate)) {
                    CategoryInfo fw = extractCategories(homeHtml, url);
                    if (fw != null) cate = fw.cate;
                }
                // 找一个真实分类页链接（“浏览器里点一下某个分类”）：优先用 AI 给的，否则扫描首页导航
                String sampleCateUrl = getString(step1, "分类页链接");
                if (TextUtils.isEmpty(sampleCateUrl)) sampleCateUrl = findCategoryLink(homeHtml, url);
                // 实证推导分类url模板：真的去抓这个分类页，读它的真实网址 + 翻页链接，绝不靠猜
                String realCateUrl = "";
                String cateUrlTpl = "";
                if (!TextUtils.isEmpty(sampleCateUrl)) {
                    String[] fetched = fetchWithUrl(sampleCateUrl, url);
                    realCateUrl = fetched[0];
                    cateUrlTpl = deriveCateUrl(realCateUrl, fetched[1], framework);
                }
                // 兜底1：框架正则若拿到过真实分类链接，就按它推导
                if (TextUtils.isEmpty(cateUrlTpl)) {
                    CategoryInfo fw = extractCategories(homeHtml, url);
                    if (fw != null && fw.sampleUrl != null) {
                        String[] f = fetchWithUrl(fw.sampleUrl, url);
                        cateUrlTpl = deriveCateUrl(f[0], f[1], framework);
                        if (TextUtils.isEmpty(realCateUrl)) realCateUrl = f[0];
                    }
                }
                // 兜底2：按框架已知规律拼（苹果CMS 等标准站，即使首页导航是 JS 渲染也能命中）
                if (TextUtils.isEmpty(cateUrlTpl)) cateUrlTpl = frameworkCateUrl(framework, cate, url);
                // 兜底3：若仍无真实分类页可抓，用模板 + 第一个分类ID 拼一个给 Step2 用
                if (TextUtils.isEmpty(realCateUrl) && !TextUtils.isEmpty(cateUrlTpl) && !TextUtils.isEmpty(cate)) {
                    String[] parts = cate.split("#")[0].split("\\$");
                    if (parts.length >= 2) {
                        realCateUrl = cateUrlTpl.replace("{cateId}", parts[1]).replace("{catePg}", "1");
                    }
                }
                if (TextUtils.isEmpty(realCateUrl)) realCateUrl = url;
                // Step2: 抓分类页，AI 分析影片列表并选一个影片
                postStatus(listener, "正在抓取分类影片列表...");
                String cateHtml = TextUtils.isEmpty(realCateUrl) ? "" : fetchHtml(realCateUrl, 24000);
                // 兜底：若分类页内容为空或过短（分类url可能不对），退回用首页 HTML 给 Step2 分析
                if (cateHtml.length() < 200) {
                    postStatus(listener, "分类页无数据，使用首页分析...");
                    cateHtml = homeHtml;
                    realCateUrl = url;
                }
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
                String config = mergeConfig(url, cate, cateUrlTpl, siteName, step2, step3);
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

    /** 从首页 HTML 扫描出一个真实分类链接（含分类关键词 + 数字路径，且不是 推荐/首页 等非内容入口） */
    private String findCategoryLink(String html, String baseUrl) {
        if (TextUtils.isEmpty(html)) return "";
        java.util.regex.Pattern aPat = java.util.regex.Pattern.compile(
                "<a\\b[^>]*href=[\"']([^\"']+)[\"'][^>]*>([^<]{1,12})</a>",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = aPat.matcher(html);
        java.util.regex.Pattern kw = java.util.regex.Pattern.compile(
                "vodshow|vodtype|type|list|show|cate|cat|channel|fenlei|sort|column|class|category",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern bad = java.util.regex.Pattern.compile(
                "首页|主页|搜索|排行|热门|最新|推荐|我的|个人|登录|注册|关于|客服|片单|专题|高清|app|下载|网址|微信|留言|友链|公告",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        while (m.find()) {
            String href = m.group(1).trim();
            String name = m.group(2).trim();
            if (TextUtils.isEmpty(href) || (!href.startsWith("/") && !href.startsWith("http"))) continue;
            if (!kw.matcher(href).find()) continue;
            if (bad.matcher(name).find()) continue;
            if (!extractId(href).isEmpty()) return href;
        }
        return "";
    }

    /** 抓取页面并返回 [最终URL, HTML]（跟随重定向，拿到浏览器里真正看到的网址） */
    private String[] fetchWithUrl(String url, String baseUrl) {
        String abs = toAbsolute(url, baseUrl);
        String[] r = {abs, ""};
        if (TextUtils.isEmpty(url)) return r;
        try {
            Request req = new Request.Builder().url(abs).build();
            try (Response res = directClient(30000).newCall(req).execute()) {
                r[0] = res.request().url().toString();
                r[1] = res.body() == null ? "" : res.body().string();
            }
        } catch (Exception ignored) {
        }
        return r;
    }

    /** 实证推导分类url模板：基于真实分类页网址 + 其翻页链接，绝不靠猜 */
    private String deriveCateUrl(String realUrl, String html, String framework) {
        if (TextUtils.isEmpty(realUrl)) return "";
        String tpl = replaceCateId(realUrl);
        if (TextUtils.isEmpty(tpl)) return "";
        return addPagePlaceholder(tpl, html, realUrl);
    }

    /** 把真实分类页网址里的分类ID段替换为 {cateId} */
    private String replaceCateId(String url) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(cat|type|id|vodshow|list|show|channel|fenlei|sort|cate|class|column)/(\\d+)",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = p.matcher(url);
        if (m.find()) {
            return url.substring(0, m.start(2)) + "{cateId}" + url.substring(m.end(2));
        }
        java.util.regex.Pattern p2 = java.util.regex.Pattern.compile("/(\\d+)(?=\\.html|\\.htm|$)");
        java.util.regex.Matcher m2 = p2.matcher(url);
        String last = null;
        int s = -1, e = -1;
        while (m2.find()) {
            last = m2.group(1);
            s = m2.start(1);
            e = m2.end(1);
        }
        if (last != null) return url.substring(0, s) + "{cateId}" + url.substring(e);
        return "";
    }

    /** 从翻页链接推导 {catePg}：找分页区里“第2页”之类的链接，对比当前URL定位页码位置 */
    private String addPagePlaceholder(String tpl, String html, String realUrl) {
        String curNum = extractLastNum(realUrl);
        if (!TextUtils.isEmpty(html)) {
            java.util.regex.Pattern hp = java.util.regex.Pattern.compile("href=[\"']([^\"']+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher hm = hp.matcher(html);
            while (hm.find()) {
                String href = hm.group(1);
                if (href.matches(".*\\.(css|js|png|jpg|jpeg|gif|ico|svg|webp|woff|woff2|ttf|eot)(\\?.*)?$")) continue;
                String num = extractLastNum(href);
                if (num.isEmpty() || num.equals(curNum)) continue;
                String absHref = toAbsolute(href, realUrl);
                String pg = absHref.replaceFirst(java.util.regex.Pattern.quote(num) + "(?=\\.html|\\.htm|$|/)", "{catePg}");
                pg = replaceCateId(pg);
                if (pg.contains("{catePg}") && pg.contains("{cateId}")) return pg;
            }
        }
        return ensurePage(tpl);
    }

    private String extractLastNum(String url) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(url == null ? "" : url);
        String last = "";
        while (m.find()) last = m.group(1);
        return last;
    }

    /** 没有翻页线索时，按常见写法补 {catePg} */
    private String ensurePage(String tpl) {
        if (tpl.contains("{catePg}")) return tpl;
        // 苹果CMS V10 常见：/cat/{cateId}.html 翻页为 /cat/{cateId}-{catePg}.html
        if (tpl.matches(".*/cat/\\{cateId\\}\\.html.*")) {
            return tpl.replace("/{cateId}.html", "/{cateId}-{catePg}.html");
        }
        return tpl + (tpl.contains("?") ? "&p={catePg}" : "?p={catePg}");
    }

    /** 终极兜底：按框架已知规律拼分类url（仅当首页/AI 都拿不到真实分类链接时） */
    private String frameworkCateUrl(String framework, String cate, String url) {
        if (TextUtils.isEmpty(cate)) return "";
        String host = UrlUtil.host(url);
        String scheme = url.startsWith("https") ? "https://" : "http://";
        String base = scheme + host;
        if (!TextUtils.isEmpty(framework) && (framework.contains("海洋") || framework.toLowerCase().contains("seacms"))) {
            return ensurePage(base + "/index.php/vod/type/id/{cateId}.html");
        }
        // 默认按 苹果CMS V10：/cat/{cateId}.html（绝大多数国内影视站为此框架）
        return ensurePage(base + "/cat/{cateId}.html");
    }

    private JsonObject callAi(String prompt) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", Setting.getAiModel());
        body.addProperty("temperature", 0.1);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", prompt);
        com.google.gson.JsonArray messages = new com.google.gson.JsonArray();
        messages.add(msg);
        body.add("messages", messages);
        RequestBody requestBody = RequestBody.create(body.toString(), JSON);
        Request request = new Request.Builder()
                .url(Setting.getAiUrl())
                .addHeader("Authorization", "Bearer " + Setting.getAiKey())
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build();
        try (Response response = directClient(60000).newCall(request).execute()) {
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

    /** Step1: AI 只负责识别【框架类型 + 分类(名称$ID) + 一个真实分类页链接】。
     *  分类url 模板不在这里生成——改由程序 fetch 真实分类页后实证推导（更准确，见 deriveCateUrl）。
     *  内嵌 XBPQ(小暴脾气) 完整字段规范，确保 AI 输出严格符合框架要求。 */
    private String buildPrompt1(String html, String url) {
        return "你是视频网站解析专家，必须严格按照【XBPQ(小暴脾气)爬虫框架】的规则输出。\n\n"
                + "===== XBPQ 框架核心规则 =====\n"
                + "【截取方式——只允许以下四种】\n"
                + "(1) p: (jsoup选择器，最常用)\n"
                + "   - p:div.classname → 定位容器\n"
                + "   - p:a->text → 取<a>文字\n"
                + "   - p:a->href → 取<a>链接\n"
                + "   - p:img->src / p:img->data-original → 取图片地址\n"
                + "   - 支持通配符*: p:ul[class*=\"v_list\"] li\n"
                + "(2) && (正则匹配): 起始文本&&结束文本，截取中间内容\n"
                + "(3) j: (json路径): j:data.list[1].name\n"
                + "(4) 分割(分割符): 用split分割成数组，如 &&分割(#)\n\n"
                + "【关键字段格式——必须严格遵守】\n"
                + "- \"分类\": 格式为 分类名$分类ID#分类名$分类ID，用$分隔名和ID，#分隔不同分类\n"
                + "  示例: 电影$1#电视剧$2#动漫$3#综艺$4\n"
                + "  分类ID = 该分类链接路径中代表分类的那一段数字\n"
                + "- \"分类url\": 必须包含 {cateId} 和 {catePg} 占位符\n"
                + "  示例: https://xxx.com/vodshow/{cateId}--------{catePg}---.html\n"
                + "         https://xxx.com/cat/{cateId}-{catePg}.html\n"
                + "         https://xxx.com/type/{cateId}/page/{catePg}\n"
                + "- \"数组\": 包裹每部影片的最小重复单元选择器\n"
                + "- \"标题\"/\"图片\"/\"链接\": 在数组单元内的选择器\n"
                + "- \"线路数组\"/\"播放数组\"/\"播放列表\"/\"播放标题\"/\"播放链接\": 详情页播放相关\n"
                + "- \"解析\": 解析接口URL，有则填无则空\n\n"
                + "【绝对禁止】\n"
                + "- 编造源码中不存在的class名或标签\n"
                + "- 使用 p:lip.xxx / p:namea 这类无效格式\n"
                + "- 把 首页/搜索/登录/推荐/APP下载 当作分类\n\n"
                + "===== 任务 =====\n"
                + "分析下面视频网站首页 HTML，输出 JSON：\n"
                + "1. \"框架\": 网站程序框架(苹果CMS V10/苹果CMS/海洋CMS/其他PHP影视站/未知)\n"
                + "2. \"分类\": 全部影视内容分类，格式 分类名$分类ID#分类名$分类ID\n"
                + "   - 从首页导航<a>链接提取，href含 vodshow/type/list/show/cate/cat/channel/fenlei 等\n"
                + "   - 若首页导航是JS动态加载，根据框架常见分类给出合理值\n"
                + "3. \"分类页链接\": 任一真实分类的完整URL(程序会去抓取验证)，找不到则留空\n"
                + "4. \"站名\": 网站真实名称(从标题/logo提取，不要用域名)\n\n"
                + "网站: " + url + "\n\n"
                + "首页HTML:\n" + html + "\n\n"
                + "只返回JSON，不要解释不要markdown:\n"
                + "{\"框架\":\"苹果CMS V10\",\"站名\":\"示例影视\",\"分类\":\"电影$1#电视剧$2#动漫$3\",\"分类页链接\":\"https://...\"}";
    }

    /** Step2: 分析分类页，生成影片列表截取规则并给出详情页链接。
     *  内嵌 XBPQ 规范，确保选择器严格符合框架要求。 */
    private String buildPrompt2(String html, String url) {
        return "你是视频网站解析专家，必须严格按照【XBPQ(小暴脾气)爬虫框架】规则输出。\n\n"
                + "===== XBPQ 框架核心规则 =====\n"
                + "【p: jsoup选择器——最常用的截取方式】\n"
                + "  ✅ p:div.my-class          → 用class定位容器(必须用源码中真实class)\n"
                + "  ✅ p:li.item               → 列表项\n"
                + "  ✅ p:a->text               → 取<a>文字内容\n"
                + "  ✅ p:a->href              → 取<a>链接地址\n"
                + "  ✅ p:img->src             → 取img的src属性\n"
                + "  ✅ p:img->data-original    → 懒加载图片真实地址(若源码有此属性)\n"
                + "  ✅ p:img->data-src         → 另一种懒加载属性名\n"
                + "  ✅ 起始文本&&结束文本      → 正则截取中间内容\n"
                + "  ❌ p:lip.xxx / p:namea     → 无效格式！禁止！\n"
                + "  ❌ 编造源码中不存在的class → 绝对禁止！\n"
                + "  支持*: p:ul[class*=\"v_list\"] li (class包含v_list的ul下所有li)\n\n"
                + "【关键字段说明】\n"
                + "- \"数组\": 包裹每部影片的最小重复单元(如 p:div.xxx 或 p:li.xxx)\n"
                + "- \"标题\": 数组内取片名的选择器(通常 p:a->text)\n"
                + "- \"图片\": 数组内取海报图的选择器(注意看img实际用src还是data-original)\n"
                + "- \"链接\": 数组内取详情页href的选择器(通常 p:a->href)\n"
                + "- 相对路径会自动补全域名前缀\n\n"
                + "===== 任务 =====\n"
                + "分析下面分类页HTML，先找到影片列表区域，观察真实标签和class名，再输出JSON:\n"
                + "1. \"数组\": 影片容器选择器(用源码中真实的class)\n"
                + "2. \"标题\": 片名选择器\n"
                + "3. \"图片\": 海报图选择器+属性名\n"
                + "4. \"链接\": 详情页href选择器\n"
                + "5. \"详情页链接\": 页面中第一个影片的完整详情URL\n\n"
                + "页面: " + url + "\n\n"
                + "分类页HTML:\n" + html + "\n\n"
                + (html.isEmpty() ? "HTML为空，所有字段返回空字符串。\n" : "")
                + "只返回JSON不要解释不要markdown:\n"
                + "{\"数组\":\"p:真实class\",\"标题\":\"p:a->text\",\"图片\":\"p:img->src\",\"链接\":\"p:a->href\",\"详情页链接\":\"https://...\"}";
    }

    /** Step3: 分析详情页，生成播放线路与播放选集截取规则。
     *  内嵌 XBPQ 规范，确保播放相关字段严格符合框架要求。 */
    private String buildPrompt3(String html, String url) {
        return "你是视频网站解析专家，必须严格按照【XBPQ(小暴脾气)爬虫框架】规则输出。\n\n"
                + "===== XBPQ 框架核心规则 =====\n"
                + "【p: jsoup选择器——最常用的截取方式】\n"
                + "  ✅ p:div.my-class / p:ul.my-class / p:li.my-class → 用真实class定位\n"
                + "  ✅ p:span->text / p:a->text / p:a->href → 取文字/链接\n"
                + "  ✅ 起始文本&&结束文本 → 正则截取\n"
                + "  ❌ p:lip.xxx / 编造class → 禁止！\n\n"
                + "【播放相关字段说明】\n"
                + "- \"线路数组\": 所有播放线路tab的容器选择器\n"
                + "- \"线路标题\": 从tab取线路名(如 线路1/腾讯/优酷)\n"
                + "- \"播放数组\": 单线路下剧集列表容器\n"
                + "- \"播放列表\": 每个剧集节点(通常是<a>标签)\n"
                + "- \"播放标题\": 剧集名称(如 第1集/第01集)\n"
                + "- \"播放链接\": 剧集地址(通常是/play/xxx.html，不是直链)\n"
                + "- \"简介\": 剧情简介文本\n"
                + "- \"解析\": 解析接口URL(有则填无则空)，用于把播放页转成可嗅探地址\n\n"
                + "【播放链接处理规则】\n"
                + "- 选集href通常是播放页(如/play/xxx.html)，不是m3u8/mp4直链\n"
                + "- 若源码中有.m3u8/.mp4直链可直接用\n"
                + "- 若只有播放页href，填该href并在\"解析\"字段给一个解析接口\n"
                + "- 相对路径会自动补全域名前缀\n\n"
                + "===== 任务 =====\n"
                + "分析下面详情页HTML，先找到「线路/播放/选集」区域，观察真实标签和class名:\n"
                + "1. \"线路数组\": 线路容器选择器\n"
                + "2. \"线路标题\": 线路名选择器\n"
                + "3. \"播放数组\": 剧集容器选择器\n"
                + "4. \"播放列表\": 剧集节点选择器\n"
                + "5. \"播放标题\": 剧集名选择器\n"
                + "6. \"播放链接\": 剧集地址选择器\n"
                + "7. \"简介\": 剧情简介\n"
                + "8. \"解析\": 解析接口URL\n\n"
                + "页面: " + url + "\n\n"
                + "详情页HTML:\n" + html + "\n\n"
                + (html.isEmpty() ? "HTML为空，所有字段返回空字符串。\n" : "")
                + "只返回JSON不要解释不要markdown:\n"
                + "{\"简介\":\"...\",\"线路数组\":\"p:真实\",\"线路标题\":\"p:a->text\",\"播放数组\":\"p:真实\",\"播放列表\":\"p:a\",\"播放标题\":\"p:a->text\",\"播放链接\":\"p:a->href\",\"解析\":\"\"}";
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

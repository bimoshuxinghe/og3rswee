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
     *  分类url 是【含 {cateId}/{catePg} 占位符的模板】，分类字段是“名称$ID#...”，两者同时存在
     *  （参考小暴脾气官方写法：茄子影视/小友影视/冰河影视 ext 既有"分类url"模板又有"分类"字段）；
     *  XBPQ 运行时会用“分类”字段里的真实ID替换模板中的 {cateId} 拼出每个分类的列表页；
     *  影片列表规则(数组/标题/图片/链接)由 AI 分析一个真实分类页得出，播放规则由 AI 分析详情页得出；
     *  播放链接按 XBPQ 文档处理「直链/解析/跳转」，相对链接自动补全域名前缀。 */
    public void detect(String url, StatusListener listener, Callback callback) {
        EXECUTOR.execute(() -> {
            try {
                // Step0: 抓首页（截断上限提高到150KB：很多CMS站的导航在页面底部，如cd-zj.com导航在第139KB处）
                postStatus(listener, "正在抓取首页...");
                String homeHtml = fetchHtml(url, 150000);
                if (TextUtils.isEmpty(homeHtml)) {
                    postError(callback, "首页抓取失败");
                    return;
                }
                // JS 渲染站检测：部分站点首页是空壳 + JS 重定向(window.location.href)，静态抓取拿不到内容
                if (homeHtml.length() < 800 && homeHtml.contains("window.location")) {
                    postError(callback, "该站点需 JS 渲染（首页为 JS 重定向空壳），当前静态抓取无法识别，请换用支持 JS 渲染的站点");
                    return;
                }
                // Step1: AI 识别【站名 + 分类(名称$ID) + 分类url模板】。
                // 关键：分类url 必须是【含 {cateId} 占位符的模板】，XBPQ 运行时会用“分类”字段里的真实ID替换它拼出分类页；
                //  分类url 模板由 AI【直接阅读首页HTML里的真实分类链接】推导——把链接里的分类ID段替换为 {cateId}，
                //  前缀(/vod /vodshow /list /type /id 等各站不同)也必须取自真实 href，【程序绝不按框架死拼】。
                //  参考小暴脾气官方写法：茄子影视/小友影视/冰河影视等 ext 既有"分类url"模板(含{cateId}/{catePg})又有"分类"字段。
                postStatus(listener, "AI 识别网站框架与分类中...");
                JsonObject step1 = callAi(buildPrompt1(homeHtml, url));
                String siteName = getString(step1, "站名");
                // 分类字段（名称$ID#名称$ID）：优先用 AI，兜底用框架正则从首页导航提取
                String cate = getString(step1, "分类");
                if (TextUtils.isEmpty(cate)) {
                    CategoryInfo fw = extractCategories(homeHtml, url);
                    if (fw != null) cate = fw.cate;
                }
                // 分类url 模板：优先用 AI 从真实分类链接推导（含 {cateId}/{catePg}），
                //  兜底从真实链接推导（仅替换首个数字段，不依赖任何框架关键词死拼）
                String cateUrlTpl = getString(step1, "分类url");
                if (TextUtils.isEmpty(cateUrlTpl) || !cateUrlTpl.contains("{cateId}")) {
                    CategoryInfo fw = extractCategories(homeHtml, url);
                    if (fw != null) cateUrlTpl = deriveCateUrlTpl(fw.sampleUrl);
                }
                // 兜底：若首页导航抓不到任何真实分类链接，则让 XBPQ 自动从首页解析（参考悟空/冠峰：仅分类url=首页）
                if (TextUtils.isEmpty(cateUrlTpl)) cateUrlTpl = schemeHost(url);
                // 用分类url模板 + 第一个分类ID 拼出真实分类页 URL，供 Step2 分析影片列表规则
                String sampleCateUrl = url;
                if (!TextUtils.isEmpty(cateUrlTpl) && !TextUtils.isEmpty(cate)) {
                    String[] parts = cate.split("#")[0].split("\\$");
                    if (parts.length >= 2) {
                        sampleCateUrl = buildSampleCateUrl(cateUrlTpl, parts[1]);
                    }
                }
                if (TextUtils.isEmpty(sampleCateUrl)) sampleCateUrl = url;
                // Step2: 抓分类页，AI 分析影片列表并选一个影片
                postStatus(listener, "正在抓取分类影片列表...");
                String cateHtml = fetchHtml(sampleCateUrl, 24000);
                // 兜底：若分类页内容为空或过短（被风控/链接不对），退回用首页 HTML 给 Step2 分析
                if (cateHtml.length() < 200) {
                    postStatus(listener, "分类页无数据，使用首页分析...");
                    cateHtml = homeHtml;
                    sampleCateUrl = url;
                }
                postStatus(listener, "AI 从列表选影片中...");
                JsonObject step2 = callAi(buildPrompt2(cateHtml, sampleCateUrl));
                String detailUrl = getString(step2, "详情页链接");
                if (TextUtils.isEmpty(detailUrl)) detailUrl = extractLink(cateHtml, new String[]{"voddetail", "detail", "play", "vod", "id"});
                // Step3: 抓详情页，AI 分析播放线路与播放选集
                postStatus(listener, "正在抓取影片详情页...");
                String detailHtml = TextUtils.isEmpty(detailUrl) ? "" : fetchHtml(detailUrl, 40000);
                postStatus(listener, "AI 分析播放线路与选集...");
                JsonObject step3 = callAi(buildPrompt3(detailHtml, detailUrl));
                // 合并配置
                postStatus(listener, "正在生成配置...");
                String config = mergeConfig(url, siteName, cate, cateUrlTpl, step2, step3);
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

    /** 从首页 HTML 框架（导航/分类链接）直接解析真实内容分类，不依赖 AI。
     *  分类ID 支持：数字(1/2、不连续或很大的随机数字如21/23)、拼音或单词slug(dy/dongman/zongyi)。 */
    private CategoryInfo extractCategories(String html, String baseUrl) {
        if (TextUtils.isEmpty(html)) return null;
        java.util.regex.Pattern aPat = java.util.regex.Pattern.compile(
                "<a\\b([^>]*)href=[\"']([^\"']+)[\"']([^>]*)>(.*?)</a>",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        // 常见分类链接前缀（命中则视为“强分类信号”），但【不强制】——slug 站可能不含这些词
        java.util.regex.Pattern cateKw = java.util.regex.Pattern.compile(
                "vodshow|vodtype|vod|type|list|show|cate|class|category|sort|column|fenlei|genre|channel|id/",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        // 明确非分类的链接（首页/搜索/登录/播放/排行等），直接排除（避免死拼关键词误伤）
        java.util.regex.Pattern blockHref = java.util.regex.Pattern.compile(
                "index|search|so\\.|login|register|reg\\.|about|contact|play|player|top|rank|hot|new|rec|app|download|wiki|help|user|member|my\\.|actor|topic|star",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern ariaPat = java.util.regex.Pattern.compile("aria-label=[\"']([^\"']+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE);
        // 两桶：strict(命中分类关键词) 优先，loose(无关键词但非黑名单，可能是slug站) 兜底
        java.util.LinkedHashMap<String, String> strict = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, String> loose = new java.util.LinkedHashMap<>();
        java.util.regex.Matcher m = aPat.matcher(html);
        while (m.find()) {
            String attrs = m.group(1) + " " + m.group(3);
            String href = m.group(2).trim();
            String inner = m.group(4);
            if (TextUtils.isEmpty(href)) continue;
            if (!href.startsWith("/") && !href.startsWith("http")) continue;
            if (blockHref.matcher(href).find()) continue;   // 明显非分类
            // 优先用 aria-label（feikuai 等站分类名写在 aria-label 里），否则取标签内纯文字（去掉内部<span>等子标签）
            String name = "";
            java.util.regex.Matcher am = ariaPat.matcher(attrs);
            if (am.find()) name = am.group(1).trim();
            if (TextUtils.isEmpty(name)) name = inner.replaceAll("<[^>]+>", "").trim();
            if (TextUtils.isEmpty(name)) continue;
            if (name.length() > 12) name = name.substring(0, 12);
            if (name.matches(".*(首页|主页|搜索|排行|热门|最新|推荐|我的|个人|登录|注册|关于|客服|片单|专题|高清|APP|下载|网址|微信|留言|友链|公告).*")) continue;
            String id = extractId(href);
            if (TextUtils.isEmpty(id)) continue;            // 链接里找不到分类ID(数字或slug)
            java.util.LinkedHashMap<String, String> target = cateKw.matcher(href).find() ? strict : loose;
            if (!target.containsKey(name)) target.put(name, href);
        }
        java.util.LinkedHashMap<String, String> map = strict.isEmpty() ? loose : strict;
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

    /** 从分类链接中提取分类ID：数字(1/2/随机大数)或拼音/单词slug(纯字母段)，取路径中最后一个有意义段。 */
    private String extractId(String href) {
        if (TextUtils.isEmpty(href)) return "";
        // 取路径最后一个段（去掉扩展名与query/fragment），数字或字母slug均可
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("/([^/?.#]+?)(?:\\.(html?|php|aspx?))?(?:[?#]|$)");
        java.util.regex.Matcher m = p.matcher(href);
        String last = null;
        while (m.find()) last = m.group(1);
        return last == null ? "" : last;
    }

    /** 相对路径转绝对路径 */
    private String toAbsolute(String href, String baseUrl) {
        if (href.startsWith("http")) return href;
        String host = UrlUtil.host(baseUrl);
        String scheme = baseUrl.startsWith("https") ? "https://" : "http://";
        if (href.startsWith("/")) return scheme + host + href;
        return scheme + host + "/" + href;
    }

    /** 兜底：从真实分类页链接推导分类url模板。
     *  不依赖任何框架关键词（/vod /vodshow /list /type /id 等前缀各站不同，必须由 AI 从真实 href 读取），
     *  仅用通用启发式：把路径中【最后一个有意义段】(代表分类ID，数字或拼音/单词slug均可)替换为 {cateId}，其余照搬真实 href。
     *  优先使用 AI 推导的模板；此兜底仅在 AI 未给出含 {cateId} 的模板时启用。 */
    private String deriveCateUrlTpl(String realUrl) {
        if (TextUtils.isEmpty(realUrl)) return "";
        // 找路径最后一个段（去掉扩展名），把分类ID段替换为 {cateId}（数字或字母slug均可）
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(/[^/?.#]+?)(\\.(html?|php|aspx?))?(?:[?#]|$)");
        java.util.regex.Matcher m = p.matcher(realUrl);
        String seg = null, ext = null; int s = -1, e = -1;
        while (m.find()) { seg = m.group(1); ext = m.group(2); s = m.start(); e = m.end(); }
        if (seg == null) return "";
        String replacement = "/" + "{cateId}" + (ext == null ? "" : ext);  // seg 已含前导/，整体替换为 /{cateId}
        return realUrl.substring(0, s) + replacement + realUrl.substring(e);
    }

    /** 用真实分类ID拼出用于【本机抓取分析】的分类页URL：
     *  {cateId}→真实ID，{catePg}→1，其余筛选占位符({area}/{class}/{year}...)清为空（XBPQ运行时才需要）。 */
    private String buildSampleCateUrl(String tpl, String cateId) {
        String u = tpl.replace("{cateId}", cateId == null ? "1" : cateId).replace("{catePg}", "1");
        u = u.replaceAll("\\{([a-zA-Z]+)\\}", "");   // 清掉其他筛选占位符
        u = u.replaceAll("(?<!:)//+", "/");            // 清理多余斜杠（保留 https:// 的 //）
        u = u.replaceAll("/\\.html", ".html");         // 清理 段/.html -> 段.html
        return u;
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
            try {
                return JsonParser.parseString(json).getAsJsonObject();
            } catch (Exception jsonEx) {
                // AI 返回的不是合法 JSON（可能模型不遵循指令格式），返回空对象而非崩溃
                return new JsonObject();
            }
        }
    }

    private String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return "";
        return obj.get(key).getAsString();
    }

    /** Step1: AI 负责识别【站名 + 框架 + 分类(名称$ID) + 分类url模板】。
     *  分类url 模板由 AI【直接阅读首页HTML里的真实分类链接】推导（前缀与ID都取自真实href），
     *  程序绝不按框架关键词(vod/list/type等)死拼URL结构，也不编造ID。 */
    private String buildPrompt1(String html, String url) {
        return "你是视频网站解析专家，必须严格按照【XBPQ(小暴脾气)爬虫框架】规则输出。\n\n"
                + "===== XBPQ 框架核心规则（只给格式，不限定具体站点写法）=====\n"
                + "【截取方式——只允许以下四种】\n"
                + "(1) p: (jsoup选择器): p:div.class / p:a->text / p:img->src / p:img->data-original\n"
                + "(2) && (正则): 起始文本&&结束文本\n"
                + "(3) j: (json路径): j:data.list[0].name\n"
                + "(4) 分割: 用 # 分割成数组\n\n"
                + "【分类相关字段格式——必须严格遵守】\n"
                + "- \"分类\": 格式 分类名$分类ID#分类名$分类ID（$分隔名与ID，#分隔不同分类）\n"
                + "  示例(仅为格式示意): 电影$1#电视剧$2#动漫$3#综艺$4\n"
                + "  分类ID = 该分类真实链接路径里【代表分类的那一段标识符】。\n"
                + "  它可能是纯数字(如1/2，也可能是不连续或很大的随机数字如21/23/99，原样提取不要改)、英文单词(如 movie)、或拼音slug(如 dy/dongman/zongyi)——无论哪种都【原样提取】，不要把拼音改成中文、不要把21改成1。\n"
                + "  ⚠️ 严禁想当然：电影不一定是1(有的站电影=20)，绝对禁止按“常见框架规律”编造ID，必须从下方HTML真实<a href>里提取。\n"
                + "- \"分类url\": 分类列表页网址【模板】，【必须】含 {cateId} 占位符(代表分类ID，XBPQ运行时会用“分类”字段里的真实ID替换它)；若该站分类页有翻页，还要含 {catePg} 占位符(代表页码)。\n"
                + "  必须从下方HTML里【真实的分类链接】推导：把链接中的分类标识符(数字或英文slug)替换成 {cateId}；翻页则观察翻页链接把页码替换成 {catePg}；无翻页则只保留 {cateId}；其他筛选维度(地区/类型/年份等)可保留为占位符或留空。\n"
                + "  注意：每个站的URL前缀(/vod /vodshow /list /type /id 等)和ID所在位置都不一样，【必须】直接读真实href推导，不要套用固定格式去拼！\n"
                + "  合法示例(仅示意格式，具体以你看到的真实链接为准): /vodshow/{cateId}.html 、 /type/{cateId}-{catePg}.html 、 /vodshow/area/{area}/id/{cateId}/page/{catePg}/year/{year}.html\n"
                + "- \"站名\": 网站真实名称(从<title>/logo提取，不要用域名)\n\n"
                + "【绝对禁止】\n"
                + "- 编造源码中不存在的class名、标签或链接\n"
                + "- 把 首页/搜索/登录/推荐/APP下载 等非内容入口当作分类\n\n"
                + "===== 任务 =====\n"
                + "下面是要解析的视频网站首页HTML。请【直接阅读这段HTML】，找到页面导航/菜单区域的 <a> 链接：\n"
                + "1. \"站名\": 网站真实名称\n"
                + "2. \"框架\": 网站程序框架(苹果CMS V10/苹果CMS/海洋CMS/其他PHP影视站/未知)\n"
                + "3. \"分类\": 所有影视内容分类，格式 分类名$分类ID#分类名$分类ID。\n"
                + "   - 从导航 <a> 的真实链接里提取：链接文字是分类名，链接href里的分类段是ID；\n"
                + "   - 只取指向真实影视内容列表的链接，忽略 首页/搜索/排行/热门/推荐/登录/注册/APP下载/关于 等；\n"
                + "   - 若首页导航是JS动态加载、静态源码里看不到分类链接，再根据框架给出常见分类。\n"
                + "4. \"分类url\": 从上面的真实分类链接推导出的网址【模板】（含 {cateId}，按需含 {catePg}），前缀与ID段必须来自真实href。\n\n"
                + "网站: " + url + "\n\n"
                + "首页HTML:\n" + html + "\n\n"
                + "只返回JSON不要解释不要markdown:\n"
                + "{\"站名\":\"示例影视\",\"框架\":\"苹果CMS V10\",\"分类\":\"电影$1#电视剧$2#动漫$3#综艺$4\",\"分类url\":\"https://站点域名/vodshow/{cateId}.html\"}";
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
     *  关键：绝大多数视频站详情页都有「直接播放/立即播放」按钮混在播放区域内，
     *  AI 必须用【数量+位置+文字特征】三重标准把它和真正的线路/选集区分开。 */
    private String buildPrompt3(String html, String url) {
        return "你是视频网站解析专家，必须严格按照【XBPQ(小暴脾气)爬虫框架】规则输出。\n\n"
                + "===== XBPQ 框架核心规则 =====\n"
                + "【p: jsoup选择器——最常用的截取方式】\n"
                + "  ✅ p:div.my-class / p:ul.my-class / p:li.my-class → 用真实class定位\n"
                + "  ✅ p:span->text / p:a->text / p:a->href → 取文字/链接\n"
                + "  ✅ 起始文本&&结束文本 → 正则截取\n"
                + "  ❌ p:lip.xxx / 编造class → 禁止！\n\n"
                + "===== ⚠️⚠️⚠️ 最重要：区分「假线路/假选集」与「真线路/真选集」=====\n"
                + "视频站详情页播放区域通常混杂着两类元素，必须严格区分：\n\n"
                + "【❌ 假线路 / 假选集 —— 绝对不能用】\n"
                + "  特征一（文字）：内容是「直接播放」「立即播放」「免费观看」「点击播放」「点击下载」「网盘播放」之一\n"
                + "  特征二（数量）：整个区域只有 1 个这样的元素（真正的线路通常是 2~6 个 tab）\n"
                + "  特征三（位置）：在线路tab容器的上方、单独一行、居中显示\n"
                + "  特征四（标签）：class 常含 play-now / cloud-play / video-info-play / btn-important / btn-large\n"
                + "  特征五（链接）：href 是 \"#\" 或空（因为它只是 JS 触发器，不是真实播放页）\n"
                + "  → 无论它出现在页面哪个位置（哪怕在 #play-list 播放列表容器内部），都绝对不能当成线路或选集！\n\n"
                + "【✅ 真线路 —— 你要找的】\n"
                + "  特征一（数量）：有 2 个以上 tab/选项\n"
                + "  特征二（文字）：每个 tab 名字不同，通常是「XX线路」「XX资源」「XX源」「线路1/2/3」「极速/优速/闪电」等\n"
                + "  特征三（位置）：在一个 tabs 容器内横向排列（class 常含 source/tab/nav/menu）\n"
                + "  特征四（标签）：class 常含 play-source-tab / source-tab / playlist-tab / nav-item\n\n"
                + "【✅ 真选集 —— 你要找的】\n"
                + "  特征一（数量）：有 2 个以上剧集链接（单集电影可能只有1个）\n"
                + "  特征二（文字）：格式为「第1集」「第01集」「EP1」「1」「番外篇」等\n"
                + "  特征三（位置）：在某个线路 tab 对应的内容区内（class 常含 source-content/list/content/episode）\n"
                + "  特征四（链接）：href 是真实的播放页 URL（如 /play/xxx-1-1.html），不是 \"#\"\n\n"
                + "【🚫 自检规则 —— 输出前必须检查】\n"
                + "  1. 你的「线路数组」选出来的元素，数量是否 ≥ 2？如果只有1个且名字是「直接播放/立即播放」，100%错了，重选！\n"
                + "  2. 你的「播放列表」选出来的元素，是否包含「第X集」格式的文字？如果选出来的是视频标题名（如「凡人修仙传」），100%错了，重选！\n"
                + "  3. 如果播放区域里同时存在「直接播放」按钮和「XX线路」tabs，前者是假的，后者才是真的！\n\n"
                + "【播放相关字段说明】\n"
                + "- \"线路数组\": 所有播放线路tab的容器选择器（找 class 含 source/tab 的容器，排除 play-now/cloud-play）\n"
                + "- \"线路标题\": 从tab取线路名(如 BF线路/FF线路/线路1)\n"
                + "- \"播放数组\": 单线路下剧集列表容器（在线路对应的内容区内）\n"
                + "- \"播放列表\": 每个剧集节点(通常是<a>标签，文字是 第1集/APP秒播/番外篇 等)\n"
                + "- \"播放标题\": 剧集名称(如 第1集/第01集)\n"
                + "- \"播放链接\": 剧集地址(通常是/play/xxx.html，不是直链)\n"
                + "- \"简介\": 剧情简介文本（从影片信息区提取，不是播放按钮的文字）\n"
                + "- \"解析\": 解析接口URL(有则填无则空)，用于把播放页转成可嗅探地址\n\n"
                + "【播放链接处理规则】\n"
                + "- 选集href通常是播放页(如/play/xxx.html)，不是m3u8/mp4直链\n"
                + "- 若源码中有.m3u8/.mp4直链可直接用\n"
                + "- 若只有播放页href，填该href并在\"解析\"字段给一个解析接口\n"
                + "- 相对路径会自动补全域名前缀\n\n"
                + "===== 任务 =====\n"
                + "分析下面详情页HTML：\n"
                + "1. 先找到「播放列表」区域（搜索 id/class 含 play-list/play/source/tab）\n"
                + "2. 在该区域内先排除所有「直接播放/立即播放/免费观看」按钮（不管它在哪个位置）\n"
                + "3. 找到真正的线路 tabs（多个 tab，名字含「线路/资源/源」）\n"
                + "4. 找到真正的选集列表（多个 <a>，文字含「第X集」）\n"
                + "5. 用自检规则验证你的选择是否正确\n\n"
                + "输出JSON字段：\n"
                + "1. \"简介\": 剧情简介\n"
                + "2. \"线路数组\": 真正的线路容器选择器（排除 play-now-btn/cloud-play-btn）\n"
                + "3. \"线路标题\": 线路名选择器\n"
                + "4. \"播放数组\": 剧集容器选择器（在 .play-source-content 内）\n"
                + "5. \"播放列表\": 剧集节点选择器\n"
                + "6. \"播放标题\": 剧集名选择器\n"
                + "7. \"播放链接\": 剧集地址选择器\n"
                + "8. \"解析\": 解析接口URL\n\n"
                + "页面: " + url + "\n\n"
                + "详情页HTML:\n" + html + "\n\n"
                + (html.isEmpty() ? "HTML为空，所有字段返回空字符串。\n" : "")
                + "只返回JSON不要解释不要markdown:\n"
                + "{\"简介\":\"...\",\"线路数组\":\"p:真实\",\"线路标题\":\"p:a->text\",\"播放数组\":\"p:真实\",\"播放列表\":\"p:a\",\"播放标题\":\"p:a->text\",\"播放链接\":\"p:a->href\",\"解析\":\"\"}";
    }

    /** 合并结果生成最终配置。
     *  ext 的“分类url”为含{cateId}的模板，“分类”为 名称$ID#...，两者配合供 XBPQ 拼出各分类页
     *  （参考小暴脾气官方写法：茄子/小友/冰河等 ext 既有"分类url"模板又有"分类"字段）。
     *  影片列表规则来自 Step2（数组/标题/图片/链接），播放规则来自 Step3。 */
    private String mergeConfig(String url, String siteName, String cate, String cateUrlTpl, JsonObject step2, JsonObject step3) {
        JsonObject ext = new JsonObject();
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
        // 去掉思考标签（DeepSeek等推理模型的思考过程）
        content = content.replaceAll("(?s) 思考.*?/思考", "").trim();
        // 去掉 markdown 代码块标记（AI常返回 ```json ... ``` 或 ``` ... ```）
        content = content.replaceAll("```json\\s*", "").replaceAll("```\\s*$", "");
        content = content.replaceAll("^```\\s*", "").replaceAll("\\s*```$", "");
        content = content.trim();
        // 找最外层 { }
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String json = content.substring(start, end + 1).trim();
            // 验证提取到的确实是合法 JSON，不是带前缀文字的残片
            try {
                JsonParser.parseString(json);
                return json;
            } catch (Exception ignored) {
                // 不是合法 JSON，返回空让调用方处理
            }
        }
        return "";
    }
}

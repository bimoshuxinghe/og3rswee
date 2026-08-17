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
                // （已移除 JS 渲染站前置拦截：不再因首页短/含 window.location 而拒绝，
                //  让 AI 自行判断能否从静态内容中提取有效信息）
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
                // 兜底：若分类页是验证码/风控页或链接不对（内容里没有真实影片详情链接），
                // 退回用首页 HTML 给 Step2 分析。枫叶4K(cd-zj.com)等站分类页有验证码，但首页推荐列表正常，必须用首页训练列表规则。
                if (!containsVodLink(cateHtml)) {
                    postStatus(listener, "分类页无数据/被风控，使用首页分析...");
                    cateHtml = homeHtml;
                    sampleCateUrl = url;
                }
                postStatus(listener, "AI 从列表选影片中...");
                JsonObject step2 = callAi(buildPrompt2(cateHtml, sampleCateUrl));
                // [已移除] JS_DYNAMIC 回退逻辑——该逻辑会阻碍 knvod 等站正确获取分类，
                // 且标准版 XBPQ 本身支持 p: 选择器语法，无需回退到首页重分析。
                String detailUrl = getString(step2, "详情页链接");
                if (TextUtils.isEmpty(detailUrl)) detailUrl = extractLink(cateHtml, new String[]{"voddetail", "detail", "play", "vod", "id"});
                // Step3: 抓详情页，AI 分析播放线路与播放选集
                postStatus(listener, "正在抓取影片详情页...");
                String detailHtml = TextUtils.isEmpty(detailUrl) ? "" : fetchHtml(detailUrl, 40000);
                postStatus(listener, "AI 分析播放线路与选集...");
                JsonObject step3 = callAi(buildPrompt3(detailHtml, detailUrl));
                // Step4: 【关键修复】详情页只要含"立即播放/立刻播放/直接播放"等CTA按钮，
                //  就一律跟进去抓【播放页】——播放页结构更干净（真线路tabs+完整集数），AI更容易做对。
                //  判定（三级优先级，逐级降级）：
                //    Lv1: AI 在"播放页URL"字段返回了CTA的href（最理想）
                //    Lv2: 程序在HTML里扫到CTA关键词 → 正则提取href（不依赖AI判断）
                //    Lv3: 【新增】模糊匹配——HTML含"播放"且AI给的线路选择器看起来像CTA → 强制覆盖
                String playPageUrl = getString(step3, "播放页URL");
                // 精确CTA词表（用于判定+提取）
                boolean hasCtaInHtml = detailHtml.contains("立即播放") || detailHtml.contains("立刻播放")
                        || detailHtml.contains("立即观看") || detailHtml.contains("开始播放")
                        || detailHtml.contains("在线播放") || detailHtml.contains("直接播放")
                        || detailHtml.contains("免费观看") || detailHtml.contains("点击播放")
                        || detailHtml.contains("网盘播放") || detailHtml.contains("播放全集");
                // 模糊CTA检测：页面中任何位置出现含"播放"二字的独立按钮/链接文字
                // 匹配模式：<a...>xxx播放xxx</a> 或 <button...>xxx播放xxx</button> 或 <span class="btn...">xxx播放</span>
                boolean hasFuzzyCta = false;
                if (!hasCtaInHtml && detailHtml.length() > 500) {
                    java.util.regex.Pattern fuzzyPat = java.util.regex.Pattern.compile(
                            "<(?:a|button|span)\\b[^>]*(?:class|id)=['\"][^'\"]*(?:btn|play|now|cloud)['\"][^>]*>[^<]*?播放",
                            java.util.regex.Pattern.CASE_INSENSITIVE);
                    hasFuzzyCta = fuzzyPat.matcher(detailHtml).find();
                }
                // 【Lv2】AI没给播放页URL但HTML里明显有CTA → 正则提取按钮href
                if (TextUtils.isEmpty(playPageUrl) && (hasCtaInHtml || hasFuzzyCta)) {
                    // AI没给播放页URL但HTML里明显有CTA → 正则提取"立即播放"按钮的href
                    playPageUrl = extractPlayButtonHref(detailHtml);
                    if (TextUtils.isEmpty(playPageUrl)) {
                        // JS触发型CTA(href="#"等)：尝试从详情页找真实播放/线路页链接
                        String alt = extractLink(detailHtml, new String[]{"player", "play", "vod", "show", "bofang"});
                        if (!TextUtils.isEmpty(alt) && !alt.equals(detailUrl)) playPageUrl = alt;
                    }
                }
                // 相对路径补全为绝对路径（CTA的href常为相对路径）
                if (!TextUtils.isEmpty(playPageUrl) && !playPageUrl.startsWith("http")) {
                    playPageUrl = schemeHost(url) + (playPageUrl.startsWith("/") ? "" : "/") + playPageUrl;
                }
                if (!TextUtils.isEmpty(playPageUrl)) {
                    postStatus(listener, "正在跟进播放页获取真实线路...");
                    String playPageHtml = fetchHtml(playPageUrl, 40000);
                    if (playPageHtml.length() > 200 && (containsVodLink(playPageHtml) || playPageHtml.contains("第") || playPageHtml.contains("集") || playPageHtml.contains("episode"))) {
                        postStatus(listener, "AI 分析播放页真实线路与选集...");
                        JsonObject step4 = callAi(buildPrompt4(playPageHtml, playPageUrl));
                        // 用播放页的线路/集数覆盖掉详情页可能残留的CTA假结果
                        String[] playKeys = {"线路数组", "线路标题", "播放数组", "播放列表", "播放标题", "播放链接", "解析"};
                        for (String k : playKeys) {
                            String v = getString(step4, k);
                            if (!TextUtils.isEmpty(v)) step3.addProperty(k, v);
                        }
                    }
                } else if (hasCtaInHtml || hasFuzzyCta) {
                    // 详情页有CTA但始终拿不到真实播放页链接：清掉step3里残留的假线路/集数，
                    // 避免把"立即播放/开始播放"按钮当成一条线路显示在详情页
                    for (String bad : new String[]{"线路数组", "线路标题", "播放数组", "播放列表", "播放标题", "播放链接"}) {
                        step3.remove(bad);
                    }
                    postStatus(listener, "检测到CTA按钮但无真实播放页链接，已跳过假线路");
                }
                // 【Lv3 强制CTA覆盖】AI可能给了播放页URL但也给了假线路选择器，
                // 或者AI完全忽略了CTA直接输出了假线路。只要检测到CTA且step3的线路字段看起来像单元素CTA，
                // 且还没被播放页结果覆盖过 → 再次尝试提取+跟进（防御性兜底）
                if ((hasCtaInHtml || hasFuzzyCta) && TextUtils.isEmpty(playPageUrl)) {
                    String lineVal = getString(step3, "线路数组");
                    String lineTitle = getString(step3, "线路标题");
                    // 如果AI给的线路值看起来像单个CTA按钮的选择器（短、含btn/play/now等关键词）
                    boolean looksLikeCtaSelector = false;
                    if (!TextUtils.isEmpty(lineVal) && lineVal.length() < 60) {
                        java.util.regex.Pattern ctaSelPat = java.util.regex.Pattern.compile(
                                "btn|play.*now|cloud.*play|now.*play|video.*play|important",
                                java.util.regex.Pattern.CASE_INSENSITIVE);
                        looksLikeCtaSelector = ctaSelPat.matcher(lineVal).find();
                    }
                    // 或者线路标题的值含CTA词（AI把按钮文字当成了线路名）
                    boolean titleIsCta = false;
                    if (!TextUtils.isEmpty(lineTitle)) {
                        java.util.regex.Pattern ctaTxtPat = java.util.regex.Pattern.compile(
                                "直接播放|立即播放|立刻播放|立即观看|开始播放|在线播放|免费观看|点击播放",
                                java.util.regex.Pattern.CASE_INSENSITIVE);
                        titleIsCta = ctaTxtPat.matcher(lineTitle).find();
                    }
                    if (looksLikeCtaSelector || titleIsCta) {
                        // 尝试最后一次提取CTA href并跟进
                        String fallbackUrl = extractPlayButtonHref(detailHtml);
                        if (TextUtils.isEmpty(fallbackUrl)) {
                            fallbackUrl = extractLink(detailHtml, new String[]{"player", "play", "vod", "show", "bofang"});
                        }
                        if (!TextUtils.isEmpty(fallbackUrl) && !fallbackUrl.equals(detailUrl)) {
                            if (!fallbackUrl.startsWith("http")) {
                                fallbackUrl = schemeHost(url) + (fallbackUrl.startsWith("/") ? "" : "/") + fallbackUrl;
                            }
                            String fpHtml = fetchHtml(fallbackUrl, 30000);
                            if (fpHtml.length() > 200) {
                                JsonObject step4b = callAi(buildPrompt4(fpHtml, fallbackUrl));
                                String[] playKeys2 = {"线路数组", "线路标题", "播放数组", "播放列表", "播放标题", "播放链接", "解析"};
                                for (String k : playKeys2) {
                                    String v = getString(step4b, k);
                                    if (!TextUtils.isEmpty(v)) step3.addProperty(k, v);
                                }
                                postStatus(listener, "强制CTA覆盖：从播放页获取到真实线路");
                            }
                        } else {
                            // 连fallback都拿不到 → 彻底清空假线路
                            for (String bad : new String[]{"线路数组", "线路标题", "播放数组", "播放列表", "播放标题", "播放链接"}) {
                                step3.remove(bad);
                            }
                        }
                    }
                }
                // 合并配置
                postStatus(listener, "正在生成配置...");
                // 后置CTA清洗：程序层面检测并移除假线路/假选集（不依赖AI判断）
                String config = sanitizeCtaLines(mergeConfig(url, siteName, cate, cateUrlTpl, step2, step3));
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

    /** 从详情页 HTML 中提取"立即播放/直接播放"等 CTA 按钮的 href（用于 Step4 跟进抓取播放页）。
     *  匹配含 CTA 文字的 <a> 标签，优先取 class 含 play/btn 的。 */
    private String extractPlayButtonHref(String html) {
        if (TextUtils.isEmpty(html)) return "";
        java.util.regex.Pattern aPat = java.util.regex.Pattern.compile(
                "<a\\b([^>]*)href=[\"']([^\"']+)[\"']([^>]*)>(.*?)</a>",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
        java.util.regex.Pattern ctaPat = java.util.regex.Pattern.compile(
                "直接播放|立即播放|立即观看|开始播放|在线播放|免费观看|点击播放|网盘播放|播放全集",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Pattern btnClass = java.util.regex.Pattern.compile(
                "class=[\"'][^\"]*(?:play-btn|btn-play|play-now|cloud-play|video-play|btn-important|btn-large)[^\"]*[\"']",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = aPat.matcher(html);
        String bestHref = "";
        int bestScore = -1;
        while (m.find()) {
            String attrs = m.group(1) + " " + m.group(3);
            String href = m.group(2).trim();
            String inner = m.group(4).replaceAll("<[^>]+>", "").trim();
            if (!ctaPat.matcher(inner).find()) continue;
            if (href.equals("#") || href.isEmpty()) continue;
            int score = 0;
            if (btnClass.matcher(attrs).find()) score += 10;
            if (inner.contains("立即") || inner.contains("直接")) score += 5;
            if (score > bestScore) { bestScore = score; bestHref = href; }
        }
        return bestHref;
    }

    /** 判断 HTML 是否含真实影片详情链接（/detail/ 或 /play/ 或 /vod/）。
     *  用于区分"有内容的分类页"与"验证码/风控/空壳页"（后者可能很长但没有真实影片链接）。 */
    private boolean containsVodLink(String html) {
        if (TextUtils.isEmpty(html)) return false;
        return html.contains("/detail/") || html.contains("/play/") || html.contains("/vod/") || html.contains("/show/");
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

    /** 从分类链接中提取分类ID：数字(1/2/随机大数)或拼音/单词slug(纯字母段)。
     *  优先取路径【最后一个含数字或字母的段】——若最后一段是空白占位符(如 "id/24/.html" 末尾的 "/" 或 ".html")则回退倒数第二段。
     *  若取到的段内含连续2个以上分隔符(-或_)，只取分隔符之前的数字/字母作为ID（如 "1-----------" → "1"，"dy--------" → "dy"）。
     *  这类 "ID+固定后缀" 格式常见于某些CMS站：/vshow/{cateId}-----------.html 或 /cupfox-list/{id}-----------.html
     *  （后面短横线是地区/年份等筛选项的空占位符，属于固定后缀，不是ID的一部分）。 */
    /** 取 URL 路径的【最后一个非空段】（去掉扩展名与 query/fragment），如 /type/guoman/ → guoman。
     *  用路径拆分而非脆弱正则，避免 /type/guoman/ 这类目录式URL被错判。 */
    private String lastPathSegment(String url) {
        if (TextUtils.isEmpty(url)) return "";
        String u = url;
        int h = u.indexOf('#'); if (h >= 0) u = u.substring(0, h);
        int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        // 去掉结尾斜杠，取最后一个非空段
        while (u.length() > 0 && u.charAt(u.length() - 1) == '/') u = u.substring(0, u.length() - 1);
        int slash = u.lastIndexOf('/');
        String seg = slash >= 0 ? u.substring(slash + 1) : u;
        // 去掉扩展名（.html/.php/.aspx）
        int dot = seg.lastIndexOf('.');
        if (dot > 0) {
            String ext = seg.substring(dot + 1).toLowerCase();
            if (ext.matches("html?|php|aspx?")) seg = seg.substring(0, dot);
        }
        return seg;
    }

    /** 从分类链接中提取分类ID：数字(1/2/随机大数)或拼音/单词slug(纯字母段)。
     *  取路径【最后一个非空段】——如 /type/guoman/ → guoman、/vshow/1-----------.html → 1（保留后缀前的数字）。
     *  若段内含连续2个以上分隔符(-或_)，只取分隔符之前的数字/字母作为ID（如 "1-----------" → "1"，"dy--------" → "dy"）。 */
    private String extractId(String href) {
        if (TextUtils.isEmpty(href)) return "";
        String last = lastPathSegment(href);
        if (last.isEmpty() || !last.matches(".*[a-zA-Z0-9].*")) return "";
        // 如果段内含连续2个以上的分隔符（-或_），只取分隔符之前的数字/字母作为ID
        java.util.regex.Pattern sepPat = java.util.regex.Pattern.compile("^([a-zA-Z0-9]+)[- _]{2,}");
        java.util.regex.Matcher sm = sepPat.matcher(last);
        if (sm.find()) return sm.group(1);   // "1-----------" → "1", "dy--------" → "dy"
        // 无特殊分隔符，整个段就是ID（可能是数字、随机大数、拼音slug）
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

    /** 兜底：从真实分类页链接推导分类url模板。
     *  不依赖任何框架关键词（/vod /vodshow /list /type /id 等前缀各站不同，必须由 AI 从真实 href 读取），
     *  仅用通用启发式：把路径中【最后一个非空段】(代表分类ID，数字/拼音slug均可)替换为 {cateId}，其余照搬真实 href。
     *  特殊处理：若ID后面跟连续分隔符（如 "1-----------"），这些是固定筛选项占位符，必须保留（→ {cateId}-----------）。
     *  目录式URL（/type/guoman/ 末尾斜杠）也正确保留斜杠。
     *  优先使用 AI 推导的模板；此兜底仅在 AI 未给出含 {cateId} 的模板时启用。 */
    private String deriveCateUrlTpl(String realUrl) {
        if (TextUtils.isEmpty(realUrl)) return "";
        String u = realUrl;
        int h = u.indexOf('#'); if (h >= 0) u = u.substring(0, h);
        int q = u.indexOf('?'); if (q >= 0) u = u.substring(0, q);
        boolean trailingSlash = u.endsWith("/");
        String work = trailingSlash ? u.substring(0, u.length() - 1) : u;
        int slash = work.lastIndexOf('/');
        if (slash < 0) return "";
        String seg = work.substring(slash + 1);
        if (seg.isEmpty() || !seg.matches(".*[a-zA-Z0-9].*")) return "";
        String ext = "";
        int dot = seg.lastIndexOf('.');
        if (dot > 0) {
            String e = seg.substring(dot + 1).toLowerCase();
            if (e.matches("html?|php|aspx?")) { ext = seg.substring(dot); seg = seg.substring(0, dot); }
        }
        // 检查段内是否有 "ID+固定后缀" 格式（如 "1-----------" 或 "dy--------"）
        java.util.regex.Pattern sepPat = java.util.regex.Pattern.compile("^([a-zA-Z0-9]+)([- _]{2,}.*)$");
        java.util.regex.Matcher sm = sepPat.matcher(seg);
        String replacement;
        if (sm.find()) {
            replacement = "{cateId}" + sm.group(2) + ext;          // 保留固定后缀
        } else {
            replacement = "{cateId}" + ext;                         // 整段替换
        }
        return work.substring(0, slash + 1) + replacement + (trailingSlash ? "/" : "");
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
                + "  注意：每个站的URL前缀(/vod /vodshow /list /type /id /vshow /cupfox-list 等)和ID所在位置都不一样，【必须】直接读真实href推导，不要套用固定格式去拼！\n"
                + "  ⚠️ 极其重要——【原样复制】真实链接里的【固定后缀】！某些站的链接形如 /vshow/1-----------.html 或 /cupfox-list/13-----------.html，\n"
                + "     其中数字(1/13)是分类ID，后面一长串短横线(-----------)是地区/年份等筛选项的【空占位符，属于固定后缀不可省略】，\n"
                + "     正确模板必须写成 /vshow/{cateId}-----------.html（保留 -----------，只把 1 换成 {cateId}），【绝对不能】丢掉短横线写成 /vshow/{cateId}.html！\n"
                + "  合法示例(仅示意格式，具体以你看到的真实链接为准): /vodshow/{cateId}.html 、 /type/{cateId}-{catePg}.html 、 /type/{cateId}/（目录式URL末尾带/，如 /type/guoman/ 国产动漫）、 /vshow/{cateId}-----------.html 、 /vodshow/area/{area}/id/{cateId}/page/{catePg}/year/{year}.html\n"
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
     *  关键改进：强制AI原样抄写class名(保留空格/连字符)，给正确示例参考，
     *  检测JS动态渲染(无列表时回退首页)，禁止编造不存在的选择器。 */
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
                + "【⚠️ 极其重要——class名必须原样抄写】\n"
                + "HTML中的class属性可能包含多个值(用空格分隔)，例如:\n"
                + "  class=\"stui-vodlist__thumb lazyload\"   ← 两个class: stui-vodlist__thumb 和 lazyload\n"
                + "  class=\"title text-overflow\"            ← 两个class: title 和 text-overflow\n"
                + "  class=\"col-md-6 col-sm-4 col-xs-3\"     ← 三个class\n"
                + "规则:\n"
                + "  1. 选择器里只写【最能唯一标识元素的那个class】，不要把所有class都堆上去\n"
                + "  2. 如果一个class不够唯一，用两个: p:div.class1.class2 (jsoup支持多class选择器)\n"
                + "  3. 【绝对禁止】把空格去掉后拼接成新词！比如 class=\"title text-overflow\"\n"
                + "     ✅ 正确写法: h4.title.text-overflow 或 h4.title (取其中一个即可)\n"
                + "     ❌ 错误写法: h4.titlea-text (这是不存在的class！)\n"
                + "  4. 连字符(-)和下划线(_)必须保留: stui-vodlist__thumb 不能写成 stuivodlistthumb\n\n"
                + "【常见CMS模板的正确选择器参考(仅作格式示意，绝不能直接套用！先看真实class)】\n"
                + "苹果CMS V10 + stui模板(最常见):\n"
                + "  数组: p:ul.stui-vodlist li\n"
                + "  标题: p:a.stui-vodlist__thumb->text  (或 p:h4.title a->text)\n"
                + "  图片: p:a.stui-vodlist__thumb->data-original  (注意是data-original不是src)\n"
                + "  链接: p:a.stui-vodlist__thumb->href\n"
                + "苹果CMS V10 + myui模板:\n"
                + "  数组: p:ul.myui-vodlist li\n"
                + "  标题: p:a.myui-vodlist__thumb->text\n"
                + "  图片: p:a.myui-vodlist__thumb->data-original\n"
                + "  链接: p:a.myui-vodlist__thumb->href\n"
                + "苹果CMS V10 + mx/mxp模板(飞快TV/电影先生等):\n"
                + "  数组: p:div.module-item   (最小的影片卡片单元，容器div，看源码真实class)\n"
                + "  标题: p:a.module-item__name->text  (或 p:a->title，看片名在文字还是title属性)\n"
                + "  图片: p:img->data-original  (看真实属性名：data-original/data-src/src)\n"
                + "  链接: p:a.module-item__name->href  (取包裹片名的<a>的href)\n"
                + "自定义模板(如枫叶4K cd-zj.com 等小众站):\n"
                + "  数组: p:div.public-list-div  (容器可能是div不是ul！直接用源码里的class)\n"
                + "  标题: p:a.public-list-exp->title  (标题可能写在<a>的title属性里，不是文字！也可用->text)\n"
                + "  图片: p:a.public-list-exp img->data-src  (懒加载属性名为data-src，不是data-original！)\n"
                + "  链接: p:a.public-list-exp->href\n\n"
                + "【⚠️ 图片属性名——必须看源码实际用哪个】\n"
                + "  常见懒加载属性: data-original / data-src / data-lazy-src / data-original-src\n"
                + "  也可能直接用: src\n"
                + "  ❌ 不要想当然写data-original！必须去<img>标签里看真实属性名(本例枫叶4K用data-src)\n\n"
                + "【⚠️ 标题位置——可能不在文字里】\n"
                + "  常见情况: 在<a>的title属性(如 title=\"片名\")、或<img>的alt属性、或在<span>/<p>文字里\n"
                + "  若卡片内看不到片名文字，优先试 ->title 或 ->alt\n\n"
                + "【关键字段说明（严格按XBPQ官方文档写法）】\n"
                + "- \"数组\": 包裹每部影片的最小重复单元。两种写法皆可（推荐②，最稳）：\n"
                + "    ① jsoup选择器(只用路径): p:ul.xxx li 或 p:div.xxx 或 p:section.xxx .item\n"
                + "    ② 截取语法: class=\"xxx\"&&</a>  (截取每个影片卡片的完整标签块)\n"
                + "- \"标题\": 在每个卡片内取片名。写法：p:a->text 或 p:h4->text 或 p:a->title(片名写在<a>的title属性里时)\n"
                + "- \"图片\": 在每个卡片内取海报。写法：p:img->data-original 或 p:img->data-src 或 p:img->src(必须看源码真实属性名)\n"
                + "- \"链接\": 在每个卡片内取详情页href。写法：p:a->href\n"
                + "- 相对路径会自动补全域名前缀\n\n"
                + "【⚠️ 属性字段必须带 ->属性】\n"
                + "  标题/图片/链接/播放标题/播放链接 这类字段，必须写成\"p:标签->属性\"或\"属性名=&&\"（带属性），\n"
                + "  绝不能只写 \"p:div.xxx\"（纯路径）！纯路径只用于数组/播放数组/播放列表。\n"
                + "  错误示例(会显示选择器原文): 标题: \"p:div.module-item\"  ← 缺少->text\n"
                + "  正确示例: 标题: \"p:a.module-item__name->text\" 或 标题: \"p:a->title\"\n\n"
                + "===== 任务 =====\n"
                + "分析下面分类页HTML，先找到影片列表区域，观察真实标签和class名(原样抄写！)，再输出JSON:\n"
                + "1. \"数组\": 影片容器选择器(用源码中真实的class，原样抄写！)\n"
                + "2. \"标题\": 片名选择器\n"
                + "3. \"图片\": 海报图选择器+属性名\n"
                + "4. \"链接\": 详情页href选择器\n"
                + "5. \"详情页链接\": 页面中第一个影片的完整详情URL\n\n"
                + "页面: " + url + "\n\n"
                + "分类页HTML:\n" + html + "\n\n"
                + (html.isEmpty() ? "HTML为空，所有字段返回空字符串。\n" : "")
                + "只返回JSON不要解释不要markdown:\n"
                + "{\"数组\":\"p:ul.stui-vodlist li\",\"标题\":\"p:a.stui-vodlist__thumb->text\",\"图片\":\"p:a.stui-vodlist__thumb->data-original\",\"链接\":\"p:a.stui-vodlist__thumb->href\",\"详情页链接\":\"https://...\"}";
    }

    /** Step3: 分析详情页，生成播放线路与播放选集截取规则。
     *  关键：绝大多数视频站详情页都有「直接播放/立即播放」按钮混在播放区域内，
     *  AI 必须用【数量+位置+文字特征】三重标准把它和真正的线路/选集区分开。
     *  【重要】若详情页只有CTA按钮而无真正多线路，AI 必须返回该按钮的href作为"播放页URL"，
     *  程序会自动跟进去抓取播放页获取真实线路/集数。 */
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
                + "【🔑 关键：CTA按钮跟进机制（最重要！必须严格执行！）】\n"
                + "【模糊匹配规则】只要页面中存在任何含「播放」二字的独立可点击元素（<a>链接/<button>按钮/<span>标签），\n"
                + "且它不是剧集列表中的「第X集/第X话」链接，就 100% 是CTA播放按钮！\n"
                + "常见CTA文字：「直接播放」「立即播放」「立刻播放」「开始播放」「在线播放」「免费观看」\n"
                + "         「点击播放」「网盘播放」「播放全集」「立即观看」「XX播放」\n"
                + "【判定方法——按顺序执行】\n"
                + "  Step1: 用眼睛扫描整个页面HTML，找到所有含「播放」的 <a> 或 <button> 元素\n"
                + "  Step2: 判断这些元素是不是剧集链接（剧集链接的文字格式是「第1集/EP01/01/番外篇」，不含「播放」二字）\n"
                + "  Step3: 只要找到 ANY 一个非剧集的「XXX播放」元素 → 它就是CTA按钮 → 执行跟进\n"
                + "【跟进操作（找到CTA后必须这样做）】\n"
                + "   ① 在「播放页URL」字段填入那个CTA按钮的真实href（不要填#或空，如果是#就找同区域其他链接）\n"
                + "   ② 「线路数组」「线路标题」「播放数组」「播放列表」「播放标题」「播放链接」「解析」全部留空（填空字符串\"\"）\n"
                + "   ③ 程序会自动用播放页URL去抓取播放页，从播放页获取真实的线路tabs和完整集数列表\n"
                + "   ④ ⚠️ 绝对不要把CTA按钮的选择器填入「线路数组」或「播放列表」字段！\n"
                + "【只有一种情况不跟进】你扫描了整个页面，完全找不到任何含「播放」的按钮/链接，\n"
                + " 且详情页本身就有 ≥2 个线路tab（如「线路1」「线路2」）和 ≥2 个剧集链接（如「第1集」「第2集」），\n"
                + " 才直接输出线路/集数字段，「播放页URL」留空。\n\n"
                + "【播放相关字段说明】\n"
                + "- \"简介\": 剧情简介文本（从影片信息区提取，不是播放按钮的文字）\n"
                + "- \"播放页URL\": 【重要新字段】若详情页只有CTA无多线路，填入CTA按钮的href；若有真线路则留空\n"
                + "- \"线路数组\": 所有播放线路tab的容器选择器（找 class 含 source/tab 的容器，排除 play-now/cloud-play）\n"
                + "- \"线路标题\": 从tab取线路名(如 BF线路/FF线路/线路1)\n"
                + "- \"播放数组\": 集数列表的【容器】(包裹所有第X集的父容器，如 ul.play-list 或包含<li>的<div>)\n"
                + "- \"播放列表\": 每个【剧集节点】(通常是<a>标签，文字是 第1集/APP秒播/番外篇 等，如 p:ul.play-list li 或 <li>&&\n>)\n"
                + "- \"播放标题\": 剧集名称(如 第1集/第01集)，相对播放列表节点取\n"
                + "- \"播放链接\": 剧集地址(通常是/play/xxx.html，不是直链)，相对播放列表节点取\n"
                + "- \"解析\": 解析接口URL(有则填无则空)，用于把播放页转成可嗅探地址\n\n"
                + "【播放链接处理规则】\n"
                + "- 选集href通常是播放页(如/play/xxx.html)，不是m3u8/mp4直链\n"
                + "- 若源码中有.m3u8/.mp4直链可直接用\n"
                + "- 若只有播放页href，填该href并在\"解析\"字段给一个解析接口\n"
                + "- 相对路径会自动补全域名前缀\n\n"
                + "===== 任务（按顺序严格执行）=====\n"
                + "Step1: 【CTA模糊匹配——最重要！】扫描整个页面HTML，找所有含「播放」二字的 <a>/<button>/<span> 元素\n"
                + "  - 排除剧集链接（「第X集/第X话/EPX/番外篇」不含「播放」二字，不是CTA）\n"
                + "  - 只要找到 ANY 一个非剧集的「XXX播放」元素 → 它就是CTA → 跳到 Step2\n"
                + "  - 如果完全找不到任何含「播放」的按钮/链接 → 跳到 Step3\n"
                + "Step2: 【有CTA→跟进播放页】\n"
                + "  - 「播放页URL」= CTA按钮的href（不要#或空，如果是JS触发#就找附近的真实URL）\n"
                + "  - 所有线路/集数字段全部填空字符串 \"\"\n"
                + "  - 停止分析，直接输出JSON\n"
                + "Step3: 【无CTA→在详情页提取线路/集数】\n"
                + "  - 确认页面有 ≥2 个线路tab 和 ≥2 个剧集链接\n"
                + "  - 正常输出线路/集数选择器，「播放页URL」留空\n\n"
                + "输出JSON字段：\n"
                + "1. \"简介\": 剧情简介\n"
                + "2. \"播放页URL\": 若只有CTA无多线路，填CTA按钮href；否则留空\n"
                + "3. \"线路数组\": 真正的线路容器选择器（排除 play-now-btn/cloud-play-btn）\n"
                + "4. \"线路标题\": 线路名选择器\n"
                + "5. \"播放数组\": 集数列表容器选择器（包裹所有集数的父容器，在线路内容区内）\n"
                + "6. \"播放列表\": 单个剧集节点选择器（通常是 <a> 或 <li>）\n"
                + "6. \"播放标题\": 剧集名选择器\n"
                + "7. \"播放链接\": 剧集地址选择器\n"
                + "8. \"解析\": 解析接口URL\n\n"
                + "页面: " + url + "\n\n"
                + "详情页HTML:\n" + html + "\n\n"
                + (html.isEmpty() ? "HTML为空，所有字段返回空字符串。\n" : "")
                + "只返回JSON不要解释不要markdown:\n"
                + "{\"简介\":\"...\",\"播放页URL\":\"\",\"线路数组\":\"p:div.play-source\",\"线路标题\":\"p:a->text\",\"播放数组\":\"p:ul.play-list\",\"播放列表\":\"p:a\",\"播放标题\":\"p:a->text\",\"播放链接\":\"p:a->href\",\"解析\":\"\"}";
    }

    /** Step4: 分析【播放页】（从详情页"立即播放"按钮跳转过来的页面）。
     *  播放页和详情页不同——它通常直接包含真正的多线路tabs和剧集列表，
     *  不再有"立即播放"CTA按钮。这是获取真实线路/集数的最佳来源。
     *  此 prompt 只输出线路/集数相关字段，不重复输出简介等详情页已有字段。 */
    private String buildPrompt4(String html, String url) {
        return "你是视频网站解析专家，必须严格按照【XBPQ(小暴脾气)爬虫框架】规则输出。\n\n"
                + "===== 背景 =====\n"
                + "这是一个视频站的【播放页】（用户在详情页点击「立即播放/直接播放」后跳转到的页面）。\n"
                + "与详情页不同，这个页面通常直接包含：\n"
                + "  - 真正的播放线路 tabs（如「线路1」「线路2」「极速线」「优速线」等，通常 ≥ 2 个）\n"
                + "  - 真正的剧集选集列表（如「第1集」「第2集」…或单集电影的片名）\n"
                + "  - 可能还有 m3u8/mp4 直链\n"
                + "你的任务：从这个页面提取真实的线路和选集选择器。\n\n"
                + "===== XBPQ 选择器规则 =====\n"
                + "【p: jsoup选择器】\n"
                + "  ✅ p:div.class / p:ul.class / p:li.class / p:a / p:span → 用源码中真实class\n"
                + "  ✅ p:a->text 取文字 / p:a->href 取链接 / p:img->src 取图片\n"
                + "  ❌ 编造不存在的class → 绝对禁止！\n"
                + "  ⚠️ class名必须原样抄写：class=\"title text-overflow\" → 用 title 或 text-overflow 或 title.text-overflow，绝对不能写成 titlea-text\n\n"
                + "【你要找的内容】\n"
                + "1. **线路区域**：找包含多个 tab 的容器（横向排列的选项卡），每个 tab 名字不同\n"
                + "   - 常见位置：页面上半部分，标题/播放器下方\n"
                + "   - 常见 class 含：source / tab / nav / menu / playlist / play-from\n"
                + "   - 线路名通常是：「XX线路」「XX资源」「XX源」「线路1/2/3」「极速/优速/闪电」\n"
                + "   - ⚠️ 如果只有一个叫「直接播放/立即播放」的元素且没有其他tab，说明这不是线路区，继续往下找\n\n"
                + "2. **选集区域**：在某条线路下方，包含多个剧集链接的区域\n"
                + "   - 常见位置：线路 tabs 下方的内容区\n"
                + "   - 常见 class 含：content / list / episode / play-list / source-content\n"
                + "   - 集名格式：「第1集」「第01集」「EP1」「1」「番外篇」（电影可能只有1个=片名）\n"
                + "   - 每个 <a> 的 href 是该集的播放地址\n\n"
                + "3. **解析接口**：如果页面里有 m3u8/mp4 直链可直接用；否则留空\n\n"
                + "===== 输出JSON字段 =====\n"
                + "- \"线路数组\": 线路tabs容器选择器（如 p:div.play-source 或 p:ul.source-tabs）\n"
                + "- \"线路标题\": 从单个tab取线路名的选择器（如 p:a->text）\n"
                + "- \"播放数组\": 集数列表容器选择器（包裹所有集数的父容器，在线路内容区内）\n"
                + "- \"播放列表\": 单个剧集节点选择器（通常是 <a> 标签）\n"
                + "- \"播放标题\": 剧集名称选择器（如 p:a->text，取出的文字是「第1集」这种）\n"
                + "- \"播放链接\": 剧集地址选择器（如 p:a->href）\n"
                + "- \"解析\": 解析接口URL（有m3u8/mp4直链则留空）\n\n"
                + "页面: " + url + "\n\n"
                + "播放页HTML:\n" + html + "\n\n"
                + (html.isEmpty() ? "HTML为空，所有字段返回空字符串。\n" : "")
                + "只返回JSON不要解释不要markdown:\n"
                + "{\"线路数组\":\"p:真实\",\"线路标题\":\"p:a->text\",\"播放数组\":\"p:真实容器\",\"播放列表\":\"p:a\",\"播放标题\":\"p:a->text\",\"播放链接\":\"p:a->href\",\"解析\":\"\"}";
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

    /** 后置CTA清洗：程序层面检测并移除AI误判的假线路/假选集。
     *  场景：详情页只有"直接播放"按钮（无线路tabs），AI把它当成了线路；
     *        或单集电影把片名"九门[全集]"当成了选集。
     *  检测规则：
     *  1. 线路数组/线路标题 的值含CTA关键词 → 删掉所有播放相关字段
     *  1.5 线路选择器匹配CTA按钮典型class模式(btn/play-now/cloud-play等) → 删掉
     *  1.6 线路标题值含CTA文字（AI把按钮文本当线路名）→ 删掉
     *  2. 播放列表/播放标题 的值不含"第X集/EP/集"格式且看起来像片名 → 删掉选集字段 */
    private String sanitizeCtaLines(String configJson) {
        try {
            JsonObject root = App.gson().fromJson(configJson, JsonObject.class);
            if (root == null || !root.has("ext")) return configJson;
            JsonObject ext = root.getAsJsonObject("ext");
            boolean dirty = false;
            // 规则1：检测假线路——线路值含CTA关键词
            String[] lineKeys = {"线路数组", "线路"};
            java.util.regex.Pattern ctaPat = java.util.regex.Pattern.compile(
                    "直接播放|立即播放|立刻播放|立即观看|开始播放|在线播放|免费观看|点击播放|网盘播放|播放全集",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
            for (String lk : lineKeys) {
                if (ext.has(lk)) {
                    String lv = ext.get(lk).getAsString();
                    if (ctaPat.matcher(lv).find()) {
                        for (String rk : new String[]{"线路数组", "线路", "线路标题", "播放数组", "播放列表", "播放标题", "播放链接"}) {
                            ext.remove(rk);
                        }
                        dirty = true;
                        break;
                    }
                }
            }
            // 规则1.5：检测CTA选择器模式——线路选择器含btn/play-now/cloud-play等典型CTA class
            if (!dirty && ext.has("线路数组")) {
                String sel = ext.get("线路数组").getAsString();
                java.util.regex.Pattern ctaSelPat = java.util.regex.Pattern.compile(
                        "btn.*play|play.*now|play.*btn|cloud.*play|now.*play|video.*play|btn-important|btn-large",
                        java.util.regex.Pattern.CASE_INSENSITIVE);
                if (ctaSelPat.matcher(sel).find()) {
                    for (String rk : new String[]{"线路数组", "线路", "线路标题", "播放数组", "播放列表", "播放标题", "播放链接"}) {
                        ext.remove(rk);
                    }
                    dirty = true;
                }
            }
            // 规则1.6：检测线路标题值含CTA文字（AI把按钮文本当线路名）
            if (!dirty && ext.has("线路标题")) {
                String lt = ext.get("线路标题").getAsString();
                if (ctaPat.matcher(lt).find()) {
                    for (String rk : new String[]{"线路数组", "线路", "线路标题", "播放数组", "播放列表", "播放标题", "播放链接"}) {
                        ext.remove(rk);
                    }
                    dirty = true;
                }
            }
            // 规则2：检测假选集（片名被当剧集，如"九门[全集]"）
            if (!dirty) {
                String[] epKeys = {"播放列表", "播放标题"};
                java.util.regex.Pattern epPat = java.util.regex.Pattern.compile(
                        "第\\d+集|第0?\\d+话|EP\\d+|^\\d+$|^[①②③④⑤⑥⑦⑧⑨⑩]+$");
                for (String ek : epKeys) {
                    if (ext.has(ek)) {
                        String ev = ext.get(ek).getAsString();
                        // 值不含剧集格式特征，且长度>2（不是单个数字）→ 可能是片名
                        if (ev.length() > 2 && !epPat.matcher(ev).find() && !ev.contains("集") && !ev.contains("话")) {
                            ext.remove("播放列表");
                            ext.remove("播放标题");
                            ext.remove("播放链接");
                            dirty = true;
                            break;
                        }
                    }
                }
            }
            return dirty ? App.gson().toJson(root) : configJson;
        } catch (Exception e) {
            return configJson; // 解析失败则原样返回
        }
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

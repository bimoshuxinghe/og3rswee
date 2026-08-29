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
                // 【分类多路径】若AI识别出"首页特例"（第一页URL与后续页结构不同），
                //  优先用首页特例拼样本页，否则可能抓到 404 或空列表
                String firstPageTpl = getString(step1, "首页特例");
                String sampleCateUrl = url;
                if (!TextUtils.isEmpty(cateUrlTpl) && !TextUtils.isEmpty(cate)) {
                    String[] parts = cate.split("#")[0].split("\\$");
                    if (parts.length >= 2) {
                        String tpl = TextUtils.isEmpty(firstPageTpl) ? cateUrlTpl : firstPageTpl;
                        sampleCateUrl = buildSampleCateUrl(tpl, parts[1]);
                    }
                }
                if (TextUtils.isEmpty(sampleCateUrl)) sampleCateUrl = url;
                // Step2: 抓分类页，AI 分析影片列表并选一个影片
                postStatus(listener, "正在抓取分类影片列表...");
                String cateHtml = fetchHtml(sampleCateUrl, 24000);
                // 兜底：若分类页是验证码/风控页或链接不对（内容里没有真实影片详情链接），
                // 退回用首页 HTML 给 Step2 分析。枫叶4K(cd-zj.com)等站分类页有验证码，但首页推荐列表正常，必须用首页训练列表规则。
                if (!containsVodLink(cateHtml) && !looksLikeListPage(cateHtml)) {
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
                // CTA 判定【双通道】任一命中即算：
                //   ① 形状匹配（主）：页面里有形如「XX播放/XX观看」文字的 <a>/<button>
                //      —— 按【元素级】统计去重文字种类，比整页文本匹配精确得多，
                //         不会把 "播放列表" 这类区块标题或 SEO 外链算进来
                //   ② 词表匹配（兜底）：CTA_WORDS 里已枚举的明确动作词（与 AI 文案共用同一份）
                //  ⚠️ 判为 CTA 只意味着【值得跟进播放页】，【不会】因此清空真线路——
                //     清空与否由 hasReal 单独把关（大量站点 CTA 与真线路本来就是共存的）
                int ctaShapeKinds = countCtaShapeHits(detailHtml);
                boolean hasCtaInHtml = ctaShapeKinds >= 1 || CTA_PATTERN.matcher(detailHtml).find();
                // 【关键】详情页自己是否已经给出了【可信的真线路】（三重校验：非空+无动作词+匹配数≥2）
                boolean hasReal = hasRealLines(step3, detailHtml);
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
                    postStatus(listener, "正在跟进播放页校验线路...");
                    String playPageHtml = fetchHtml(playPageUrl, 40000);
                    if (playPageHtml.length() > 200 && (containsVodLink(playPageHtml) || playPageHtml.contains("第") || playPageHtml.contains("集") || playPageHtml.contains("episode"))) {
                        postStatus(listener, "AI 分析播放页真实线路与选集...");
                        JsonObject step4 = callAi(buildPrompt4(playPageHtml, playPageUrl));
                        // ⚠️ 播放页结果【同样要过可信校验】——播放页里也可能残留「全部播放」这类动作按钮。
                        //   只有播放页给出了可信真线路（step4Real），才用它覆盖详情页结果；
                        //   否则若详情页已有真线路就保留，避免"越覆盖越差"。
                        boolean step4Real = hasRealLines(step4, playPageHtml);
                        if (step4Real || !hasReal) {
                            String[] playKeys = {"线路数组", "线路标题", "播放数组", "播放列表", "播放标题", "播放链接", "解析"};
                            for (String k : playKeys) {
                                String v = getString(step4, k);
                                if (!TextUtils.isEmpty(v)) step3.addProperty(k, v);
                            }
                        }
                    }
                } else if ((hasCtaInHtml || hasFuzzyCta) && !hasReal) {
                    // 详情页有CTA、自己又没有真线路、且拿不到播放页链接 → 清掉假线路/假集数，
                    // 避免把「立即播放/全部播放」这种动作按钮当成一条线路显示出来。
                    // ⚠️ hasReal 为 true 时不清——很多站 CTA 与真线路本来就是共存的。
                    for (String bad : new String[]{"线路数组", "线路标题", "播放数组", "播放列表", "播放标题", "播放链接"}) {
                        step3.remove(bad);
                    }
                    postStatus(listener, "检测到CTA按钮但无真实线路，已清除假线路");
                }
                // 【Lv3 强制CTA覆盖】AI可能给了播放页URL但也给了假线路选择器，
                // 或者AI完全忽略了CTA直接输出了假线路。只要检测到CTA且step3的线路字段看起来像单元素CTA，
                // 且还没被播放页结果覆盖过 → 再次尝试提取+跟进（防御性兜底）
                // ⚠️ 已存在可信真线路时不再走强制覆盖（CTA 与真线路可以共存，覆盖反而可能变差）
                if ((hasCtaInHtml || hasFuzzyCta) && TextUtils.isEmpty(playPageUrl) && !hasReal) {
                    String lineVal = getString(step3, "线路数组");
                    String lineTitle = getString(step3, "线路标题");
                    // 如果AI给的线路值看起来像单个CTA按钮的选择器（短、含典型CTA class）
                    boolean looksLikeCtaSelector = false;
                    if (!TextUtils.isEmpty(lineVal) && lineVal.length() < 60) {
                        java.util.regex.Pattern ctaSelPat = java.util.regex.Pattern.compile(
                                "btn-important|btn-large|btn-primary|btn-danger|btn-block"
                                        + "|play-now|play-btn|btn-play|cloud-play|now-play|video-play",
                                java.util.regex.Pattern.CASE_INSENSITIVE);
                        looksLikeCtaSelector = ctaSelPat.matcher(lineVal).find();
                    }
                    // 或者线路标题的值含CTA词（AI把按钮文字当成了线路名）
                    boolean titleIsCta = !TextUtils.isEmpty(lineTitle) && CTA_PATTERN.matcher(lineTitle).find();
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
                String config = sanitizeCtaLines(mergeConfig(url, siteName, cate, cateUrlTpl, step1, step2, step3));
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
        java.util.regex.Pattern btnClass = java.util.regex.Pattern.compile(
                "class=[\"'][^\"]*(?:play-btn|btn-play|play-now|cloud-play|video-play|btn-important|btn-large"
                        + "|btn-primary|btn-danger|btn-accent)[^\"]*[\"']",
                java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = aPat.matcher(html);
        String bestHref = "";
        int bestScore = -1;
        while (m.find()) {
            String attrs = m.group(1) + " " + m.group(3);
            String href = m.group(2).trim();
            String inner = m.group(4).replaceAll("<[^>]+>", "").trim();
            // 形状匹配(XX播放) 或 词表命中，任一即认为是 CTA 按钮
            if (!isCtaText(inner)) continue;
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

    /** 【通用列表页判定】原 containsVodLink 只认 /detail/ /play/ /vod/ /show/ 四种路径，
     *  对自研站（如 soujunet.com 的详情链接形如 /souju/18bnsah.html）会全部 miss，
     *  导致程序误判"分类页无数据"而回退去分析首页，影片列表规则因此学错。
     *  补充一条与框架无关的判定：统计"同一目录下形如 /dir/{id}.html 的链接"出现次数，≥3 即认为是列表页。
     *  （分类目录 category/type/list/show/vod/index/page/tag/search/topic/actor 一律排除，
     *   那些是目录页不是详情页。） */
    private boolean looksLikeListPage(String html) {
        if (TextUtils.isEmpty(html)) return false;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "href=[\"']([^\"'#]+)[\"']", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.Map<String, Integer> shape = new java.util.HashMap<>();
        java.util.regex.Matcher m = p.matcher(html);
        while (m.find()) {
            String href = m.group(1).trim();
            if (!href.startsWith("/") && !href.startsWith("http")) continue;
            int q = href.indexOf('?');
            if (q >= 0) href = href.substring(0, q);
            if (!(href.endsWith(".html") || href.endsWith(".htm"))) continue;
            String[] segs = href.split("/");
            if (segs.length < 3) continue;                       // 至少 /dir/xxx.html
            String dir = segs[segs.length - 2];
            if (dir.isEmpty()) continue;
            if (dir.matches("(?i).*(category|type|list|show|vod|index|page|tag|search|topic|actor|label).*")) continue;
            Integer n = shape.get(dir);
            shape.put(dir, n == null ? 1 : n + 1);
        }
        for (int n : shape.values()) if (n >= 3) return true;
        return false;
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

    /** 四步共用的 XBPQ(小暴脾气) 语法速查。
     *  内容取自 XBPQ 官方说明文档，覆盖四种截取方式、过滤、替换、拼接、变量与常见易错点，
     *  让 AI 输出的规则【语法合法】，而不是凭印象编造。 */
    private String commonRules() {
        return "===== XBPQ(小暴脾气) 语法速查（必须严格遵守）=====\n"
                + "【四种截取方式】\n"
                + "(1) p: jsoup选择器 —— 解析 HTML 首选\n"
                + "    p:div.class / p:li.item / p:ul[class*=\"v_list\"] li（class包含匹配，支持*通配符）\n"
                + "    p:ul[class*=\"v_list\"],ul[id=\"list\"] li（逗号=多项选择，满足任一项）\n"
                + "    p:a->text 取文字 / p:a->href 取链接 / p:a->title 取title属性 / p:img->src 取图片\n"
                + "    ⚠️ 纯路径(不带 ->属性)只用于【容器类】字段：数组/播放数组/播放列表/线路数组；\n"
                + "       标题/图片/链接/播放标题/播放链接 这类【取值字段】必须带 ->属性！\n"
                + "       ❌ 错误: 标题=\"p:div.module-item\"（缺->text，页面上会直接显示选择器原文）\n"
                + "       ✅ 正确: 标题=\"p:a.module-item__name->text\"\n"
                + "(2) && 正则截取 —— 起始文本&&结束文本（省略前=从头截，省略后=截到尾，末尾加\"整页\"=整页查找）\n"
                + "    支持数字定位(1&&-1)、单标签通配符*(<a*>&&</a>)、跨标签通配符**\n"
                + "(3) j: json路径 —— j:data.list[1].name 或 j:/data/list/1/name，下标从1开始，\n"
                + "    支持范围 j:data.list[1,-1]；子对象全取用 j:urls.*\n"
                + "(4) 分割(符) —— 效率最高。\"list\"&&</ul>分割(后:</li>)，直接用 split 切成数组\n"
                + "    分割符可补回原位：分割(前:<a) / 分割(后:</li>)；支持轮询：分割(前:<a或后:</li>)\n\n"
                + "【过滤】接在截取规则后面，用中括号：\n"
                + "  [包含:a#b] 含任一词 / [不含:a#b] 不含所有词 / [只含:a#b] 完全等于任一词\n"
                + "  [含序号:1#4-7#9-] 按位次取（可单个、多个#分隔、连续-连接，可省略首尾）\n"
                + "【替换】[替换:被替换>>替换内容]，多项用#分隔；替换为空=删除；<序号>可自动从1编号\n"
                + "【拼接】用 + 连接字符串与截取结果，可无限拼接：/play/+/vod/&&.html+-1-1.html\n"
                + "【变量】{cateId}=分类ID  {catePg}=页码  {{线路标题}}  {{分类标题}}  {{标题}}  {{播放链接}}\n"
                + "【指定/轮询】分类名--规则||分类名2--规则2||默认--兜底规则（未指定走第一组）；未指定分类的多个规则用 || 分隔会轮询\n\n"
                + "【⚠️ 三条血泪铁律】\n"
                + "  1. class 名【原样抄写】！class=\"title text-overflow\" → 取 title 或 text-overflow，\n"
                + "     ❌ 绝对禁止删掉空格拼成 titlea-text 这种源码里不存在的词；连字符-下划线_必须保留。\n"
                + "  2. 图片属性【看真实源码】！可能是 data-original / data-src / data-lazy-src / src / alt，\n"
                + "     ❌ 不要想当然写 data-original，必须去 <img> 标签里看这个站到底用的哪个。\n"
                + "  3. 选择器必须能匹配到【多个】元素（数组类字段的起码要求），只匹配到1个的基本都是错的。\n";
    }

    /** CTA(动作按钮)词表——AI 与程序共用同一份语义，避免两边判定不一致。
     *  这些词描述的是【动作】（做什么），而不是【来源】（从哪来），命中即不是线路。 */
    private static final String CTA_WORDS =
            "全部播放|播放全部|全集播放|播放全集|直接播放|立即播放|立刻播放|马上播放|开始播放"
                    + "|在线播放|免费观看|免费播放|点击播放|网盘播放|播放正片|正片播放|播放本片|播放该片"
                    + "|立即观看|马上观看|在线观看|开始观看|立即收看|观看正片|一键播放|极速播放"
                    + "|高清播放|高清观看|去播放|去观看|点我播放|点击观看|播放影片|播放视频";

    /** 由 CTA_WORDS 编译出的正则，供 detect / extractPlayButtonHref / sanitizeCtaLines 共用，
     *  保证【AI 文案】与【程序判定】用的是同一份动作词表，不会出现"AI认得、程序不认得"的错位。 */
    private static final java.util.regex.Pattern CTA_PATTERN =
            java.util.regex.Pattern.compile(CTA_WORDS, java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 【形状规则——比枚举词表更通用】任何形如「□□播放」「□□观看」「播放□□」的文字，
     *  一律视为动作按钮(CTA)，命中就点进去拿真实线路。
     *  □□ = 0~6 个汉字/字母/数字。这样「全部播放」「立即播放」以及将来任何新造词都能自动覆盖，
     *  不必再往词表里一个个加。 */
    private static final java.util.regex.Pattern CTA_SHAPE = java.util.regex.Pattern.compile(
            "[\\u4e00-\\u9fa5A-Za-z0-9]{0,6}(?:播放|观看)$|^播放[\\u4e00-\\u9fa5]{0,4}$|^观看[\\u4e00-\\u9fa5]{0,4}$");

    /** 形状规则的【例外表】：这些虽然长得像「XX播放」，但其实是栏目/区块标题，不是按钮。
     *  必须排除，否则「播放列表」「播放线路」这种区域标题会被误判成 CTA。 */
    private static final java.util.regex.Pattern CTA_SHAPE_EXCLUDE = java.util.regex.Pattern.compile(
            "播放列表|播放地址|播放页面|播放源|播放线路|播放线路|播放器|播放页|播放记录|播放历史"
                    + "|播放量|播放次数|播放方式|播放说明|播放平台|播放渠道|播放区域|播放时间|播放设置"
                    + "|观看记录|观看历史|观看人数|观看次数|观看量|观看列表|在线观看人数");

    /** 判断一段文字是否是【CTA 动作词】。双通道判定，任一命中即算：
     *  ① 形状匹配（优先）：形如「XX播放」「XX观看」「播放XX」——覆盖任何新造词
     *  ② 词表匹配（兜底）：CTA_WORDS 里已枚举的明确动作词
     *  形如「播放列表」「播放线路」这类区块标题会被 CTA_SHAPE_EXCLUDE 排除掉。 */
    private boolean isCtaText(String text) {
        if (TextUtils.isEmpty(text)) return false;
        String t = text.trim();
        if (t.isEmpty()) return false;
        if (CTA_SHAPE_EXCLUDE.matcher(t).find()) return false;
        return CTA_SHAPE.matcher(t).matches() || CTA_PATTERN.matcher(t).find();
    }

    /** 统计页面里"文字形如 XX播放/XX观看 的 <a>/<button>"有【多少种不同文字】。
     *  ⚠️ 用【去重后的文字种类数】而非出现次数——同一个按钮可能被多处渲染。
     *  用途（配合"数量否决"）：
     *    == 1 种 → 孤零零一个动作按钮 → 100% 是 CTA，应该点进去拿真实线路
     *    >= 2 种 → 更像一组并排的线路 tab（如「云播放」「快播放」）→ 按真线路处理，不算 CTA
     *  只取 <a>/<button> 的文字，<h2>/<div> 这类区块标题不参与（避免"播放列表"被算进来）。 */
    private int countCtaShapeHits(String html) {
        if (TextUtils.isEmpty(html)) return 0;
        java.util.Set<String> texts = new java.util.HashSet<>();
        // 只取【短文字】的 <a>/<button>：CTA 按钮文字一般不超过 10 个字，
        //   页面底部的 SEO 外链（如"搜剧网在线观看动漫凤仙花"）会因超长被滤掉。
        // 正则允许 <a> 内嵌 0~3 个行内标签（如 <a><span>立即播放</span></a>），
        //   否则选集/按钮这类带 <span> 包裹的写法会被漏掉；
        //   但用 (?!/?a\b) 禁止嵌套 <a>，否则会跨越到【相邻】的下一个 <a>，
        //   把「首页」+「立即播放」两个不同按钮的文字粘成一个（实测踩过这个坑）。
        String[] pats = {
                "<a\\b([^>]*)>([^<]{0,12}(?:<(?!/?a\\b)[^>]+>[^<]{0,12}){0,3})</a>",
                "<button\\b([^>]*)>([^<]{0,12}(?:<(?!/?a\\b)[^>]+>[^<]{0,12}){0,3})</button>"};
        for (String pat : pats) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile(pat, java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL)
                    .matcher(html);
            while (m.find()) {
                String attrs = m.group(1);
                // 新窗口打开的链接（SEO 外链 / 友链 / 广告）不可能是站内播放按钮
                if (attrs != null && attrs.toLowerCase().contains("target=\"_blank\"")) continue;
                String t = m.group(2).replaceAll("<[^>]+>", "").trim();
                if (t.length() > 10) continue;      // 双保险：CTA 按钮文字不会超过 10 个字
                if (isCtaText(t)) texts.add(t);
            }
        }
        return texts.size();
    }

    /** 【数量校验】在 HTML 里粗算一个选择器能匹配到几个元素（零依赖实现，不引入 jsoup）。
     *  只处理最常见的 p: 选择器形态：p:tag.class / p:tag / p:.class / p:tag[attr]。
     *  用途：线路数组必须能选出 ≥2 个元素，只匹配到 1 个的 100% 不是线路
     *  （典型误判：把孤零零的「全部播放」按钮当成一条线路）。
     *  @return 匹配到的元素个数；无法解析选择器时返回 -1（表示"不确定"，调用方应放行而非误杀） */
    private int countSelectorHits(String html, String selector) {
        if (TextUtils.isEmpty(html) || TextUtils.isEmpty(selector)) return -1;
        String sel = selector.trim();
        if (!sel.startsWith("p:")) return -1;              // 只校验 jsoup 选择器；&& / j: / 分割 不处理
        sel = sel.substring(2).trim();
        if (sel.isEmpty()) return -1;
        // 取选择器【最后一段】作为计数依据（父级链太长时正则难以还原，用最末段近似）
        int sp = sel.indexOf(' ');
        String last = sp >= 0 ? sel.substring(sp + 1).trim() : sel;
        if (last.isEmpty()) return -1;
        // 提取标签名（开头的连续英文字母），没有则匹配任意标签
        java.util.regex.Matcher tagM = java.util.regex.Pattern.compile("^[a-zA-Z]+").matcher(last);
        String tag = tagM.find() ? tagM.group().toLowerCase() : "";
        // 提取 class（.xxx 形式，取最后一个）
        String cls = "";
        java.util.regex.Matcher clsM = java.util.regex.Pattern.compile("\\.([A-Za-z0-9_\\-]+)").matcher(last);
        while (clsM.find()) cls = clsM.group(1);
        // 提取属性选择器 [attr] 或 [attr="v"]
        String attr = "";
        java.util.regex.Matcher atM = java.util.regex.Pattern.compile("\\[([A-Za-z0-9_\\-]+)").matcher(last);
        if (atM.find()) attr = atM.group(1);
        if (cls.isEmpty() && attr.isEmpty() && tag.isEmpty()) return -1;
        // 还原成开标签正则：<tag ... class="... cls ..." ...>
        String tagPat = tag.isEmpty() ? "[a-zA-Z][a-zA-Z0-9]*" : java.util.regex.Pattern.quote(tag);
        String openTag = "<" + tagPat + "\\b[^>]*";
        if (!cls.isEmpty()) {
            openTag += "[^>]*class=['\"][^'\"]*\\b" + java.util.regex.Pattern.quote(cls) + "\\b[^'\"]*['\"]";
        } else if (!attr.isEmpty()) {
            openTag += "[^>]*\\b" + java.util.regex.Pattern.quote(attr) + "\\b";
        }
        openTag += "[^>]*>";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(openTag, java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        int n = 0;
        while (m.find() && n < 300) n++;
        return n;
    }

    /** 判断 AI 给出的线路字段是否【可信的真线路】，三重校验：
     *  ① 线路数组非空；② 线路数组/线路标题都不含 CTA 动作词；③ 选择器在页面里能匹配到 ≥2 个元素（数量否决）。
     *  三者全过才认为"详情页自己就有真线路"，不必清空、也不必强依赖播放页结果。 */
    private boolean hasRealLines(JsonObject step3, String html) {
        String arr = getString(step3, "线路数组");
        if (TextUtils.isEmpty(arr)) arr = getString(step3, "线路");
        if (TextUtils.isEmpty(arr)) return false;
        if (isCtaText(arr)) return false;                                   // 值里含"全部播放"等动作词 → 假线路
        String title = getString(step3, "线路标题");
        if (isCtaText(title)) return false;
        int hits = countSelectorHits(html, arr);
        return hits < 0 || hits >= 2;   // 数不出来(-1)时放行，避免误杀，交给 XBPQ 运行时处理
    }

    /** 区分「真线路」与「CTA按钮」的核心判定说明，buildPrompt3 与 buildPrompt4 共用。 */
    private String lineVsCtaRules() {
        return "===== ⚠️ 核心难点：区分「真线路」与「播放按钮(CTA)」=====\n"
                + "很多站的详情页/播放页会【同时】出现这两类东西，它们长得像但本质完全不同，绝不能混淆：\n\n"
                + "【✅ 真线路 = 一组同级的兄弟元素，描述「从哪来」】\n"
                + "  硬门槛一（数量）：同一容器内【≥ 2 个】结构完全相同的兄弟元素（只有 1 个的 100% 不是线路）\n"
                + "  硬门槛二（结构）：同一标签、同一套 class、同样的属性名，横向排列成一行 tab/胶囊\n"
                + "  硬门槛三（文字）：互不相同，且是【来源名/资源名】\n"
                + "           国内无广告 / 海外 / 腾讯 / 爱奇艺 / m3u8 / 蓝光 / 极速线 / 线路1 / 线路2 / 备用\n"
                + "  外观线索：class 常含 source / tab / chip / line / nav-item / play-from / playlist-from\n"
                + "           常带数据属性 data-source-id / data-tab / data-line / aria-selected / is-active\n"
                + "  ⚠️ 真线路 tab 的标签可能是 <a>，也可能是 <button>、<span>、<li>——\n"
                + "     不要因为「不是 <a>」「没有 href」就把它排除掉！\n\n"
                + "【❌ CTA 按钮 = 一个孤零零的动作指令，描述「做什么」】\n"
                + "  本质：点了就跳转去播放，是一个【动作】，不是一组内容\n"
                + "  特征一（数量）：通常【只有 1 个】——这是它最致命的破绽\n"
                + "  特征二（文字）：是一句命令，见下方动作词表\n"
                + "  特征三（外观）：<a> 或 <button>，class 常含 btn / button / primary / accent / danger\n"
                + "                 / play-now / cloud-play / video-info-play / btn-important / btn-large\n"
                + "  特征四（位置）：往往单独占一行、居中或靠左，位于线路 tabs 的上方，或海报/简介旁边\n"
                + "  特征五（链接）：href 可能是 \"#\" 或空（纯 JS 触发器，不是真实页面）\n\n"
                + "【🔑 判定口诀一：\"这个视频\"朗读测试】\n"
                + "  把候选文字后面加上「这个视频」读一遍：\n"
                + "    「全部播放这个视频」「立即播放这个视频」「开始播放这个视频」 → 通顺 = 动作指令 = CTA ❌\n"
                + "    「国内无广告这个视频」「m3u8这个视频」「蓝光这个视频」       → 不通顺 = 来源名 = 真线路 ✅\n\n"
                + "【🔑 判定口诀二：数量否决（最有效！一眼识破「全部播放」类误判）】\n"
                + "  在脑海里执行你准备输出的选择器，数一数它在页面上能匹配到【几个】元素。\n"
                + "  ⚠️ 只匹配到 1 个 → 它【绝对不可能】是「线路数组」，立刻废弃，不许输出！\n"
                + "  举例：页面里只有一个孤零零的「全部播放」按钮，它没有兄弟元素，\n"
                + "        「全部播放」是一个动作（让你去播放全部），不是一条线路（不是某路资源）。\n"
                + "        把它当线路，等于把「开门」当成一个房间——彻底错了。\n\n"
                + "【🔑 判定口诀三：\"XX播放\"形状匹配（最通用，优先级最高，不用背词表）】\n"
                + "  直接看【文字形状】：\n"
                + "    形如「□□播放」「□□观看」「播放□□」「观看□□」的文字 → 【一律判定为动作按钮(CTA)】！\n"
                + "    （□□ = 0~6 个任意汉字 / 字母 / 数字）\n"
                + "  已见过的实例：全部播放、立即播放、在线播放、开始播放、点击播放、免费播放、高清播放、\n"
                + "                网盘播放、极速播放、播放全集、播放正片、立即观看、免费观看、在线观看……\n"
                + "  以后碰到没见过的新词（比如「一键播放」「智能播放」），只要长得像「XX播放」，就按 CTA 处理。\n"
                + "  ✅ 正确处理 CTA = 点进它指向的页面，去那里拿真实线路和真实集数。\n\n"
                + "  ⚠️ 两个例外，别误伤：\n"
                + "    (1) 页面里同时存在【≥2 个不同】的「XX播放」文字（如「云播放」「快播放」并排）→\n"
                + "        它们更可能是一组线路 tab，按【真线路】处理，不要当成 CTA。\n"
                + "    (2) 「播放列表」「播放线路」「播放地址」「播放源」这类是【栏目/区块标题】，不是按钮，跳过。\n\n"
                + "===== 📌 真实案例对照（照着这个理解，这是最常见也最容易错的结构）=====\n"
                + "某真实站点的详情页里【同时】存在下面两段，正确答案见后：\n\n"
                + "〔第一段 —— CTA 动作按钮区，三个按钮并排，其中只有一个是动作〕\n"
                + "  <div class=\"cap-detail-actions\">\n"
                + "    <a href=\"/\" class=\"cap-btn\">首页</a>\n"
                + "    <a href=\"/soujuplay/10abat2/2/39430471.html\" class=\"cap-btn cap-btn-accent\">立即播放</a>\n"
                + "    <button id=\"btn-favorite\" class=\"cap-btn\">收藏</button>\n"
                + "  </div>\n\n"
                + "〔第二段 —— 真线路区，3 个同结构兄弟 button + 各自独立的集数面板〕\n"
                + "  <div class=\"cap-chip-row\" data-play-source-tabs aria-label=\"播放线路\">\n"
                + "    <button data-play-source-tab data-source-id=\"2\" class=\"cap-chip is-active\">国内无广告</button>\n"
                + "    <button data-play-source-tab data-source-id=\"3\" class=\"cap-chip\">海外</button>\n"
                + "  </div>\n"
                + "  <div class=\"cap-play-source\" data-play-source-panel data-source-id=\"2\">\n"
                + "    <div class=\"cap-episode-grid\">\n"
                + "      <a href=\"/soujuplay/10abat2/2/39430471.html\" class=\"cap-episode\"><span>第1期</span></a>\n"
                + "    </div>\n"
                + "  </div>\n\n"
                + "✅ 正确答案（走【分支A】）：\n"
                + "  · 线路数组 = p:button[data-play-source-tab]   ← 能选出 3 个，是真线路\n"
                + "                                                 （注意：不是 <a>，没有 href，这完全正常）\n"
                + "  · 线路标题 = p:button->text                   ← 取出「国内无广告」「海外」，是来源名\n"
                + "  · 播放数组 = p:div.cap-play-source            ← 取【面板层】，不是最外层容器\n"
                + "  · 播放列表 = p:a.cap-episode\n"
                + "  · 播放标题 = p:a.cap-episode span->text       ← 集名在里面的 <span> 里\n"
                + "  · 播放链接 = p:a.cap-episode->href\n"
                + "  · 播放页URL = /soujuplay/10abat2/2/39430471.html  ← CTA 的 href，顺手填上，程序会跟进校验\n\n"
                + "❌ 典型错误（千万别这么干）：\n"
                + "  · 看到「立即播放」就把线路和选集全留空          → 白白丢掉 3 条能用的真线路\n"
                + "  · 把「立即播放」当成一条线路写进线路数组        → 那是动作不是来源\n"
                + "  · 播放数组选成最外层（把 3 个面板都装起来的那个）→ 各线路集数会混成一团\n"
                + "  · 因为线路 tab 是 <button> 没有 href 就跳过它   → 漏掉真线路\n\n"
                + "〔反例 —— 「全部播放」孤零零一个时该怎么判〕\n"
                + "  <div class=\"play-box\">\n"
                + "    <a href=\"/play/abc-1-1.html\" class=\"btn-primary\">全部播放</a>\n"
                + "  </div>\n"
                + "  这里【只有 1 个】元素，没有兄弟，不构成「数组」——它是动作，不是线路。\n"
                + "  ✅ 正确答案（走【分支B】）：播放页URL = /play/abc-1-1.html，其余 6 个播放字段全部留空 \"\"。\n\n"
                + "【已知 CTA 动作词（与上面的形状规则等价，供你对照检查）】\n"
                + "  " + CTA_WORDS.replace("|", "、") + "\n\n"
                + "【⚠️ 不要互相排斥：真线路与 CTA 可以【同时存在】】\n"
                + "  大量站点（如带 cap-chip-row / data-play-source-tabs 的站）详情页里既有「立即播放」大按钮，\n"
                + "  又有「国内无广告/海外」等多个真线路 tab。这是【正常的】：\n"
                + "    · 大按钮 = 快捷入口（点它跳到播放页，默认走第一条线路）\n"
                + "    · tabs     = 真正的多条线路（每条下面有自己的选集）\n"
                + "  ✅ 正确做法：线路/选集照常输出，同时把按钮的 href 也填进「播放页URL」（程序会用它做二次校验）。\n"
                + "  ❌ 错误做法：因为有按钮，就把线路和选集全部清空——这样会白白丢掉已经能用的数据！\n";
    }

    /** Step1: AI 负责识别【站名 + 框架 + 分类(名称$ID) + 分类url模板】。
     *  分类url 模板由 AI【直接阅读首页HTML里的真实分类链接】推导（前缀与ID都取自真实href）。
     *  重点增强【分类多路径识别】：
     *    - 分类ID 可能是数字 / 英文slug / 拼音slug(如 guo-chan-ju、dian-ying-pian)，一律原样提取
     *    - 同一站内不同分类可能走【不同路径结构】：能归并则用统一模板，不能归并则输出"特殊分类链接"
     *    - 分类页第一页与后续页 URL 不同时输出"首页特例"（XBPQ 用方括号附加在分类url末尾）
     *    - 两级分类(大分类→子分类)时优先取【子分类】，因为大分类页常是聚合页，每类只摆几部 */
    private String buildPrompt1(String html, String url) {
        return "你是视频网站解析专家，必须严格按照【XBPQ(小暴脾气)爬虫框架】规则输出。\n\n"
                + commonRules()
                + "===== 任务：从首页 HTML 识别【站名 / 框架 / 分类 / 分类链接模板】=====\n\n"
                + "【第一步：找到所有真实分类链接】\n"
                + "  在导航、菜单、分类条、页脚分类等区块里找指向【影视内容列表页】的 <a> 链接。\n"
                + "  排除：首页、搜索、排行、热门、最新、推荐、专题、片单、演员、登录、注册、APP下载、关于、留言、友链、公告。\n\n"
                + "【第二步：读懂分类ID（三种形态都可能，一律原样提取，禁止改写）】\n"
                + "  ① 数字型：/vodshow/1.html、/type/2.html        → ID = 1、2\n"
                + "     ⚠️ 可能不连续，也可能是 21/23/99 这类大数，照抄，不要改成 1/2/3。\n"
                + "  ② 英文slug型：/movie/list、/type/dongman       → ID = movie、dongman\n"
                + "  ③ 拼音slug型：/category/guo-chan-ju、/category/dian-ying-pian → ID = guo-chan-ju、dian-ying-pian\n"
                + "     ⚠️ 拼音slug 里的连字符【必须保留】，它是ID的一部分，不要截断成 guo，也不要删成 guochanju。\n"
                + "  ⚠️ 严禁想当然：电影不一定是1（有的站电影=20），绝对禁止按常见框架规律编造ID。\n\n"
                + "【第三步：⚠️ 分类【多路径】识别（本次重点！）】\n"
                + "  很多站的分类链接【不是】一个模板能覆盖的，常见四种情况，请逐条比对：\n\n"
                + "  (1) 路径结构一致，只有ID不同 → 归并成一个模板（最常见）\n"
                + "      例：/category/guo-chan-ju、/category/ou-mei-ju、/category/dong-zuo-pian\n"
                + "      → 分类url = https://站点/category/{cateId}\n\n"
                + "  (2) 同一站存在【多种路径结构】→ 取【覆盖分类最多】的那种做主模板，其余写进「特殊分类链接」\n"
                + "      例：电影走 /movie/{cateId}.html，电视剧走 /tv/list-{cateId}-{catePg}.html\n"
                + "      → 分类url         = https://站点/movie/{cateId}.html\n"
                + "      → 特殊分类链接    = 电视剧$https://站点/tv/list-{cateId}-{catePg}.html\n"
                + "      （多个分类共用同一个特殊链接时，分类名用顿号或逗号隔开：电影、电视剧$链接）\n\n"
                + "  (3) 分类是【两级】的（大分类 → 子分类）→ 优先把【子分类】作为最终分类\n"
                + "      原因：大分类页往往只是个聚合页（每个子分类摆一行、每样几部片），点进去内容不全；\n"
                + "            子分类页才是完整列表。两级链接通常都能在首页或分类页源码里找到，请优先收集子分类。\n"
                + "      例：首页有 /category/lian-xu-ju（连续剧，大分类），其页面里又列出\n"
                + "          /category/guo-chan-ju（国产剧）、/category/ou-mei-ju（欧美剧）、/category/han-guo-ju（韩国剧）…\n"
                + "      → 应把 国产剧/欧美剧/韩国剧… 这些【子分类】作为「分类」输出，而不是只输出\"连续剧\"一个。\n\n"
                + "  (4) 第一页与其他页【URL 不同】→ 把第一页链接填进「首页特例」\n"
                + "      例一：第一页 /category/{cateId}，第二页起 /category/{cateId}?page=2\n"
                + "            → 分类url = https://站点/category/{cateId}?page={catePg}；首页特例 = https://站点/category/{cateId}\n"
                + "      例二：第一页 /type/{cateId}.html，第二页起 /type/{cateId}-page-2.html\n"
                + "            → 分类url = https://站点/type/{cateId}-page-{catePg}.html；首页特例 = https://站点/type/{cateId}.html\n\n"
                + "【第四步：翻页识别】观察分类页有没有翻页链接（下一页 / 页码 / 加载更多）\n"
                + "  常见形态：/page/2/、?page=2、-pg-2.html、/2.html、p=2、offset=20\n"
                + "  把页码那一段替换成 {catePg} 写进分类url。\n"
                + "  ⚠️ 若站点是【无限滚动 / JS加载更多】、静态源码里根本看不到翻页链接 → 不要写 {catePg}，只保留 {cateId}。\n\n"
                + "【⚠️ 分类url 铁律】\n"
                + "  · 必须【原样复制】真实链接里的固定后缀！\n"
                + "    某些站形如 /vshow/1-----------.html，后面一长串短横线是地区/年份筛选项的【空占位符】，\n"
                + "    正确模板 = /vshow/{cateId}-----------.html（保留 -----------，只把 1 换成 {cateId}）\n"
                + "    ❌ 绝对不能丢掉短横线写成 /vshow/{cateId}.html！\n"
                + "  · 目录式URL末尾的 / 要保留（如 /type/guoman/）\n"
                + "  · 路径前缀（/vod、/vodshow、/list、/type、/id、/category、/vshow、/cupfox-list …）各站不同，必须从真实 href 抄\n\n"
                + "【输出字段】\n"
                + "  1. \"站名\": 网站真实名称（从 <title>/logo 提取，不要用域名）\n"
                + "  2. \"框架\": 苹果CMS V10 / 苹果CMS / 海洋CMS / 其他PHP影视站 / 未知\n"
                + "  3. \"分类\": 分类名$分类ID#分类名$分类ID（$ 分隔名与ID，# 分隔不同分类）\n"
                + "  4. \"分类url\": 主模板，【必须】含 {cateId}，有翻页则含 {catePg}\n"
                + "  5. \"首页特例\": 第一页URL与后续页不同才填，否则留空 \"\"\n"
                + "  6. \"特殊分类链接\": 路径结构与主模板不一致的分类才填，\n"
                + "     格式 分类名$链接模板#分类名2$链接模板2（多个分类共用用顿号分隔），没有则留空 \"\"\n\n"
                + "网站: " + url + "\n\n"
                + "首页HTML:\n" + html + "\n\n"
                + "只返回JSON不要解释不要markdown:\n"
                + "{\"站名\":\"\",\"框架\":\"\",\"分类\":\"\",\"分类url\":\"\",\"首页特例\":\"\",\"特殊分类链接\":\"\"}";
    }

    /** Step2: 分析分类页，生成影片列表截取规则并给出详情页链接。
     *  关键改进：强制AI原样抄写class名(保留空格/连字符)，给正确示例参考，
     *  检测JS动态渲染(无列表时回退首页)，禁止编造不存在的选择器。 */
    /** Step2: 分析分类页，生成影片列表截取规则并给出详情页链接。
     *  增强点：
     *   - 【多区块聚合页】识别：一个页面里塞了 N 个子分类区块（区块标题 + 几部片 + "查看全部"），
     *     此时数组选择器必须能【跨区块】一次匹配所有卡片，否则列表会缺一大半
     *   - 图片属性 / 片名位置必须看真实源码：可能是 src 而非 data-original，片名可能在 alt 或 title 里 */
    private String buildPrompt2(String html, String url) {
        return "你是视频网站解析专家，必须严格按照【XBPQ(小暴脾气)爬虫框架】规则输出。\n\n"
                + commonRules()
                + "===== 任务：分析【分类页/列表页】，提取影片列表规则 =====\n\n"
                + "【⚠️ 先判断页面类型——这一步直接决定「数组」怎么写】\n"
                + "  ■ 单区块列表页：一整片连续的影片卡片（最常见）\n"
                + "      → 数组取【包裹一部影片的最小重复单元】即可\n"
                + "  ■ 【多区块聚合页】：一个页面里塞了【多个子分类区块】，\n"
                + "      每个区块 = 区块标题（如 国产剧 / 欧美剧）+ 若干影片卡片 + \"查看全部\"或\"更多\"链接\n"
                + "      → ⚠️ 数组选择器必须能【一次性跨区块匹配到全部卡片】！\n"
                + "      ✅ 正确：直接用【卡片自身】的 class，如 p:a.cap-movie-card（不论它落在哪个区块里）\n"
                + "      ❌ 错误：用某个区块的内部容器，如 p:div.cap-grid a（只会匹配到局部，列表会大量缺片）\n"
                + "      判断依据：源码里是否出现多个 .cap-section / .module / .box / .vodlist 之类的区块容器，\n"
                + "               且每个区块内都有标题 + 若干卡片。\n\n"
                + "【字段写法】\n"
                + "  · \"数组\": 包裹每部影片的【最小重复单元】。两种写法皆可：\n"
                + "      ① jsoup 纯路径：p:ul.stui-vodlist li / p:div.module-item / p:a.cap-movie-card\n"
                + "      ② 截取语法：class=\"xxx\"&&</a>（截取每个卡片的完整标签块）\n"
                + "  · \"标题\": 卡片内取片名。p:a->text / p:h4->text / p:a->title / p:img->alt\n"
                + "     ⚠️ 片名不一定在文字里！可能写在 <a title=\"片名\"> 的 title 属性，或 <img alt=\"片名\"> 的 alt。\n"
                + "        卡片里看不到片名文字时，优先试 ->title 或 ->alt。\n"
                + "  · \"图片\": 卡片内取海报。⚠️ 必须看 <img> 标签【真实】用的属性名：\n"
                + "       data-original / data-src / data-lazy-src / data-original-src / src\n"
                + "       ❌ 不要想当然写 data-original——很多自研站（如 soujunet）直接用的就是 src。\n"
                + "  · \"链接\": 卡片内取详情页 href，p:a->href（取包裹片名/海报的那个 <a>）\n"
                + "  · \"详情页链接\": 页面中【第一个】影片的完整详情URL（程序会用它在下一步分析播放规则）\n\n"
                + "【各框架真实选择器参考（仅示意格式！必须先看源码里的真实 class 再决定）】\n"
                + "  苹果CMS+stui: 数组 p:ul.stui-vodlist li ／ 标题 p:a.stui-vodlist__thumb->text ／ 图片 p:a.stui-vodlist__thumb->data-original\n"
                + "  苹果CMS+myui: 数组 p:ul.myui-vodlist li ／ 标题 p:a.myui-vodlist__thumb->text ／ 图片 p:a.myui-vodlist__thumb->data-original\n"
                + "  苹果CMS+mx:   数组 p:div.module-item     ／ 标题 p:a.module-item__name->text\n"
                + "  自研模板:     数组 p:div.public-list-div ／ 标题 p:a.public-list-exp->title ／ 图片 p:img->data-src\n"
                + "  ⚠️ 以上只是【格式示意】，绝不可以直接照抄！必须从下方 HTML 里找这个站真正用的 class。\n\n"
                + "页面: " + url + "\n\n"
                + "分类页HTML:\n" + html + "\n\n"
                + (html.isEmpty() ? "HTML为空，所有字段返回空字符串。\n" : "")
                + "只返回JSON不要解释不要markdown:\n"
                + "{\"数组\":\"\",\"标题\":\"\",\"图片\":\"\",\"链接\":\"\",\"详情页链接\":\"\"}";
    }

    /** Step3: 分析详情页，生成播放线路与播放选集截取规则。
     *  关键：绝大多数视频站详情页都有「直接播放/立即播放」按钮混在播放区域内，
     *  AI 必须用【数量+位置+文字特征】三重标准把它和真正的线路/选集区分开。
     *  【重要】若详情页只有CTA按钮而无真正多线路，AI 必须返回该按钮的href作为"播放页URL"，
     *  程序会自动跟进去抓取播放页获取真实线路/集数。 */
    /** Step3: 分析详情页，生成播放线路与播放选集截取规则。
     *  核心：用【三分支决策】取代粗暴的"一票否决"——
     *    分支A: 有真线路(≥2个来源名兄弟元素) → 输出线路+选集（CTA可共存，其href填播放页URL）
     *    分支B: 无真线路但有CTA动作按钮   → 只填播放页URL，程序跟进播放页取线路
     *    分支C: 两者皆无(单线路站)        → 详情页直接输出选集
     *  这样对 soujunet.com 这类"详情页既有立即播放按钮、又有国内无广告/海外多线路"的站，
     *  不会因为存在CTA而白白丢掉已经能用的真实线路。 */
    private String buildPrompt3(String html, String url) {
        return "你是视频网站解析专家，必须严格按照【XBPQ(小暴脾气)爬虫框架】规则输出。\n\n"
                + commonRules()
                + lineVsCtaRules()
                + "===== 决策流程（严格按 分支A → 分支B → 分支C 顺序判断，命中即停止）=====\n\n"
                + "【第一步：找真线路】扫描整个页面，找【≥2 个同级兄弟元素】且文字是来源名的容器。\n"
                + "  → 找到了 → 进入【分支A】\n"
                + "  → 找不到 → 进入第二步\n\n"
                + "【第二步：找 CTA 动作按钮】扫描页面，找文字命中动作词表的 <a>/<button>/<span>。\n"
                + "  → 找到了 → 进入【分支B】\n"
                + "  → 找不到 → 进入【分支C】\n\n"
                + "──────────────────────────────\n"
                + "【分支A：有真线路 —— 最常见，优先走这条】\n"
                + "  · 线路数组   = 线路 tab 的【容器】选择器（纯路径，必须能选出 ≥2 个 tab）\n"
                + "  · 线路标题   = 从单个 tab 取线路名（p:button->text / p:a->text / p:span->text / p:li->text）\n"
                + "  · 播放数组   = 单条线路对应的【集数面板容器】（每个线路一个面板，取面板这一层）\n"
                + "  · 播放列表   = 单个【剧集节点】（通常是 <a>，纯路径）\n"
                + "  · 播放标题   = 剧集名（如 p:a->text，取出的文字是「第1集」「正片」「HD」这种）\n"
                + "  · 播放链接   = 剧集地址 p:a->href\n"
                + "  · 播放页URL  = 页面【若同时】有CTA按钮，顺手把它的 href 填这里；没有就留空 \"\"\n"
                + "  ⚠️ 播放数组与播放列表的层级关系：\n"
                + "     线路容器 > 每线路面板(播放数组) > 集数格子 > <a>(播放列表)\n"
                + "     播放数组千万别选到把所有面板都装起来的最外层，否则各线路的集数会混在一起。\n\n"
                + "【分支B：无真线路，但有 CTA 按钮】\n"
                + "  说明线路和选集藏在【播放页】里——必须点了那个按钮进去才看得见真实线路和集数。\n"
                + "  · 播放页URL = CTA 按钮的 href（真实URL，不要 # 或空）\n"
                + "  · 线路数组 / 线路标题 / 播放数组 / 播放列表 / 播放标题 / 播放链接 全部留空字符串 \"\"\n"
                + "  · ⚠️ 若 href 是 \"#\" 或 javascript:（纯JS触发），就在按钮附近找含 play/player/bofang/vod 的真实链接\n"
                + "  · 程序会自动跟进这个播放页取线路，所以这里【不需要也不允许】你猜线路。\n\n"
                + "【分支C：既无真线路，也无 CTA（单线路站）】\n"
                + "  说明集数直接铺在详情页，没有多线路概念。\n"
                + "  · 输出 播放数组 / 播放列表 / 播放标题 / 播放链接\n"
                + "  · 线路数组 / 线路标题 / 播放页URL 留空 \"\"\n\n"
                + "===== 选集长什么样（分支A/C 用于校验）=====\n"
                + "  真选集文字：第1集 / 第01集 / EP1 / 01 / 正片 / HD / 预告 / 番外 / 上 / 下\n"
                + "  不是选集：影片名、演员名、查看全部、热门推荐、相关推荐、站点导航词\n"
                + "  每个 <a> 的 href 必须是真实播放页/播放地址，不是 \"#\"，也不是分类页链接\n\n"
                + "===== 其他字段 =====\n"
                + "  · \"简介\": 剧情简介文本（从影片信息区/描述段落提取，【不是】任何按钮的文字）\n"
                + "  · \"解析\": 解析接口URL（页面里若有形如 xxx.php?url= 的解析接口则填，否则留空）\n\n"
                + "===== 输出前自检（必做）=====\n"
                + "  1. 我的「线路数组」能选出 ≥2 个元素吗？只有1个 → 废弃，改走分支B/C！\n"
                + "  2. 我的「线路标题」取出来的文字，念\"这个视频\"通顺吗？通顺 → 是CTA，废弃！\n"
                + "  3. 我的「播放列表」取出来的是「第X集」这种吗？是「全部播放/影片名」→ 错了！\n\n"
                + "页面: " + url + "\n\n"
                + "详情页HTML:\n" + html + "\n\n"
                + (html.isEmpty() ? "HTML为空，所有字段返回空字符串。\n" : "")
                + "只返回JSON不要解释不要markdown:\n"
                + "{\"简介\":\"\",\"播放页URL\":\"\",\"线路数组\":\"\",\"线路标题\":\"\",\"播放数组\":\"\",\"播放列表\":\"\",\"播放标题\":\"\",\"播放链接\":\"\",\"解析\":\"\"}";
    }


    /** Step4: 分析【播放页】（从详情页"立即播放"按钮跳转过来的页面）。
     *  播放页和详情页不同——它通常直接包含真正的多线路tabs和剧集列表，
     *  不再有"立即播放"CTA按钮。这是获取真实线路/集数的最佳来源。
     *  此 prompt 只输出线路/集数相关字段，不重复输出简介等详情页已有字段。 */
    /** Step4: 分析【播放页】（从详情页 CTA 按钮跳转过来的页面）。
     *  播放页通常直接包含真正的多线路 tabs 与完整剧集列表，是提取真实播放规则的最佳来源。
     *  ⚠️ 但播放页里【依然可能】残留动作按钮（如选集区上方的「全部播放」、播放器下方的「立即播放」），
     *     所以这里要跑一遍与详情页完全相同的【真线路 vs CTA】判定，靠数量否决 + 动作词表区分。 */
    private String buildPrompt4(String html, String url) {
        return "你是视频网站解析专家，必须严格按照【XBPQ(小暴脾气)爬虫框架】规则输出。\n\n"
                + "===== 背景 =====\n"
                + "这是视频站的【播放页】——用户在详情页点击「立即播放 / 全部播放 / 直接播放」这类按钮后跳转到的页面。\n"
                + "与详情页相比，这个页面的线路和集数通常更完整、结构更干净，是提取真实播放规则的最佳来源。\n"
                + "⚠️ 但别掉以轻心：播放页里依然可能残留动作按钮（选集区上方的「全部播放」、播放器下方的「立即播放」等），\n"
                + "   所以本页同样要严格区分真线路与 CTA，判定规则与详情页完全一致。\n\n"
                + commonRules()
                + lineVsCtaRules()
                + "===== 决策流程（分支A → 分支B → 分支C，命中即停止）=====\n\n"
                + "【分支A：有真线路（≥2 个来源名兄弟元素）】→ 输出全部播放字段\n"
                + "  · 线路数组 = 线路 tab 的【容器】（必须能选出 ≥2 个 tab）\n"
                + "  · 线路标题 = 单个 tab 的线路名（p:button->text / p:a->text / p:span->text / p:li->text）\n"
                + "  · 播放数组 = 单条线路对应的【集数面板容器】\n"
                + "      ⚠️ 若集数是【按线路分面板】展示（每个线路一个 div，里面才是该剧路的集），\n"
                + "         播放数组要选【面板】这一层（如 p:div.cap-play-source、p:div[data-source-id]），\n"
                + "         ❌ 不要选把所有面板都装起来的最外层，否则各线路的集数会混成一大团。\n"
                + "  · 播放列表 = 单个剧集节点（通常是 <a>，纯路径）\n"
                + "  · 播放标题 = 剧集名 p:a->text（取出来是「第1集」「正片」「HD」这种）\n"
                + "  · 播放链接 = 剧集地址 p:a->href\n\n"
                + "【分支B：无真线路，但有动作按钮】→ 说明还得再跳一层才能看到线路\n"
                + "  · 7 个字段全部留空字符串 \"\"，程序会放弃本页结果（不要硬凑一个假线路出来）\n\n"
                + "【分支C：无真线路也无 CTA，但页面直接铺着集数】→ 单线路站\n"
                + "  · 只输出 播放数组 / 播放列表 / 播放标题 / 播放链接\n"
                + "  · 线路数组 / 线路标题 留空 \"\"\n\n"
                + "【播放页特有提示】\n"
                + "  · 若页面里能直接看到 m3u8 / mp4 直链，可填进「解析」，或直接作为播放链接\n"
                + "  · 集名可能是「第1集」「EP01」「01」「HD」「正片」「预告」，这些都算合法选集\n"
                + "  · 有些站的线路 tab 是 <button> 且【没有 href】（用 JS 切换面板），这完全正常，照常输出\n\n"
                + "===== 输出前自检 =====\n"
                + "  1. 线路数组能选出 ≥2 个元素吗？只有1个 → 不是线路，废弃！\n"
                + "  2. 线路标题取出来的文字，念\"这个视频\"通顺吗？通顺 → 是 CTA，废弃！\n"
                + "  3. 播放列表取出来的是「第X集/正片/HD」吗？是「全部播放/影片名」→ 错了！\n\n"
                + "页面: " + url + "\n\n"
                + "播放页HTML:\n" + html + "\n\n"
                + (html.isEmpty() ? "HTML为空，所有字段返回空字符串。\n" : "")
                + "只返回JSON不要解释不要markdown:\n"
                + "{\"线路数组\":\"\",\"线路标题\":\"\",\"播放数组\":\"\",\"播放列表\":\"\",\"播放标题\":\"\",\"播放链接\":\"\",\"解析\":\"\"}";
    }

    /** 合并结果生成最终配置。
     *  ext 的“分类url”为含{cateId}的模板，“分类”为 名称$ID#...，两者配合供 XBPQ 拼出各分类页
     *  （参考小暴脾气官方写法：茄子/小友/冰河等 ext 既有"分类url"模板又有"分类"字段）。
     *  影片列表规则来自 Step2（数组/标题/图片/链接），播放规则来自 Step3。 */
    private String mergeConfig(String url, String siteName, String cate, String cateUrlTpl, JsonObject step1, JsonObject step2, JsonObject step3) {
        JsonObject ext = new JsonObject();
        if (!TextUtils.isEmpty(cateUrlTpl)) ext.addProperty("分类url", cateUrlTpl);
        if (!TextUtils.isEmpty(cate)) ext.addProperty("分类", cate);
        // 【分类多路径】第一页URL与其他页不同 → 按 XBPQ 写法用英文中括号附加在分类url末尾：
        //   分类url = 主模板[首页链接]，如 https://x/category/{cateId}?page={catePg}[https://x/category/{cateId}]
        //   （XBPQ 文档："第一页与其他页不一样的，直接用英文中括号加在分类url末尾"）
        String firstPage = step1 == null ? "" : getString(step1, "首页特例");
        if (!TextUtils.isEmpty(firstPage) && !TextUtils.isEmpty(cateUrlTpl)
                && !firstPage.equals(cateUrlTpl) && !cateUrlTpl.contains("[")) {
            ext.addProperty("分类url", cateUrlTpl + "[" + firstPage + "]");
        }
        // 【分类多路径】部分分类的链接结构与主模板不一致 → 用"特殊分类链接"单独指定
        //   格式：分类名$链接模板#分类名2$链接模板2（多个分类共用同一链接用顿号/逗号隔开）
        String special = step1 == null ? "" : getString(step1, "特殊分类链接");
        if (!TextUtils.isEmpty(special)) ext.addProperty("特殊分类链接", special);
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
            for (String lk : lineKeys) {
                if (ext.has(lk)) {
                    String lv = ext.get(lk).getAsString();
                    // 形状匹配(XX播放) + 词表匹配，任一命中即为假线路
                    if (isCtaText(lv)) {
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
                // ⚠️ 只匹配【典型CTA按钮class】，不再用裸 "btn"（会误伤 cap-btn / tab-btn 等真线路容器）
                java.util.regex.Pattern ctaSelPat = java.util.regex.Pattern.compile(
                        "btn-important|btn-large|btn-primary|btn-danger|btn-block"
                                + "|play-now|play-btn|btn-play|cloud-play|now-play|video-play",
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
                if (isCtaText(lt)) {
                    for (String rk : new String[]{"线路数组", "线路", "线路标题", "播放数组", "播放列表", "播放标题", "播放链接"}) {
                        ext.remove(rk);
                    }
                    dirty = true;
                }
            }
            // 规则2：检测假选集（AI把片名当成剧集名填进了"播放列表/播放标题"，如"九门[全集]"）
            //  ⚠️ 关键修正：只在值【不是选择器】时才按文本值判定！
            //  旧实现未做此区分，把 "p:a.cap-episode" 这类【合法选择器】当成"不含第X集的片名"误删，
            //  导致绝大多数站点的播放列表/播放标题/播放链接被无差别清空。
            if (!dirty) {
                String[] epKeys = {"播放列表", "播放标题"};
                java.util.regex.Pattern epPat = java.util.regex.Pattern.compile(
                        "第\\d+集|第0?\\d+话|EP\\d+|^\\d+$|^[①②③④⑤⑥⑦⑧⑨⑩]+$");
                for (String ek : epKeys) {
                    if (ext.has(ek)) {
                        String ev = ext.get(ek).getAsString();
                        if (isSelectorLike(ev)) continue;   // 是选择器/截取规则 → 放行，不按文本值判
                        // 走到这里说明填的是【文本值】：不含剧集格式且长度>2 → 大概率是片名而非集数
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

    /** 判断一个配置值是否像【选择器/截取规则】而非【文本值】。
     *  XBPQ 的规则形态：p:(jsoup选择器) / j:(json路径) / a&&b(正则) / xxx分割(符) / 含 ->属性。
     *  用于 sanitizeCtaLines 里区分"AI填的是规则"还是"AI填的是取出来的字面值"，避免误杀合法规则。 */
    private boolean isSelectorLike(String v) {
        if (TextUtils.isEmpty(v)) return false;
        String s = v.trim();
        return s.startsWith("p:") || s.startsWith("j:") || s.contains("&&")
                || s.contains("分割(") || s.contains("->") || s.startsWith("/");
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

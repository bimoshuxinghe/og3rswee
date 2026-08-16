package com.fongmi.android.tv.ui.dialog;

import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;

import androidx.fragment.app.Fragment;
import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.databinding.DialogXbpqBinding;
import com.fongmi.android.tv.event.RefreshEvent;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.UrlUtil;
import com.github.catvod.net.OkHttp;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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

public class XbpqDialog extends BaseAlertDialog {

    private static final String JAR = "assets://1118.jar";
    private static final String API = "csp_XBPQ";
    private static final String AI_URL = "https://api.siliconflow.cn/v1/chat/completions";
    private static final String AI_MODEL = "deepseek-ai/DeepSeek-R1-0528-Qwen3-8B";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler HANDLER = new Handler(Looper.getMainLooper());

    private DialogXbpqBinding binding;

    public static void show(Fragment fragment) {
        new XbpqDialog().show(fragment.getChildFragmentManager(), null);
    }

    /** 直连 client：不走应用内代理，避免代理导致抓取/AI 调用超时 */
    private static OkHttpClient directClient(long timeout) {
        return OkHttp.client().newBuilder()
                .proxy(Proxy.NO_PROXY)
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    protected ViewBinding getBinding() {
        return binding = DialogXbpqBinding.inflate(getLayoutInflater());
    }

    @Override
    protected MaterialAlertDialogBuilder getBuilder() {
        return builder().setView(getBinding().getRoot());
    }

    @Override
    protected void initView() {
        binding.url.requestFocus();
    }

    @Override
    protected void initEvent() {
        binding.url.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) onConfirm();
            return true;
        });
        binding.cancel.setOnClickListener(v -> dismiss());
        binding.confirm.setOnClickListener(v -> onConfirm());
        binding.aiDetect.setOnClickListener(v -> onAiDetect());
    }

    private void onConfirm() {
        String input = binding.url.getText().toString().trim();
        if (TextUtils.isEmpty(input)) {
            Notify.show(R.string.auto_site_empty);
            return;
        }
        if (input.startsWith("{")) {
            addFromJson(input);
        } else {
            addFromUrl(input);
        }
    }

    private void onAiDetect() {
        String input = binding.url.getText().toString().trim();
        if (TextUtils.isEmpty(input)) {
            Notify.show(R.string.auto_site_empty);
            return;
        }
        if (!Setting.hasAiKey()) {
            Notify.show(R.string.auto_site_ai_no_key);
            return;
        }
        if (!input.startsWith("http://") && !input.startsWith("https://")) input = "https://" + input;
        String url = input;
        binding.aiDetect.setEnabled(false);
        Notify.progress(requireContext());
        EXECUTOR.execute(() -> {
            try {
                // Step1: 抓首页，AI 分析分类
                String homeHtml = fetchHtml(url);
                if (TextUtils.isEmpty(homeHtml)) {
                    fail();
                    return;
                }
                JsonObject step1 = callAi(buildPrompt1(homeHtml, url));
                String cateUrl = getString(step1, "分类页链接");
                if (TextUtils.isEmpty(cateUrl)) cateUrl = extractLink(homeHtml, new String[]{"vodshow", "vodtype", "list", "show", "type", "cateId"});
                // Step2: 抓分类页，AI 分析影片列表
                String cateHtml = TextUtils.isEmpty(cateUrl) ? "" : fetchHtml(cateUrl);
                JsonObject step2 = callAi(buildPrompt2(cateHtml, cateUrl));
                String detailUrl = getString(step2, "详情页链接");
                if (TextUtils.isEmpty(detailUrl)) detailUrl = extractLink(cateHtml, new String[]{"voddetail", "detail", "play", "vod", "id"});
                // Step3: 抓详情页，AI 分析播放线路与播放链接
                String detailHtml = TextUtils.isEmpty(detailUrl) ? "" : fetchHtml(detailUrl);
                JsonObject step3 = callAi(buildPrompt3(detailHtml, detailUrl));
                // 合并配置
                String config = mergeConfig(url, step1, step2, step3);
                HANDLER.post(() -> {
                    binding.aiDetect.setEnabled(true);
                    Notify.dismiss();
                    if (TextUtils.isEmpty(config)) {
                        Notify.show(R.string.auto_site_ai_failed);
                        return;
                    }
                    binding.url.setText(config);
                    addFromJson(config);
                });
            } catch (Exception e) {
                HANDLER.post(() -> {
                    binding.aiDetect.setEnabled(true);
                    Notify.dismiss();
                    Notify.show("AI识别失败：" + e.getMessage());
                });
            }
        });
    }

    private void fail() {
        HANDLER.post(() -> {
            binding.aiDetect.setEnabled(true);
            Notify.dismiss();
            Notify.show(R.string.auto_site_ai_failed);
        });
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

    /** Step1: 分析首页，生成分类配置并给出分类页链接 */
    private String buildPrompt1(String html, String url) {
        return "你是一个视频网站解析专家。下面是视频网站 " + url + " 的首页 HTML 源码。\n\n"
                + "请完成以下任务：\n"
                + "1. 找出网站的分类导航列表，生成\"分类\"字段，格式为\"分类名$分类ID#分类名$分类ID\"（如\"电影$1#电视剧$2\"），分类ID从分类链接中提取数字部分，只保留主要分类（电影/电视剧/综艺/动漫等），不要包含首页/搜索/登录等非内容分类\n"
                + "2. 找出分类页链接模板，生成\"分类url\"字段，必须包含 {cateId}（分类ID）和 {catePg}（页码）占位符，如 https://example.com/vodshow/id/{cateId}/page/{catePg}.html\n"
                + "3. 给出一个真实的分类页完整链接（用于下一步抓取），生成\"分类页链接\"字段\n\n"
                + "首页 HTML 源码（截断）：\n" + html + "\n\n"
                + "只返回一个 JSON 对象，不要输出任何解释文字、不要用 markdown 代码块包裹：\n"
                + "{\n"
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
        root.addProperty("name", host);
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

    private void addFromUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://" + url;
        String host = UrlUtil.host(url);
        if (TextUtils.isEmpty(host)) {
            Notify.show(R.string.auto_site_empty);
            return;
        }
        String ext = url + ";;";
        if (exists(ext)) return;
        Site site = new Site();
        site.setKey("xbpq_" + host.replace(".", "_"));
        site.setName(host);
        site.setApi(API);
        site.setExt(ext);
        site.setJar(JAR);
        site.setType(3);
        save(site);
    }

    private void addFromJson(String json) {
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            String key = obj.has("key") ? obj.get("key").getAsString() : "";
            String name = obj.has("name") ? obj.get("name").getAsString() : "";
            String api = obj.has("api") ? obj.get("api").getAsString() : API;
            String ext = "";
            if (obj.has("ext")) {
                JsonElement extEl = obj.get("ext");
                ext = extEl.isJsonObject() ? App.gson().toJson(extEl.getAsJsonObject()) : extEl.getAsString();
            }
            int type = obj.has("type") ? obj.get("type").getAsInt() : 3;
            if (TextUtils.isEmpty(ext)) {
                Notify.show(R.string.auto_site_empty);
                return;
            }
            if (TextUtils.isEmpty(key)) {
                String host = UrlUtil.host(ext);
                key = "xbpq_" + host.replace(".", "_");
            }
            if (TextUtils.isEmpty(name)) {
                name = UrlUtil.host(ext);
            }
            if (exists(ext)) return;
            Site site = new Site();
            site.setKey(key);
            site.setName(name);
            site.setApi(api);
            site.setExt(ext);
            site.setJar(JAR);
            site.setType(type);
            save(site);
        } catch (Exception e) {
            Notify.show(R.string.auto_site_failed);
        }
    }

    private boolean exists(String ext) {
        for (Site site : VodConfig.get().getSites()) {
            if (ext.equals(site.getExt())) {
                Notify.show(R.string.auto_site_exist);
                dismiss();
                return true;
            }
        }
        return false;
    }

    private void save(Site site) {
        site.save();
        VodConfig.get().getSites().add(site);
        RefreshEvent.home();
        Notify.show(R.string.auto_site_success);
        dismiss();
    }
}

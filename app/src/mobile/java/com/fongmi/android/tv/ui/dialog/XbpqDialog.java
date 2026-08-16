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
                String html;
                try (Response res = OkHttp.newCall(directClient(30000), url).execute()) {
                    html = res.body() == null ? "" : res.body().string();
                }
                if (TextUtils.isEmpty(html)) {
                    HANDLER.post(() -> {
                        binding.aiDetect.setEnabled(true);
                        Notify.dismiss();
                        Notify.show(R.string.auto_site_ai_failed);
                    });
                    return;
                }
                if (html.length() > 8000) html = html.substring(0, 8000);
                String config = callAi(html, url);
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

    private String callAi(String html, String url) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("model", AI_MODEL);
        body.addProperty("temperature", 0.2);
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", buildPrompt(html, url));
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
            return extractJson(content);
        }
    }

    private String extractJson(String content) {
        if (content == null) return "";
        content = content.replaceAll("(?s) 思考.*?/思考", "").trim();
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) return content.substring(start, end + 1);
        return "";
    }

    private String buildPrompt(String html, String url) {
        return "你是一个视频网站解析专家。请分析下面这个视频网站的 HTML 源码，生成 XBPQ 爬虫配置。\n\n"
                + "XBPQ 配置格式说明（ext 为 JSON 对象）：\n"
                + "- 分类url：分类页链接模板，必须包含 {cateId}（分类ID）和 {catePg}（页码）占位符，如 https://example.com/vodshow/id/{cateId}/page/{catePg}.html\n"
                + "- 分类：分类列表，格式\"分类名$分类ID#分类名$分类ID\"，如\"电影$1#电视剧$2\"\n"
                + "- 简介：详情页剧情介绍截取规则，如\"<p>&&</p>\"\n"
                + "- 搜索数组/搜索标题/搜索链接：搜索结果列表截取规则\n"
                + "- 线路数组/线路标题：播放线路截取规则\n"
                + "- 播放数组/播放链接：播放列表截取规则\n"
                + "- 主页url：网站首页地址\n\n"
                + "网站地址：" + url + "\n\n"
                + "网站首页 HTML 源码（截断）：\n" + html + "\n\n"
                + "请只返回一个 JSON 对象，不要输出任何解释文字、不要用 markdown 代码块包裹，格式如下：\n"
                + "{\n"
                + "  \"key\": \"站点key\",\n"
                + "  \"name\": \"站点名称\",\n"
                + "  \"type\": 3,\n"
                + "  \"api\": \"csp_XBPQ\",\n"
                + "  \"ext\": {\n"
                + "    \"主页url\": \"...\",\n"
                + "    \"分类url\": \"...\",\n"
                + "    \"分类\": \"...\",\n"
                + "    \"简介\": \"...\"\n"
                + "  }\n"
                + "}";
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

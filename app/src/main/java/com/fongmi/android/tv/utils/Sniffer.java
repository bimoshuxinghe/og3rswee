package com.fongmi.android.tv.utils;

import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ClickableSpan;

import com.fongmi.android.tv.api.config.RuleConfig;
import com.fongmi.android.tv.bean.Result;
import com.fongmi.android.tv.bean.Rule;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Trans;
import com.github.catvod.utils.Util;

import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Sniffer {

    public static final Pattern CLICKER = Pattern.compile("\\[a=cr:(\\{.*?\\})\\/](.*?)\\[\\/a]");
    public static final Pattern AI_PUSH = Pattern.compile("(https?|thunder|magnet|ed2k|video):\\S+");
    /**
     * 可直接播放的媒体地址特征。xhtv 为本软件 M3U8 合并下载的专属格式（本质为 MPEG-TS），
     * 必须列入白名单，否则会被误判为需解析的网页地址，导致本地离线播放一直转圈/报解析失败。
     */
    public static final Pattern SNIFFER = Pattern.compile("https?://[^\\s]{12,}\\.(?:m3u8|mp4|mkv|flv|mp3|m4a|aac|mpd|xhtv)(?:\\?.*)?|https?://.*?video/tos[^\\s]*|rtmp:[^\\s]+");

    public static String getUrl(String text) {
        if (Json.isObj(text) || text.contains("$")) return text;
        Matcher m = AI_PUSH.matcher(text);
        if (m.find()) return m.group(0);
        return text;
    }

    public static boolean isVideoFormat(String url) {
        if (url == null || url.isEmpty()) return false;
        // 软件专属合并格式 .xhtv 直接可播，无需任何解析（不受正则长度/规则过滤影响）
        if (url.toLowerCase().contains(".xhtv")) return true;
        Rule rule = getRule(UrlUtil.uri(url));
        for (String exclude : rule.getExclude()) if (url.contains(exclude)) return false;
        for (String exclude : rule.getExclude()) if (Pattern.compile(exclude).matcher(url).find()) return false;
        for (String regex : rule.getRegex()) if (url.contains(regex)) return true;
        for (String regex : rule.getRegex()) if (Pattern.compile(regex).matcher(url).find()) return true;
        if (url.contains("url=http") || url.contains("v=http") || url.contains(".html")) return false;
        return SNIFFER.matcher(url).find();
    }

    public static List<String> getScript(Uri uri) {
        return getRule(uri).getScript();
    }

    private static Rule getRule(Uri uri) {
        if (uri.getHost() == null) return Rule.empty();
        String hosts = TextUtils.join(",", Arrays.asList(UrlUtil.host(uri), UrlUtil.host(uri.getQueryParameter("url"))));
        for (Rule rule : RuleConfig.get().getRules()) for (String host : rule.getHosts()) if (Util.containOrMatch(hosts, host)) return rule;
        return Rule.empty();
    }

    public static SpannableStringBuilder buildClickable(String text, Function<Result, ClickableSpan> factory) {
        SpannableStringBuilder span = new SpannableStringBuilder();
        Matcher matcher = CLICKER.matcher(text);
        int last = 0;
        while (matcher.find()) {
            span.append(text, last, matcher.start());
            int start = span.length();
            span.append(Trans.s2t(matcher.group(2).trim()));
            span.setSpan(factory.apply(Result.type(matcher.group(1))), start, span.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            last = matcher.end();
        }
        span.append(text, last, text.length());
        return span;
    }
}

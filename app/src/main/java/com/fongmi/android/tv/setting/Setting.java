package com.fongmi.android.tv.setting;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import com.fongmi.android.tv.App;
import com.github.catvod.Init;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Setting {

    public static String getDoh() {
        return Prefers.getString("doh");
    }

    public static void putDoh(String doh) {
        Prefers.put("doh", doh);
    }

    public static String getKeyword() {
        return Prefers.getString("keyword");
    }

    public static void putKeyword(String keyword) {
        Prefers.put("keyword", keyword);
    }

    public static String getHot() {
        return Prefers.getString("hot");
    }

    public static void putHot(String hot) {
        Prefers.put("hot", hot);
    }

    public static String getUa() {
        return Prefers.getString("ua");
    }

    public static void putUa(String ua) {
        Prefers.put("ua", ua);
    }

    public static int getWall() {
        return Prefers.getInt("wall", 1);
    }

    public static void putWall(int wall) {
        Prefers.put("wall", wall);
    }

    public static int getWallType() {
        return Prefers.getInt("wall_type", 0);
    }

    public static int getPictureReaderMode() {
        return Prefers.getInt("picture_reader_mode", 0);
    }

    public static void putPictureReaderMode(int mode) {
        Prefers.put("picture_reader_mode", mode);
    }

    public static void putWallType(int type) {
        Prefers.put("wall_type", type);
    }

    public static int getReset() {
        return Prefers.getInt("reset", 0);
    }

    public static void putReset(int reset) {
        Prefers.put("reset", reset);
    }

    public static int getSiteMode() {
        return Prefers.getInt("site_mode");
    }

    public static void putSiteMode(int mode) {
        Prefers.put("site_mode", mode);
    }

    public static int getSyncMode() {
        return Prefers.getInt("sync_mode");
    }

    public static void putSyncMode(int mode) {
        Prefers.put("sync_mode", mode);
    }

    public static boolean isIncognito() {
        return Prefers.getBoolean("incognito");
    }

    public static void putIncognito(boolean incognito) {
        Prefers.put("incognito", incognito);
    }

    public static boolean getUpdate() {
        return Prefers.getBoolean("update", true);
    }

    public static void putUpdate(boolean update) {
        Prefers.put("update", update);
    }

    public static boolean isAdblock() {
        return Prefers.getBoolean("adblock", true);
    }

    public static void putAdblock(boolean adblock) {
        Prefers.put("adblock", adblock);
    }

    public static boolean isAiAdblock() {
        return Prefers.getBoolean("ai_adblock", true);
    }

    public static void putAiAdblock(boolean aiAdblock) {
        Prefers.put("ai_adblock", aiAdblock);
    }

    /**
     * 声纹去广「提前确认」：START 证据（默认约 1 秒）即派发跳转，
     * 不必等整条指纹完整校验走完（长规则可达 5 秒以上）。
     *
     * <p>开启后广告跳过延迟可从「播了五六秒才跳」降到约 1 秒；
     * 若遇到误跳正片，关闭即可回到「必须完整锚点验证」的保守行为。
     */
    public static boolean isAdEarlyConfirm() {
        return Prefers.getBoolean("ad_early_confirm", true);
    }

    public static void putAdEarlyConfirm(boolean early) {
        Prefers.put("ad_early_confirm", early);
    }

    /**
     * 广告区间记忆：把声纹确认过的广告位置按视频持久化，
     * 同一视频再次播放时开播前就已知广告在哪——HLS 可直接删掉对应分片，
     * MP4/FLV 则预先计划跳过，做到二次播放零延迟。
     */
    public static boolean isAdSegmentMemory() {
        return Prefers.getBoolean("ad_segment_memory", true);
    }

    public static void putAdSegmentMemory(boolean memory) {
        Prefers.put("ad_segment_memory", memory);
    }

    public static String getAiAdblockKeywords() {
        return Prefers.getString("ai_adblock_keywords", "麻将来了,澳门,赌场,娱乐城,荷官,百家乐,老虎机,时时彩,六合彩,彩票,下注,投注,首充,提现,棋牌,捕鱼,斗地主");
    }

    public static void putAiAdblockKeywords(String keywords) {
        Prefers.put("ai_adblock_keywords", keywords);
    }

    public static int getAiAdblockSkipSeconds() {
        return Prefers.getInt("ai_adblock_skip_seconds", 15);
    }

    public static void putAiAdblockSkipSeconds(int seconds) {
        Prefers.put("ai_adblock_skip_seconds", seconds);
    }

    /** 自动采集：播放时后台扫描广告候选并生成音频指纹规则。 */
    public static boolean isAutoCollect() {
        return Prefers.getBoolean("ad_auto_collect", true);
    }

    public static void putAutoCollect(boolean autoCollect) {
        Prefers.put("ad_auto_collect", autoCollect);
    }

    /** 广告跳过模式：0=仅提示，1=提示+自动跳过，2=仅自动跳过（默认提示+自动跳过）。 */
    public static final int AD_SKIP_MODE_NOTICE = 0;
    public static final int AD_SKIP_MODE_NOTICE_AND_SKIP = 1;
    public static final int AD_SKIP_MODE_SKIP_ONLY = 2;
    public static final int DEFAULT_AD_SKIP_MODE = AD_SKIP_MODE_NOTICE_AND_SKIP;

    public static int getAdSkipMode() {
        return Prefers.getInt("ad_skip_mode", DEFAULT_AD_SKIP_MODE);
    }

    public static void putAdSkipMode(int mode) {
        Prefers.put("ad_skip_mode", mode);
    }

    /** 智能趣广告的规则文件路径（采集器 APK 生成的 RULES.JSON）。默认在外部 Download 目录（与旧版一致）。 */
    public static final String DEFAULT_RULES_PATH = "/storage/emulated/0/Download/m3u8-ad-audio/RULES.JSON";

    /** 返回默认（外部）规则路径，与旧版行为一致。 */
    public static String getDefaultRulesPath() {
        return DEFAULT_RULES_PATH;
    }

    /** 解析保存的路径；为空或占位符时返回默认外部路径。 */
    public static String getAdRulesPath() {
        String saved = Prefers.getString("ad_rules_path", "");
        if (saved == null || saved.trim().isEmpty() || DEFAULT_RULES_PATH.equals(saved.trim())) {
            return DEFAULT_RULES_PATH;
        }
        return saved.trim();
    }

    public static void putAdRulesPath(String path) {
        String normalized = (path == null || path.trim().isEmpty()) ? DEFAULT_RULES_PATH : path.trim();
        Prefers.put("ad_rules_path", normalized);
    }

    /**
     * 应用私有目录兜底路径。Android 11+ 上外部目录可能因未授予“所有文件访问权限”
     * 而 EACCES 写入失败，此时降级到这里，零权限依赖，保证规则一定能落盘，
     * 声纹去广告不会因写不进外部目录而静默失效。
     */
    public static String getPrivateRulesPath() {
        return new File(Init.context().getFilesDir(), "m3u8-ad-audio/RULES.JSON").getAbsolutePath();
    }

    /**
     * 读写规则时按优先级尝试的路径列表：首选外部默认路径（与旧版/采集器兼容），
     * 其次应用私有目录兜底。外部路径在 Android 11+ 未授予“所有文件访问权限”时会 EACCES，
     * 自动落到私有目录。
     */
    public static List<String> getRulesPathCandidates() {
        List<String> list = new ArrayList<>();
        String primary = getAdRulesPath();
        list.add(primary);
        String priv = getPrivateRulesPath();
        if (!priv.equals(primary)) list.add(priv);
        return list;
    }

    /** 外部（默认）规则目录是否可写，用于自检面板提示是否需授予“所有文件访问权限”。 */
    public static boolean isPrimaryRulesPathWritable() {
        try {
            File dir = new File(getAdRulesPath()).getParentFile();
            return dir != null && (dir.exists() ? dir.canWrite() : dir.mkdirs());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 是否需要请求“所有文件访问权限”（MANAGE_EXTERNAL_STORAGE）。
     * Android 11+ 上写外部 Download 目录必须授权该特殊权限，否则 EACCES；
     * API < 30 用 WRITE_EXTERNAL_STORAGE 即可，无需此授权。
     */
    public static boolean needsAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        try {
            return !Environment.isExternalStorageManager();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 广告规则库地址（可拉取的第三方 rules.json）。默认为空：不预置任何地址，
     * 由用户自行在“去广告设置”里填写自己的规则库（避免内置地址不可达时误导用户）。
     * 仅拉取、不回传：本地自动采集到的规则合并进本地 RULES.JSON，但绝不上传。
     */
    public static final String DEFAULT_RULE_LIBRARY_URL = "";

    public static String getRuleLibraryUrl() {
        return Prefers.getString("ad_rule_library_url", DEFAULT_RULE_LIBRARY_URL);
    }

    public static void putRuleLibraryUrl(String url) {
        Prefers.put("ad_rule_library_url", (url == null || url.trim().isEmpty()) ? "" : url.trim());
    }

    /**
     * 一次性迁移：清理旧版（5.9.8 早期）持久化到本地偏好的不可达默认规则库地址。
     * 当时我们把第三方 ccfork 规则库预置为默认值并写入了本地存储；该地址后续被证实不可达，
     * 已改为默认空串（由用户自行填写）。但已运行过旧版的用户本地已存有该旧地址，单靠改默认值
     * 无法清除，故在启动时检测并清成空串，使设置页不再显示该链接。
     * 幂等：仅在当前存储值恰好等于旧默认地址时清空，用户后来手动填的地址不受影响。
     */
    public static final String LEGACY_RULE_LIBRARY_URL =
            "https://m3u8-ad-audio-rules-sync.ccfork.workers.dev/rules.json";

    public static void migrateDeprecatedRuleLibraryUrl() {
        String cur = getRuleLibraryUrl();
        if (LEGACY_RULE_LIBRARY_URL.equals(cur)) {
            putRuleLibraryUrl("");
        }
    }

    public static boolean isZhuyin() {
        return Prefers.getBoolean("zhuyin");
    }

    public static void putZhuyin(boolean zhuyin) {
        Prefers.put("zhuyin", zhuyin);
    }

    public static int getThemeColor() {
        return Prefers.getInt("theme_color", -1);
    }

    public static void putThemeColor(int color) {
        Prefers.put("theme_color", color);
    }

    public static int getWallColor() {
        return Prefers.getInt("wall_color", 0);
    }

    public static void putWallColor(int color) {
        Prefers.put("wall_color", color);
    }

    public static int getDynamicColor() {
        int color = getThemeColor();
        if (color == -1) return 0;
        return color != 0 ? color : getWallColor();
    }

    public static boolean hasFileManager() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false;
        return new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + App.get().getPackageName())).resolveActivity(App.get().getPackageManager()) != null || new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).resolveActivity(App.get().getPackageManager()) != null;
    }

    public static String getSyncUrl() {
        return Prefers.getString("sync_url", "");
    }

    public static void putSyncUrl(String value) {
        Prefers.put("sync_url", value);
    }

    public static String getSyncUser() {
        return Prefers.getString("sync_user", "");
    }

    public static void putSyncUser(String value) {
        Prefers.put("sync_user", value);
    }

    public static String getSyncPass() {
        return Prefers.getString("sync_pass", "");
    }

    public static void putSyncPass(String value) {
        Prefers.put("sync_pass", value);
    }

    public static boolean isSyncAutoBackup() {
        return getSyncInterval() > 0;
    }

    public static void putSyncAutoBackup(boolean value) {
        Prefers.put("sync_auto_backup", value);
    }

    public static int getSyncInterval() {
        return Prefers.getInt("sync_interval", 0);
    }

    public static void putSyncInterval(int value) {
        Prefers.put("sync_interval", value);
    }

    public static boolean isSyncAutoSync() {
        return Prefers.getBoolean("sync_auto_sync", false);
    }

    public static void putSyncAutoSync(boolean value) {
        Prefers.put("sync_auto_sync", value);
    }

    public static String getProxySubscriptionUrl() {
        return Prefers.getString("proxy_subscription_url", "");
    }

    public static void putProxySubscriptionUrl(String value) {
        Prefers.put("proxy_subscription_url", value);
    }

    public static String getProxySubscriptionNodes() {
        return Prefers.getString("proxy_subscription_nodes", "");
    }

    public static void putProxySubscriptionNodes(String value) {
        Prefers.put("proxy_subscription_nodes", value);
    }

    public static String getProxySubscriptionConfig() {
        return Prefers.getString("proxy_subscription_config", "");
    }

    public static void putProxySubscriptionConfig(String value) {
        Prefers.put("proxy_subscription_config", value);
    }

    public static String getProxySubscriptionCoreName() {
        return Prefers.getString("proxy_subscription_core_name", "");
    }

    public static void putProxySubscriptionCoreName(String value) {
        Prefers.put("proxy_subscription_core_name", value);
    }

    public static String getProxySubscriptionSelected() {
        return Prefers.getString("proxy_subscription_selected", "");
    }

    public static void putProxySubscriptionSelected(String value) {
        Prefers.put("proxy_subscription_selected", value);
    }

    public static boolean isProxySubscriptionEnabled() {
        return Prefers.getBoolean("proxy_subscription_enabled", false);
    }

    public static void putProxySubscriptionEnabled(boolean value) {
        Prefers.put("proxy_subscription_enabled", value);
    }

    public static int getSearchMode() {
        return Prefers.getInt("search_mode", 0);
    }

    public static void putSearchMode(int value) {
        Prefers.put("search_mode", value);
    }

    public static int getLayoutMode() {
        return Prefers.getInt("layout_mode", 0);
    }

    public static void putLayoutMode(int value) {
        Prefers.put("layout_mode", value);
    }

    public static boolean isShortShow() {
        return Prefers.getBoolean("episode_short_show", false);
    }

    public static void putShortShow(boolean value) {
        Prefers.put("episode_short_show", value);
    }

    public static boolean isAlwaysTime() {
        return Prefers.getBoolean("always_time", false);
    }

    public static void putAlwaysTime(boolean value) {
        Prefers.put("always_time", value);
    }

    public static boolean isAlwaysProgress() {
        return Prefers.getBoolean("always_progress", false);
    }

    public static void putAlwaysProgress(boolean value) {
        Prefers.put("always_progress", value);
    }

    public static boolean isHomeVod() {
        return Prefers.getBoolean("home_vod", true);
    }

    public static void putHomeVod(boolean value) {
        Prefers.put("home_vod", value);
    }

    public static boolean isHomeHot() {
        return Prefers.getBoolean("home_hot", true);
    }

    public static void putHomeHot(boolean value) {
        Prefers.put("home_hot", value);
    }

    public static boolean isHomeLive() {
        return Prefers.getBoolean("home_live", true);
    }

    public static void putHomeLive(boolean value) {
        Prefers.put("home_live", value);
    }

    public static boolean isHomeLocal() {
        return Prefers.getBoolean("home_local", false);
    }

    public static void putHomeLocal(boolean value) {
        Prefers.put("home_local", value);
    }

    public static boolean isHomeHistory() {
        return Prefers.getBoolean("home_history", true);
    }

    public static void putHomeHistory(boolean value) {
        Prefers.put("home_history", value);
    }

    public static boolean isHomeDownload() {
        return Prefers.getBoolean("home_download", true);
    }

    public static void putHomeDownload(boolean value) {
        Prefers.put("home_download", value);
    }

    /** 下载 M3U8 时自动合并 ts 分片为单个 .ts 文件（仅对非加密源有效） */
    public static boolean isMergeTs() {
        return Prefers.getBoolean("download_merge_ts", true);
    }

    public static void putMergeTs(boolean value) {
        Prefers.put("download_merge_ts", value);
    }

    public static int getHomeStyle() {
        return Prefers.getInt("home_style", 1);
    }

    public static void putHomeStyle(int value) {
        Prefers.put("home_style", value);
    }

    public static boolean isHomeCapsule() {
        return getHomeStyle() == 1;
    }

    public static String getIqiyiRecommends() {
        return Prefers.getString("iqiyi_recommends", "");
    }

    public static void putIqiyiRecommends(String value) {
        Prefers.put("iqiyi_recommends", value);
    }

    public static String getHomeRecommend(String key) {
        return Prefers.getString("home_recommend_" + key, "");
    }

    public static void putHomeRecommend(String key, String value) {
        Prefers.put("home_recommend_" + key, value);
    }

    public static int getTransition() {
        return Math.min(Math.max(Prefers.getInt("transition_anim", 0), 0), 7);
    }

    public static void putTransition(int value) {
        Prefers.put("transition_anim", Math.min(Math.max(value, 0), 7));
    }

    public static int getSearchFilter() {
        return Prefers.getInt("search_filter", 1);
    }

    public static void putSearchFilter(int value) {
        Prefers.put("search_filter", value);
    }

    public static boolean isSearchExact() {
        return getSearchFilter() == 0;
    }

    public static int getSearchThread() {
        return Math.min(Math.max(Prefers.getInt("search_thread", 10), 3), 20);
    }

    public static void putSearchThread(int value) {
        Prefers.put("search_thread", Math.min(Math.max(value, 3), 20));
    }

    public static String getTmdbApiKey() {
        return Prefers.getString("tmdb_api_key", "");
    }

    public static void putTmdbApiKey(String value) {
        Prefers.put("tmdb_api_key", value == null ? "" : value.trim());
    }

    public static boolean hasTmdbApiKey() {
        return !getTmdbApiKey().isEmpty();
    }

    /** TMDB API 基地址（含版本路径），默认官方地址 */
    public static String getTmdbApiUrl() {
        return Prefers.getString("tmdb_api_url", "https://api.themoviedb.org/3");
    }

    public static void putTmdbApiUrl(String value) {
        Prefers.put("tmdb_api_url", value == null ? "" : value.trim());
    }

    /** TMDB 图片基地址（不含尺寸路径），默认官方地址 */
    public static String getTmdbImageUrl() {
        return Prefers.getString("tmdb_image_url", "https://image.tmdb.org/t/p");
    }

    public static void putTmdbImageUrl(String value) {
        Prefers.put("tmdb_image_url", value == null ? "" : value.trim());
    }

    public static String getAiKey() {
        return Prefers.getString("ai_key", "");
    }

    public static void putAiKey(String value) {
        Prefers.put("ai_key", value == null ? "" : value.trim());
    }

    public static boolean hasAiKey() {
        return !getAiKey().isEmpty();
    }

    /** AI 模型 ID，默认使用硅基流动免费模型 Qwen2-7B；可改为任意 OpenAI 兼容模型。 */
    public static String getAiModel() {
        return Prefers.getString("ai_model", "Qwen/Qwen2-7B-Instruct");
    }

    public static void putAiModel(String value) {
        Prefers.put("ai_model", value == null ? "" : value.trim());
    }

    /** AI API 地址（OpenAI 兼容接口），默认硅基流动。用户可改为任意兼容端点。 */
    public static String getAiUrl() {
        return Prefers.getString("ai_url", "https://api.siliconflow.cn/v1/chat/completions");
    }

    public static void putAiUrl(String value) {
        Prefers.put("ai_url", value == null ? "" : value.trim());
    }
}

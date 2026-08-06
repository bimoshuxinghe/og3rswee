package com.fongmi.android.tv.setting;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;

import com.fongmi.android.tv.App;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Path;
import com.github.catvod.utils.Prefers;

import java.io.File;
import java.nio.charset.StandardCharsets;

public class PlayerSetting {

    public static final int ENGINE_EXO = 0;
    public static final int ENGINE_MPV = 1;

    public static int getEngine() {
        return Math.min(Math.max(Prefers.getInt("player_engine", ENGINE_EXO), ENGINE_EXO), ENGINE_MPV);
    }

    public static void putEngine(int engine) {
        Prefers.put("player_engine", Math.min(Math.max(engine, ENGINE_EXO), ENGINE_MPV));
    }

    public static boolean isMpv() {
        return getEngine() == ENGINE_MPV;
    }

    public static boolean isPreload() {
        return Prefers.getBoolean("preload", false);
    }

    public static void putPreload(boolean preload) {
        Prefers.put("preload", preload);
    }

    public static boolean isPreloadNext() {
        return Prefers.getBoolean("preload_next", false);
    }

    public static void putPreloadNext(boolean preloadNext) {
        Prefers.put("preload_next", preloadNext);
    }

    public static int getPreloadThread() {
        return Math.min(Math.max(Prefers.getInt("preload_thread", 1), 1), 5);
    }

    public static void putPreloadThread(int thread) {
        Prefers.put("preload_thread", Math.min(Math.max(thread, 1), 5));
    }

    public static int getPreloadCapacity() {
        return Math.min(Math.max(Prefers.getInt("preload_capacity", 128), 32), 512);
    }

    public static void putPreloadCapacity(int capacity) {
        Prefers.put("preload_capacity", Math.min(Math.max(capacity, 32), 512));
    }

    public static int getPreloadSeconds() {
        return Math.min(Math.max(Prefers.getInt("preload_seconds", 120), 10), 300);
    }

    public static void putPreloadSeconds(int seconds) {
        Prefers.put("preload_seconds", Math.min(Math.max(seconds, 10), 300));
    }

    public static int getControllerTransparency() {
        return Math.min(Math.max(Prefers.getInt("controller_alpha", 80), 0), 90);
    }

    public static void putControllerTransparency(int transparency) {
        Prefers.put("controller_alpha", Math.min(Math.max(transparency, 0), 90));
    }

    public static void applyControllerTransparency(View view) {
        if (view == null) return;
        Drawable background = view.getBackground();
        if (background == null) return;
        background.mutate().setAlpha(Math.round((100 - getControllerTransparency()) * 2.55f));
    }

    public static File getMpvConfigDir() {
        File dir = Path.files("mpv");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static File getMpvConfigFile() {
        return new File(getMpvConfigDir(), "mpv.conf");
    }

    public static boolean hasMpvConfig() {
        return Path.exists(getMpvConfigFile());
    }

    public static String getMpvConfigName() {
        return Prefers.getString("mpv_config_name");
    }

    public static boolean importMpvConfig(String path) {
        if (TextUtils.isEmpty(path)) return false;
        File source = new File(path);
        if (!Path.exists(source)) return false;
        File target = getMpvConfigFile();
        if (!source.getAbsolutePath().equals(target.getAbsolutePath())) Path.copy(source, target);
        if (!Path.exists(target)) return false;
        Prefers.put("mpv_config_name", source.getName());
        return true;
    }

    public static boolean importMpvConfigUrl(String url) {
        if (TextUtils.isEmpty(url)) return false;
        url = url.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;
        String text = OkHttp.string(url);
        if (TextUtils.isEmpty(text)) return false;
        File target = getMpvConfigFile();
        Path.write(target, text.getBytes(StandardCharsets.UTF_8));
        if (!Path.exists(target)) return false;
        Prefers.put("mpv_config_name", url);
        return true;
    }

    public static void clearMpvConfig() {
        Path.clear(getMpvConfigFile());
        Prefers.remove("mpv_config_name");
    }

    public static int getMpvRender() {
        return Math.min(Math.max(Prefers.getInt("mpv_render"), 0), 2);
    }

    public static void putMpvRender(int render) {
        Prefers.put("mpv_render", Math.min(Math.max(render, 0), 2));
    }

    public static boolean isMpvAudioPassthrough() {
        return Prefers.getBoolean("mpv_audio_passthrough");
    }

    public static void putMpvAudioPassthrough(boolean passthrough) {
        Prefers.put("mpv_audio_passthrough", passthrough);
    }

    public static boolean isMpvDolbyPassthrough() {
        return Prefers.getBoolean("mpv_dolby_passthrough");
    }

    public static void putMpvDolbyPassthrough(boolean passthrough) {
        Prefers.put("mpv_dolby_passthrough", passthrough);
    }

    public static boolean isExoDolbyVisionPassthrough() {
        return Prefers.getBoolean("exo_dolby_vision_passthrough", true);
    }

    public static void putExoDolbyVisionPassthrough(boolean passthrough) {
        Prefers.put("exo_dolby_vision_passthrough", passthrough);
    }

    public static int getRender() {
        return Prefers.getInt("render", 0);
    }

    public static void putRender(int render) {
        Prefers.put("render", render);
    }

    public static int getSize() {
        return Prefers.getInt("size", 2);
    }

    public static void putSize(int size) {
        Prefers.put("size", size);
    }

    public static int getScale() {
        return Prefers.getInt("scale");
    }

    public static void putScale(int scale) {
        Prefers.put("scale", scale);
    }

    public static int getBuffer() {
        return Math.min(Math.max(Prefers.getInt("buffer"), 1), 10);
    }

    public static void putBuffer(int buffer) {
        Prefers.put("buffer", buffer);
    }

    public static int getBackground() {
        return Prefers.getInt("background", 0);
    }

    public static void putBackground(int background) {
        Prefers.put("background", background);
    }

    public static boolean isBackgroundOff() {
        return getBackground() == 0;
    }

    public static boolean isBackgroundOn() {
        return getBackground() == 1 || getBackground() == 2;
    }

    public static boolean isBackgroundPiP() {
        return getBackground() == 2;
    }

    public static boolean isHomeMute() {
        return Prefers.getBoolean("home_mute", false);
    }

    public static void putHomeMute(boolean homeMute) {
        Prefers.put("home_mute", homeMute);
    }

    public static boolean isHomeCarousel() {
        return Prefers.getBoolean("home_carousel", true);
    }

    public static void putHomeCarousel(boolean homeCarousel) {
        Prefers.put("home_carousel", homeCarousel);
    }

    public static boolean isDetailPoster() {
        return Prefers.getBoolean("detail_poster", true);
    }

    public static void putDetailPoster(boolean detailPoster) {
        Prefers.put("detail_poster", detailPoster);
    }

    public static float getLrcTextSize() {
        return Math.min(Math.max(Prefers.getFloat("lrc_text_size", 56f), 24f), 80f);
    }

    public static void putLrcTextSize(float size) {
        Prefers.put("lrc_text_size", Math.min(Math.max(size, 24f), 80f));
    }

    public static int getLrcColor() {
        return Prefers.getInt("lrc_color", 0xFFFFD700);
    }

    public static void putLrcColor(int color) {
        Prefers.put("lrc_color", color);
    }

    public static float getSpeed() {
        return Math.min(Math.max(Prefers.getFloat("speed", 3), 2), 5);
    }

    public static void putSpeed(float speed) {
        Prefers.put("speed", speed);
    }

    public static boolean isCaption() {
        return Prefers.getBoolean("caption");
    }

    public static void putCaption(boolean caption) {
        Prefers.put("caption", caption);
    }

    public static boolean hasCaption() {
        return new Intent(Settings.ACTION_CAPTIONING_SETTINGS).resolveActivity(App.get().getPackageManager()) != null;
    }

    public static boolean isTunnel() {
        return Prefers.getBoolean("tunnel");
    }

    public static void putTunnel(boolean tunnel) {
        Prefers.put("tunnel", tunnel);
    }

    public static boolean isAudioPrefer() {
        return Prefers.getBoolean("audio_prefer");
    }

    public static void putAudioPrefer(boolean audioPrefer) {
        Prefers.put("audio_prefer", audioPrefer);
    }

    public static boolean isVideoPrefer() {
        return Prefers.getBoolean("video_prefer");
    }

    public static void putVideoPrefer(boolean videoPrefer) {
        Prefers.put("video_prefer", videoPrefer);
    }

    public static boolean isPreferAAC() {
        return Prefers.getBoolean("prefer_aac");
    }

    public static void putPreferAAC(boolean preferAAC) {
        Prefers.put("prefer_aac", preferAAC);
    }

    public static float getSubtitleTextSize() {
        return Prefers.getFloat("subtitle_text_size");
    }

    public static void putSubtitleTextSize(float value) {
        Prefers.put("subtitle_text_size", value);
    }

    public static float getSubtitlePosition() {
        return Prefers.getFloat("subtitle_position");
    }

    public static void putSubtitlePosition(float value) {
        Prefers.put("subtitle_position", value);
    }

    public static float getMpvSubtitleScale() {
        return Prefers.getFloat("mpv_subtitle_scale", 1.0f);
    }

    public static void putMpvSubtitleScale(float value) {
        Prefers.put("mpv_subtitle_scale", value);
    }

    public static float getMpvSubtitlePosition() {
        return Prefers.getFloat("mpv_subtitle_position", 100.0f);
    }

    public static void putMpvSubtitlePosition(float value) {
        Prefers.put("mpv_subtitle_position", value);
    }

    // === Subtitle style settings (matching APK's l41/j41 configuration) ===

    public static int getSubtitleStyleSource() {
        int base = isCaption() ? 1 : 0;
        return Math.min(Math.max(Prefers.getInt("subtitle_style_source", base), 0), 2);
    }

    public static void putSubtitleStyleSource(int source) {
        Prefers.put("subtitle_style_source", Math.min(Math.max(source, 0), 2));
    }

    public static int getSubtitleForegroundColor() {
        return Prefers.getInt("subtitle_foreground_color", -1);
    }

    public static void putSubtitleForegroundColor(int color) {
        Prefers.put("subtitle_foreground_color", color);
    }

    public static float getSubtitleForegroundOpacity() {
        return Math.min(Math.max(Prefers.getFloat("subtitle_foreground_opacity", 1.0f), 0.0f), 1.0f);
    }

    public static void putSubtitleForegroundOpacity(float opacity) {
        Prefers.put("subtitle_foreground_opacity", Math.min(Math.max(opacity, 0.0f), 1.0f));
    }

    public static int getSubtitleBackgroundColor() {
        return Prefers.getInt("subtitle_background_color", 0xFF000000);
    }

    public static void putSubtitleBackgroundColor(int color) {
        Prefers.put("subtitle_background_color", color);
    }

    public static float getSubtitleBackgroundOpacity() {
        return Math.min(Math.max(Prefers.getFloat("subtitle_background_opacity", 1.0f), 0.0f), 1.0f);
    }

    public static void putSubtitleBackgroundOpacity(float opacity) {
        Prefers.put("subtitle_background_opacity", Math.min(Math.max(opacity, 0.0f), 1.0f));
    }

    public static int getSubtitleEdgeType() {
        return Math.min(Math.max(Prefers.getInt("subtitle_edge_type", 1), 0), 2);
    }

    public static void putSubtitleEdgeType(int type) {
        Prefers.put("subtitle_edge_type", Math.min(Math.max(type, 0), 2));
    }

    public static int getSubtitleEdgeColor() {
        return Prefers.getInt("subtitle_edge_color", 0xFF000000);
    }

    public static void putSubtitleEdgeColor(int color) {
        Prefers.put("subtitle_edge_color", color);
    }

    public static float getSubtitleEdgeOpacity() {
        return Math.min(Math.max(Prefers.getFloat("subtitle_edge_opacity", 1.0f), 0.0f), 1.0f);
    }

    public static void putSubtitleEdgeOpacity(float opacity) {
        Prefers.put("subtitle_edge_opacity", Math.min(Math.max(opacity, 0.0f), 1.0f));
    }

    public static float getSubtitleEdgeWidth() {
        return Math.min(Math.max(Prefers.getFloat("subtitle_edge_width", 2.0f), 0.0f), 6.0f);
    }

    public static void putSubtitleEdgeWidth(float width) {
        Prefers.put("subtitle_edge_width", Math.min(Math.max(width, 0.0f), 6.0f));
    }

    public static float getSubtitleShadow() {
        return Math.min(Math.max(Prefers.getFloat("subtitle_shadow", 2.0f), 0.0f), 8.0f);
    }

    public static void putSubtitleShadow(float shadow) {
        Prefers.put("subtitle_shadow", Math.min(Math.max(shadow, 0.0f), 8.0f));
    }

    public static int getSubtitleSecondaryTrack() {
        return Math.max(Prefers.getInt("subtitle_secondary_track", -2), -2);
    }

    public static void putSubtitleSecondaryTrack(int track) {
        Prefers.put("subtitle_secondary_track", Math.max(track, -2));
    }

    public static float getSubtitleSecondaryPosition() {
        return Math.min(Math.max(Prefers.getFloat("subtitle_secondary_position", 10.0f), 0.0f), 150.0f);
    }

    public static void putSubtitleSecondaryPosition(float position) {
        Prefers.put("subtitle_secondary_position", Math.min(Math.max(position, 0.0f), 150.0f));
    }

    // === Preload time setting (matching APK's k41.w() formula) ===

    public static int getPreloadTime() {
        return Math.min(Math.max(Prefers.getInt("preload_time", 120), 20), 120);
    }

    public static void putPreloadTime(int time) {
        Prefers.put("preload_time", Math.min(Math.max(time, 20), 120));
    }

    public static int getMpvCacheSecs() {
        int preloadTime = getPreloadTime();
        int rounded = Math.round((preloadTime - 20) / 10.0f) * 10 + 20;
        return Math.min(120, Math.max(rounded, 20));
    }

    // === MPV GPU settings (matching APK's separate boolean flags) ===

    public static boolean isMpvGpuNext() {
        return Prefers.getBoolean("mpv_gpu_next", getMpvRender() >= 1);
    }

    public static boolean isMpvVulkan() {
        return Prefers.getBoolean("mpv_vulkan", getMpvRender() == 2);
    }

    // === Subtitle scale calculation (matching APK's l41.w() and l41.x()) ===

    public static float getMpvSubtitleScaleValue(Context context) {
        float textSize = Prefers.getFloat("subtitle_text_size", 0.0f);
        float scale;
        if (textSize == 0.0f) {
            scale = 1.0f;
        } else {
            scale = textSize / 0.0533f;
        }
        scale = Prefers.getFloat("subtitle_scale", scale);
        if (scale == 1.0f && getSubtitleStyleSource() == 1) {
            android.view.accessibility.CaptioningManager cm = (android.view.accessibility.CaptioningManager)
                    context.getSystemService(Context.CAPTIONING_SERVICE);
            if (cm != null) {
                scale *= cm.getFontScale();
            }
        }
        return scale;
    }
}

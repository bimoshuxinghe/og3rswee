package com.fongmi.android.tv.player;

import android.content.Context;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import com.fongmi.android.tv.R;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.ResUtil;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.fongmi.adaudio.probe.AdAudioProbe;
import io.github.fongmi.adaudio.probe.PlaybackClock;
import io.github.fongmi.adaudio.probe.ProbeError;
import io.github.fongmi.adaudio.probe.ProbeListener;
import io.github.fongmi.adaudio.probe.ProbeMedia;
import io.github.fongmi.adaudio.probe.SkipRequest;

/**
 * 智能趣广告（音频探针）宿主桥接层。
 *
 * <p>SDK 自包含：内部用无声纯音频 ExoPlayer 快速解码并与规则指纹匹配，宿主只负责
 * 提供当前播放位置（{@link PlaybackClock}）、在收到跳转请求时 seek 自己的播放器、
 * 以及在自身拖动时通知探针。规则由采集器 APK 生成并经后台地址下发，未配置时 fail-open，
 * 不影响正常播放。</p>
 */
public final class AdProbeManager {

    /** SDK 适配器仅放行不会向重定向目标泄露凭据的安全请求头白名单。 */
    private static final Set<String> ALLOWED_HEADER_NAMES = Collections.unmodifiableSet(
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "user-agent", "accept", "accept-language", "cache-control", "pragma")));

    private static volatile AdProbeManager instance;

    private AdAudioProbe probe;
    private Player player;
    private String lastUrl;
    private Map<String, String> lastHeaders;
    private String rulesUrl;
    private boolean showSkipNotice = true;

    private final PlaybackClock clock = () -> {
        Player p = player;
        if (p == null) return 0L;
        long pos = p.getCurrentPosition();
        return pos < 0L ? 0L : pos;
    };

    private final ProbeListener listener = new ProbeListener() {
        @Override
        public void onSkipRequested(SkipRequest request) {
            Player p = player;
            if (p == null) return;
            long target = request.getSeekTargetPositionMs();
            if (target < 0L) return;
            try {
                p.seekTo(target);
            } catch (Exception ignored) {
                // 宿主 seek 失败不应影响后续检测
            }
            if (showSkipNotice) Notify.show(ResUtil.getString(R.string.ad_skipped));
        }

        @Override
        public void onError(ProbeError error) {
            // fail-open：探针错误只记录，绝不打断宿主播放
            if (error != null && error.isFatal()) {
                // 当前媒体分析已停止，等待下一次 open 或规则刷新
            }
        }
    };

    public static AdProbeManager get() {
        if (instance == null) {
            synchronized (AdProbeManager.class) {
                if (instance == null) instance = new AdProbeManager();
            }
        }
        return instance;
    }

    private AdProbeManager() {
    }

    /** 绑定宿主播放器并（按需）创建探针实例。每次 PlayerManager 重建都会调用。 */
    public void init(Context context, Player player) {
        this.player = player;
        this.rulesUrl = Setting.getAdRulesUrl();
        ensureProbe(context);
    }

    /** PlayerManager 切换引擎后更新宿主播放器引用。 */
    public void setPlayer(Player player) {
        this.player = player;
    }

    private void ensureProbe(Context context) {
        if (probe != null) return;
        String url = (rulesUrl == null || rulesUrl.trim().isEmpty()) ? null : rulesUrl.trim();
        try {
            probe = AdAudioProbe.create(context.getApplicationContext(), url, clock, listener);
            probe.setEnabled(Setting.isAiAdblock());
        } catch (RuntimeException | LinkageError e) {
            // 适配器与运行环境不兼容时放弃探针，绝不影响宿主播放
            e.printStackTrace();
            probe = null;
        }
    }

    /** 开始分析新媒体；同一 URL 不会重复开（避免 subtitle/format 切换误触发）。 */
    public void open(String url, Map<String, String> headers) {
        if (url == null || probe == null || !probe.isEnabled()) return;
        if (url.equals(lastUrl)) return;
        lastUrl = url;
        lastHeaders = headers;
        try {
            ProbeMedia media = ProbeMedia.builder(url).setHeaders(filterHeaders(headers)).build();
            probe.open(media);
        } catch (RuntimeException | LinkageError e) {
            e.printStackTrace();
        }
    }

    /** 宿主主动 seek 后调用，让探针把内部分析位置同步到新时间轴。 */
    public void onHostSeek(long positionMs) {
        if (probe == null) return;
        try {
            probe.notifyHostDiscontinuity(Math.max(0L, positionMs));
        } catch (RuntimeException | LinkageError ignored) {
        }
    }

    /** 开关变化时调用：开启会（按需）创建探针并重开当前媒体，关闭会停用。 */
    public void setEnabled(boolean enabled, Context context) {
        if (enabled) ensureProbe(context);
        if (probe == null) return;
        try {
            probe.setEnabled(enabled);
        } catch (RuntimeException | LinkageError ignored) {
        }
        if (enabled && lastUrl != null) open(lastUrl, lastHeaders);
    }

    /** 规则后台地址变化时重建探针以加载新规则。 */
    public void setRulesUrl(String url, Context context) {
        if (url == null) url = "";
        if (url.trim().equals(rulesUrl == null ? "" : rulesUrl.trim())) return;
        rulesUrl = url;
        if (probe != null) {
            try {
                probe.close();
            } catch (RuntimeException | LinkageError ignored) {
            }
            probe = null;
        }
        ensureProbe(context);
        if (lastUrl != null) open(lastUrl, lastHeaders);
    }

    /** 停止当前分析会话（保留实例与规则缓存），用于 PlayerManager 释放时。 */
    public void release() {
        if (probe == null) return;
        try {
            probe.stop();
        } catch (RuntimeException | LinkageError ignored) {
        }
        lastUrl = null;
        lastHeaders = null;
    }

    private static Map<String, String> filterHeaders(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return Collections.emptyMap();
        Map<String, String> allowed = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if (name == null) continue;
            String normalized = name.toLowerCase(Locale.US);
            if (ALLOWED_HEADER_NAMES.contains(normalized)) {
                allowed.put(normalized, entry.getValue());
            }
        }
        return allowed;
    }
}

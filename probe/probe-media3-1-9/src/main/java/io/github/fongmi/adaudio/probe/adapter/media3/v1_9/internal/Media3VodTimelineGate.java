/* Media3 时间线分类器屏蔽 placeholder，并只发布权威的有限 VOD 时间线。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9.internal;

import androidx.media3.common.C;

final class Media3VodTimelineGate {
    enum Decision { PENDING, VOD, REJECT_LIVE, REJECT_DYNAMIC, IGNORED }

    private enum State { PENDING, VOD, REJECTED }

    private State state = State.PENDING;
    private boolean ready;
    private boolean seenAuthoritative;
    private boolean live;
    private boolean dynamic;
    private long durationMs = C.TIME_UNSET;

    void reset() {
        state = State.PENDING;
        ready = false;
        seenAuthoritative = false;
        live = false;
        dynamic = false;
        durationMs = C.TIME_UNSET;
    }

    Decision update(boolean placeholder, boolean live, boolean dynamic, long durationMs) {
        if (state == State.REJECTED) return Decision.IGNORED;
        if (placeholder) return state == State.VOD ? Decision.IGNORED : Decision.PENDING;
        seenAuthoritative = true;
        this.live = live;
        this.dynamic = dynamic;
        this.durationMs = durationMs;
        return decide();
    }

    Decision markReady() {
        ready = true;
        return decide();
    }

    /**
     * 兼容 Media3 1.11 对 HLS 时间线标志的更激进判定。
     *
     * <p>Media3 1.10 时代，点播（含带广告的 VOD、EVENT 型播放列表）通常 {@code isLive=false}
     * 且 {@code isDynamic=false}，探针能正常确认 VOD。升级到 1.11 后，部分点播/ EVENT 型
     * 流会被标记为 {@code isLive=true} 或 {@code isDynamic=true}，导致 {@link #decide()} 直接
     * 判为直播/动态并拒绝——表现就是「探针不读音频、声纹去广告完全失效」。
     *
     * <p>经验法则：只要窗口拥有<b>已知且有限</b>的时长，就一定是有限点播（直播时长未知），
     * 此时忽略 isLive/isDynamic 的误判，照常按 VOD 放行分析。这是向 1.10 行为对齐的兜底，
     * 不会让真正的直播（时长未知）蒙混过关。
     */
    private Decision decide() {
        if (!ready || !seenAuthoritative) return Decision.PENDING;
        boolean knownFiniteDuration = durationMs != C.TIME_UNSET && durationMs > 0L;
        if (live && !knownFiniteDuration) {
            state = State.REJECTED;
            return Decision.REJECT_LIVE;
        }
        if (dynamic && !knownFiniteDuration) {
            state = State.REJECTED;
            return Decision.REJECT_DYNAMIC;
        }
        state = State.VOD;
        return Decision.VOD;
    }

    boolean isVodConfirmed() {
        return state == State.VOD;
    }
}

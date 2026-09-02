/* 定义两帧候选、四帧确认及完整锚点验证参数。 */
package io.github.fongmi.adaudio.probe.internal.core;

/** 匹配器的保守参数；所有参数都在构造时固定，运行中不会修改宿主状态。 */
public final class MatcherConfig {
    private final int candidateFrames;
    private final int confirmationFrames;
    private final int maxHammingBits;
    private final float prefixMatchRatio;
    private final float fullMatchRatio;
    private final long cooldownMs;
    private final long maxTimelineGapMs;
    private final boolean confirmEarly;

    private MatcherConfig(Builder builder) {
        candidateFrames = builder.candidateFrames;
        confirmationFrames = builder.confirmationFrames;
        maxHammingBits = builder.maxHammingBits;
        prefixMatchRatio = builder.prefixMatchRatio;
        fullMatchRatio = builder.fullMatchRatio;
        cooldownMs = builder.cooldownMs;
        maxTimelineGapMs = builder.maxTimelineGapMs;
        confirmEarly = builder.confirmEarly;
    }

    public static MatcherConfig conservative() {
        return new Builder().setCandidateFrames(4).setConfirmationFrames(6)
                .setMaxHammingBits(5).build();
    }

    /** 发布默认值：约 0.77 秒给出候选，四帧 START 仅供内部跟踪重叠序列。 */
    public static MatcherConfig releaseSafe() {
        return new Builder().build();
    }

    public int getCandidateFrames() {
        return candidateFrames;
    }

    public int getConfirmationFrames() {
        return confirmationFrames;
    }

    public int getMaxHammingBits() {
        return maxHammingBits;
    }

    public float getPrefixMatchRatio() {
        return prefixMatchRatio;
    }

    public float getFullMatchRatio() {
        return fullMatchRatio;
    }

    public long getCooldownMs() {
        return cooldownMs;
    }

    public long getMaxTimelineGapMs() {
        return maxTimelineGapMs;
    }

    /**
     * 是否启用提前确认：START 证据即可作为跳转依据，不必等整条指纹完整校验。
     * 开启后匹配器会在 START 确认时立即落冷却，避免同一段广告被后续帧或
     * 其它指纹变体反复匹配成新的 occurrence。
     */
    public boolean isConfirmEarly() {
        return confirmEarly;
    }

    public static final class Builder {
        private int candidateFrames = 2;
        private int confirmationFrames = 4;
        private int maxHammingBits = 5;
        private float prefixMatchRatio = 1.0f;
        private float fullMatchRatio = 0.78f;
        private long cooldownMs = 5000L;
        private long maxTimelineGapMs = 2500L;
        private boolean confirmEarly = false;

        public Builder setCandidateFrames(int value) { candidateFrames = Math.max(2, value); return this; }
        public Builder setConfirmationFrames(int value) { confirmationFrames = Math.max(3, value); return this; }
        public Builder setMaxHammingBits(int value) { maxHammingBits = Math.max(0, Math.min(16, value)); return this; }
        public Builder setPrefixMatchRatio(float value) { prefixMatchRatio = clamp(value, 0.5f, 1.0f); return this; }
        public Builder setFullMatchRatio(float value) { fullMatchRatio = clamp(value, 0.5f, 1.0f); return this; }
        public Builder setCooldownMs(long value) { cooldownMs = Math.max(0, value); return this; }
        public Builder setMaxTimelineGapMs(long value) { maxTimelineGapMs = Math.max(500, value); return this; }

        /** 提前确认开关；默认关闭，保持「必须完整锚点验证」的既有行为。 */
        public Builder setConfirmEarly(boolean value) { confirmEarly = value; return this; }

        public MatcherConfig build() {
            if (confirmationFrames <= candidateFrames) confirmationFrames = candidateFrames + 1;
            if (fullMatchRatio > prefixMatchRatio) fullMatchRatio = prefixMatchRatio;
            return new MatcherConfig(this);
        }

        private static float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }
    }
}

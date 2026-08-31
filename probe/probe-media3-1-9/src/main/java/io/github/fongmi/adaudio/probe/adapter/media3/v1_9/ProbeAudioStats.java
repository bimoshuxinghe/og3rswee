/* 探针音频链路运行时统计，用于诊断「到底有没有读到音频」。 */
package io.github.fongmi.adaudio.probe.adapter.media3.v1_9;

/**
 * 声纹去广告的音频链路统计。
 *
 * <p>探针是 fail-open 设计：configure 失败、解码没跑、PCM 没投递，
 * 表现都是同一个「什么都不提示」，不看计数根本无法区分。
 * 这里把几个关键节点做成静态计数，宿主治检面板直接读取展示。</p>
 */
public final class ProbeAudioStats {

    /** configure 被调用次数。 */
    public static volatile long configureCalls;
    /** configure 成功次数（sampleRate/channelCount 被赋值）。 */
    public static volatile long configureOk;
    /** 最近一次 configure 的结果说明。 */
    public static volatile String lastConfigureResult = "从未调用";
    /** handleBuffer 被调用次数（解码器往 sink 送数据）。 */
    public static volatile long bufferCalls;
    /** 实际接受的 PCM 帧数（handleBuffer 返回 true）。 */
    public static volatile long pcmFrames;
    /** 累计 PCM 字节数。 */
    public static volatile long pcmBytes;
    /** 最近一次配置的采样率。 */
    public static volatile int sampleRate;
    /** 最近一次配置的声道数。 */
    public static volatile int channelCount;

    /** 权威 VOD 时间线确认次数（handleBuffer 闸门打开的前提）。 */
    public static volatile long vodConfirmed;
    /** 时间线被判为直播/动态而被拒绝的次数（探针只支持点播）。 */
    public static volatile long timelineRejectedLive;
    /** 最近一次时间线判定说明。 */
    public static volatile String lastTimelineDecision = "未发生";
    /** 解码器首次送出 PCM 的时刻（毫秒，0 表示从未读到）。 */
    public static volatile long firstPcmAtMs;

    private ProbeAudioStats() {
    }

    public static void reset() {
        configureCalls = 0L;
        configureOk = 0L;
        lastConfigureResult = "从未调用";
        bufferCalls = 0L;
        pcmFrames = 0L;
        pcmBytes = 0L;
        sampleRate = 0;
        channelCount = 0;
        vodConfirmed = 0L;
        timelineRejectedLive = 0L;
        lastTimelineDecision = "未发生";
        firstPcmAtMs = 0L;
    }

    /** 自检面板用：一行摘要。 */
    public static String summary() {
        String pcm = pcmFrames > 0
                ? "已读到 PCM：" + pcmFrames + " 帧 / " + pcmBytes + " 字节"
                : "未读到任何 PCM（音频链路未通）";
        return "configure 调用 " + configureCalls + " 次（成功 " + configureOk + " 次）\n"
                + "    最近结果：" + lastConfigureResult + "\n"
                + "    采样率 " + sampleRate + "Hz，声道 " + channelCount + "\n"
                + "    解码器送数据 " + bufferCalls + " 次\n"
                + "    " + pcm + "\n"
                + "    时间线确认(VOD闸门) " + vodConfirmed + " 次，"
                + "被判直播拒绝 " + timelineRejectedLive + " 次\n"
                + "    最近判定：" + lastTimelineDecision;
    }
}

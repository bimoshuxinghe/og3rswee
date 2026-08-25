package com.fongmi.android.tv.player;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.BaseAudioProcessor;

import com.fongmi.android.tv.setting.Setting;

import java.nio.ByteBuffer;

/**
 * Vosk 语音采集 AudioProcessor：挂在 DefaultAudioSink 内部 AudioProcessor 链上，
 * 在 queueInput 入口复制解码后的 PCM 交给 {@link VoskAsrManager} 做语音识别。
 *
 * <p>采用与历史可用版本（1b42276）一致的 AudioProcessor 方案：
 * <ul>
 *   <li>onConfigure 原样返回输入格式，不抛 UnhandledAudioFormatException，播放稳定；</li>
 *   <li>isActive() 恒为 Vosk 总开关状态，绝不依赖模型就绪状态——media3 在 configure
 *       时一次性评估 isActive()，若模型异步加载未完成时返回 false，processor 会被永久
 *       标记为 inactive，queueInput 再也不会被调用，导致语音广告识别完全失效；</li>
 *   <li>queueInput 拷贝 buffer 独立喂识别器，播放路径不受任何影响。</li>
 * </ul>
 */
public final class VoskAudioProcessor extends BaseAudioProcessor {

    private int inputSampleRate;
    private int inputChannels;
    private boolean inputIsFloat;

    public VoskAudioProcessor() {
    }

    @Override
    public AudioFormat onConfigure(AudioFormat inputAudioFormat) throws AudioProcessor.UnhandledAudioFormatException {
        this.inputSampleRate = inputAudioFormat.sampleRate;
        this.inputChannels = inputAudioFormat.channelCount;
        this.inputIsFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT;
        return inputAudioFormat;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int size = inputBuffer.remaining();
        if (size == 0) return;
        byte[] copy = new byte[size];
        inputBuffer.get(copy);
        inputBuffer.rewind();
        ByteBuffer output = replaceOutputBuffer(size);
        output.put(inputBuffer);
        output.flip();
        try {
            VoskAsrManager.get().feedPcm(copy, inputSampleRate, inputChannels, inputIsFloat);
        } catch (Throwable t) {
            // 完全隔离：Vosk 采集任何异常都不影响播放链路
        }
    }

    @Override
    public boolean isActive() {
        // 只要 Vosk 总开关开启就保持激活，绝不能依赖模型就绪状态（详见类注释）
        return Setting.isVoskEnabled();
    }
}

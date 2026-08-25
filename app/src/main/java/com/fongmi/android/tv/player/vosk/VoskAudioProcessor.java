package com.fongmi.android.tv.player.vosk;

import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

public class VoskAudioProcessor extends BaseAudioProcessor {

    private final VoskAdblock vosk;
    private int inputSampleRate;
    private int inputChannels;

    public VoskAudioProcessor(VoskAdblock vosk) {
        this.vosk = vosk;
    }

    @Override
    public AudioFormat onConfigure(AudioFormat inputAudioFormat) throws AudioProcessor.UnhandledAudioFormatException {
        this.inputSampleRate = inputAudioFormat.sampleRate;
        this.inputChannels = inputAudioFormat.channelCount;
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
        vosk.offerPcm16(copy, inputSampleRate, inputChannels);
    }

    @Override
    public boolean isActive() {
        // 只要 AI 去广开关开启就保持激活，绝不能依赖模型就绪状态：
        // media3 在 configure 时一次性评估 isActive()，若模型异步加载未完成时返回 false，
        // processor 会被永久标记为 inactive，queueInput 再也不会被调用，导致语音广告识别完全失效。
        // media3 的 DefaultAudioProcessorChain 会自动插入格式转换 processor，
        // 对不支持的 passthrough 格式也会回退，恒激活不会影响正常播放。
        return vosk.isEnabled();
    }
}

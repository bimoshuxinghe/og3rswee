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
        // 仅当模型已就绪时才激活音频处理器，避免在模型未就绪时强行把
        // 所有音频都送入 processor 链，干扰部分片源（float/passthrough）的正常渲染。
        return super.isActive() && vosk.isActive();
    }
}

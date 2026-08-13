package com.fongmi.android.tv.player.vosk;

import androidx.media3.common.AudioFormat;
import androidx.media3.common.C;
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
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw new AudioProcessor.UnhandledAudioFormatException(inputAudioFormat);
        }
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
        vosk.acceptPcm16(copy, inputSampleRate, inputChannels);
    }

    @Override
    public boolean isActive() {
        return vosk.isActive();
    }
}

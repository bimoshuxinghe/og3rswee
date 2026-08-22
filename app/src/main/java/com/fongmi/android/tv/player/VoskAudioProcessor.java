package com.fongmi.android.tv.player;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;

/**
 * 旁路 PCM 采集器：作为 {@link androidx.media3.exoplayer.audio.DefaultAudioSink}
 * 处理链中的一个 AudioProcessor，原样透传音频（不影响播放），同时把 PCM 数据
 * 复制一份交给 {@link VoskAsrManager} 做语音识别。仅在 Vosk 开启时激活。
 */
@UnstableApi
public final class VoskAudioProcessor extends BaseAudioProcessor {

    @Override
    public AudioFormat onConfigure(AudioFormat inputAudioFormat) throws AudioProcessor.UnhandledAudioFormatException {
        // 只支持 PCM（16bit/float），透传不改格式；其它格式抛异常让链跳过
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT && inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT) {
            throw new AudioProcessor.UnhandledAudioFormatException(inputAudioFormat);
        }
        return inputAudioFormat;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        int remaining = inputBuffer.remaining();
        if (remaining > 0) {
            byte[] copy = new byte[remaining];
            inputBuffer.duplicate().get(copy);
            VoskAsrManager.get().feedPcm(copy, inputAudioFormat.sampleRate, inputAudioFormat.channelCount,
                    inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT);
        }
        // 原样透传
        ByteBuffer output = replaceOutputBuffer(remaining);
        output.put(inputBuffer);
        output.flip();
    }
}

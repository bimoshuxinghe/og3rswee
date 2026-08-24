package com.fongmi.android.tv.player;

import android.media.AudioDeviceInfo;

import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.AuxEffectInfo;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.analytics.PlayerId;
import androidx.media3.exoplayer.audio.AudioCapabilities;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.AudioOutputProvider;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;

import java.nio.ByteBuffer;

import com.fongmi.android.tv.setting.Setting;

/**
 * 独立旁路 PCM 采集器：委托包装 {@link DefaultAudioSink}，在 handleBuffer 入口
 * 把解码后的 PCM 复制一份交给 {@link VoskAsrManager} 做语音识别。
 *
 * <p>与旧的 {@code VoskAudioProcessor}（挂在 DefaultAudioSink 内部处理链上）不同，
 * 本实现不向播放链路注册任何 AudioProcessor，Vosk 采集完全在播放器处理链之外：
 * <ul>
 *   <li>播放路径：原样交给内部 DefaultAudioSink，无任何处理器参与，不存在
 *       {@code UnhandledAudioFormatException} 导致 sink 初始化失败的风险；</li>
 *   <li>Vosk 路径：复制 buffer 独立入队，所有异常 try-catch 完全隔离，绝不影响播放。</li>
 * </ul>
 */
@UnstableApi
public final class VoskAudioSink implements AudioSink {

    private final AudioSink delegate;

    // Vosk 采集所需格式信息，configure 时从 inputFormat 记录
    private volatile boolean voskReady;
    private volatile int pcmSampleRate;
    private volatile int pcmChannelCount;
    private volatile boolean pcmIsFloat;

    public VoskAudioSink(AudioSink delegate) {
        this.delegate = delegate;
    }

    private boolean voskActive() {
        return Setting.isVoskEnabled() && voskReady;
    }

    @Override
    public void setListener(AudioSink.Listener listener) {
        delegate.setListener(listener);
    }

    @Override
    public void setPlayerId(@Nullable PlayerId playerId) {
        delegate.setPlayerId(playerId);
    }

    @Override
    public void setClock(Clock clock) {
        delegate.setClock(clock);
    }

    @Override
    public boolean supportsFormat(Format format) {
        return delegate.supportsFormat(format);
    }

    @Override
    public int getFormatSupport(Format format) {
        return delegate.getFormatSupport(format);
    }

    @Override
    public AudioOffloadSupport getFormatOffloadSupport(Format format) {
        return delegate.getFormatOffloadSupport(format);
    }

    @Override
    public long getCurrentPositionUs(boolean sourceEnded) {
        return delegate.getCurrentPositionUs(sourceEnded);
    }

    @Override
    public void configure(Format inputFormat, int specifiedBufferSize, @Nullable int[] outputChannels)
            throws AudioSink.ConfigurationException {
        if (inputFormat != null
                && MimeTypes.AUDIO_RAW.equals(inputFormat.sampleMimeType)
                && Util.isEncodingLinearPcm(inputFormat.pcmEncoding)) {
            pcmSampleRate = inputFormat.sampleRate;
            pcmChannelCount = inputFormat.channelCount;
            pcmIsFloat = inputFormat.pcmEncoding == C.ENCODING_PCM_FLOAT;
            voskReady = true;
        } else {
            // 非 PCM（offload / passthrough / 压缩直通），无 Vosk 数据可取
            voskReady = false;
        }
        delegate.configure(inputFormat, specifiedBufferSize, outputChannels);
    }

    @Override
    public void play() {
        delegate.play();
    }

    @Override
    public void handleDiscontinuity() {
        delegate.handleDiscontinuity();
    }

    @Override
    public boolean handleBuffer(ByteBuffer buffer, long presentationTimeUs, int encodedAccessUnitCount)
            throws AudioSink.InitializationException, AudioSink.WriteException {
        // Vosk 总开关开启即计数（含 voskReady=false 的非 PCM 帧），便于诊断"音频是否到达旁路"
        if (Setting.isVoskEnabled() && buffer != null && buffer.hasRemaining()) {
            try {
                int remaining = buffer.remaining();
                byte[] copy = new byte[remaining];
                buffer.duplicate().get(copy);
                if (voskReady) {
                    VoskAsrManager.get().feedPcm(copy, pcmSampleRate, pcmChannelCount, pcmIsFloat);
                } else {
                    // 收到音频但 configure 未提供 PCM 格式（压缩直通等），仅记录计数便于诊断
                    VoskAsrManager.get().feedPcm(copy, -1, 0, false);
                }
            } catch (Throwable t) {
                // 完全隔离：Vosk 采集任何异常都不影响播放链路
            }
        }
        return delegate.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount);
    }

    @Override
    public void playToEndOfStream() throws AudioSink.WriteException {
        delegate.playToEndOfStream();
    }

    @Override
    public boolean isEnded() {
        return delegate.isEnded();
    }

    @Override
    public boolean hasPendingData() {
        return delegate.hasPendingData();
    }

    @Override
    public void setPlaybackParameters(PlaybackParameters playbackParameters) {
        delegate.setPlaybackParameters(playbackParameters);
    }

    @Override
    public PlaybackParameters getPlaybackParameters() {
        return delegate.getPlaybackParameters();
    }

    @Override
    public void setSkipSilenceEnabled(boolean skipSilenceEnabled) {
        delegate.setSkipSilenceEnabled(skipSilenceEnabled);
    }

    @Override
    public boolean getSkipSilenceEnabled() {
        return delegate.getSkipSilenceEnabled();
    }

    @Override
    public void setAudioAttributes(AudioAttributes audioAttributes) {
        delegate.setAudioAttributes(audioAttributes);
    }

    @Nullable
    @Override
    public AudioAttributes getAudioAttributes() {
        return delegate.getAudioAttributes();
    }

    @Nullable
    @Override
    public AudioCapabilities getAudioCapabilities() {
        return delegate.getAudioCapabilities();
    }

    @Override
    public void setAudioSessionId(int audioSessionId) {
        delegate.setAudioSessionId(audioSessionId);
    }

    @Override
    public void setAuxEffectInfo(AuxEffectInfo auxEffectInfo) {
        delegate.setAuxEffectInfo(auxEffectInfo);
    }

    @Override
    public void setPreferredDevice(@Nullable AudioDeviceInfo audioDeviceInfo) {
        delegate.setPreferredDevice(audioDeviceInfo);
    }

    @Override
    public void setVirtualDeviceId(int virtualDeviceId) {
        delegate.setVirtualDeviceId(virtualDeviceId);
    }

    @Override
    public void setOutputStreamOffsetUs(long outputStreamOffsetUs) {
        delegate.setOutputStreamOffsetUs(outputStreamOffsetUs);
    }

    @Override
    public long getAudioTrackBufferSizeUs() {
        return delegate.getAudioTrackBufferSizeUs();
    }

    @Override
    public void enableTunnelingV21() {
        delegate.enableTunnelingV21();
    }

    @Override
    public void disableTunneling() {
        delegate.disableTunneling();
    }

    @androidx.annotation.RequiresApi(29)
    @Override
    public void setOffloadMode(@AudioSink.OffloadMode int offloadMode) {
        delegate.setOffloadMode(offloadMode);
    }

    @androidx.annotation.RequiresApi(29)
    @Override
    public void setOffloadDelayPadding(int delayInFrames, int paddingInFrames) {
        delegate.setOffloadDelayPadding(delayInFrames, paddingInFrames);
    }

    @Override
    public void setAudioOutputProvider(AudioOutputProvider audioOutputProvider) {
        delegate.setAudioOutputProvider(audioOutputProvider);
    }

    @Override
    public void setVolume(float volume) {
        delegate.setVolume(volume);
    }

    @Override
    public void pause() {
        delegate.pause();
    }

    @Override
    public void flush() {
        delegate.flush();
    }

    @Override
    public void reset() {
        delegate.reset();
    }

    @Override
    public void release() {
        delegate.release();
    }
}

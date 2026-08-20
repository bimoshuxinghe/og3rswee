-dontwarn android.content.res.**
-dontwarn org.checkerframework.**
-dontwarn kotlin.annotations.jvm.**
-dontwarn java.lang.ClassValue
-dontwarn java.lang.SafeVarargs
-dontwarn sun.misc.Unsafe
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.codehaus.mojo.animal_sniffer.IgnoreJRERequirement
-dontwarn org.ietf.jgss.**

# Media3 FFmpeg 解码器保护说明：
# growOutputBuffer 是 FfmpegAudioDecoder 中的 private 方法，仅被 native 代码通过 JNI 调用
# R8 优化会内联/移除"未使用"的 private 方法，导致 NoSuchMethodError 崩溃
# -keepclassmembers 只能防止混淆(重命名)，不能防止 R8 优化阶段移除方法
# 必须用 -dontoptimize(无参数) 禁用 R8 优化，并用 -keep,includedescriptorclasses 保护方法

# 禁用 R8 优化阶段（合法语法：-dontoptimize 不带参数 = 禁用全部优化）
# R8 优化会移除/内联"未使用"的 private 方法，但 JNI 调用对 R8 不可见
-dontoptimize
-dontwarn org.kxml2.io.**
-dontwarn org.xmlpull.v1.**
-dontwarn org.slf4j.impl.StaticLoggerBinder
-dontwarn org.conscrypt.**
-dontwarn javax.**
-dontwarn okio.**

-keep class org.xmlpull.** { *; }
-keepclassmembers class org.xmlpull.** { *; }

-keepclassmembernames class com.google.common.base.Function { *; }

-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }

-keepclasseswithmembernames class * {
    native <methods>;
}

# === libvlc 全包保护 ===
# libvlc-all-3.6.2.aar 不自带 consumer proguard rules，release 开启 minify 时必须手动 keep，
# 否则 R8 混淆 org.videolan.libvlc 内部类（如 MediaPlayer$Event）会导致 JNI 字段/方法查找失败，
# new LibVLC() 时 native 层崩溃（SIGSEGV/SIGABRT）直接闪退回桌面，Java try-catch 无法捕获。
-keep class org.videolan.libvlc.** { *; }
-keepclassmembers class org.videolan.libvlc.** { *; }
-keep interface org.videolan.libvlc.** { *; }
-keepclassmembers class org.videolan.libvlc.interfaces.** { *; }
-keep enum org.videolan.libvlc.** { *; }

# === Media3 Decoder 全包保护 ===
# R8 无法看到 JNI native 代码中的方法引用，会误删/重命名"未使用"的方法
# FfmpegAudioDecoder.growOutputBuffer 是 private 方法，仅被 native 代码调用
# R8 认为"未使用"就移除它，导致 NoSuchMethodError 崩溃
# 必须保护整个 decoder 包的所有类和方法

# 保留整个 decoder 包（含 SimpleDecoder 父类、SimpleDecoderOutputBuffer 等）
-keep class androidx.media3.decoder.** { *; }
-keepclassmembers class androidx.media3.decoder.** { *; }

# 保留整个 ffmpeg 子包（含 FfmpegAudioDecoder, FfmpegLibrary, FfmpegAudioRenderer 等）
# 崩溃日志显示 androidx.media3.decoder.ffmpeg.c 仍被混淆，说明之前的规则不够全面
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-keepclassmembers class androidx.media3.decoder.ffmpeg.** { *; }

# 特别保护 JNI 回调方法 growOutputBuffer
# 这是 FfmpegAudioDecoder 中的 private 方法，被 native 代码通过 JNI GetMethodID 调用
# 使用 -keep,includedescriptorclasses（与 Media3 官方 proguard-rules.txt 一致）
# -keep 防止混淆+移除，includedescriptorclasses 同时保护参数类型类不被移除
# 保留两个重载：2 参数版本匹配当前 ffmpeg_jni.cc 与 armeabi-v7a so；
# 3 参数版本匹配旧版 arm64-v8a 预编译 so，避免 NoSuchMethodError 崩溃。
-keep, includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
    private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int, int);
    native long ffmpegInitialize(java.lang.String, byte[], boolean, int, int);
    native int ffmpegDecode(long, java.nio.ByteBuffer, int, androidx.media3.decoder.SimpleDecoderOutputBuffer, java.nio.ByteBuffer, int);
    native int ffmpegGetChannelCount(long);
    native int ffmpegGetSampleRate(long);
    native long ffmpegReset(long, byte[]);
    native void ffmpegRelease(long);
}

# 保留 SimpleDecoder 父类及其泛型签名
# FfmpegAudioDecoder extends SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfmpegDecoderException>
# R8 改变泛型签名会导致 JNI 方法查找失败
-keep class androidx.media3.decoder.SimpleDecoder { *; }
-keep class androidx.media3.decoder.SimpleDecoderOutputBuffer { *; }
-keep class androidx.media3.decoder.DecoderInputBuffer { *; }
-keep class androidx.media3.decoder.VideoDecoderOutputBuffer { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegDecoderException { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegLibrary { *; }
-keep class androidx.media3.decoder.ffmpeg.FfmpegDecoder { *; }

# 保留泛型签名和注解，确保 JNI 方法签名匹配正确
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes InnerClasses
-keepattributes EnclosingMethod

-keepclassmembers class androidx.media3.datasource.RawResourceDataSource {
  public static android.net.Uri buildRawResourceUri(int);
}

-dontnote androidx.media3.datasource.rtmp.RtmpDataSource
-keepclassmembers class androidx.media3.datasource.rtmp.RtmpDataSource {
  <init>();
}

-dontnote androidx.media3.decoder.vp9.LibvpxVideoRenderer
-keepclassmembers class androidx.media3.decoder.vp9.LibvpxVideoRenderer {
  <init>(long, android.os.Handler, androidx.media3.exoplayer.video.VideoRendererEventListener, int);
}

-dontnote androidx.media3.decoder.av1.Libdav1dVideoRenderer
-keepclassmembers class androidx.media3.decoder.av1.Libdav1dVideoRenderer {
  <init>(long, android.os.Handler, androidx.media3.exoplayer.video.VideoRendererEventListener, int);
}

-dontnote androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer
-keepclassmembers class androidx.media3.decoder.ffmpeg.ExperimentalFfmpegVideoRenderer {
  <init>(long, android.os.Handler, androidx.media3.exoplayer.video.VideoRendererEventListener, int);
}

-dontnote androidx.media3.decoder.opus.LibopusAudioRenderer
-keepclassmembers class androidx.media3.decoder.opus.LibopusAudioRenderer {
  <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}

-dontnote androidx.media3.decoder.flac.LibflacAudioRenderer
-keepclassmembers class androidx.media3.decoder.flac.LibflacAudioRenderer {
  <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}

-dontnote androidx.media3.decoder.iamf.LibiamfAudioRenderer
-keepclassmembers class androidx.media3.decoder.iamf.LibiamfAudioRenderer {
  <init>(android.content.Context, android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}

-dontnote androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
-keepclassmembers class androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer {
  <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}

-dontnote androidx.media3.decoder.midi.MidiRenderer
-keepclassmembers class androidx.media3.decoder.midi.MidiRenderer {
  <init>(android.content.Context, android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}

-dontnote androidx.media3.decoder.mpegh.MpeghAudioRenderer
-keepclassmembers class androidx.media3.decoder.mpegh.MpeghAudioRenderer {
  <init>(android.os.Handler, androidx.media3.exoplayer.audio.AudioRendererEventListener, androidx.media3.exoplayer.audio.AudioSink);
}

-dontnote androidx.media3.exoplayer.dash.offline.DashDownloader$Factory
-keepclassmembers class androidx.media3.exoplayer.dash.offline.DashDownloader$Factory {
  <init>(androidx.media3.datasource.cache.CacheDataSource$Factory);
}

-dontnote androidx.media3.exoplayer.hls.offline.HlsDownloader$Factory
-keepclassmembers class androidx.media3.exoplayer.hls.offline.HlsDownloader$Factory {
  <init>(androidx.media3.datasource.cache.CacheDataSource$Factory);
}

-dontnote androidx.media3.exoplayer.smoothstreaming.offline.SsDownloader$Factory
-keepclassmembers class androidx.media3.exoplayer.smoothstreaming.offline.SsDownloader$Factory {
  <init>(androidx.media3.datasource.cache.CacheDataSource$Factory);
}

-dontnote androidx.media3.exoplayer.dash.DashMediaSource$Factory
-keepclasseswithmembers class androidx.media3.exoplayer.dash.DashMediaSource$Factory {
  <init>(androidx.media3.datasource.DataSource$Factory);
}

-dontnote androidx.media3.exoplayer.hls.HlsMediaSource$Factory
-keepclasseswithmembers class androidx.media3.exoplayer.hls.HlsMediaSource$Factory {
  <init>(androidx.media3.datasource.DataSource$Factory);
}

-dontnote androidx.media3.exoplayer.smoothstreaming.SsMediaSource$Factory
-keepclasseswithmembers class androidx.media3.exoplayer.smoothstreaming.SsMediaSource$Factory {
  <init>(androidx.media3.datasource.DataSource$Factory);
}

-dontnote androidx.media3.exoplayer.rtsp.RtspMediaSource$Factory
-keepclasseswithmembers class androidx.media3.exoplayer.rtsp.RtspMediaSource$Factory {
  <init>();
}

-if class * implements androidx.media3.exoplayer.ExoPlayer {
    public void setVideoEffects(java.util.List);
}
-keepclasseswithmembers class androidx.media3.effect.SingleInputVideoGraph$Factory {
  <init>(androidx.media3.common.VideoFrameProcessor$Factory);
}

-if class * implements androidx.media3.exoplayer.ExoPlayer {
    public void setVideoEffects(java.util.List);
}
-keepclasseswithmembers class androidx.media3.effect.DefaultVideoFrameProcessor$Factory$Builder {
  <init>();
  androidx.media3.effect.DefaultVideoFrameProcessor$Factory build();
  androidx.media3.effect.DefaultVideoFrameProcessor$Factory$Builder setEnableReplayableCache(boolean);
}

-dontnote androidx.media3.effect.SingleInputVideoGraph$Factory
-dontnote androidx.media3.effect.DefaultVideoFrameProcessor$Factory$Builder

-dontnote androidx.media3.decoder.flac.FlacExtractor
-keepclassmembers class androidx.media3.decoder.flac.FlacExtractor {
  <init>(int);
}

-dontnote androidx.media3.decoder.flac.FlacLibrary
-keepclassmembers class androidx.media3.decoder.flac.FlacLibrary {
  public static boolean isAvailable();
}

-dontnote androidx.media3.decoder.midi.MidiExtractor
-keepclassmembers class androidx.media3.decoder.midi.MidiExtractor {
  <init>();
}

-dontnote androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView
-keepclassmembers class androidx.media3.exoplayer.video.spherical.SphericalGLSurfaceView {
  <init>(android.content.Context);
}

-dontnote androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView
-keepclassmembers class androidx.media3.exoplayer.video.VideoDecoderGLSurfaceView {
  <init>(android.content.Context);
}

-keepnames class androidx.media3.exoplayer.ExoPlayer {}
-keepclassmembers class androidx.media3.exoplayer.ExoPlayer {
  void setImageOutput(androidx.media3.exoplayer.image.ImageOutput);
  void setScrubbingModeEnabled(boolean);
  boolean isScrubbingModeEnabled();
}

-keepclasseswithmembers class androidx.media3.exoplayer.image.ImageOutput {
  void onImageAvailable(long, android.graphics.Bitmap);
}

-keepnames class androidx.media3.transformer.CompositionPlayer {}
-keepclassmembers class androidx.media3.transformer.CompositionPlayer {
  void setScrubbingModeEnabled(boolean);
  boolean isScrubbingModeEnabled();
}

-dontnote androidx.appcompat.app.AlertDialog.Builder
-keepclassmembers class androidx.appcompat.app.AlertDialog$Builder {
  <init>(android.content.Context, int);
  public android.content.Context getContext();
  public androidx.appcompat.app.AlertDialog$Builder setTitle(java.lang.CharSequence);
  public androidx.appcompat.app.AlertDialog$Builder setView(android.view.View);
  public androidx.appcompat.app.AlertDialog$Builder setPositiveButton(int, android.content.DialogInterface$OnClickListener);
  public androidx.appcompat.app.AlertDialog$Builder setNegativeButton(int, android.content.DialogInterface$OnClickListener);
  public androidx.appcompat.app.AlertDialog create();
}

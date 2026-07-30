# MPV 播放器接入指南

本文记录当前项目中 MPV 播放内核的接入方式，方便后续迁移、排查和继续扩展。当前实现的核心思路是：**保留原来的播放页 UI 和 Media3 控制体系，只把底层播放核心从 EXO 换成 MPV**。

## 1. 接入目标

- EXO 和 MPV 作为同级播放内核存在。
- 播放页、选集、进度、字幕、音轨、倍速、控制栏等 UI 继续复用原逻辑。
- 用户点击播放器名称时可在 EXO / MPV 间切换。
- MPV 可读取同一份 `PlaySpec`，尽量复用 EXO 的地址、请求头、字幕、DRM 元信息。
- MPV native 库不可用时，不崩溃，提示原因并保持 EXO。

## 2. Native 库接入

### 2.1 JNI 桥接类

MPV 的 Java 入口是：

```text
app/src/main/java/is/xyz/mpv/MPVLib.java
```

它负责加载 native 库：

```java
System.loadLibrary("mpv");
System.loadLibrary("player");
```

并暴露 MPV 所需 JNI 方法，例如：

- `create(Context)`
- `init()`
- `destroy()`
- `attachSurface(Surface)`
- `detachSurface()`
- `command(String[])`
- `setOptionString(...)`
- `getProperty...(...)`
- `observeProperty(...)`

### 2.2 SO 放置位置

native 库放在：

```text
app/src/main/jniLibs/arm64-v8a/
app/src/main/jniLibs/armeabi-v7a/
```

至少需要：

- `libmpv.so`
- `libplayer.so`
- MPV 依赖的 FFmpeg / dav1d / c++ 等 so

如果某个 ABI 缺少依赖，运行时会出现类似：

```text
MPV 不可用：is.xyz.mpv.MPVLib
native 库加载失败
dlopen failed
```

### 2.3 ABI 分包

项目通过 product flavor 控制 ABI：

```gradle
arm64_v8a {
    ndk { abiFilters "arm64-v8a" }
}

armeabi_v7a {
    ndk { abiFilters "armeabi-v7a" }
}
```

所以打包时要选对应任务，例如：

```powershell
.\gradlew.bat :app:assembleLeanbackArm64_v8aRelease
.\gradlew.bat :app:assembleLeanbackArmeabi_v7aRelease
.\gradlew.bat :app:assembleMobileArm64_v8aRelease
```

## 3. 播放器架构

当前播放器分三层：

```text
播放页 UI / MediaSession
        ↓
PlayerManager
        ↓
PlayerEngine 接口
        ↓
ExoPlayerEngine 或 MpvPlayerEngine
        ↓
EXO Player 或 MpvSimplePlayer
        ↓
MPVLib / native mpv
```

### 3.1 PlayerManager：播放器选择中心

`PlayerManager` 是 EXO / MPV 的切换入口。

关键逻辑：

- `toggleEngine()`：用户点击播放器按钮时切换 EXO / MPV。
- `canUseMpv(spec)`：判断当前配置和资源是否允许使用 MPV。
- `getMpvUnsupportedReason(spec)`：MPV 不可用时返回提示原因。
- `buildEngine(...)`：根据当前设置创建 `MpvPlayerEngine` 或 `ExoPlayerEngine`。
- `switchEngine()`：切换内核时记录当前位置，再用新内核继续播放。

注意：这里不直接操作 MPV native，只负责决策和生命周期衔接。

### 3.2 PlayerEngine：统一播放器接口

`MpvPlayerEngine` 实现项目原有的 `PlayerEngine` 接口，让 MPV 看起来和 EXO 一样：

```text
app/src/main/java/com/fongmi/android/tv/player/engine/MpvPlayerEngine.java
```

它负责：

- 创建 `MpvSimplePlayer`
- 接收 `PlaySpec`
- 调用 `ExoUtil.getMediaItem(spec, decode)` 复用 MediaItem 构建逻辑
- 转发播放、暂停、seek、倍速、音轨、字幕、释放等操作

关键点是：**MPV 仍然被包装成 Media3 `Player` 风格的对象**，这样原 UI 不需要重新写一套。

### 3.3 MpvSimplePlayer：MPV 的 Media3 适配层

`MpvSimplePlayer` 是核心适配类：

```text
app/src/main/java/com/fongmi/android/tv/player/mpv/MpvSimplePlayer.java
```

它继承：

```java
SimpleBasePlayer
```

并实现：

```java
MPVLib.EventObserver
MPVLib.LogObserver
```

主要职责：

- 初始化 MPV：`MPVLib.create()`、设置 options、`MPVLib.init()`
- 加载播放地址：`command("loadfile", ...)`
- 接收 Media3 的 `setMediaItems`、`prepare`、`seek`、`stop`、`release`
- 监听 MPV 属性：进度、时长、缓冲、暂停状态、音轨字幕轨、视频尺寸
- 把 MPV 状态转换成 Media3 的 `Player.STATE_BUFFERING / READY / ENDED / IDLE`
- 管理 Surface，把 Android 播放画面交给 MPV 渲染

## 4. 设置项接入

播放器设置集中在：

```text
app/src/main/java/com/fongmi/android/tv/setting/PlayerSetting.java
```

核心字段：

- `ENGINE_EXO = 0`
- `ENGINE_MPV = 1`
- `player_engine`：当前播放内核
- `mpv_render`：MPV 渲染方式
- `mpv_audio_passthrough`：MPV 音频直通
- `mpv_dolby_passthrough`：MPV 杜比直通
- `mpv_config_name`：外部配置文件名称或 URL

MPV 配置文件会导入到：

```text
files/mpv/mpv.conf
```

运行时如果存在配置文件，会设置：

```text
config=yes
config-dir=<files/mpv>
```

否则使用：

```text
config=no
```

## 5. MPV 初始化参数

`MpvSimplePlayer.initialize()` 会设置一批默认参数，主要包括：

- `profile=fast`
- 渲染方式：`vo=gpu` 或 `vo=gpu-next`
- `gpu-api=opengl / vulkan`
- `gpu-context=android / androidvk`
- `hwdec=mediacodec-copy / no`
- `hwdec-codecs=h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1`
- `tls-verify=no`
- `ytdl=no`
- `demuxer-max-bytes`
- `demuxer-max-back-bytes`
- `idle=yes`
- `force-window=no`

### 5.1 软解 / 硬解

当前映射：

```text
硬解 → hwdec=mediacodec-copy
软解 → hwdec=no
```

切换软硬解时会重新加载当前资源，并尽量保留进度。

### 5.2 渲染方式

当前支持：

- `gpu`
- `gpu-next`
- `gpu-next + vulkan`

大致映射：

```text
OpenGL GPU       → vo=gpu, gpu-api=opengl, gpu-context=android
OpenGL GPU Next  → vo=gpu-next, gpu-api=opengl, gpu-context=android
Vulkan GPU Next  → vo=gpu-next, gpu-api=vulkan, gpu-context=androidvk
```

不同盒子对 Vulkan / MediaCodec / HDR 的兼容差异很大，卡顿、黑屏、偏色时优先切回 OpenGL。

## 6. 播放地址加载

普通资源：

```java
command("loadfile", playableUrl, "replace");
```

带选项资源：

```java
command("loadfile", playableUrl, "replace", "-1", options);
```

这里的 `-1` 很重要：当前 MPV JNI 对 `loadfile` 参数格式敏感，第 4 个参数是播放列表索引，真正的 `options` 要放第 5 个参数。否则会报：

```text
The loadfile option must be an integer
Command loadfile: argument index can't be parsed
```

### 6.1 HLS / m3u8 特殊处理

有些本地代理地址是：

```text
http://127.0.0.1:9978/proxy?do=xxx&url=...
```

URL 路径本身不以 `.m3u8` 结尾，MPV / FFmpeg 可能误判成普通 playlist。当前处理是根据 MediaItem 的 mimeType 或 URL 内容判断 HLS，并给 MPV 加：

```text
demuxer-lavf-format=hls
```

这样 MPV 会按 HLS 流处理，而不是把 m3u8 拆成普通列表逐个打开 ts。

## 7. Surface 渲染接入

`MpvSimplePlayer` 支持接收：

- `Surface`
- `SurfaceHolder`
- `SurfaceView`
- `TextureView`

最终会调用：

```java
MPVLib.attachSurface(surface);
setMpvProperty("android-surface-size", width + "x" + height);
setMpvProperty("vo", getMpvVo());
setMpvOption("force-window", "yes");
```

退出或释放时调用：

```java
MPVLib.detachSurface();
setMpvProperty("vo", "null");
setMpvOption("force-window", "no");
```

这部分是之前解决“退出播放页仍在播放 / 换集无画面 / 加载圈不消失”的关键区域。

## 8. 状态同步

MPV 事件通过 `event(...)` 进入 Java：

- `MPV_EVENT_START_FILE`：进入缓冲
- `MPV_EVENT_FILE_LOADED`：文件加载完成
- `MPV_EVENT_PLAYBACK_RESTART`：播放真正开始 / 恢复
- `MPV_EVENT_SEEK`：seek 中
- `MPV_EVENT_VIDEO_RECONFIG`：视频轨或尺寸变化
- `MPV_EVENT_AUDIO_RECONFIG`：音频轨变化
- `MPV_EVENT_END_FILE`：播放结束或失败

MPV 属性通过 `observeProperty(...)` 监听：

- `time-pos`：当前进度
- `duration/full`：总时长
- `demuxer-cache-time`：缓存时长
- `pause`：暂停状态
- `eof-reached`：结束状态
- `width` / `height`：视频尺寸
- `track-list`：音轨 / 字幕轨 / 视频轨列表

这些状态最后会映射到 Media3 `SimpleBasePlayer.State`，让原来的播放页 UI 能继续使用。

## 9. 音轨和字幕

### 9.1 外挂字幕

MediaItem 中的外部字幕会在 MPV 文件加载完成后通过：

```text
sub-add
```

传给 MPV。

### 9.2 内置轨道

MPV 的 `track-list` 会被转换成项目已有的 `Tracks.Group`，这样现有音轨 / 字幕菜单可以复用。

轨道选择通过设置：

```text
aid
sid
vid
```

实现。

## 10. 音频直通和杜比

音频直通逻辑在：

```text
app/src/main/java/com/fongmi/android/tv/player/mpv/MpvAudioPassthrough.java
```

它会根据 Android 设备能力判断支持格式，再设置：

```text
audio-spdif=<formats>
```

如果设备不支持或日志识别到直通失败，会关闭对应设置并按原进度重载 MPV。

## 11. 特殊媒体支持

辅助判断在：

```text
app/src/main/java/com/fongmi/android/tv/player/mpv/MpvMedia.java
```

当前处理：

- 伪装图片分片：`.png`、`.jpg`、`.jpeg`、`.webp`、`.gif`
- Blu-ray ISO：`.iso`
- ISO 会转换为 MPV 的 `bd://` 播放方式，并设置 `bluray-device`

注意：这些能力只是让 MPV 能接收这类资源，不代表所有设备都能硬解流畅播放。

## 12. 接入一个新项目时的最小步骤

如果要把当前 MPV 接入方案迁移到另一个 Android 项目，最小步骤如下：

1. 放入 native 库：
   - `jniLibs/arm64-v8a/libmpv.so`
   - `jniLibs/arm64-v8a/libplayer.so`
   - 对应 ABI 的所有依赖 so
2. 加入 JNI 桥接：
   - `is.xyz.mpv.MPVLib`
3. 新建 MPV 播放适配层：
   - 继承 Media3 `SimpleBasePlayer`
   - 实现 MPV 事件和日志监听
4. 新建统一播放器接口：
   - 让 EXO 和 MPV 都实现同一套 `PlayerEngine`
5. 在播放器管理器中加入内核切换：
   - EXO / MPV 配置保存
   - MPV 可用性检测
   - 切换时保存当前进度并继续播放
6. 把 Surface 交给 MPV：
   - `attachSurface`
   - 设置 `android-surface-size`
   - 退出时 `detachSurface`
7. 把 MPV 状态映射回 UI：
   - 进度
   - 缓冲
   - 时长
   - 错误
   - 播放结束
8. 处理 HLS、本地代理、字幕、音轨等兼容问题。

## 13. 常见问题排查

### 13.1 提示 MPV 不可用

优先检查：

- ABI 是否匹配设备。
- `libmpv.so` 是否存在。
- `libplayer.so` 是否存在。
- MPV 依赖 so 是否缺失。
- APK 是否真的包含对应 ABI。

### 13.2 有声音没画面

优先检查：

- `attachSurface` 是否调用。
- `android-surface-size` 是否设置。
- 换集 / 退出时是否过早 `vo=null`。
- 渲染方式是否和设备兼容。
- 硬解是否输出了设备不支持的格式。

### 13.3 m3u8 / 代理资源打不开

优先检查：

- 代理是否返回 200。
- playlist 的 Content-Type 是否正确。
- 分片是否能返回 `video/mp2t`。
- MPV 是否误判为 playlist。
- 必要时加 `demuxer-lavf-format=hls`。
- `loadfile` 参数格式是否为 `url, replace, -1, options`。

### 13.4 切换内核后从头播放

优先检查：

- `PlayerManager.switchEngine()` 是否记录当前位置。
- `setMediaItem(timeout, position)` 是否把位置传给新内核。
- MPV 是否先打开流再 seek，避免某些 m3u8 直接 `start=xxx` 卡死。

### 13.5 盒子 MPV 卡顿

MPV 比 EXO 更依赖设备 GPU / Vulkan / MediaCodec 组合。低端盒子可能 EXO 流畅、MPV 卡顿。建议：

- 优先 OpenGL。
- 关闭 Vulkan。
- 尝试软解 / 硬解切换。
- 高码率 HDR / 杜比视界资源优先用 EXO。

## 14. 当前实现边界

- MPV 已作为同级播放内核接入，但不是所有资源都保证优于 EXO。
- EXO 对 Android 设备硬解、HDR、系统解码链路更稳定。
- MPV 更适合补足 EXO 处理不好的格式、特殊流、外挂字幕和未来高级音频能力。
- 自动失败回退 EXO 目前应谨慎开启，调试阶段会掩盖 MPV 原始错误。


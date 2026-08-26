---
AIGC:
    Label: "1"
    ContentProducer: 001191440300708461136T1XGW3
    ProduceID: 67d4fb44e116ce1e6018c56d46f39de6_bf0054c8a14f11f1a65b525400826444
    ReservedCode1: P1yHJFk/qH1PzXL3/ZuTdE0V5grUnCn3wFazUoIrBvvLWrln5xxNK6UOQlVDvdnCUkeM8XTFVJZKzq4i6NTHCUXEKTt9TuNMwpZeyVxnBmZRhe41FAFqcHbhPTpwvUO2urxw0Ch2IqJ+wyE6Aid7Uy6jU6RpxkiyDSrOye7141Li///SucZybCMsbx0=
    ContentPropagator: 001191440300708461136T1XGW3
    PropagateID: 67d4fb44e116ce1e6018c56d46f39de6_bf0054c8a14f11f1a65b525400826444
    ReservedCode2: P1yHJFk/qH1PzXL3/ZuTdE0V5grUnCn3wFazUoIrBvvLWrln5xxNK6UOQlVDvdnCUkeM8XTFVJZKzq4i6NTHCUXEKTt9TuNMwpZeyVxnBmZRhe41FAFqcHbhPTpwvUO2urxw0Ch2IqJ+wyE6Aid7Uy6jU6RpxkiyDSrOye7141Li///SucZybCMsbx0=
---

# media-kit 播放器替换交付说明（手机版 arm64-v8a 限定）

## 一、本次改动总览

| 项目 | 内容 |
|------|------|
| 播放器内核 | 移除原 mpv-android 的 `libplayer.so`，替换为 media-kit 官方 `libmpv-android-video-build v1.1.7` 的 `libmpv.so` |
| 原生库 | `app/src/main/jniLibs/arm64-v8a/`：`libmpv.so` + `libmediakitandroidhelper.so` |
| JNI 桥接 | 新增 `app/src/main/cpp/mpv_bridge.c`（NDK 编译产出 `libmpvkit.so`） |
| 构建配置 | 新增 `app/src/main/cpp/CMakeLists.txt`；`app/build.gradle` 接入 externalNativeBuild |
| Java 层 | `is.xyz.mpv.MPVLib` 静态块改为加载 `libmpv` + `libmpvkit` |
| 插件修复 | `JarLoader.java` 已按 FongMi/tv 官方逻辑修复（标准父加载器） |

## 二、只保留手机版 v8a

- **ABI**：jniLibs 仅保留 `arm64-v8a`，已删除 `armeabi-v7a` 原生库。
- **Mode**：打包时只构建 `mobile`（手机版），不构建 `leanback`（大屏/电视版）。

## 三、构建命令（Android Studio 或命令行）

```bash
# 命令行构建手机版 v8a 正式包
./gradlew assembleMobileArm64_v8aRelease

# 调试包
./gradlew assembleMobileArm64_v8aDebug
```

产物：`app/build/outputs/apk/mobileArm64_v8a/release/mobile-arm64_v8a.apk`

## 四、桥接层实现要点

1. **事件循环**：独立 pthread 调用 `mpv_wait_event`，事件转发回 Java 静态回调
   （`eventProperty` / `event` / `eventEndFile` / `eventCommandReply` / `logMessage`）。
2. **渲染**：EGL + OpenGL ES2 + 标准 `mpv_render_context`（`MPV_RENDER_API_TYPE_OPENGL`），
   渲染线程由 `mpv_render_context_set_update_callback` 信号驱动，`eglSwapBuffers` 上屏。
3. **硬解**：初始化前调用 media-kit 扩展 `mpv_lavc_set_java_vm` + `av_jni_set_java_vm`，
   Java 层 `hwdec=mediacodec` 即可工作。
4. **OSD**：`attachOsdSurface` 等为兼容空实现（渲染走同一 render context）。

## 五、注意事项

- `MPVLib.java` 的 native 方法签名与 `mpv_bridge.c` 一一对应，新增/修改 native 方法需同步两侧。
- proguard 已含 `-keep class is.xyz.mpv.MPVLib { *; }` 与 native 方法保护规则。
- 本机无 NDK 时由 Android Studio 自动下载；CMake 要求 3.22.1+。
*（内容由AI生成，仅供参考）*

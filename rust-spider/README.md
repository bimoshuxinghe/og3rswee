# spider_4kvm — TVBox 内置 Rust 爬虫引擎

将 4KVM（4k影视）Python 插件移植为 Rust 原生库（.so），通过 JNI 集成到 TVBox 壳子。
站点配置使用 `rust://spider_4kvm` 形式加载（见 `BaseLoader` 的 `rust://` 分支）。

## 架构

```
Java 侧 (com.fongmi.android.tv.api.loader)
  RustLoader → RustSpider (extends Spider) → RustBridge (JNI)
                                                    ↓ JNI
Rust 侧 (libspider_4kvm.so)
  jni_api.rs  → spider.rs（首页/分类/详情/搜索/播放）
                 └→ wasm_engine.rs（wasmi 执行站点 WASM 生成播放签名）
```

- **站点 WASM 签名**：播放页 API 需 wasm-bindgen 模块 `build_play_url(dataid, secret, quality, play_key)`
  生成签名参数。引擎用纯 Rust 的 wasmi 解释器内嵌执行 `wasm/nbmovie_wasm_bg.wasm`，
  与网站算法永久同步（站点更新 WASM 时只需替换 wasm/ 下文件重新编译）。
- **协议**：TVBox Spider 协议 JSON（homeContent/categoryContent/detailContent/searchContent/playerContent）。

## 构建

依赖：Rust stable、Android NDK r26d、rustup targets（aarch64-linux-android、armv7-linux-androideabi）。

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi
export NDK_HOME=~/android-ndk-r26d   # 指向 NDK 根目录
./build-rust.sh
```

产物：
- `target/aarch64-linux-android/release/libspider_4kvm.so` → 复制到 `app/src/main/jniLibs/arm64-v8a/`
- `target/armv7-linux-androideabi/release/libspider_4kvm.so` → 复制到 `app/src/main/jniLibs/armeabi-v7a/`

## 测试

```bash
cargo test                      # WASM 引擎单元测试（无需网络）
cargo test --test spider_test   # 端到端测试（需网络，验证数据与播放线路）
```

## 站点配置（ext）

```json
{"host": "https://www.4kvms.org/"}
```

备用域名自动容错（源自播放页 `window._pdf`：oss.douyinbit.com / myoss.douyinbit.top）。

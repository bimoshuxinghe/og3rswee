# spider_rust — TVBox 内置通用 Rust 爬虫引擎

依据《TV Box 内置 Rust 爬虫引擎技术文档》实现：壳子（FongMi/TV 系）内置的通用 Rust 爬虫运行时。
**单个 `libspider_rust.so` 服务多个站点**——站点配置使用统一的 `rust://spider_rust` 入口，
由引擎按 `ext.module`（或 host 特征）分发到对应站点的 Rust 实现。

```
壳子站点配置 (JSON)                      Rust 引擎 (libspider_rust.so)
{"api":"rust://spider_rust",     →   RustLoader → RustSpider → RustBridge(JNI)
 "ext":{"host":"...",                     → jni_api.rs (host+ext 双参数)
          "module":"4kvm"}}               → spider/mod.rs 分发层
                                              ├─ fourkvm   (4KVM 站点)
                                              └─ ...       (未来新增站点)
```

## 已内置站点

| 模块名 | 站点 | host 特征 | 说明 |
|--------|------|-----------|------|
| `4kvm` | 4K影视 | `4kvms` / `4kvm` | 由 `4KVM.py` 完整移植，含 WASM 播放签名 |

## 构建

依赖：Rust stable、Android NDK r26d（或自行设置 `NDK_HOME`）。

```bash
# 一键交叉编译（双 ABI: arm64-v8a + armeabi-v7a）
./build-rust.sh

# 产物
target/aarch64-linux-android/release/libspider_rust.so
target/armv7-linux-androideabi/release/libspider_rust.so
```

部署到壳子工程：

```bash
cp target/aarch64-linux-android/release/libspider_rust.so   <TVBox仓库>/app/src/main/jniLibs/arm64-v8a/
cp target/armv7-linux-androideabi/release/libspider_rust.so <TVBox仓库>/app/src/main/jniLibs/armeabi-v7a/
```

## 测试

```bash
cargo test            # WASM 单测 + 分发逻辑测试 + 端到端网络测试
```

## 壳子侧配置（多站点共用引擎）

技术文档 4.3 的多插件配置示例——所有 Rust 站点共用同一个 api：

```json
{
  "sites": [
    {
      "key": "rust_4kvm",
      "name": "4K影视[Rust]",
      "type": 1,
      "api": "rust://spider_rust",
      "ext": "{\"host\":\"https://www.4kvms.org/\"}"
    }
  ]
}
```

`ext` 支持的字段（均为可选，见 `src/spider/mod.rs` 的 `SiteExt`）：

| 字段 | 说明 |
|------|------|
| `host` | 站点域名（必需，如 `https://www.4kvms.org/`） |
| `module` | 站点模块名；缺省时按 host 特征推断（如 host 含 `4kvms` → `4kvm`） |
| `user_agent` | 自定义 UA（缺省用内置桌面版 UA） |
| `cookie` | 自定义 Cookie |
| `timeout` | 请求超时（秒） |
| `backup_hosts` | 备用域名列表（主域名不可达时切换） |
| `headers` | 额外请求头，JSON 二维数组：`[["X-Token","abc"]]` |

## 新增一个站点的 Rust 实现

引擎是**通用运行时**，新增站点不需要新的 `.so`，按以下四步走：

1. **写模块**：在 `src/spider/` 新增 `mysite.rs`，实现该站点的爬虫
   （参照 `fourkvm.rs`：`new(ext: &SiteExt)` + `home_content/category_content/detail_content/search_content/player_content`）；
2. **注册分发**：在 `src/spider/mod.rs` 中 `pub mod mysite;`，并在五个入口函数的
   `match ext.resolve_module().as_str()` 中各加一个分支；
3. **重编译**：`./build-rust.sh`，把新 `.so` 部署到壳子 `jniLibs`；
4. **加配置**：站点配置写
   `"api":"rust://spider_rust","ext":{"host":"https://你的站点/","module":"mysite"}"`
   （`module` 与 host 特征推断二选一即可生效）。

> 分发层对未实现的 module 会返回明确错误（`引擎暂无该站点的 Rust 实现: module=xxx`），
> 壳子按空结果处理，不影响其他站点。

## 目录结构

```
rust-spider/
├── build-rust.sh            # 一键交叉编译（双 ABI）
├── src/
│   ├── lib.rs               # crate 入口（spider_rust）
│   ├── jni_api.rs           # JNI 导出层（host+ext 双参数，panic 安全）
│   ├── spider/
│   │   ├── mod.rs           # 分发层：SiteExt 解析 + 多站点注册
│   │   └── fourkvm.rs       # 4KVM 站点实现（4KVM.py 移植）
│   ├── types.rs             # Spider 协议数据结构
│   └── wasm_engine.rs       # wasmi 执行站点 WASM 生成播放签名
├── tests/
│   ├── spider_test.rs       # 端到端 + 分发逻辑测试
│   └── wasm_test.rs         # WASM 签名引擎单测
└── wasm/
    └── nbmovie_wasm_bg.wasm # 4KVM 站点 WASM（编译期内嵌）
```

## 技术要点

- **纯 Rust 依赖**：`reqwest` 关 default-tls 改用 `rustls-tls`，Android 上无 OpenSSL 依赖；
  WASM 签名用 `wasmi` 纯 Rust 解释器，无 C 依赖。
- **panic 安全**：release 不用 `panic="abort"`，所有 JNI 入口包 `catch_unwind`，
  Rust panic 不会拖垮 App。
- **交叉编译环境变量**：cc-rs 用小写目标名（`CC_aarch64_linux_android`），
  cargo 链接器用大写目标名（`CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER`），
  均由 `build-rust.sh` 统一 export（`.cargo/config.toml` 的 `[env]` 不传递给 build script 子进程）。
- **JNI 签名**：与壳子 `RustBridge.java` 的 native 方法一一对应（技术文档 5.1），
  改签名时需同步改两侧并重新生成头文件校验。

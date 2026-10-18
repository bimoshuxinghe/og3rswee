//! spider_rust — TVBox 内置通用 Rust 爬虫引擎
//!
//! 依据《TV Box 内置 Rust 爬虫引擎技术文档》实现的通用引擎：
//! 单个 `libspider_rust.so` 通过 `ext.module`（或 host 特征）分发到不同站点模块，
//! 壳子侧站点配置使用 `rust://spider_rust` 形式加载。
//!
//! 模块划分：
//! - [`types`]        : Spider 协议数据结构（Vod / Category / Detail / Play）
//! - [`spider`]       : 站点分发层（SiteExt 解析 + 多站点模块注册）
//! - [`spider::fourkvm`] : 4KVM 站点实现（首个内置站点）
//! - [`wasm_engine`]  : 站点 WASM 签名引擎（播放地址生成）
//! - [`jni_api`]      : JNI 导出层（壳子 Java 侧调用）
//!
//! 新增一个站点的 Rust 实现只需：
//! 1. 在 `src/spider/` 新增模块（如 `mysite.rs`）；
//! 2. 在 `src/spider/mod.rs` 的 `pub mod` 与各入口 `match` 中注册；
//! 3. 重编译 .so 后，站点配置 `"api":"rust://spider_rust","ext":{"host":"...","module":"mysite"}` 即可使用。

pub mod jni_api;
pub mod spider;
pub mod types;
pub mod wasm_engine;

pub use spider::SiteExt;

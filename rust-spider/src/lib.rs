//! spider_4kvm — TVBox 内置 Rust 爬虫引擎
//!
//! 将 4KVM（4k影视）Python 插件移植为 Rust 原生库（.so），
//! 通过 JNI 集成到 TVBox 壳子，站点配置使用 `rust://spider_4kvm` 形式加载。
//!
//! 模块划分：
//! - [`types`]       : Spider 协议数据结构（Vod / Category / Detail / Play）
//! - [`wasm_engine`] : 站点 WASM 签名引擎（播放地址生成）
//! - [`spider`]      : 4KVM 爬虫核心逻辑（首页/分类/详情/搜索/播放）
//! - [`jni_api`]     : JNI 导出层（壳子 Java 侧调用）

pub mod jni_api;
pub mod spider;
pub mod types;
pub mod wasm_engine;

pub use spider::Spider4KVM;

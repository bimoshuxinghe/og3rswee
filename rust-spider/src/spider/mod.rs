//! 站点分发层：把壳子下发的请求按 `ext.module`（或 host 特征）分发到对应站点模块。
//!
//! 对应技术文档 4.3「多插件配置」：多个站点共用 `"api": "rust://spider_rust"`，
//! 由本层依据 ext 决定实际执行哪个站点的 Rust 实现。
//!
//! # 新增站点流程
//! 1. 在 `src/spider/` 下新增模块文件（如 `mysite.rs`），实现该站点的爬虫；
//! 2. 在本文件 `pub mod` 声明模块，并在各入口函数的 `match` 中注册分支；
//! 3. 重编译 .so（`./build-rust.sh`）；
//! 4. 站点配置写入 `"api":"rust://spider_rust","ext":{"host":"https://...","module":"mysite"}`
//!    （`module` 可省略，此时按 host 特征自动识别，识别不了则报错提示）。

pub mod fourkvm;

use serde::Deserialize;

/// 站点扩展配置（来自壳子站点配置的 ext JSON）
///
/// 字段均为可选，未提供时使用各站点模块的默认值：
/// - `host`          : 站点域名（必需，如 `https://www.4kvms.org/`）
/// - `module`        : 站点模块名（如 `4kvm`）；缺省时按 host 特征推断
/// - `backup_hosts`  : 备用域名列表（主域名不可达时切换）
/// - `timeout`       : 请求超时（秒）
/// - `user_agent`    : 自定义 User-Agent
/// - `cookie`        : 自定义 Cookie
/// - `headers`       : 额外请求头（JSON 二维数组，如 `[["K","V"],...]`）
#[derive(Debug, Clone, Default, Deserialize)]
pub struct SiteExt {
    #[serde(default)]
    pub host: String,
    #[serde(default)]
    pub module: String,
    #[serde(default)]
    pub backup_hosts: Vec<String>,
    #[serde(default)]
    pub timeout: u64,
    #[serde(default)]
    pub user_agent: String,
    #[serde(default)]
    pub cookie: String,
    #[serde(default)]
    pub headers: Vec<(String, String)>,
}

impl SiteExt {
    /// 解析壳子传来的 ext JSON 字符串
    pub fn parse(ext_json: &str) -> Result<Self, String> {
        let trimmed = ext_json.trim();
        if trimmed.is_empty() {
            return Ok(Self::default());
        }
        serde_json::from_str(trimmed).map_err(|e| format!("ext JSON 解析失败: {}", e))
    }

    /// 解析实际生效的站点模块名：
    /// 1. `ext.module` 非空 → 直接采用（小写化）；
    /// 2. 否则按 host 特征推断（如 host 含 4kvms/4kvm → 4kvm）；
    /// 3. 都推不出 → 空串（调用方报错提示）。
    pub fn resolve_module(&self) -> String {
        if !self.module.trim().is_empty() {
            return self.module.trim().to_lowercase();
        }
        let host = self.host.to_lowercase();
        if host.contains("4kvms") || host.contains("4kvm") {
            return "4kvm".to_string();
        }
        String::new()
    }
}

/// 统一入口：首页内容
pub fn home_content(ext: &SiteExt) -> Result<String, String> {
    match ext.resolve_module().as_str() {
        "4kvm" => {
            let s = fourkvm::Spider4KVM::new(ext);
            serde_json::to_string(&s.home_content()).map_err(|e| e.to_string())
        }
        "" => Err("ext 缺少 host，且无法按 host 识别站点模块".to_string()),
        m => Err(format!("引擎暂无该站点的 Rust 实现: module={}", m)),
    }
}

/// 统一入口：分类内容
pub fn category_content(ext: &SiteExt, tid: &str, pg: u32) -> Result<String, String> {
    match ext.resolve_module().as_str() {
        "4kvm" => {
            let s = fourkvm::Spider4KVM::new(ext);
            serde_json::to_string(&s.category_content(tid, pg)).map_err(|e| e.to_string())
        }
        "" => Err("ext 缺少 host，且无法按 host 识别站点模块".to_string()),
        m => Err(format!("引擎暂无该站点的 Rust 实现: module={}", m)),
    }
}

/// 统一入口：详情内容
pub fn detail_content(ext: &SiteExt, id: &str) -> Result<String, String> {
    match ext.resolve_module().as_str() {
        "4kvm" => {
            let s = fourkvm::Spider4KVM::new(ext);
            serde_json::to_string(&s.detail_content(id)).map_err(|e| e.to_string())
        }
        "" => Err("ext 缺少 host，且无法按 host 识别站点模块".to_string()),
        m => Err(format!("引擎暂无该站点的 Rust 实现: module={}", m)),
    }
}

/// 统一入口：搜索内容
pub fn search_content(ext: &SiteExt, key: &str, pg: u32) -> Result<String, String> {
    match ext.resolve_module().as_str() {
        "4kvm" => {
            let s = fourkvm::Spider4KVM::new(ext);
            serde_json::to_string(&s.search_content(key, pg)).map_err(|e| e.to_string())
        }
        "" => Err("ext 缺少 host，且无法按 host 识别站点模块".to_string()),
        m => Err(format!("引擎暂无该站点的 Rust 实现: module={}", m)),
    }
}

/// 统一入口：播放内容
pub fn player_content(ext: &SiteExt, id: &str) -> Result<String, String> {
    match ext.resolve_module().as_str() {
        "4kvm" => {
            let s = fourkvm::Spider4KVM::new(ext);
            serde_json::to_string(&s.player_content(id)).map_err(|e| e.to_string())
        }
        "" => Err("ext 缺少 host，且无法按 host 识别站点模块".to_string()),
        m => Err(format!("引擎暂无该站点的 Rust 实现: module={}", m)),
    }
}

/// 统一入口：预热（建连 + 初始化站点 WASM 签名引擎）
pub fn warm_up(ext: &SiteExt) -> Result<String, String> {
    match ext.resolve_module().as_str() {
        "4kvm" => {
            let _ = fourkvm::Spider4KVM::new(ext);
            Ok("ok".to_string())
        }
        "" => Err("ext 缺少 host，且无法按 host 识别站点模块".to_string()),
        m => Err(format!("引擎暂无该站点的 Rust 实现: module={}", m)),
    }
}

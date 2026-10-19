//! ============================================================================
//! Rust 站点模板 — 复制本文件开始写你自己的站点
//! ============================================================================
//!
//! 使用方法（三步）：
//! 1. 把本文件复制为 `rust-spider/src/spider/mysite.rs`（名字随意，下同），
//!    全局替换 `MySite` 为你的站点名；
//! 2. 在 `rust-spider/src/spider/mod.rs` 注册：
//!      - 顶部加 `pub mod mysite;`
//!      - home_content / category_content / detail_content / search_content /
//!        player_content 五个函数的 match 里各加一个分支：
//!          "mysite" => { let s = mysite::MySite::new(ext);
//!                        serde_json::to_string(&s.home_content()).map_err(|e| e.to_string()) }
//!    （五个函数的分支写法完全一致，只是调用方法不同）
//! 3. 编译：
//!      - Android .bin：./build-rust.sh
//!        产物 target/<abi>/release/spider_rust_bin
//!        复制到壳子 app/src/main/assets/rust/spider_rust.<abi>.bin
//!        （abi 取 arm64-v8a / armeabi-v7a）
//!      - 电脑上测试：cargo build --release
//!        产物 target/release/spider_rust_bin，直接命令行跑：
//!        ./target/release/spider_rust_bin home '{"host":"https://你的站点/"}'
//!
//! 站点配置（壳子侧，无需改壳子任何代码）：
//! {
//!   "key": "mysite",
//!   "name": "我的站点 • Rust二进制",
//!   "type": 3,
//!   "api": "./rust/spider_rust.bin",
//!   "searchable": 1,
//!   "ext": {"host": "https://www.example.com/"}
//! }
//!
//! 返回数据结构见 src/types.rs；HTTP 请求工具见 fourkvm.rs（reqwest 用法）。
//! ============================================================================

use super::SiteExt;
use crate::types::*;
use once_cell::sync::Lazy;
use reqwest::header::{HeaderMap, HeaderValue, USER_AGENT};
use scraper::{Html, Selector};
use std::time::Duration;

/// 全局 Tokio 运行时（照抄即可）
static RUNTIME: Lazy<tokio::runtime::Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("Tokio 运行时创建失败")
});

pub struct MySite {
    host: String,
    client: reqwest::Client,
}

impl MySite {
    /// 构造：ext.host 为站点域名；UA/Cookie/超时等均可从 ext 读取（参考 fourkvm.rs）
    pub fn new(ext: &SiteExt) -> Self {
        let host = normalize_host(if ext.host.is_empty() { "https://www.example.com/" } else { &ext.host });
        let mut headers = HeaderMap::new();
        let ua = if ext.user_agent.is_empty() {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36"
        } else {
            &ext.user_agent
        };
        headers.insert(USER_AGENT, HeaderValue::from_str(ua).unwrap());
        let client = reqwest::Client::builder()
            .default_headers(headers)
            .cookie_store(true)
            .gzip(true)
            .timeout(Duration::from_secs(if ext.timeout > 0 { ext.timeout } else { 15 }))
            .build()
            .unwrap_or_default();
        Self { host, client }
    }

    // ---------- 网络辅助 ----------

    /// 同步 GET 请求，返回 HTML 文本
    fn fetch_text(&self, url: &str) -> Result<String, String> {
        let client = self.client.clone();
        let url = url.to_string();
        RUNTIME.block_on(async move {
            let resp = client.get(&url).send().await.map_err(|e| e.to_string())?;
            if !resp.status().is_success() {
                return Err(format!("HTTP {}", resp.status()));
            }
            resp.text().await.map_err(|e| e.to_string())
        })
    }

    /// 相对路径 → 绝对 URL
    fn abs_url(&self, u: &str) -> String {
        let u = u.trim();
        if u.starts_with("http") {
            u.to_string()
        } else if u.starts_with("//") {
            format!("https:{}", u)
        } else if u.starts_with('/') {
            format!("{}{}", self.host.trim_end_matches('/'), u)
        } else {
            format!("{}/{}", self.host.trim_end_matches('/'), u)
        }
    }

    // ---------- 五个协议入口（壳子会依次调用） ----------

    /// 首页：分类 + 推荐列表
    pub fn home_content(&self) -> HomeResult {
        // TODO: 改成你站点的真实分类
        let class = vec![
            VodClass { type_name: "电影".into(), type_id: self.abs_url("movie") },
            VodClass { type_name: "电视剧".into(), type_id: self.abs_url("tv") },
        ];

        // TODO: 解析首页推荐列表；没有推荐可返回空列表
        let list = match self.fetch_text(&self.host) {
            Ok(html) => self.parse_list(&html),
            Err(e) => {
                eprintln!("[MySite] 首页请求失败: {}", e);
                Vec::new()
            }
        };
        HomeResult { class, list }
    }

    /// 分类列表页：tid 是你在 home_content 里下发的 type_id，pg 是页码（从 1 开始）
    pub fn category_content(&self, tid: &str, pg: u32) -> CategoryResult {
        // 页码拼 URL 的方式按站点实际规则改（?page=N / /page/N/ 等）
        let url = if pg <= 1 { tid.to_string() } else { format!("{}?page={}", tid, pg) };
        let list = match self.fetch_text(&url) {
            Ok(html) => self.parse_list(&html),
            Err(e) => {
                eprintln!("[MySite] 分类请求失败: {}", e);
                Vec::new()
            }
        };
        // pagecount 给个大数，壳子按"下一页是否为空"处理；limit 是每页条数提示
        CategoryResult { list, page: pg, pagecount: 9999, limit: list.len().max(1) as u32, total: 999999 }
    }

    /// 详情页：id 是列表项的 vod_id；返回选集串 "第1集$播放id#第2集$播放id"
    pub fn detail_content(&self, id: &str) -> DetailResult {
        let url = self.abs_url(id);
        let (vod_name, vod_pic, play_url) = match self.fetch_text(&url) {
            Ok(html) => {
                let doc = Html::parse_document(&html);
                let name = doc.select(&Selector::parse("h1").unwrap())
                    .next()
                    .map(|e| e.text().collect::<String>().trim().to_string())
                    .unwrap_or_else(|| "未知标题".into());
                // TODO: 海报选择器
                let pic = doc.select(&Selector::parse("img").unwrap())
                    .next()
                    .and_then(|e| e.value().attr("src").or_else(|| e.value().attr("data-src")))
                    .map(|u| self.abs_url(u))
                    .unwrap_or_default();
                // TODO: 选集选择器，拼 "第N集$集id"
                let mut episodes = Vec::new();
                if let Ok(sel) = Selector::parse("a[href*='/play/']") {
                    for (i, a) in doc.select(&sel).enumerate() {
                        let href = a.value().attr("href").unwrap_or("");
                        if href.is_empty() { continue; }
                        episodes.push(format!("第{}集${}", i + 1, self.abs_url(href)));
                    }
                }
                (name, pic, episodes.join("#"))
            }
            Err(e) => {
                eprintln!("[MySite] 详情请求失败: {}", e);
                ("播放".into(), String::new(), format!("播放${}", url))
            }
        };
        DetailResult {
            list: vec![VideoDetail {
                vod_id: url,
                vod_name,
                vod_pic,
                vod_play_from: "Rust线路".into(),
                vod_play_url: play_url,
                ..Default::default()
            }],
        }
    }

    /// 搜索：key 是关键字，pg 页码
    pub fn search_content(&self, key: &str, pg: u32) -> CategoryResult {
        let url = format!("{}search?q={}&page={}", self.host, urlencoding(key), pg.max(1));
        let list = match self.fetch_text(&url) {
            Ok(html) => self.parse_list(&html),
            Err(e) => {
                eprintln!("[MySite] 搜索请求失败: {}", e);
                Vec::new()
            }
        };
        let total = list.len() as u32;
        CategoryResult { list, page: pg, pagecount: 1, limit: 30, total }
    }

    /// 播放：id 是详情页下发的选集 id（$ 后面部分）
    /// 直链：PlayResult { parse: 0, url: "https://....m3u8", header: ... }
    /// 嗅探：PlayResult { parse: 1, url: "网页地址", header: ... }（壳子用 WebView 抓包）
    pub fn player_content(&self, id: &str) -> PlayResult {
        let page_url = self.abs_url(id);
        // TODO: 改成你站点的取播放地址逻辑。以下是"进播放页抓第一个 video/m3u8"的通用示例
        match self.fetch_text(&page_url) {
            Ok(html) => {
                // 常见写法 1：页面里直接有 m3u8/mp4 地址
                for marker in [".m3u8", ".mp4"] {
                    if let Some(idx) = html.find(marker) {
                        // 向前找最近的引号
                        let head = &html[..idx];
                        let start = head.rfind('"').or_else(|| head.rfind('\'')).map(|i| i + 1).unwrap_or(0);
                        let url = html[start..idx + marker.len()].to_string();
                        if url.starts_with("http") || url.starts_with('/') {
                            return PlayResult {
                                parse: 0,
                                url: self.abs_url(&url),
                                header: Some(self.play_headers()),
                            };
                        }
                    }
                }
                // 抓不到直链 → 交给壳子嗅探
                PlayResult { parse: 1, url: page_url, header: Some(self.play_headers()) }
            }
            Err(_) => PlayResult { parse: 1, url: page_url, header: Some(self.play_headers()) },
        }
    }

    // ---------- 列表解析（首页/分类/搜索共用，改成你站点的选择器） ----------

    /// TODO: 改成你站点的列表项选择器
    fn parse_list(&self, html: &str) -> Vec<VideoItem> {
        let doc = Html::parse_document(html);
        let mut videos = Vec::new();
        // 示例：所有指向详情页的链接（按站点改选择器）
        if let Ok(a_sel) = Selector::parse("a[href*='/play/']") {
            if let Ok(img_sel) = Selector::parse("img") {
                let mut seen = std::collections::HashSet::new();
                for a in doc.select(&a_sel) {
                    let href = match a.value().attr("href") {
                        Some(h) if !h.is_empty() => h,
                        _ => continue,
                    };
                    if !seen.insert(href.to_string()) { continue; }
                    let img = a.select(&img_sel).next();
                    let name = img.and_then(|i| i.value().attr("alt"))
                        .filter(|s| !s.is_empty())
                        .unwrap_or("未知标题")
                        .to_string();
                    let pic = img.and_then(|i| i.value().attr("data-src").or_else(|| i.value().attr("src")))
                        .unwrap_or("")
                        .to_string();
                    videos.push(VideoItem {
                        vod_id: self.abs_url(href),
                        vod_name: name,
                        vod_pic: self.abs_url(&pic),
                        vod_remarks: String::new(),
                        vod_year: String::new(),
                    });
                    if videos.len() >= 30 { break; }
                }
            }
        }
        videos
    }

    fn play_headers(&self) -> std::collections::HashMap<String, String> {
        let mut m = std::collections::HashMap::new();
        m.insert("User-Agent".into(), "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36".into());
        m.insert("Referer".into(), self.host.clone());
        m
    }
}

// ---------- 通用小工具 ----------

fn normalize_host(h: &str) -> String {
    let mut h = h.trim().to_string();
    if h.is_empty() { return h; }
    if !h.contains("://") { h = format!("https://{}", h); }
    if !h.ends_with('/') { h.push('/'); }
    h
}

/// 简易 URL 编码
fn urlencoding(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

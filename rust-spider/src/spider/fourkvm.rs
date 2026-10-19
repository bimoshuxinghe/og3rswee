//! 4KVM（4k影视）爬虫核心 — 由 4KVM.py 移植为 Rust
//!
//! 页面结构（2026-10 实测）：
//! - 首页   : `.swiper-slide` 轮播 + `.movie-card` 列表
//! - 分类页 : `.movie-card`（img alt=标题, data-src=海报, span.absolute.bottom-0=备注）
//! - 搜索页 : `.group.relative`（img alt=标题, p.line-clamp-2=简介）
//! - 详情页 : `a[data-episode]`（href=播放页, dataid=选集ID）
//! - 播放   : 站点 WASM `build_play_url(dataid, secret, quality, play_key)`
//!            → `/video/play?...` → JSON → `quality_urls[].url`（m3u8 直链）
//!
//! 本模块经 `super::SiteExt` 配置化：UA / Cookie / 超时 / 备用域名 / 额外头均可由 ext 指定。

use super::SiteExt;
use crate::types::*;
use crate::wasm_engine;
use once_cell::sync::Lazy;
use regex::Regex;
use reqwest::header::{HeaderMap, HeaderValue, COOKIE, USER_AGENT};
use scraper::{Html, Selector};
use std::collections::HashMap;
use std::time::Duration;

/// 默认清晰度（非 VIP 可用）
const DEFAULT_QUALITY: &str = "1080";

/// 默认 User-Agent（ext.user_agent 可覆盖）
const DEFAULT_UA: &str = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0";

/// 站点备用 OSS 域名（源自播放页 `window._pdf`，主域名失败时切换；ext.backup_hosts 可覆盖）
const DEFAULT_BACKUP_OSS_HOSTS: [&str; 2] = ["oss.douyinbit.com", "myoss.douyinbit.top"];

/// 初始 Cookie（与 Python 版 init 一致；ext.cookie 可覆盖）
const DEFAULT_COOKIE: &str = "_ga=GA1.1.1153221558.1775407756; _ga_4GN328S9FG=GS2.1.s1775407755$o1$g1$t1775407774$j41$l0$h0";

/// 全局 Tokio 运行时
static RUNTIME: Lazy<tokio::runtime::Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("Tokio 运行时创建失败")
});

/// 4KVM 爬虫（经 SiteExt 配置化）
#[derive(Clone)]
pub struct Spider4KVM {
    host: String,
    client: reqwest::Client,
    user_agent: String,
    backup_hosts: Vec<String>,
}

impl Spider4KVM {
    /// 创建爬虫实例。`ext.host` 形如 `https://www.4kvms.org/`（可带尾斜杠）；
    /// UA/Cookie/超时/备用域名/额外头缺省时用内置默认值。
    pub fn new(ext: &SiteExt) -> Self {
        let host = normalize_host(&ext.host);
        let user_agent = if ext.user_agent.trim().is_empty() {
            DEFAULT_UA.to_string()
        } else {
            ext.user_agent.trim().to_string()
        };
        let cookie = if ext.cookie.trim().is_empty() {
            DEFAULT_COOKIE.to_string()
        } else {
            ext.cookie.trim().to_string()
        };
        let backup_hosts: Vec<String> = if ext.backup_hosts.is_empty() {
            DEFAULT_BACKUP_OSS_HOSTS.iter().map(|s| s.to_string()).collect()
        } else {
            ext.backup_hosts.clone()
        };

        let mut headers = HeaderMap::new();
        headers.insert(USER_AGENT, HeaderValue::from_str(&user_agent).unwrap_or_else(|_| HeaderValue::from_static(DEFAULT_UA)));
        headers.insert("Accept", HeaderValue::from_static(
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        ));
        headers.insert("Accept-Language", HeaderValue::from_static("zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6"));
        headers.insert("Cache-Control", HeaderValue::from_static("max-age=0"));
        headers.insert("Sec-Fetch-Dest", HeaderValue::from_static("document"));
        headers.insert("Sec-Fetch-Mode", HeaderValue::from_static("navigate"));
        headers.insert("Sec-Fetch-Site", HeaderValue::from_static("same-origin"));
        headers.insert("Sec-Fetch-User", HeaderValue::from_static("?1"));
        headers.insert("Upgrade-Insecure-Requests", HeaderValue::from_static("1"));
        // 初始 Cookie
        headers.insert(COOKIE, HeaderValue::from_str(&cookie).unwrap_or_else(|_| HeaderValue::from_static(DEFAULT_COOKIE)));
        // ext.headers 额外头（覆盖同名默认头）
        for (k, v) in &ext.headers {
            if let (Ok(name), Ok(value)) = (
                reqwest::header::HeaderName::from_bytes(k.trim().as_bytes()),
                HeaderValue::from_str(v.trim()),
            ) {
                headers.insert(name, value);
            }
        }

        let connect_timeout = Duration::from_secs(10);
        let total_timeout = Duration::from_secs(if ext.timeout > 0 { ext.timeout } else { 15 });

        let client = reqwest::Client::builder()
            .default_headers(headers)
            .cookie_store(true)
            .gzip(true)
            .brotli(true)
            .connect_timeout(connect_timeout)
            .timeout(total_timeout)
            .build()
            .unwrap_or_default();

        Self { host, client, user_agent, backup_hosts }
    }

    // ---------- 首页 ----------

    /// 首页内容（分类 + 推荐列表）
    pub fn home_content(&self) -> HomeResult {
        let classes = vec![
            VodClass { type_name: "电影".into(), type_id: format!("{}movie", self.host) },
            VodClass { type_name: "电视剧".into(), type_id: format!("{}tv", self.host) },
            VodClass { type_name: "动漫".into(), type_id: format!("{}anime", self.host) },
        ];
        let list = match self.fetch_text(&self.host) {
            Ok(html) => {
                let doc = Html::parse_document(&html);
                let mut videos = parse_slides(&doc, self);
                if videos.is_empty() {
                    videos = parse_movie_cards(&doc, self);
                }
                videos
            }
            Err(e) => {
                eprintln!("[4KVM] 首页错误: {}", e);
                Vec::new()
            }
        };
        HomeResult { class: classes, list }
    }

    // ---------- 分类 ----------

    /// 分类内容
    pub fn category_content(&self, tid: &str, pg: u32) -> CategoryResult {
        let base = if tid.starts_with("http") {
            tid.to_string()
        } else {
            format!("{}{}", self.host, tid.trim_start_matches('/'))
        };
        let url = if pg <= 1 {
            base.clone()
        } else if base.contains('?') {
            format!("{}&page={}", base, pg)
        } else {
            format!("{}?page={}", base, pg)
        };

        match self.fetch_text(&url) {
            Ok(html) => {
                let doc = Html::parse_document(&html);
                let list = parse_movie_cards(&doc, self);
                let pagecount = if list.len() >= 20 { 9999 } else { pg.max(1) };
                CategoryResult { list, page: pg, pagecount, limit: 30, total: 999999 }
            }
            Err(e) => {
                eprintln!("[4KVM] 分类错误: {}", e);
                CategoryResult { list: Vec::new(), page: pg, pagecount: 1, limit: 30, total: 0 }
            }
        }
    }

    // ---------- 详情 ----------

    /// 详情内容（含选集线路）
    pub fn detail_content(&self, id: &str) -> DetailResult {
        let url = self.normalize_url(id);
        // 与 Python 版一致：非播放页 ID 直接构造单集 VOD
        if !url.contains("/play/") {
            return DetailResult { list: vec![single_vod(&url)] };
        }
        let html = match self.fetch_text(&url) {
            Ok(h) => h,
            Err(e) => {
                eprintln!("[4KVM] 详情页错误: {}", e);
                return DetailResult { list: vec![single_vod(&url)] };
            }
        };
        let doc = Html::parse_document(&html);

        // 标题：h1 → h2 → title
        let mut title = text_of(&doc, "h1");
        if title.is_empty() {
            title = text_of(&doc, "h2");
        }
        if title.is_empty() {
            title = text_of(&doc, "title")
                .replace(" - 4k影视", "")
                .replace(" - 4K影视", "");
        }
        if title.is_empty() {
            title = "未知视频".into();
        }

        // 海报
        let pic = attr_of(&doc, "img", "src")
            .or_else(|| attr_of(&doc, "img", "data-src"))
            .map(|u| self.normalize_url(&u))
            .unwrap_or_default();

        // 选集：a[data-episode]（含 dataid，供播放签名使用）
        let play_links = parse_episodes(&doc, &html, &self);

        let remarks = format!("共{}集", play_links.len());
        DetailResult {
            list: vec![VideoDetail {
                vod_id: url.clone(),
                vod_name: title,
                vod_pic: pic,
                vod_remarks: remarks,
                vod_play_from: "星河皓月".into(),
                vod_play_url: play_links.join("#"),
                ..Default::default()
            }],
        }
    }

    // ---------- 搜索 ----------

    /// 搜索内容
    pub fn search_content(&self, key: &str, pg: u32) -> CategoryResult {
        let mut url = format!("{}search?q={}", self.host, urlencoding(key));
        if pg > 1 {
            url.push_str(&format!("&page={}", pg));
        }
        match self.fetch_text(&url) {
            Ok(html) => {
                let doc = Html::parse_document(&html);
                let mut list = parse_search_items(&doc, &self);
                if list.is_empty() {
                    list = parse_movie_cards(&doc, self);
                }
                let total = list.len() as u32;
                CategoryResult { list, page: pg, pagecount: 1, limit: 30, total }
            }
            Err(e) => {
                eprintln!("[4KVM] 搜索错误: {}", e);
                CategoryResult { list: Vec::new(), page: pg, pagecount: 1, limit: 30, total: 0 }
            }
        }
    }

    // ---------- 播放 ----------

    /// 播放内容：解析播放地址（m3u8 直链）
    ///
    /// 解析路线（与 Python 版行为对齐，并保留 Rust WASM 签名快路径）：
    /// 1. `id` 本身是媒体直链（.m3u8/.mp4/.flv/.ts）→ parse 0；
    /// 2. `id` 形如 `dataid=40779&url=/play/xxx&key=...`（详情页下发的选集）→ WASM 签名直取；
    /// 3. 否则回站访问播放页：
    ///    a. 提取 dataid + userlink 走 WASM 签名 API（优先，可解析页面内嵌播放器）；
    ///    b. 页面无签名信息时按 Python 版直提：iframe / video source / script 内联正则；
    ///    c. 都失败 → parse 1 交给 WebView 嗅探。
    pub fn player_content(&self, id: &str) -> PlayResult {
        // 1) id 本身即媒体直链（Python: is_direct 判断）
        if is_direct_media(id) {
            return PlayResult { parse: 0, url: id.to_string(), header: Some(self.header_map()) };
        }

        // 2) 优先从 id 中解析 dataid + secret（详情页下发的快路径选集）
        let (dataid, secret) = parse_play_id(id);
        let fast_key = parse_play_key(id);

        // 回站时拿到的页面 HTML（供 WASM 失败后走 Python 直提兜底）
        let mut page_html: Option<String> = None;

        let (dataid, secret, play_key) = if !dataid.is_empty() {
            (dataid, secret, fast_key.unwrap_or_default())
        } else {
            // 回站提取：访问播放页拿 userlink 与当前集 dataid
            let page_url = self.normalize_url(id);
            match self.fetch_text(&page_url) {
                Ok(html) => {
                    let doc = Html::parse_document(&html);
                    let (did, sec) = extract_first_episode(&doc);
                    if did.is_empty() {
                        // 页面无选集签名信息 → Python 版直提路线
                        if let Some(r) = extract_direct_media(&doc, &page_url, self) {
                            return r;
                        }
                        return PlayResult { parse: 1, url: page_url, header: Some(self.header_map()) };
                    }
                    let key = extract_userlink(&html);
                    page_html = Some(html);
                    (did, sec, key)
                }
                Err(e) => {
                    eprintln!("[4KVM] 播放页错误: {}", e);
                    return PlayResult { parse: 1, url: page_url, header: Some(self.header_map()) };
                }
            }
        };

        // 3) WASM 生成签名 API 路径
        let api_path = match wasm_engine::build_play_url(&dataid, &secret, DEFAULT_QUALITY, &play_key) {
            Ok(p) => p,
            Err(e) => {
                eprintln!("[4KVM] WASM 签名失败: {}", e);
                return self.play_fallback(id, page_html.as_deref());
            }
        };
        let api_url = format!("{}{}", self.host.trim_end_matches('/'), api_path);
        let referer = format!("{}play/{}", self.host, secret);

        // 4) 请求播放数据
        let resp: PlayApiResponse = match RUNTIME.block_on(self.fetch_json(&api_url, &referer)) {
            Ok(r) => r,
            Err(e) => {
                eprintln!("[4KVM] 播放 API 错误: {}", e);
                return self.play_fallback(id, page_html.as_deref());
            }
        };

        // 5) 选择可用线路（跳过 VIP 锁定）
        let data = match resp.data {
            Some(d) if resp.code == 200 => d,
            _ => {
                eprintln!("[4KVM] 播放 API 返回异常: code={} msg={}", resp.code, resp.message);
                return self.play_fallback(id, page_html.as_deref());
            }
        };
        let quality = data
            .quality_urls
            .iter()
            .find(|q| !q.locked && !q.url.is_empty() && q.url != "1")
            .or_else(|| data.quality_urls.iter().find(|q| !q.url.is_empty() && q.url != "1"));

        match quality {
            Some(q) => {
                let url = if q.url.starts_with("http") {
                    q.url.clone()
                } else {
                    format!("{}{}", self.host, q.url)
                };
                // 备用域名容错：主 OSS 域名不可达时切换
                let url = self.fallback_oss_url(&url);
                PlayResult { parse: 0, url, header: Some(self.header_map()) }
            }
            None => self.play_fallback(id, page_html.as_deref()),
        }
    }

    /// WASM 签名路线失败后的兜底：有页面 HTML 时走 Python 版直提，否则 parse 1 交 WebView
    fn play_fallback(&self, id: &str, page_html: Option<&str>) -> PlayResult {
        if let Some(html) = page_html {
            let doc = Html::parse_document(html);
            let page_url = self.normalize_url(id);
            if let Some(r) = extract_direct_media(&doc, &page_url, self) {
                return r;
            }
            return PlayResult { parse: 1, url: page_url, header: Some(self.header_map()) };
        }
        PlayResult { parse: 1, url: self.normalize_url(id), header: Some(self.header_map()) }
    }

    // ---------- 网络 ----------

    async fn fetch_json(&self, url: &str, referer: &str) -> Result<PlayApiResponse, String> {
        let resp = self
            .client
            .get(url)
            .header("Referer", referer)
            .header("Accept", "application/json")
            .send()
            .await
            .map_err(|e| format!("请求失败: {}", e))?;
        let status = resp.status();
        if !status.is_success() {
            return Err(format!("HTTP {}", status));
        }
        resp.json::<PlayApiResponse>()
            .await
            .map_err(|e| format!("JSON 解析失败: {}", e))
    }

    /// 同步阻塞请求 HTML（供 JNI 层调用）
    fn fetch_text(&self, url: &str) -> Result<String, String> {
        let client = self.client.clone();
        let url = url.to_string();
        RUNTIME.block_on(async move {
            let resp = client
                .get(&url)
                .send()
                .await
                .map_err(|e| format!("请求失败: {}", e))?;
            let status = resp.status();
            if !status.is_success() {
                return Err(format!("HTTP {}", status));
            }
            resp.text().await.map_err(|e| format!("读取失败: {}", e))
        })
    }

    /// URL 归一化（相对路径 → 绝对地址）
    pub fn normalize_url(&self, url: &str) -> String {
        let url = url.trim();
        if url.is_empty() {
            return String::new();
        }
        if url.starts_with("//") {
            format!("https:{}", url)
        } else if url.starts_with('/') {
            format!("{}{}", self.host.trim_end_matches('/'), url)
        } else if url.starts_with("http") {
            url.to_string()
        } else {
            format!("{}/{}", self.host.trim_end_matches('/'), url)
        }
    }

    fn header_map(&self) -> HashMap<String, String> {
        let mut m = HashMap::new();
        m.insert("User-Agent".into(), self.user_agent.clone());
        m.insert("Referer".into(), format!("{}/", self.host));
        m
    }

    /// OSS 域名容错：主域名不可达时切换备用域名（HEAD 探测）
    fn fallback_oss_url(&self, url: &str) -> String {
        let client = self.client.clone();
        let url = url.to_string();
        let backup_hosts = self.backup_hosts.clone();
        RUNTIME.block_on(async move {
            let ok = client
                .head(&url)
                .send()
                .await
                .map(|r| r.status().is_success())
                .unwrap_or(false);
            if ok {
                return url;
            }
            // 解析当前主机
            if let Some(idx) = url.find("://") {
                let rest = &url[idx + 3..];
                if let Some(slash) = rest.find('/') {
                    let current_host = &rest[..slash];
                    if backup_hosts.iter().any(|h| h == current_host) {
                        for host in &backup_hosts {
                            if host == current_host {
                                continue;
                            }
                            let candidate = format!("https://{}{}", host, &rest[slash..]);
                            if client
                                .head(&candidate)
                                .send()
                                .await
                                .map(|r| r.status().is_success())
                                .unwrap_or(false)
                            {
                                return candidate;
                            }
                        }
                    }
                }
            }
            url
        })
    }
}

// ---------- 解析辅助 ----------

/// 解析首页轮播 `.swiper-slide`
fn parse_slides(doc: &Html, spider: &Spider4KVM) -> Vec<VideoItem> {
    let slide_sel = match Selector::parse(".swiper-slide") {
        Ok(s) => s,
        Err(_) => return Vec::new(),
    };
    let a_sel = Selector::parse("a[href^=\"/play/\"]").unwrap();
    let img_sel = Selector::parse("img").unwrap();
    let mut videos = Vec::new();
    for slide in doc.select(&slide_sel) {
        if let Some(a) = slide.select(&a_sel).next() {
            let href = a.value().attr("href").unwrap_or("");
            if href.is_empty() {
                continue;
            }
            let img = a.select(&img_sel).next();
            let title = img
                .and_then(|i| i.value().attr("alt"))
                .filter(|s| !s.is_empty())
                .or_else(|| slide.value().attr("data-title"))
                .unwrap_or("未知")
                .to_string();
            let img_src = img
                .and_then(|i| i.value().attr("data-src").or_else(|| i.value().attr("src")))
                .unwrap_or("");
            let rating = slide.value().attr("data-rating").unwrap_or("");
            let remarks = if rating.is_empty() {
                "推荐".to_string()
            } else {
                format!("评分:{}", rating)
            };
            videos.push(VideoItem {
                vod_id: spider.normalize_url(href),
                vod_name: title,
                vod_pic: spider.normalize_url(img_src),
                vod_remarks: remarks,
                vod_year: String::new(),
            });
        }
    }
    videos
}

/// 解析 `.movie-card` 列表（首页/分类页通用）
fn parse_movie_cards(doc: &Html, spider: &Spider4KVM) -> Vec<VideoItem> {
    let card_sel = match Selector::parse(".movie-card") {
        Ok(s) => s,
        Err(_) => return Vec::new(),
    };
    let a_sel = Selector::parse("a[href^=\"/play/\"]").unwrap();
    let img_sel = Selector::parse("img").unwrap();
    let remark_sel = Selector::parse("span.absolute.bottom-0").unwrap();
    let rating_sel = Selector::parse(".text-green-500, .text-yellow-400").unwrap();
    let mut videos = Vec::new();
    for card in doc.select(&card_sel) {
        if let Some(a) = card.select(&a_sel).next() {
            let href = a.value().attr("href").unwrap_or("");
            if href.is_empty() {
                continue;
            }
            let img = a.select(&img_sel).next();
            let title = img
                .and_then(|i| i.value().attr("alt"))
                .filter(|s| !s.is_empty())
                .unwrap_or("未知标题")
                .to_string();
            let img_src = img
                .and_then(|i| i.value().attr("data-src").or_else(|| i.value().attr("src")))
                .unwrap_or("");
            // 备注：更新至X集 / 评分
            let mut remarks = text_of_sel(card, &remark_sel);
            let rating = text_of_sel(card, &rating_sel);
            if !rating.is_empty() {
                if remarks.is_empty() {
                    remarks = format!("评分:{}", rating);
                } else {
                    remarks = format!("{} | 评分:{}", remarks, rating);
                }
            }
            videos.push(VideoItem {
                vod_id: spider.normalize_url(href),
                vod_name: title,
                vod_pic: spider.normalize_url(img_src),
                vod_remarks: remarks,
                vod_year: String::new(),
            });
        }
    }

    // 备用选择器链（Python 版 getVideoList：.video-item/.movie-item/.post-item/article/.item）
    if videos.is_empty() {
        let a_play_sel = Selector::parse("a[href^=\"/play/\"]").unwrap();
        let a_any_sel = Selector::parse("a").unwrap();
        let img_sel = Selector::parse("img").unwrap();
        for sel_str in [".video-item", ".movie-item", ".post-item", "article", ".item"] {
            let item_sel = match Selector::parse(sel_str) {
                Ok(s) => s,
                Err(_) => continue,
            };
            let items: Vec<_> = doc.select(&item_sel).collect();
            if items.is_empty() {
                continue;
            }
            eprintln!("[4KVM] 使用备用选择器: {}, {} 项", sel_str, items.len());
            for item in items {
                // py: 先找 a[href^="/play/"]，找不到则任意 a
                let a = item
                    .select(&a_play_sel)
                    .next()
                    .or_else(|| item.select(&a_any_sel).next());
                let Some(a) = a else { continue };
                let href = a.value().attr("href").unwrap_or("");
                if href.is_empty() {
                    continue;
                }
                let img = a.select(&img_sel).next();
                let title = img
                    .and_then(|i| i.value().attr("alt"))
                    .filter(|s| !s.is_empty())
                    .unwrap_or("未知")
                    .to_string();
                let img_src = img
                    .and_then(|i| i.value().attr("data-src").or_else(|| i.value().attr("src")))
                    .unwrap_or("");
                videos.push(VideoItem {
                    vod_id: spider.normalize_url(href),
                    vod_name: title,
                    vod_pic: spider.normalize_url(img_src),
                    vod_remarks: String::new(),
                    vod_year: String::new(),
                });
            }
            if !videos.is_empty() {
                break;
            }
        }
    }
    videos
}

/// 解析搜索页 `.group.relative`
fn parse_search_items(doc: &Html, spider: &Spider4KVM) -> Vec<VideoItem> {
    let item_sel = match Selector::parse(".group.relative") {
        Ok(s) => s,
        Err(_) => return Vec::new(),
    };
    let a_sel = Selector::parse("a[href^=\"/play/\"]").unwrap();
    let img_sel = Selector::parse("img").unwrap();
    let desc_sel = Selector::parse("p.line-clamp-2").unwrap();
    let mut videos = Vec::new();
    for item in doc.select(&item_sel) {
        if let Some(a) = item.select(&a_sel).next() {
            let href = a.value().attr("href").unwrap_or("");
            if href.is_empty() {
                continue;
            }
            let img = a.select(&img_sel).next();
            let title = img
                .and_then(|i| i.value().attr("alt"))
                .filter(|s| !s.is_empty())
                .unwrap_or("未知标题")
                .to_string();
            let img_src = img
                .and_then(|i| i.value().attr("data-src").or_else(|| i.value().attr("src")))
                .unwrap_or("");
            let desc = text_of_sel(a, &desc_sel);
            let remarks: String = desc.chars().take(20).collect();
            videos.push(VideoItem {
                vod_id: spider.normalize_url(href),
                vod_name: title,
                vod_pic: spider.normalize_url(img_src),
                vod_remarks: remarks,
                vod_year: String::new(),
            });
        }
    }
    videos
}

/// 解析详情页选集。
/// 选择器顺序（与 Python 版对齐，并保留 Rust data-episode 路线）：
/// 1. `a.episode-link[href^="/play/"]`（Python 主路线）
/// 2. `a[data-episode][href^="/play/"]`（Rust 路线）
/// 3. 兜底：`a[href^="/play/"]`（去重，标题固定「播放」）
/// 下发格式：`第N集$...`；若选集元素带 dataid 且页面可提取 userlink，
/// 附带签名快路径参数（`dataid=..&url=..&key=..`），否则只下发播放页 URL（Python 格式）。
fn parse_episodes(doc: &Html, html: &str, spider: &Spider4KVM) -> Vec<String> {
    let span_sel = Selector::parse("span").unwrap();
    // 页面级 userlink（WASM 签名密钥），整个详情页相同
    let play_key = extract_userlink(html);
    let mut links: Vec<String> = Vec::new();
    let mut seen = std::collections::HashSet::new();

    // 选择器 1/2：带集数的选集按钮
    let ep_sels = [
        Selector::parse("a.episode-link[href^=\"/play/\"]").ok(),
        Selector::parse("a[data-episode][href^=\"/play/\"]").ok(),
    ];
    'outer: for ep_sel in ep_sels.iter().flatten() {
        for ep in doc.select(ep_sel) {
            let href = ep.value().attr("href").unwrap_or("");
            if href.is_empty() {
                continue;
            }
            let key = href.to_string();
            if !seen.insert(key.clone()) {
                continue;
            }
            // 集数：data-episode 属性 → span 文本 → 序号
            let episode_num = ep
                .value()
                .attr("data-episode")
                .filter(|s| !s.is_empty())
                .map(|s| s.to_string())
                .or_else(|| {
                    ep.select(&span_sel)
                        .next()
                        .map(|s| s.text().collect::<String>().trim().to_string())
                })
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| (links.len() + 1).to_string());
            let dataid = ep.value().attr("dataid").unwrap_or("").to_string();
            let play_url = spider.normalize_url(href);
            if !dataid.is_empty() && !play_key.is_empty() {
                // Rust 快路径：第N集$dataid=xxx&url=/play/yyy&key=userlink
                links.push(format!(
                    "第{}集$dataid={}&url={}&key={}",
                    episode_num, dataid, play_url, play_key
                ));
            } else {
                // Python 格式：第N集$https://.../play/yyy
                links.push(format!("第{}集${}", episode_num, play_url));
            }
        }
        if !links.is_empty() {
            break 'outer;
        }
    }

    // 选择器 3：兜底任意 /play/ 链接（去重）
    if links.is_empty() {
        let a_sel = Selector::parse("a[href^=\"/play/\"]").unwrap();
        let mut seen2 = std::collections::HashSet::new();
        for a in doc.select(&a_sel) {
            let href = a.value().attr("href").unwrap_or("");
            if href.is_empty() {
                continue;
            }
            if seen2.insert(href.to_string()) {
                links.push(format!("播放${}", spider.normalize_url(href)));
            }
        }
    }
    links
}

/// 从播放页提取 userlink（导航栏内联变量 `userlink:'xxx'`）
fn extract_userlink(html: &str) -> String {
    if let Some(idx) = html.find("userlink") {
        let rest = &html[idx..];
        let bytes = rest.as_bytes();
        let mut i = 0;
        // 跳过 userlink 与冒号
        while i < bytes.len() && bytes[i] != b':' {
            i += 1;
        }
        i += 1;
        while i < bytes.len() && (bytes[i] == b' ' || bytes[i] == b'\'' || bytes[i] == b'"') {
            i += 1;
        }
        let start = i;
        while i < bytes.len() && bytes[i] != b'\'' && bytes[i] != b'"' {
            i += 1;
        }
        if i > start {
            return rest[start..i].to_string();
        }
    }
    String::new()
}

/// 提取首个选集（回站兜底用）
fn extract_first_episode(doc: &Html) -> (String, String) {
    let ep_sel = match Selector::parse("a[data-episode]") {
        Ok(s) => s,
        Err(_) => return (String::new(), String::new()),
    };
    if let Some(ep) = doc.select(&ep_sel).next() {
        let dataid = ep.value().attr("dataid").unwrap_or("").to_string();
        let href = ep.value().attr("href").unwrap_or("");
        let secret = href.rsplit('/').next().unwrap_or("").to_string();
        if !dataid.is_empty() && !secret.is_empty() {
            return (dataid, secret);
        }
    }
    (String::new(), String::new())
}

/// 解析 playerContent 的 id：
/// - `dataid=40779&url=/play/ch4phdr82` → ("40779", "ch4phdr82")
/// - `/play/ch4phdr82` / 完整 URL → ("", "ch4phdr82")
fn parse_play_id(id: &str) -> (String, String) {
    let mut dataid = String::new();
    let mut secret = String::new();
    for part in id.split('&') {
        if let Some(v) = part.strip_prefix("dataid=") {
            dataid = v.to_string();
        } else if let Some(v) = part.strip_prefix("url=") {
            secret = v.rsplit('/').next().unwrap_or("").to_string();
        }
    }
    if secret.is_empty() && id.contains("/play/") {
        secret = id.rsplit('/').next().unwrap_or("").to_string();
    }
    (dataid, secret)
}

/// 从 id 中解析 play_key（`key=xxx` 参数）
fn parse_play_key(id: &str) -> Option<String> {
    for part in id.split('&') {
        if let Some(v) = part.strip_prefix("key=") {
            if !v.is_empty() {
                return Some(v.to_string());
            }
        }
    }
    None
}

/// 单集 VOD 兜底
fn single_vod(url: &str) -> VideoDetail {
    VideoDetail {
        vod_id: url.to_string(),
        vod_name: "播放".into(),
        vod_play_from: "星河皓月".into(),
        vod_play_url: format!("播放${}", url),
        ..Default::default()
    }
}

/// host 归一化：确保带协议与尾斜杠
fn normalize_host(host: &str) -> String {
    let mut h = host.trim().to_string();
    if h.is_empty() {
        h = "https://www.4kvms.org/".to_string();
    }
    if !h.contains("://") {
        h = format!("https://{}", h);
    }
    if !h.ends_with('/') {
        h.push('/');
    }
    h
}

/// 简易 URL 编码（搜索关键字）
fn urlencoding(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

/// 取元素文本
fn text_of(doc: &Html, sel: &str) -> String {
    match Selector::parse(sel) {
        Ok(s) => doc
            .select(&s)
            .next()
            .map(|e| e.text().collect::<String>().trim().to_string())
            .unwrap_or_default(),
        Err(_) => String::new(),
    }
}

/// 取元素属性
fn attr_of(doc: &Html, sel: &str, attr: &str) -> Option<String> {
    match Selector::parse(sel) {
        Ok(s) => doc
            .select(&s)
            .next()
            .and_then(|e| e.value().attr(attr))
            .map(|s| s.to_string()),
        Err(_) => None,
    }
}

/// 取子元素文本
fn text_of_sel(parent: scraper::ElementRef<'_>, sel: &Selector) -> String {
    parent
        .select(sel)
        .next()
        .map(|e| e.text().collect::<String>().trim().to_string())
        .unwrap_or_default()
}

/// 是否媒体直链（Python: `.m3u8/.mp4/.flv/.ts` 子串判断）
fn is_direct_media(url: &str) -> bool {
    let lower = url.to_lowercase();
    [".m3u8", ".mp4", ".flv", ".ts"].iter().any(|e| lower.contains(e))
}

/// Python 版播放直提路线：iframe → video source → script 内联正则。
/// iframe 非直链时返回 parse 1 + iframe 地址（交 WebView 嗅探 iframe 页），与 py 行为一致。
fn extract_direct_media(doc: &Html, page_url: &str, spider: &Spider4KVM) -> Option<PlayResult> {
    let _ = page_url;
    // 1) iframe
    if let Some(iframe) = attr_of(doc, "iframe", "src") {
        if iframe.is_empty() {
            return None;
        }
        let iframe = spider.normalize_url(&iframe);
        return Some(if is_direct_media(&iframe) {
            PlayResult { parse: 0, url: iframe, header: Some(spider.header_map()) }
        } else {
            PlayResult { parse: 1, url: iframe, header: Some(spider.header_map()) }
        });
    }
    // 2) video source / video src
    let video = attr_of(doc, "video source", "src").or_else(|| attr_of(doc, "video", "src"));
    if let Some(v) = video {
        if !v.is_empty() {
            return Some(PlayResult {
                parse: 0,
                url: spider.normalize_url(&v),
                header: Some(spider.header_map()),
            });
        }
    }
    // 3) script 内联正则：https?://...m3u8|mp4|flv（取第一个）
    static MEDIA_RE: Lazy<Regex> = Lazy::new(|| {
        Regex::new(r#"https?://[^"'\s<>]+\.(?:m3u8|mp4|flv)"#).expect("媒体 URL 正则编译失败")
    });
    let script_sel = Selector::parse("script").ok()?;
    let scripts_text: String = doc
        .select(&script_sel)
        .map(|s| s.text().collect::<String>())
        .collect::<Vec<_>>()
        .join("\n");
    if let Some(m) = MEDIA_RE.find(&scripts_text) {
        return Some(PlayResult {
            parse: 0,
            url: m.as_str().to_string(),
            header: Some(spider.header_map()),
        });
    }
    None
}

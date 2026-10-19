//! 4KVM 站点 WASM 模块 —— “Rust 版 py”：纯逻辑，零 IO。
//!
//! 编译：`cargo build --release --target wasm32-unknown-unknown`
//! 产物：`target/wasm32-unknown-unknown/release/site_4kvm.wasm`（预计 < 300KB）
//!
//! 逻辑与 4KVM.py / src/spider/fourkvm.rs 完全对齐；所有 IO（网络/HTML 解析/正则/签名）
//! 均通过 host imports 由壳子内置引擎提供，本模块只写解析流程。
//!
//! 协议（宿主侧见 src/wasm_site/mod.rs）：
//! - 导出：alloc / home / category / detail / search / player / result_ptr / result_len
//! - 导入（模块 "host"）：host_fetch / host_query / host_regex / host_sign_play / host_log

use serde_json::{json, Value};
use std::alloc::Layout;

// ---------------------------------------------------------------------------
// host imports（壳子内置引擎提供）
// ---------------------------------------------------------------------------

#[link(wasm_import_module = "host")]
extern "C" {
    fn host_fetch(in_ptr: i32, in_len: i32, out_ptr: i32, out_cap: i32) -> i32;
    fn host_query(in_ptr: i32, in_len: i32, out_ptr: i32, out_cap: i32) -> i32;
    fn host_regex(in_ptr: i32, in_len: i32, out_ptr: i32, out_cap: i32) -> i32;
    fn host_sign_play(in_ptr: i32, in_len: i32, out_ptr: i32, out_cap: i32) -> i32;
    fn host_log(ptr: i32, len: i32);
}

// ---------------------------------------------------------------------------
// 内存导出（宿主读写站点线性内存的通道）
// ---------------------------------------------------------------------------

/// 分配内存供宿主写入输入
#[no_mangle]
pub extern "C" fn alloc(len: i32) -> i32 {
    if len <= 0 {
        return 0;
    }
    unsafe {
        let layout = Layout::from_size_align_unchecked(len as usize, 1);
        std::alloc::alloc(layout) as i32
    }
}

/// 结果缓冲（站点执行完毕后宿主读取）
static mut RESULT: Vec<u8> = Vec::new();

#[no_mangle]
pub extern "C" fn result_ptr() -> i32 {
    unsafe { RESULT.as_ptr() as i32 }
}

#[no_mangle]
pub extern "C" fn result_len() -> i32 {
    unsafe { RESULT.len() as i32 }
}

fn set_result(v: &Value) -> i32 {
    let s = serde_json::to_string(v).unwrap_or_default();
    unsafe {
        RESULT = s.into_bytes();
    }
    0
}

// ---------------------------------------------------------------------------
// host 调用封装
// ---------------------------------------------------------------------------

/// 通用 host 调用：请求 JSON 入，响应字符串出
fn host_call(
    f: unsafe extern "C" fn(i32, i32, i32, i32) -> i32,
    req: &str,
    cap: usize,
) -> Result<String, String> {
    let mut buf = vec![0u8; cap];
    let ret = unsafe {
        f(
            req.as_ptr() as i32,
            req.len() as i32,
            buf.as_mut_ptr() as i32,
            buf.len() as i32,
        )
    };
    if ret < 0 {
        return Err("host 调用失败".to_string());
    }
    let n = (ret as usize).min(buf.len());
    String::from_utf8(buf[..n].to_vec()).map_err(|e| e.to_string())
}

/// HTTP 请求（GET，可带额外头）
fn fetch(url: &str, headers: &Value) -> Result<String, String> {
    let req = json!({"url": url, "headers": headers}).to_string();
    host_call(host_fetch, &req, 8 * 1024 * 1024)
}

/// CSS 选择器查询 → 元素 JSON 数组 [{text, html, attrs}]
fn query(html: &str, selector: &str) -> Result<Vec<Value>, String> {
    let req = json!({"html": html, "selector": selector}).to_string();
    let s = host_call(host_query, &req, 16 * 1024 * 1024)?;
    serde_json::from_str(&s).map_err(|e| e.to_string())
}

/// 正则匹配 → [{m: 全匹配, g: [捕获组]}]
fn regex_all(text: &str, pattern: &str) -> Result<Vec<Value>, String> {
    let req = json!({"text": text, "pattern": pattern}).to_string();
    let s = host_call(host_regex, &req, 1024 * 1024)?;
    serde_json::from_str(&s).map_err(|e| e.to_string())
}

/// 播放地址签名（壳子内置 WASM 签名引擎）
fn sign_play(dataid: &str, secret: &str, quality: &str, play_key: &str) -> Result<String, String> {
    let req = json!({
        "dataid": dataid,
        "secret": secret,
        "quality": quality,
        "play_key": play_key,
    })
    .to_string();
    host_call(host_sign_play, &req, 64 * 1024)
}

/// 日志
fn log(msg: &str) {
    unsafe {
        host_log(msg.as_ptr() as i32, msg.len() as i32);
    }
}

// ---------------------------------------------------------------------------
// 输入解析
// ---------------------------------------------------------------------------

/// 站点上下文（来自宿主输入 JSON 的 ext）
struct Ctx {
    host: String,
}

fn input_value(ptr: i32, len: i32) -> Value {
    if ptr <= 0 || len <= 0 {
        return Value::Null;
    }
    let bytes = unsafe { std::slice::from_raw_parts(ptr as *const u8, len as usize) };
    serde_json::from_slice(bytes).unwrap_or(Value::Null)
}

fn ctx_of(input: &Value) -> Ctx {
    let host = input["ext"]["host"]
        .as_str()
        .unwrap_or("")
        .trim()
        .trim_end_matches('/')
        .to_string();
    Ctx { host }
}

// ---------------------------------------------------------------------------
// 工具函数（与 fourkvm.rs 对齐）
// ---------------------------------------------------------------------------

fn normalize_url(host: &str, url: &str) -> String {
    let url = url.trim();
    if url.is_empty() {
        return String::new();
    }
    if url.starts_with("//") {
        format!("https:{}", url)
    } else if url.starts_with('/') {
        format!("{}{}", host, url)
    } else if url.starts_with("http") {
        url.to_string()
    } else {
        format!("{}/{}", host, url.trim_start_matches('/'))
    }
}

/// 简易 URL 编码（搜索关键字）
fn urlencode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => out.push(b as char),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

/// 是否媒体直链（.m3u8/.mp4/.flv/.ts）
fn is_direct_media(url: &str) -> bool {
    let lower = url.to_lowercase();
    [".m3u8", ".mp4", ".flv", ".ts"].iter().any(|e| lower.contains(e))
}

/// 从播放页 HTML 提取 userlink（WASM 签名密钥）
fn extract_userlink(html: &str) -> String {
    let ms = match regex_all(html, r#"userlink[^a-zA-Z0-9]*[:=][^a-zA-Z0-9]*['"]([^'"]+)['"]"#) {
        Ok(m) => m,
        Err(_) => return String::new(),
    };
    ms.first()
        .and_then(|m| m["g"].as_array())
        .and_then(|g| g.first())
        .and_then(|v| v.as_str())
        .unwrap_or("")
        .to_string()
}

/// 解析 playerContent 的 id：`dataid=..&url=..&key=..` 或纯播放页 URL
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

/// 从 id 中解析 play_key（`key=xxx`）
fn parse_play_key(id: &str) -> String {
    for part in id.split('&') {
        if let Some(v) = part.strip_prefix("key=") {
            if !v.is_empty() {
                return v.to_string();
            }
        }
    }
    String::new()
}

// ---------------------------------------------------------------------------
// 元素 JSON 辅助
// ---------------------------------------------------------------------------

fn attr(el: &Value, name: &str) -> String {
    el["attrs"][name].as_str().unwrap_or("").to_string()
}

fn text(el: &Value) -> String {
    el["text"].as_str().unwrap_or("").trim().to_string()
}

fn inner(el: &Value) -> String {
    el["html"].as_str().unwrap_or("").to_string()
}

fn first(items: Vec<Value>) -> Option<Value> {
    items.into_iter().next()
}

// ---------------------------------------------------------------------------
// 列表解析
// ---------------------------------------------------------------------------

/// 首页轮播 `.swiper-slide`（img alt 标题，src 优先）
fn parse_slides(ctx: &Ctx, html: &str) -> Result<Vec<Value>, String> {
    let slides = query(html, ".swiper-slide")?;
    let mut out = Vec::new();
    for slide in &slides {
        let a = match first(query(inner(slide).as_str(), "a[href^=\"/play/\"]")?) {
            Some(a) => a,
            None => continue,
        };
        let href = attr(&a, "href");
        if href.is_empty() {
            continue;
        }
        let img = first(query(inner(&a).as_str(), "img")?);
        let title = img
            .as_ref()
            .map(|i| attr(i, "alt"))
            .filter(|s| !s.is_empty())
            .or_else(|| {
                let t = attr(slide, "data-title");
                if t.is_empty() {
                    None
                } else {
                    Some(t)
                }
            })
            .unwrap_or_else(|| "未知".to_string());
        let img_src = img
            .as_ref()
            .map(|i| {
                let s = attr(i, "src");
                if s.is_empty() {
                    attr(i, "data-src")
                } else {
                    s
                }
            })
            .unwrap_or_default();
        let rating = attr(slide, "data-rating");
        let remarks = if rating.is_empty() {
            "推荐".to_string()
        } else {
            format!("评分:{}", rating)
        };
        out.push(json!({
            "vod_id": normalize_url(&ctx.host, &href),
            "vod_name": title,
            "vod_pic": normalize_url(&ctx.host, &img_src),
            "vod_remarks": remarks,
            "vod_year": "",
        }));
    }
    Ok(out)
}

/// `.movie-card` 列表 + Python 版备用选择器链
fn parse_cards(ctx: &Ctx, html: &str) -> Result<Vec<Value>, String> {
    let cards = query(html, ".movie-card")?;
    let mut out = Vec::new();
    for card in &cards {
        let a = match first(query(inner(card).as_str(), "a[href^=\"/play/\"]")?) {
            Some(a) => a,
            None => continue,
        };
        let href = attr(&a, "href");
        if href.is_empty() {
            continue;
        }
        let img = first(query(inner(&a).as_str(), "img")?);
        let title = img
            .as_ref()
            .map(|i| attr(i, "alt"))
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "未知标题".to_string());
        let img_src = img
            .as_ref()
            .map(|i| {
                let s = attr(i, "data-src");
                if s.is_empty() {
                    attr(i, "src")
                } else {
                    s
                }
            })
            .unwrap_or_default();
        // 备注：更新至X集（span.absolute.bottom-0 → .text-yellow-500）
        let mut remarks = {
            let spans = query(inner(card).as_str(), "span.absolute.bottom-0")?;
            spans.first().map(text).unwrap_or_default()
        };
        if remarks.is_empty() {
            let spans = query(inner(card).as_str(), ".text-yellow-500")?;
            remarks = spans.first().map(text).unwrap_or_default();
        }
        // 评分：.text-green-500, .text-yellow-400
        let rating = {
            let els = query(inner(card).as_str(), ".text-green-500, .text-yellow-400")?;
            els.first().map(text).unwrap_or_default()
        };
        if !rating.is_empty() {
            if remarks.is_empty() {
                remarks = format!("评分:{}", rating);
            } else {
                remarks = format!("{} | 评分:{}", remarks, rating);
            }
        }
        out.push(json!({
            "vod_id": normalize_url(&ctx.host, &href),
            "vod_name": title,
            "vod_pic": normalize_url(&ctx.host, &img_src),
            "vod_remarks": remarks,
            "vod_year": "",
        }));
    }

    // 备用选择器链（Python 版 getVideoList）
    if out.is_empty() {
        for sel in [".video-item", ".movie-item", ".post-item", "article", ".item"] {
            let items = query(html, sel)?;
            if items.is_empty() {
                continue;
            }
            log(&format!("使用备用选择器: {}, {} 项", sel, items.len()));
            for item in &items {
                // 先找 a[href^="/play/"]，找不到则任意 a
                let a = match first(query(inner(item).as_str(), "a[href^=\"/play/\"]")?) {
                    Some(a) => a,
                    None => match first(query(inner(item).as_str(), "a")?) {
                        Some(a) => a,
                        None => continue,
                    },
                };
                let href = attr(&a, "href");
                if href.is_empty() {
                    continue;
                }
                let img = first(query(inner(&a).as_str(), "img")?);
                let title = img
                    .as_ref()
                    .map(|i| attr(i, "alt"))
                    .filter(|s| !s.is_empty())
                    .unwrap_or_else(|| "未知".to_string());
                let img_src = img
                    .as_ref()
                    .map(|i| {
                        let s = attr(i, "data-src");
                        if s.is_empty() {
                            attr(i, "src")
                        } else {
                            s
                        }
                    })
                    .unwrap_or_default();
                out.push(json!({
                    "vod_id": normalize_url(&ctx.host, &href),
                    "vod_name": title,
                    "vod_pic": normalize_url(&ctx.host, &img_src),
                    "vod_remarks": "",
                    "vod_year": "",
                }));
            }
            if !out.is_empty() {
                break;
            }
        }
    }
    Ok(out)
}

/// 搜索页 `.group.relative`（img alt 标题，p.line-clamp-2 简介截断 20 字）
fn parse_search_items(ctx: &Ctx, html: &str) -> Result<Vec<Value>, String> {
    let items = query(html, ".group.relative")?;
    let mut out = Vec::new();
    for item in &items {
        let a = match first(query(inner(item).as_str(), "a[href^=\"/play/\"]")?) {
            Some(a) => a,
            None => continue,
        };
        let href = attr(&a, "href");
        if href.is_empty() {
            continue;
        }
        let img = first(query(inner(&a).as_str(), "img")?);
        let title = img
            .as_ref()
            .map(|i| attr(i, "alt"))
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| "未知标题".to_string());
        let img_src = img
            .as_ref()
            .map(|i| {
                let s = attr(i, "data-src");
                if s.is_empty() {
                    attr(i, "src")
                } else {
                    s
                }
            })
            .unwrap_or_default();
        let desc = {
            let ps = query(inner(&a).as_str(), "p.line-clamp-2")?;
            ps.first().map(text).unwrap_or_default()
        };
        let remarks: String = desc.chars().take(20).collect();
        out.push(json!({
            "vod_id": normalize_url(&ctx.host, &href),
            "vod_name": title,
            "vod_pic": normalize_url(&ctx.host, &img_src),
            "vod_remarks": remarks,
            "vod_year": "",
        }));
    }
    Ok(out)
}

/// 详情页选集：`a.episode-link`（py 主路线）→ `a[data-episode]`（rust 路线）→ 兜底
fn parse_episodes(ctx: &Ctx, html: &str) -> Result<Vec<String>, String> {
    let mut links: Vec<String> = Vec::new();
    let mut seen: Vec<String> = Vec::new();
    let userlink = extract_userlink(html);

    for sel in [
        "a.episode-link[href^=\"/play/\"]",
        "a[data-episode][href^=\"/play/\"]",
    ] {
        let eps = query(html, sel)?;
        for ep in &eps {
            let href = attr(ep, "href");
            if href.is_empty() || seen.contains(&href) {
                continue;
            }
            seen.push(href.clone());
            // 集数：data-episode 属性 → span 文本 → 序号
            let episode_num = {
                let d = attr(ep, "data-episode");
                if !d.is_empty() {
                    d
                } else {
                    let spans = query(inner(ep).as_str(), "span")?;
                    let t = spans.first().map(text).unwrap_or_default();
                    if !t.is_empty() {
                        t
                    } else {
                        (links.len() + 1).to_string()
                    }
                }
            };
            let dataid = attr(ep, "dataid");
            let play_url = normalize_url(&ctx.host, &href);
            if !dataid.is_empty() && !userlink.is_empty() {
                links.push(format!(
                    "第{}集$dataid={}&url={}&key={}",
                    episode_num, dataid, play_url, userlink
                ));
            } else {
                links.push(format!("第{}集${}", episode_num, play_url));
            }
        }
        if !links.is_empty() {
            break;
        }
    }

    // 兜底：任意 /play/ 链接（去重）
    if links.is_empty() {
        let all = query(html, "a[href^=\"/play/\"]")?;
        for a in &all {
            let href = attr(a, "href");
            if href.is_empty() || seen.contains(&href) {
                continue;
            }
            seen.push(href.clone());
            links.push(format!("播放${}", normalize_url(&ctx.host, &href)));
        }
    }
    Ok(links)
}

/// Python 版播放直提：iframe → video source → script 内联正则
fn extract_direct_media(ctx: &Ctx, html: &str) -> Option<Value> {
    // iframe（非直链时 parse 1 交 WebView 嗅探，与 py 一致）
    if let Some(iframe) = first(query(html, "iframe").ok()?) {
        let src = attr(&iframe, "src");
        if !src.is_empty() {
            let u = normalize_url(&ctx.host, &src);
            return Some(if is_direct_media(&u) {
                json!({"parse": 0, "url": u})
            } else {
                json!({"parse": 1, "url": u})
            });
        }
    }
    // video source / video src
    for sel in ["video source", "video"] {
        if let Some(v) = first(query(html, sel).ok()?) {
            let src = attr(&v, "src");
            if !src.is_empty() {
                return Some(json!({"parse": 0, "url": normalize_url(&ctx.host, &src)}));
            }
        }
    }
    // script 内联正则：整个 HTML 上找 m3u8/mp4/flv
    let ms = regex_all(html, r#"https?://[^"'\s<>]+\.(?:m3u8|mp4|flv)"#).ok()?;
    if let Some(m) = ms.first() {
        let url = m["m"].as_str().unwrap_or("");
        if !url.is_empty() {
            return Some(json!({"parse": 0, "url": url}));
        }
    }
    None
}

// ---------------------------------------------------------------------------
// action 入口
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "C" fn home(in_ptr: i32, in_len: i32) -> i32 {
    let input = input_value(in_ptr, in_len);
    let ctx = ctx_of(&input);
    match home_impl(&ctx) {
        Ok(v) => set_result(&v),
        Err(e) => {
            log(&format!("首页错误: {}", e));
            set_result(&json!({"class": [], "list": []}))
        }
    }
}

fn home_impl(ctx: &Ctx) -> Result<Value, String> {
    let classes = json!([
        {"type_name": "电影", "type_id": format!("{}/movie", ctx.host)},
        {"type_name": "电视剧", "type_id": format!("{}/tv", ctx.host)},
        {"type_name": "动漫", "type_id": format!("{}/anime", ctx.host)},
    ]);
    let html = fetch(&format!("{}/", ctx.host), &json!({}))?;
    let mut list = parse_slides(ctx, &html)?;
    if list.is_empty() {
        list = parse_cards(ctx, &html)?;
    }
    Ok(json!({"class": classes, "list": list}))
}

#[no_mangle]
pub extern "C" fn category(in_ptr: i32, in_len: i32) -> i32 {
    let input = input_value(in_ptr, in_len);
    let ctx = ctx_of(&input);
    let tid = input["args"]["tid"].as_str().unwrap_or("").to_string();
    let pg = input["args"]["pg"].as_u64().unwrap_or(1).max(1) as u32;
    match category_impl(&ctx, &tid, pg) {
        Ok(v) => set_result(&v),
        Err(e) => {
            log(&format!("分类错误: {}", e));
            set_result(&json!({"list": [], "page": pg, "pagecount": 1, "limit": 30, "total": 0}))
        }
    }
}

fn category_impl(ctx: &Ctx, tid: &str, pg: u32) -> Result<Value, String> {
    let base = if tid.starts_with("http") {
        tid.to_string()
    } else {
        format!("{}/{}", ctx.host, tid.trim_start_matches('/'))
    };
    let url = if pg <= 1 {
        base
    } else if base.contains('?') {
        format!("{}&page={}", base, pg)
    } else {
        format!("{}?page={}", base, pg)
    };
    let html = fetch(&url, &json!({}))?;
    let list = parse_cards(ctx, &html)?;
    let pagecount = if list.len() >= 20 { 9999 } else { pg.max(1) };
    Ok(json!({
        "list": list,
        "page": pg,
        "pagecount": pagecount,
        "limit": 30,
        "total": 999999,
    }))
}

#[no_mangle]
pub extern "C" fn detail(in_ptr: i32, in_len: i32) -> i32 {
    let input = input_value(in_ptr, in_len);
    let ctx = ctx_of(&input);
    let id = input["args"]["id"].as_str().unwrap_or("").to_string();
    match detail_impl(&ctx, &id) {
        Ok(v) => set_result(&v),
        Err(e) => {
            log(&format!("详情页错误: {}", e));
            set_result(&json!({"list": []}))
        }
    }
}

fn detail_impl(ctx: &Ctx, id: &str) -> Result<Value, String> {
    let url = normalize_url(&ctx.host, id);
    // 非播放页 ID：单集 VOD（与 py 一致）
    if !url.contains("/play/") {
        return Ok(single_vod(&url));
    }
    let html = fetch(&url, &json!({}))?;

    // 标题：h1 → h2 → title（去站点后缀）
    let mut title = {
        let els = query(&html, "h1")?;
        els.first().map(text).unwrap_or_default()
    };
    if title.is_empty() {
        let els = query(&html, "h2")?;
        title = els.first().map(text).unwrap_or_default();
    }
    if title.is_empty() {
        let els = query(&html, "title")?;
        title = els
            .first()
            .map(text)
            .unwrap_or_default()
            .replace(" - 4k影视", "")
            .replace(" - 4K影视", "");
    }
    if title.is_empty() {
        title = "未知视频".to_string();
    }

    // 海报：img src → data-src
    let pic = {
        let imgs = query(&html, "img")?;
        let src = imgs.first().map(|i| attr(i, "src")).unwrap_or_default();
        if !src.is_empty() {
            src
        } else {
            imgs.first().map(|i| attr(i, "data-src")).unwrap_or_default()
        }
    };

    let play_links = parse_episodes(ctx, &html)?;
    Ok(json!({
        "list": [{
            "vod_id": url,
            "vod_name": title,
            "vod_pic": normalize_url(&ctx.host, &pic),
            "vod_content": "",
            "vod_year": "",
            "vod_area": "",
            "vod_remarks": format!("共{}集", play_links.len()),
            "vod_actor": "",
            "vod_director": "",
            "type_name": "",
            "vod_play_from": "星河皓月",
            "vod_play_url": play_links.join("#"),
        }]
    }))
}

fn single_vod(url: &str) -> Value {
    json!({
        "list": [{
            "vod_id": url,
            "vod_name": "播放",
            "vod_pic": "",
            "vod_content": "",
            "vod_year": "",
            "vod_area": "",
            "vod_remarks": "",
            "vod_actor": "",
            "vod_director": "",
            "type_name": "",
            "vod_play_from": "星河皓月",
            "vod_play_url": format!("播放${}", url),
        }]
    })
}

#[no_mangle]
pub extern "C" fn search(in_ptr: i32, in_len: i32) -> i32 {
    let input = input_value(in_ptr, in_len);
    let ctx = ctx_of(&input);
    let key = input["args"]["key"].as_str().unwrap_or("").to_string();
    let pg = input["args"]["pg"].as_u64().unwrap_or(1).max(1) as u32;
    match search_impl(&ctx, &key, pg) {
        Ok(v) => set_result(&v),
        Err(e) => {
            log(&format!("搜索错误: {}", e));
            set_result(&json!({"list": [], "page": pg}))
        }
    }
}

fn search_impl(ctx: &Ctx, key: &str, pg: u32) -> Result<Value, String> {
    let mut url = format!("{}/search?q={}", ctx.host, urlencode(key));
    if pg > 1 {
        url.push_str(&format!("&page={}", pg));
    }
    let html = fetch(&url, &json!({}))?;
    let mut list = parse_search_items(ctx, &html)?;
    if list.is_empty() {
        list = parse_cards(ctx, &html)?;
    }
    Ok(json!({"list": list, "page": pg}))
}

#[no_mangle]
pub extern "C" fn player(in_ptr: i32, in_len: i32) -> i32 {
    let input = input_value(in_ptr, in_len);
    let ctx = ctx_of(&input);
    let id = input["args"]["id"].as_str().unwrap_or("").to_string();
    match player_impl(&ctx, &id) {
        Ok(v) => set_result(&v),
        Err(e) => {
            log(&format!("播放器错误: {}", e));
            set_result(&json!({"parse": 1, "url": normalize_url(&ctx.host, &id)}))
        }
    }
}

/// 播放解析：直链 ID → dataid 快路径 → 回站签名（host）→ py 直提兜底
fn player_impl(ctx: &Ctx, id: &str) -> Result<Value, String> {
    // 1) id 本身即媒体直链
    if is_direct_media(id) {
        return Ok(json!({"parse": 0, "url": id}));
    }

    // 2) 详情页下发的快路径选集
    let (dataid, secret) = parse_play_id(id);
    let fast_key = parse_play_key(id);

    // 回站拿到的页面 HTML（签名失败后走 py 直提兜底）
    let mut page_html: Option<String> = None;

    let (dataid, secret, play_key) = if !dataid.is_empty() {
        (dataid, secret, fast_key)
    } else {
        let page_url = normalize_url(&ctx.host, id);
        let html = fetch(&page_url, &json!({}))?;
        let eps = query(&html, "a[data-episode]")?;
        let ep = eps.first();
        let did = ep.map(|e| attr(e, "dataid")).unwrap_or_default();
        if did.is_empty() {
            // 页面无签名信息 → py 直提路线
            if let Some(r) = extract_direct_media(ctx, &html) {
                return Ok(r);
            }
            return Ok(json!({"parse": 1, "url": page_url}));
        }
        let sec = ep
            .map(|e| {
                let href = attr(e, "href");
                href.rsplit('/').next().unwrap_or("").to_string()
            })
            .unwrap_or_default();
        let key = extract_userlink(&html);
        page_html = Some(html);
        (did, sec, key)
    };

    // 3) 签名 API 路径（壳子内置 WASM 签名引擎）
    let api_path = match sign_play(&dataid, &secret, "1080", &play_key) {
        Ok(p) => p,
        Err(e) => {
            log(&format!("签名失败: {}", e));
            return Ok(play_fallback(ctx, id, page_html.as_deref(), &secret));
        }
    };
    let api_url = format!("{}{}", ctx.host, api_path);
    let referer = format!("{}/play/{}", ctx.host, secret);

    // 4) 请求播放数据
    let resp_text = fetch(&api_url, &json!({"Referer": referer}))?;
    let resp: Value = serde_json::from_str(&resp_text).unwrap_or(Value::Null);

    // 5) 选择可用线路（跳过 VIP 锁定）
    let quality = resp["data"]["quality_urls"]
        .as_array()
        .and_then(|qs| {
            qs.iter()
                .find(|q| {
                    q["locked"].as_bool() != Some(true)
                        && !q["url"].as_str().unwrap_or("").is_empty()
                        && q["url"].as_str() != Some("1")
                })
                .or_else(|| {
                    qs.iter().find(|q| {
                        !q["url"].as_str().unwrap_or("").is_empty() && q["url"].as_str() != Some("1")
                    })
                })
        });

    match quality {
        Some(q) => {
            let mut url = q["url"].as_str().unwrap_or("").to_string();
            if !url.starts_with("http") {
                url = format!("{}{}", ctx.host, url);
            }
            Ok(json!({"parse": 0, "url": url}))
        }
        None => Ok(play_fallback(ctx, id, page_html.as_deref(), &secret)),
    }
}

/// 签名路线失败兜底：有页面 HTML 走 py 直提，否则 parse 1 交 WebView
fn play_fallback(ctx: &Ctx, id: &str, page_html: Option<&str>, secret: &str) -> Value {
    if let Some(html) = page_html {
        if let Some(r) = extract_direct_media(ctx, html) {
            return r;
        }
        return json!({"parse": 1, "url": format!("{}/play/{}", ctx.host, secret)});
    }
    json!({"parse": 1, "url": normalize_url(&ctx.host, id)})
}

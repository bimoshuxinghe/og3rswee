//! WASM 站点运行时 —— “Rust 版 py” 模式：外置站点 .wasm + 壳子内置全部依赖。
//!
//! # 架构
//!
//! ```text
//! 壳子（APK）
//!   └─ libspider_rust.so（本模块：wasmi 运行时 + 网络/HTML/正则能力）
//!        └─ 加载外置 4kvm.wasm（纯逻辑，无 IO，100~300KB）
//!             └─ 需要 IO 时回调 host import
//! ```
//!
//! # 站点 wasm 协议
//!
//! **导出**（站点提供）：
//! - `alloc(len: i32) -> i32`                      分配内存（供宿主写入输入）
//! - `home(in_ptr, in_len) -> i32`                 入口，返回 0 成功、非 0 失败
//! - `category(in_ptr, in_len) -> i32`
//! - `detail(in_ptr, in_len) -> i32`
//! - `search(in_ptr, in_len) -> i32`
//! - `player(in_ptr, in_len) -> i32`
//! - `result_ptr() -> i32` / `result_len() -> i32`  结果输出（站点内存中的 JSON）
//!
//! **导入**（宿主提供，模块名 `host`，统一签名 `(in_ptr, in_len, out_ptr, out_cap) -> i32`）：
//! - `host_fetch`     入参 `{"url":..,"headers":{..}}`，出参：响应体文本
//! - `host_query`     入参 `{"html":..,"selector":..}`，出参：匹配元素 JSON 数组
//!                   `[{"text":..,"html":..,"attrs":{"k":"v"}},...]`
//! - `host_regex`     入参 `{"text":..,"pattern":..}`，出参：`[{"m":全匹配,"g":[捕获组]},...]`
//! - `host_sign_play` 入参 `{"dataid":..,"secret":..,"quality":..,"play_key":..}`，
//!                   出参：签名后的播放 API 路径（壳子内置 WASM 签名引擎）
//! - `host_log(ptr, len)`                          日志（无返回）
//!
//! # 执行模型
//!
//! `Module` 编译一次；每次请求新建 `Store` 实例化（内存随 Store 回收），
//! 输入 JSON 经 `alloc` 写入站点内存，入口执行后经 `result_ptr/len` 读回结果。

use crate::spider::SiteExt;
use once_cell::sync::Lazy;
use scraper::Html;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use wasmi::{Caller, Config, Engine, Extern, Linker, Module, Store};

/// 已加载的外置站点 wasm 模块表：key = ext.module（或 host 推断），与内置分发层同一套 key 规则。
/// 支持多个 wasm 站点并存，互不影响；key 未注册时走内置站点分发。
static SITE_MODULES: Lazy<Mutex<HashMap<String, Arc<Module>>>> =
    Lazy::new(|| Mutex::new(HashMap::new()));

/// 全局 Tokio 运行时（host_fetch 同步阻塞用）
static RUNTIME: Lazy<tokio::runtime::Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("Tokio 运行时创建失败")
});

/// 宿主上下文（存 Store data，供 host import 闭包访问）
struct HostCtx {
    client: reqwest::Client,
}

/// 加载外置站点 wasm 字节（编译校验后按 key 缓存）。
/// key 一般取 `ext.module`（缺省时由 host 推断，见 SiteExt::resolve_module）
pub fn load_site_wasm(key: &str, bytes: &[u8]) -> Result<(), String> {
    let config = Config::default();
    let engine = Engine::new(&config);
    let module =
        Module::new(&engine, bytes).map_err(|e| format!("wasm 模块编译失败: {}", e))?;
    SITE_MODULES
        .lock()
        .unwrap()
        .insert(key.to_string(), Arc::new(module));
    Ok(())
}

/// 指定 key 是否已注册外置站点 wasm
pub fn has_site(key: &str) -> bool {
    SITE_MODULES.lock().unwrap().contains_key(key)
}

/// 卸载全部外置站点 wasm（回到内置分发模式）
pub fn clear_site() {
    SITE_MODULES.lock().unwrap().clear();
}

// ---------------------------------------------------------------------------
// 对外入口（与内置分发层同签名）
// ---------------------------------------------------------------------------

pub fn call_home(key: &str, ext: &SiteExt) -> Result<String, String> {
    call_action(key, "home", ext, json!({}))
}

pub fn call_category(key: &str, ext: &SiteExt, tid: &str, pg: u32) -> Result<String, String> {
    call_action(key, "category", ext, json!({"tid": tid, "pg": pg}))
}

pub fn call_detail(key: &str, ext: &SiteExt, id: &str) -> Result<String, String> {
    call_action(key, "detail", ext, json!({"id": id}))
}

pub fn call_search(key: &str, ext: &SiteExt, kw: &str, pg: u32) -> Result<String, String> {
    call_action(key, "search", ext, json!({"key": kw, "pg": pg}))
}

pub fn call_player(key: &str, ext: &SiteExt, id: &str) -> Result<String, String> {
    call_action(key, "player", ext, json!({"id": id}))
}

// ---------------------------------------------------------------------------
// 执行核心
// ---------------------------------------------------------------------------

/// 组装站点输入 JSON：ext 全量字段 + 本次请求参数
fn build_input(ext: &SiteExt, args: Value) -> Value {
    json!({
        "ext": {
            "host": ext.host,
            "module": ext.module,
            "backup_hosts": ext.backup_hosts,
            "timeout": ext.timeout,
            "user_agent": ext.user_agent,
            "cookie": ext.cookie,
            "headers": ext.headers,
        },
        "args": args,
    })
}

/// 执行站点 wasm 的一个 action，返回结果 JSON 字符串
fn call_action(key: &str, action: &str, ext: &SiteExt, args: Value) -> Result<String, String> {
    let module = {
        let guard = SITE_MODULES.lock().unwrap();
        guard
            .get(key)
            .cloned()
            .ok_or_else(|| format!("未加载外置站点 wasm: key={}", key))?
    };
    let input = build_input(ext, args);
    let input_bytes = serde_json::to_vec(&input).map_err(|e| e.to_string())?;

    // 每次请求独立 Store（内存随 Store drop 回收）
    let client = build_client(ext);
    let mut store = Store::new(module.engine(), HostCtx { client });
    let linker = build_linker(module.engine())?;
    let instance = linker
        .instantiate(&mut store, &module)
        .map_err(|e| format!("wasm 实例化失败: {}", e))?
        .start(&mut store)
        .map_err(|e| format!("wasm start 失败: {}", e))?;

    let memory = instance
        .get_memory(&store, "memory")
        .ok_or("站点 wasm 缺少 memory 导出")?;
    let alloc = instance
        .get_typed_func::<i32, i32>(&store, "alloc")
        .map_err(|e| format!("站点 wasm 缺少 alloc 导出: {}", e))?;
    let result_ptr = instance
        .get_typed_func::<(), i32>(&store, "result_ptr")
        .map_err(|e| format!("站点 wasm 缺少 result_ptr 导出: {}", e))?;
    let result_len = instance
        .get_typed_func::<(), i32>(&store, "result_len")
        .map_err(|e| format!("站点 wasm 缺少 result_len 导出: {}", e))?;
    let action_fn = instance
        .get_typed_func::<(i32, i32), i32>(&store, action)
        .map_err(|e| format!("站点 wasm 缺少 {} 导出: {}", action, e))?;

    // 输入经 alloc 写入站点内存
    let in_ptr = alloc
        .call(&mut store, input_bytes.len() as i32)
        .map_err(|e| format!("alloc 调用失败: {}", e))?;
    memory
        .write(&mut store, in_ptr as usize, &input_bytes)
        .map_err(|e| format!("写入站点内存失败: {}", e))?;

    // 执行入口
    let rc = action_fn
        .call(&mut store, (in_ptr, input_bytes.len() as i32))
        .map_err(|e| format!("站点 wasm 执行 trap: {}", e))?;
    if rc != 0 {
        return Err(format!("站点 wasm action={} 返回错误码 {}", action, rc));
    }

    // 读回结果
    let rptr = result_ptr
        .call(&mut store, ())
        .map_err(|e| format!("result_ptr 调用失败: {}", e))?;
    let rlen = result_len
        .call(&mut store, ())
        .map_err(|e| format!("result_len 调用失败: {}", e))?;
    if rlen < 0 {
        return Err("站点 wasm 结果长度为负".to_string());
    }
    let mut buf = vec![0u8; rlen as usize];
    memory
        .read(&store, rptr as usize, &mut buf)
        .map_err(|e| format!("读取站点内存失败: {}", e))?;
    String::from_utf8(buf).map_err(|e| format!("站点结果非 UTF-8: {}", e))
}

/// 构造 HTTP 客户端（与 fourkvm 同款默认头逻辑的简化版）
fn build_client(ext: &SiteExt) -> reqwest::Client {
    let ua = if ext.user_agent.trim().is_empty() {
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0"
    } else {
        ext.user_agent.trim()
    };
    let total_timeout = std::time::Duration::from_secs(if ext.timeout > 0 { ext.timeout } else { 15 });
    let mut headers = reqwest::header::HeaderMap::new();
    if let Ok(v) = reqwest::header::HeaderValue::from_str(ua) {
        headers.insert(reqwest::header::USER_AGENT, v);
    }
    if !ext.cookie.trim().is_empty() {
        if let Ok(v) = reqwest::header::HeaderValue::from_str(ext.cookie.trim()) {
            headers.insert(reqwest::header::COOKIE, v);
        }
    }
    for (k, v) in &ext.headers {
        if let (Ok(name), Ok(value)) = (
            reqwest::header::HeaderName::from_bytes(k.trim().as_bytes()),
            reqwest::header::HeaderValue::from_str(v.trim()),
        ) {
            headers.insert(name, value);
        }
    }
    reqwest::Client::builder()
        .default_headers(headers)
        .cookie_store(true)
        .gzip(true)
        .brotli(true)
        .connect_timeout(std::time::Duration::from_secs(10))
        .timeout(total_timeout)
        .build()
        .unwrap_or_default()
}

// ---------------------------------------------------------------------------
// host imports 注册
// ---------------------------------------------------------------------------

fn build_linker(engine: &Engine) -> Result<Linker<HostCtx>, String> {
    let mut linker = Linker::new(engine);

    // host_fetch：HTTP 请求，返回响应体文本
    linker
        .func_wrap(
            "host",
            "host_fetch",
            |mut caller: Caller<'_, HostCtx>, in_ptr: i32, in_len: i32, out_ptr: i32, out_cap: i32| -> i32 {
                let req_bytes = match read_guest(&mut caller, in_ptr, in_len) {
                    Some(b) => b,
                    None => return -1,
                };
                let req: Value = match serde_json::from_slice(&req_bytes) {
                    Ok(v) => v,
                    Err(_) => return -1,
                };
                let url = req.get("url").and_then(|v| v.as_str()).unwrap_or("").to_string();
                if url.is_empty() {
                    return -1;
                }
                let headers = req.get("headers").cloned().unwrap_or(json!({}));

                let client = caller.data().client.clone();
                let url_for_log = url.clone();
                let result = RUNTIME.block_on(async move {
                    let mut req = client.get(&url);
                    if let Some(obj) = headers.as_object() {
                        for (k, v) in obj {
                            if let Some(vs) = v.as_str() {
                                req = req.header(k.as_str(), vs);
                            }
                        }
                    }
                    let resp = req.send().await.map_err(|e| e.to_string())?;
                    if !resp.status().is_success() {
                        return Err(format!("HTTP {}", resp.status()));
                    }
                    resp.text().await.map_err(|e| e.to_string())
                });
                let text = match result {
                    Ok(t) => t,
                    Err(e) => {
                        eprintln!("[WasmSite] host_fetch 失败: {} ({})", url_for_log, e);
                        return -1;
                    }
                };
                write_guest(&mut caller, out_ptr, out_cap, text.as_bytes())
            },
        )
        .map_err(|e| format!("注册 host_fetch 失败: {}", e))?;

    // host_query：CSS 选择器查询，返回元素 JSON 数组
    linker
        .func_wrap(
            "host",
            "host_query",
            |mut caller: Caller<'_, HostCtx>, in_ptr: i32, in_len: i32, out_ptr: i32, out_cap: i32| -> i32 {
                let req_bytes = match read_guest(&mut caller, in_ptr, in_len) {
                    Some(b) => b,
                    None => return -1,
                };
                let req: Value = match serde_json::from_slice(&req_bytes) {
                    Ok(v) => v,
                    Err(_) => return -1,
                };
                let html = req.get("html").and_then(|v| v.as_str()).unwrap_or("");
                let selector = req.get("selector").and_then(|v| v.as_str()).unwrap_or("");
                if selector.is_empty() {
                    return -1;
                }
                let items = query_elements(html, selector);
                let json_str = serde_json::to_string(&items).unwrap_or_else(|_| "[]".to_string());
                write_guest(&mut caller, out_ptr, out_cap, json_str.as_bytes())
            },
        )
        .map_err(|e| format!("注册 host_query 失败: {}", e))?;

    // host_regex：正则匹配，返回 [{"m":全匹配,"g":[捕获组...]}] JSON 数组
    linker
        .func_wrap(
            "host",
            "host_regex",
            |mut caller: Caller<'_, HostCtx>, in_ptr: i32, in_len: i32, out_ptr: i32, out_cap: i32| -> i32 {
                let req_bytes = match read_guest(&mut caller, in_ptr, in_len) {
                    Some(b) => b,
                    None => return -1,
                };
                let req: Value = match serde_json::from_slice(&req_bytes) {
                    Ok(v) => v,
                    Err(_) => return -1,
                };
                let text = req.get("text").and_then(|v| v.as_str()).unwrap_or("");
                let pattern = req.get("pattern").and_then(|v| v.as_str()).unwrap_or("");
                let re = match regex::Regex::new(pattern) {
                    Ok(r) => r,
                    Err(_) => return -1,
                };
                let matches: Vec<Value> = re
                    .captures_iter(text)
                    .map(|c| {
                        let groups: Vec<String> = (1..c.len())
                            .filter_map(|i| c.get(i).map(|m| m.as_str().to_string()))
                            .collect();
                        json!({
                            "m": c.get(0).map(|m| m.as_str()).unwrap_or(""),
                            "g": groups,
                        })
                    })
                    .collect();
                let json_str = serde_json::to_string(&matches).unwrap_or_else(|_| "[]".to_string());
                write_guest(&mut caller, out_ptr, out_cap, json_str.as_bytes())
            },
        )
        .map_err(|e| format!("注册 host_regex 失败: {}", e))?;

    // host_sign_play：播放地址签名（壳子内置 WASM 签名引擎，站点零依赖）
    linker
        .func_wrap(
            "host",
            "host_sign_play",
            |mut caller: Caller<'_, HostCtx>, in_ptr: i32, in_len: i32, out_ptr: i32, out_cap: i32| -> i32 {
                let req_bytes = match read_guest(&mut caller, in_ptr, in_len) {
                    Some(b) => b,
                    None => return -1,
                };
                let req: Value = match serde_json::from_slice(&req_bytes) {
                    Ok(v) => v,
                    Err(_) => return -1,
                };
                let dataid = req.get("dataid").and_then(|v| v.as_str()).unwrap_or("");
                let secret = req.get("secret").and_then(|v| v.as_str()).unwrap_or("");
                let quality = req.get("quality").and_then(|v| v.as_str()).unwrap_or("1080");
                let play_key = req.get("play_key").and_then(|v| v.as_str()).unwrap_or("");
                match crate::wasm_engine::build_play_url(dataid, secret, quality, play_key) {
                    Ok(path) => write_guest(&mut caller, out_ptr, out_cap, path.as_bytes()),
                    Err(e) => {
                        eprintln!("[WasmSite] host_sign_play 失败: {}", e);
                        -1
                    }
                }
            },
        )
        .map_err(|e| format!("注册 host_sign_play 失败: {}", e))?;

    // host_log：日志
    linker
        .func_wrap(
            "host",
            "host_log",
            |mut caller: Caller<'_, HostCtx>, ptr: i32, len: i32| {
                if let Some(bytes) = read_guest(&mut caller, ptr, len) {
                    let msg = String::from_utf8_lossy(&bytes);
                    eprintln!("[WasmSite] {}", msg);
                }
            },
        )
        .map_err(|e| format!("注册 host_log 失败: {}", e))?;

    Ok(linker)
}

/// 从站点内存读取字节
fn read_guest(caller: &mut Caller<'_, HostCtx>, ptr: i32, len: i32) -> Option<Vec<u8>> {
    if ptr < 0 || len < 0 {
        return None;
    }
    let mem = match caller.get_export("memory") {
        Some(Extern::Memory(m)) => m,
        _ => return None,
    };
    let mut buf = vec![0u8; len as usize];
    mem.read(&*caller, ptr as usize, &mut buf).ok()?;
    Some(buf)
}

/// 向站点内存写入字节（截断到 out_cap），返回实际写入长度；失败返回 -1
fn write_guest(caller: &mut Caller<'_, HostCtx>, out_ptr: i32, out_cap: i32, data: &[u8]) -> i32 {
    if out_ptr < 0 || out_cap < 0 {
        return -1;
    }
    let n = data.len().min(out_cap as usize);
    let mem = match caller.get_export("memory") {
        Some(Extern::Memory(m)) => m,
        _ => return -1,
    };
    match mem.write(caller, out_ptr as usize, &data[..n]) {
        Ok(()) => n as i32,
        Err(_) => -1,
    }
}

/// CSS 查询 → 元素 JSON 数组（text / inner html / attrs）
fn query_elements(html: &str, selector: &str) -> Vec<Value> {
    let doc = Html::parse_document(html);
    let sel = match scraper::Selector::parse(selector) {
        Ok(s) => s,
        Err(_) => return Vec::new(),
    };
    doc.select(&sel)
        .map(|el| {
            let mut attrs = serde_json::Map::new();
            for (name, value) in el.value().attrs() {
                attrs.insert(name.to_string(), Value::String(value.to_string()));
            }
            json!({
                "text": el.text().collect::<String>().trim(),
                "html": el.inner_html(),
                "attrs": Value::Object(attrs),
            })
        })
        .collect()
}

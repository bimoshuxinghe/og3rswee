//! JNI 导出层：壳子 Java 侧 `com.fongmi.android.tv.api.loader.RustBridge` 调用入口。
//!
//! 每个 native 方法接收 `host`（站点域名）与 `ext`（站点配置 JSON），
//! 经分发层 `crate::spider` 按 `ext.module`（或 host 特征）路由到对应站点模块，
//! 返回 Spider 协议 JSON 字符串。
//!
//! 异常安全：所有入口都包裹 `catch_unwind`，Rust panic 不会拖垮 App，
//! 出错时返回空字符串（Java 侧按空结果处理）。

use crate::spider::{self, SiteExt};
use crate::wasm_site;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

/// 读取 Java 字符串参数
fn str_arg(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    let js = env
        .get_string(s)
        .map_err(|e| format!("读取字符串参数失败: {}", e))?;
    let rs = js
        .to_str()
        .map_err(|e| format!("UTF-8 转换失败: {}", e))?;
    Ok(rs.to_string())
}

/// 解析 (host, ext) 参数对为 SiteExt：
/// - ext JSON 合法时保留其全部字段（module/userAgent/cookie/headers 等均生效）；
/// - ext.host 为空时用 host 参数兜底；
/// - 两者都为空则报错（由调用方转为空串返回 Java 侧）。
fn parse_ext(host: &str, ext_json: &str) -> Result<SiteExt, String> {
    let mut ext = SiteExt::parse(ext_json)?;
    if ext.host.trim().is_empty() && !host.trim().is_empty() {
        ext.host = host.trim().to_string();
    }
    if ext.host.trim().is_empty() {
        return Err("ext.host 为空：请在站点配置 ext 中提供 host".to_string());
    }
    Ok(ext)
}

/// 把 Rust 结果转换为 Java 字符串（失败返回空串，不抛异常过 FFI）
fn ret_str(env: &mut JNIEnv, result: Result<String, String>) -> jstring {
    match result {
        Ok(v) => env
            .new_string(v)
            .map(|j| j.into_raw())
            .unwrap_or(std::ptr::null_mut()),
        Err(e) => {
            eprintln!("[RustBridge] {}", e);
            env.new_string("")
                .map(|j| j.into_raw())
                .unwrap_or(std::ptr::null_mut())
        }
    }
}

/// panic 安全执行体
fn safe<F: FnOnce() -> Result<String, String>>(f: F) -> Result<String, String> {
    std::panic::catch_unwind(std::panic::AssertUnwindSafe(f))
        .unwrap_or_else(|_| Err("Rust 内部 panic".to_string()))
}

/// 统一分派：key 已注册外置站点 wasm 时执行 wasm 站点，否则走内置站点分发层。
/// key 规则与内置分发一致：ext.module 优先，否则按 host 推断。
fn dispatch<F, G>(key: &str, wasm_fn: F, builtin_fn: G) -> Result<String, String>
where
    F: FnOnce() -> Result<String, String>,
    G: FnOnce() -> Result<String, String>,
{
    if wasm_site::has_site(key) {
        wasm_fn()
    } else {
        builtin_fn()
    }
}

/// 计算站点 key（module 优先，host 推断兜底，皆空时用 "default"）
fn site_key(ext: &SiteExt) -> String {
    let key = ext.resolve_module();
    if key.is_empty() {
        "default".to_string()
    } else {
        key
    }
}

/// 通用：读取 host + ext 两个字符串参数并构造 SiteExt
fn read_site(env: &mut JNIEnv, host: &JString, ext: &JString) -> Result<SiteExt, String> {
    let host = str_arg(env, host)?;
    let ext = str_arg(env, ext)?;
    parse_ext(&host, &ext)
}

// ---------------------------------------------------------------------------
// JNI 导出函数（与 Java 侧 RustBridge native 方法一一对应，签名见技术文档 5.1）
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativeHomeContent(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    ext: JString,
) -> jstring {
    let ext = match read_site(&mut env, &host, &ext) {
        Ok(e) => e,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let key = site_key(&ext);
    ret_str(&mut env, safe(|| dispatch(&key, || wasm_site::call_home(&key, &ext), || spider::home_content(&ext))))
}

#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativeCategoryContent(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    tid: JString,
    pg: jni::sys::jint,
    ext: JString,
) -> jstring {
    let ext = match read_site(&mut env, &host, &ext) {
        Ok(e) => e,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let tid = match str_arg(&mut env, &tid) {
        Ok(t) => t,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let key = site_key(&ext);
    ret_str(
        &mut env,
        safe(|| dispatch(&key, || wasm_site::call_category(&key, &ext, &tid, pg.max(1) as u32), || spider::category_content(&ext, &tid, pg.max(1) as u32))),
    )
}

#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativeDetailContent(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    id: JString,
    ext: JString,
) -> jstring {
    let ext = match read_site(&mut env, &host, &ext) {
        Ok(e) => e,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let id = match str_arg(&mut env, &id) {
        Ok(i) => i,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let key = site_key(&ext);
    ret_str(&mut env, safe(|| dispatch(&key, || wasm_site::call_detail(&key, &ext, &id), || spider::detail_content(&ext, &id))))
}

#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativeSearchContent(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    key: JString,
    pg: jni::sys::jint,
    ext: JString,
) -> jstring {
    let ext = match read_site(&mut env, &host, &ext) {
        Ok(e) => e,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let key = match str_arg(&mut env, &key) {
        Ok(k) => k,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let module_key = site_key(&ext);
    ret_str(
        &mut env,
        safe(|| dispatch(&module_key, || wasm_site::call_search(&module_key, &ext, &key, pg.max(1) as u32), || spider::search_content(&ext, &key, pg.max(1) as u32))),
    )
}

#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativePlayerContent(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    id: JString,
    ext: JString,
) -> jstring {
    let ext = match read_site(&mut env, &host, &ext) {
        Ok(e) => e,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let id = match str_arg(&mut env, &id) {
        Ok(i) => i,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let key = site_key(&ext);
    ret_str(&mut env, safe(|| dispatch(&key, || wasm_site::call_player(&key, &ext, &id), || spider::player_content(&ext, &id))))
}

/// 预热：建连 + 初始化站点 WASM 引擎（Java 侧 init 时调用）
#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativeWarmUp(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    ext: JString,
) -> jstring {
    let ext = match read_site(&mut env, &host, &ext) {
        Ok(e) => e,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let key = site_key(&ext);
    ret_str(&mut env, safe(|| dispatch(&key, || wasm_site::call_home(&key, &ext).map(|_| "ok".to_string()), || spider::warm_up(&ext))))
}

/// 加载外置站点 wasm（"Rust 版 py" 模式：壳子内置依赖，站点为纯逻辑 wasm 文件）。
/// 加载成功后，6 个内容入口自动分派到该 wasm 站点执行。
/// path 为 wasm 文件绝对路径；返回 "ok" 或错误信息。
#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativeLoadSiteWasm(
    mut env: JNIEnv,
    _class: JClass,
    ext: JString,
    path: JString,
) -> jstring {
    let result = (|| -> Result<String, String> {
        let ext_json = str_arg(&mut env, &ext)?;
        let site = SiteExt::parse(&ext_json).map_err(|e| format!("ext JSON 解析失败: {}", e))?;
        let key = site_key(&site);
        let path = str_arg(&mut env, &path)?;
        let bytes = std::fs::read(&path).map_err(|e| format!("读取 wasm 文件失败: {}", e))?;
        wasm_site::load_site_wasm(&key, &bytes)?;
        Ok("ok".to_string())
    })();
    ret_str(&mut env, result)
}

/// 卸载外置站点 wasm（回到内置站点分发模式）
#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativeClearSiteWasm(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    wasm_site::clear_site();
    ret_str(&mut env, Ok("ok".to_string()))
}

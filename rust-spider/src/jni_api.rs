//! JNI 导出层：壳子 Java 侧 `com.fongmi.android.tv.api.loader.RustBridge` 调用入口。
//!
//! 每个 native 方法接收 `host`（站点域名）与 `ext`（站点配置 JSON），
//! 经分发层 `crate::spider` 按 `ext.module`（或 host 特征）路由到对应站点模块，
//! 返回 Spider 协议 JSON 字符串。
//!
//! 异常安全：所有入口都包裹 `catch_unwind`，Rust panic 不会拖垮 App，
//! 出错时返回空字符串（Java 侧按空结果处理）。

use crate::spider::{self, SiteExt};
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
    ret_str(&mut env, safe(|| spider::home_content(&ext)))
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
    ret_str(
        &mut env,
        safe(|| spider::category_content(&ext, &tid, pg.max(1) as u32)),
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
    ret_str(&mut env, safe(|| spider::detail_content(&ext, &id)))
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
    ret_str(
        &mut env,
        safe(|| spider::search_content(&ext, &key, pg.max(1) as u32)),
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
    ret_str(&mut env, safe(|| spider::player_content(&ext, &id)))
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
    ret_str(&mut env, safe(|| spider::warm_up(&ext)))
}

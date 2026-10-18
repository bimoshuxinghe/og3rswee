//! JNI 导出层：壳子 Java 侧 `com.fongmi.android.tv.api.loader.RustBridge` 调用入口。
//!
//! 每个 native 方法接收 `host`（站点域名），内部按 host 缓存爬虫实例
//! （复用 HTTP 连接与 Cookie），返回 Spider 协议 JSON 字符串。
//!
//! 异常安全：所有入口都包裹 `catch_unwind`，Rust panic 不会拖垮 App，
//! 出错时返回空字符串（Java 侧按空结果处理）。

use crate::spider::Spider4KVM;
use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;
use once_cell::sync::Lazy;
use std::collections::HashMap;
use std::sync::Mutex;

/// 按 host 缓存的爬虫实例池
static SPIDERS: Lazy<Mutex<HashMap<String, Spider4KVM>>> = Lazy::new(|| Mutex::new(HashMap::new()));

/// 获取（或创建）指定 host 的爬虫实例
fn get_spider(host: &str) -> Spider4KVM {
    let mut map = SPIDERS.lock().unwrap_or_else(|e| e.into_inner());
    map.entry(host.to_string())
        .or_insert_with(|| Spider4KVM::new(host))
        .clone()
}

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

// ---------------------------------------------------------------------------
// JNI 导出函数（与 Java 侧 RustBridge native 方法一一对应）
// ---------------------------------------------------------------------------

#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativeHomeContent(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
) -> jstring {
    let host = match str_arg(&mut env, &host) {
        Ok(h) => h,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    ret_str(
        &mut env,
        safe(|| {
            let s = get_spider(&host);
            serde_json::to_string(&s.home_content()).map_err(|e| e.to_string())
        }),
    )
}

#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativeCategoryContent(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    tid: JString,
    pg: jni::sys::jint,
) -> jstring {
    let host = match str_arg(&mut env, &host) {
        Ok(h) => h,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let tid = match str_arg(&mut env, &tid) {
        Ok(h) => h,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    ret_str(
        &mut env,
        safe(|| {
            let s = get_spider(&host);
            serde_json::to_string(&s.category_content(&tid, pg.max(1) as u32))
                .map_err(|e| e.to_string())
        }),
    )
}

#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativeDetailContent(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    id: JString,
) -> jstring {
    let host = match str_arg(&mut env, &host) {
        Ok(h) => h,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let id = match str_arg(&mut env, &id) {
        Ok(h) => h,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    ret_str(
        &mut env,
        safe(|| {
            let s = get_spider(&host);
            serde_json::to_string(&s.detail_content(&id)).map_err(|e| e.to_string())
        }),
    )
}

#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativeSearchContent(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    key: JString,
    pg: jni::sys::jint,
) -> jstring {
    let host = match str_arg(&mut env, &host) {
        Ok(h) => h,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let key = match str_arg(&mut env, &key) {
        Ok(h) => h,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    ret_str(
        &mut env,
        safe(|| {
            let s = get_spider(&host);
            serde_json::to_string(&s.search_content(&key, pg.max(1) as u32))
                .map_err(|e| e.to_string())
        }),
    )
}

#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativePlayerContent(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
    id: JString,
) -> jstring {
    let host = match str_arg(&mut env, &host) {
        Ok(h) => h,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    let id = match str_arg(&mut env, &id) {
        Ok(h) => h,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    ret_str(
        &mut env,
        safe(|| {
            let s = get_spider(&host);
            serde_json::to_string(&s.player_content(&id)).map_err(|e| e.to_string())
        }),
    )
}

/// 预热：建连 + 初始化 WASM 引擎（Java 侧 init 时调用）
#[no_mangle]
pub extern "system" fn Java_com_fongmi_android_tv_api_loader_RustBridge_nativeWarmUp(
    mut env: JNIEnv,
    _class: JClass,
    host: JString,
) -> jstring {
    let host = match str_arg(&mut env, &host) {
        Ok(h) => h,
        Err(e) => return ret_str(&mut env, Err(e)),
    };
    ret_str(
        &mut env,
        safe(|| {
            let _ = get_spider(&host);
            Ok("ok".to_string())
        }),
    )
}

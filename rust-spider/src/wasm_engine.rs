//! WASM 执行引擎：通过 wasmi（纯 Rust，无 C 依赖）执行站点内置的 nbmovie WASM 模块，
//! 调用其导出的 `build_play_url` 生成带签名的播放 API 地址。
//!
//! 设计要点（与 Web 端 wasm-bindgen 胶水行为对齐）：
//! - WASM 内部通过 i32 句柄引用 JS 堆对象（wasm-bindgen heap），
//!   本引擎用常量句柄 `1` 模拟有效对象，`0` 表示 null/None；
//! - `getElementById` 返回 0（TVBox 无 DOM），令 WASM 走 `Date.now()` 时间分支；
//! - `Date.now()` 由 host 函数提供真实毫秒时间戳；
//! - 字符串通过 WASM 自带分配器（`__wbindgen_export` = malloc）写入线性内存，
//!   返回值通过 retptr 约定读回。

use once_cell::sync::Lazy;
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};
use wasmi::core::{F64, Trap};
use wasmi::{AsContextMut, Caller, Engine, Extern, Linker, Module, Store, TypedFunc};

/// 站点 WASM 模块（编译期内嵌；站点更新 WASM 时只需替换此文件）
pub const NBMOVIE_WASM: &[u8] = include_bytes!("../wasm/nbmovie_wasm_bg.wasm");

/// wasm-bindgen 堆句柄常量：1 = 有效对象，0 = null
const HEAP_OBJECT: i32 = 1;

fn now_millis() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as f64)
        .unwrap_or(0.0)
}

/// build_play_url 的签名：(retptr, ptr0, len0, ptr1, len1, ptr2, len2, ptr3, len3) -> ()
type BuildPlayUrlFn = TypedFunc<(i32, i32, i32, i32, i32, i32, i32, i32, i32), ()>;

/// WASM 核心状态（进程内单例，Mutex 保护 Store 可变访问）
pub struct WasmCore {
    store: Store<()>,
    instance: wasmi::Instance,
    /// wasm-bindgen malloc：__wbindgen_export(size, align) -> ptr
    malloc: TypedFunc<(i32, i32), i32>,
    add_stack_pointer: TypedFunc<i32, i32>,
    build_play_url: BuildPlayUrlFn,
}

static ENGINE: Lazy<Mutex<WasmCore>> = Lazy::new(|| {
    Mutex::new(WasmCore::new().unwrap_or_else(|e| panic!("WASM 引擎初始化失败: {}", e)))
});

impl WasmCore {
    fn new() -> Result<Self, wasmi::Error> {
        let engine = Engine::default();
        let module = Module::new(&engine, NBMOVIE_WASM)?;

        let mut linker = Linker::new(&engine);
        Self::define_imports(&mut linker)?;

        let mut store = Store::new(&engine, ());
        let instance = linker
            .instantiate(&mut store, &module)?
            .ensure_no_start(&mut store)?;

        let malloc = Self::get_typed(&instance, &store, "__wbindgen_export")?;
        let add_stack_pointer =
            Self::get_typed(&instance, &store, "__wbindgen_add_to_stack_pointer")?;
        let build_play_url = Self::get_typed(&instance, &store, "build_play_url")?;

        Ok(Self {
            store,
            instance,
            malloc,
            add_stack_pointer,
            build_play_url,
        })
    }

    fn get_typed<P, R>(
        instance: &wasmi::Instance,
        store: &Store<()>,
        name: &str,
    ) -> Result<TypedFunc<P, R>, wasmi::Error>
    where
        P: wasmi::WasmParams,
        R: wasmi::WasmResults,
    {
        match instance.get_export(store, name) {
            Some(Extern::Func(f)) => f.typed::<P, R>(store),
            _ => Err(wasmi::Error::from(Trap::new(format!("缺少导出函数: {}", name)))),
        }
    }

    /// 定义全部 host 函数（与 Web 胶水行为对齐）
    ///
    /// wasmi 0.31 的 `func_wrap` 要求类型化闭包，故全部使用具体参数类型。
    fn define_imports(linker: &mut Linker<()>) -> Result<(), wasmi::Error> {
        let m = "./nbmovie_wasm_bg.js";

        // is_undefined(arg) -> i32 : 恒 false
        linker.func_wrap(m, "__wbg___wbindgen_is_undefined_52709e72fb9f179c", |_arg: i32| -> i32 { 0 })?;

        // throw(ptr, len) : 正常不会触发；静默返回避免宿主崩溃
        linker.func_wrap(m, "__wbg___wbindgen_throw_6ddd609b62940d55", |_ptr: i32, _len: i32| {})?;

        // content(retptr, obj) : meta 不存在时不应被调用；若被调用则写空 (ptr=0, len=0)
        linker.func_wrap(
            m,
            "__wbg_content_4373268a6f34e443",
            |mut caller: Caller<'_, ()>, retptr: i32, _obj: i32| {
                if let Some(mem) = caller.get_export("memory").and_then(Extern::into_memory) {
                    let _ = mem.write(&mut caller.as_context_mut(), retptr as usize, &[0u8; 8]);
                }
            },
        )?;

        // document(obj) -> i32 : 返回 null
        linker.func_wrap(m, "__wbg_document_c0320cd4183c6d9b", |_obj: i32| -> i32 { 0 })?;

        // getElementById(this, ptr, len) -> i32 : TVBox 无 DOM，返回 null
        linker.func_wrap(m, "__wbg_getElementById_d1f25d287b19a833", |_this: i32, _ptr: i32, _len: i32| -> i32 { 0 })?;

        // instanceof_HtmlMetaElement(obj) -> i32 : false
        linker.func_wrap(m, "__wbg_instanceof_HtmlMetaElement_07f78901e9785572", |_obj: i32| -> i32 { 0 })?;

        // instanceof_Window(obj) -> i32 : false
        linker.func_wrap(m, "__wbg_instanceof_Window_23e677d2c6843922", |_obj: i32| -> i32 { 0 })?;

        // Date.now() -> f64（wasmi 0.31 要求 F64 新类型）
        linker.func_wrap(m, "__wbg_now_16f0c993d5dd6c27", || -> F64 {
            F64::from(now_millis())
        })?;

        // static_accessor_GLOBAL / GLOBAL_THIS / SELF / WINDOW -> i32 : 返回有效句柄（避免 null 解引用）
        for name in [
            "__wbg_static_accessor_GLOBAL_8adb955bd33fac2f",
            "__wbg_static_accessor_GLOBAL_THIS_ad356e0db91c7913",
            "__wbg_static_accessor_SELF_f207c857566db248",
            "__wbg_static_accessor_WINDOW_bb9f1ba69d61b386",
        ] {
            linker.func_wrap(m, name, || -> i32 { HEAP_OBJECT })?;
        }

        // object_clone_ref(obj) -> i32 : 返回同一有效句柄
        linker.func_wrap(m, "__wbindgen_object_clone_ref", |_obj: i32| -> i32 { HEAP_OBJECT })?;

        // object_drop_ref(obj) : 无操作
        linker.func_wrap(m, "__wbindgen_object_drop_ref", |_obj: i32| {})?;

        Ok(())
    }

    fn memory(&self) -> Option<wasmi::Memory> {
        self.instance
            .get_export(&self.store, "memory")
            .and_then(Extern::into_memory)
    }

    /// 把字符串写入 WASM 线性内存（使用其内部分配器），返回 (ptr, len)
    fn write_string(&mut self, s: &str) -> Result<(i32, i32), wasmi::Error> {
        let bytes = s.as_bytes();
        let len = bytes.len() as i32;
        // malloc(size, align=1)
        let ptr = self.malloc.call(&mut self.store, (len, 1))?;
        if let Some(mem) = self.memory() {
            mem.write(&mut self.store, ptr as usize, bytes)?;
        }
        Ok((ptr, len))
    }

    fn read_string(&self, ptr: i32, len: i32) -> Result<String, wasmi::Error> {
        if len <= 0 {
            return Ok(String::new());
        }
        let mem = self
            .memory()
            .ok_or_else(|| wasmi::Error::from(Trap::new("missing memory")))?;
        let mut buf = vec![0u8; len as usize];
        mem.read(&self.store, ptr as usize, &mut buf)?;
        Ok(String::from_utf8_lossy(&buf).into_owned())
    }

    fn call_build_play_url(
        &mut self,
        dataid: &str,
        secret: &str,
        quality: &str,
        play_key: &str,
    ) -> Result<String, String> {
        let (p0, l0) = self
            .write_string(dataid)
            .map_err(|e| format!("写 dataid 失败: {}", e))?;
        let (p1, l1) = self
            .write_string(secret)
            .map_err(|e| format!("写 secret 失败: {}", e))?;
        let (p2, l2) = self
            .write_string(quality)
            .map_err(|e| format!("写 quality 失败: {}", e))?;
        let (p3, l3) = self
            .write_string(play_key)
            .map_err(|e| format!("写 play_key 失败: {}", e))?;

        // retptr = 栈上 16 字节
        let retptr = self
            .add_stack_pointer
            .call(&mut self.store, -16)
            .map_err(|e| format!("分配 retptr 失败: {}", e))?;

        // build_play_url(retptr, ptr0, len0, ptr1, len1, ptr2, len2, ptr3, len3)
        self.build_play_url
            .call(
                &mut self.store,
                (retptr, p0, l0, p1, l1, p2, l2, p3, l3),
            )
            .map_err(|e| format!("调用 build_play_url 失败: {}", e))?;

        // 归还栈指针
        let _ = self.add_stack_pointer.call(&mut self.store, 16);

        // 读回 (ptr, len)
        let mem = self
            .memory()
            .ok_or_else(|| "missing memory".to_string())?;
        let mut buf = [0u8; 8];
        mem.read(&self.store, retptr as usize, &mut buf)
            .map_err(|e| format!("读 retptr 失败: {}", e))?;
        let rptr = i32::from_le_bytes([buf[0], buf[1], buf[2], buf[3]]);
        let rlen = i32::from_le_bytes([buf[4], buf[5], buf[6], buf[7]]);

        self.read_string(rptr, rlen)
            .map_err(|e| format!("读结果失败: {}", e))
    }
}

/// 调用 WASM 导出的 build_play_url，生成播放 API 路径
///
/// 参数与 Web 端一致：
/// - `dataid`   : 选集元素 dataid 属性
/// - `secret`   : 播放页 id（/play/xxxx 的 xxxx）
/// - `quality`  : 清晰度（1080 / 720 / 4k）
/// - `play_key` : 播放页 userlink（导航栏内联变量）
pub fn build_play_url(
    dataid: &str,
    secret: &str,
    quality: &str,
    play_key: &str,
) -> Result<String, String> {
    let mut core = ENGINE
        .lock()
        .map_err(|e| format!("WASM 引擎锁失败: {}", e))?;
    core.call_build_play_url(dataid, secret, quality, play_key)
}

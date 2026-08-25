# -*- coding: utf-8 -*-
"""壳子内置 n-sig 解密服务（所有 YouTube 类 TVBox 线路通用）。

背景：YouTube 媒体 URL 中的 n 参数是加密签名（n-sig），未解密/解密失效时
CDN 会拒绝请求（403），播放中途弹 Bad HTTP Status。本模块优先使用壳子
Java 侧内置的 QuickJS 引擎（ES2020+ 完整支持）执行 player.js 中提取的
真实 JS 解密函数，彻底规避 js2py 对现代 JS 静默失败的问题；QuickJS 不可用
时依次兜底 js2py、yt-dlp JSInterpreter。

线路用法（py_youtube.py 等任意线路）：
    from base.nsig import decrypt_nsig
    final_url = decrypt_nsig(media_url, player_url, session=self.session)

或只解密 n 值：
    from base.nsig import decrypt_n
    new_n = decrypt_n(n_value, player_url, session=self.session)

解密函数按 player_url 缓存，重复调用零额外开销。
诊断：get_last_error() 返回失败原因；get_last_engine() 返回成功引擎。
"""

import re
import time
import requests

_player_cache = {}
_fn_cache = {}
_last_error = ''   # 最近一次失败原因（供线路打印诊断）

_DEFAULT_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36'


def _http_get_text(url, session=None):
    try:
        if session is not None:
            r = session.get(url, timeout=15)
        else:
            r = requests.get(url, timeout=15, headers={'User-Agent': _DEFAULT_UA})
        r.raise_for_status()
        return r.text
    except Exception:
        return ''


def _get_player_code(player_url, session=None):
    """拉取 player.js 源码（带缓存）"""
    if not player_url:
        return ''
    key = 'code:' + player_url
    cached = _player_cache.get(key)
    if cached is not None:
        return cached
    url = player_url
    if url.startswith('//'):
        url = 'https:' + url
    elif url.startswith('/'):
        url = 'https://www.youtube.com' + url
    code = _http_get_text(url, session)
    _player_cache[key] = code
    return code


def _verify_is_function(code, name):
    for p in (r'function\s+' + re.escape(name) + r'\s*\(',
              re.escape(name) + r'\s*=\s*function\s*\(',
              r'var\s+' + re.escape(name) + r'\s*=\s*function\s*\('):
        if re.search(p, code):
            return True
    return False


def _extract_nsig_function_name(code):
    """从 player.js 提取 n-sig 主函数名"""
    patterns = [
        r'\.get\((?:"|\')n(?:"|\')\)\)\s*&&\s*\(\w+=([a-zA-Z0-9_$]+)\(',
        r'\.get\((?:"|\')n(?:"|\')\)\)\s*&&\s*\(\w+=([a-zA-Z0-9_$]+)(?:\[(\d+)\])?\(',
        r'String\.fromCharCode\(110\).*?get\(\w+\)\)\s*&&\s*\(\w+=([a-zA-Z0-9_$]+)\(',
        r'get\((?:"|\')n(?:"|\')\).*?=[^=]*?\b([a-zA-Z0-9_$]{2,})\(',
        r'\b([a-zA-Z0-9_$]{2,})\([^)]*\bget\((?:"|\')n(?:"|\')\)',
        r'[;,]\s*([a-zA-Z0-9_$]+)\s*\([^)]*\bget\((?:"|\')n(?:"|\')\)',
    ]
    for pattern in patterns:
        m = re.search(pattern, code)
        if m:
            name = m.group(1)
            if _verify_is_function(code, name):
                return name
    m = re.search(r'set\((?:"|\')n(?:"|\')\s*,\s*([a-zA-Z0-9_$]+)\(', code)
    if m and _verify_is_function(code, m.group(1)):
        return m.group(1)
    return None


def _extract_js_function_body(code, name):
    """提取函数体（不含外层花括号），支持字符串/转义感知的括号配对"""
    starts = []
    for pattern in (r'function\s+' + re.escape(name) + r'\s*\([^)]*\)\s*\{',
                    re.escape(name) + r'\s*=\s*function\s*\([^)]*\)\s*\{',
                    r'var\s+' + re.escape(name) + r'\s*=\s*function\s*\([^)]*\)\s*\{'):
        m = re.search(pattern, code)
        if m:
            starts.append(m.end() - 1)
    if not starts:
        return ''
    start = starts[0]
    depth = 0
    in_str = None
    escape = False
    for i in range(start, len(code)):
        ch = code[i]
        if escape:
            escape = False
            continue
        if ch == '\\':
            escape = True
            continue
        if in_str:
            if ch == in_str:
                in_str = None
            continue
        if ch in ('"', "'", '`'):
            in_str = ch
            continue
        if ch == '{':
            depth += 1
        elif ch == '}':
            depth -= 1
            if depth == 0:
                return code[start + 1:i]
    return ''


def _extract_raw_js_function(code, name):
    """提取函数完整原始 JS 定义（function NAME(...){...} / NAME=function(...){...}）"""
    patterns = [
        r'function\s+' + re.escape(name) + r'\s*\([^)]*\)\s*\{',
        re.escape(name) + r'\s*=\s*function\s*\([^)]*\)\s*\{',
        r'var\s+' + re.escape(name) + r'\s*=\s*function\s*\([^)]*\)\s*\{',
    ]
    for pat in patterns:
        m = re.search(pat, code)
        if not m:
            continue
        start = m.start()
        brace_pos = code.find('{', m.start())
        if brace_pos < 0:
            continue
        depth = 0
        in_str = None
        escape = False
        for i in range(brace_pos, len(code)):
            ch = code[i]
            if escape:
                escape = False
                continue
            if ch == '\\':
                escape = True
                continue
            if in_str:
                if ch == in_str:
                    in_str = None
                continue
            if ch in ('"', "'", '`'):
                in_str = ch
                continue
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    return code[start:i + 1]
        break
    return None


def _extract_raw_js_object(code, name):
    """提取对象完整原始 JS 定义（var NAME={...}; / NAME={...}）"""
    patterns = [
        r'var\s+' + re.escape(name) + r'\s*=\s*\{',
        r'(?:let|const)\s+' + re.escape(name) + r'\s*=\s*\{',
        re.escape(name) + r'\s*=\s*\{',
    ]
    for pat in patterns:
        m = re.search(pat, code)
        if not m:
            continue
        start = m.start()
        brace_pos = m.end() - 1  # pattern 以 '{' 结尾
        if code[brace_pos] != '{':
            brace_pos = code.find('{', m.end())
        if brace_pos < 0:
            continue
        depth = 0
        in_str = None
        escape = False
        for i in range(brace_pos, len(code)):
            ch = code[i]
            if escape:
                escape = False
                continue
            if ch == '\\':
                escape = True
                continue
            if in_str:
                if ch == in_str:
                    in_str = None
                continue
            if ch in ('"', "'", '`'):
                in_str = ch
                continue
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    end = i + 1
                    while end < len(code) and code[end] in ' \t\r\n;':
                        end += 1
                    return code[start:end]
        break
    return None


_JS_BUILTIN_OBJS = {'Array', 'String', 'Math', 'Number', 'RegExp', 'JSON', 'Object',
                    'Promise', 'Map', 'Set', 'Date', 'Symbol', 'BigInt', 'Function',
                    'Int8Array', 'Uint8Array', 'Uint8ClampedArray', 'Int16Array',
                    'Uint16Array', 'Int32Array', 'Uint32Array', 'Float32Array',
                    'Float64Array', 'BigInt64Array', 'BigUint64Array', 'ArrayBuffer',
                    'DataView', 'Error', 'TypeError', 'RangeError', 'ReferenceError',
                    'SyntaxError', 'EvalError', 'URIError', 'AggregateError',
                    'WeakMap', 'WeakSet', 'Proxy', 'Reflect', 'globalThis'}
_JS_BUILTIN_FUNCS = {'split', 'join', 'reverse', 'slice', 'splice', 'push', 'pop',
                     'shift', 'unshift', 'concat', 'map', 'filter', 'reduce',
                     'forEach', 'find', 'findIndex', 'includes', 'indexOf',
                     'lastIndexOf', 'some', 'every', 'sort', 'fill', 'copyWithin',
                     'flat', 'flatMap', 'charAt', 'charCodeAt', 'codePointAt',
                     'fromCharCode', 'fromCodePoint', 'toString', 'valueOf', 'keys',
                     'values', 'entries', 'has', 'get', 'set', 'add', 'delete', 'clear',
                     'parseInt', 'parseFloat', 'isNaN', 'isFinite', 'encodeURI',
                     'encodeURIComponent', 'decodeURI', 'decodeURIComponent',
                     'escape', 'unescape', 'atob', 'btoa', 'String', 'Array', 'Math',
                     'Number', 'RegExp', 'JSON', 'Object', 'Promise', 'Map', 'Set',
                     'Date', 'Symbol', 'BigInt', 'Function', 'window', 'document',
                     'navigator', 'location', 'setTimeout', 'clearTimeout',
                     'setInterval', 'clearInterval', 'queueMicrotask', 'alert',
                     'console', 'globalThis', 'undefined', 'NaN', 'Infinity'}


def _extract_nsig_deps_all(code, func_name):
    """递归（BFS）提取主函数及其依赖函数/对象的原始 JS 定义，防循环。

    只提取一层依赖的版本在遇到「函数A调用B，B又调用C」时会让 QuickJS
    执行报 ReferenceError。这里用队列 BFS 把整条依赖链都抠出来。
    """
    deps = {}
    queue = [(func_name, 'function')]
    seen = set()
    while queue:
        name, kind = queue.pop(0)
        if name in seen:
            continue
        seen.add(name)
        if kind == 'function':
            raw = _extract_raw_js_function(code, name)
            body = _extract_js_function_body(code, name) if raw else ''
        else:
            raw = _extract_raw_js_object(code, name)
            body = ''
        if not raw:
            continue
        deps[name] = raw
        if not body:
            continue
        # 对象方法调用：obj.method(...) -> 依赖对象
        for obj_name, _ in re.findall(r'([a-zA-Z0-9_$]+)\.([a-zA-Z0-9_$]+)\(', body):
            if obj_name in _JS_BUILTIN_OBJS or obj_name in deps or obj_name in seen:
                continue
            if _extract_raw_js_object(code, obj_name):
                queue.append((obj_name, 'object'))
        # 函数调用：func(...) -> 依赖函数
        for call_name in re.findall(r'\b([a-zA-Z0-9_$]{2,})\s*\(', body):
            if call_name in _JS_BUILTIN_FUNCS or call_name in deps or call_name in seen:
                continue
            if _verify_is_function(code, call_name):
                queue.append((call_name, 'function'))
    return deps


def _build_js_source(func_name, main_raw, deps):
    """组装交给 JS 引擎执行的源码：依赖定义 + 主函数定义（只定义，不调用）。

    函数声明存在提升，依赖顺序不影响调用；调用由引擎侧在函数定义完成后执行。
    """
    parts = []
    seen = set()
    for name, raw in deps.items():
        if name in seen:
            continue
        seen.add(name)
        parts.append(raw)
    parts.append(main_raw)
    return '\n'.join(parts)


def _build_js2py_code(func_name, main_raw, deps):
    """组装可交给 js2py 执行的 JS 源码：依赖定义 + 主函数 + 调用"""
    parts = []
    seen = set()
    for name, raw in deps.items():
        if name in seen:
            continue
        seen.add(name)
        parts.append(raw)
    parts.append(main_raw)
    parts.append('var __n__ = "";')
    parts.append('var __decrypted__ = %s(__n__);' % func_name)
    return '\n'.join(parts)


_last_engine = ''  # 最近一次成功使用的引擎（quickjs/js2py/jsinterp/None）


def _quickjs_decrypt(js_src, func_name, n_value):
    """通过 Chaquopy 调 Java 侧 QuickJS 引擎执行解密；返回新 n 或 None。"""
    global _last_error
    try:
        from java import jclass
        Cls = jclass('com.fongmi.chaquo.NsigDecryptor')
        res = Cls.decrypt(js_src, func_name, str(n_value))
        if res is None:
            return None
        return str(res)
    except Exception as e:
        _last_error = 'quickjs java bridge error: %r' % (e,)
        return None


def _quickjs_prepare(js_src, func_name):
    """通过 Chaquopy 调 Java 侧 QuickJS 预编译（evaluate + 缓存函数引用）。

    用于冒烟验证：能成功 evaluate 完整 player.js 且函数存在即可信，
    不实际调用解密（避免无效输入返回 null 的误判）。
    返回 True/False。
    """
    try:
        from java import jclass
        Cls = jclass('com.fongmi.chaquo.NsigDecryptor')
        return bool(Cls.prepare(js_src, func_name))
    except Exception as e:
        _last_error = 'quickjs prepare bridge error: %r' % (e,)
        return False


# ---------- 方案A：全量 player.js + solver 注入（新版 URL 工厂模式） ----------

_SETUP_NODES_JS = r"""
if (typeof globalThis.XMLHttpRequest === "undefined") {
    globalThis.XMLHttpRequest = { prototype: {} };
}
if (typeof URL === "undefined") {
    globalThis.location = {
        hash: "", host: "www.youtube.com", hostname: "www.youtube.com",
        href: "https://www.youtube.com/watch?v=yt-dlp-wins", origin: "https://www.youtube.com",
        password: "", pathname: "/watch", port: "", protocol: "https:",
        search: "?v=yt-dlp-wins", username: "",
    };
} else {
    globalThis.location = new URL("https://www.youtube.com/watch?v=yt-dlp-wins");
}
if (typeof globalThis.document === "undefined") {
    globalThis.document = Object.create(null);
}
if (typeof globalThis.navigator === "undefined") {
    globalThis.navigator = Object.create(null);
}
if (typeof globalThis.self === "undefined") {
    globalThis.self = globalThis;
}
if (typeof globalThis.window === "undefined") {
    globalThis.window = globalThis;
}
"""


def _extract_factory_candidates(code):
    """从 player.js 提取 URL 工厂函数候选（新版 n-sig 模式）。

    新版 n-sig 不是独立函数：n 在 URL 对象方法内部修改。ejs 通过 AST 匹配
    `X.Y("alr","yes")` 调用定位工厂函数（创建 URL 对象并 set("alr","yes")）。
    这里用正则近似：找所有 `set("alr","yes")` 调用点，向前回溯最近的
    `NAME=function(` 定义作为候选。真实工厂通常含 `new g.xxx(url, true)`。
    """
    cands = []
    for m in re.finditer(r'set\s*\(\s*["\']alr["\']\s*,\s*["\']yes["\']', code):
        pos = m.start()
        seg = code[max(0, pos - 8000):pos]
        matches = list(re.finditer(r'([A-Za-z0-9_$]{1,3})\s*=\s*function\s*\(', seg))
        if not matches:
            continue
        name = matches[-1].group(1)
        # 确认该函数定义存在且体含 new g.（URL 对象工厂特征）
        m2 = re.search(r'\b' + re.escape(name) + r'\s*=\s*function\s*\(', code)
        if not m2:
            continue
        brace = code.find('{', m2.end())
        if brace < 0:
            continue
        depth = 0
        in_str = None
        esc = False
        for i in range(brace, len(code)):
            ch = code[i]
            if esc:
                esc = False
                continue
            if ch == '\\':
                esc = True
                continue
            if in_str:
                if ch == in_str:
                    in_str = None
                continue
            if ch in ('"', "'", '`'):
                in_str = ch
                continue
            if ch == '{':
                depth += 1
            elif ch == '}':
                depth -= 1
                if depth == 0:
                    body = code[m2.start():i + 1]
                    if 'new g.' in body:
                        cands.append(name)
                    break
    # 去重保持顺序
    seen = set()
    out = []
    for n in cands:
        if n not in seen:
            seen.add(n)
            out.append(n)
    return out


def _build_full_player_js(code, candidates):
    """组装全量执行 JS：setupNodes + 完整 player.js（IIFE 内注入 solver）。

    solver 定义在 player.js IIFE 内部（可访问词法作用域中的工厂函数），
    并暴露到 globalThis.__nsolver__ 供 Java 侧调用。多个候选工厂逐个 try。
    """
    cands_json = ', '.join('"%s"' % n for n in candidates)
    solver = r"""
  var __nsolver__ = function(__n__) {
    var cands = [%s];
    for (var i = 0; i < cands.length; i++) {
      try {
        var fn = eval(cands[i]);
        if (typeof fn !== "function") continue;
        var url = fn("https://youtube.com/watch?v=yt-dlp-wins", "s", undefined);
        if (!url || typeof url.set !== "function") continue;
        url.set("n", __n__);
        var proto = Object.getPrototypeOf(url);
        var keys = Object.keys(proto).concat(Object.getOwnPropertyNames(proto));
        for (var j = 0; j < keys.length; j++) {
          var key = keys[j];
          if (key !== "constructor" && key !== "set" && key !== "get" && key !== "clone") {
            try { url[key](); } catch (e) {}
            break;
          }
        }
        var out = url.get("n");
        if (out) return out;
      } catch (e) {}
    }
    return null;
  };
  globalThis.__nsolver__ = __nsolver__;
""" % cands_json
    marker = '})(_yt_player);'
    idx = code.rfind(marker)
    if idx < 0:
        return ''
    injected = code[:idx] + solver + code[idx:]
    return _SETUP_NODES_JS + '\n' + injected


def _compile_full_player_func(code):
    """方案A：全量执行。返回 fn(n) 或 None，失败原因写入 _last_error。"""
    global _last_error, _last_engine
    if not code:
        _last_error = 'player.js empty'
        return None
    cands = _extract_factory_candidates(code)
    if not cands:
        _last_error = 'factory candidates not found (no set("alr","yes"))'
        return None
    js_src = _build_full_player_js(code, cands)
    if not js_src:
        _last_error = 'player IIFE marker not found'
        return None

    def fn_full(n_value):
        out = _quickjs_decrypt(js_src, '__nsolver__', n_value)
        if out is None:
            _last_error = 'quickjs full-player exec failed (n=%s)' % (str(n_value)[:16],)
            return None
        return out

    # 冒烟验证：QuickJS 能 evaluate 整个 player.js 且函数存在即可信。
    # 注意不实际调用解密——无效输入会让解密函数返回 null，会造成误判；
    # 真正的失败表现为 evaluate 抛异常（prepare 返回 False）。
    if not _quickjs_prepare(js_src, '__nsolver__'):
        _last_error = 'quickjs full-player prepare failed (cands=%s)' % (cands,)
        return None
    _last_error = ''
    _last_engine = 'quickjs-full'
    return fn_full



def _compile_decrypt_func(player_url, session=None):
    """编译并缓存 player_url 对应的解密函数；失败返回 None，原因写入 _last_error。
    路径A: Java 侧 QuickJS 引擎执行原始 JS（壳子内置，ES2020+，最可靠）
    路径B: 壳子内置 js2py 执行原始 JS
    路径C: yt-dlp JSInterpreter 解释执行（支持部分现代语法）"""
    global _last_error, _last_engine
    _last_engine = ''
    js2py = None
    try:
        import js2py
    except Exception as e:
        _last_error = 'js2py import failed: %r' % (e,)

    code = _get_player_code(player_url, session)
    if not code:
        _last_error = 'player.js fetch empty: %s' % (player_url[:80],)
        return None

    # ---- 路径A0: QuickJS 全量执行（方案A，适配新版 URL 工厂模式） ----
    fn_full = _compile_full_player_func(code)
    if fn_full is not None:
        return fn_full

    func_name = _extract_nsig_function_name(code)
    if not func_name:
        _last_error = 'nsig function name not found in player.js (len=%d)' % len(code)
        return None

    # ---- 路径A: QuickJS（Java 侧） ----
    try:
        main_raw = _extract_raw_js_function(code, func_name)
        main_body = _extract_js_function_body(code, func_name)
        if main_raw and main_body:
            deps = _extract_nsig_deps_all(code, func_name)
            js_src = _build_js_source(func_name, main_raw, deps)

            def fn_quickjs(n_value):
                out = _quickjs_decrypt(js_src, func_name, n_value)
                if out is None:
                    _last_error = 'quickjs exec failed (n=%s)' % (str(n_value)[:16],)
                    return None
                return out

            # 冒烟验证：QuickJS 是完整引擎，只要执行成功结果即可信
            smoke = fn_quickjs('smoke_test_123')
            if smoke is None:
                _last_error = 'quickjs smoke test failed; fallback to js2py'
            else:
                _last_error = ''
                _last_engine = 'quickjs'
                return fn_quickjs
        else:
            _last_error = 'nsig main function extract failed (func=%s)' % func_name
    except Exception as e:
        _last_error = 'quickjs unexpected: %r' % (e,)

    # ---- 路径B: js2py ----
    if js2py is not None:
        try:
            main_raw = _extract_raw_js_function(code, func_name)
            main_body = _extract_js_function_body(code, func_name)
            if main_raw and main_body:
                deps = _extract_nsig_deps_all(code, func_name)
                js_src = _build_js2py_code(func_name, main_raw, deps)
                ctx = js2py.EvalJs()
                try:
                    ctx.execute(js_src)
                except Exception as e:
                    _last_error = 'js2py compile error: %r (func=%s deps=%d)' % (e, func_name, len(deps))
                else:
                    def fn_js2py(n_value):
                        try:
                            n_esc = str(n_value).replace('\\', '\\\\').replace('"', '\\"')
                            ctx.execute('var __n__ = "%s";' % n_esc)
                            ctx.execute('var __decrypted__ = %s(__n__);' % func_name)
                            return str(ctx.eval('__decrypted__'))
                        except Exception as e:
                            _last_error = 'js2py exec error: %r' % (e,)
                            return None
                    # 冒烟验证：js2py 对现代语法可能静默返回 None，必须实测一次
                    smoke = fn_js2py('smoke_test_123')
                    if smoke is None:
                        _last_error = 'js2py smoke test failed; fallback to jsinterp'
                    else:
                        _last_error = ''
                        _last_engine = 'js2py'
                        return fn_js2py
        except Exception as e:
            _last_error = 'js2py unexpected: %r' % (e,)

    # ---- 路径C: yt-dlp JSInterpreter ----
    try:
        from .jsinterp import JSInterpreter
        interp = JSInterpreter(code)
        func = interp.extract_function(func_name)

        def fn_jsinterp(n_value):
            try:
                return str(func((n_value,)))
            except Exception as e:
                _last_error = 'jsinterp exec error: %r' % (e,)
                return None

        # 冒烟验证：jsinterp 对不支持语法可能静默返回 None
        smoke = fn_jsinterp('smoke_test_123')
        if smoke is None:
            _last_error = 'jsinterp smoke test failed (unsupported syntax?)'
            return None

        _last_error = ''
        _last_engine = 'jsinterp'
        return fn_jsinterp
    except Exception as e:
        _last_error = 'jsinterp compile error: %r (func=%s)' % (e, func_name)
        return None


def get_last_error():
    """返回最近一次解密失败原因（供线路打印诊断）"""
    return _last_error


def get_last_engine():
    """返回最近一次成功使用的解密引擎（quickjs/js2py/jsinterp/空）"""
    return _last_engine


def _dbg(msg):
    """记录调试日志到 App 内置日志（DbgLog，供本地调试页查看）；失败静默忽略。"""
    try:
        from java import jclass
        jclass('com.fongmi.chaquo.DbgLog').log(msg)
    except Exception:
        pass


def decrypt_n(n_value, player_url, session=None):
    """解密 n 参数值；失败返回 None"""
    if not n_value or not player_url:
        return None
    _dbg('nsig decrypt_n enter player=%s n=%s' % (player_url[:80], str(n_value)[:16]))
    key = 'fn:' + player_url
    fn = _fn_cache.get(key)
    if fn is None:
        fn = _compile_decrypt_func(player_url, session)
        if fn is None:
            _fn_cache[key] = False
            _dbg('nsig compile FAIL player=%s reason=%s' % (player_url[:80], _last_error))
            return None
        _fn_cache[key] = fn
        _dbg('nsig compile OK engine=%s player=%s' % (_last_engine, player_url[:80]))
    elif fn is False:
        return None
    try:
        decrypted = fn(n_value)
    except Exception as e:
        _dbg('nsig exec EXC %r' % (e,))
        return None
    if decrypted and decrypted != str(n_value):
        _dbg('nsig OK engine=%s n=%s -> %s' % (_last_engine, str(n_value)[:16], str(decrypted)[:16]))
        return decrypted
    _dbg('nsig FAIL engine=%s reason=%s' % (_last_engine, _last_error))
    return None


def decrypt_nsig(media_url, player_url, session=None):
    """解密媒体 URL 中的 n 参数并替换，返回完整 URL；失败返回原 URL。

    替换策略：直接从原始 query 中定位 n 参数值（含 URL 编码原样），
    只替换该值，其余参数与顺序一概不动，避免 quote 不一致导致替换失败。
    """
    try:
        from urllib.parse import urlparse, urlunparse, unquote, quote
    except ImportError:
        from urllib.parse import urlparse, urlunparse, unquote, quote
    try:
        parsed = urlparse(media_url)
        m = re.search(r'(^|&)n=([^&]+)', parsed.query)
        if not m:
            return media_url
        n_raw = m.group(2)          # URL 中原始编码值
        n_value = unquote(n_raw)    # 解码后的真实 n 值
        if not n_value:
            return media_url
        decrypted = decrypt_n(n_value, player_url, session)
        if not decrypted or decrypted == n_value:
            return media_url
        new_n_raw = quote(decrypted, safe='')
        new_query = parsed.query[:m.start(2)] + new_n_raw + parsed.query[m.end(2):]
        return urlunparse(parsed._replace(query=new_query))
    except Exception:
        return media_url

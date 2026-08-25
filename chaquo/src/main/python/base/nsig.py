# -*- coding: utf-8 -*-
"""壳子内置 js2py n-sig 解密服务（所有 YouTube 类 TVBox 线路通用）。

背景：YouTube 媒体 URL 中的 n 参数是加密签名（n-sig），未解密/解密失效时
CDN 会拒绝请求（403），播放中途弹 Bad HTTP Status。本模块利用壳子内置的
js2py，直接执行 player.js 中提取的真实 JS 解密函数，无需依赖在线 API，
也无需线路各自实现解密。

线路用法（py_youtube.py 等任意线路）：
    from base.nsig import decrypt_nsig
    final_url = decrypt_nsig(media_url, player_url, session=self.session)

或只解密 n 值：
    from base.nsig import decrypt_n
    new_n = decrypt_n(n_value, player_url, session=self.session)

解密函数按 player_url 缓存，重复调用零额外开销。
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


def _extract_nsig_deps_raw(code, func_body):
    """提取主函数体引用的依赖函数/对象的原始 JS 定义"""
    deps = {}
    # 模式1: obj.method(...) -> 依赖对象
    for obj_name, _ in re.findall(r'([a-zA-Z0-9_$]+)\.([a-zA-Z0-9_$]+)\(', func_body):
        if obj_name in ('Array', 'String', 'Math', 'Number', 'RegExp', 'JSON', 'Object'):
            continue
        if obj_name not in deps:
            raw = _extract_raw_js_object(code, obj_name)
            if raw:
                deps[obj_name] = raw
    # 模式2: func(...) -> 依赖函数
    for call_name in re.findall(r'\b([a-zA-Z0-9_$]{2,})\s*\(', func_body):
        if call_name in ('split', 'join', 'reverse', 'slice', 'splice', 'push', 'pop',
                         'shift', 'unshift', 'concat', 'map', 'filter', 'reduce',
                         'charCodeAt', 'fromCharCode', 'parseInt', 'parseFloat',
                         'encodeURIComponent', 'decodeURIComponent', 'String', 'Array',
                         'Math', 'Number', 'RegExp', 'JSON', 'Object', 'window',
                         'document', 'navigator', 'location', 'setTimeout', 'Date'):
            continue
        if call_name not in deps and _verify_is_function(code, call_name):
            raw = _extract_raw_js_function(code, call_name)
            if raw:
                deps[call_name] = raw
    return deps


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


def _compile_decrypt_func(player_url, session=None):
    """编译并缓存 player_url 对应的解密函数；失败返回 None，原因写入 _last_error。
    路径A: 壳子内置 js2py 执行原始 JS；路径B: yt-dlp JSInterpreter 解释执行（支持现代语法）"""
    global _last_error
    js2py = None
    try:
        import js2py
    except Exception as e:
        _last_error = 'js2py import failed: %r' % (e,)

    code = _get_player_code(player_url, session)
    if not code:
        _last_error = 'player.js fetch empty: %s' % (player_url[:80],)
        return None
    func_name = _extract_nsig_function_name(code)
    if not func_name:
        _last_error = 'nsig function name not found in player.js (len=%d)' % len(code)
        return None

    # ---- 路径A: js2py ----
    if js2py is not None:
        try:
            main_raw = _extract_raw_js_function(code, func_name)
            main_body = _extract_js_function_body(code, func_name)
            if main_raw and main_body:
                deps = _extract_nsig_deps_raw(code, main_body)
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
                        return fn_js2py
        except Exception as e:
            _last_error = 'js2py unexpected: %r' % (e,)

    # ---- 路径B: yt-dlp JSInterpreter ----
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
        return fn_jsinterp
    except Exception as e:
        _last_error = 'jsinterp compile error: %r (func=%s)' % (e, func_name)
        return None


def get_last_error():
    """返回最近一次解密失败原因（供线路打印诊断）"""
    return _last_error


def decrypt_n(n_value, player_url, session=None):
    """解密 n 参数值；失败返回 None"""
    if not n_value or not player_url:
        return None
    key = 'fn:' + player_url
    fn = _fn_cache.get(key)
    if fn is None:
        fn = _compile_decrypt_func(player_url, session)
        if fn is None:
            _fn_cache[key] = False
            return None
        _fn_cache[key] = fn
    elif fn is False:
        return None
    try:
        decrypted = fn(n_value)
    except Exception:
        return None
    if decrypted and decrypted != str(n_value):
        return decrypted
    return None


def decrypt_nsig(media_url, player_url, session=None):
    """解密媒体 URL 中的 n 参数并替换，返回完整 URL；失败返回原 URL"""
    try:
        from urllib.parse import urlparse, urlunparse, parse_qs, quote
    except ImportError:
        from urllib.parse import urlparse, urlunparse, parse_qs, quote
    try:
        parsed = urlparse(media_url)
        query = parse_qs(parsed.query)
        n_value = query.get('n', [None])[0]
        if not n_value:
            return media_url
        decrypted = decrypt_n(n_value, player_url, session)
        if not decrypted or decrypted == n_value:
            return media_url
        import re as _re
        new_query = parsed.query.replace('n=' + quote(n_value), 'n=' + quote(decrypted), 1)
        if new_query == parsed.query:
            new_query = _re.sub(r'([?&])n=' + _re.escape(quote(n_value)) + r'(&|$)',
                                r'\1n=' + quote(decrypted) + r'\2', parsed.query)
        if new_query == parsed.query:
            new_query = _re.sub(r'([?&])n=' + _re.escape(n_value) + r'(&|$)',
                                r'\1n=' + quote(decrypted) + r'\2', parsed.query)
        return urlunparse(parsed._replace(query=new_query))
    except Exception:
        return media_url

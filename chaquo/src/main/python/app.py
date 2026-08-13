import os
import requests
from importlib.machinery import SourceFileLoader
import json
import time

STREAM_PROXY_MIME = 'application/x-codex-stream-url'


def spider(cache, api):
    if not api.startswith('http') and not '/' in api and not '\\' in api and '\n' not in api:
        module_name = api[:-3] if api.endswith('.py') else api
        try:
            import importlib
            return importlib.import_module(module_name).Spider()
        except ImportError:
            pass
    name = os.path.basename(api)
    path = cache + '/' + name
    download(path, api)
    name = name.split('.')[0]
    return SourceFileLoader(name, path).load_module().Spider()


def download(path, api):
    if api.startswith('http'):
        writeFile(path, redirect(api).content)
    else:
        writeFile(path, str.encode(api))


def writeFile(path, content):
    with open(path, 'wb') as f:
        f.write(content)


def redirect(url):
    rsp = requests.get(url, allow_redirects=False, verify=False)
    if 'Location' in rsp.headers:
        return redirect(rsp.headers['Location'])
    else:
        return rsp


def str2json(content):
    return json.loads(content)


def getDependence(ru):
    result = ru.getDependence()
    return result


def getName(ru):
    result = ru.getName()
    return result


def init(ru, extend):
    ru.init(extend)
    apply_system_proxy(ru, extend)


def apply_system_proxy(ru, extend):
    try:
        data = json.loads(extend) if extend else {}
        proxy = data.get('proxy')
        use_system = proxy is None or str(proxy).lower() in ('', 'system', 'direct', 'none', 'vpn')
        name = ru.getName() if hasattr(ru, 'getName') else ''
        if isinstance(proxy, str) and proxy.lower().startswith(('socks://', 'socks5://', 'socks5h://')):
            session = getattr(ru, 'session', None)
            if session is not None:
                session.proxies = {'http': proxy, 'https': proxy}
                session.trust_env = False
            if hasattr(ru, 'proxy_str'):
                ru.proxy_str = proxy
            return
        if use_system and ('YouTube' in name or 'youtube' in name or '油管' in name):
            session = getattr(ru, 'session', None)
            if session is not None:
                session.proxies = {}
                session.trust_env = False
            if hasattr(ru, 'proxy_str'):
                ru.proxy_str = 'system'
    except Exception:
        pass


def homeContent(ru, filter):
    result = ru.homeContent(filter)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def homeVideoContent(ru):
    result = ru.homeVideoContent()
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def categoryContent(ru, tid, pg, filter, extend):
    result = ru.categoryContent(tid, pg, filter, str2json(extend))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def detailContent(ru, array):
    result = ru.detailContent(str2json(array))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def searchContent(ru, key, quick, pg="1"):
    result = ru.searchContent(key, quick, pg)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def playerContent(ru, flag, id, vipFlags):
    result = ru.playerContent(flag, id, str2json(vipFlags))
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def liveContent(ru, url):
    result = ru.liveContent(url)
    return result


def localProxy(ru, param):
    params = str2json(param)
    prepare_proxy_hls(ru, params)
    result = local_stream_proxy(ru, params)
    if result is None:
        result = ru.localProxy(params)
    return result


def prepare_proxy_hls(ru, params):
    try:
        if params.get('do') == 'py' and params.get('type') == 'hls' and get_stream_proxy(ru):
            setattr(ru, 'direct_segments', False)
    except Exception:
        pass


def local_stream_proxy(ru, params):
    try:
        if params.get('do') != 'py':
            return None
        typ = params.get('type')
        if typ == 'media':
            return stream_youtube_media(ru, params)
        if typ == 'single':
            return stream_youtube_single(ru, params)
        if typ == 'hls':
            return stream_youtube_hls(ru, params)
    except Exception:
        return None
    return None


def stream_youtube_media(ru, params):
    vid = params.get('vid')
    quality = params.get('quality') or '1080p'
    track = params.get('track')
    if not vid or track not in ('video', 'audio') or not hasattr(ru, 'getCache'):
        return None
    data = ru.getCache(f'yt_{vid}_{quality}')
    if not isinstance(data, dict):
        return None
    target_url = data.get('video_url') if track == 'video' else data.get('audio_url')
    media_item = data.get('video_item') if track == 'video' else data.get('audio_item')
    if not target_url:
        return None
    headers = get_stream_headers(ru, media_item, params)
    return [200, STREAM_PROXY_MIME, {'url': target_url, 'headers': headers, 'proxy': get_stream_proxy(ru), 'content_type': 'application/octet-stream'}]


def stream_youtube_single(ru, params):
    vid = params.get('vid')
    if not vid or not hasattr(ru, 'getCache'):
        return None
    data = ru.getCache(f'yt_single_{vid}')
    if not isinstance(data, dict) or not data.get('url'):
        return None
    headers = dict(data.get('headers') or getattr(ru, 'header', {}) or {})
    range_header = params.get('range') or params.get('Range')
    if range_header:
        headers['Range'] = range_header
    return [200, STREAM_PROXY_MIME, {'url': data.get('url'), 'headers': headers, 'proxy': get_stream_proxy(ru), 'content_type': 'video/mp4'}]


def stream_youtube_hls(ru, params):
    key = params.get('key') or ''
    cache = getattr(ru, 'hls_url_cache', {}) or {}
    item = cache.get(key)
    if not isinstance(item, dict):
        return None
    kind = item.get('kind')
    target_url = item.get('url') or ''
    if not target_url or kind in ('master', 'playlist') or target_url.split('?')[0].endswith('.m3u8'):
        return None
    headers = get_hls_headers(ru, target_url, kind)
    range_header = params.get('range') or params.get('Range')
    if range_header:
        headers['Range'] = range_header
    try:
        ttl = ru._hls_ttl(kind) if hasattr(ru, '_hls_ttl') else 600
        item['expires'] = time.time() + ttl
    except Exception:
        pass
    return [200, STREAM_PROXY_MIME, {'url': target_url, 'headers': headers, 'proxy': get_stream_proxy(ru), 'content_type': 'application/octet-stream'}]


def get_stream_headers(ru, media_item, params):
    headers = dict(getattr(ru, 'header', {}) or {})
    if isinstance(media_item, dict):
        headers.update(media_item.get('headers') or {})
    range_header = params.get('range') or params.get('Range')
    if range_header:
        headers['Range'] = range_header
    return headers


def get_hls_headers(ru, target_url, kind):
    try:
        if hasattr(ru, '_hls_headers'):
            return dict(ru._hls_headers(target_url, kind) or {})
    except Exception:
        pass
    headers = dict(getattr(ru, 'header', {}) or {})
    headers['Accept'] = '*/*'
    if kind == 'media':
        headers['User-Agent'] = 'com.google.android.youtube/21.02.35 (Linux; U; Android 11) gzip'
        headers.pop('Origin', None)
        headers.pop('Referer', None)
    return headers


def get_stream_proxy(ru):
    proxy = ''
    try:
        proxies = getattr(getattr(ru, 'session', None), 'proxies', {}) or {}
        proxy = proxies.get('https') or proxies.get('http') or ''
    except Exception:
        proxy = ''
    if not proxy:
        proxy = str(getattr(ru, 'proxy_str', '') or '')
    if not proxy:
        try:
            proxy = str((getattr(ru, 'extendDict', {}) or {}).get('proxy') or '')
        except Exception:
            proxy = ''
    value = str(proxy or '').strip()
    if not value or value.lower() in ('system', 'vpn', 'direct', 'none'):
        return ''
    if not value.startswith(('http://', 'https://', 'socks://', 'socks5://', 'socks5h://')):
        value = 'http://' + value
    return value


def action(ru, action):
    result = ru.action(action)
    formatJo = json.dumps(result, ensure_ascii=False)
    return formatJo


def destroy(ru):
    ru.destroy()


def run():
    pass


if __name__ == '__main__':
    run()

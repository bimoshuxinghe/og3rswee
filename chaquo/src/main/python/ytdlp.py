import json

import yt_dlp

_BASE_OPTS = {
    'quiet': True,
    'no_warnings': True,
    'noplaylist': True,
    'skip_download': True,
    'socket_timeout': 20,
    'retries': 2,
    # 显式指定可直链播放的格式：优先含音视频的 mp4（progressive），
    # 其次含音视频的任意直链，最后兜底交给 yt-dlp 默认选择
    'format': ('best[ext=mp4][protocol^=http][vcodec!=none][acodec!=none]/'
               'best[vcodec!=none][acodec!=none]/best'),
}

# YouTube 反爬加固：显式指定可用的 player_client，tv 客户端无需登录即可拿直链，
# 避免默认客户端被 YouTube 拒绝导致解析不出播放地址
_CLIENT_ARGS = {
    'youtube': {
        'player_client': ['tv', 'android_vr', 'web'],
    },
}


def extract(url, playlist=False):
    opts = dict(_BASE_OPTS)
    opts['noplaylist'] = not playlist
    if playlist:
        # 播放列表只取条目信息；条目 url 用网页地址，播放单集时再解析直链
        opts['extract_flat'] = 'in_playlist'
    try:
        with yt_dlp.YoutubeDL(dict(opts, extractor_args=_CLIENT_ARGS)) as ydl:
            info = ydl.extract_info(url, download=False)
            if playlist or info.get('_type') == 'playlist':
                items = []
                for entry in info.get('entries') or []:
                    if not entry:
                        continue
                    items.append({
                        'name': entry.get('title'),
                        'url': entry.get('webpage_url') or entry.get('url'),
                        'duration': entry.get('duration', 0),
                    })
                return json.dumps({'type': 'playlist', 'items': items}, ensure_ascii=False)
            return json.dumps({'type': 'single', 'url': _pick_url(info)}, ensure_ascii=False)
    except Exception as e:
        return json.dumps({'type': 'error', 'message': str(e)}, ensure_ascii=False)


def _pick_url(info):
    # 已选中格式的直链（format 选择器已优先 progressive 含音视频格式）
    if info.get('url'):
        return info['url']
    formats = info.get('formats') or []
    # 按容器类型偏好挑选 http 直链
    for ext in ('mp4', 'm3u8', 'mpd'):
        for f in formats:
            if _is_http(f) and f.get('ext') == ext and f.get('url'):
                return f['url']
    # 任意 http 直链兜底
    for f in formats:
        if _is_http(f) and f.get('url'):
            return f['url']
    return ''


def _is_http(f):
    # 用 url 前缀判断而非 protocol：HLS/DASH 的 protocol 可能是 m3u8_native 等，但 url 仍是 http(s)
    return (f.get('url') or '').startswith(('http://', 'https://'))

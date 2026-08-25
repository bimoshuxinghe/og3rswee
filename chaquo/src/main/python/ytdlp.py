import json

import yt_dlp

_OPTS = {
    'quiet': True,
    'no_warnings': True,
    'noplaylist': True,
    'skip_download': True,
    'socket_timeout': 20,
    'retries': 2,
}


def extract(url, playlist=False):
    opts = dict(_OPTS)
    opts['noplaylist'] = not playlist
    with yt_dlp.YoutubeDL(opts) as ydl:
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


def _pick_url(info):
    if info.get('url'):
        return info['url']
    formats = info.get('formats') or []
    prefer = ['mp4', 'm3u8', 'mpd']
    for ext in prefer:
        for f in formats:
            if (f.get('protocol') or '').startswith('http') and f.get('ext') == ext and f.get('url'):
                return f['url']
    for f in formats:
        if (f.get('protocol') or '').startswith('http') and f.get('url'):
            return f['url']
    return ''

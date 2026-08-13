import sys
import re
import json
import requests
from base.spider import Spider

class Spider(Spider):
    def getName(self):
        return "爱奇艺首页"

    def init(self, extend=""):
        self.headers = {
            'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36',
            'Accept-Language': 'zh-CN,zh;q=0.9,en;q=0.8',
            'Accept': '*/*',
            'Referer': 'https://www.iqiyi.com/',
        }

    def homeContent(self, filter):
        # 1. 获取爱奇艺首页 HTML 源码
        try:
            response = requests.get('https://www.iqiyi.com/', headers=self.headers, timeout=10)
            html = response.content.decode('utf-8', errors='ignore')
        except Exception as e:
            return {"class": [], "list": []}

        # 2. 匹配获取动态推荐的 API 地址
        api_url_match = re.search(r'src=["\'](//www\.iqiyi\.com/prelw/portal/lw/v7/channel/recommend[^"\']+)["\']', html)
        if api_url_match:
            api_url = 'https:' + api_url_match.group(1)
        else:
            api_url = 'https://www.iqiyi.com/prelw/portal/lw/v7/channel/recommend?lwaFastKey=Page_recommend_1&v=17.063.25600&adExt=%7B%22r%22%3A%222.18.0-ares6-pure%22%7D'

        # 3. 请求推荐 API
        try:
            api_res = requests.get(api_url, headers=self.headers, timeout=10)
            js_code = api_res.text
        except Exception as e:
            return {"class": [], "list": []}

        # 4. 解析页面中携带 of JSON 报文
        start_marker = "qyMesh.preload['Page_recommend_1']= { response: "
        start_pos = js_code.find(start_marker)
        if start_pos == -1:
            return {"class": [], "list": []}
            
        json_start = start_pos + len(start_marker)
        brace_count = 0
        json_end = -1
        for idx in range(json_start, len(js_code)):
            char = js_code[idx]
            if char == '{':
                brace_count += 1
            elif char == '}':
                brace_count -= 1
                if brace_count == 0:
                    json_end = idx + 1
                    break
                    
        if json_end == -1:
            return {"class": [], "list": []}
            
        try:
            context_data = json.loads(js_code[json_start:json_end])
            items = context_data.get('items', [])
        except Exception:
            return {"class": [], "list": []}

        # 5. 寻找到广告场景为 focus 的轮播图项
        focus_item = None
        for item in items:
            temp = item.get('temp', {})
            if temp.get('adScene') == 'focus':
                focus_item = item
                break

        vod_list = []
        if focus_item:
            video_data = focus_item.get('video', [])[0].get('data', [])
            for item in video_data:
                title = item.get('display_name', '').strip() or item.get('title', '').strip()
                subtitle = item.get('desc') or item.get('desc1') or item.get('desc2') or item.get('description') or ""
                subtitle = str(subtitle).strip()
                
                is_ad = item.get('isAd') is True or item.get('isPpcAd') is True or item.get('ad') is not None or "广告" in subtitle
                if is_ad:
                    continue
                
                image = item.get('banner_image_url', '').strip() or item.get('image_url_normal', '').strip() or item.get('image_cover', '').strip()
                link = item.get('page_url', '').strip()
                title_logo = item.get('banner_logo_url', '').strip()
                
                # 将轮播项构造成 CatVod 格式的 Vod 对象
                vod_list.append({
                    "vod_id": link,
                    "vod_name": title,
                    "vod_pic": image,
                    "vod_remarks": subtitle,
                    "vod_tag": title_logo
                })

        # 前三项会自动成为首页的轮播 Banner 图，下面将分类及视频列表返回
        result = {
            "class": [{"type_id": "iqiyi_home", "type_name": "爱奇艺精选"}],
            "list": vod_list
        }
        return result

    def detailContent(self, ids):
        url = ids[0]
        vod = {
            "vod_id": url,
            "vod_name": "爱奇艺精选视频",
            "vod_pic": "",
            "vod_remarks": "第三方解析播放",
            "vod_actor": "未知",
            "vod_director": "未知",
            "vod_content": "此视频是爱奇艺首页推荐内容，点击将尝试通过默认解析接口播放。"
        }
        return {"list": [vod]}

    def playerContent(self, flag, id, vipFlags):
        # 默认解析接口
        play_url = "https://jx.jsonplayer.com/vip/?url=" + id
        return {
            "parse": 1,
            "url": play_url,
            "header": {"User-Agent": "Mozilla/5.0"}
        }

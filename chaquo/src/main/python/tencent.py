import requests
from base.spider import Spider


class Spider(Spider):
    def getName(self):
        return "Tencent Video"

    def init(self, extend=""):
        self.headers = {
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept": "application/json, text/plain, */*",
            "Accept-Language": "zh-CN,zh;q=0.9,en;q=0.8",
            "Origin": "https://v.qq.com",
            "Referer": "https://v.qq.com/channel/tv",
        }

    def homeContent(self, filter):
        try:
            response = requests.post(
                "https://pbaccess.video.qq.com/trpc.vector_layout.page_view.PageService/getPage?video_appid=3000010&vversion_platform=2",
                headers=self.headers,
                json=self._payload(),
                timeout=10,
            )
            data = response.json()
        except Exception:
            return {"class": [], "list": []}

        module = self._find_hot_module(data)
        cards = module.get("children_list", {}).get("list", {}).get("cards", []) if module else []
        vod_list = []
        for card in cards:
            params = card.get("params", {})
            title = self._first(params, "title_pc", "title", "mz_title")
            image = self._first(params, "image_url", "pic_1080x607", "cut_image_url", "vid_image_url")
            cid = self._first(params, "item_id", "cid", "vid")
            if not title or not image or not cid:
                continue
            vod_list.append({
                "vod_id": self._cover_url(cid),
                "vod_name": title,
                "vod_pic": image,
                "vod_remarks": self._first(params, "rec_subtitle", "second_title", "stitle_pc", "rec_normal_reason"),
            })

        return {
            "class": [{"type_id": "tencent_tv_hot", "type_name": "Tencent TV Hot"}],
            "list": vod_list
        }

    def detailContent(self, ids):
        url = ids[0]
        return {"list": [{
            "vod_id": url,
            "vod_name": "Tencent Video",
            "vod_pic": "",
            "vod_remarks": "",
            "vod_content": "Tencent Video recommendation.",
        }]}

    def playerContent(self, flag, id, vipFlags):
        return {
            "parse": 1,
            "url": "https://jx.jsonplayer.com/vip/?url=" + id,
            "header": {"User-Agent": "Mozilla/5.0"}
        }

    def _payload(self):
        return {
            "page_params": {
                "page_type": "channel",
                "page_id": "100113",
                "scene": "channel",
                "new_mark_label_enabled": "1",
                "vl_to_mvl": "",
                "ad_exp_ids": "",
                "ams_cookies": "",
                "ad_trans_data": "{\"ad_request_id\":\"home_recommend\",\"game_sessions\":[]}",
                "skip_privacy_types": "0",
                "support_click_scan": "1",
            },
            "page_bypass_params": {
                "params": {
                    "platform_id": "2",
                    "caller_id": "3000010",
                    "data_mode": "default",
                    "user_mode": "default",
                    "specified_strategy": "",
                    "page_type": "channel",
                    "page_id": "100113",
                    "scene": "channel",
                    "new_mark_label_enabled": "1",
                },
                "scene": "channel",
                "app_version": "",
                "abtest_bypass_id": "",
            },
            "page_context": None,
        }

    def _find_hot_module(self, data):
        for module in data.get("data", {}).get("CardList", []):
            params = module.get("params", {})
            if module.get("type") == "pc_shelves" and params.get("columnTitle") == "重磅热播":
                return module
        for module in data.get("data", {}).get("CardList", []):
            if module.get("type") == "pc_shelves":
                return module
        return None

    def _first(self, params, *keys):
        for key in keys:
            value = str(params.get(key, "")).strip()
            if value:
                return value
        return ""

    def _cover_url(self, cid):
        if cid.startswith("http"):
            return cid
        return "https://v.qq.com/x/cover/" + cid + ".html"

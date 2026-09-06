# YSP 央视频直播代理（psy1 PHP 移植）

`psy1.php`（央视频 CCTV/卫视直播代理）的双语言移植版，已并入本项目：

| 版本 | 位置 | 用途 |
|---|---|---|
| **Python 版** | `other/tools/ysp/ysp_proxy.py` | 本地/服务器独立运行（仅标准库，无第三方依赖） |
| **Java 版** | `app/src/main/java/com/fongmi/android/tv/server/process/Ysp.java` | APK 内置，随应用启动，无需外部主机 |

## Python 版本地运行

```bash
python3 ysp_proxy.py --host 127.0.0.1 --port 19978
```

- 直播：`http://127.0.0.1:19978/ysp?id=cctv1`（返回补全 TS 路径的 m3u8，80 秒内存缓存）
- 回看：`http://127.0.0.1:19978/ysp?id=cctv1&playseek=20260905200000-20260905203000`（302 跳转）
- 频道列表：`http://127.0.0.1:19978/ysp`（不带 id）
- 调试：`http://127.0.0.1:19978/ysp?id=cctv1&debug=1`（返回上游原始 JSON）
- 自检：`python3 ysp_proxy.py --selftest`（cKey 加解密 round-trip）

> 端口可任意指定，默认 `19978`（避开应用内置服务器的 9978）。

## Java 版（APK 内置）

应用启动内置服务器后自动可用，路径与参数和 Python 版一致（端口以应用实际监听为准）：

```
/ysp?id=cctv1
/ysp?id=cctv1&playseek=YYYYMMDDHHMMSS-YYYYMMDDHHMMSS
```

直播源示例（`cctv1,#genre#` 行之后）：

```
央视频CCTV1,http://127.0.0.1:19978/ysp?id=cctv1#
```

## 算法链路（与原 PHP 一致）

1. 频道表 `id → [cnlid, livepid, defn]`
2. 构建二进制数据包（13 字段 + 固定头，偏移 18 写入签名）
3. `oi_symmetry_encrypt2`（TEA-16 轮 CBC 变体，首字节存 pad 长度）加密
4. 追加签名校验和（`sig = (0x83*sig + b) & 0x7FFFFFFF`）
5. XOR（16 字节循环密钥）→ 自定义 Base64（`+/` → `_-`）→ 前缀 `--01` 得 cKey
6. 请求 `bkliveinfo.ysp.cctv.cn` 取 playurl
7. 直播：拉取 m3u8 并把相对 `.ts` 补全为绝对地址；回看：改写域名 + `starttime` 后 302 跳转

## 验证结论

- Python 版对真实 API 实测通过（`iretcode=0`，直播 m3u8 / TS 片段下载 / 回看 302 均正常）
- Java 版与 Python 版做过交叉验证：Java 生成 cKey → Python 解密校验通过（含 guard_time）

## 零配置开机即用（Java 版新增）

- `/ysp?list=live` 动态输出 FongMi 直播源 txt（Host 跟随请求地址，127.0.0.1 / 局域网 IP / 端口顺延均正确）
- `LiveConfig.defaultConfig()` 空直播配置时自动回退到 `http://127.0.0.1:<port>/ysp?list=live`
- 即：装上新 APK，打开 App → 直播页默认就有央视频 73 个频道，无需手动导入直播源

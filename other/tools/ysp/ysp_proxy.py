#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ysp_proxy.py — 央视频(YSP/CCTV)直播代理（PHP psy1 的 Python 版实现）

来源：psy1.php（PHP 版）→ 1:1 翻译为 Python（仅标准库，无第三方依赖）。

功能：
  - GET /ysp?id=cctv1                 直播：返回补全过 TS 路径的 m3u8（80s 内存缓存）
  - GET /ysp?id=cctv1&playseek=YYYYMMDDHHMMSS-YYYYMMDDHHMMSS
                                      回看：302 跳转到回看地址
  - GET /ysp                          返回可用频道列表
  - GET /ysp?id=cctv1&debug=1         调试：返回上游原始 JSON / playurl

运行：
  python3 ysp_proxy.py --host 127.0.0.1 --port 19978

配合 FongMi TV 直播源使用（cctv1,#genre# 行之后）：
  央视频CCTV1,http://127.0.0.1:19978/ysp?id=cctv1#
"""

import argparse
import base64
import json
import random
import re
import ssl
import struct
import sys
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

TZ_SH = timezone(timedelta(hours=8))  # Asia/Shanghai

# ---------------- 频道表：id -> [cnlid, livepid, defn] ----------------
CHANNELS = {
    'cctv1': ['2024078201', '600001859', 'fhd'],        # CCTV-1高清
    'cctv2': ['2024075401', '600001800', 'fhd'],        # CCTV-2高清
    'cctv3': ['2024068501', '600001801', 'fhd'],        # CCTV-3高清
    'cctv4': ['2029797101', '600001814', 'fhd'],        # CCTV-4高清
    'cctv5': ['2024078401', '600001818', 'fhd'],        # CCTV-5高清
    'cctv5p': ['2024078001', '600001817', 'fhd'],       # CCTV-5+高清
    'cctv6': ['2013693901', '600108442', 'fhd'],        # CCTV-6高清
    'cctv7': ['2024072001', '600004092', 'fhd'],        # CCTV-7高清
    'cctv8': ['2029793001', '600001803', 'fhd'],        # CCTV-8高清
    'cctv9': ['2024078601', '600004078', 'fhd'],        # CCTV-9高清
    'cctv10': ['2024078701', '600001805', 'fhd'],       # CCTV-10高清
    'cctv11': ['2027248701', '600001806', 'fhd'],       # CCTV-11高清
    'cctv12': ['2027248801', '600001807', 'fhd'],       # CCTV-12高清
    'cctv13': ['2029797201', '600001811', 'fhd'],       # CCTV-13高清
    'cctv14': ['2027248901', '600001809', 'fhd'],       # CCTV-14高清
    'cctv15': ['2027249001', '600001815', 'fhd'],       # CCTV-15高清
    'cctv16': ['2027249101', '600098637', 'fhd'],       # CCTV-16高清
    'cctv164k': ['2027249301', '600099502', 'fhd'],     # CCTV-16(4K)
    'cctv17': ['2027249401', '600001810', 'fhd'],       # CCTV-17高清
    'cctv4k': ['2029810301', '600002264', 'fhd'],       # CCTV-4K
    'cctv8k': ['2026774101', '600156816', 'fhd'],       # CCTV-8K
    'cgtn': ['2024181701', '600014550', 'fhd'],         # CGTN
    'cgtnfy': ['2024181801', '600084704', 'fhd'],       # CGTN法语频道
    'cgtney': ['2024181901', '600084758', 'fhd'],       # CGTN俄语频道
    'cgtnalby': ['2024182001', '600084782', 'fhd'],     # CGTN阿拉伯语频道
    'cgtnxby': ['2024182101', '600084744', 'fhd'],      # CGTN西班牙语频道
    'cgtnwyjl': ['2024182301', '600084781', 'fhd'],     # CGTN外语纪录频道
    'cctvfyjc': ['2025637103', '600099658', 'shd'],     # CCTV风云剧场频道
    'cctvdyjc': ['2026874203', '600099655', 'shd'],     # CCTV第一剧场频道
    'cctvhjjc': ['2026874303', '600099620', 'shd'],     # CCTV怀旧剧场频道
    'cctvsjdl': ['2026874403', '600099637', 'shd'],     # CCTV世界地理频道
    'cctvfyyy': ['2026874503', '600099660', 'shd'],     # CCTV风云音乐频道
    'cctvbqkj': ['2026874603', '600099649', 'shd'],     # CCTV兵器科技频道
    'cctvfyzq': ['2026966203', '600099636', 'shd'],     # CCTV风云足球频道
    'cctvgeqwq': ['2026874703', '600099659', 'shd'],    # CCTV高尔夫·网球频道
    'cctvnxss': ['2026874803', '600099650', 'shd'],     # CCTV女性时尚频道
    'cctvyswhjp': ['2026874903', '600099653', 'shd'],   # CCTV央视文化精品频道
    'cctvystq': ['2026875003', '600099652', 'shd'],     # CCTV央视台球频道
    'cctvdszn': ['2026875103', '600099656', 'shd'],     # CCTV电视指南频道
    'cctvwsjk': ['2025637003', '600099651', 'shd'],     # CCTV卫生健康频道
    'bjws': ['2024052703', '600002309', 'fhd'],         # 北京卫视
    'jsws': ['2024171103', '600002521', 'fhd'],         # 江苏卫视
    'dfws': ['2024054503', '600002483', 'fhd'],         # 东方卫视
    'zjws': ['2024054703', '600002520', 'fhd'],         # 浙江卫视
    'hnws': ['2024054803', '600002475', 'fhd'],         # 湖南卫视
    'hbws': ['2024171203', '600002508', 'fhd'],         # 湖北卫视
    'gdws': ['2024060903', '600002485', 'fhd'],         # 广东卫视
    'gxws': ['2024060703', '600002509', 'fhd'],         # 广西卫视
    'hljws': ['2029797003', '600002498', 'fhd'],        # 黑龙江卫视
    'hnws2': ['2024055603', '600002506', 'fhd'],        # 海南卫视
    'cqws': ['2024061103', '600002531', 'fhd'],         # 重庆卫视
    'szws': ['2024061303', '600002481', 'fhd'],         # 深圳卫视
    'scws': ['2024061403', '600002516', 'fhd'],         # 四川卫视
    'henanws': ['2029797303', '600002525', 'fhd'],      # 河南卫视
    'fjdnhz': ['2024061503', '600002484', 'fhd'],       # 福建东南卫视
    'gzhws': ['2024061603', '600002490', 'fhd'],        # 贵州卫视
    'jxws': ['2024061703', '600002503', 'fhd'],         # 江西卫视
    'lnws': ['2024171303', '600002505', 'fhd'],         # 辽宁卫视
    'ahws': ['2024171403', '600002532', 'fhd'],         # 安徽卫视
    'hbws2': ['2024171503', '600002493', 'fhd'],        # 河北卫视
    'sdws': ['2029787903', '600002513', 'fhd'],         # 山东卫视
    'tjws': ['2019927003', '600152137', 'fhd'],         # 天津卫视
    'jlws': ['2025561503', '600190405', 'fhd'],         # 吉林卫视
    'shanxiws': ['2029795103', '600190400', 'fhd'],     # 陕西卫视
    'nxws': ['2025608503', '600190737', 'fhd'],         # 宁夏卫视
    'nmgws': ['2025561203', '600190401', 'fhd'],        # 内蒙古卫视
    'ynws': ['2025561303', '600190402', 'fhd'],         # 云南卫视
    'shanxiws2': ['2025560803', '600190407', 'fhd'],    # 山西卫视
    'qhws': ['2025559103', '600190406', 'fhd'],         # 青海卫视
    'xzws': ['2025558003', '600190403', 'fhd'],         # 西藏卫视
    'cetv1': ['2022823801', '600171827', 'fhd'],        # 中国教育电视台1频道
    'gxpd': ['2029360403', '600213139', 'fhd'],         # 国学频道
    'xjws': ['2019927403', '600152138', 'fhd'],         # 新疆卫视
}

# 频道中文名（与 Java 版 Ysp.Channel.NAMES 一致）
CHANNEL_NAMES = {
    'cctv1': 'CCTV-1', 'cctv2': 'CCTV-2', 'cctv3': 'CCTV-3', 'cctv4': 'CCTV-4',
    'cctv5': 'CCTV-5', 'cctv5p': 'CCTV-5+', 'cctv6': 'CCTV-6', 'cctv7': 'CCTV-7',
    'cctv8': 'CCTV-8', 'cctv9': 'CCTV-9', 'cctv10': 'CCTV-10', 'cctv11': 'CCTV-11',
    'cctv12': 'CCTV-12', 'cctv13': 'CCTV-13', 'cctv14': 'CCTV-14', 'cctv15': 'CCTV-15',
    'cctv16': 'CCTV-16', 'cctv164k': 'CCTV-16(4K)', 'cctv17': 'CCTV-17',
    'cctv4k': 'CCTV-4K', 'cctv8k': 'CCTV-8K', 'cgtn': 'CGTN',
    'cgtnfy': 'CGTN法语频道', 'cgtney': 'CGTN俄语频道', 'cgtnalby': 'CGTN阿拉伯语频道',
    'cgtnxby': 'CGTN西班牙语频道', 'cgtnwyjl': 'CGTN外语纪录频道',
    'cctvfyjc': 'CCTV风云剧场频道', 'cctvdyjc': 'CCTV第一剧场频道', 'cctvhjjc': 'CCTV怀旧剧场频道',
    'cctvsjdl': 'CCTV世界地理频道', 'cctvfyyy': 'CCTV风云音乐频道', 'cctvbqkj': 'CCTV兵器科技频道',
    'cctvfyzq': 'CCTV风云足球频道', 'cctvgeqwq': 'CCTV高尔夫·网球频道', 'cctvnxss': 'CCTV女性时尚频道',
    'cctvyswhjp': 'CCTV央视文化精品频道', 'cctvystq': 'CCTV央视台球频道', 'cctvdszn': 'CCTV电视指南频道',
    'cctvwsjk': 'CCTV卫生健康频道',
    'bjws': '北京卫视', 'jsws': '江苏卫视', 'dfws': '东方卫视', 'zjws': '浙江卫视',
    'hnws': '湖南卫视', 'hbws': '湖北卫视', 'gdws': '广东卫视', 'gxws': '广西卫视',
    'hljws': '黑龙江卫视', 'hnws2': '海南卫视', 'cqws': '重庆卫视', 'szws': '深圳卫视',
    'scws': '四川卫视', 'henanws': '河南卫视', 'fjdnhz': '福建东南卫视', 'gzhws': '贵州卫视',
    'jxws': '江西卫视', 'lnws': '辽宁卫视', 'ahws': '安徽卫视', 'hbws2': '河北卫视',
    'sdws': '山东卫视', 'tjws': '天津卫视', 'jlws': '吉林卫视', 'shanxiws': '陕西卫视',
    'nxws': '宁夏卫视', 'nmgws': '内蒙古卫视', 'ynws': '云南卫视', 'shanxiws2': '山西卫视',
    'qhws': '青海卫视', 'xzws': '西藏卫视', 'cetv1': '中国教育电视台1频道',
    'gxpd': '国学频道', 'xjws': '新疆卫视',
}


# ================== CKeyManager ==================
class CKeyManager:
    DELTA = 0x9E3779B9
    ROUNDS = 16
    LOG_ROUNDS = 4
    SALT_LEN = 2
    ZERO_LEN = 7
    TEA_CKEY = bytes.fromhex('59b2f7cf725ef43c34fdd7c123411ed3')
    GUARD_TEA_KEY = bytes.fromhex('110DBEC10C23E7D2E56A1CAD6914EF1B')

    def __init__(self):
        self.xor_key = [0x84, 0x2E, 0xED, 0x08, 0xF0, 0x66, 0xE6, 0xEA,
                        0x48, 0xB4, 0xCA, 0xA9, 0x91, 0xED, 0x6F, 0xF3]
        self.guard_xor_key = [0xB3, 0xC9, 0x53, 0xA0, 0x69, 0x13, 0xAD, 0x4D]
        self.standard_alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/='
        self.custom_alphabet = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-='
        self.guid = ''
        self.generate_guid()

    # ---------------- 随机 GUID ----------------
    def generate_guid(self):
        # 32 位十六进制字符串（不带连字符）
        self.guid = '%08x%04x%04x%04x%012x' % (
            random.randint(0, 0xffffffff), random.randint(0, 0xffff),
            random.randint(0, 0xffff), random.randint(0, 0xffff),
            random.randint(0, 0xffffffffffff))
        if len(self.guid) != 32:
            self.guid = self.guid.rjust(32, '0')
        return self.guid

    # ---------------- 辅助函数 ----------------
    @staticmethod
    def random_hex_str(length):
        return ''.join(random.choice('0123456789ABCDEF') for _ in range(length))

    def spvcode(self, defn):
        height = 2160 if re.search(r'(4k|8k|hdr)', defn, re.I) else 1080
        frame_rates = [30, 60, 90, 120]
        h264_str = ','.join('%d:%d' % (fps, height) for fps in frame_rates)
        h265_str = ','.join('%d:%d' % (fps, height) for fps in frame_rates)
        spvcode_raw = 'H(%s|%s);2(%s|%s)' % (h264_str, h264_str, h265_str, h265_str)
        return base64.b64encode(spvcode_raw.encode()).decode()

    @staticmethod
    def calc_signature(buffer):
        signature = 0
        for b in buffer:
            signature = (0x83 * signature + (b & 0xFF)) & 0x7FFFFFFF
        return signature

    def custom_decode(self, text):
        if not text:
            return b''
        text = text.rstrip('=')
        if len(text) % 4 != 0:
            text += '=' * (4 - len(text) % 4)
        table = str.maketrans(self.custom_alphabet, self.standard_alphabet)
        translated = text.translate(table)
        return base64.b64decode(translated)

    def custom_encode(self, data):
        encoded = base64.b64encode(data).decode()
        table = str.maketrans(self.standard_alphabet, self.custom_alphabet)
        return encoded.translate(table).rstrip('=')

    def xor_array(self, byte_array):
        return [b ^ self.xor_key[i & 0xF] for i, b in enumerate(byte_array)]

    # ---------------- TEA ECB ----------------
    @classmethod
    def _key_words(cls, key):
        return list(struct.unpack('>4I', key[:16]))

    def tea_encrypt_ecb(self, in_buf, key):
        if len(in_buf) < 8:
            in_buf = in_buf.ljust(8, b'\0')
        y, z = struct.unpack('>2I', in_buf[:8])
        k = self._key_words(key)
        s = 0
        for _ in range(self.ROUNDS):
            s = (s + self.DELTA) & 0xFFFFFFFF
            y = (y + ((((z << 4) & 0xFFFFFFFF) + k[0]) ^ ((z + s) & 0xFFFFFFFF) ^ (((z >> 5) + k[1])))) & 0xFFFFFFFF
            z = (z + ((((y << 4) & 0xFFFFFFFF) + k[2]) ^ ((y + s) & 0xFFFFFFFF) ^ (((y >> 5) + k[3])))) & 0xFFFFFFFF
        return struct.pack('>2I', y, z)

    def tea_decrypt_ecb(self, in_buf, key):
        y, z = struct.unpack('>2I', in_buf[:8])
        k = self._key_words(key)
        s = (self.DELTA << self.LOG_ROUNDS) & 0xFFFFFFFF
        for _ in range(self.ROUNDS):
            z = (z - ((((y << 4) & 0xFFFFFFFF) + k[2]) ^ ((y + s) & 0xFFFFFFFF) ^ (((y >> 5) + k[3])))) & 0xFFFFFFFF
            y = (y - ((((z << 4) & 0xFFFFFFFF) + k[0]) ^ ((z + s) & 0xFFFFFFFF) ^ (((z >> 5) + k[1])))) & 0xFFFFFFFF
            s = (s - self.DELTA) & 0xFFFFFFFF
        return struct.pack('>2I', y, z)

    # ---------------- OI CBC（腾讯 oi_symmetry_encrypt2） ----------------
    def oi_symmetry_encrypt2(self, in_buf, key):
        n = len(in_buf)
        pad_salt_body_zero = n + 1 + self.SALT_LEN + self.ZERO_LEN
        padlen = pad_salt_body_zero % 8
        if padlen:
            padlen = 8 - padlen

        out = bytearray()
        src = [0] * 8
        src[0] = (random.randint(0, 255) & 0xF8) | padlen
        src_i = 1

        iv_plain = [0] * 8
        iv_crypt = iv_plain[:]

        def flush():
            nonlocal iv_plain, iv_crypt, src, src_i
            # 与 PHP 顺序一致：src XOR iv_crypt 后 TEA-ECB 加密，输出再 XOR iv_plain
            xored = [src[j] ^ iv_crypt[j] for j in range(8)]
            enc = self.tea_encrypt_ecb(bytes(xored), key)
            tmp = list(enc)
            for j in range(8):
                tmp[j] ^= iv_plain[j]
            out.extend(tmp)
            # 关键：iv_plain 取异或后的 TEA 输入块（PHP 在异或之后赋值 $iv_plain = $src_buf）
            iv_plain = xored
            iv_crypt = tmp
            src = [0] * 8
            src_i = 0

        # 填充 padding
        while padlen:
            src[src_i] = random.randint(0, 255)
            src_i += 1
            padlen -= 1
            if src_i == 8:
                flush()

        # Salt
        i = 0
        while i < self.SALT_LEN:
            if src_i < 8:
                src[src_i] = random.randint(0, 255)
                src_i += 1
                i += 1
            if src_i == 8:
                flush()

        # 主体数据
        idx = 0
        remain = n
        while remain:
            if src_i < 8:
                src[src_i] = in_buf[idx]
                idx += 1
                src_i += 1
                remain -= 1
            if src_i == 8:
                flush()

        # Zero 填充
        i = 0
        while i < self.ZERO_LEN:
            if src_i < 8:
                src[src_i] = 0
                src_i += 1
                i += 1
            if src_i == 8:
                flush()

        # 最后一组
        if src_i > 0:
            for j in range(src_i, 8):
                src[j] = 0
            xored = [src[j] ^ iv_crypt[j] for j in range(8)]
            enc = self.tea_encrypt_ecb(bytes(xored), key)
            tmp = list(enc)
            for j in range(8):
                tmp[j] ^= iv_plain[j]
            out.extend(tmp)

        return bytes(out)

    def oi_symmetry_decrypt2(self, in_buf, key):
        n = len(in_buf)
        if n % 8 != 0 or n < 16:
            return None

        dest = list(self.tea_decrypt_ecb(in_buf[:8], key))
        pad_len = dest[0] & 0x07
        out_len = n - 1 - pad_len - self.SALT_LEN - self.ZERO_LEN
        if out_len < 0:
            return None

        iv_pre_crypt = [0] * 8
        iv_cur_crypt = list(in_buf[:8])
        offset = 8
        dest_i = 1
        dest_i += pad_len  # 跳过 padding

        def next_block():
            nonlocal iv_pre_crypt, iv_cur_crypt, offset, dest, dest_i
            iv_pre_crypt = iv_cur_crypt
            iv_cur_crypt = list(in_buf[offset:offset + 8])
            if offset + 8 > n:
                return False
            dest = [dest[j] ^ iv_cur_crypt[j] for j in range(8)]
            dest = list(self.tea_decrypt_ecb(bytes(dest), key))
            offset += 8
            dest_i = 0
            return True

        # 跳过 salt
        salt_count = 1
        while salt_count <= self.SALT_LEN:
            if dest_i < 8:
                dest_i += 1
            elif dest_i == 8:
                if not next_block():
                    return None
            salt_count += 1

        # 还原明文
        plain = bytearray()
        cnt = out_len
        while cnt > 0:
            if dest_i < 8:
                plain.append(dest[dest_i] ^ iv_pre_crypt[dest_i])
                dest_i += 1
                cnt -= 1
            elif dest_i == 8:
                if not next_block():
                    return None
        return bytes(plain)

    # ---------------- guard time ----------------
    @staticmethod
    def guard_last_five(value):
        value = str(value)
        return value[-5:] if len(value) >= 5 else ''

    def generate_ck_guard_time(self, timestamp, guid,
                                guard_data='-1', package_name='null', process_name='null'):
        body = struct.pack('>I', timestamp)
        for part in (self.guard_last_five(guid), self.guard_last_five(package_name),
                     self.guard_last_five(process_name), guard_data):
            pb = part.encode()
            body += struct.pack('>H', len(pb)) + pb

        plain = struct.pack('>H', len(body)) + body
        checksum = self.calc_signature(plain)

        encrypted = self.oi_symmetry_encrypt2(plain, self.GUARD_TEA_KEY)
        encrypted += struct.pack('>I', checksum)

        out = bytearray(encrypted)
        for i in range(len(out)):
            out[i] ^= self.guard_xor_key[i & 7]
        return bytes(out).hex().upper()

    # ---------------- cKey ----------------
    def encrypt_data_to_ckey(self, data):
        checksum = self.calc_signature(data)
        encrypted = self.oi_symmetry_encrypt2(data, self.TEA_CKEY)
        encrypted += struct.pack('>I', checksum)
        arr = self.xor_array(encrypted)
        return '--01' + self.custom_encode(bytes(arr))

    def decrypt_ckey_to_data(self, ckey):
        raw = self.custom_decode(ckey[4:])
        if not raw:
            return None
        dec = bytes(self.xor_array(raw))
        data = dec[:-4]
        checksum = struct.unpack('>I', dec[-4:])[0]
        plain = self.oi_symmetry_decrypt2(data, self.TEA_CKEY)
        return {'data': plain, 'checksum': checksum}

    def verify_ckey(self, ckey):
        r = self.decrypt_ckey_to_data(ckey)
        if not r or r['data'] is None:
            return False
        return r['checksum'] == self.calc_signature(r['data'])

    # ---------------- 数据包构建 ----------------
    @staticmethod
    def _str16(s):
        b = s.encode() if isinstance(s, str) else s
        return struct.pack('>H', len(b)) + b

    def build_packet(self, p):
        data = bytes.fromhex('0000004200000004000004d2')  # 12字节固定头
        data += struct.pack('>I', p['Platform'])
        data += struct.pack('>I', 0)                      # Signature 占位
        data += struct.pack('>I', p['Timestamp'])
        data += self._str16(p['Sdtfrom'])
        data += self._str16(p['randFlag'])
        data += self._str16(p['appVer'])
        data += self._str16(p['vid'])
        data += self._str16(p['guid'])
        data += struct.pack('>I', 1)                      # part1
        data += struct.pack('>I', 1)                      # isDlna
        data += self._str16('2622783A')                   # uid
        data += self._str16('nil')                        # bundleID
        data += self._str16(p['uuid4'])
        data += self._str16('nil')                        # bundleID1
        data += self._str16('v0.1.000')                   # ckeyVersion
        data += self._str16('com.cctv.yangshipin.app.iphone')
        data += self._str16('4330403')                    # platform_str
        data += self._str16('ex_json_bus')
        data += self._str16('ex_json_vs')
        data += self._str16(p['ck_guard_time'])

        buffer = struct.pack('>H', len(data)) + data
        signature = self.calc_signature(buffer)
        buffer = buffer[:18] + struct.pack('>I', signature) + buffer[22:]
        return buffer

    def generate_ckey(self, cnlid, timestamp=None):
        if timestamp is None:
            timestamp = int(time.time())
        rand_flag = '_zj1A5Gh6QYcxWjIUGos2w=='  # 与 PHP 版一致（硬编码）
        uuid4 = '57eab0c4-2c58-44c6-8ae9-dd2757525dc5'
        ck_guard_time = self.generate_ck_guard_time(timestamp, self.guid)
        params = {
            'Platform': 4330403,
            'Timestamp': timestamp,
            'Sdtfrom': 'dcgh',
            'vid': cnlid,
            'guid': self.guid,
            'appVer': 'V8.22.1035.3031',
            'randFlag': rand_flag,
            'uuid4': uuid4,
            'ck_guard_time': ck_guard_time,
        }
        buffer = self.build_packet(params)
        ckey = self.encrypt_data_to_ckey(buffer)
        return {'ckey': ckey, 'params': params, 'buffer': buffer}

    # ---------------- 上游请求 ----------------
    @staticmethod
    def http_get(url, headers=None, timeout=15):
        ctx = ssl._create_unverified_context()
        h = {'User-Agent': 'qqlive', 'Connection': 'Keep-Alive', 'Accept': 'application/json'}
        if headers:
            h.update(headers)
        req = urllib.request.Request(url, headers=h)
        with urllib.request.urlopen(req, timeout=timeout, context=ctx) as resp:
            return resp.read()

    def make_live_request(self, cnlid, livepid='600001859', defn='fhd', playseek=None):
        self.generate_guid()
        ckey_result = self.generate_ckey(cnlid)
        ckey = ckey_result['ckey']
        params = ckey_result['params']

        flowid = '%04X%04X-%04X-%04X-%04X-%04X%04X%04X_%d' % (
            random.randint(0, 0xffff), random.randint(0, 0xffff), random.randint(0, 0xffff),
            random.randint(0, 0x0fff) | 0x4000, random.randint(0, 0x3fff) | 0x8000,
            random.randint(0, 0xffff), random.randint(0, 0xffff), random.randint(0, 0xffff),
            4330403)

        is_playback = bool(playseek)
        playback_timestamp = None
        if is_playback:
            try:
                start_str = playseek.split('-')[0]
                dt = datetime.strptime(start_str, '%Y%m%d%H%M%S').replace(tzinfo=TZ_SH)
                playback_timestamp = int(dt.timestamp())
            except Exception as e:
                return {'success': False, 'error': '回看时间处理失败: %s' % e, 'playseek': playseek}

        request_params = {
            'atime': '120', 'livepid': livepid, 'cnlid': cnlid,
            'appVer': 'V8.22.1035.3031', 'app_version': '300090', 'caplv': '1',
            'cmd': '2', 'defn': defn, 'device': 'iPhone', 'encryptVer': '4.2',
            'getpreviewinfo': '0', 'hevclv': '33', 'lang': 'zh-Hans_JP',
            'livequeue': '0', 'logintype': '1', 'nettype': '1', 'newnettype': '1',
            'newplatform': '4330403', 'platform': '4330403', 'sdtfrom': 'v3021',
            'spacode': '23', 'spaudio': '1', 'spdemuxer': '6', 'spdrm': '2',
            'spdynamicrange': '7', 'spflv': '1', 'spflvaudio': '1', 'sphdrfps': '60',
            'sphttps': '0',
            'spvcode': 'MSgzMDoyMTYwLDYwOjIxNjB8MzA6MjE2MCw2MDoyMTYwKTsyKDMwOjIxNjAsNjA6MjE2MHwzMDoyMTYwLDYwOjIxNjAp',
            'spvideo': '4', 'stream': '1', 'system': '1', 'sysver': 'ios18.2.1',
            'uhd_flag': '4', 'cKey': ckey, 'guid': self.guid,
            'fntick': str(params['Timestamp']), 'flowid': flowid,
        }

        if is_playback:
            request_params['playbacktime'] = str(playback_timestamp)
            response = self._send_http_request(request_params)
            if response['success'] and response.get('response', {}).get('playurl'):
                return response
            # 第二次尝试：不带 playbacktime，改写域名并加 starttime
            request_params.pop('playbacktime', None)
            response = self._send_http_request(request_params)
            if response['success'] and response.get('response', {}).get('playurl'):
                playurl = self.process_playback_url(response['response']['playurl'], playback_timestamp)
                response['response']['playurl'] = playurl
                return response
            return {'success': False, 'error': '无法获取回看地址', 'playseek': playseek,
                    'response': response.get('response')}
        else:
            request_params['playbacktime'] = '0'
            return self._send_http_request(request_params)

    @staticmethod
    def process_playback_url(playurl, playback_timestamp):
        parts = playurl.split('/')
        if len(parts) >= 3:
            parts[2] = 'tlivecloud-playback-cdn.ysp.cctv.cn/tcloud.cctv.com'
            playurl = '/'.join(parts)
            playurl += ('&' if '?' in playurl else '?') + 'starttime=' + str(playback_timestamp)
        return playurl

    def _send_http_request(self, params):
        url = 'https://bkliveinfo.ysp.cctv.cn'
        query = urllib.parse.urlencode(params)
        try:
            raw = self.http_get(url + '?' + query)
        except Exception as e:
            return {'success': False, 'error': 'cURL错误: %s' % e}
        try:
            data = json.loads(raw.decode('utf-8', 'replace'))
        except Exception:
            return {'success': False, 'error': '无效的JSON响应'}
        if 'iretcode' in data:
            result = {'success': data['iretcode'] == 0, 'iretcode': data['iretcode'],
                      'response': data}
            if data['iretcode'] == 0:
                result['playurl'] = data.get('playurl')
            else:
                result['error'] = data.get('errinfo', '未知错误')
            return result
        return {'success': False, 'error': '无效的JSON响应'}

    def get_play_url(self, cnlid, livepid='600001859', defn='fhd', playseek=None):
        result = self.make_live_request(cnlid, livepid, defn, playseek)
        if result.get('success') and result.get('playurl'):
            return result['playurl']
        return None


# ---------------- 直播 URL 缓存（PHP 用 cookie，Python 用内存） ----------------
class PlayUrlCache:
    TIMEOUT = 80  # 秒

    def __init__(self):
        self._lock = threading.Lock()
        self._data = {}

    def get(self, cid):
        with self._lock:
            entry = self._data.get(cid)
            if entry and (time.time() - entry['time']) <= self.TIMEOUT:
                return entry['url']
        return None

    def set(self, cid, url):
        with self._lock:
            self._data[cid] = {'url': url, 'time': time.time()}

    def drop(self, cid):
        with self._lock:
            self._data.pop(cid, None)


MANAGER = CKeyManager()
CACHE = PlayUrlCache()


def fetch_m3u8(play_url):
    """拉取 m3u8 内容（对应 PHP 的 file_get_contents($playUrl)）"""
    try:
        return MANAGER.http_get(play_url, headers={'Accept': '*/*'}).decode('utf-8', 'replace')
    except Exception:
        return None


def patch_ts_paths(m3u8_content, play_url):
    """补全 TS 相对路径（对应 PHP 的 preg_replace("/(.*?.ts)/i", $baseUrl."$1", ...)）"""
    base_url = play_url[:play_url.rfind('/') + 1]
    return re.sub(r'(.*?\.ts)', lambda m: base_url + m.group(1), m3u8_content, flags=re.I)


# ---------------- HTTP Server ----------------
class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, fmt, *args):
        sys.stderr.write('[%s] %s\n' % (self.log_date_time_string(), fmt % args))

    def _send(self, code, body, content_type='text/plain; charset=utf-8', headers=None):
        if isinstance(body, str):
            body = body.encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Access-Control-Allow-Origin', '*')
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(body)

    def _redirect(self, location):
        self._send(302, '', headers={'Location': location})

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        qs = urllib.parse.parse_qs(parsed.query)

        if parsed.path not in ('/ysp', '/ysp.php'):
            self._send(404, 'Not Found. Use /ysp?id=cctv1')
            return

        cid = (qs.get('id') or [None])[0]
        playseek = (qs.get('playseek') or [None])[0]
        debug = (qs.get('debug') or ['0'])[0] == '1'

        # 动态直播源列表：Host 跟随请求地址（127.0.0.1 / 局域网 IP / 自定义端口均正确）
        if (qs.get('list') or [''])[0] == 'live':
            host = self.headers.get('Host', '127.0.0.1:19978')
            lines = ['央视频,#genre#']
            for key in CHANNELS:
                lines.append('%s,http://%s/ysp?id=%s#' % (CHANNEL_NAMES.get(key, key), host, key))
            self._send(200, '\n'.join(lines) + '\n')
            return

        if cid is None:
            self._send(200, self._channel_list(), content_type='text/plain; charset=utf-8')
            return

        if cid not in CHANNELS:
            self._send(404, '未知频道: %s\n' % cid)
            return

        cnlid, livepid, defn = CHANNELS[cid]
        is_live = not playseek

        if debug:
            result = MANAGER.make_live_request(cnlid, livepid, defn, playseek)
            self._send(200, json.dumps(result, ensure_ascii=False, indent=2)[:20000],
                       content_type='application/json; charset=utf-8')
            return

        # 回看模式：直接 302 跳转（对应 PHP 的 header("Location: ...")）
        if not is_live:
            play_url = MANAGER.get_play_url(cnlid, livepid, defn, playseek)
            if not play_url:
                self._send(500, '获取播放地址失败')
                return
            self._redirect(play_url)
            return

        # 直播模式：带缓存
        play_url = CACHE.get(cid)
        need_refresh = play_url is None

        for attempt in (1, 2):
            if need_refresh:
                play_url = MANAGER.get_play_url(cnlid, livepid, defn, None)
                if not play_url:
                    self._send(500, '获取播放地址失败\n')
                    return
                CACHE.set(cid, play_url)

            m3u8 = fetch_m3u8(play_url)
            if m3u8 is not None:
                self._send(200, patch_ts_paths(m3u8, play_url),
                           content_type='application/vnd.apple.mpegurl')
                return

            # 拉取失败：若是缓存地址则清除后重试
            if not need_refresh:
                CACHE.drop(cid)
                need_refresh = True
            else:
                break

        self._send(502, '无法获取 M3U8 内容，请稍后重试\n')

    @staticmethod
    def _channel_list():
        lines = ['YSP 直播代理（psy1 PHP 的 Python 版）',
                 '用法：/ysp?id=<频道>&playseek=YYYYMMDDHHMMSS-YYYYMMDDHHMMSS',
                 '直播源列表：/ysp?list=live', '', '可用频道：']
        for cid in CHANNELS:
            lines.append('  %-12s %s' % (cid, CHANNEL_NAMES.get(cid, '')))
        return '\n'.join(lines)


def selftest():
    """本地自检：cKey 加解密 round-trip + 数据包解析"""
    m = CKeyManager()
    ckey = m.generate_ckey('2024078201')['ckey']
    assert ckey.startswith('--01'), 'cKey 前缀错误'
    r = m.decrypt_ckey_to_data(ckey)
    assert r and r['data'] is not None, '解密失败'
    assert m.verify_ckey(ckey), '校验和不匹配'
    plain = r['data']
    assert len(plain) > 24, '明文长度异常'
    platform = struct.unpack('>I', plain[14:18])[0]
    ts = struct.unpack('>I', plain[22:26])[0]
    print('[selftest] OK  cKey len=%d  packet_len=%d  platform=%d  timestamp=%d'
          % (len(ckey), len(plain), platform, ts))
    return True


def main():
    ap = argparse.ArgumentParser(description='央视频(YSP)直播代理 - Python 版')
    ap.add_argument('--host', default='127.0.0.1')
    ap.add_argument('--port', type=int, default=19978)
    ap.add_argument('--selftest', action='store_true', help='运行加解密自检后退出')
    args = ap.parse_args()

    if args.selftest:
        selftest()
        return

    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print('[ysp_proxy] listening on http://%s:%d  (try http://%s:%d/ysp?id=cctv1)'
          % (args.host, args.port, args.host, args.port))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == '__main__':
    main()

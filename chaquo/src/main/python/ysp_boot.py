"""内置央视频直播代理启动器（ysp-live v9.5）。

由 Java 侧 YspLive 调用：把 assets 释放出来的 ysp-live-v9.5.py 作为模块加载，
设置好命令行参数后在守护线程中运行其 main()（内部自带 ThreadingHTTPServer，
监听 127.0.0.1:8767，提供 /all.m3u 全频道订阅）。

参数说明：
- script_dir：ysp-live-v9.5.py 所在目录（应用缓存，可读写）
- port：监听端口（固定 8767）
- bind：监听地址（固定 127.0.0.1，仅本机）
- data_dir：状态持久化目录（device-state / proxy-cache-state）
"""
import os
import sys
import threading


def start(script_dir, port=8767, bind="127.0.0.1", data_dir=""):
    script = os.path.join(script_dir, "ysp-live-v9.5.py")
    if not os.path.exists(script):
        raise FileNotFoundError("ysp-live 脚本不存在: " + script)

    # 以独立模块名加载，避免与 chaquo 内部模块名冲突
    from importlib.machinery import SourceFileLoader
    mod = SourceFileLoader("ysp_live_v95", script).load_module()

    # 复刻 PC 版命令行：python3 ysp-live-v9.5.py 8767 --bind 127.0.0.1 --no-engine --data-dir <cache>
    argv = ["ysp-live-v9.5.py", str(int(port)), "--bind", bind, "--no-engine"]
    if data_dir:
        argv += ["--data-dir", data_dir]
    sys.argv = argv

    # main() 内部 serve_forever() 会阻塞，放守护线程随 App 进程存活
    threading.Thread(target=mod.main, name="ysp-live", daemon=True).start()
    return True

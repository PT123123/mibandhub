#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""pairing_qr.py —— 从手机把 MAC + AuthKey 取出来，生成「手环管家」配对二维码

痛点：手环管家配对页要手敲 MAC 和 AuthKey。本脚本把「手机连电脑 → 拉官方 App
日志 → 解析密钥 → 生成二维码」一步做完：屏幕上弹出一张二维码，手机打开
「手环管家」→ 去配对 → 扫码填入，扫一下三个字段就自动填好，一个字符都不用敲。

原理（路线 A 的延伸）：「小米运动健康」一启动就把 huamiAuthKey 明文写进
XiaomiFit.device.log（连手环都不用连）。adb shell 用户带 ext_data_rw 组，
能直接读 /sdcard/Android/data/<包名>/ 里的文件 —— 这条路的细节与排障和
`just fetch`（tools/adb_fetch.sh）完全一致。

用法
----
    python pairing_qr.py                     # 手机已连 adb（USB 调试已授权）
    python pairing_qr.py --serial <serial>   # 多台设备时指定一台
    python pairing_qr.py --log 日志文件       # 不连 adb，直接解析本地日志
    python pairing_qr.py --json              # 只要 JSON（不生成二维码）
    python pairing_qr.py --no-qr             # 只打印文本，不生成二维码
    python pairing_qr.py --selftest          # 离线自检

依赖：二维码生成需要 `segno`（纯 Python，`pip install segno` 即可，无需 Pillow）；
拉日志与解析全部用标准库 + 复用仓库里的 parse_log.py。
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
from typing import List, Optional

# 复用路线 A 的解析逻辑（单一真相；独立拷贝出去跑不了也不怕 —— 报错会提示放回仓库根）
try:
    from parse_log import Hit, _read_source, scan_lines
except ImportError:
    print("错误：需要与 parse_log.py 放在同一目录运行（解析逻辑复用它）。", file=sys.stderr)
    sys.exit(2)

__version__ = "1.0.0"

# 二维码载荷：SHOUHUAN1|MAC|AUTHKEY|NAME
# 手环管家 App 端按同一条规则解析（data/PairingQr.kt），两边一起改。
QR_PREFIX = "SHOUHUAN1"
QR_SEP = "|"

# 官方 App 的日志路径（老包名 com.xiaomi.hm.health 已不更新，但兼容旧机器）
_LOG_DIRS = (
    ("/sdcard/Android/data/com.mi.health/files/log",
     ("XiaomiFit.device.log", "XiaomiFit.main.log", "hmble_log.log")),
    ("/sdcard/Android/data/com.xiaomi.hm.health/files/log",
     ("XiaomiFit.device.log", "XiaomiFit.main.log", "hmble_log.log")),
)

_MISSING_MAC_HINT = "??:??:??:??:??:??"


# ---------------------------------------------------------------------------
# adb
# ---------------------------------------------------------------------------


def _adb(args: List[str], serial: str = "", timeout: int = 60) -> subprocess.CompletedProcess:
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += list(args)
    return subprocess.run(
        cmd, capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=timeout,
    )


def adb_devices() -> List[str]:
    """返回已授权（device 状态）的设备序列号列表。"""
    p = _adb(["devices"], timeout=15)
    if p.returncode != 0:
        raise RuntimeError("adb 执行失败：%s" % (p.stderr.strip() or p.stdout.strip()))
    out = []
    for line in p.stdout.splitlines()[1:]:
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            out.append(parts[0])
    return out


def resolve_serial(serial: str = "") -> str:
    if serial:
        return serial
    devs = adb_devices()
    if not devs:
        raise RuntimeError(
            "没有已授权的 adb 设备。\n"
            "  → 插上 USB、打开「USB 调试」，并在手机上点「允许」。\n"
            "  → 小米 / HyperOS 可能还要额外打开「USB 调试（安全设置）」，\n"
            "    否则在部分 ROM 上读目录会被拦。"
        )
    if len(devs) > 1:
        raise RuntimeError(
            "检测到多台设备，请用 --serial 指定一台：\n  " + "\n  ".join(devs)
        )
    return devs[0]


def pull_logs(serial: str) -> List[str]:
    """把官方 App 的日志拉到临时目录，返回本地文件路径列表。"""
    if not shutil.which("adb"):
        raise RuntimeError("找不到 adb。请安装 Android SDK Platform-Tools 并加进 PATH。")
    tmpdir = tempfile.mkdtemp(prefix="shouhuan_qr_")
    pulled = []
    for dirpath, names in _LOG_DIRS:
        for name in names:
            dst = os.path.join(tmpdir, name)
            p = _adb(["pull", "%s/%s" % (dirpath, name), dst], serial=serial)
            if p.returncode == 0 and os.path.exists(dst):
                pulled.append(dst)
    if not pulled:
        shutil.rmtree(tmpdir, ignore_errors=True)
        raise RuntimeError(
            "没能从手机拉到任何日志。可能原因：\n"
            "  * 手机上没装「小米运动健康」(com.mi.health)，或装了一直没启动过\n"
            "    （密钥在它启动时才写进日志）；\n"
            "  * 这台 ROM 不允许 adb shell 读 Android/data。"
        )
    return pulled


# ---------------------------------------------------------------------------
# 载荷
# ---------------------------------------------------------------------------


def build_payload(mac: str, key: str, name: str = "") -> str:
    """组装二维码文本。name 可空 —— App 端留空时用默认型号名。"""
    return QR_SEP.join((QR_PREFIX, mac, key, name or ""))


def parse_payload(payload: str):
    """自检/调试用：按 App 端同款规则把载荷拆回 (mac, key, name)。"""
    marker = QR_PREFIX + QR_SEP
    if not payload.startswith(marker):
        return None
    parts = payload[len(marker):].split(QR_SEP)
    mac = parts[0] if len(parts) > 0 else ""
    key = parts[1] if len(parts) > 1 else ""
    name = parts[2] if len(parts) > 2 else ""
    return mac, key, name


# ---------------------------------------------------------------------------
# 二维码
# ---------------------------------------------------------------------------


def _segno():
    """segno 懒加载；没装就返回 None。"""
    try:
        import segno  # type: ignore
        return segno
    except ImportError:
        return None


def render_qr_png(payload: str, out_path: str) -> None:
    segno = _segno()
    qr = segno.make(payload, error="m")
    # scale=10 → 每模块 10px，border=4 → 四周 4 模块留白，手机扫码最容易对准
    qr.save(out_path, scale=10, border=4)


def print_qr_terminal(payload: str) -> None:
    """终端里再画一张 ASCII 二维码兜底（图片查看器打不开时也能扫）。"""
    segno = _segno()
    qr = segno.make(payload, error="m")
    qr.terminal(module_size=2, border=2)


def open_image(path: str) -> None:
    if sys.platform.startswith("win"):
        os.startfile(path)
    elif sys.platform == "darwin":
        subprocess.Popen(["open", path])
    else:
        subprocess.Popen(["xdg-open", path])


# ---------------------------------------------------------------------------
# 主流程
# ---------------------------------------------------------------------------


def _dedupe(hits: List[Hit]) -> List[Hit]:
    seen = set()
    out = []
    for h in hits:
        pair = (h.key, h.mac)
        if pair in seen:
            continue
        seen.add(pair)
        out.append(h)
    return out


def choose_hit(hits: List[Hit]) -> Hit:
    if len(hits) == 1:
        return hits[0]
    print("解析到 %d 台设备：" % len(hits), file=sys.stderr)
    for i, h in enumerate(hits):
        print("  [%d] %s  MAC=%s  Key=%s…%s"
              % (i, h.name or "(未知型号)", h.mac, h.key[:6], h.key[-4:]),
              file=sys.stderr)
    try:
        choice = input("选哪台（默认 0）：")
    except EOFError:
        choice = ""
    idx = int(choice) if choice.isdigit() and 0 <= int(choice) < len(hits) else 0
    return hits[idx]


def run(args) -> int:
    if args.log:
        sources = list(args.log)
    else:
        try:
            serial = resolve_serial(args.serial)
            sources = pull_logs(serial)
        except RuntimeError as e:
            print("错误：%s" % e, file=sys.stderr)
            return 1
        print("设备：%s" % serial, file=sys.stderr)
        print("已拉取 %d 个日志文件，开始解析…" % len(sources), file=sys.stderr)

    hits: List[Hit] = []
    for path in sources:
        try:
            hits.extend(scan_lines(_read_source(path), args.mac_lookback))
        except OSError as e:
            print("错误：读取失败 %s：%s" % (path, e), file=sys.stderr)
            return 1
    hits = _dedupe(hits)

    if not hits:
        print(
            "日志里没找到密钥。排查清单：\n"
            "  1) 「小米运动健康」只要启动过就会写 huamiAuthKey —— 手机上打开它\n"
            "     一次（手环不需要连着）再重跑本脚本；\n"
            "  2) 确认登录的是绑定了手环的那个小米账号；\n"
            "  3) 用 --log 直接喂日志文件排查：python pairing_qr.py --log 日志路径。",
            file=sys.stderr,
        )
        return 1

    if args.json:
        rows = []
        for h in hits:
            d = h.as_dict()
            mac = h.mac if h.mac and h.mac != _MISSING_MAC_HINT else ""
            d["payload"] = build_payload(mac, h.key, h.name)
            rows.append(d)
        print(json.dumps(rows, ensure_ascii=False, indent=2))
        return 0

    hit = choose_hit(hits)

    if not hit.mac or hit.mac == _MISSING_MAC_HINT:
        print("错误：日志里找到了密钥，但没配对到 MAC（换一台日志更全的设备，"
              "或用 --log 指定含 MAC 的日志）。", file=sys.stderr)
        return 1

    payload = build_payload(hit.mac, hit.key, hit.name)

    print()
    print("解析到设备：%s" % (hit.name or "(未知型号)"))
    print("  MAC    ：%s" % hit.mac)
    print("  AuthKey：%s" % hit.key)
    print("  来源   ：%s（日志第 %d 行）" % (hit.source, hit.line_no))
    print()
    print("--- 纯文本（手动填也行） ---")
    print("%s    %s" % (hit.mac, hit.key))

    if args.no_qr:
        return 0

    if _segno() is None:
        print()
        print("（没有 segno，跳过二维码生成。装一下即可：pip install segno）",
              file=sys.stderr)
        return 1

    out_png = os.path.join(os.getcwd(), "pairing_qr.png")
    try:
        render_qr_png(payload, out_png)
        open_image(out_png)
    except Exception as e:  # 图片打开失败不致命，终端 ASCII 兜底
        print("（图片打开失败：%s）" % e, file=sys.stderr)

    print()
    print("二维码已生成：%s（已用图片查看器打开）" % out_png)
    print()
    print("手机操作：打开「手环管家」→ 设备 → 去配对 → 扫码填入 →")
    print("对着电脑屏幕上的二维码扫一下，MAC / AuthKey 会自动填好，直接点保存。")
    print()
    print("--- 终端里的备用二维码（图片窗口若扫不动，扫这个） ---")
    try:
        print_qr_terminal(payload)
    except Exception as e:
        print("（终端二维码输出失败：%s）" % e, file=sys.stderr)

    return 0


def selftest() -> int:
    """离线自检：不需要手机、不联网。"""
    failures = []
    hex32 = "0123456789abcdef0123456789abcdef"

    def check(name: str, got, want) -> None:
        ok = got == want
        if not ok:
            failures.append("%s\n    期望：%r\n    实际：%r" % (name, want, got))
        print("  %s %s" % ("OK  " if ok else "FAIL", name))

    print("自检 1/3  载荷格式（与 App 端 PairingQr.kt 同规则）")
    payload = build_payload("AA:BB:CC:DD:EE:FF", "0x" + hex32, "小米手环5")
    check("标准载荷",
          payload, "SHOUHUAN1|AA:BB:CC:DD:EE:FF|0x%s|小米手环5" % hex32)
    check("名字留空", build_payload("AA:BB:CC:DD:EE:FF", "0x" + hex32),
          "SHOUHUAN1|AA:BB:CC:DD:EE:FF|0x%s|" % hex32)
    check("往返解析", parse_payload(payload),
          ("AA:BB:CC:DD:EE:FF", "0x" + hex32, "小米手环5"))
    check("前缀不符拒绝", parse_payload("https://example.com/x"), None)

    print("自检 2/3  解析复用（parse_log.py 可导入）")
    from parse_log import Hit  # noqa: F401
    check("parse_log 导入", True, True)

    print("自检 3/3  二维码生成（segno，可跳过）")
    if _segno() is None:
        print("  SKIP  未安装 segno —— 生成二维码需要：pip install segno")
    else:
        tmp = tempfile.mktemp(suffix=".png")
        try:
            render_qr_png("SHOUHUAN1|AA:BB:CC:DD:EE:FF|0x%s|小米手环5" % hex32, tmp)
            check("PNG 生成且非空", os.path.getsize(tmp) > 0, True)
        finally:
            if os.path.exists(tmp):
                os.remove(tmp)

    print()
    if failures:
        print("自检失败 %d 项：" % len(failures))
        for f in failures:
            print("  - " + f)
        return 1
    print("自检全部通过。")
    return 0


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="pairing_qr.py",
        description="从手机（adb）拉官方日志解析 MAC/AuthKey，生成「手环管家」配对二维码。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例：\n"
               "  python pairing_qr.py                 # 手机已连 adb\n"
               "  python pairing_qr.py --serial 192.168.1.5:5555\n"
               "  python pairing_qr.py --log XiaomiFit.device.log\n"
               "  python pairing_qr.py --json\n"
               "  python pairing_qr.py --selftest\n",
    )
    p.add_argument("--serial", default="", help="adb 设备序列号（多台设备时必填）")
    p.add_argument("--log", action="append", default=[],
                   help="直接解析本地日志（可传多个），不走 adb")
    p.add_argument("--json", action="store_true", help="JSON 输出，不生成二维码")
    p.add_argument("--no-qr", action="store_true", help="只打印文本，不生成二维码")
    p.add_argument("--mac-lookback", type=int, default=300,
                   help="向回找多少行配对 MAC（默认：%(default)s）")
    p.add_argument("--selftest", action="store_true", help="跑离线自检后退出")
    p.add_argument("--version", action="version", version="%(prog)s " + __version__)
    return p


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    if args.selftest:
        return selftest()
    if args.json and args.no_qr:
        args.no_qr = False  # --json 本来就是纯文本输出，两者不冲突
    return run(args)


if __name__ == "__main__":
    sys.exit(main())

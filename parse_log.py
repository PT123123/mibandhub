#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""parse_log.py —— 路线 A：从官方 App 调试日志里抠出蓝牙 AuthKey

思路（计划书 §A）：官方「小米运动健康」把蓝牙配对密钥写进自己的调试日志，
触发「导出日志」后，在日志里搜 ``encryptKey=`` 就能拿到 32 位十六进制密钥。
本脚本把「搜 + 配对 MAC + 规范化成 0x... + 输出」自动化。

用法
----
    # 1) 直接读安卓标准日志路径（Termux 上多半读不到，见下方「分区存储」）
    python parse_log.py

    # 2) 读你从 MT 管理器 / SAF 拷出来的日志（推荐）
    python parse_log.py ~/storage/downloads/XiaomiFit.device.log

    # 3) 管道
    cat XiaomiFit.device.log | python parse_log.py -

    # 4) 自动扫描常见位置
    python parse_log.py --find

    # 5) 只要密钥本身（方便喂给别的脚本）
    python parse_log.py 日志文件 --keys-only

关于「分区存储」（Android 11+，计划书 §A3 关键工程点）
------------------------------------------------------
`/storage/emulated/0/Android/data/com.mi.health/files/log/` 属于**别的 App 的**
外部私有目录。Android 11 起普通 App 无法直接读，Termux 也一样（除非 root 或
授予 MANAGE_EXTERNAL_STORAGE）。最省事的正规做法：

  用 MT 管理器（或系统文件管理器的「所有文件访问」）把日志**复制/分享**到
  自己的可见目录（如 Download），再用本脚本解析 —— 即计划书说的走 SAF。
本脚本检测到「路径存在但打不开」时会明确提示这一点，不会让你瞎猜。
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from typing import Dict, Iterable, Iterator, List, Optional, Tuple

__version__ = "1.0.0"

# ---------------------------------------------------------------------------
# 复用主程序里的规范化逻辑（保持单一真相；独立运行时退化为本地精简版）
# ---------------------------------------------------------------------------

_HEX32 = "0123456789abcdef"

try:
    from xiaomi_authkey import normalize_auth_key

    _NORM_SOURCE = "xiaomi_authkey.normalize_auth_key"
except Exception:  # 单文件拷贝出去时也能用
    def normalize_auth_key(raw):  # type: ignore[misc]
        if raw is None:
            return ""
        s = str(raw).strip().strip('"').strip("'")
        if s[:2].lower() == "0x":
            s = s[2:]
        for sep in (":", "-", " ", "_"):
            s = s.replace(sep, "")
        if not s:
            return ""
        lower = s.lower()
        if any(ch not in _HEX32 for ch in lower):
            return ""
        if len(lower) > 32:
            lower = lower[-32:]
        return "0x" + lower.rjust(32, "0")

    _NORM_SOURCE = "本地兜底实现"


# ---------------------------------------------------------------------------
# 匹配规则
# ---------------------------------------------------------------------------

# 字段名与值之间的分隔：``=`` 或 ``:``，两侧都可能带引号。
#
# 这里踩过一个真实 bug：早先把 ``"?`` 只写在冒号**之后**，于是
# ``"huamiAuthKey":"c0ffee..."`` 这种 JSON 键匹配不上 —— 键名后面紧跟的是
# 键的**闭合引号**而不是冒号。当时靠单写一条 ``encryptKey(json)`` 打补丁，
# 但 huamiAuthKey 没写 json 变体，就漏了。统一成 _SEP 后一条线索吃下所有形态。
_SEP = r"[\"']?\s*[=:]\s*[\"']?"

# 优先级从上到下；label 会出现在输出里，方便你知道密钥是从哪条线索抠出来的
PATTERNS: List[Tuple[str, "re.Pattern[str]"]] = [
    # 【实测最强线索】某红米机型 / 小米运动健康 3.48.3 上验证：
    #   DeviceInfo(device=Device(did='huami.xxx', model='hmpace.bracelet.v5',
    #       name='小米手环5', detail=Detail(mac=AA:BB:CC:DD:EE:FF, sn=xxx)),
    #       ..., huamiAuthKey=0123456789abcdef0123456789abcdef, ...)
    # 关键优势：密钥与 MAC 在**同一行**且无歧义配对，不需要靠 lookback 猜；
    # 且**无需**手动触发「导出日志」—— App 一启动就会把这些写进 device.log。
    ("huamiAuthKey", re.compile(r"huamiAuthKey" + _SEP + r"([0-9a-fA-F]{32})", re.I)),
    # 计划书 §A 的主线索：encryptKey=<32hex>（要手动触发「导出日志」才出现）
    ("encryptKey", re.compile(r"encryptKey" + _SEP + r"([0-9a-fA-F]{32})", re.I)),
    # 其他常见字段名。必须 re.I：huamiAuthKey 这类驼峰字段以前匹配不到，
    # 就是因为开头 auth 是大小写敏感的（[Kk] 只补了 K，没补 A）。
    ("authKey", re.compile(r"auth[_]?key" + _SEP + r"([0-9a-fA-F]{32})", re.I)),
    # 最后兜底：additionalInfo 里带 key 的 JSON 片段
    ("additionalInfo", re.compile(r"additionalInfo" + _SEP + r"(\{.*?auth_key.*?\})", re.I)),
]

MAC_RE = re.compile(r"\b([0-9A-Fa-f]{2}(?:[:-][0-9A-Fa-f]{2}){5})\b")

# 设备名/型号：实测与密钥在**同一行**，抽出来省得用户在 Gadgetbridge 里选错型号。
# 例：name='小米手环5   ' model='hmpace.bracelet.v5'（值可能带尾随空格，故 .strip()）
NAME_RE = re.compile(r"name='([^']*)'")
MODEL_RE = re.compile(r"model='([^']*)'")

# 日志里这些 MAC 是手机/本机自身，不是手环，别拿来配对
_SELF_MAC_HINTS = ("02:00:00:00:00:00",)

DEFAULT_LOG_PATHS = [
    "/storage/emulated/0/Android/data/com.mi.health/files/log/XiaomiFit.device.log",
    "/storage/emulated/0/Android/data/com.mi.health/files/log/XiaomiFit.android.log",
    "/storage/emulated/0/Android/data/com.xiaomi.hm.health/files/log/XiaomiFit.device.log",
    "/sdcard/Android/data/com.mi.health/files/log/XiaomiFit.device.log",
    "~/storage/downloads/XiaomiFit.device.log",
    "~/XiaomiFit.device.log",
]


class Hit:
    """一条命中：密钥 + 它的线索来源 + 上下文中的 MAC 与设备元信息。"""

    __slots__ = ("raw_key", "key", "source", "mac", "line_no", "name", "model")

    def __init__(self, raw_key: str, source: str, mac: str, line_no: int,
                 name: str = "", model: str = "") -> None:
        self.raw_key = raw_key
        self.key = normalize_auth_key(raw_key)
        self.source = source
        self.mac = mac
        self.line_no = line_no
        # 设备名/型号取自**同一行**（如 name='小米手环5' model='hmpace.bracelet.v5'）。
        # Gadgetbridge 里必须先选对型号才能填 key，带上这两个字段省得来回翻日志。
        self.name = name
        self.model = model

    def as_dict(self) -> Dict[str, object]:
        return {
            "auth_key": self.key,
            "mac_address": self.mac or "??:??:??:??:??:??",
            "device_name": self.name,
            "model": self.model,
            "source": self.source,
            "line": self.line_no,
        }


def _extract_from_json_blob(blob: str, source: str, mac: str, line_no: int) -> Optional[Hit]:
    """additionalInfo 命中后，再往里挖 auth_key / encryptKey。"""
    try:
        data = json.loads(blob)
    except ValueError:
        return None
    if not isinstance(data, dict):
        return None
    for field in ("auth_key", "authKey", "encryptKey"):
        if field in data:
            hit = Hit(str(data[field]), source + "/" + field, mac, line_no)
            if hit.key:
                return hit
    return None


def _looks_like_real_key(key: str) -> bool:
    """排掉 all-0 / all-f 之类的占位符。

    真随机 32 位 hex 的字符种类数几乎不可能低于 8，所以按此收紧是安全的；
    反而能挡掉日志里 ``encryptKey=00000000000000000000000000000000`` 这类噪音。
    """
    if not key or len(key) != 34:      # "0x" + 32
        return False
    body = key[2:]
    return len(set(body)) >= 8


def scan_lines(lines: Iterable[str], mac_lookback: int = 300) -> List[Hit]:
    """扫描日志行，返回去重后的命中列表。

    MAC 配对策略：密钥行本身若含 MAC 就用它；否则用**之前 mac_lookback 行内**
    最近出现过的 MAC（日志通常先打印设备信息再打印密钥）。
    """
    hits: List[Hit] = []
    seen = set()
    last_mac = ""
    last_mac_line = -10 ** 9

    for idx, line in enumerate(lines, start=1):
        line = line.rstrip("\r\n")
        # 日志里嵌 JSON 时引号是转义的（\"auth_key\":\"...\"）。先还原成普通引号，
        # 这样 JSON 形态的匹配规则就能统一生效，不用为转义形式再写一套正则。
        if '\\"' in line:
            line = line.replace('\\"', '"')

        line_macs = [m for m in MAC_RE.findall(line) if m.lower() not in _SELF_MAC_HINTS]
        if line_macs:
            last_mac = line_macs[-1].upper()
            last_mac_line = idx
        ctx_mac = line_macs[-1].upper() if line_macs else (
            last_mac if idx - last_mac_line <= mac_lookback else ""
        )

        for label, pattern in PATTERNS:
            for m in pattern.finditer(line):
                raw = m.group(1)
                if label == "additionalInfo":
                    hit = _extract_from_json_blob(raw, label, ctx_mac, idx)
                else:
                    hit = Hit(raw, label, ctx_mac, idx)
                    if not hit.key:
                        hit = None
                if hit is None:
                    continue
                if not _looks_like_real_key(hit.key):
                    continue
                # 元信息只在真正命中时才抽，避免对每一行都多跑两次正则
                m_name = NAME_RE.search(line)
                m_model = MODEL_RE.search(line)
                if m_name:
                    hit.name = m_name.group(1).strip()
                if m_model:
                    hit.model = m_model.group(1).strip()
                dedup_key = (hit.key, hit.mac)
                if dedup_key in seen:
                    continue
                seen.add(dedup_key)
                hits.append(hit)
    return hits


# ---------------------------------------------------------------------------
# 输入来源
# ---------------------------------------------------------------------------


def _expand(path: str) -> str:
    return os.path.expanduser(path)


def _read_source(src: str) -> Iterator[str]:
    """src 为 '-' 时读 stdin，否则读文件。"""
    if src == "-":
        for line in sys.stdin:
            yield line
        return
    path = _expand(src)
    if not os.path.exists(path):
        raise FileNotFoundError(path)
    if not os.access(path, os.R_OK):
        raise PermissionError(path)
    with open(path, "r", encoding="utf-8", errors="replace") as fh:
        for line in fh:
            yield line


def find_logs() -> List[str]:
    found = []
    for p in DEFAULT_LOG_PATHS:
        real = _expand(p)
        if os.path.exists(real):
            found.append(real)
    return found


def _explain_oserror(exc: OSError) -> str:
    if isinstance(exc, FileNotFoundError):
        return ("找不到文件：%s\n  提示：确认路径拼写；或先用 --find 看看有哪些位置存在。"
                % exc.filename)
    if isinstance(exc, PermissionError):
        return (
            "没有读取权限：%s\n"
            "  → 这就是计划书 §A3 说的**分区存储**限制：Android 11+ 下普通 App 读不了\n"
            "    别的 App 在 Android/data/ 里的文件，Termux 同样读不了（除非 root 或\n"
            "    授予「所有文件访问」）。\n"
            "  → 正规做法：用 MT 管理器 / 系统文件管理器把日志复制或分享到\n"
            "    自己的可见目录（如 Download），再：\n"
            "        python parse_log.py ~/storage/downloads/<日志文件名>" % exc.filename
        )
    return "读取失败：%s" % exc


# ---------------------------------------------------------------------------
# 输出
# ---------------------------------------------------------------------------


def render(hits: List[Hit], mode: str) -> str:
    if mode == "json":
        return json.dumps([h.as_dict() for h in hits], ensure_ascii=False, indent=2)

    if mode == "keys-only":
        return "\n".join(h.key for h in hits)

    if not hits:
        return (
            "没找到任何密钥。排查清单：\n"
            "  1) 看看日志里到底有什么字段（实测 huamiAuthKey 最常见）：\n"
            "       grep -a -i 'authkey\\|encryptkey' 日志文件 | head\n"
            "  2) 「小米运动健康」只要启动过就会写 huamiAuthKey，**不需要**手动导出日志；\n"
            "     只有走 encryptKey 线索时才需要：「我的」→「关于」→ 连续猛点页面图标\n"
            "     7~10 次，直到出现「Log 迁移至…」提示。\n"
            "  3) 换方式二（计划书 §A2）：装「小米健康研究」，连上手环后再触发日志。\n"
            "  4) App 大版本升级可能改了字段名 —— 用 -v 看看本脚本支持哪些线索。"
        )

    lines: List[str] = []
    for i, h in enumerate(hits):
        lines.append("命中 %d：%s" % (i, h.source))
        if h.name:
            lines.append("  设备   ：%s%s" % (h.name, ("  (%s)" % h.model) if h.model else ""))
        lines.append("  AuthKey：%s" % h.key)
        lines.append("  MAC    ：%s" % (h.mac or "(日志里没找到配对的 MAC)"))
        lines.append("  位置   ：第 %d 行" % h.line_no)
        lines.append("")
    lines.append("填进 Gadgetbridge：先选对上面的型号，再把 AuthKey 整串粘贴到「Auth key」字段。")
    lines.append("手环同一时间只能连一个 App —— 请先停用/退出「小米运动健康」。")
    lines.append("")
    lines.append("--- 纯文本 ---")
    for h in hits:
        lines.append("%s    %s" % (h.mac or "??:??:??:??:??:??", h.key))
    return "\n".join(lines)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="parse_log.py",
        description="从「小米运动健康」调试日志里解析手环蓝牙 AuthKey（路线 A）。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="示例：\n"
               "  python parse_log.py                      # 读安卓默认日志路径\n"
               "  python parse_log.py ~/storage/downloads/XiaomiFit.device.log\n"
               "  python parse_log.py *.log                # 多个日志一起（自动跨文件去重）\n"
               "  cat log.txt | python parse_log.py -\n"
               "  python parse_log.py --find               # 扫描常见位置\n"
               "  python parse_log.py log.txt --json\n",
    )
    p.add_argument("path", nargs="*",
                   help="日志文件路径（可传多个，会合并去重）；'-' 表示从 stdin 读")
    p.add_argument("--find", action="store_true", help="扫描常见日志位置后退出")
    p.add_argument("--json", action="store_true", help="JSON 输出")
    p.add_argument("--keys-only", action="store_true", help="只输出密钥")
    p.add_argument("--mac-lookback", type=int, default=300,
                   help="向回找多少行配对 MAC（默认：%(default)s）")
    p.add_argument("--source", default=None, help="只输出某个线索来源的命中")
    p.add_argument("-v", "--verbose", action="store_true", help="打印扫描细节并列出支持的线索")
    p.add_argument("--version", action="version", version="%(prog)s " + __version__)
    return p


def main(argv: Optional[List[str]] = None) -> int:
    args = build_parser().parse_args(argv)

    if args.verbose:
        print("auth_key 规范化实现来源：%s" % _NORM_SOURCE, file=sys.stderr)
        print("支持的日志线索（标签 → 正则）：", file=sys.stderr)
        for label, pat in PATTERNS:
            print("  %-18s %s" % (label, pat.pattern), file=sys.stderr)
        print(file=sys.stderr)

    if args.find:
        found = find_logs()
        if not found:
            print("常见位置都没找到日志。已知默认路径：")
            for p in DEFAULT_LOG_PATHS:
                print("  %s" % p)
            print("\n→ 先按计划书 §A2 在官方 App 里触发「导出日志」，再回来 --find。")
            return 1
        print("找到这些日志：")
        for p in found:
            size = os.path.getsize(p) if os.access(p, os.R_OK) else -1
            readable = "可读" if size >= 0 else "存在但不可读（分区存储限制）"
            print("  %s  [%s]" % (p, readable))
        print("\n解析示例：python parse_log.py \"<上面的路径>\"")
        return 0

    sources: List[str] = []
    if args.path:
        sources = list(args.path)
    else:
        sources = find_logs()
        if not sources:
            print("没给路径，也没在常见位置找到日志。\n"
                  "→ 用 python parse_log.py --find 查看已知位置，"
                  "或直接把路径当参数传进来。", file=sys.stderr)
            return 1
        print("未指定路径，自动使用：%s" % sources[0], file=sys.stderr)

    raw_hits: List[Hit] = []
    for src in sources:
        try:
            raw_hits.extend(scan_lines(_read_source(src), args.mac_lookback))
        except OSError as e:
            print(_explain_oserror(e), file=sys.stderr)
            return 1
        except UnicodeDecodeError as e:
            print("编码异常（日志可能是二进制）：%s" % e, file=sys.stderr)
            return 1

    # 跨文件去重。同一台设备会在 device.log / main.log / hmble_log.log 里
    # 都留痕，而 scan_lines 的去重集合是单次调用局部的 —— 不在这里再收一次，
    # 一次传多个日志就会把同一台设备重复列出。
    all_hits: List[Hit] = []
    seen_pairs = set()
    for h in raw_hits:
        pair = (h.key, h.mac)
        if pair in seen_pairs:
            continue
        seen_pairs.add(pair)
        all_hits.append(h)

    if args.source:
        all_hits = [h for h in all_hits if h.source == args.source]

    mode = "json" if args.json else ("keys-only" if args.keys_only else "text")
    print(render(all_hits, mode))

    if args.verbose and all_hits:
        print("\n[verbose] 共 %d 条去重命中，来自 %d 个文件。"
              % (len(all_hits), len(sources)), file=sys.stderr)

    return 0 if all_hits else 1


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""打包可分发产物：``dist/shouhuan-authkey-<version>.zip``

只依赖标准库（本机没有 `zip` 命令，用 zipfile 自己打）。
产物刻意精简成「直接拷到手机上就能跑」的那几样东西：

    xiaomi_authkey.py   路线 B 主程序
    parse_log.py        路线 A 日志解析器
    termux/             一键脚本 + 安装器
    README.md           说明（含协议差异警告）

不带 tests/ 和 tools/——那是在开发机上用的，手机不需要。
"""

from __future__ import annotations

import hashlib
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

FILES = [
    "xiaomi_authkey.py",
    "parse_log.py",
    "README.md",
    ".gitattributes",
    # 电脑端封装脚本
    "tools/adb_fetch.sh",     # adb 直读手机日志（配套 parse_log.py）
    "tools/setup_phone.sh",   # 给手机装 Gadgetbridge 并配好连手环所需权限
]
DIRS = ["termux"]

# 固定时间戳 → 同样内容产出同样字节，便于比对（可复现构建）
_FIXED_DATE = (2024, 1, 1, 0, 0, 0)


def collect(root: Path) -> list[Path]:
    """按固定顺序收集要打包的文件。"""
    out: list[Path] = []
    for name in FILES:
        p = root / name
        if p.is_file():
            out.append(p)
    for name in DIRS:
        d = root / name
        if d.is_dir():
            out.extend(sorted(
                p for p in d.rglob("*")
                if p.is_file() and "__pycache__" not in p.parts
            ))
    return out


def main() -> int:
    tool = ROOT / "xiaomi_authkey.py"
    if not tool.is_file():
        print("错误：找不到 %s，请在项目根目录下运行。" % tool, file=sys.stderr)
        return 1

    sys.path.insert(0, str(ROOT))
    try:
        import xiaomi_authkey
    except Exception as exc:                       # 导入失败不该静默
        print("错误：无法导入 xiaomi_authkey（%s）" % exc, file=sys.stderr)
        return 1

    version = getattr(xiaomi_authkey, "__version__", "0.0.0")
    arc_prefix = "shouhuan-authkey-%s" % version

    members = collect(ROOT)
    if not members:
        print("错误：打包清单为空。", file=sys.stderr)
        return 1

    dist = ROOT / "dist"
    dist.mkdir(parents=True, exist_ok=True)
    archive = dist / ("%s.zip" % arc_prefix)

    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as zf:
        for path in members:
            rel = path.relative_to(ROOT).as_posix()
            info = zipfile.ZipInfo("%s/%s" % (arc_prefix, rel), date_time=_FIXED_DATE)
            info.compress_type = zipfile.ZIP_DEFLATED
            # .sh 给可执行位，避免解压后还要 chmod
            info.external_attr = (0o755 if path.suffix == ".sh" else 0o644) << 16
            zf.writestr(info, path.read_bytes())

    payload = archive.read_bytes()
    digest = hashlib.sha256(payload).hexdigest()
    (dist / ("%s.zip.sha256" % arc_prefix)).write_text(
        "%s  %s.zip\n" % (digest, arc_prefix), encoding="utf-8"
    )

    print("      产物：%s" % archive.relative_to(ROOT).as_posix())
    print("      大小：%.1f KiB   文件数：%d" % (len(payload) / 1024.0, len(members)))
    print("      sha256：%s" % digest)
    print("      内容：")
    for path in members:
        print("        %s" % path.relative_to(ROOT).as_posix())
    print()
    print("      拷到手机后：")
    print("        unzip %s.zip" % arc_prefix)
    print("        bash %s/termux/install.sh" % arc_prefix)
    return 0


if __name__ == "__main__":
    sys.exit(main())

#!/usr/bin/env bash
# 从已经用 USB / adb 连上的安卓手机里，直接提取小米手环的 AuthKey。
#
# 为什么这条路最省事（2026-09 在 Redmi Note 12T Pro / HyperOS / Android 15 实测）：
#   * 不需要 root —— 安卓上 adb shell 用户带 ext_data_rw 组，可以直接读
#     /sdcard/Android/data/<包名>/ 里的文件。普通 App 和 Termux 读不到，
#     这正是计划书 §A3 说的「分区存储」限制；adb 绕开了它。
#   * 不需要在手机上点「导出日志」—— 小米运动健康一启动就把 huamiAuthKey
#     明文写进 XiaomiFit.device.log（连手环都不需要连着）。
#   * 不需要 MT 管理器 / SAF 选文件，全程零手机端操作。
#
# 用法：
#   bash tools/adb_fetch.sh              # 只连了一台设备
#   bash tools/adb_fetch.sh <serial>     # 多台设备时指定
set -uo pipefail

# Git Bash 的 pwd 给 /c/... 形式，Windows 原生程序（adb.exe / python.exe）
# 会把它误当成 C:\c\... —— 所以目录能用 pwd -W 就拿原生形式。
_native_pwd() { pwd -W 2>/dev/null || pwd; }

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && _native_pwd)"
ROOT="$(dirname "$HERE")"

if ! command -v adb >/dev/null 2>&1; then
  echo "找不到 adb。请安装 Android SDK Platform-Tools 并加进 PATH。" >&2
  exit 1
fi

SERIAL="${1:-}"

if [ -z "$SERIAL" ]; then
  DEVS="$(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')"
  COUNT="$(printf '%s\n' "$DEVS" | grep -c . || true)"
  if [ "$COUNT" -eq 0 ]; then
    echo "没有已授权的 adb 设备。" >&2
    echo "  → 插上 USB、打开「USB 调试」，并在手机上点「允许」。" >&2
    echo "  → 小米 / HyperOS 可能还要额外打开「USB 调试（安全设置）」，" >&2
    echo "    否则在部分 ROM 上装包/读目录会被拦（见 skill: android-adb-install-rom-block）。" >&2
    exit 1
  fi
  if [ "$COUNT" -gt 1 ]; then
    echo "检测到多台设备，请指定一台：" >&2
    printf '  %s\n' $DEVS >&2
    echo "用法：bash tools/adb_fetch.sh <serial>" >&2
    exit 1
  fi
  SERIAL="$DEVS"
fi

echo "设备：$SERIAL"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 官方 App 的两种包名（新版 com.mi.health，老版 com.xiaomi.hm.health）
DIRS=(
  "/sdcard/Android/data/com.mi.health/files/log"
  "/sdcard/Android/data/com.xiaomi.hm.health/files/log"
)
FILES=(XiaomiFit.device.log XiaomiFit.main.log hmble_log.log)

# 关键：cd 进临时目录后用**相对路径**拉，别把 /tmp/... 直接喂给 adb.exe
cd "$TMP" || exit 1

pulled=0
for dir in "${DIRS[@]}"; do
  for f in "${FILES[@]}"; do
    if adb -s "$SERIAL" pull "$dir/$f" "./$f" >/dev/null 2>&1; then
      pulled=$((pulled + 1))
    fi
  done
done

if [ "$pulled" -eq 0 ]; then
  echo "没能拉到任何日志。可能原因：" >&2
  echo "  * 手机上没装「小米运动健康」(com.mi.health)" >&2
  echo "  * 这台 ROM 不允许 adb shell 读 Android/data" >&2
  echo "  * 日志路径变了，手动确认一下：" >&2
  echo "      adb -s $SERIAL shell ls ${DIRS[0]}" >&2
  exit 1
fi

echo "已拉取 $pulled 个日志文件，开始解析…"
echo

PY=python3
command -v python3 >/dev/null 2>&1 || PY=python

"$PY" "$ROOT/parse_log.py" ./*.log

#!/usr/bin/env bash
# `just install` 的实现体 —— justfile 里只是一行薄包装。
#
# 为什么不放 shebang 配方里：Windows 上 just 跑 shebang 配方要把
# 临时脚本路径（…\Temp\just-XXXX\install）拼进命令字符串，个别终端
# 环境会把 C:\Users\... 的反斜杠当转义吃掉（变成 C:Users-*…），
# bash 打不开脚本直接 127。放进真实脚本文件后这条路彻底不走了。
#
# 用法（与 just install 一致）：
#   bash tools/just_install.sh                    # 自动判断
#   bash tools/just_install.sh phone              # 多台 adb 设备里认出手机那台
#   bash tools/just_install.sh apk [serial]       # 装到手机
#   bash tools/just_install.sh cli [目录]         # 装命令行入口
#   bash tools/just_install.sh termux             # 装 Termux 桌面小部件
#   DRY_RUN=1                                     # 只打印计划
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

[ -f xiaomi_authkey.py ] || { echo "错误：不在项目根目录（$root）"; exit 1; }

mode="${1:-auto}"
extra="${2:-}"

# DRY_RUN=1 时只打印，不真的动手机/家目录
dry() {
  if [ "${DRY_RUN:-0}" = "1" ]; then
    echo "    [dry-run] $*"
  else
    "$@"
  fi
}

# 已授权的 adb 设备 serial（一行一台；Windows 上 adb 输出带 \r，先剥掉）。
# 结果存进全局 ADB_RAW（报错时要打原始输出），serial 列表走 stdout。
#
# 空结果自动重试一次：adb server 冷启动（或被另一个版本的 adb 客户端杀掉
# 重启 —— 机器上装了多个 platform-tools 时很常见）之后的第一次查询，
# 设备列表经常是空的，设备要过一两秒才重新挂上来。
ADB_RAW=""
adb_serials() {
  ADB_RAW="$(adb devices 2>&1 | tr -d '\r' || true)"
  if ! printf '%s\n' "$ADB_RAW" | awk 'NR>1' | grep -q "device"; then
    sleep 2
    ADB_RAW="$(adb devices 2>&1 | tr -d '\r' || true)"
  fi
  printf '%s\n' "$ADB_RAW" | awk 'NR>1 && $2=="device" {print $1}'
}

# 屏幕对角线（英寸）—— characteristics 不可信时（小米手机报 nosdcard）拿它兜底
screen_inches() {
  # serial 先存局部变量：下面的 set -- 会把 $1 覆盖成屏宽，
  # 再用 $1 查 density 就成了 `adb -s 1080 ...`（查无此设备，悄悄返回空）
  local serial="$1" w h dpi
  set -- $(adb -s "$serial" shell wm size </dev/null 2>/dev/null | tr -d '\r' | awk '/size/{print $3}' | tail -1 | tr 'x' ' ')
  [ $# -eq 2 ] || { echo 0; return; }
  w=$1; h=$2
  dpi="$(adb -s "$serial" shell wm density </dev/null 2>/dev/null | tr -d '\r' | awk '/density/{print $3}' | tail -1)"
  case "$dpi" in ''|*[!0-9]*) echo 0; return ;; esac
  awk -v w="$w" -v h="$h" -v d="$dpi" 'BEGIN { printf "%.1f", sqrt((w/d)^2 + (h/d)^2) }'
}

# 这台是不是手机。判定顺序（实测：联想平板报 tablet，小米手机却报 nosdcard）：
#   1) characteristics 里有 tablet / watch / television → 不是
#   2) 里有 phone → 是
#   3) 都没有 → 看屏幕对角线，7 寸以下算手机
is_phone() {
  local chars inches
  chars="$(adb -s "$1" shell getprop ro.build.characteristics </dev/null 2>/dev/null | tr -d '\r')"
  case ",$chars," in
    *,tablet,*|*,watch,*|*,television,*) return 1 ;;
    *,phone,*) return 0 ;;
  esac
  inches="$(screen_inches "$1")"
  awk -v v="$inches" 'BEGIN { exit !(v > 0 && v < 7) }'
}

# 位置参数直接给路径（just install /some/dir）时当成 cli 的目标目录
case "$mode" in
  /*|./*|../*|~*) extra="$mode"; mode="cli" ;;
esac

# ---------------- 自动判断 ----------------
if [ "$mode" = "auto" ]; then
  mode=""
  if command -v adb >/dev/null 2>&1; then
    serials="$(adb_serials)"
    if [ -n "$serials" ]; then
      count="$(printf '%s\n' "$serials" | grep -c . || true)"
      if [ "$count" -eq 1 ]; then
        serial="$serials"
        echo "自动判断：检测到 adb 设备 $serial → 安装 APK"
      else
        # 多台连着（比如手机 + 平板同时插着）：优先挑 characteristics=phone 的那台
        serial=""
        # 输入走 fd 3：循环体里的 adb shell 会转发/吃掉本地 stdin，
        # 走 fd0 的话设备列表读一行就断了
        while read -r s <&3; do
          [ -n "$s" ] || continue
          if is_phone "$s"; then serial="$s"; break; fi
        done 3< <(printf '%s\n' "$serials")
        if [ -n "$serial" ]; then
          echo "自动判断：$count 台 adb 设备，认出手机 $serial → 安装 APK"
        else
          serial="$(printf '%s\n' "$serials" | head -1)"
          echo "自动判断：$count 台 adb 设备里没认出手机，用第一台 $serial → 安装 APK"
          echo "          （想明确指定：just install apk <serial>；只要手机：just install phone）"
        fi
      fi
      mode="apk"; extra="$serial"
    fi
  fi
  if [ -z "$mode" ]; then
    case "${PREFIX:-}" in
      *com.termux*)
        echo "自动判断：检测到 Termux → 安装桌面小部件"
        mode="termux"
        ;;
      *)
        echo "自动判断：没有 adb 设备、也不在 Termux → 安装命令行入口到 ~/.local/bin"
        mode="cli"
        ;;
    esac
  fi
fi

# ---------------- 指定 phone：多台设备里认出手机，跳过平板 ----------------
if [ "$mode" = "phone" ]; then
  command -v adb >/dev/null 2>&1 || { echo "错误：找不到 adb，装不了手机" >&2; exit 1; }
  serials="$(adb_serials)"
  if [ -z "$serials" ]; then
    echo "错误：adb 里查不到处于 device 状态的设备。adb 的原始输出：" >&2
    printf '%s\n' "$ADB_RAW" | sed 's/^/    /' >&2
    echo "上面要是空的，或者有 daemon / version 之类的字样：多半是这台机器装了" >&2
    echo "多个 adb（不同版本会互相杀 server），重跑一次一般就好；也可以直接" >&2
    echo "指定 serial 绕过探测：just install apk <serial>" >&2
    exit 1
  fi
  serial=""
  # 输入走 fd 3：循环体里的 adb shell 会转发/吃掉本地 stdin，
  # 走 fd0 的话设备列表读一行就断了
  while read -r s <&3; do
    [ -n "$s" ] || continue
    chars="$(adb -s "$s" shell getprop ro.build.characteristics </dev/null 2>/dev/null | tr -d '\r')"
    model="$(adb -s "$s" shell getprop ro.product.model </dev/null 2>/dev/null | tr -d '\r')"
    if is_phone "$s"; then
      tag=" ← 手机"
      [ -z "$serial" ] && serial="$s"
    else
      tag=""
    fi
    echo "    adb 设备：$s（${model:-型号未知}）→ characteristics=${chars:-（没报）}$tag"
  done 3< <(printf '%s\n' "$serials")
  if [ -z "$serial" ]; then
    echo "错误：连着的设备里没认出手机（平板按 characteristics / 屏幕尺寸排除后一台不剩）。" >&2
    echo "      要么确认手机已插好并授权调试，要么明确指定：just install apk <serial>" >&2
    exit 1
  fi
  echo "选定手机：$serial → 安装 APK"
  mode="apk"; extra="$serial"
fi

case "$mode" in
  apk|android|app)
    if [ "${DRY_RUN:-0}" = "1" ]; then
      echo
      echo "    [dry-run] bash tools/gradle.sh assembleDebug"
      echo "    [dry-run] bash tools/android_install.sh <debug apk> ${extra:-(唯一连接的设备)}"
      exit 0
    fi
    echo
    echo "==> 编译 APK"
    bash tools/gradle.sh assembleDebug
    apkfile="$(ls app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1 || true)"
    [ -n "$apkfile" ] || { echo "错误：没找到 debug APK 产物" >&2; exit 1; }
    echo
    echo "==> 装到手机"
    bash tools/android_install.sh "$apkfile" "$extra"
    ;;

  termux)
    [ -f termux/install.sh ] || { echo "错误：找不到 termux/install.sh" >&2; exit 1; }
    echo
    dry bash termux/install.sh
    ;;

  cli|"")
    echo
    dry bash tools/install_cli.sh "$extra"
    ;;

  *)
    echo "未知安装目标：$mode" >&2
    echo "可用：auto（默认）/ phone（认出手机）/ apk [serial] / cli [目录] / termux，或者直接给一个目录" >&2
    exit 2
    ;;
esac

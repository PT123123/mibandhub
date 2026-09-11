#!/usr/bin/env bash
# 安装 APK 到 adb 连接的安卓手机，自动处理国产 ROM 对 adb 安装的拦截。
#
# 三级降级（每一级失败才走下一级）：
#   1) adb install -r                          —— 正常情况，一步到位
#   2) push + pm install -r -t                 —— 绕开 install-session 通道
#   3) push 到 Download + 调起 MIUI 安装器 UI  —— 走"用户安装"流程，需要用户点一下
#
# 用法: bash tools/android_install.sh <apk> [serial]
set -uo pipefail

APK="${1:?用法: bash tools/android_install.sh <apk> [serial]}"
SERIAL="${2:-}"
ADB="${ADB:-adb}"

[ -f "$APK" ] || { echo "APK 不存在：$APK" >&2; exit 1; }

SEL=()
[ -n "$SERIAL" ] && SEL=(-s "$SERIAL")
sel() { "$ADB" ${SEL[@]+"${SEL[@]}"} "$@"; }

# adb 是原生程序，喂 /c/... 形式的 POSIX 路径会被解析成 C:\c\...，必须转原生形式
native() {
  if command -v cygpath >/dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

APK_NATIVE="$(native "$APK")"

echo "==> 目标设备：${SERIAL:-（唯一连接的设备）}"
echo "==> APK：$APK_NATIVE"

# ---------- 1) 常规安装 ----------
echo
echo "[1/3] adb install -r"
OUT="$("$ADB" ${SEL[@]+"${SEL[@]}"} install -r "$APK_NATIVE" 2>&1 | tr -d '\r')"
echo "$OUT" | sed 's/^/      /'
case "$OUT" in
  *Success*) echo "      完成"; exit 0 ;;
esac

case "$OUT" in
  *INSTALL_FAILED_USER_RESTRICTED*) echo "      被 ROM 拦截，降级" ;;
  *) echo "      失败原因不是 ROM 拦截，停止" >&2; exit 1 ;;
esac

# ---------- 2) shell 通道 ----------
echo
echo "[2/3] push + pm install（shell 通道）"
REMOTE="/data/local/tmp/install-$$.apk"
if ! sel push "$APK_NATIVE" "$REMOTE" >/dev/null 2>&1; then
  echo "      push 失败" >&2
  exit 1
fi
OUT="$(sel shell "pm install -r -t $REMOTE" 2>&1 | tr -d '\r')"
sel shell "rm -f $REMOTE" >/dev/null 2>&1
echo "$OUT" | sed 's/^/      /'
case "$OUT" in
  *Success*) echo "      完成"; exit 0 ;;
esac
echo "      shell 通道同样被拦，降级到系统安装器"

# ---------- 3) 系统安装器 UI ----------
echo
echo "[3/3] 调起系统安装器（需要你在手机上确认）"
DEST="/sdcard/Download/$(basename "$APK")"
if ! sel push "$APK_NATIVE" "$DEST" >/dev/null 2>&1; then
  echo "      推送失败" >&2
  exit 1
fi

# 用 VIEW + file:// 拉起；不要指名 PackageInstallerActivity —— 类名随版本变
sel shell "am start -a android.intent.action.VIEW -d file://$DEST -t application/vnd.android.package-archive" >/dev/null 2>&1

cat <<EOF

----------------------------------------------------------------------
已在手机上拉起安装界面，请解锁手机点「继续 / 安装」。

APK 也放到了手机 Download 目录，装不上时可以在文件管理器里直接点它：
    $DEST

装完后用这条确认：
    adb ${SEL[@]+"${SEL[@]}"} shell pm list packages | grep <你的包名>

根治办法（一次即可，但这个开关偶尔会自己关掉）：
    设置 → 更多设置 → 开发者选项 → 打开「USB 调试（安全设置）」
----------------------------------------------------------------------
EOF

#!/usr/bin/env bash
# =============================================================================
# 给安卓手机装 Gadgetbridge，并把「连手环」必需的权限一次配好。
#
# 装完后手机端只剩两件事必须手动做（adb 改不了）：
#   1. HyperOS/MIUI 里把 Gadgetbridge 的「自启动」打开、省电策略设为「无限制」
#   2. 在 Gadgetbridge 里添加设备（MAC + AuthKey，见 authkeys.txt）
#
# 用法：
#   bash tools/setup_phone.sh                 # 自动选唯一在线设备
#   bash tools/setup_phone.sh <serial>        # 指定设备
#   bash tools/setup_phone.sh --check-only    # 只体检，不安装、不改设置
#   bash tools/setup_phone.sh --apk <文件>    # 用本地 APK，跳过下载
#
# F-Droid 发新版后，覆盖内置常量即可，不必改脚本：
#   GB_VERSION_CODE=253 GB_SHA256=<新哈希> bash tools/setup_phone.sh
# =============================================================================
set -euo pipefail

PKG="nodomain.freeyourgadget.gadgetbridge"

# --- 内置版本常量（F-Droid 稳定版，取「主签名」那个 apkName）----------------
GB_VERSION="${GB_VERSION:-0.93.0}"
GB_VERSION_CODE="${GB_VERSION_CODE:-252}"
GB_APK_NAME="${GB_APK_NAME:-${PKG}_${GB_VERSION_CODE}.apk}"
GB_SHA256="${GB_SHA256:-86ee3996df118d211b714ae50bd4cb752b73d23e1f7b083cf254f4eb96baaade}"
GB_MIRROR="${GB_MIRROR:-https://mirrors.tuna.tsinghua.edu.cn/fdroid/repo}"

# 连手环必需的运行时权限。刻意只给这几个：短信/通讯录/相机/传感器属于
# 可选功能，留给用户在 Gadgetbridge 里按需开，避免过度授权。
NEED_PERMS="BLUETOOTH_SCAN BLUETOOTH_CONNECT ACCESS_FINE_LOCATION POST_NOTIFICATIONS"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CACHE="$ROOT/.cache/apk"

SERIAL=""
APK=""
CHECK_ONLY=0
APK_FROM_USER=0

while [ $# -gt 0 ]; do
  case "$1" in
    -s|--serial)  SERIAL="${2:?--serial 需要一个序列号}"; shift 2 ;;
    --apk)        APK="${2:?--apk 需要一个文件路径}"; APK_FROM_USER=1; shift 2 ;;
    --check-only) CHECK_ONLY=1; shift ;;
    -h|--help)    sed -n '2,19p' "$0"; exit 0 ;;
    *)            # 裸位置参数当作序列号，好让 `just setup-phone <serial>` 能用
                  if [ -z "$SERIAL" ]; then SERIAL="$1"; shift
                  else echo "多余参数：$1（-h 看用法）" >&2; exit 2; fi ;;
  esac
done

# Windows 下 adb.exe 是原生程序，收到 POSIX 路径会当成 C:\c\...；凡是「要交给
# 原生程序」的路径都必须转成原生形式。cygpath 在 Linux/Termux 上不存在，
# `||` 兜底成原值。
to_native() {
  if command -v cygpath > /dev/null 2>&1; then cygpath -w "$1"; else printf '%s' "$1"; fi
}

ADB="${ADB:-adb}"
command -v "$ADB" > /dev/null 2>&1 || { echo "错误：找不到 adb，请先装 platform-tools 并加进 PATH" >&2; exit 1; }

# --- 1. 选设备 ---------------------------------------------------------------
if [ -z "$SERIAL" ]; then
  # 必须 tr 掉 \r：Windows 版 adb 输出是 CRLF，$2 会变成 "device\r"，
  # 直接比较 $2 == "device" 会永远为假、导致「设备明明在线却说没有」。
  mapfile -t ONLINE < <("$ADB" devices | tr -d '\r' | awk 'NR > 1 && $2 == "device" { print $1 }')
  case "${#ONLINE[@]}" in
    0) echo "错误：没有在线的 adb 设备。插上 USB、开「USB 调试」后重试。" >&2; exit 1 ;;
    1) SERIAL="${ONLINE[0]}" ;;
    *) echo "有 ${#ONLINE[@]} 台设备在线，请指定序列号：" >&2
       printf '  %s\n' "${ONLINE[@]}" >&2
       exit 1 ;;
  esac
fi

echo "目标设备：$SERIAL"
MODEL="$("$ADB" -s "$SERIAL" shell 'getprop ro.product.marketname' 2>/dev/null | tr -d '\r' || true)"
OS="$("$ADB" -s "$SERIAL" shell 'getprop ro.build.version.release' 2>/dev/null | tr -d '\r' || true)"
echo "  型号：${MODEL:-未知}   Android ${OS:-?}"

perm_state() {
  "$ADB" -s "$SERIAL" shell "dumpsys package $PKG | grep -m1 '$1:'" 2>/dev/null \
    | tr -d '\r' | sed 's/.*granted=\([a-z]*\).*/\1/' || true
}

if [ "$CHECK_ONLY" = 1 ]; then
  echo
  echo "=== 体检模式（不做任何改动）==="
  echo "已装版本：$("$ADB" -s "$SERIAL" shell "dumpsys package $PKG | grep -m1 versionName" 2>/dev/null | tr -d '\r' | awk -F= '{print $2}' || true)"
  for p in $NEED_PERMS; do
    printf "  %-24s %s\n" "$p" "$(perm_state "$p")"
  done
  echo "  通知使用权               $("$ADB" -s "$SERIAL" shell 'settings get secure enabled_notification_listeners' 2>/dev/null | tr -d '\r' | grep -c "$PKG" || true) 条匹配（1=已授权）"
  exit 0
fi

# --- 2. 准备 APK -------------------------------------------------------------
if [ -z "$APK" ]; then
  mkdir -p "$CACHE"
  APK="$CACHE/$GB_APK_NAME"
  if [ ! -f "$APK" ]; then
    echo
    echo "[1/5] 下载 Gadgetbridge $GB_VERSION（versionCode $GB_VERSION_CODE）"
    echo "      $GB_MIRROR/$GB_APK_NAME"
    # f-droid.org 在国内常直连超时，默认走清华镜像；要换源改 GB_MIRROR 或直接 --apk
    # 注意 curl 在 Windows 上也是原生程序 —— 输出路径同样要转原生形式，
    # 否则 -o /c/Users/... 会被当成 C:\c\Users\... 报「系统找不到指定的文件」。
    if ! curl -fL --max-time 300 -o "$(to_native "$APK.part")" "$GB_MIRROR/$GB_APK_NAME"; then
      rm -f "$APK.part"
      echo "      下载失败。可以手动下好再用 --apk <文件> 指定。" >&2
      exit 1
    fi
    mv "$APK.part" "$APK"
    echo "      已缓存到 .cache/apk/（下次不用重下）"
  else
    echo
    echo "[1/5] 复用已缓存的 APK：$GB_APK_NAME"
  fi
else
  echo
  echo "[1/5] 使用指定的 APK：$APK"
  [ -f "$APK" ] || { echo "      文件不存在：$APK" >&2; exit 1; }
fi

# 自己下载的才强制校验哈希（宁可不装，也不装来源不明的东西）。
# 用户用 --apk 显式指定的文件，只提示不一致，不拦。
if [ "$APK_FROM_USER" = 0 ] && command -v sha256sum > /dev/null 2>&1 && [ -n "$GB_SHA256" ]; then
  ACTUAL="$(sha256sum "$APK" | cut -d' ' -f1)"
  if [ "$ACTUAL" != "$GB_SHA256" ]; then
    echo "      哈希不匹配，拒绝安装！" >&2
    echo "        期望 $GB_SHA256" >&2
    echo "        实际 $ACTUAL" >&2
    exit 1
  fi
  echo "      sha256 校验通过"
elif [ "$APK_FROM_USER" = 1 ] && command -v sha256sum > /dev/null 2>&1 && [ -n "$GB_SHA256" ]; then
  ACTUAL="$(sha256sum "$APK" | cut -d' ' -f1)"
  [ "$ACTUAL" = "$GB_SHA256" ] || echo "      提示：与你指定的 APK 不匹配（按自定义 APK 继续）"
fi

# --- 3. 安装 -----------------------------------------------------------------
# 部分国产 ROM（MIUI/HyperOS/ColorOS）会拦 adb install 走的 install-session
# 通道，报 INSTALL_FAILED_USER_RESTRICTED 且设备上不弹任何框 —— 但也有不少
# 机器是放行的，所以先试常规方式，失败了再降级走 shell 的 package installer。
# 降级时包得先 push 到设备上，因为 pm install 读的是设备上的路径。
echo
echo "[2/5] 安装"
APK_NATIVE="$(to_native "$APK")"
INSTALLED=0
if "$ADB" -s "$SERIAL" install -r "$APK_NATIVE" 2>&1 | tr -d '\r' | grep -q '^Success'; then
  echo "      adb install 成功"
  INSTALLED=1
else
  echo "      adb install 被拦，降级走 pm install（shell 通道）"
  REMOTE="/data/local/tmp/$(basename "$APK")"
  if "$ADB" -s "$SERIAL" push "$APK" "$REMOTE" > /dev/null 2>&1 \
     && "$ADB" -s "$SERIAL" shell "pm install -r -t '$REMOTE'" 2>&1 | tr -d '\r' | grep -q '^Success'; then
    echo "      pm install 成功"
    INSTALLED=1
  fi
  "$ADB" -s "$SERIAL" shell "rm -f '$REMOTE'" > /dev/null 2>&1 || true
fi
[ "$INSTALLED" = 1 ] || { echo "      安装失败。若提示 USER_RESTRICTED，见 README 的排障一节。" >&2; exit 1; }

VERSION="$("$ADB" -s "$SERIAL" shell "dumpsys package $PKG | grep -m1 versionName" 2>/dev/null | tr -d '\r' | awk -F= '{print $2}' || true)"
echo "      设备上的版本：${VERSION:-?}"

# --- 4. 运行权限 -------------------------------------------------------------
# 逐条串行执行、每条执行完立刻回读。别把这几个 pm grant 塞进同一条 shell 里
# 并发跑 —— 会读到彼此尚未落盘的状态，出现「rc=0 但 granted 仍是 false」的假成功。
echo
echo "[3/5] 授予连手环必需的权限"
for p in $NEED_PERMS; do
  "$ADB" -s "$SERIAL" shell "pm grant $PKG android.permission.$p" > /dev/null 2>&1 || true
  printf "      %-24s %s\n" "$p" "$(perm_state "$p")"
done

# --- 5. 后台存活 + 通知使用权 -------------------------------------------------
echo
echo "[4/5] 后台存活（HyperOS 的后台限制是手环掉线的头号原因）"
"$ADB" -s "$SERIAL" shell "dumpsys deviceidle whitelist +$PKG" 2>&1 | tr -d '\r' | sed 's/^/      /' || true
"$ADB" -s "$SERIAL" shell "am set-standby-bucket $PKG active" > /dev/null 2>&1 || true
echo "      standby-bucket：$("$ADB" -s "$SERIAL" shell "am get-standby-bucket $PKG" 2>/dev/null | tr -d '\r' || true)  （5 = EXEMPTED，最宽松）"

echo
echo "[5/5] 通知使用权（Gadgetbridge 靠它把手机通知转发到手环）"
# 组件名不写死：从系统反查，避免 Gadgetbridge 改动包结构后脚本失效
SVC="$("$ADB" -s "$SERIAL" shell "cmd package query-services --components -a android.service.notification.NotificationListenerService" 2>/dev/null \
       | tr -d '\r' | grep "^${PKG}" | head -1 || true)"
if [ -n "$SVC" ]; then
  "$ADB" -s "$SERIAL" shell "cmd notification allow_listener '$SVC'" > /dev/null 2>&1 || true
  if "$ADB" -s "$SERIAL" shell 'settings get secure enabled_notification_listeners' 2>/dev/null | tr -d '\r' | grep -q "$PKG"; then
    echo "      已授权：$SVC"
  else
    echo "      自动授权失败，请手动开：设置 → 通知与状态栏 → 通知使用权"
  fi
else
  echo "      没找到通知监听组件，请手动开启通知使用权"
fi

# --- 收尾 --------------------------------------------------------------------
cat <<'EOF'

------------------------------------------------------------------
安装与权限配置完成。剩下两步必须在手机上手动做：

  1. 防后台被杀（否则手环会频繁断连）
     设置 → 应用设置 → 应用管理 → Gadgetbridge
       · 「自启动」打开
       · 「省电策略」设为「无限制」

  2. 添加设备
     打开 Gadgetbridge → 加号 → 选到对应型号 → 填 MAC 与 Auth key
     MAC / AuthKey 见仓库根的 authkeys.txt

  另注：手环同一时间只能连一个 App ——
  请先在「小米运动健康」里断开或停用，否则 Gadgetbridge 连不上。
------------------------------------------------------------------
EOF

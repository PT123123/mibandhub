#!/usr/bin/env bash
# 把 shouhuan 的 DataStore 从一台设备迁移到另一台（无需 root）。
#
# 用法:
#   ./migrate_datastore.sh <源设备序列号> <目标设备序列号>
#
# 前提:
#   1. 两台设备都已连 adb 且已授权（adb devices 能看到）。
#   2. 源设备上的 shouhuan 必须是 debug 包，否则 run-as 读不到 /data/data。
#      —— 若源是 release：先在源设备 adb install -r 一个同 keystore 重签的
#         debuggable 包（覆盖，数据保留），取完数据后再处理源设备。
#   3. 目标设备先装好 shouhuan。建议装 debug 包以便 run-as 写入；
#      若日常用 release，用 debug 包接收数据后 adb install -r release 覆盖
#      （同证书会保留数据）。
#
# 注意: 本脚本只迁移数据，不碰手环。手环重连靠 datastore 里带的 AuthKey 自动完成；
#       若连不上，多半是手环仍绑在旧手机，见脚本末尾提示。
set -euo pipefail

PKG="com.mibandhub.app"
SRC="${1:?用法: $0 <源设备序列号> <目标设备序列号>}"
DST="${2:?用法: $0 <源设备序列号> <目标设备序列号>}"

# 归档落在系统临时区，避免污染项目目录；Git Bash 下用 $TEMP
TMP="$(mktemp -d "${TEMP:-/tmp}/mig.XXXXXX")"
ARCHIVE="$TMP/mig.tar"
cleanup() { rm -rf "$TMP"; }
trap cleanup EXIT

# Git Bash 路径转换：本地路径要 cygpath -w，远端目标路径要 MSYS_NO_PATHCONV=1
win_path() { cygpath -w "$1" 2>/dev/null || echo "$1"; }

echo "==> [1/6] 从源设备 ($SRC) 取出 files/datastore ..."
# exec-out 走二进制流，避免 pty/管道 CRLF 损坏 tar 归档
if ! adb -s "$SRC" exec-out run-as "$PKG" tar -c files/datastore > "$ARCHIVE"; then
  echo "✗ run-as 失败：源设备上的 $PKG 可能不是 debug 包（不可 debuggable）。" >&2
  echo "  解决：在源设备先 'adb install -r' 一个同 keystore 重签的 debuggable 包，再重跑本脚本。" >&2
  exit 1
fi
echo "  取出 $(wc -c < "$ARCHIVE") 字节 -> $ARCHIVE"

echo "==> [2/6] 推送到目标设备 ($DST) 的 /data/local/tmp ..."
MSYS_NO_PATHCONV=1 adb -s "$DST" push "$(win_path "$ARCHIVE")" /data/local/tmp/mig.tar

echo "==> [3/6] 在目标设备解包到应用私有目录 ..."
# 注意：/data/... 是设备端绝对路径，必须 MSYS_NO_PATHCONV=1 防止 Git Bash 把它当成本地 /data
MSYS_NO_PATHCONV=1 adb -s "$DST" exec-out run-as "$PKG" tar -xf /data/local/tmp/mig.tar
MSYS_NO_PATHCONV=1 adb -s "$DST" shell run-as "$PKG" rm -f /data/local/tmp/mig.tar || true

echo "==> [4/6] sha256 校验（两端 band.preferences_pb）..."
SRC_SHA=$(adb -s "$SRC" exec-out run-as "$PKG" sha256sum files/datastore/band.preferences_pb 2>/dev/null | awk '{print $1}')
DST_SHA=$(adb -s "$DST" exec-out run-as "$PKG" sha256sum files/datastore/band.preferences_pb 2>/dev/null | awk '{print $1}')
echo "  src=$SRC_SHA"
echo "  dst=$DST_SHA"
if [ -z "$SRC_SHA" ] || [ -z "$DST_SHA" ] || [ "$SRC_SHA" != "$DST_SHA" ]; then
  echo "✗ sha256 不一致，迁移失败！" >&2
  exit 1
fi
echo "  ✓ 校验通过"

echo "==> [5/6] 授权目标设备权限（蓝牙 / 通知）..."
# 注意：部分 ROM（如 ColorOS / OPPO）禁止 adb 直接授予运行时权限，
# 会报 "Neither user 2000 ... has android.permission.GRANT_RUNTIME_PERMISSIONS"。
# 这种情况下授权只能由用户在系统设置 / 应用首次启动的弹窗里手动完成。
NEED_MANUAL=()
for p in BLUETOOTH_CONNECT BLUETOOTH_SCAN POST_NOTIFICATIONS; do
  FULL="android.permission.$p"
  ERR=$(MSYS_NO_PATHCONV=1 adb -s "$DST" shell pm grant "$PKG" "$FULL" 2>&1)
  if [ -z "$ERR" ]; then
    echo "  ✓ $p (adb 授权成功)"
  elif echo "$ERR" | grep -qi "GRANT_RUNTIME_PERMISSIONS\|SecurityException"; then
    echo "  ✗ $p：本机 ROM 禁止 adb 授权（ColorOS 等常见），需手动授予"
    NEED_MANUAL+=("$p")
  else
    echo "  (跳过 $p：$ERR —— 装完首次启动点一次授权即可)"
  fi
done

if [ "${#NEED_MANUAL[@]}" -gt 0 ]; then
  echo
  echo "  ⚠️ 以下权限无法用 adb 授予，请在目标手机上手动开启（否则无法连手环 / 收通知）："
  echo "     设置 → 应用 → shouhuan → 权限 → 将「${NEED_MANUAL[*]}」相关权限设为允许"
  echo "     或直接打开 shouhuan，按首次启动的权限弹窗点「允许」。"
fi

echo "==> [6/6] 完成"
echo
echo "请在目标设备打开 shouhuan：配对信息已随 datastore 带入，正常情况下会自动重连手环。"
echo "若手环连不上，多半是手环仍绑在旧手机："
echo "  - 在旧手机的小米运动健康 / Zepp Life 里『解绑设备』，或"
echo "  - 手环端恢复出厂设置（设置 → 恢复出厂），然后在新手机重连。"
echo "注意：红米上的 shouhuan 先『断开连接』即可（别点『忘记此设备』，那会删掉 datastore 里的配对键）。"

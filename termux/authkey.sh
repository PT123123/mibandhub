#!/data/data/com.termux/files/usr/bin/bash
# ---------------------------------------------------------------------------
# authkey.sh —— 路线 B 一键取 AuthKey（放到 ~/.shortcuts/ 给 Termux:Widget 用）
#
# 依赖：pkg install python
# 用法：termux/install.sh 装好后，长按桌面 → 小部件 → Termux:Widget 添加
#
# 安全：密码只在终端里输入、只留在 shell 变量里，不写文件、不回显。
# ---------------------------------------------------------------------------

set -uo pipefail

DIR="${AUTHKEY_DIR:-$(cat "$HOME/.authkey_dir" 2>/dev/null || echo "$HOME/shouhuan")}"
TOOL="$DIR/xiaomi_authkey.py"
ERR="$HOME/.cache/authkey.err"
mkdir -p "$HOME/.cache"

_pause() { read -r -p "回车退出…" _ || true; }

if ! command -v python >/dev/null 2>&1 && [ ! -x "$PREFIX/bin/python" ]; then
  echo "缺少 python。请先执行：  pkg install python"
  _pause; exit 1
fi

if [ ! -f "$TOOL" ]; then
  echo "找不到工具：$TOOL"
  echo "请把 xiaomi_authkey.py 放到该目录，或先跑 termux/install.sh，"
  echo "或用 AUTHKEY_DIR=/你的目录 指定。"
  _pause; exit 1
fi

clear
echo "=== 小米手环 AuthKey 获取（路线 B） ==="
echo
echo "先跑一次连通性自检…"
if ! python "$TOOL" --probe; then
  echo
  echo "网络不通，先解决网络再取密钥。"
  _pause; exit 1
fi

echo
read -r -p "小米账号（邮箱/手机号）: " EMAIL
if [ -z "${EMAIL:-}" ]; then
  echo "账号为空，退出。"; _pause; exit 1
fi

printf "密码（不回显）: "
read -r -s PASSWORD
echo
echo
echo "正在登录并拉取设备…（首次可能稍慢）"
echo

# 唯一的网络调用：一次登录 + 一次取设备，避免反复请求触发风控
# 密码走 stdin（--password-stdin），不出现在进程参数里
OUT=$(printf '%s\n' "$PASSWORD" | python "$TOOL" -e "$EMAIL" --password-stdin 2>"$ERR")
RC=$?
unset PASSWORD

printf '%s\n' "$OUT"

if [ "$RC" -ne 0 ]; then
  echo
  echo "--- 错误详情 ---"
  cat "$ERR"
  rm -f "$ERR"
  _pause; exit "$RC"
fi
rm -f "$ERR"

KEYS=$(printf '%s\n' "$OUT" | grep -Eo '0x[0-9a-f]{32}' | sort -u)
if [ -n "$KEYS" ] && command -v termux-clipboard-set >/dev/null 2>&1; then
  printf '%s' "$KEYS" | termux-clipboard-set && {
    echo
    echo "已复制到剪贴板，直接粘贴进 Gadgetbridge 的「Auth key」："
    printf '  %s\n' "$KEYS"
  }
fi

echo
echo "别忘了：手环同一时间只能连一个 App，先去「小米运动健康」里停用它。"
_pause

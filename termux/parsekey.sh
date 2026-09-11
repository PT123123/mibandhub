#!/data/data/com.termux/files/usr/bin/bash
# ---------------------------------------------------------------------------
# parsekey.sh —— 路线 A 一键（放到 ~/.shortcuts/ 给 Termux:Widget 用）
#
# 流程：调系统文件选择器(SAF) → 你选中官方 App 导出的日志 → 解析 → 复制密钥
# 这一条路绕开了 Android 11+ 的分区存储限制：不需要 root，也不需要
# MANAGE_EXTERNAL_STORAGE，因为你亲手把文件「给」了 Termux。
#
# 依赖：pkg install python termux-api  （并且要装 Termux:API 那个独立 App）
# ---------------------------------------------------------------------------

set -uo pipefail

DIR="${AUTHKEY_DIR:-$(cat "$HOME/.authkey_dir" 2>/dev/null || echo "$HOME/shouhuan")}"
TOOL="$DIR/parse_log.py"
TMP="$HOME/.cache/picked.log"
mkdir -p "$HOME/.cache"

_pause() { read -r -p "回车退出…" _ || true; }

if ! command -v termux-storage-get >/dev/null 2>&1; then
  echo "缺少 termux-storage-get。请执行："
  echo "  pkg install termux-api"
  echo "并安装「Termux:API」这个独立 App（只有装了这个，文件选择器才调得起来）。"
  _pause; exit 1
fi

if [ ! -f "$TOOL" ]; then
  echo "找不到工具：$TOOL"
  echo "请先跑 termux/install.sh，或用 AUTHKEY_DIR=/你的目录 指定。"
  _pause; exit 1
fi

clear
echo "=== 路线 A：从官方 App 日志里取 AuthKey ==="
echo
echo "如果还没导出日志，先在手机上做这一步："
echo "  「小米运动健康」→ 我的 → 关于 → 连续猛点页面图标 7~10 次"
echo "  → 出现「Log 迁移至…」提示 → 确定"
echo
echo "现在会弹出系统文件选择器，请选中："
echo "  XiaomiFit.device.log"
echo "（它在 Android/data/com.mi.health/files/log/ 里；若看不到该目录，"
echo "  用 MT 管理器先把它复制/分享到 Download 再选）"
echo
_pause

rm -f "$TMP"
termux-storage-get "$TMP"

if [ ! -s "$TMP" ]; then
  echo "没选到文件或文件为空，退出。"
  _pause; exit 1
fi

echo
echo "已拿到日志（$(wc -c < "$TMP" | tr -d ' ') 字节），开始解析…"
echo

OUT=$(python "$TOOL" "$TMP")
RC=$?
printf '%s\n' "$OUT"

KEYS=$(printf '%s\n' "$OUT" | grep -Eo '0x[0-9a-f]{32}' | sort -u)
if [ -n "$KEYS" ] && command -v termux-clipboard-set >/dev/null 2>&1; then
  printf '%s' "$KEYS" | termux-clipboard-set && {
    echo
    echo "已复制到剪贴板："
    printf '  %s\n' "$KEYS"
  }
fi

if [ "$RC" -ne 0 ]; then
  echo
  echo "没解析出密钥，按上面的排查清单走一遍。"
fi

echo
echo "别忘了：手环同一时间只能连一个 App，先去「小米运动健康」里停用它。"
_pause

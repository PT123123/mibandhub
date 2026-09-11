#!/data/data/com.termux/files/usr/bin/bash
# ---------------------------------------------------------------------------
# install.sh —— 在 Termux 里一键部署（把脚本装成桌面小部件）
#
# 用法：cd 到本仓库目录后执行
#   bash termux/install.sh
# ---------------------------------------------------------------------------

set -uo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
DIR="$(cd "$HERE/.." && pwd)"
SHORTCUTS="$HOME/.shortcuts"

echo "仓库目录：$DIR"

# --- 1. 依赖 --------------------------------------------------------------
# python 是硬需求：两个小部件都要靠它跑登录/解析。
if ! command -v python >/dev/null 2>&1; then
  echo
  echo "缺少硬依赖：python"
  echo "  执行：pkg install python"
  echo "  装完重跑本脚本。"
  exit 1
fi

# termux-api 只被 parsekey.sh（路线 A，要调系统文件选择器）用到；
# authkey.sh（路线 B 账密一键）完全不需要它。
# 所以这里只记状态、装完再提醒，不阻断安装。
HAS_TERMUX_API=1
command -v termux-storage-get >/dev/null 2>&1 || HAS_TERMUX_API=0

# --- 2. 记住工具目录，供小部件脚本定位 ------------------------------------
echo "$DIR" > "$HOME/.authkey_dir"
chmod 600 "$HOME/.authkey_dir"
echo "已记录工具目录到 ~/.authkey_dir"

# --- 3. 装小部件脚本 ------------------------------------------------------
mkdir -p "$SHORTCUTS"
cp "$HERE/authkey.sh" "$SHORTCUTS/authkey.sh"
cp "$HERE/parsekey.sh" "$SHORTCUTS/parsekey.sh"
chmod +x "$SHORTCUTS/authkey.sh" "$SHORTCUTS/parsekey.sh"
echo "已安装小部件脚本："
echo "  $SHORTCUTS/authkey.sh   → 路线 B（账密一键取密钥）"
echo "  $SHORTCUTS/parsekey.sh  → 路线 A（选日志文件解析）"

# --- 4. 自检 --------------------------------------------------------------
echo
echo "跑一次离线自检确认工具可用…"
if python "$DIR/xiaomi_authkey.py" --selftest >/dev/null 2>&1; then
  echo "  OK  自检通过"
else
  echo "  失败  自检没通过，请手动跑：python \"$DIR/xiaomi_authkey.py\" --selftest"
fi

if [ "$HAS_TERMUX_API" -eq 0 ]; then
  echo
  echo "注意：没检测到 termux-api —— parsekey.sh（路线 A）弹不出文件选择器。"
  echo "      要用路线 A 请执行 pkg install termux-api，"
  echo "      并从 F-Droid 装「Termux:API」这个独立 App（它不是 pkg 包）。"
  echo "      authkey.sh（路线 B）不受影响，现在就能用。"
fi

cat <<'EOF'

------------------------------------------------------------------
安装完成。启用桌面一键：

  1. 回到手机桌面，长按空白处 → 添加小部件
  2. 找到「Termux:Widget」→ 拖出一个小部件
     （没这个 App 先去 F-Droid 装「Termux:Widget」）
  3. 点开它，就能看到 authkey / parsekey 两个入口

建议先去「小米运动健康」导出日志，用 parsekey 当天就把密钥拿到。
------------------------------------------------------------------
EOF

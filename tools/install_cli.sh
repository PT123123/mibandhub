#!/usr/bin/env bash
# 生成 xiaomi-authkey / parse-log 两个命令行入口。
#
#   bash tools/install_cli.sh               # 装到 ~/.local/bin
#   bash tools/install_cli.sh /some/dir     # 装到指定目录
#   PY=/usr/bin/python3 bash tools/install_cli.sh
#
# 由 `just install cli` 调用；也可以单独跑。
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

target="${1:-$HOME/.local/bin}"
py="${PY:-$(command -v python3 2>/dev/null || command -v python 2>/dev/null || echo python)}"

[ -f xiaomi_authkey.py ] || { echo "错误：不在项目根目录（$root）" >&2; exit 1; }

echo "安装命令行入口到：$target"
mkdir -p "$target"

# 路径形态要分开处理：
#   · 解释器给 bash 用 -> POSIX 形式即可（bash 的 exec 认 /c/...）
#   · 脚本路径是 python.exe 的参数，而 python.exe 是原生程序，收到 /c/...
#     会解析成 C:\c\...（实测报 No such file）；必须给原生形式。
#   · cygpath 在 Termux/Linux 上不存在，`||` 兜底成原值，分隔符也随平台定。
root_native="$(cygpath -w "$root" 2>/dev/null || echo "$root")"
case "$root_native" in
  *\\*) script_sep='\' ;;
  *)    script_sep='/' ;;
esac

write_wrapper() {
  local name="$1" script="$2"
  cat > "$target/$name" <<WRAP
#!/usr/bin/env bash
# 本文件由 just install 生成；要换目录请改仓库位置后重新 just install cli
exec "$py" "$root_native${script_sep}${script}" "\$@"
WRAP
  chmod +x "$target/$name"
  echo "  + $name"
}

write_wrapper xiaomi-authkey xiaomi_authkey.py
write_wrapper parse-log     parse_log.py

echo
case ":$PATH:" in
  *":$target:"*)
    echo "该目录已在 PATH 中，直接试：xiaomi-authkey --probe"
    ;;
  *)
    echo "提示：$target 不在 PATH 中，需要先加进去："
    echo "  export PATH=\"$target:\$PATH\""
    echo "（Windows 下请在 Git Bash 里使用）"
    ;;
esac
echo
echo "安装完成。仓库移动位置后需重新执行 just install cli。"

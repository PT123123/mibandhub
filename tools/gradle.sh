#!/usr/bin/env bash
# 统一的 gradle 调用入口 —— 只负责三件事：切到项目根目录、定离线策略、把失败说清楚。
#
#   bash tools/gradle.sh assembleDebug
#   bash tools/gradle.sh --refresh-dependencies assembleDebug   # 额外参数原样透传
#   ONLINE=1 bash tools/gradle.sh assembleDebug                 # 走网络
#
# 为什么默认离线：
#   本机 dl.google.com 不可达、maven.google.com 超时，一旦让 gradle 去在线解析依赖，
#   它会长时间卡住没有任何产出（实测一次 assembleDebug 空转 26 分钟，app/build 下一个
#   文件都没动）。而 AGP 8.5.2 / Kotlin 2.0.21 / Compose BOM 的构件本地缓存里都有，
#   离线跑 assembleDebug 只要 1 分钟上下。
#   需要拉新依赖时用 ONLINE=1（或者 just deps）显式联网，别让它成为默认路径。
#
# 关于看不到进度：
#   这里只有一条 gradle，没有管道、没有后台进程 —— 中途安静是正常的，不是卡死。
#   想看实时进度，另开一个终端：tail -f app/build/gradle.log
#   也**不要**把调用方接进管道（`just build | tail`）：gradle 的守护进程会长期活着
#   并继承写端，下游拿不到 EOF，构建早就 BUILD SUCCESSFUL 了命令行还在挂着。
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

if [ ! -f ./gradlew ]; then
  echo "错误：找不到 ./gradlew" >&2
  echo "     先执行一次：gradle wrapper --gradle-version 8.9" >&2
  exit 1
fi

if [ "$#" -eq 0 ]; then
  echo "用法：bash tools/gradle.sh <gradle 任务> [更多参数...]" >&2
  echo "例如：bash tools/gradle.sh assembleDebug" >&2
  exit 2
fi

args=(--console=plain)
if [ "${ONLINE:-0}" = "1" ]; then
  echo "==> gradle 模式：在线（ONLINE=1）—— 可能要等一会儿拉依赖"
else
  args+=(--offline)
  echo "==> gradle 模式：离线（默认）—— 缓存里没有的依赖会直接失败，不联网空等"
  echo "    换依赖版本后用：ONLINE=1 just deps"
fi

log="app/build/gradle.log"
mkdir -p app/build

echo "==> 正在编译，输出写到 $log（想看实时进度：tail -f $log）"

set +e
./gradlew "${args[@]}" "$@" > "$log" 2>&1
rc=$?
set -e

echo
if [ "$rc" -ne 0 ]; then
  echo "----- $log 末尾 40 行 -----" >&2
  tail -n 40 "$log" >&2
  echo "---------------------------" >&2
  echo "gradle 失败（退出码 $rc）。完整输出：$log" >&2
  if [ "${ONLINE:-0}" != "1" ] && grep -qE "No cached version|Could not resolve|Could not find|offline mode" "$log"; then
    echo >&2
    echo "日志里出现了『缓存里没有』的迹象 —— 这条命令可以联网拉一次，之后照旧离线：" >&2
    echo "    ONLINE=1 just deps" >&2
  fi
  exit "$rc"
fi

# 成功时把结论行打出来（就是那句 BUILD SUCCESSFUL in 53s）
tail -n 3 "$log"
echo
echo "gradle 输出已写入 $log"

# =============================================================================
# 手环管家 —— 小米手环管理 App + 配套工具链
#
# 两部分构成：
#   A. Android App（Kotlin + Jetpack Compose）—— app/ 模块
#   B. 配网/取密钥工具（Python）—— 取 AuthKey、解析官方 App 日志
#
#   just                    列出所有命令
#
#  —— 统一入口（默认就够用）——
#   just build              全项目构建：Python 校验/测试/打包 + 编译 APK
#   just build apk          只编译 APK
#   just build tools        只做 Python 那套
#   just install            自动判断装什么（adb 设备 → APK；Termux → 小部件；否则 → 命令行入口）
#   just install phone      编译并装到「手机」—— 多台 adb 设备（手机+平板）里认出手机那台
#   just install apk        编译并装到手机
#   just install cli        装 xiaomi-authkey / parse-log 命令行入口
#   just install termux     装 Termux 桌面小部件
#   just clean              清 Python 缓存与 dist/
#   just clean all          连 Android 产物一起清
#
#  —— 细分命令（要单独跑某一步时用）——
#   just apk [release]      编译 APK
#   just app-install        编译并装机（等价于 just install apk）
#   just android-clean      只清 Android 产物
#   just deps               联网拉一次依赖（改依赖版本后用）
#   just fetch              从 adb 连接的手机直接提取手环 AuthKey（手机端零操作）
#   just setup-phone        给手机装 Gadgetbridge 并配好权限
#
#  —— 环境变量 ——
#   ONLINE=1    让 gradle 走网络。默认一律离线，详见 tools/gradle.sh 里的说明。
#   DRY_RUN=1   让 just install 只打印计划，不真的装。
#   PY=...      指定 Python 解释器。
#
# 注意：本仓库要在 Windows(Git Bash) 和 Termux(Android) 两边跑，
#       所以 shebang 用 /usr/bin/env bash（不硬编码 Windows 路径），
#       并且 .gitattributes 强制 LF 行尾 —— CRLF 会让 Termux 的 bash 直接炸。
#
# 另一条踩过的坑：**带 shebang 的配方拿不到 `*ARGS` 位置参数**（$# 恒为 0），
#       只有 `{{...}}` 插值可靠。所以下面用「具名参数 + 插值」而不是 `$1`。
# =============================================================================

set shell := ["bash", "-uc"]

# 项目根目录（justfile 所在目录）
root := justfile_directory()

# Python 解释器：优先 python3，其次 python；可用 PY=... 覆盖
py := `command -v python3 2>/dev/null || command -v python 2>/dev/null || echo python`

# 源码文件（用于语法检查）
sources := "xiaomi_authkey.py parse_log.py tools/package.py"


# 默认：列出所有命令
default:
    @just --list


#   just build          两样都做
#   just build apk      只编译 APK
#   just build tools    只做 Python 那套
# 全项目构建：Python 工具链（校验 + 自检 + 测试 + 打包）→ Android APK
build target="all":
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{root}}"
    py="{{py}}"
    target="{{target}}"

    # 先确认在项目根目录，避免误操作到别处
    [ -f xiaomi_authkey.py ] || { echo "错误：不在项目根目录（{{root}}）"; exit 1; }

    case "$target" in
      all|"")          do_tools=1; do_apk=1 ;;
      apk|android)     do_tools=0; do_apk=1 ;;
      tools|py|python) do_tools=1; do_apk=0 ;;
      *) echo "未知目标：$target（可用：all / apk / tools）" >&2; exit 2 ;;
    esac

    if [ "$do_tools" -eq 1 ]; then
      echo "[1/5] Python 语法检查"
      for src in {{sources}}; do
        if [ -f "$src" ]; then
          "$py" -m py_compile "$src"
          echo "      OK  $src"
        fi
      done
      if compgen -G "tests/*.py" > /dev/null; then
        "$py" -m py_compile tests/*.py
        echo "      OK  tests/*.py"
      fi
      # 跨平台 shell 脚本也要过语法 —— 它们要在 Termux 和 Windows(Git Bash)
      # 两边都跑，写错了不会在任何单侧编译里暴露
      for sh in termux/*.sh tools/*.sh; do
        [ -f "$sh" ] || continue
        bash -n "$sh"
        echo "      OK  $sh"
      done

      echo "[2/5] 离线自检（密码学实现 vs 上游抓包向量）"
      "$py" xiaomi_authkey.py --selftest

      echo "[3/5] 测试套件"
      "$py" -m unittest discover -s tests -p "test_*.py"

      echo "[4/5] 打包"
      "$py" tools/package.py
    else
      echo "[1-4/5] 跳过 Python 工具链"
    fi

    if [ "$do_apk" -eq 1 ]; then
      echo "[5/5] Android APK（assembleDebug）"
      bash tools/gradle.sh assembleDebug
      apkfile="$(ls app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1 || true)"
      if [ -n "$apkfile" ]; then
        echo "      APK：$apkfile（$(du -h "$apkfile" | cut -f1)）"
      fi
    else
      echo "[5/5] 跳过 Android"
    fi

    echo
    echo "构建完成。"


#   just install                自动判断
#   just install phone          装到「手机」：多台 adb 设备里按 ro.build.characteristics 挑 phone 那台
#   just install apk [serial]   装到手机
#   just install cli [目录]     装命令行入口（默认 ~/.local/bin）
#   just install termux         装 Termux 桌面小部件
#   just install /some/dir      等价于 install cli /some/dir
#   DRY_RUN=1 just install      只打印计划，不真的装
#   just install                自动判断
#   just install phone          装到「手机」：多台 adb 设备里认出手机那台（跳过平板）
#   just install apk [serial]   装到手机
#   just install cli [目录]     装命令行入口（默认 ~/.local/bin）
#   just install termux         装 Termux 桌面小部件
#   just install /some/dir      等价于 install cli /some/dir
#   DRY_RUN=1 just install      只打印计划，不真的装
#
# 实现体在 tools/just_install.sh —— 刻意用「不带 shebang 的单行薄包装」
#（fetch、setup-phone 同款模式）：Windows 上 just 执行 shebang 配方要把
# 临时脚本路径（…\Temp\just-XXXX\install）拼进命令字符串，个别终端环境会把
# C:\Users\... 的反斜杠当转义吃掉，bash 打不开脚本直接 127。
#
# 安装到该装的地方：没给目标就自动判断。
install target="auto" extra="":
    bash tools/just_install.sh "{{target}}" "{{extra}}"


#   just clean          只清 Python 侧
#   just clean all      连 Android 产物一起清
# 清理缓存与构建产物（只动本仓库内的东西）
clean what="tools":
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{root}}"
    what="{{what}}"

    # 安全网：清错目录会造成真实损失，这里先确认身份
    [ -f xiaomi_authkey.py ] || { echo "错误：不在项目根目录（{{root}}）"; exit 1; }

    case "$what" in
      tools|py|python|all|"") ;;
      *) echo "未知目标：$what（可用：tools / all）" >&2; exit 2 ;;
    esac

    removed=0

    # 0) Android 产物（要先停 gradle 守护进程，否则 Windows 上文件被占着删不掉）
    if [ "$what" = "all" ] && [ -d app/build ]; then
      if [ -f ./gradlew ]; then
        ./gradlew --stop >/dev/null 2>&1 || true
      fi
      # 这里用 rm -rf 而不是 `gradlew clean`：gradle 的 clean 任务会连
      # app/build/gradle.log 一起删，而那个文件正被本脚本的重定向打开着，
      # Windows 上会直接删失败。
      if rm -rf app/build 2>/dev/null; then
        echo "  removed  app/build/"
        removed=$((removed + 1))
      else
        echo "  ! 删不掉 app/build —— 被沙箱的批量删除保护拦了，或文件被占用" >&2
        echo "    手动清：./gradlew --stop && rm -rf app/build" >&2
        exit 1
      fi
    fi

    # 1) 构建产物
    if [ -d dist ]; then
      rm -rf dist
      echo "  removed  dist/"
      removed=$((removed + 1))
    fi

    # 2) 缓存与临时文件
    if [ -d __pycache__ ]; then
      rm -rf __pycache__
      echo "  removed  __pycache__/"
      removed=$((removed + 1))
    fi
    while IFS= read -r d; do
      rm -rf "$d"
      echo "  removed  ${d#./}/"
      removed=$((removed + 1))
    done < <(find . -type d -name __pycache__ -not -path "./.git/*" 2>/dev/null)
    while IFS= read -r f; do
      rm -f "$f"
      removed=$((removed + 1))
    done < <(find . -type f \( -name '*.pyc' -o -name '*.pyo' \) -not -path "./.git/*" 2>/dev/null)

    for f in .picked.log picked.log .coverage; do
      if [ -e "$f" ]; then
        rm -f "$f"
        echo "  removed  $f"
        removed=$((removed + 1))
      fi
    done

    echo
    if [ "$removed" -eq 0 ]; then
      echo "没有需要清理的东西，仓库已经是干净的。"
    else
      echo "清理完成（$removed 项）。源码与 tests/ 未受影响。"
    fi


# =============================================================================
# Android 手环管理 App（Kotlin + Jetpack Compose）
#
# gradle 一律通过 tools/gradle.sh 调用：默认离线、输出进 app/build/gradle.log、
# 失败时告诉你是缓存缺依赖还是代码问题。要联网用 ONLINE=1（或 just deps）。
#
# 注意别用 `just apk | tail` 这类管道去看进度 —— 管道会把输出攒到最后一次性吐出，
# 看起来像卡住了。构建日志已经在 app/build/gradle.log 里。
# =============================================================================

# 编译 APK（variant 默认 debug，可传 release）
apk variant="debug":
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{root}}"
    case "{{variant}}" in
      debug)   task=assembleDebug ;;
      release) task=assembleRelease ;;
      *)       echo "未知 variant：{{variant}}（只支持 debug / release）" >&2; exit 2 ;;
    esac
    bash tools/gradle.sh "$task"
    echo
    ls -lh app/build/outputs/apk/{{variant}}/*.apk 2>/dev/null || true


# 编译并安装到手机（serial 可省略；多台设备时 just app-install <serial>）
app-install serial="":
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{root}}"
    bash tools/gradle.sh assembleDebug
    apkfile="$(ls app/build/outputs/apk/debug/*.apk 2>/dev/null | head -1 || true)"
    [ -n "$apkfile" ] || { echo "错误：没找到 debug APK 产物" >&2; exit 1; }
    bash tools/android_install.sh "$apkfile" "{{serial}}"


# 清理 Android 构建产物
android-clean:
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{root}}"
    # 先停守护进程：Windows 上它握着 app/build 里的文件，直接删会失败
    if [ -f ./gradlew ]; then
      ./gradlew --stop >/dev/null 2>&1 || true
    fi
    rm -rf app/build
    echo "已清理 Android 构建产物（app/build）。"


# 联网拉一次依赖 —— 只在改了依赖版本、或报「缓存里没有」时用
deps:
    #!/usr/bin/env bash
    set -euo pipefail
    cd "{{root}}"
    echo "==> 联网刷新依赖（这一步会等网络；之后照旧离线构建）"
    ONLINE=1 bash tools/gradle.sh --refresh-dependencies assembleDebug


# =============================================================================
# 配网 / 取密钥工具链（Python）
# =============================================================================

# 从 adb 连接的安卓手机直接提取手环 AuthKey（手机端零操作；多台设备时 just fetch <serial>）
fetch serial="":
    bash tools/adb_fetch.sh {{serial}}


# 给 adb 连接的手机装 Gadgetbridge 并配好权限（体检：just setup-phone -- --check-only）
setup-phone *ARGS:
    bash tools/setup_phone.sh {{ARGS}}

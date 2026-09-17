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
#   just install            只装不编译：自动判断装什么（adb 设备 → APK；否则 → 命令行入口）
#   just install phone      只装不编译：装到「手机」—— 多台 adb 设备（手机+平板）里认出手机那台
#   just install apk        只装不编译：把现成的 debug APK 装到手机
#   just app-install        编译 + 安装一步到位
#   just install cli        装 xiaomi-authkey / parse-log 命令行入口
#   just install termux     装 Termux 桌面小部件
#   just clean              清 Python 缓存与 dist/
#   just clean all          连 Android 产物一起清
#
#  —— 细分命令（要单独跑某一步时用）——
#   just apk [release]      编译 APK
#   just app-install        编译并装机（编译 + 安装一步到位）
#   just android-clean      只清 Android 产物
#   just deps               联网拉一次依赖（改依赖版本后用）
#   just fetch              从 adb 连接的手机直接提取手环 AuthKey（手机端零操作）
#   just setup-phone        给手机装 Gadgetbridge 并配好权限
#
#  —— 发正式版（给别人装 / Obtainium 在线升级）——
#   just release            出正式签名包 → dist/shouhuan.apk，并自检签名与版本
#   just release verify     只对 dist 里现成的包做自检（不编译）
#   just release bump         版本号 +1（patch，日常发版前先跑，不然用户更新装不上）
#   just release bump-minor    中段 +1（有新功能但兼容升级）
#   just release bump-major    首段 +1（换签名 keystore → 必须卸载重装，直接上 major）
#   just release publish    打 tag + 建 GitHub Release 挂上 APK（要先 push 代码）
#
#  —— 环境变量 ——
#   ONLINE=1    让 gradle 走网络。默认一律离线，详见 tools/gradle.sh 里的说明。
#   DRY_RUN=1   让 just install 只打印计划，不真的装。
#   PY=...      指定 Python 解释器。
#
# =============================================================================
# 架构约定（重要，别改回去）：
#
# 1. **配方一律不带 shebang**。just 的 shebang 配方会把配方体写进
#    %TEMP%\just-XXXX\<配方名> 再交给解释器，Windows 上这条路径的反斜杠会被
#    吃掉（/bin/bash: C:Users...: No such file or directory → exit 127，
#    实测翻车）。所以每个配方都只是一行「不带 shebang 的薄包装」，由
#    `set shell` 的 bash 直接当参数执行 —— 走 -c 参数不落临时文件，没事。
#
# 2. **逻辑全在 tools/，Windows 一律走 PowerShell**：配方行经 bash -uc 只做
#    一次「调 powershell.exe -File tools/xxx.ps1」的派发（bash 在这里只是
#    传话的，不执行任何逻辑）。路径必须用正斜杠 —— bash 会把未加引号的
#    tools\just_build.ps1 里的 \j 当转义吃掉。ps1 内部再按需经 Git Bash 调
#    gradle.sh / android_install.sh / termux/install.sh 这些跨平台 bash 工具。
#    .ps1 必须保存成 UTF-8 带 BOM（PowerShell 5.1 没 BOM 解析中文会乱码）。
# =============================================================================

set shell := ["bash", "-uc"]

# Windows 侧统一用 powershell.exe 跑 ps1（系统自带必有；ps1 内部自己找 Git Bash）
ps1 := "powershell.exe -NoProfile -ExecutionPolicy Bypass -File"


# 默认：列出所有命令
default:
    @just --list


#   just build          两样都做
#   just build apk      只编译 APK
#   just build tools    只做 Python 那套
# 全项目构建：Python 工具链（校验 + 自检 + 测试 + 打包）→ Android APK
build target="all":
    {{ ps1 }} tools/just_build.ps1 {{target}}


# 安装到该装的地方：没给目标就自动判断（DRY_RUN=1 只打印不执行）。
# **只装不编译** —— APK 目标装的是 app/build/outputs/apk/debug/ 下现成的包，
# 没有就报错提示先跑 just apk；要编译 + 安装一步到位用 just app-install。
#
#   just install                自动判断
#   just install phone          装到「手机」：多台 adb 设备里认出手机那台（跳过平板）
#   just install apk [serial]   装到手机
#   just install cli [目录]     装命令行入口（默认 ~/.local/bin）
#   just install termux         装 Termux 桌面小部件
#   just install /some/dir      等价于 install cli /some/dir
install target="auto" extra="":
    {{ ps1 }} tools/just_install.ps1 "{{target}}" "{{extra}}"


#   just clean          只清 Python 侧
#   just clean all      连 Android 产物一起清
# 清理缓存与构建产物（只动本仓库内的东西）
clean what="tools":
    {{ ps1 }} tools/just_clean.ps1 {{what}}


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
    {{ ps1 }} tools/just_build.ps1 {{variant}}


# 编译并安装到手机（serial 可省略；多台设备时 just app-install <serial>）
app-install serial="":
    {{ ps1 }} tools/just_build.ps1 apk && {{ ps1 }} tools/just_install.ps1 apk "{{serial}}"


# 清理 Android 构建产物
android-clean:
    {{ ps1 }} tools/just_clean.ps1 android


# =============================================================================
# 发正式版：本地出正式签名包 → GitHub Release → Obtainium 一键更新
#
# 为什么要有这套：debug 包签的是每台机器随机生成的 debug key，给别人装、或者
# 换台机器再发一版，用户点安装会 INSTALL_FAILED_UPDATE_INCOMPATIBLE —— 只能卸载
# 重装。所以对外分发的包由仓库根 keystore.properties 指定的那把正式 key 签（被 .gitignore 覆盖），
# keystore 本体在仓库外（debug.keystore，alias androiddebugkey），路径见 keystore.properties。
#
# 资产名固定为 shouhuan.apk（Obtainium 和 releases/latest/download/<name>
# 都按资产名精确匹配）。所以永久直链是：
#   https://github.com/PT123123/mibandhub/releases/latest/download/shouhuan.apk
#
# 标准发版顺序：
#   just release bump       → 提交版本号
#   just release            → 出包 + 自检
#   git push                → 代码先上去
#   just release publish    → 打 tag + 建 Release + 校验永久直链
# =============================================================================

#   just release            出包 + 自检
#   just release verify     只自检 dist 里现成的包
#   just release publish    发布（extra 可给 notes 文件路径）
#   just release bump       版本号 +1
#
# 出正式签名包 / 自检 / 打 tag 发 Release（产物 dist/shouhuan.apk + dist/shouhuan-<版本>.apk）
release target="package" extra="":
    {{ ps1 }} tools/just_release.ps1 "{{target}}" "{{extra}}"


# 联网拉一次依赖 —— 只在改了依赖版本、或报「缓存里没有」时用（ONLINE=1 透传给 gradle.sh）
deps:
    {{ ps1 }} tools/just_build.ps1 deps


# =============================================================================
# 配网 / 取密钥工具链 —— 纯 bash 工具（adb 抓取链路），直接经 Git Bash 跑
# =============================================================================

# 从 adb 连接的安卓手机直接提取手环 AuthKey（手机端零操作；多台设备时 just fetch <serial>）
fetch serial="":
    bash tools/adb_fetch.sh {{serial}}


# 给 adb 连接的手机装 Gadgetbridge 并配好权限（体检：just setup-phone -- --check-only）
setup-phone *ARGS:
    bash tools/setup_phone.sh {{ARGS}}

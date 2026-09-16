# just build / just apk / just deps 的实现体（PowerShell，唯一入口）。
#
# 为何逻辑搬进 tools/ 而不是写在 justfile 配方里：just 的 shebang 配方在
# Windows 上会把临时脚本路径的反斜杠吃掉（/bin/bash: C:Users...: No such
# file or directory → 127）。所以 justfile 里一律用「不带 shebang 的单行
# 薄包装」，只做一次 powershell.exe -File 派发，逻辑都在本文件里；内部
# 需要时再经 Git Bash 调 tools/gradle.sh 等 bash 工具。
#
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/just_build.ps1 <target>
#     all      Python 工具链（校验+自检+测试+打包）+ 编译 APK（默认）
#     apk      只编译 APK（debug）
#     debug    同 apk
#     release  只编译 release APK
#     deps     联网刷新依赖后编译（ONLINE=1 时透传给 gradle.sh）
#     tools    只跑 Python 工具链
#
#   PY=... 指定 Python 解释器；ONLINE=1 让 gradle 走网络。
# 注意：本文件必须保存成 UTF-8 带 BOM —— PowerShell 5.1 没有 BOM 会把中文解析成乱码。
param([string]$Target = "all")
$ErrorActionPreference = "Stop"

# 管道/重定向场景下的中文编码：直接写控制台用 WriteConsoleW 不受影响，
# 但被 bash/CI 捕获时走 [Console]::OutputEncoding，默认是 GBK 会变乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
if (-not (Test-Path "xiaomi_authkey.py")) {
    Write-Host "错误：不在项目根目录（$root）"
    exit 1
}

# python：PY=... 覆盖 > python3 > python
$py = $env:PY
if (-not $py) {
    $py = "python"
    if (Get-Command python3 -ErrorAction SilentlyContinue) { $py = "python3" }
}

$doTools = $false
$doApk = $false
$task = "assembleDebug"
$gradleExtra = @()
switch ($Target) {
    { @("", "all") -contains $_ } { $doTools = $true; $doApk = $true }
    { @("apk", "android", "debug") -contains $_ } { $doApk = $true }
    "release" { $doApk = $true; $task = "assembleRelease" }
    "deps" { $doApk = $true; $gradleExtra = @("--refresh-dependencies") }
    { @("tools", "py", "python") -contains $_ } { $doTools = $true }
    default {
        Write-Host "未知目标：$Target（可用：all / apk / release / tools / deps）"
        exit 2
    }
}

# 跑 Python 命令并在非零退出时终止（PS 5.1 原生命令非零不会自动停）
function Invoke-PyChecked {
    & $py @args
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

# gradle.sh / *.sh 都是 bash 脚本，而且必须在 Git Bash 里跑：裸调 `bash`
# 会拿到 WSL 的 bash（System32 的 shim），那里面没有 Android SDK 和依赖缓存。
# 从 git.exe 的安装位置推导 Git Bash，再退到几个标准安装路径 ——
# 与 just_install.ps1 里的同名逻辑保持一致。
function Get-GitBash {
    $cands = @()
    $git = (Get-Command git -ErrorAction SilentlyContinue).Source
    if ($git) {
        $gitRoot = Split-Path -Parent (Split-Path -Parent $git)   # <Git>\cmd\git.exe → <Git>
        $cands += (Join-Path $gitRoot "bin\bash.exe")
        $cands += (Join-Path $gitRoot "usr\bin\bash.exe")
    }
    $cands += @(
        (Join-Path $env:ProgramFiles "Git\bin\bash.exe"),
        (Join-Path ${env:ProgramFiles(x86)} "Git\bin\bash.exe"),
        (Join-Path $env:LOCALAPPDATA "Programs\Git\bin\bash.exe")
    )
    foreach ($c in $cands) {
        if ($c -and (Test-Path $c)) { return $c }
    }
    # 还没有就从所有 bash.exe 里挑一个非 System32 的（System32 的是 WSL shim）
    $b = Get-Command bash.exe -All -ErrorAction SilentlyContinue |
        Where-Object { $_.Source -notmatch 'System32' } |
        Select-Object -First 1
    if ($b) { return $b.Source }
    return $null
}
$script:gitBash = Get-GitBash

function Invoke-BashChecked([string[]]$BashArgs) {
    if (-not $script:gitBash) {
        Write-Host "错误：找不到 Git Bash —— gradle/sh 脚本要用它跑。"
        Write-Host "装 Git for Windows，或把它的 bash.exe 加进 PATH。"
        exit 1
    }
    & $script:gitBash @BashArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if ($doTools) {
    Write-Host "[1/5] Python 语法检查"
    foreach ($src in @("xiaomi_authkey.py", "parse_log.py", "tools/package.py")) {
        if (Test-Path $src) {
            Invoke-PyChecked -m py_compile $src
            Write-Host "      OK  $src"
        }
    }
    $tests = @(Get-ChildItem "tests" -Filter "*.py" -ErrorAction SilentlyContinue)
    if ($tests.Count -gt 0) {
        Invoke-PyChecked -m py_compile @($tests | ForEach-Object { $_.FullName })
        Write-Host "      OK  tests/*.py"
    }
    # 跨平台 shell 脚本也要过语法 —— 它们要在 Termux 和 Windows(Git Bash)
    # 两边都跑，写错了不会在任何单侧编译里暴露
    foreach ($sh in @(Get-ChildItem "termux/*.sh", "tools/*.sh" -ErrorAction SilentlyContinue)) {
        Invoke-BashChecked @("-n", $sh.FullName)
        Write-Host "      OK  $($sh.FullName)"
    }

    Write-Host "[2/5] 离线自检（密码学实现 vs 上游抓包向量）"
    Invoke-PyChecked xiaomi_authkey.py --selftest

    Write-Host "[3/5] 测试套件"
    Invoke-PyChecked -m unittest discover -s tests -p "test_*.py"

    Write-Host "[4/5] 打包"
    Invoke-PyChecked tools/package.py
}
else {
    Write-Host "[1-4/5] 跳过 Python 工具链"
}

if ($doApk) {
    Write-Host "[5/5] Android APK（$task）"
    Invoke-BashChecked (@("tools/gradle.sh") + $gradleExtra + @($task))
    $variant = "debug"
    if ($task -eq "assembleRelease") { $variant = "release" }
    $apk = Get-ChildItem "app/build/outputs/apk/$variant/*.apk" -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if ($apk) {
        Write-Host ("      APK：{0}（{1:N1} MB）" -f $apk.FullName, ($apk.Length / 1MB))
    }
}
else {
    Write-Host "[5/5] 跳过 Android"
}

Write-Host ""
Write-Host "构建完成。"

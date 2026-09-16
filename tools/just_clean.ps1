# just clean / just android-clean 的实现体（PowerShell，唯一入口）。
#
# justfile 里只是一行薄包装（shebang 配方在 Windows 上会被 just 的临时
# 脚本路径反斜杠坑掉，见 just_build.ps1 头注释），逻辑都在本文件里。
#
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/just_clean.ps1 <target>
#     tools    只清 Python 侧（默认）
#     android  只清 Android 产物（app/build）
#     all      连 Android 产物一起清
# 注意：本文件必须保存成 UTF-8 带 BOM —— PowerShell 5.1 没有 BOM 会把中文解析成乱码。
param([string]$What = "tools")
$ErrorActionPreference = "Stop"

# 管道/重定向场景下的中文编码：直接写控制台用 WriteConsoleW 不受影响，
# 但被 bash/CI 捕获时走 [Console]::OutputEncoding，默认是 GBK 会变乱码
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch { }

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# 安全网：清错目录会造成真实损失，这里先确认身份
if (-not (Test-Path "xiaomi_authkey.py")) {
    Write-Host "错误：不在项目根目录（$root）"
    exit 1
}

if (@("", "tools", "py", "python", "all", "android") -notcontains $What) {
    Write-Host "未知目标：$What（可用：tools / android / all）"
    exit 2
}

$removed = 0

# 0) Android 产物（要先停 gradle 守护进程，否则 Windows 上文件被占着删不掉）
if (($What -eq "all" -or $What -eq "android") -and (Test-Path "app/build")) {
    if (Test-Path ".\gradlew.bat") {
        & .\gradlew.bat --stop 2>&1 | Out-Null
    }
    # 这里不用 `gradlew clean`：它会连 app/build/gradle.log 一起删，而那个
    # 文件可能正被日志重定向打开着，Windows 上会直接删失败。
    $ok = $true
    try { Remove-Item -Recurse -Force "app/build" -ErrorAction Stop }
    catch { $ok = $false }
    if ($ok) {
        Write-Host "  removed  app/build/"
        $removed++
    }
    else {
        Write-Host "  ! 删不掉 app/build —— 被沙箱的批量删除保护拦了，或文件被占用"
        Write-Host "    手动清：.\gradlew.bat --stop; Remove-Item -Recurse -Force app\build"
        exit 1
    }
}

# 1) 构建产物
if (Test-Path "dist") {
    Remove-Item -Recurse -Force "dist"
    Write-Host "  removed  dist/"
    $removed++
}

# 2) 缓存与临时文件（跳过 .git 里的）
Get-ChildItem -Recurse -Directory -Filter "__pycache__" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notlike "*\.git\*" } |
    ForEach-Object {
        Remove-Item -Recurse -Force $_.FullName
        Write-Host ("  removed  " + $_.FullName.Substring($root.Length + 1) + "/")
        $removed++
    }
Get-ChildItem -Recurse -File -Include "*.pyc", "*.pyo" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notlike "*\.git\*" } |
    ForEach-Object {
        Remove-Item -Force $_.FullName
        $removed++
    }

foreach ($f in @(".picked.log", "picked.log", ".coverage")) {
    if (Test-Path $f) {
        Remove-Item -Force $f
        Write-Host "  removed  $f"
        $removed++
    }
}

Write-Host ""
if ($removed -eq 0) {
    Write-Host "没有需要清理的东西，仓库已经是干净的。"
}
else {
    Write-Host "清理完成（$removed 项）。源码与 tests/ 未受影响。"
}

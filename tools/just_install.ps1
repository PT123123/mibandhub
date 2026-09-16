# tools/just_install.ps1 —— just install 的 PowerShell 实现（唯一入口）。
#
# 为什么有它：just → bash 的这条链路在个别终端环境里，bash 继承到的 adb
# 可能不是平时用的那个（profile 动过 PATH、多个 platform-tools 互杀 server），
# 甚至环境变量都缺（LOCALAPPDATA unbound）。用户的交互终端是 pwsh 7，
# 就让 Windows 上直接跑 PowerShell，绕开 bash 继承环境的坑。
#
# cli / termux / 直接给路径这几种目标本来就是 bash 工具链的活
# （tools/install_cli.sh、termux/install.sh），在这里经 Git Bash 转过去。
param(
    [string]$Target = "auto",
    [string]$Extra = ""
)
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
$DryRun = $env:DRY_RUN -eq "1"

# gradle.sh / android_install.sh / install_cli.sh 都是 bash 脚本，而且必须在
# Git Bash 里跑：pwsh 里裸调 `bash` 会拿到 WSL 的 bash（System32 的 shim），
# 那里面没有 Android SDK 和依赖缓存，gradle 一进 /mnt/c 就离线解析失败。
# 从 git.exe 的安装位置推导 Git Bash，再退到几个标准安装路径。
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

function Invoke-BashTool([string]$ScriptPath, [string[]]$ScriptArgs) {
    if (-not $script:gitBash) {
        Write-Host "错误：找不到 Git Bash —— gradle/装机脚本要用它跑。"
        Write-Host "装 Git for Windows，或把它的 bash.exe 加进 PATH。"
        exit 1
    }
    & $script:gitBash $ScriptPath @ScriptArgs
}

# 位置参数直接给路径（just install /some/dir、~\bin 之类）时当成 cli 的目标目录
if ($Target -match '^(~|\./|\.\./|[A-Za-z]:[\\/]|/|\\)') {
    $Extra = $Target
    $Target = "cli"
}

$sdkAdb = if ($env:LOCALAPPDATA) { Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe" } else { $null }
$script:adb = (Get-Command adb -ErrorAction SilentlyContinue).Source
$script:serials = @()
$script:rawOut = ""

# 查一个 adb 的设备列表：返回 state=device 的 serial 数组
function Get-Serials([string]$AdbExe) {
    if (-not $AdbExe -or -not (Test-Path $AdbExe)) { return @() }
    $script:rawOut = ((& $AdbExe devices 2>&1 | Out-String) -replace "`r", "")
    $serials = @()
    foreach ($line in ($script:rawOut -split "`n")) {
        $p = $line.Trim() -split "\s+"
        if ($p.Count -ge 2 -and $p[1] -eq "device") { $serials += $p[0] }
    }
    return ,$serials
}

# 选一个能用的 adb：PATH 里的优先；查不到设备就等 2 秒重试；
# 还没有就试标准 SDK 路径 —— 多个版本的 adb 会互相杀 server，
# 谁后查谁说了算，刚重启完的第一次查询经常是空的。
function Pick-Adb {
    if (-not $script:adb) { return }
    $script:serials = Get-Serials $script:adb
    if ($serials.Count -eq 0) {
        Start-Sleep -Seconds 2
        $script:serials = Get-Serials $script:adb
    }
    if ($serials.Count -eq 0 -and $sdkAdb -and (Test-Path $sdkAdb) -and $sdkAdb -ne $script:adb) {
        $s2 = Get-Serials $sdkAdb
        if ($s2.Count -gt 0) {
            $script:adb = $sdkAdb
            $script:serials = $s2
            Write-Host "    （换用 $sdkAdb）"
        }
    }
    $env:ADB = $script:adb
}

# 这台是不是手机（小米手机 characteristics 报 nosdcard，要按屏幕对角线兜底）
function Test-IsPhone([string]$Serial) {
    $chars = (& $script:adb -s $Serial shell getprop ro.build.characteristics 2>$null | Out-String).Trim()
    if ($chars -match 'tablet|watch|television') { return $false }
    if ($chars -match 'phone') { return $true }
    $m = [regex]::Match((& $script:adb -s $Serial shell wm size 2>$null | Out-String), '(\d+)x(\d+)')
    if (-not $m.Success) { return $false }
    $md = [regex]::Match((& $script:adb -s $Serial shell wm density 2>$null | Out-String), '(\d+)')
    if (-not $md.Success -or [double]$md.Groups[1].Value -le 0) { return $false }
    $w = [double]$m.Groups[1].Value
    $h = [double]$m.Groups[2].Value
    $dpi = [double]$md.Groups[1].Value
    $inches = [math]::Sqrt([math]::Pow($w / $dpi, 2) + [math]::Pow($h / $dpi, 2))
    return ($inches -gt 0 -and $inches -lt 7)
}

if ($Target -eq "auto") {
    $Target = ""
    if (Get-Command adb -ErrorAction SilentlyContinue) {
        Pick-Adb
        if ($serials.Count -gt 0) {
            if ($serials.Count -eq 1) {
                $serial = $serials[0]
                Write-Host "自动判断：检测到 adb 设备 $serial → 安装 APK"
            } else {
                $serial = $null
                foreach ($s in $serials) {
                    if (Test-IsPhone $s) { $serial = $s; break }
                }
                if ($serial) {
                    Write-Host "自动判断：$($serials.Count) 台 adb 设备，认出手机 $serial → 安装 APK"
                } else {
                    $serial = $serials[0]
                    Write-Host "自动判断：$($serials.Count) 台 adb 设备里没认出手机，用第一台 $serial → 安装 APK"
                }
            }
            $Target = "apk"
            $Extra = $serial
        }
    }
    if (-not $Target) {
        Write-Host "自动判断：没有 adb 设备 → 安装命令行入口到 ~/.local/bin"
        $Target = "cli"
    }
}

if ($Target -eq "phone") {
    if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
        Write-Host "错误：找不到 adb，装不了手机"
        exit 1
    }
    Pick-Adb
    if ($serials.Count -eq 0) {
        Write-Host "错误：adb 里查不到处于 device 状态的设备。用的 adb：$script:adb"
        Write-Host "adb 的原始输出："
        $script:rawOut -split "`n" | ForEach-Object { Write-Host "    $_" }
        Write-Host "排查建议：① 直接插拔一次手机；② 重跑一次（server 冷启动的第一次查询经常是空的）；③ 绕过探测：just install apk <serial>"
        exit 1
    }
    $serial = $null
    foreach ($s in $serials) {
        $chars = (& $script:adb -s $s shell getprop ro.build.characteristics 2>$null | Out-String).Trim()
        $model = (& $script:adb -s $s shell getprop ro.product.model 2>$null | Out-String).Trim()
        $tag = ""
        if (Test-IsPhone $s) {
            $tag = " ← 手机"
            if (-not $serial) { $serial = $s }
        }
        Write-Host "    adb 设备：$s（$(if ($model) { $model } else { '型号未知' })）→ characteristics=$(if ($chars) { $chars } else { '（没报）' })$tag"
    }
    if (-not $serial) {
        Write-Host "错误：连着的设备里没认出手机（平板按 characteristics / 屏幕尺寸排除后一台不剩）。"
        Write-Host "      要么确认手机已插好并授权调试，要么明确指定：just install apk <serial>"
        exit 1
    }
    Write-Host "选定手机：$serial → 安装 APK"
    $Target = "apk"
    $Extra = $serial
}

switch -Exact ($Target) {
    "apk" {
        if ($DryRun) {
            Write-Host ""
            Write-Host "    [dry-run] bash tools/android_install.sh <debug apk> $Extra"
            exit 0
        }
        # 只装不编译：找现成的 debug APK。没有就报错让用户先编译，
        # 绝不在这里偷偷触发 gradle —— 编译要等几分钟，装机的语义就该是秒装。
        $apk = Get-ChildItem "app/build/outputs/apk/debug/*.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $apk) {
            Write-Host "错误：没找到已编译的 debug APK（app/build/outputs/apk/debug/）。"
            Write-Host "先编译一次：just apk （或 just build apk），之后 just install 直接装现成的。"
            exit 1
        }
        Write-Host ""
        Write-Host "==> 安装已有 APK：$($apk.Name)（编译于 $($apk.LastWriteTime.ToString('yyyy-MM-dd HH:mm'))）"
        Invoke-BashTool "tools/android_install.sh" @($apk.FullName, $Extra)
        exit $LASTEXITCODE
    }
    "termux" {
        if ($DryRun) {
            Write-Host "    [dry-run] bash termux/install.sh"
            exit 0
        }
        Invoke-BashTool "termux/install.sh" @()
        exit $LASTEXITCODE
    }
    "cli" {
        if ($DryRun) {
            Write-Host "    [dry-run] bash tools/install_cli.sh $Extra"
            exit 0
        }
        Invoke-BashTool "tools/install_cli.sh" @($Extra)
        exit $LASTEXITCODE
    }
    default {
        Write-Host "未知安装目标：$Target"
        Write-Host "可用：auto（默认）/ phone（认出手机）/ apk [serial] / cli [目录] / termux"
        exit 2
    }
}

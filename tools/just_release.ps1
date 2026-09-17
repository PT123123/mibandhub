# just release 的实现体（PowerShell，唯一入口）。
#
# 用途：把「本地出正式签名包 → 推到 GitHub Release → 给 Obtainium 一个永久直链」
# 这条路固定成一条命令，每一步都自检。设计要点（都是踩过的坑）：
#
#   * 资产名**固定**为 shouhuan.apk。Obtainium 和 releases/latest/download/<name>
#     都按资产名精确匹配，名字里带版本号的话每发一版都得改配置 / 改直链。
#     留档用的 shouhuan-<版本>.apk 是同内容的第二个名字。
#   * release 包必须签的是「仓库 blessing 的正式证书」：自检按证书 SHA256 精确比对放行，
#     而不是按 CN=Android Debug 字符串拦。当前 blessing 的 key 是
#     仓库外备份的那把固定 keystore（alias androiddebugkey，
#     不是每台机器随机生成的 debug key），所以可以分发且能覆盖升级。
#   * 发布前必须：工作区干净 + HEAD 已经 push + 包是当前源码出的（比源码指纹，不比时间
#     —— 先出包后提交是正常顺序，拿提交时间比一定误判）。
#     发完再真下载一次比 sha256 —— 只看网页显示「已发布」不算验证。
#   * 每条 gh 命令都显式带 -R <owner/repo>：仓库里一旦出现 upstream remote，
#     gh 会优先往 upstream 发（422 target_commitish is invalid），别依赖它自己推断。
#
#   powershell.exe -NoProfile -ExecutionPolicy Bypass -File tools/just_release.ps1 <target> [extra]
#     package   出正式签名包（默认）：assembleRelease → dist/ → 自检
#     publish   打 tag + 建 GitHub Release（extra = 自定义 notes 文件路径，可省）
#     bump        版本号 +1（versionCode+1、versionName 末段+1），日常发版前跑
#     bump-minor  中段 +1、末段归零（有新功能但兼容升级）
#     bump-major  首段 +1、中末归零（换签名 keystore → 必须卸载重装，直接上 major）
#     verify    只对 dist/shouhuan.apk 做自检（不编译、不发布）
#
#   ONLINE=1 让 gradle 走网络；GH=... 指定 gh 可执行文件。
# 注意：本文件必须保存成 UTF-8 带 BOM —— PowerShell 5.1 没有 BOM 会把中文解析成乱码。
param([string]$Target = "package", [string]$Extra = "")
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

# 固定名 = Obtainium / latest/download 认的那个；带版本号的那份是留档副本
$AssetFixed = "shouhuan.apk"
$PackageName = "com.ted.shouhuan"
$nl = [Environment]::NewLine
$script:gh = "gh"
$script:GhOutput = ""

function Say([string]$msg) { Write-Host $msg }

# 跑一条 gh 命令，返回退出码（输出留在 $script:GhOutput）。
# 关键点：gh 在「没找到」这类正常分支上也会往 stderr 写东西，而 $ErrorActionPreference=Stop
# 会把原生命令的 stderr 输出升级成终止性错误（NativeCommandError）—— 脚本还没走到读
# 退出码就挂了。所以这里临时把偏好调回 Continue。
function Invoke-Gh([string[]]$GhArgs) {
    $eap = $ErrorActionPreference
    $ErrorActionPreference = "Continue"
    try {
        $script:GhOutput = ((& $script:gh @GhArgs 2>&1) | Out-String)
        return $LASTEXITCODE
    }
    finally { $ErrorActionPreference = $eap }
}

function Fail([string]$msg) {
    Write-Host ""
    Write-Host "✗ $msg"
    exit 1
}

# ---------------------------------------------------------------- git / 版本

function Get-AppVersion {
    $path = Join-Path $root "app/build.gradle.kts"
    # 用 File.ReadAllText（默认 UTF-8）而不是 Get-Content -Raw：PS 5.1 的
    # Get-Content 没 BOM 时按 ANSI 解，文件里的中文注释会变乱码
    $txt = [System.IO.File]::ReadAllText($path)
    $c = [regex]::Match($txt, 'versionCode\s*=\s*(\d+)')
    $n = [regex]::Match($txt, 'versionName\s*=\s*"([^"]+)"')
    if (-not $c.Success -or -not $n.Success) {
        Fail "从 app/build.gradle.kts 里读不到 versionCode / versionName"
    }
    return [pscustomobject]@{ Code = [int]$c.Groups[1].Value; Name = $n.Groups[1].Value }
}

# remote.origin.url 的两种写法都要认：git@github.com:owner/repo.git / https://github.com/owner/repo.git
function Get-RepoSlug {
    $url = "$(& git config --get remote.origin.url)".Trim()
    if (-not $url) { Fail "读不到 remote.origin.url" }
    $slug = $url -replace '\.git$', '' -replace '^git@[^:]+:', '' -replace '^https?://[^/]+/', ''
    if ($slug -notmatch '^[^/]+/[^/]+$') { Fail "认不出 remote.origin.url 的形式：$url" }
    return $slug
}

# 「会影响 APK 的内容」的指纹：app/src 下所有受版本控制的文件 + 构建入口文件，按内容算 sha256。
#
# 为什么不用提交时间判包是不是过期：正常顺序是「先出包、后提交」，提交时间必然晚于
# 出包时间，拿它比一定判成过期，那个判据根本没法用。比内容才对：只要源码没动过，
# 一个旧包就还是当前源码的包。
function Get-SourceFingerprint {
    $files = @(& git ls-files "app/src" "app/build.gradle.kts" "build.gradle.kts" `
            "settings.gradle.kts" "gradle.properties") | Where-Object { $_ -and (Test-Path $_) }
    if (-not $files) { Fail "源码指纹：一个文件都没列出来" }
    $hashes = (& git hash-object @files) -join "`n"
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = $sha.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($hashes))
        return (([BitConverter]::ToString($bytes)) -replace '-', '').ToLower()
    }
    finally { $sha.Dispose() }
}

# ---------------------------------------------------------------- SDK / 工具链

function Get-AndroidSdk {
    if ($env:ANDROID_HOME) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT) { return $env:ANDROID_SDK_ROOT }
    if (Test-Path "local.properties") {
        foreach ($line in Get-Content "local.properties") {
            if ($line -match '^\s*sdk\.dir\s*=\s*(.+)$') {
                # local.properties 里是 Properties 转义形态：C\:\\Users\\...\\Sdk
                return ($matches[1].Trim() -replace '\\:', ':' -replace '\\\\', '\')
            }
        }
    }
    Fail "找不到 Android SDK（local.properties 里没有 sdk.dir，也没有 ANDROID_HOME）"
}

function Get-BuildTools {
    $dir = Join-Path (Get-AndroidSdk) "build-tools"
    $best = Get-ChildItem $dir -Directory -ErrorAction SilentlyContinue |
        Sort-Object -Property Name |
        Select-Object -Last 1
    if (-not $best) { Fail "$dir 下没有 build-tools" }
    return $best.FullName
}

# gradle.sh 是 bash 脚本，必须在 Git Bash 里跑：裸调 `bash` 会拿到 WSL 的 shim，
# 那里面没有 Android SDK 和依赖缓存。与 just_build.ps1 里的同名逻辑保持一致。
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
    $b = Get-Command bash.exe -All -ErrorAction SilentlyContinue |
        Where-Object { $_.Source -notmatch 'System32' } |
        Select-Object -First 1
    if ($b) { return $b.Source }
    return $null
}

# ---------------------------------------------------------------- 自检

function Test-Apk([string]$Apk) {
    if (-not (Test-Path $Apk)) { Fail "找不到 $Apk" }
    $bt = Get-BuildTools
    $ver = Get-AppVersion

    Say ""
    Say "== 自检：$Apk =="

    $apksigner = Join-Path $bt "apksigner.bat"
    if (-not (Test-Path $apksigner)) { Fail "找不到 $apksigner" }
    $certs = (& $apksigner verify --print-certs $Apk 2>&1 | Out-String)
    if ($LASTEXITCODE -ne 0) {
        Fail "apksigner 校验失败（包没签名？）`n$certs"
    }
    $subject = [regex]::Match($certs, 'Signer #1 certificate DN:\s*(.+)')
    $fp = [regex]::Match($certs, 'Signer #1 certificate SHA-256 digest:\s*(\S+)')
    $certSha = ($fp.Groups[1].Value -replace ':', '').ToUpper()
    Say ("  签名主体    {0}" -f $subject.Groups[1].Value.Trim())
    Say ("  证书 SHA256 {0}" -f $certSha)

    # 只认「仓库 blessing 的正式证书」：用证书 SHA256 精确比对，而不是按 CN=Android Debug
    # 这种字符串拦。手环管家的 release 现在签的是仓库外备份的那把固定 keystore
    # （alias androiddebugkey）—— 它的 DN 也是 CN=Android Debug，但这是一把固定、仓库外备份的
    # keystore（不是「每台机器随机生成」的那把），所以可以分发。换成别的 key/别的机器出的包，
    # SHA256 对不上这里会直接拦下，防止误发不可覆盖升级的包。
    $BlessedCertSha256 = "E0BB843A9192A9579727BE09083540A277B15BAC967D49CF26A8F30D750A6400"
    if ($certSha -ne $BlessedCertSha256) {
        Fail @"
这个包用的不是手环管家正式的签名证书（证书 SHA256 对不上）。
  期望: $BlessedCertSha256
  实际: $certSha
  release 包必须用能覆盖升级的正式 key 签（仓库外备份的那把固定 keystore，alias androiddebugkey）。
  检查：keystore.properties 指的对不对、app/build.gradle.kts 的 buildTypes.release 有没有接 signingConfig。
"@
    }

    $aapt2 = Join-Path $bt "aapt2.exe"
    if (-not (Test-Path $aapt2)) { Fail "找不到 $aapt2" }
    $badging = (& $aapt2 dump badging $Apk 2>&1 | Out-String)
    $m = [regex]::Match($badging, "package:\s*name='([^']+)'\s*versionCode='(\d+)'\s*versionName='([^']*)'")
    if (-not $m.Success) { Fail "aapt2 dump badging 里读不到 package 行" }
    Say ("  包名        {0}" -f $m.Groups[1].Value)
    Say ("  版本        {0}（versionCode {1}）" -f $m.Groups[3].Value, $m.Groups[2].Value)
    if ($m.Groups[1].Value -ne $PackageName) { Fail "包名不是 $PackageName" }
    if ([int]$m.Groups[2].Value -ne $ver.Code) { Fail "APK 里的 versionCode 与 build.gradle.kts 不一致，重新编译" }
    if ($m.Groups[3].Value -ne $ver.Name) { Fail "APK 里的 versionName 与 build.gradle.kts 不一致，重新编译" }

    $hash = (Get-FileHash -Path $Apk -Algorithm SHA256).Hash.ToLower()
    Say ("  体积        {0:N2} MB" -f ((Get-Item $Apk).Length / 1MB))
    Say ("  sha256      {0}" -f $hash)
    return $hash
}

# ---------------------------------------------------------------- package

function Invoke-Package {
    if (-not (Test-Path "keystore.properties")) {
        Fail @"
缺少 keystore.properties（正式签名材料），出不了可分发的 release 包。
  仓库里没有是正常的 —— 它被 .gitignore 覆盖，只存在于出包的机器上。
  恢复方式：把你仓库外备份的那把 keystore 配进仓库根 keystore.properties（storeFile / storePassword / keyAlias / keyPassword 四项）。
"@
    }

    $ver = Get-AppVersion
    $versioned = "shouhuan-$($ver.Name).apk"
    Say "== 出正式签名包：v$($ver.Name)（versionCode $($ver.Code)）=="

    # gradle 走 tools/gradle.sh：默认离线，ONLINE=1 才联网
    $gitBash = Get-GitBash
    if (-not $gitBash) { Fail "找不到 Git Bash —— gradle.sh 要用它跑" }
    & $gitBash "tools/gradle.sh" "assembleRelease"
    if ($LASTEXITCODE -ne 0) {
        # release 比 debug 多跑 lintVital，要额外的 lint 依赖；离线缓存里没有时
        # gradle 会直接失败。第一次出包碰到就联网补一次，之后照旧离线。
        $hint = ""
        if ((Test-Path "app/build/gradle.log") -and
            (Select-String -Path "app/build/gradle.log" -Pattern "No cached version|Could not resolve" -Quiet)) {
            $hint = $nl + "  日志显示有构件不在离线缓存里 —— 联网补一次即可，之后照旧离线：" + $nl +
                "    ONLINE=1 just release"
        }
        Fail ("gradle assembleRelease 失败（完整日志：app/build/gradle.log）" + $hint)
    }

    $out = "app/build/outputs/apk/release"
    $apk = Get-ChildItem "$out/*.apk" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike "*unsigned*" } |
        Select-Object -First 1
    if (-not $apk) { Fail "$out 下没有签名后的 APK（只剩 unsigned 包说明签名没接上）" }

    New-Item -ItemType Directory -Force -Path "dist" | Out-Null
    Copy-Item $apk.FullName "dist/$AssetFixed" -Force
    Copy-Item $apk.FullName "dist/$versioned" -Force
    Say ("  产物        {0}" -f $apk.FullName)
    Say ("      → dist/{0}" -f $AssetFixed)
    Say ("      → dist/{0}" -f $versioned)

    $hash = Test-Apk "dist/$AssetFixed"
    Set-Content -Path "dist/$AssetFixed.sha256" -Value ("{0}  {1}" -f $hash, $AssetFixed) -Encoding Ascii

    # 记下出这个包时的源码状态，publish 时比一次 —— 防止「改了代码忘了重新出包就发版」
    $fp = Get-SourceFingerprint
    [System.IO.File]::WriteAllText((Join-Path $root "dist/$AssetFixed.build.txt"), $fp,
        (New-Object System.Text.UTF8Encoding($false)))
    Say ("  源码指纹    {0}…（存在 dist/{1}.build.txt）" -f $fp.Substring(0, 16), $AssetFixed)

    $slug = Get-RepoSlug
    Say ""
    Say "== 下一步 =="
    Say "  1) 提交并 push 代码（publish 会拦下没 push 的情况）"
    Say "  2) just release publish"
    Say ""
    Say ("  Obtainium：添加仓库 https://github.com/{0}" -f $slug)
    Say '  APK 筛选正则：^shouhuan\.apk$   （同名资产有两份，正则压实只认固定名那份）'
    Say ("  永久直链：https://github.com/{0}/releases/latest/download/{1}" -f $slug, $AssetFixed)
}

# ---------------------------------------------------------------- publish

function New-ReleaseNotes([string]$Tag, [string]$Slug, [string]$Versioned, [string]$Sha) {
    $path = "dist/release-notes.md"
    if ($Extra) {
        if (-not (Test-Path $Extra)) { Fail "指定的 notes 文件不存在：$Extra" }
        Copy-Item $Extra $path -Force
        Say "  使用自定义 notes：$Extra"
        return $path
    }

    # 上一个 tag：按版本序取一个不等于当前的（首个版本时为空）
    $prev = $null
    foreach ($t in @(& git tag --sort=-v:refname)) {
        if ($t -and $t -ne $Tag) { $prev = $t; break }
    }
    if ($prev) {
        $log = @(& git log --oneline --no-decorate "$prev..HEAD")
        $range = "$prev → $Tag"
    }
    else {
        $log = @(& git log --oneline --no-decorate -n 40)
        $range = "首个版本（最近 40 条提交）"
    }

    $ver = Get-AppVersion
    $nl = [Environment]::NewLine
    $lines = @(
        "## 安装"
        ""
        "    https://github.com/$Slug/releases/latest/download/$AssetFixed"
        ""
        "手机浏览器直接开上面的链接；或在 Obtainium 里添加仓库 https://github.com/$Slug 后一键更新。"
        "这个直链是**永久的**，每发一版自动指向最新，不用改。"
        ""
        "> 如果之前装过用别的签名 key 出的版本（旧正式 key 或 debug 包）：包名相同但签名不同，装不上 —— 先卸载旧版再装（配对信息要重填一次）。"
        ""
        "## 本次变更（$range）"
        ""
    )
    $lines += ($log | Where-Object { $_ } | ForEach-Object { "- $_" })
    $lines += @(
        ""
        "---"
        ""
        "- ``$AssetFixed`` —— 正式签名包，versionName $($ver.Name) / versionCode $($ver.Code)"
        "- ``$Versioned`` —— 同一份文件的留档副本（内容完全一致）"
        "- sha256（固定名那份）：``$Sha``"
    )

    [System.IO.File]::WriteAllText(
        (Join-Path $root $path), ($lines -join $nl) + $nl,
        (New-Object System.Text.UTF8Encoding($false)))
    return $path
}

function Invoke-Publish {
    $ver = Get-AppVersion
    $Tag = "v$($ver.Name)"
    $versioned = "shouhuan-$($ver.Name).apk"
    $slug = Get-RepoSlug

    if (-not (Test-Path "dist/$AssetFixed")) { Fail "dist/$AssetFixed 不存在，先跑 just release" }
    $Sha = (Get-FileHash -Path "dist/$AssetFixed" -Algorithm SHA256).Hash.ToLower()
    if (-not (Test-Path "dist/$versioned")) { Fail "dist/$versioned 不存在，先跑 just release" }

    # 包必须还是当前源码出的：比「出包时记下的源码指纹」，不是比时间
    # （先出包后提交是正常顺序，拿提交时间比一定误判）
    $fpFile = "dist/$AssetFixed.build.txt"
    if (-not (Test-Path $fpFile)) {
        Fail "dist/$AssetFixed.build.txt 不在 —— 这个包不是 just release 出的，重新出包再发版"
    }
    $fpSaved = ([System.IO.File]::ReadAllText((Join-Path $root $fpFile))).Trim()
    if ($fpSaved -ne (Get-SourceFingerprint)) {
        Fail @"
dist/$AssetFixed 不是当前源码出的（源码指纹对不上），发出去的是旧代码。
  重新出包：just release        然后再：just release publish
"@
    }

    Say "== 发布 $Tag =="

    # 1) 工作区必须干净 —— 否则 tag 指的不是你正在看的那棵树
    $dirty = @(& git status --porcelain)
    if ($dirty.Count -gt 0) {
        Fail ("工作区还有未提交的改动，先提交再发版：" + $nl + (($dirty | ForEach-Object { "  $_" }) -join $nl))
    }

    # 2) HEAD 必须已经 push —— 没 push 的话 tag 会打在远端找不到的 commit 上，
    #    gh 会报 target_commitish is invalid（而且 gh 是一次性调用，422 不留半成品）
    $branch = "$(& git rev-parse --abbrev-ref HEAD)".Trim()
    $head = "$(& git rev-parse HEAD)".Trim()
    $remote = "$(& git rev-parse --verify --quiet "origin/$branch")".Trim()
    if (-not $remote) { $remote = "(远端没有 origin/$branch)" }
    if ($head -ne $remote) {
        Fail ("本地 HEAD（{0}）与远端（{1}）不一致，先 push：git push origin {2}" -f `
            $head.Substring(0, 7), $remote, $branch)
    }

    # 3) 同一个 tag 指向不同 commit 是事故，直接拦
    $localTag = "$(& git rev-parse -q --verify "refs/tags/$Tag")".Trim()
    if ($localTag -and $localTag -ne $head) {
        Fail "本地已有 $Tag 且指向 $($localTag.Substring(0, 7))，不是当前 HEAD。换版本号：just release bump"
    }
    if (-not $localTag) {
        & git tag -a $Tag -m "shouhuan $Tag"
        if ($LASTEXITCODE -ne 0) { Fail "打 tag 失败" }
        Say "  已建 tag $Tag"
    }
    & git push origin $Tag
    if ($LASTEXITCODE -ne 0) { Fail "push tag 失败（远端已有同名 tag？）" }

    # 4) release notes
    $notes = New-ReleaseNotes $Tag $slug $versioned $Sha

    # 5) gh release（每条命令都显式 -R，别让 gh 自己猜仓库）
    $script:gh = $env:GH
    if (-not $script:gh) { $script:gh = "gh" }
    #    「有没有同名 Release」用 release list 判断，不用 release view：view 在不存在时
    #    会往 stderr 写 "release not found"，而 $ErrorActionPreference=Stop 会把原生命令的
    #    stderr 输出当成终止性错误（NativeCommandError），`2>$null` 也拦不住。
    if ((Invoke-Gh @("release", "list", "-R", $slug, "--json", "tagName", "-L", "200")) -ne 0) {
        Fail "gh release list 失败：$($script:GhOutput)"
    }
    $exists = $script:GhOutput -match ('"tagName"\s*:\s*"' + [regex]::Escape($Tag) + '"')

    $assets = @("dist/$AssetFixed", "dist/$versioned")
    if ($exists) {
        Say "  Release 已存在 → 只覆盖上传资产"
        if ((Invoke-Gh (@("release", "upload", $Tag, "-R", $slug, "--clobber") + $assets)) -ne 0) {
            Fail "gh release upload 失败：$($script:GhOutput)"
        }
    }
    else {
        if ((Invoke-Gh (@("release", "create", $Tag, "-R", $slug, "--title", $Tag,
                        "--notes-file", $notes, "--verify-tag") + $assets)) -ne 0) {
            Fail "gh release create 失败：$($script:GhOutput)"
        }
    }
    Say "  $($script:GhOutput.Trim())"

    # 6) 真下载一次比 sha256 —— 只看网页显示「已发布」不算验证
    $url = "https://github.com/$slug/releases/latest/download/$AssetFixed"
    Say ""
    Say "== 校验永久直链 =="
    Say "  $url"
    $tmp = Join-Path $env:TEMP "shouhuan-dl-check.apk"
    $ok = $false
    try {
        Invoke-WebRequest -Uri $url -OutFile $tmp -UseBasicParsing -TimeoutSec 180
        $dl = (Get-FileHash -Path $tmp -Algorithm SHA256).Hash.ToLower()
        if ($dl -eq $Sha) {
            Say ("  ✓ 下载成功，sha256 一致（{0}）" -f $dl)
            $ok = $true
        }
        else {
            Say ("  ✗ sha256 不一致：下载 {0} / 本地 {1}" -f $dl, $Sha)
        }
    }
    catch {
        Say "  ! 下载校验没跑通：$($_.Exception.Message)"
        Say "    GitHub 资产走 objects.githubusercontent.com，国内经常超时 —— 这不代表发布失败。"
    }
    finally {
        Remove-Item $tmp -ErrorAction SilentlyContinue
    }

    Say ""
    Say "发布完成：https://github.com/$slug/releases/tag/$Tag"
    if (-not $ok) { Say "（直链校验未通过/未完成，稍后可自己再下一次确认）" }
    Say ""
    Say "Obtainium：添加仓库 https://github.com/$slug，APK 筛选正则 ^shouhuan\.apk$"
}

# ---------------------------------------------------------------- bump
#
# 三种模式：
#   bump        末段 +1（patch）—— 日常发版用，不破坏升级
#   bump-minor  中段 +1、末段归零 —— 有新功能但兼容升级
#   bump-major  首段 +1、中末归零 —— 换签名 keystore 这类「必须卸载重装」的破坏性变更
#                约定：一旦切换 keystore，直接上 major，用版本号本身告诉用户「装不上更新」

function Invoke-Bump {
    param([string]$Kind = "patch")
    $ver = Get-AppVersion
    $newCode = $ver.Code + 1
    $parts = @($ver.Name -split '\.')
    while ($parts.Count -lt 3) { $parts += '0' }
    switch ($Kind) {
        "major" {
            $parts[0] = [string]([int]$parts[0] + 1); $parts[1] = '0'; $parts[2] = '0'
        }
        "minor" {
            $parts[1] = [string]([int]$parts[1] + 1); $parts[2] = '0'
        }
        default {
            $parts[2] = [string]([int]$parts[2] + 1)
        }
    }
    $newName = $parts[0..2] -join '.'

    $path = Join-Path $root "app/build.gradle.kts"
    $txt = [System.IO.File]::ReadAllText($path)
    $txt = $txt -replace "(versionCode\s*=\s*)$($ver.Code)\b", "`${1}$newCode"
    $txt = $txt -replace "(versionName\s*=\s*`")$([regex]::Escape($ver.Name))(`")", "`${1}$newName`${2}"
    [System.IO.File]::WriteAllText($path, $txt, (New-Object System.Text.UTF8Encoding($false)))

    Say "版本号：$($ver.Name) ($($ver.Code)) → $newName ($newCode)"
    Say "  改了 app/build.gradle.kts（记得随下次提交一起提交）"
    if ($Kind -eq "major") {
        Say "  ⚠ 这是 major 升级：签名 keystore 变了 → 已装旧版的用户升级会"
        Say "    INSTALL_FAILED_UPDATE_INCOMPATIBLE，必须卸载重装（配对信息重填）。"
    } else {
        Say "  然后：just release  →  just release publish"
    }
}

# ---------------------------------------------------------------- main

switch ($Target) {
    { @("", "package", "dist") -contains $_ } { Invoke-Package }
    "publish" { Invoke-Publish }
    "bump" { Invoke-Bump "patch" }
    "bump-minor" { Invoke-Bump "minor" }
    "bump-major" { Invoke-Bump "major" }
    "verify" { [void](Test-Apk "dist/$AssetFixed") }
    default {
        Say "未知目标：$Target（可用：package / publish / bump / bump-minor / bump-major / verify）"
        exit 2
    }
}

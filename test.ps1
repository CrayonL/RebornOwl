#requires -Version 7.0
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Mandatory)][string]$SdkRoot,
    [Parameter(Mandatory)][string]$JdkRoot,
    [Parameter(Mandatory)][string]$DexDirectory,
    [Parameter(Mandatory)][string]$PythonPath,
    [string]$DeviceSerial,
    [switch]$Offline
)
$ErrorActionPreference = 'Stop'
if (-not $Offline -and -not $DeviceSerial) { throw '真机测试需要 DeviceSerial；离线验证请显式使用 -Offline' }

function Invoke-TestCommand {
    param([string]$Program, [string[]]$Arguments)
    $info = [Diagnostics.ProcessStartInfo]::new($Program)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.StandardOutputEncoding = [Text.Encoding]::UTF8
    $info.StandardErrorEncoding = [Text.Encoding]::UTF8
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit(60000)) {
        $process.Kill($true)
        throw '测试超过 60 秒，记为失败'
    }
    Write-Host $stdout.GetAwaiter().GetResult()
    Write-Host $stderr.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw "测试命令失败：$Program，退出码 $($process.ExitCode)" }
    $process.Dispose()
}

$SdkRoot = (Resolve-Path -LiteralPath $SdkRoot).Path
$JdkRoot = (Resolve-Path -LiteralPath $JdkRoot).Path
$DexDirectory = (Resolve-Path -LiteralPath $DexDirectory).Path
$PythonPath = (Resolve-Path -LiteralPath $PythonPath).Path
$platform = Join-Path $SdkRoot 'platforms/android-35/android.jar'
$xposed = Join-Path $PSScriptRoot 'work/xposed-api.jar'
$modern = Join-Path $PSScriptRoot 'work/libxposed-api-101.0.1.jar'
$adb = Join-Path $SdkRoot 'platform-tools/adb.exe'
$classes = Join-Path $PSScriptRoot ('work/test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $classes | Out-Null
$sources = Get-ChildItem -LiteralPath "$PSScriptRoot/src/src", "$PSScriptRoot/work/test" -Filter '*.java' -Recurse | ForEach-Object FullName
Invoke-TestCommand "$JdkRoot/bin/javac.exe" (@('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:-options', '-classpath', "$platform;$modern;$xposed", '-d', $classes) + $sources)
$tests = @('DexIndexRegressionTest', 'CorrectFeatureTest', 'PremiumFeatureTest', 'UnlockLessonsFeatureTest', 'DexIndexTest', 'DateOverrideTest', 'SkipFeatureTest')
foreach ($test in $tests) {
    Invoke-TestCommand "$JdkRoot/bin/java.exe" @('-Dstdout.encoding=UTF-8', '-Dstderr.encoding=UTF-8', '-cp', "$classes;$platform;$modern;$xposed", "com.owl.auxiliary.$test", $DexDirectory)
}
Invoke-TestCommand $PythonPath @('-X', 'utf8', '-B', "$PSScriptRoot/work/test/host_contract_test.py", $DexDirectory)
Invoke-TestCommand $PythonPath @('-X', 'utf8', '-B', "$PSScriptRoot/work/test/host_date_contract_test.py", $DexDirectory)
Invoke-TestCommand $PythonPath @('-X', 'utf8', '-B', "$PSScriptRoot/work/test/host_unlock_contract_test.py", $DexDirectory)
foreach ($test in @('premium_feedback_test.py', 'source_link_test.py')) {
    Invoke-TestCommand $PythonPath @('-X', 'utf8', '-B', "$PSScriptRoot/work/test/$test", '--sdk', $SdkRoot, '--jdk', $JdkRoot)
}
$dex = Join-Path $classes 'dex'
New-Item -ItemType Directory -Path $dex | Out-Null
[xml]$manifest = Get-Content -LiteralPath "$PSScriptRoot/src/AndroidManifest.xml"
$minSdk = $manifest.manifest.'uses-sdk'.GetAttribute('minSdkVersion', 'http://schemas.android.com/apk/res/android')
$classFiles = Get-ChildItem -LiteralPath $classes -Filter '*.class' -Recurse | ForEach-Object FullName
Invoke-TestCommand "$JdkRoot/bin/java.exe" (@('-cp', "$SdkRoot/build-tools/36.1.0/lib/d8.jar", 'com.android.tools.r8.D8', '--min-api', $minSdk, '--output', $dex, '--lib', $platform, '--classpath', $modern, '--classpath', $xposed) + $classFiles)
if ($Offline) {
    Write-Host 'OFFLINE_TESTS_PASS（未连接手机；ART、实际 Hook 和课程进入仍待真机验证）'
    return
}
$remoteDex = '/data/local/tmp/owl-entry-test.dex'
Invoke-TestCommand $adb @('-s', $DeviceSerial, 'push', "$dex/classes.dex", $remoteDex)
# 原包 API JAR 仅用于编译；入口测试在 ART 上使用手机真实框架类。
Invoke-TestCommand $adb @('-s', $DeviceSerial, 'shell', '/system/bin/app_process', "-Djava.class.path=${remoteDex}:/data/adb/modules/zygisk_lsposed/framework.dex", '/system/bin', 'com.owl.auxiliary.XposedEntryTest')
$hostApk = (& $adb -s $DeviceSerial shell pm path com.duolingo | Select-Object -First 1) -replace '^package:', ''
if ($LASTEXITCODE -ne 0 -or $hostApk -notmatch '^/data/app/[A-Za-z0-9/~_+=.-]+/base\.apk$') {
    throw '无法确定当前多邻国安装路径'
}
# 直接调用宿主结束判断的纯函数，不创建课程或提交学习进度。
Invoke-TestCommand $adb @('-s', $DeviceSerial, 'shell', '/system/bin/app_process', "-Djava.class.path=${remoteDex}:${hostApk}:/data/adb/modules/zygisk_lsposed/framework.dex", '/system/bin', 'com.owl.auxiliary.SkipFeatureTest', '--host')
Write-Host 'ALL_TESTS_PASS（离线回归和 ART 入口测试，不代表全部业务 Hook 验收）'

#requires -Version 7.0
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Mandatory)][string]$SdkRoot,
    [Parameter(Mandatory)][string]$JdkRoot,
    [Parameter(Mandatory)][string]$Keystore,
    [string]$KeyAlias = 'owl',
    [System.Security.SecureString]$KeyStorePassword
)
$ErrorActionPreference = 'Stop'

function Invoke-Checked {
    param([string]$Program, [string[]]$Arguments, [System.Security.SecureString]$Password)
    $info = [System.Diagnostics.ProcessStartInfo]::new($Program)
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardInput = $true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process = [System.Diagnostics.Process]::Start($info)
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if ($Password) {
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Password)
        try {
            $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
            $process.StandardInput.WriteLine($plain)
            $process.StandardInput.WriteLine($plain)
        } finally {
            $plain = $null
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
        }
    }
    $process.StandardInput.Close()
    if (-not $process.WaitForExit(120000)) {
        $process.Kill($true)
        throw "构建命令超时：$Program"
    }
    Write-Host $stdout.GetAwaiter().GetResult()
    Write-Host $stderr.GetAwaiter().GetResult()
    if ($process.ExitCode -ne 0) { throw "构建命令失败（$($process.ExitCode)）：$Program" }
    $process.Dispose()
}

function Add-ApkEntry {
    param([IO.Compression.ZipArchive]$Archive, [string]$Path, [string]$Name)
    if ($Archive.GetEntry($Name)) { throw "APK 条目重复：$Name" }
    [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($Archive, $Path, $Name) | Out-Null
}

$SdkRoot = (Resolve-Path -LiteralPath $SdkRoot).Path
$JdkRoot = (Resolve-Path -LiteralPath $JdkRoot).Path
$Keystore = (Resolve-Path -LiteralPath $Keystore).Path
$tools = Join-Path $SdkRoot 'build-tools/36.1.0'
$platform = Join-Path $SdkRoot 'platforms/android-35/android.jar'
$java = Join-Path $JdkRoot 'bin/java.exe'
$javac = Join-Path $JdkRoot 'bin/javac.exe'
$xposed = Join-Path $PSScriptRoot 'work/xposed-api.jar'
$modern = Join-Path $PSScriptRoot 'work/libxposed-api-101.0.1.jar'
foreach ($path in @($platform, $java, $javac, $xposed, $modern, "$tools/aapt2.exe", "$tools/lib/d8.jar", "$tools/lib/apksigner.jar")) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "缺少构建工具：$path" }
}
if (-not $KeyStorePassword) { $KeyStorePassword = Read-Host '签名密钥口令' -AsSecureString }
$build = Join-Path $PSScriptRoot ('work/build-' + [guid]::NewGuid().ToString('N'))
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
$out = Join-Path $PSScriptRoot 'out'
New-Item -ItemType Directory -Path $classes, $dex, $out -Force | Out-Null
[xml]$manifest = Get-Content -LiteralPath "$PSScriptRoot/src/AndroidManifest.xml"
$version = $manifest.manifest.GetAttribute('versionName', 'http://schemas.android.com/apk/res/android')
$minSdk = $manifest.manifest.'uses-sdk'.GetAttribute('minSdkVersion', 'http://schemas.android.com/apk/res/android')
$apk = Join-Path $out "owl-reborn-$version-duolingo-6.103.6.apk"
if (Test-Path -LiteralPath $apk) { throw "输出已存在，请先保留或移走：$apk" }

Invoke-Checked "$tools/aapt2.exe" @('compile', '--dir', "$PSScriptRoot/src/res", '--no-crunch', '-o', "$build/resources.zip")
Invoke-Checked "$tools/aapt2.exe" @('link', '--manifest', "$PSScriptRoot/src/AndroidManifest.xml", '-I', $platform, '-o', "$build/base.apk", "$build/resources.zip")
$sources = Get-ChildItem -LiteralPath "$PSScriptRoot/src/src" -Filter '*.java' -Recurse | ForEach-Object FullName
Invoke-Checked $javac (@('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-Xlint:-options', '-classpath', "$platform;$modern;$xposed", '-d', $classes) + $sources)
$classFiles = Get-ChildItem -LiteralPath $classes -Filter '*.class' -Recurse | ForEach-Object FullName
Invoke-Checked $java (@('-cp', "$tools/lib/d8.jar", 'com.android.tools.r8.D8', '--min-api', $minSdk, '--output', $dex, '--lib', $platform, '--classpath', $modern, '--classpath', $xposed) + $classFiles)
$archive = [IO.Compression.ZipFile]::Open("$build/base.apk", [IO.Compression.ZipArchiveMode]::Update)
try {
    Add-ApkEntry $archive "$dex/classes.dex" 'classes.dex'
    foreach ($name in @('java_init.list', 'module.prop', 'scope.list')) {
        Add-ApkEntry $archive "$PSScriptRoot/src/resources/META-INF/xposed/$name" "META-INF/xposed/$name"
    }
} finally { $archive.Dispose() }
Invoke-Checked "$tools/zipalign.exe" @('-p', '4', "$build/base.apk", "$build/aligned.apk")
Invoke-Checked $java @('-jar', "$tools/lib/apksigner.jar", 'sign', '--ks', $Keystore, '--ks-key-alias', $KeyAlias, '--ks-pass', 'stdin', '--key-pass', 'stdin', '--v4-signing-enabled', 'false', '--out', $apk, "$build/aligned.apk") $KeyStorePassword
Invoke-Checked $java @('-jar', "$tools/lib/apksigner.jar", 'verify', '--verbose', '--print-certs', $apk)
Invoke-Checked "$tools/zipalign.exe" @('-c', '4', $apk)
Get-FileHash -LiteralPath $apk -Algorithm SHA256 | Format-List
Write-Host "APK：$apk"

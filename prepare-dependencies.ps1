#requires -Version 7.0
$ErrorActionPreference = 'Stop'
$dependencyDirectory = Join-Path $PSScriptRoot 'work'
New-Item -ItemType Directory -Path $dependencyDirectory -Force | Out-Null

function Save-VerifiedDependency {
    param([string]$Url, [string]$Destination, [string]$Sha256)
    if (Test-Path -LiteralPath $Destination) {
        if ((Get-FileHash -LiteralPath $Destination -Algorithm SHA256).Hash -ne $Sha256) {
            throw "已有依赖与官方校验值不符，请自行保留并移走后重试：$Destination"
        }
        return
    }
    $response = Invoke-WebRequest -Uri $Url
    $bytes = $response.Content
    if ($Url.EndsWith('.aar')) {
        $stream = [IO.MemoryStream]::new($bytes)
        $archive = [IO.Compression.ZipArchive]::new($stream)
        try {
            $entry = $archive.GetEntry('classes.jar')
            if (-not $entry) { throw '官方 AAR 中没有 classes.jar' }
            $input = $entry.Open()
            $output = [IO.MemoryStream]::new()
            try { $input.CopyTo($output); $bytes = $output.ToArray() }
            finally { $input.Dispose(); $output.Dispose() }
        } finally { $archive.Dispose(); $stream.Dispose() }
    }
    $digest = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))
    if ($digest -ne $Sha256) { throw "官方依赖校验失败：$Url" }
    [IO.File]::WriteAllBytes($Destination, $bytes)
}

Save-VerifiedDependency -Url 'https://api.xposed.info/de/robv/android/xposed/api/82/api-82.jar' `
    -Destination (Join-Path $dependencyDirectory 'xposed-api.jar') `
    -Sha256 'F48C635F1C7469FDEC0E00AD2EA0B7A6B2F5B55065784A35B7CA3A84615E8E25'
Save-VerifiedDependency -Url 'https://repo.maven.apache.org/maven2/io/github/libxposed/api/101.0.1/api-101.0.1.aar' `
    -Destination (Join-Path $dependencyDirectory 'libxposed-api-101.0.1.jar') `
    -Sha256 '2421B40AD9A0015731A1399FD547C7132AD73491A6B6864CE9C9F3D1BBF0A120'
Write-Host 'DEPENDENCIES_VERIFIED（只参与编译，不打入 APK）'

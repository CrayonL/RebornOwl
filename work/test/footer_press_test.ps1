#requires -Version 7.0
[CmdletBinding(PositionalBinding = $false)]
param(
    [Parameter(Mandatory)][string]$Normal,
    [Parameter(Mandatory)][string]$ClosePressed,
    [Parameter(Mandatory)][string]$RestartPressed,
    [Parameter(Mandatory)][int[]]$CloseBounds,
    [Parameter(Mandatory)][int[]]$RestartBounds
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

function Get-ChangedPixels {
    param([Drawing.Bitmap]$Before, [Drawing.Bitmap]$After, [Drawing.Rectangle]$Bounds)
    if ($Before.Size -ne $After.Size) { throw '截图尺寸不一致' }
    if ($Bounds.Width -le 0 -or $Bounds.Height -le 0 -or $Bounds.X -lt 0 -or $Bounds.Y -lt 0 -or
        $Bounds.Right -gt $Before.Width -or $Bounds.Bottom -gt $Before.Height) {
        throw '按钮区域超出截图或为空'
    }
    $changed = 0
    for ($y = $Bounds.Top; $y -lt $Bounds.Bottom; $y++) {
        for ($x = $Bounds.Left; $x -lt $Bounds.Right; $x++) {
            if ($Before.GetPixel($x, $y) -ne $After.GetPixel($x, $y)) { $changed++ }
        }
    }
    return $changed
}

if ($CloseBounds.Count -ne 4 -or $RestartBounds.Count -ne 4) {
    throw '区域格式必须为 x,y,width,height，依据正常状态截图定位'
}
$closeRegion = [Drawing.Rectangle]::new($CloseBounds[0], $CloseBounds[1], $CloseBounds[2], $CloseBounds[3])
$restartRegion = [Drawing.Rectangle]::new($RestartBounds[0], $RestartBounds[1], $RestartBounds[2], $RestartBounds[3])
$normalImage = [Drawing.Bitmap]::new((Resolve-Path -LiteralPath $Normal).Path)
$closeImage = [Drawing.Bitmap]::new((Resolve-Path -LiteralPath $ClosePressed).Path)
$restartImage = [Drawing.Bitmap]::new((Resolve-Path -LiteralPath $RestartPressed).Path)
try {
    $cases = @(
        @{ Name = '关闭'; Pressed = $closeImage; Active = $closeRegion; Idle = $restartRegion },
        @{ Name = '重启'; Pressed = $restartImage; Active = $restartRegion; Idle = $closeRegion }
    )
    foreach ($case in $cases) {
        $active = Get-ChangedPixels $normalImage $case.Pressed $case.Active
        $idle = Get-ChangedPixels $normalImage $case.Pressed $case.Idle
        Write-Host "按下$($case.Name)：自身变化 $active 像素，旁边变化 $idle 像素"
        if ($active -eq 0) { throw '截图未捕获按压效果，不能计为通过' }
        if ($idle -ne 0) { throw '未按下的按钮发生变化' }
    }
    Write-Host 'FOOTER_PRESS_PASS：两个按钮分别响应按压，另一按钮保持逐像素不变'
} finally {
    $normalImage.Dispose()
    $closeImage.Dispose()
    $restartImage.Dispose()
}

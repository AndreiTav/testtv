param(
    [string]$Adb = 'adb',
    [string]$Tv = '192.168.68.50:5555',
    [string]$Records = (Join-Path $PSScriptRoot '..\tv-records'),
    [switch]$DisableStockLauncher
)
. (Join-Path $PSScriptRoot 'Common.ps1')
Connect-Tv
if (-not (Test-Path (Join-Path $Records 'original-state.json'))) {
    throw 'Execute Install-Test.ps1 primeiro para salvar a restauracao.'
}
$run = Join-Path $Records ('home-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $run | Out-Null
Invoke-TvAdb -Arguments @('shell', 'cmd', 'package', 'query-activities', '--user', '0',
    '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME') |
    Set-Content -Encoding UTF8 (Join-Path $run 'home-candidates-before.txt')
Set-TvHome 'dev.salattv.launcher/.MainActivity'
Invoke-TvAdb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
Start-Sleep -Seconds 3
Save-TvScreenshot $run '01-home.png'
if ($DisableStockLauncher) {
    # Use only after inspecting the first HOME screenshot on the real TV.
    $oldState = Get-TvPackageState 'com.google.android.apps.tv.launcherx'
    try {
        Set-TvPackageState 'com.google.android.apps.tv.launcherx' 3
        if ((Get-TvHome).Split('/')[0] -ne 'dev.salattv.launcher') {
            throw 'Outra HOME assumiu o controle; pode ser o assistente de configuracao.'
        }
        Invoke-TvAdb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
        Start-Sleep -Seconds 3
        Save-TvScreenshot $run '02-without-stock.png'
    } catch {
        Set-TvPackageState 'com.google.android.apps.tv.launcherx' $oldState
        $baseline = Get-Content -Raw (Join-Path $Records 'original-state.json') | ConvertFrom-Json
        Set-TvHome $baseline.OriginalHome
        Invoke-TvAdb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
        throw
    }
}
Write-Host 'Confira a captura, os quatro apps, o controle e as entradas HDMI.'

param(
    [string]$Adb = 'adb',
    [string]$Tv = '192.168.68.50:5555',
    [string]$Records = (Join-Path $PSScriptRoot '..\tv-records'),
    [switch]$DisableStockLauncher
)
. (Join-Path $PSScriptRoot 'Common.ps1')
Connect-Tv
$baselineFile = Join-Path $Records 'original-state.json'
if (-not (Test-Path $baselineFile)) {
    throw 'Execute Install-Test.ps1 primeiro para salvar a restauracao.'
}
$baseline = Get-Content -Raw $baselineFile | ConvertFrom-Json
if ($baseline.Tv -ne $Tv) { throw 'O backup pertence a outro endereco de TV.' }
$run = Join-Path $Records ('home-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [guid]::NewGuid().ToString('N').Substring(0,6))
New-Item -ItemType Directory -Path $run | Out-Null
$candidates = Invoke-TvAdb -Arguments @('shell', 'cmd', 'package', 'query-activities', '--user', '0',
    '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
$candidates | Set-Content -Encoding UTF8 (Join-Path $run 'home-candidates-before.txt')
if ($candidates -notmatch 'dev\.salattv\.launcher') { throw 'Sala TV nao aparece entre os candidatos HOME. Nao alterei os pacotes.' }
$appState = Get-TvPackageState 'dev.salattv.launcher'
if ($appState -notin @(0,1)) { throw 'Sala TV esta desativado. Nao alterei os pacotes.' }
$previousHome = Get-TvHome
$stock = 'com.google.android.apps.tv.launcherx'
$oldState = Get-TvPackageState $stock
@{ Tv=$Tv; PreviousHome=$previousHome; StockState=$oldState } |
    ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $run 'state-before-attempt.json')
$stockTouched = $false
try {
    if ($DisableStockLauncher) {
        # Explicit opt-in, after the user verified Sala TV on the real device.
        # Some firmwares ignore the new HOME while LauncherX is still enabled.
        $start = Invoke-TvAdb -Arguments @('shell', 'am', 'start', '-W', '-n', 'dev.salattv.launcher/.MainActivity')
        if ($start -match '(?m)^Error:') { throw 'Sala TV nao abriu. Nao desativei o launcher antigo.' }
        $stockTouched = $true
        Set-TvPackageState $stock 3
    }
    Set-TvHome 'dev.salattv.launcher/.MainActivity'
    Invoke-TvAdb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
    Start-Sleep -Seconds 3
    $resolved = Get-TvHome
    $resolved | Set-Content -Encoding UTF8 (Join-Path $run 'home-after.txt')
    if ($resolved.Split('/')[0] -ne 'dev.salattv.launcher') { throw "Outra HOME assumiu o controle: $resolved" }
    $image = if ($DisableStockLauncher) { '02-without-stock.png' } else { '01-home.png' }
    Save-TvScreenshot $run $image
    Write-Host "HOME confirmada: $resolved"
} catch {
    $failure = $_.Exception.Message
    $failure | Set-Content -Encoding UTF8 (Join-Path $run 'failure.txt')
    try {
        Get-TvHome | Set-Content -Encoding UTF8 (Join-Path $run 'home-on-failure.txt')
        Save-TvScreenshot $run 'failure.png'
    } catch { Write-Warning 'Nao foi possivel capturar o diagnostico visual da falha.' }
    try {
        if ($stockTouched) { Set-TvPackageState $stock $oldState }
        Set-TvHome $previousHome
        Invoke-TvAdb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
        Write-Host 'HOME e estado anterior do LauncherX restaurados; os demais pacotes foram preservados.'
    } catch {
        Write-Warning 'Restauracao automatica nao confirmada. Use Restore-TV.ps1 ou os comandos de emergencia do README.'
    }
    throw "$failure Diagnosticos: $run"
}
Write-Host 'Confira a captura, os quatro apps, o controle e as entradas HDMI.'

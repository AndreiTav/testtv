param(
    [string]$Adb = 'adb',
    [string]$Tv = '192.168.68.50:5555',
    [string]$Records = (Join-Path $PSScriptRoot '..\tv-records'),
    [switch]$DisableStockLauncher,
    [switch]$DisableSetupRecovery
)
. (Join-Path $PSScriptRoot 'Common.ps1')
if ($DisableSetupRecovery -and -not $DisableStockLauncher) {
    throw 'A opcao de Recovery exige -DisableStockLauncher e verificacao previa do Sala TV.'
}
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
$recovery = 'com.google.android.tungsten.setupwraith/.RecoveryActivity'
$oldRecoveryState = $null
if ($DisableSetupRecovery) {
    $provisioned = (Invoke-TvAdb -Arguments @('shell', 'settings', 'get', 'global', 'device_provisioned')).Trim()
    $setupComplete = (Invoke-TvAdb -Arguments @('shell', 'settings', '--user', '0', 'get', 'secure', 'user_setup_complete')).Trim()
    if ($provisioned -ne '1' -or $setupComplete -ne '1') {
        throw 'Configuracao inicial incompleta ou estado desconhecido. Nao desativei a RecoveryActivity.'
    }
    if ($candidates -notmatch 'com\.google\.android\.tungsten\.setupwraith') {
        throw 'O assistente nao aparece entre os candidatos HOME. Envie o diagnostico antes de alterar a Activity.'
    }
    $dump = Invoke-TvAdb -Arguments @('shell', 'dumpsys', 'package', 'com.google.android.tungsten.setupwraith')
    $dump | Set-Content -Encoding UTF8 (Join-Path $run 'setupwraith-before.txt')
    $oldRecoveryState = Get-TvComponentState -Component $recovery -Dump $dump
    # Extend the existing baseline additively before changing the component.
    if (-not $baseline.PSObject.Properties['ComponentStates']) {
        $baseline | Add-Member -MemberType NoteProperty -Name 'ComponentStates' -Value ([pscustomobject]@{})
    }
    if (-not $baseline.ComponentStates.PSObject.Properties[$recovery]) {
        $baseline.ComponentStates | Add-Member -MemberType NoteProperty -Name $recovery -Value $oldRecoveryState
        Copy-Item -LiteralPath $baselineFile -Destination (Join-Path $run 'original-state-before-extension.json')
        $tempBaseline = Join-Path $Records ('baseline-' + [guid]::NewGuid().ToString('N') + '.tmp')
        $baseline | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 $tempBaseline
        Move-Item -LiteralPath $tempBaseline -Destination $baselineFile -Force
    }
}
@{ Tv=$Tv; PreviousHome=$previousHome; StockState=$oldState; RecoveryState=$oldRecoveryState } |
    ConvertTo-Json | Set-Content -Encoding UTF8 (Join-Path $run 'state-before-attempt.json')
$stockTouched = $false
$recoveryTouched = $false
try {
    if ($DisableStockLauncher) {
        # Explicit opt-in, after the user verified Sala TV on the real device.
        # Some firmwares ignore the new HOME while LauncherX is still enabled.
        $start = Invoke-TvAdb -Arguments @('shell', 'am', 'start', '-W', '-n', 'dev.salattv.launcher/.MainActivity')
        if ($start -match '(?m)^Error:') { throw 'Sala TV nao abriu. Nao desativei o launcher antigo.' }
        if ($DisableSetupRecovery) {
            $recoveryTouched = $true
            Set-TvComponentState $recovery 2
        }
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
    $rollbackErrors = @()
    if ($stockTouched) {
        try { Set-TvPackageState $stock $oldState } catch { $rollbackErrors += $_.Exception.Message }
    }
    if ($recoveryTouched) {
        try { Set-TvComponentState $recovery $oldRecoveryState } catch { $rollbackErrors += $_.Exception.Message }
    }
    try {
        Set-TvHome $previousHome
        Invoke-TvAdb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
    } catch { $rollbackErrors += $_.Exception.Message }
    if ($rollbackErrors.Count) {
        $rollbackErrors | Set-Content -Encoding UTF8 (Join-Path $run 'rollback-errors.txt')
        Write-Warning 'Restauracao automatica nao confirmada. Use Restore-TV.ps1 ou os comandos de emergencia do README.'
    } else {
        Write-Host 'HOME e estados anteriores restaurados; os demais apps foram preservados.'
    }
    throw "$failure Diagnosticos: $run"
}
Write-Host 'Confira a captura, os quatro apps, o controle e as entradas HDMI.'

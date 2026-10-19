param(
    [string]$Adb = 'adb',
    [string]$Tv = '192.168.68.50:5555',
    [string]$Records = (Join-Path $PSScriptRoot '..\tv-records')
)
. (Join-Path $PSScriptRoot 'Common.ps1')
$file = Join-Path $Records 'original-state.json'
if (-not (Test-Path $file)) { throw 'Backup original-state.json nao encontrado. Nao alterei a TV.' }
$state = Get-Content -Raw $file | ConvertFrom-Json
if ($state.Tv -ne $Tv) { throw 'O backup pertence a outro endereco de TV.' }
Connect-Tv
# Restore the old launcher before switching away from Sala TV.
Set-TvPackageState 'com.google.android.apps.tv.launcherx' ([int]$state.PackageStates.'com.google.android.apps.tv.launcherx')
Set-TvPackageState 'com.google.android.tungsten.setupwraith' ([int]$state.PackageStates.'com.google.android.tungsten.setupwraith')
Set-TvHome $state.OriginalHome
foreach ($property in $state.PackageStates.PSObject.Properties) {
    if ($property.Name -notin @('com.google.android.apps.tv.launcherx', 'com.google.android.tungsten.setupwraith')) {
        Set-TvPackageState $property.Name ([int]$property.Value)
    }
}
foreach ($property in $state.PreCleanupStates.PSObject.Properties) {
    Set-TvPackageState $property.Name ([int]$property.Value)
}
Invoke-TvAdb -Arguments @('shell', 'input', 'keyevent', 'KEYCODE_HOME') | Out-Null
Start-Sleep -Seconds 3
Save-TvScreenshot $Records ('restored-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.png')
Write-Host 'HOME e estados registrados restaurados. O Sala TV continua instalado; nenhum dado foi apagado.'

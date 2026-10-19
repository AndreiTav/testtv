param(
    [string]$Adb = 'adb',
    [string]$Tv = '192.168.68.50:5555',
    [string]$Apk = (Join-Path $PSScriptRoot '..\SalaTV-debug.apk'),
    [string]$Records = (Join-Path $PSScriptRoot '..\tv-records')
)
. (Join-Path $PSScriptRoot 'Common.ps1')
if (-not (Test-Path $Apk)) { throw "APK nao encontrado: $Apk" }
Connect-Tv
Save-TvBaseline
$homeBeforeInstall = Get-TvHome
$run = Join-Path $Records ('test-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $run | Out-Null
$remote = '/data/local/tmp/salatv-' + [guid]::NewGuid().ToString('N') + '.apk'
Invoke-TvAdb -Arguments @('push', $Apk, $remote) | Write-Host
$hash = Invoke-TvAdb -Arguments @('shell', 'sha256sum', $remote)
$expected = (Get-FileHash -Algorithm SHA256 $Apk).Hash
if (($hash -split '\s+')[0] -ine $expected) { throw 'SHA256 do APK transferido nao confere.' }
# One pm command per ADB shell invocation; no uninstall, data clear or verification bypass.
# Preserve the installer's actual stdout/stderr even when pm returns a nonzero exit.
$savedErrorPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = 'Continue'
    $lines = & $Adb -s $Tv shell pm install -r -t $remote 2>&1
    $installExit = $LASTEXITCODE
} finally { $ErrorActionPreference = $savedErrorPreference }
$result = ($lines | ForEach-Object { $_.ToString() }) -join "`n"
$result | Set-Content -Encoding UTF8 (Join-Path $run 'install-result.txt')
Write-Host $result
if ($installExit -ne 0 -or $result -notmatch '(?m)^Success\s*$') {
    throw 'Instalacao recusada. Nao repita com outro metodo; envie install-result.txt.'
}
Invoke-TvAdb -Arguments @('shell', 'rm', $remote) | Out-Null
if ((Get-TvHome) -ne $homeBeforeInstall) { Set-TvHome $homeBeforeInstall }
$start = Invoke-TvAdb -Arguments @('shell', 'am', 'start', '-W', '-n', 'dev.salattv.launcher/.MainActivity')
$start | Set-Content -Encoding UTF8 (Join-Path $run 'start-result.txt')
Write-Host $start
if ($start -match '(?m)^Error:') { throw 'Falha ao abrir o launcher.' }
Start-Sleep -Seconds 3
Save-TvScreenshot $run '01-initial.png'
Invoke-TvAdb -Arguments @('shell', 'logcat', '-d', '-t', '300', '-s', 'AndroidRuntime:E') |
    Set-Content -Encoding UTF8 (Join-Path $run 'runtime-errors.txt')
Write-Host 'Teste instalado e aberto. A HOME anterior e os pacotes existentes foram preservados.'
Write-Host 'Envie 01-initial.png e install-result.txt antes de definir como HOME.'

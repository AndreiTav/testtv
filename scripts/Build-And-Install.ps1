param(
    [string]$Adb = 'adb',
    [string]$Tv = '192.168.68.50:5555',
    [string]$Records = (Join-Path $PSScriptRoot '..\tv-records')
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$project = Split-Path $PSScriptRoot -Parent
Push-Location $project
try {
    if (-not $env:ANDROID_HOME -and -not (Test-Path 'local.properties')) {
        throw 'Configure ANDROID_HOME ou local.properties com o Android SDK 35.'
    }
    $keystore = Join-Path $project '.local\debug.keystore'
    if (-not (Test-Path $keystore)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $keystore) | Out-Null
        & keytool -genkeypair -keystore $keystore -storepass android -keypass android -alias androiddebugkey `
            -dname 'CN=Sala TV Debug,O=Local Development,C=BR' -keyalg RSA -keysize 2048 -validity 10000 -noprompt
        if ($LASTEXITCODE -ne 0) { throw 'Falha ao gerar a chave de desenvolvimento.' }
    }
    & .\gradlew.bat --no-daemon --console=plain testDebugUnitTest lintDebug assembleDebug
    if ($LASTEXITCODE -ne 0) { throw 'Build ou verificacoes falharam. Nao instalei.' }
    & (Join-Path $PSScriptRoot 'Install-Test.ps1') -Adb $Adb -Tv $Tv -Records $Records `
        -Apk (Join-Path $project 'app\build\outputs\apk\debug\app-debug.apk')
} finally { Pop-Location }

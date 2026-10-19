Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Invoke-TvAdb {
    param([string[]]$Arguments)
    $output = & $Adb -s $Tv @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB falhou: $($Arguments -join ' ')" }
    return ($output -join "`n")
}

function Connect-Tv {
    if (-not (Get-Command $Adb -ErrorAction SilentlyContinue)) { throw "ADB nao encontrado: $Adb" }
    Write-Host 'Se aparecer uma solicitacao de depuracao na TV, autorize este computador.'
    & $Adb connect $Tv
    if ($LASTEXITCODE -ne 0) { throw 'Falha ao conectar a TV.' }
    if ((Invoke-TvAdb -Arguments @('get-state')).Trim() -ne 'device') { throw 'TV nao autorizada ou offline.' }
}

function Get-TvPackageState {
    param([string]$Package)
    $dump = Invoke-TvAdb -Arguments @('shell', 'dumpsys', 'package', $Package)
    $match = [regex]::Match($dump, '(?m)^\s*User 0:.*\benabled=(\d+)')
    if (-not $match.Success) { throw "Nao foi possivel ler o estado de $Package" }
    return [int]$match.Groups[1].Value
}

function Set-TvPackageState {
    param([string]$Package, [int]$State)
    $verbs = @('default-state', 'enable', 'disable', 'disable-user', 'disable-until-used')
    if ($State -lt 0 -or $State -gt 4) { throw 'Estado de pacote invalido.' }
    Invoke-TvAdb -Arguments @('shell', 'pm', $verbs[$State], '--user', '0', $Package) | Write-Host
    if ((Get-TvPackageState $Package) -ne $State) { throw "Estado nao confirmado para $Package" }
}

function Get-TvHome {
    $output = Invoke-TvAdb -Arguments @('shell', 'cmd', 'package', 'resolve-activity', '--brief',
        '--user', '0', '-a', 'android.intent.action.MAIN', '-c', 'android.intent.category.HOME')
    $matches = [regex]::Matches($output, '(?m)^\s*([A-Za-z0-9_.]+/[A-Za-z0-9_.$]+)\s*$')
    if ($matches.Count -ne 1) { throw 'Nao foi possivel identificar a HOME atual com seguranca.' }
    return $matches[0].Groups[1].Value
}

function Set-TvHome {
    param([string]$Component)
    Invoke-TvAdb -Arguments @('shell', 'cmd', 'package', 'set-home-activity', '--user', '0', $Component) | Write-Host
    $resolved = Get-TvHome
    # Android can abbreviate the activity component with a leading dot.
    $wanted = $Component.Split('/')
    $actual = $resolved.Split('/')
    $wantedName = if ($wanted[1].StartsWith('.')) { $wanted[0] + $wanted[1] } else { $wanted[1] }
    $actualName = if ($actual[1].StartsWith('.')) { $actual[0] + $actual[1] } else { $actual[1] }
    if ($wanted[0] -ne $actual[0] -or $wantedName -ne $actualName) {
        throw "HOME solicitada nao foi assumida. Resolver atual: $resolved"
    }
}

function Save-TvScreenshot {
    param([string]$Directory, [string]$Name)
    New-Item -ItemType Directory -Force -Path $Directory | Out-Null
    $remote = '/data/local/tmp/salatv-' + [guid]::NewGuid().ToString('N') + '.png'
    Invoke-TvAdb -Arguments @('shell', 'screencap', '-p', $remote) | Out-Null
    $local = Join-Path $Directory $Name
    Invoke-TvAdb -Arguments @('pull', $remote, $local) | Write-Host
    if (-not (Test-Path $local) -or (Get-Item $local).Length -lt 100) { throw 'Captura vazia.' }
    Invoke-TvAdb -Arguments @('shell', 'rm', $remote) | Out-Null
    Write-Host "Captura: $local"
}

function Save-TvBaseline {
    New-Item -ItemType Directory -Force -Path $Records | Out-Null
    $file = Join-Path $Records 'original-state.json'
    if (Test-Path $file) { return }
    $state = [ordered]@{
        Tv = $Tv
        OriginalHome = Get-TvHome
        PackageStates = [ordered]@{
            'com.google.android.apps.tv.launcherx' = Get-TvPackageState 'com.google.android.apps.tv.launcherx'
            'com.google.android.tungsten.setupwraith' = Get-TvPackageState 'com.google.android.tungsten.setupwraith'
            'com.netflix.ninja' = Get-TvPackageState 'com.netflix.ninja'
        }
        # Values from the user's pre-cleanup dumpsys reports, before our two changes.
        PreCleanupStates = [ordered]@{ 'com.tcl.bi' = 0; 'com.tcl.logkit' = 0 }
    }
    $state | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $file
    Write-Host "Estado original salvo: $file"
}

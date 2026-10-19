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

function Get-TvComponentState {
    param([string]$Component, [string]$Dump = '')
    if ($Component -notmatch '^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$') { throw 'Componente invalido.' }
    $parts = $Component.Split('/')
    $class = if ($parts[1].StartsWith('.')) { $parts[0] + $parts[1] } else { $parts[1] }
    $names = @($class, $parts[1], ($parts[0] + '/' + $class), $Component)
    if ([string]::IsNullOrWhiteSpace($Dump)) {
        $Dump = Invoke-TvAdb -Arguments @('shell', 'dumpsys', 'package', $parts[0])
    }
    # Android stores component overrides separately from the application's enabled state.
    # Read only the first, installed User 0 block of the requested package.
    $found = $false
    $userIndent = -1
    $section = 0
    $sectionIndent = -1
    $result = 0
    foreach ($line in ($Dump -split '\r?\n')) {
        if (-not $found) {
            if ($line -match '^(\s*)User 0:.*\binstalled=true\b') {
                $found = $true
                $userIndent = $Matches[1].Length
            }
            continue
        }
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $indent = [regex]::Match($line, '^\s*').Length
        if ($line -match '^\s*User \d+:' -or $indent -le $userIndent) { break }
        if ($line -match '^\s*(enabledComponents|disabledComponents):') {
            if ($line -notmatch '^\s*(enabledComponents|disabledComponents):\s*$') {
                throw 'Formato de componentes inesperado; nao vou alterar a Activity.'
            }
            $section = if ($Matches[1] -eq 'enabledComponents') { 1 } else { 2 }
            $sectionIndent = $indent
            continue
        }
        if ($section -eq 0) { continue }
        if ($indent -le $sectionIndent) { $section = 0; continue }
        $entry = $line.Trim()
        if ($entry -notmatch '^[A-Za-z0-9_.$]+(/[A-Za-z0-9_.$]+)?$') {
            throw 'Lista de componentes inesperada; estado anterior nao confirmado.'
        }
        if ($entry -in $names) {
            if ($result -ne 0 -and $result -ne $section) { throw 'Estado de componente ambiguo.' }
            $result = $section
        }
    }
    if (-not $found) { throw 'Estado instalado do usuario 0 nao encontrado para o componente.' }
    return $result
}

function Set-TvComponentState {
    param([string]$Component, [int]$State)
    if ($Component -notmatch '^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$') { throw 'Componente invalido.' }
    if ($State -notin @(0,1,2)) { throw 'Estado de Activity invalido.' }
    $verbs = @('default-state', 'enable', 'disable')
    Invoke-TvAdb -Arguments @('shell', 'pm', $verbs[$State], '--user', '0', $Component) | Write-Host
    if ((Get-TvComponentState $Component) -ne $State) { throw "Estado da Activity nao confirmado: $Component" }
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

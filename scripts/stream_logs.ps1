# stream_logs.ps1
# Captura logs em tempo real do ColetorBloqueado via ADB Logcat.
# Variaveis de ambiente: DURATION_SECONDS, OUTPUT_FORMAT, CLEAR_BUFFER_FIRST, ADB_DEVICE_SERIAL, FOCUS_AREA

param(
    [string]$DurationSeconds = "0",
    [string]$OutputFormat    = "threadtime",
    [string]$ClearBuffer     = "true",
    [string]$DeviceSerial    = "",
    [string]$FocusArea       = "geral"
)
if ($env:DURATION_SECONDS)   { $DurationSeconds = $env:DURATION_SECONDS }
if ($env:OUTPUT_FORMAT)      { $OutputFormat    = $env:OUTPUT_FORMAT }
if ($env:CLEAR_BUFFER_FIRST) { $ClearBuffer     = $env:CLEAR_BUFFER_FIRST }
if ($env:ADB_DEVICE_SERIAL)  { $DeviceSerial    = $env:ADB_DEVICE_SERIAL }
if ($env:FOCUS_AREA)         { $FocusArea       = $env:FOCUS_AREA }

$ErrorActionPreference = "SilentlyContinue"

function Write-Step([string]$msg) { Write-Host "" ; Write-Host "[$([datetime]::Now.ToString('HH:mm:ss'))] $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "  OK: $msg" -ForegroundColor Green }
function Write-Bad([string]$msg)  { Write-Host "  ERRO: $msg" -ForegroundColor Red }
function Write-Info([string]$msg) { Write-Host "  >> $msg" -ForegroundColor Gray }

function Find-Adb {
    if (Get-Command "adb" -ErrorAction SilentlyContinue) { return "adb" }
    $candidates = @(
        $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT,
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk",
        "C:\Android\sdk"
    )
    foreach ($basePath in $candidates) {
        if (-not [string]::IsNullOrEmpty($basePath) -and (Test-Path "$basePath\platform-tools\adb.exe")) {
            $env:PATH = "$basePath\platform-tools;$env:PATH"
            return "adb"
        }
    }
    Write-Bad "ADB nao encontrado."
    exit 1
}

function Get-BaseArgs {
    if ($DeviceSerial -ne "") { return @("-s", $DeviceSerial) }
    return @()
}

function Get-Filters([string]$focus) {
    switch ($focus) {
        "crash"    { return @("AndroidRuntime:E", "MainActivity:E", "MonitoramentoService:E", "*:S") }
        "bloqueio" { return @("MonitoramentoService:D", "SettingsPasswordActivity:D", "BloqueioActivity:D", "AndroidRuntime:E", "*:S") }
        "setup"    { return @("SetupWizardActivity:D", "MeuAdminReceiver:D", "AndroidRuntime:E", "*:S") }
        "service"  { return @("MonitoramentoService:D", "BootReceiver:D", "AndroidRuntime:E", "*:S") }
        default    { return @(
            "MainActivity:D", "MonitoramentoService:D", "MeuAdminReceiver:D",
            "SetupWizardActivity:D", "SettingsPasswordActivity:D",
            "BloqueioActivity:D", "BootReceiver:D",
            "HomeRestoreReceiver:D", "HomeChangerReceiver:D",
            "AndroidRuntime:E", "*:S"
        )}
    }
}

# --- 1. ADB ---
Write-Step "Verificando ADB..."
$adb = Find-Adb
$base = Get-BaseArgs

# --- 2. Dispositivo ---
Write-Step "Verificando dispositivo..."
$devList = & $adb @base devices 2>&1
$connected = $devList | Where-Object { $_ -match "\bdevice$" }
if (-not $connected) {
    Write-Bad "Nenhum dispositivo Android conectado."
    exit 1
}
Write-Ok "Dispositivo pronto"

# --- 3. Limpar buffer ---
if ($ClearBuffer -eq "true") {
    Write-Step "Limpando buffer de logs antigos..."
    & $adb @base logcat -c 2>&1 | Out-Null
    Write-Ok "Buffer limpo"
}

# --- 4. Iniciar captura ---
$filters = Get-Filters $FocusArea
$durLabel = if ($DurationSeconds -eq "0") { "continua (Ctrl+C para encerrar)" } else { "${DurationSeconds}s" }

Write-Step "Iniciando captura de logs"
Write-Info "Foco    : $FocusArea"
Write-Info "Formato : $OutputFormat"
Write-Info "Duracao : $durLabel"
Write-Info "Filtros : $($filters -join ' ')"
Write-Host ""
Write-Host "--- INICIO DOS LOGS ---" -ForegroundColor DarkCyan
Write-Host ""

# Argumentos do logcat
$logArgs = @()
if ($OutputFormat -eq "threadtime") { $logArgs += @("-v", "threadtime") }
$logArgs += $filters

if ($DurationSeconds -ne "0") {
    $dur = [int]$DurationSeconds
    $job = Start-Job -ScriptBlock {
        param($a, $b, $c)
        & $a @b logcat @c
    } -ArgumentList $adb, $base, $logArgs

    Start-Sleep -Seconds $dur
    Stop-Job $job
    $lines = Receive-Job $job
    Remove-Job $job

    foreach ($line in $lines) {
        if     ($line -match " E ")               { Write-Host $line -ForegroundColor Red }
        elseif ($line -match " W ")               { Write-Host $line -ForegroundColor Yellow }
        elseif ($line -match "Bloqueio ativo")    { Write-Host $line -ForegroundColor Magenta }
        else                                       { Write-Host $line -ForegroundColor Gray }
    }

    Write-Host ""
    Write-Host "--- FIM DOS LOGS ---" -ForegroundColor DarkCyan

    $errors    = ($lines | Where-Object { $_ -match " E " }).Count
    $warnings  = ($lines | Where-Object { $_ -match " W " }).Count
    $bloqueios = ($lines | Where-Object { $_ -match "Bloqueio ativo" }).Count

    Write-Step "Resumo da Sessao"
    Write-Info "Total de linhas     : $($lines.Count)"
    Write-Info "Erros (E)           : $errors"
    Write-Info "Avisos (W)          : $warnings"
    Write-Info "Bloqueios detectados: $bloqueios"
} else {
    & $adb @base logcat @logArgs | ForEach-Object {
        $l = $_
        if     ($l -match " E ")            { Write-Host $l -ForegroundColor Red }
        elseif ($l -match " W ")            { Write-Host $l -ForegroundColor Yellow }
        elseif ($l -match "Bloqueio ativo") { Write-Host $l -ForegroundColor Magenta }
        elseif ($l -match "SUCCESS")        { Write-Host $l -ForegroundColor Green }
        else                                 { Write-Host $l -ForegroundColor Gray }
    }
}

# remove_device_owner.ps1
# Remove Device Owner e Device Admin do ColetorBloqueado via ADB.
# ATENCAO: Funciona completamente apenas em builds DEBUG.
# Variaveis de ambiente: PACKAGE_NAME, CLEAR_APP_DATA, ADB_DEVICE_SERIAL

param(
    [string]$PackageName  = "com.brasil.coletorbloqueado",
    [string]$ClearAppData = "true",
    [string]$DeviceSerial = ""
)
if ($env:PACKAGE_NAME)      { $PackageName  = $env:PACKAGE_NAME }
if ($env:CLEAR_APP_DATA)    { $ClearAppData = $env:CLEAR_APP_DATA }
if ($env:ADB_DEVICE_SERIAL) { $DeviceSerial = $env:ADB_DEVICE_SERIAL }

$ErrorActionPreference = "SilentlyContinue"

function Write-Step([string]$msg) { Write-Host "" ; Write-Host "[$([datetime]::Now.ToString('HH:mm:ss'))] $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "  OK: $msg" -ForegroundColor Green }
function Write-Bad([string]$msg)  { Write-Host "  ERRO: $msg" -ForegroundColor Red }
function Write-Info([string]$msg) { Write-Host "  >> $msg" -ForegroundColor Gray }
function Write-Warn([string]$msg) { Write-Host "  AVISO: $msg" -ForegroundColor Yellow }

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
Write-Ok "Dispositivo conectado"

# --- 3. Aviso de seguranca ---
Write-Step "AVISO DE SEGURANCA"
Write-Warn "Voce vai remover os privilegios de Device Admin/Owner."
Write-Warn "O dispositivo ficara desprotegido apos esta operacao."
Write-Warn "Funciona apenas em builds DEBUG."
Write-Host ""
Write-Host "Confirma a remocao? (S/N): " -ForegroundColor Yellow -NoNewline
$resp = Read-Host
if ($resp -notmatch "^[Ss]$") {
    Write-Info "Operacao cancelada."
    exit 0
}

# --- 4. Limpar dados do app ---
if ($ClearAppData -eq "true") {
    Write-Step "Limpando dados do app ($PackageName)..."
    $clearOut = & $adb @base shell pm clear $PackageName 2>&1
    if ($clearOut -match "Success") {
        Write-Ok "Dados limpos (whitelist, senha e configuracoes resetadas)"
    } else {
        Write-Warn "Nao foi possivel limpar os dados: $clearOut"
        Write-Info "Continuando com a remocao do admin..."
    }
}

# --- 5. Remover Active Admin ---
Write-Step "Removendo Active Admin / Device Owner..."
$adminComponent = "$PackageName/.MeuAdminReceiver"
Write-Info "Componente: $adminComponent"
$output = & $adb @base shell dpm remove-active-admin $adminComponent 2>&1
Write-Host $output

if ($output -match "Success" -or $output -match "removed") {
    Write-Ok "Device Admin removido com sucesso!"
} elseif ($output -match "SecurityException" -or $output -match "prohibited") {
    Write-Bad "BLOQUEADO PELO ANDROID (build de producao detectada)"
    Write-Bad ""
    Write-Warn "Opcoes disponiveis:"
    Write-Warn "  1. Factory Reset: Configuracoes > Redefinir > Dados de fabrica"
    Write-Warn "  2. Compile build Debug e instale via ADB"
    write-Warn "  3. Configuracoes > Seguranca > Administradores (se disponivel)"
    exit 1
} elseif ($output -match "not active admin") {
    Write-Info "O componente nao e um administrador ativo (talvez ja removido)."
    exit 0
} else {
    Write-Warn "Resultado inesperado. Verifique manualmente."
    Write-Info $output
    exit 1
}

# --- 6. Verificar estado final ---
Write-Step "Verificando estado final..."
$ownerCheck = & $adb @base shell dpm list-owners 2>&1
if ($ownerCheck -match $PackageName) {
    Write-Warn "O app ainda aparece como Device Owner. Pode ser necessario Factory Reset."
} else {
    Write-Ok "Confirmado: '$PackageName' nao e mais Device Owner."
}

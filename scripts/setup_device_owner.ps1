# setup_device_owner.ps1
# Define o ColetorBloqueado como Device Owner via ADB.
# Variaveis de ambiente: TARGET_COMPONENT, ADB_DEVICE_SERIAL

param(
    [string]$TargetComponent = "com.brasil.coletorbloqueado/.MeuAdminReceiver",
    [string]$DeviceSerial    = ""
)
if ($env:TARGET_COMPONENT)   { $TargetComponent = $env:TARGET_COMPONENT }
if ($env:ADB_DEVICE_SERIAL)  { $DeviceSerial    = $env:ADB_DEVICE_SERIAL }

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
    Write-Bad "ADB nao encontrado. Configure ANDROID_HOME."
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
Write-Step "Verificando dispositivo conectado..."
$devList = & $adb @base devices 2>&1
$connected = $devList | Where-Object { $_ -match "\bdevice$" }
if (-not $connected) {
    Write-Bad "Nenhum dispositivo Android conectado ou autorizado."
    Write-Info "1. Conecte o cabo USB"
    Write-Info "2. Ative Depuracao USB nas Opcoes do Desenvolvedor"
    Write-Info "3. Autorize a chave RSA na tela do dispositivo"
    exit 1
}
Write-Ok "$($connected.Count) dispositivo(s) encontrado(s)"

# --- 3. App instalado? ---
Write-Step "Verificando se o app esta instalado..."
$pkgCheck = & $adb @base shell pm list packages 2>&1 | Select-String "com.brasil.coletorbloqueado"
if (-not $pkgCheck) {
    Write-Bad "App 'com.brasil.coletorbloqueado' nao encontrado no dispositivo."
    Write-Info "Instale o APK primeiro: .\scripts\build_and_install.ps1"
    exit 1
}
Write-Ok "App encontrado"

# --- 4. Ja e Device Owner? ---
Write-Step "Verificando Device Owner atual..."
$ownerCheck = & $adb @base shell dpm list-owners 2>&1
if ($ownerCheck -match "com.brasil.coletorbloqueado") {
    Write-Ok "ColetorBloqueado ja e Device Owner! Nenhuma acao necessaria."
    exit 0
}

# --- 5. Contas presentes? ---
Write-Step "Verificando contas de usuario..."
$accounts = & $adb @base shell pm list accounts 2>&1
$accountLines = $accounts | Where-Object { $_ -match "Account \{" }
if ($accountLines) {
    Write-Warn "Ha $($accountLines.Count) conta(s) configurada(s)."
    $accountLines | ForEach-Object { Write-Warn "  $_" }
    Write-Warn ""
    Write-Warn "O comando set-device-owner FALHARA com contas presentes."
    Write-Warn "Remova todas em: Configuracoes > Contas > [conta] > Remover conta"
    Write-Host ""
    Write-Host "Deseja tentar mesmo assim? (S/N): " -ForegroundColor Yellow -NoNewline
    $resp = Read-Host
    if ($resp -notmatch "^[Ss]$") {
        Write-Info "Operacao cancelada."
        exit 0
    }
}

# --- 6. Executar set-device-owner ---
Write-Step "Configurando Device Owner..."
Write-Info "Componente: $TargetComponent"
$output = & $adb @base shell dpm set-device-owner $TargetComponent 2>&1
Write-Host $output

if ($output -match "Success") {
    Write-Step "Sucesso!"
    Write-Ok "ColetorBloqueado agora e Device Owner."
    Write-Ok "Reinicie o app no dispositivo para aplicar todas as restricoes."
    exit 0
} elseif ($output -match "accounts on the device") {
    Write-Bad "FALHA: Contas de usuario impedem o setup."
    Write-Bad "Remova TODAS as contas e tente novamente."
    exit 1
} elseif ($output -match "already a device owner") {
    Write-Warn "Ja existe um Device Owner ativo. Use remove_device_owner.ps1 (apenas debug)."
    exit 1
} elseif ($output -match "component must be a registered device admin") {
    Write-Bad "Componente nao registrado como Device Admin. Verifique o AndroidManifest.xml."
    exit 1
} else {
    Write-Bad "Falha desconhecida: $output"
    exit 1
}

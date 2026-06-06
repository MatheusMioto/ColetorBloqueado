# build_and_install.ps1
# Compila e instala o ColetorBloqueado via Gradle + ADB.
# Variaveis de ambiente suportadas:
#   BUILD_VARIANT      assembleDebug | assembleRelease  (default: assembleDebug)
#   ADB_FLAGS          flags do adb install             (default: -r)
#   SKIP_INSTALL       true | false                     (default: false)
#   ADB_DEVICE_SERIAL  serial do dispositivo            (default: vazio = unico conectado)

param(
    [string]$BuildVariant  = "assembleDebug",
    [string]$AdbFlags      = "-r",
    [string]$SkipInstall   = "false",
    [string]$DeviceSerial  = ""
)

# Sobrescreve com variaveis de ambiente se definidas
if ($env:BUILD_VARIANT)      { $BuildVariant = $env:BUILD_VARIANT }
if ($env:ADB_FLAGS)          { $AdbFlags     = $env:ADB_FLAGS }
if ($env:SKIP_INSTALL)       { $SkipInstall  = $env:SKIP_INSTALL }
if ($env:ADB_DEVICE_SERIAL)  { $DeviceSerial = $env:ADB_DEVICE_SERIAL }

$ErrorActionPreference = "Stop"
$StartTime = Get-Date

function Write-Step([string]$msg) { Write-Host "" ; Write-Host "[$([datetime]::Now.ToString('HH:mm:ss'))] $msg" -ForegroundColor Cyan }
function Write-Ok([string]$msg)   { Write-Host "  OK: $msg" -ForegroundColor Green }
function Write-Bad([string]$msg)  { Write-Host "  ERRO: $msg" -ForegroundColor Red }
function Write-Info([string]$msg) { Write-Host "  >> $msg" -ForegroundColor Gray }

function Find-Adb {
    if (Get-Command "adb" -ErrorAction SilentlyContinue) { return "adb" }
    $candidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk",
        "C:\Android\sdk"
    )
    foreach ($basePath in $candidates) {
        if (-not [string]::IsNullOrEmpty($basePath)) {
            $adbExe = "$basePath\platform-tools\adb.exe"
            if (Test-Path $adbExe) {
                $env:PATH = "$basePath\platform-tools;$env:PATH"
                Write-Info "ADB encontrado em: $adbExe"
                return "adb"
            }
        }
    }
    Write-Bad "ADB nao encontrado. Configure ANDROID_HOME ou adicione platform-tools ao PATH."
    exit 1
}

function Get-AdbBaseArgs {
    if ($DeviceSerial -ne "") { return @("-s", $DeviceSerial) }
    return @()
}

function Find-Gradlew {
    foreach ($candidate in @(".\gradlew.bat", "..\gradlew.bat")) {
        if (Test-Path $candidate) { return $candidate }
    }
    Write-Bad "gradlew.bat nao encontrado. Execute a partir da raiz do projeto."
    exit 1
}

function Get-ApkPath([string]$variant) {
    $isRelease = $variant -match "Release"
    $subdir    = if ($isRelease) { "release" } else { "debug" }
    $filename  = if ($isRelease) { "app-release.apk" } else { "app-debug.apk" }
    foreach ($base in @(".", "..")) {
        $p = "$base\app\build\outputs\apk\$subdir\$filename"
        if (Test-Path $p) { return (Resolve-Path $p).Path }
    }
    return $null
}

# --- 1. ADB ---
Write-Step "Verificando ADB..."
$adb = Find-Adb
Write-Ok "ADB disponivel"
$baseArgs = Get-AdbBaseArgs

# --- 2. Compilar ---
Write-Step "Compilando: $BuildVariant"
$gradlew = Find-Gradlew
$buildStart = Get-Date
& $gradlew $BuildVariant
$buildDuration = [int]((Get-Date) - $buildStart).TotalSeconds
if ($LASTEXITCODE -ne 0) {
    Write-Bad "BUILD FALHOU (codigo $LASTEXITCODE)"
    exit $LASTEXITCODE
}
Write-Ok "BUILD SUCCESSFUL em ${buildDuration}s"

# --- 3. Localizar APK ---
Write-Step "Localizando APK gerado..."
$apkPath = Get-ApkPath $BuildVariant
if (-not $apkPath) {
    Write-Bad "APK nao encontrado apos o build."
    exit 1
}
Write-Ok "APK: $apkPath"

# --- 4. Skip install? ---
if ($SkipInstall -eq "true") {
    Write-Info "SKIP_INSTALL=true. Instalacao ignorada."
    Write-Ok "APK disponivel em: $apkPath"
    exit 0
}

# --- 5. Verificar dispositivo ---
Write-Step "Verificando dispositivos ADB..."
$deviceLines = & $adb @baseArgs devices 2>&1
$connected = $deviceLines | Where-Object { $_ -match "\bdevice$" }
if (-not $connected) {
    Write-Bad "Nenhum dispositivo Android conectado ou autorizado."
    Write-Info "1. Conecte o cabo USB"
    Write-Info "2. Ative Depuracao USB nas Opcoes do Desenvolvedor"
    Write-Info "3. Autorize a chave RSA na tela do dispositivo"
    exit 1
}
Write-Ok "$($connected.Count) dispositivo(s) pronto(s)"

# --- 6. Instalar APK ---
Write-Step "Instalando APK..."
Write-Info "Flags: $AdbFlags"
$flagList = $AdbFlags.Split(" ") + @($apkPath)
$installOut = & $adb @baseArgs install @flagList 2>&1
Write-Host $installOut

if ($installOut -match "Success") {
    Write-Ok "Instalacao concluida!"
} elseif ($installOut -match "INSTALL_FAILED_UPDATE_INCOMPATIBLE") {
    Write-Bad "Assinatura incompativel com APK instalado."
    Write-Info "Execute: adb uninstall com.brasil.coletorbloqueado"
    exit 1
} else {
    Write-Bad "Falha na instalacao."
    exit 1
}

# --- Relatorio ---
$total = [int]((Get-Date) - $StartTime).TotalSeconds
Write-Step "Concluido!"
Write-Ok "Variante : $BuildVariant"
Write-Ok "APK      : $apkPath"
Write-Ok "Duracao  : ${total}s"

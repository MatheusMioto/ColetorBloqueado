# build_and_install.ps1
# Script para compilar e instalar o app ColetorBloqueado via PowerShell de forma não-interativa.

$ErrorActionPreference = "Stop"

function Ensure-Adb {
    if (Get-Command "adb" -ErrorAction SilentlyContinue) {
        return
    }
    Write-Host "adb nao encontrado no PATH. Tentando localizar o SDK do Android automaticamente..." -ForegroundColor Yellow
    $candidates = @(
        $env:ANDROID_HOME,
        $env:ANDROID_SDK_ROOT,
        "$env:LOCALAPPDATA\Android\Sdk",
        "$env:USERPROFILE\AppData\Local\Android\Sdk",
        "C:\Android\sdk"
    )
    foreach ($path in $candidates) {
        if (-not [string]::IsNullOrEmpty($path) -and (Test-Path "$path\platform-tools\adb.exe")) {
            $adbDir = "$path\platform-tools"
            Write-Host "Android SDK encontrado em: $path" -ForegroundColor Green
            Write-Host "Adicionando temporariamente ao PATH: $adbDir" -ForegroundColor Green
            $env:PATH = "$adbDir;$env:PATH"
            return
        }
    }
    Write-Error "Nao foi possivel encontrar o 'adb'. Por favor, certifique-se de que o SDK do Android esta instalado e defina a variavel de ambiente ANDROID_HOME ou adicione o diretorio 'platform-tools' ao PATH do sistema."
}

Ensure-Adb

Write-Host "Iniciando compilacao do projeto com Gradle..." -ForegroundColor Cyan
if (Test-Path ".\gradlew.bat") {
    $gradlew = ".\gradlew.bat"
} elseif (Test-Path "..\gradlew.bat") {
    $gradlew = "..\gradlew.bat"
} else {
    Write-Error "Arquivo gradlew.bat nao encontrado. Certifique-se de que esta na raiz do projeto ou no diretorio scripts."
}

# Executa compilacao
& $gradlew assembleDebug

Write-Host "Compilacao concluida com sucesso. Verificando APK..." -ForegroundColor Green
$apkPath = "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) {
    $apkPath = "..\app\build\outputs\apk\debug\app-debug.apk"
    if (-not (Test-Path $apkPath)) {
        Write-Error "APK nao encontrado em $apkPath."
    }
}

Write-Host "Verificando dispositivos conectados via ADB..." -ForegroundColor Cyan
$devices = adb devices | Select-String -Pattern "\bdevice\b"
if ($devices.Count -eq 0) {
    Write-Error "Nenhum dispositivo Android conectado via ADB."
}

Write-Host "Instalando APK no dispositivo..." -ForegroundColor Cyan
adb install -r -t $apkPath

Write-Host "Instalacao concluida com sucesso!" -ForegroundColor Green

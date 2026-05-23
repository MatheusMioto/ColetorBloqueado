# setup_device_owner.ps1
# Script para definir o app como Device Owner via ADB de forma automatizada e nao-interativa.

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

Write-Host "Verificando dispositivos conectados via ADB..." -ForegroundColor Cyan
$devices = adb devices | Select-String -Pattern "\bdevice\b"
if ($devices.Count -eq 0) {
    Write-Error "Nenhum dispositivo Android conectado via ADB."
}

Write-Host "Provisionando app como Device Owner no dispositivo..." -ForegroundColor Cyan
$output = adb shell dpm set-device-owner com.brasil.coletorbloqueado/.MeuAdminReceiver 2>&1

if ($output -match "Success") {
    Write-Host "Sucesso: ColetorBloqueado agora e Device Owner do dispositivo!" -ForegroundColor Green
} else {
    Write-Warning "Falha ao definir Device Owner. Saida do ADB:"
    Write-Host $output -ForegroundColor Red
    Write-Warning "Nota: Certifique-se de que nao ha contas de usuario (Google, WhatsApp, etc.) configuradas no dispositivo antes de rodar o comando."
}

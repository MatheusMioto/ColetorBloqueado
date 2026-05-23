# remove_device_owner.ps1
# Script para tentar remover o status de Device Owner em ambiente de testes/emuladores de forma automatizada e nao-interativa.

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

Write-Host "Limpando os dados do aplicativo..." -ForegroundColor Cyan
adb shell pm clear com.brasil.coletorbloqueado

Write-Host "Tentando remover o Active Admin..." -ForegroundColor Cyan
$output = adb shell dpm remove-active-admin com.brasil.coletorbloqueado/.MeuAdminReceiver 2>&1

if ($output -match "Success" -or $output -match "removed") {
    Write-Host "Sucesso: Admin removido do dispositivo." -ForegroundColor Green
} else {
    Write-Warning "Nao foi possivel remover o Active Admin diretamente via ADB. Saida do ADB:"
    Write-Host $output -ForegroundColor Yellow
    Write-Warning "IMPORTANTE: Para remover um 'Device Owner' persistente em producao, o Android exige o reset de fabrica (Factory Reset) do dispositivo ou chamada programatica a dpm.clearDeviceOwnerApp() em uma build de debug."
}

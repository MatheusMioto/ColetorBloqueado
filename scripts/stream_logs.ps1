# stream_logs.ps1
# Script PowerShell para monitorar logs em tempo real do app ColetorBloqueado de forma nao-interativa ou continua.

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

Write-Host "Iniciando monitoramento de logs (Pressione Ctrl+C para encerrar)..." -ForegroundColor Green
Write-Host "Filtros aplicados: MainActivity, MonitoramentoService, MeuAdminReceiver, AndroidRuntime" -ForegroundColor DarkGray

# Executa logcat com filtros especificos e suprime logs de outras tags
adb logcat MainActivity:D MonitoramentoService:D MeuAdminReceiver:D AndroidRuntime:E *:S

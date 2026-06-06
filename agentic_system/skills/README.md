# Guia Detalhado de Execução das Skills

Este diretório contém os schemas em formato **JSON Schema** (compatíveis com OpenAI Function Calling) para as ferramentas executadas pelos agentes. Cada skill encapsula um ou mais comandos ADB ou scripts PowerShell.

---

## Visão Geral das Skills

| # | Skill | Versão | Executor | Quem Usa |
|---|---|---|---|---|
| 1 | [`build_install_skill`](#1-build_install_skill) | 2.0 | PowerShell + Gradle | Build Agent |
| 2 | [`setup_device_owner_skill`](#2-setup_device_owner_skill) | 2.0 | ADB | Policy Agent |
| 3 | [`remove_device_owner_skill`](#3-remove_device_owner_skill) | 2.0 | ADB | Policy Agent |
| 4 | [`stream_logs_skill`](#4-stream_logs_skill) | 2.0 | ADB Logcat | Monitoring Agent |
| 5 | [`modify_whitelist_skill`](#5-modify_whitelist_skill) | 2.0 | ADB + PowerShell | Policy Agent |
| 6 | [`check_device_status_skill`](#6-check_device_status_skill) | 1.0 | ADB | Todos os Agentes |
| 7 | [`diagnose_setup_skill`](#7-diagnose_setup_skill) | 1.0 | ADB | Policy Agent, Coordinator |

---

## 1. `build_install_skill`

*   **Schema:** [`build_install_skill.json`](build_install_skill.json)
*   **Descrição:** Compila o APK via Gradle e instala no dispositivo via ADB.

**Funcionamento Técnico:**

1. Invoca o Gradle Wrapper com a variante escolhida:
   ```powershell
   .\gradlew.bat assembleDebug   # ou assembleRelease
   ```
2. Aguarda a criação do APK em `app\build\outputs\apk\<variant>\`.
3. Verifica dispositivos via `adb devices`.
4. Instala com:
   ```bash
   adb install -r app\build\outputs\apk\debug\app-debug.apk
   ```

**Sucesso esperado:** Saída contendo `"BUILD SUCCESSFUL"` e `"Success"` no install.

**Retorno estruturado:**
```json
{ "status": "SUCCESS", "apk_path": "...", "build_duration_seconds": 28 }
```

---

## 2. `setup_device_owner_skill`

*   **Schema:** [`setup_device_owner_skill.json`](setup_device_owner_skill.json)
*   **Descrição:** Configura o ColetorBloqueado como Device Owner via ADB.

**⚠️ Pré-requisito crítico:** Nenhuma conta de usuário pode estar no dispositivo.

**Comando ADB subjacente:**
```bash
adb shell dpm set-device-owner com.brasil.coletorbloqueado/.MeuAdminReceiver
```

**Sucesso esperado:**
```
Success: com.brasil.coletorbloqueado set as active admin and device owner
```

**Falha mais comum:**
```
Not allowed to set the device owner because there are already some accounts on the device
```
→ Remova TODAS as contas em Configurações > Contas.

---

## 3. `remove_device_owner_skill`

*   **Schema:** [`remove_device_owner_skill.json`](remove_device_owner_skill.json)
*   **Descrição:** Remove privilégios de Device Admin/Owner. **Apenas em builds Debug ou com Factory Reset.**

**Comandos ADB:**
```bash
adb shell pm clear com.brasil.coletorbloqueado
adb shell dpm remove-active-admin com.brasil.coletorbloqueado/.MeuAdminReceiver
```

**Nota de Produção:** Builds Release assinadas bloqueiam a remoção via ADB por segurança. Use Factory Reset nesses casos.

---

## 4. `stream_logs_skill`

*   **Schema:** [`stream_logs_skill.json`](stream_logs_skill.json)
*   **Descrição:** Captura logs em tempo real via ADB Logcat, focando nas tags do projeto.

**Comando ADB:**
```bash
adb logcat -c && adb logcat \
  MainActivity:D \
  MonitoramentoService:D \
  MeuAdminReceiver:D \
  SetupWizardActivity:D \
  SettingsPasswordActivity:D \
  BootReceiver:D \
  HomeRestoreReceiver:D \
  AndroidRuntime:E \
  *:S
```

**Padrões de log importantes:**

| Padrão | Tag | Significado |
|---|---|---|
| `"Bloqueio ativo: .* em foreground"` | MonitoramentoService | Interceptação funcionando ✅ |
| `"Temporizador de manutencao esgotado"` | MonitoramentoService | Timer expirou ✅ |
| `"Erro ao aplicar travas do sistema"` | MainActivity | Device Owner ausente ⚠️ |
| `"FATAL EXCEPTION"` | AndroidRuntime | Crash — capturar stack trace 🔴 |

---

## 5. `modify_whitelist_skill`

*   **Schema:** [`modify_whitelist_skill.json`](modify_whitelist_skill.json)
*   **Descrição:** Adiciona, remove ou lista apps na whitelist do ColetorBloqueado.

**Verificar whitelist atual via ADB:**
```bash
adb shell run-as com.brasil.coletorbloqueado \
  cat /data/data/com.brasil.coletorbloqueado/shared_prefs/Configuracoes.xml
```

**Forçar recarga do serviço após mudança:**
```bash
adb shell am force-stop com.brasil.coletorbloqueado
adb shell am startservice com.brasil.coletorbloqueado/.MonitoramentoService
```

**Exemplos de pacotes comuns:**
- `com.android.settings` — Configurações do sistema
- `com.android.camera` — Câmera
- `br.com.totvs.mobilebutler` — Mobile Butler (Totvs)

---

## 6. `check_device_status_skill` ✨ NOVA

*   **Schema:** [`check_device_status_skill.json`](check_device_status_skill.json)
*   **Descrição:** Verifica o estado completo do dispositivo e retorna um snapshot estruturado.

**Verificações executadas:**

| Verificação | Comando ADB |
|---|---|
| Device Owner | `adb shell dpm list-owners` |
| Contas no dispositivo | `adb shell pm list accounts` |
| App instalado | `adb shell pm list packages \| findstr coletorbloqueado` |
| Serviço rodando | `adb shell dumpsys activity services com.brasil.coletorbloqueado` |
| SharedPreferences | `adb shell run-as com.brasil.coletorbloqueado cat ...xml` |

**Retorno:**
```json
{
  "overall_health": "HEALTHY | NEEDS_SETUP | DEGRADED | CRITICAL",
  "is_device_owner": true,
  "has_google_accounts": false,
  "setup_complete": true,
  "service_running": true,
  "whitelist_count": 3,
  "recommendations": []
}
```

**Quando usar:** ANTES de qualquer operação de setup, deploy ou modificação de políticas.

---

## 7. `diagnose_setup_skill` ✨ NOVA

*   **Schema:** [`diagnose_setup_skill.json`](diagnose_setup_skill.json)
*   **Descrição:** Executa uma checklist de 4 passos (mesma sequência do SetupWizardActivity) e retorna um relatório de prontidão.

**Passos verificados:**

| Passo | O que verifica |
|---|---|
| 1 | Contas Google removidas |
| 2 | Device Admin ativo |
| 3 | PACKAGE_USAGE_STATS concedida |
| 4 | Device Owner configurado |

**Retorno:**
```json
{
  "ready_for_device_owner": false,
  "setup_progress_percent": 50,
  "blocking_issues": ["Conta Google detectada: user@gmail.com"],
  "checklist": [
    { "step_number": 1, "status": "FAIL", "action_required": "Remova a conta..." },
    { "step_number": 2, "status": "PASS" },
    { "step_number": 3, "status": "PASS" },
    { "step_number": 4, "status": "FAIL", "action_required": "Execute o comando ADB..." }
  ]
}
```

**Quando usar:** SEMPRE antes de `setup_device_owner_skill`. Evita falhas previsíveis.

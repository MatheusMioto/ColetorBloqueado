# Guia Detalhado de Execução das Skills

Este diretório contém os schemas em formato JSON Schema (compatíveis com OpenAI Function Calling) para as ferramentas que os agentes usam. Abaixo está a descrição detalhada do funcionamento interno e comandos acionados por cada Skill.

---

## 1. Skill: `build_install_skill`
*   **Arquivo de Schema:** [`build_install_skill.json`](build_install_skill.json)
*   **Funcionamento Técnico:** 
    1. Muda para o diretório raiz do projeto.
    2. Invoca o Gradle Wrapper (`.\gradlew.bat assembleDebug`) para compilar a build do aplicativo.
    3. Aguarda a criação do arquivo APK em `app\build\outputs\apk\debug\app-debug.apk`.
    4. Verifica se existe algum dispositivo ativo listado por `adb devices`.
    5. Realiza a instalação usando:
       ```bash
       adb install -r -t app\build\outputs\apk\debug\app-debug.apk
       ```
*   **Sucesso Esperado:** Saída final contendo `"Success"`.

---

## 2. Skill: `setup_device_owner_skill`
*   **Arquivo de Schema:** [`setup_device_owner_skill.json`](setup_device_owner_skill.json)
*   **Funcionamento Técnico:**
    1. Executa o script de provisionamento.
    2. Roda o comando do gerenciador de políticas de dispositivo do Android (DPM):
       ```bash
       adb shell dpm set-device-owner com.brasil.coletorbloqueado/.MeuAdminReceiver
       ```
*   **Condição Prévia Crítica:** O dispositivo Android **não pode** possuir nenhuma conta de usuário configurada (como contas Google, WhatsApp, Microsoft, etc.) nas configurações do sistema. Caso contrário, o comando falhará com erro de segurança.
*   **Sucesso Esperado:** Mensagem `"Success: com.brasil.coletorbloqueado set as active admin and device owner"`.

---

## 3. Skill: `remove_device_owner_skill`
*   **Arquivo de Schema:** [`remove_device_owner_skill.json`](remove_device_owner_skill.json)
*   **Funcionamento Técnico:**
    1. Limpa os dados do aplicativo para invalidar preferências salvas:
       ```bash
       adb shell pm clear com.brasil.coletorbloqueado
       ```
    2. Executa a remoção do receptor administrativo do Device Policy Manager:
       ```bash
       adb shell dpm remove-active-admin com.brasil.coletorbloqueado/.MeuAdminReceiver
       ```
*   **Nota de Produção:** Em sistemas Android modernos, a remoção direta do Device Owner via ADB é restrita para builds de produção (`release`), sendo permitida apenas em builds de `debug` ou através de Factory Reset do dispositivo.

---

## 4. Skill: `stream_logs_skill`
*   **Arquivo de Schema:** [`stream_logs_skill.json`](stream_logs_skill.json)
*   **Funcionamento Técnico:**
    1. Abre o console do `logcat` com buffers específicos para capturar o fluxo de depuração do dispositivo em tempo real:
       ```bash
       adb logcat MainActivity:D MonitoramentoService:D MeuAdminReceiver:D AndroidRuntime:E *:S
       ```
    2. Silencia todas as outras tags do sistema (`*:S`) para que o agente possa focar exclusivamente em crashs (`AndroidRuntime`) e no ciclo de vida de segurança do app.

---

## 5. Skill: `modify_whitelist_skill`
*   **Arquivo de Schema:** [`modify_whitelist_skill.json`](modify_whitelist_skill.json)
*   **Funcionamento Técnico:**
    *   **Método Programático (Via Developer Agent):** Modifica os registros em código na classe `MainActivity.java` na lista de pacotes bloqueados por padrão.
    *   **Método de Teste Rápido (Via ADB):** Modifica o arquivo XML de preferências no aparelho:
        1. Copia o arquivo de preferências do aparelho:
           ```bash
           adb shell run-as com.brasil.coletorbloqueado cat /data/data/com.brasil.coletorbloqueado/shared_prefs/Configuracoes.xml
           ```
        2. Atualiza o arquivo adicionando/removendo a tag `<string>package_name</string>` no set de whitelist.
        3. Força a atualização reiniciando o serviço:
           ```bash
           adb shell am force-stop com.brasil.coletorbloqueado
           adb shell am startservice com.brasil.coletorbloqueado/.MonitoramentoService
           ```

# ColetorBloqueado 🔒

**ColetorBloqueado** é uma solução Android corporativa (Kiosk Mode / MDM) projetada para transformar coletores de dados e dispositivos móveis corporativos em ambientes restritos e seguros.

O aplicativo utiliza as APIs de **Device Owner** (`DevicePolicyManager`) do Android para impedir o uso não autorizado do dispositivo, bloqueando o acesso a configurações do sistema, instalações de apps terceiros e navegadores não permitidos, liberando estritamente os aplicativos autorizados (Whitelist).

---

## 🎯 Objetivo do Projeto

- **Modo Kiosk / Bloqueio Total:** Impedir a navegação livre no Android e garantir que os operadores acessem apenas as ferramentas de trabalho especificadas.
- **Gerenciamento de Whitelist:** Permitir que o administrador escolha dinamicamente quais aplicativos instalados podem ser executados no dispositivo.
- **Monitoramento em Tempo Real:** Execução de um serviço em segundo plano (`MonitoramentoService`) que intercepta tentativas de abertura de aplicativos não autorizados e exibe a tela de bloqueio instantaneamente.
- **Modo Manutenção com Timer:** Acesso administrativo protegido por senha mestre configurável, com tempo de expiração temporizado para liberar o dispositivo temporariamente para manutenção técnica sem comprometer a segurança contínua.
- **Integração com Launchers:** Suporte para definição dinâmica de Home padrão e integração com launchers personalizados corporativos (ex: Mobile Butler).

---

## 🏗️ Estrutura do Projeto

```text
ColetorBloqueado/
├── app/
│   ├── build.gradle.kts                 # Configurações de compilação e dependências do aplicativo
│   └── src/main/
│       ├── AndroidManifest.xml          # Permissões, declarações de componentes e receivers
│       └── java/com/brasil/coletorbloqueado/
│           ├── MainActivity.java        # Painel de controle administrativo, gerenciamento da Whitelist e Timer
│           ├── MonitoramentoService.java# Serviço Foreground responsável por monitorar o app ativo no sistema
│           ├── SetupWizardActivity.java # Assistente de configuração inicial na primeira execução do dispositivo
│           ├── BloqueioActivity.java    # Tela transparente/overlay exibida ao tentar abrir app não autorizado
│           ├── SettingsPasswordActivity.java # Interceptador de tela de configurações com validação de senha
│           ├── MeuAdminReceiver.java    # DeviceAdminReceiver integrado ao DevicePolicyManager do Android
│           ├── BootReceiver.java        # Receiver para inicialização automática no boot do sistema (BOOT_COMPLETED)
│           ├── HomeChangerReceiver.java # Receiver para troca da Home launcher por solicitações externas autorizadas
│           └── HomeRestoreReceiver.java # Receiver para alteração/restauração da Home padrão dinamicamente
├── scripts/
│   ├── build_and_install.ps1            # Script PowerShell para compilar e instalar o APK no dispositivo via ADB
│   ├── setup_device_owner.ps1          # Script PowerShell para ativar o app como Device Owner via ADB
│   ├── remove_device_owner.ps1         # Script PowerShell para remover privilégios de Device Owner (modo Debug)
│   └── stream_logs.ps1                 # Script PowerShell para monitoramento e filtragem de logs via Logcat
├── build.gradle.kts                     # Gradle build script raiz
├── settings.gradle.kts                    # Configurações de projetos Gradle
└── coletorbloqueado.jks                 # Chave de assinatura para compilação Release
```

---

## 🚀 Como Utilizar

### 📋 Pré-requisitos

1. **Dispositivo Android:** Android 8.0 (API Level 26) ou superior.
2. **Ambiente de Desenvolvimento:**
   - JDK 11 ou 17 instalado e configurado nas variáveis de ambiente.
   - Android SDK Platform-Tools (`adb`) acessível pelo terminal.
   - PowerShell (para execução dos scripts automatizados em ambiente Windows).

---

### 🔨 Passo a Passo de Instalação

#### 1. Ativar Depuração USB no Dispositivo
No dispositivo Android:
- Vá em **Configurações > Sobre o Telefone**.
- Toque em **Número da Versão** (Build Number) 7 vezes até ativar as **Opções do Desenvolvedor**.
- Em **Opções do Desenvolvedor**, ative a **Depuração USB**.
- Conecte o dispositivo via cabo USB ao computador e autorize a chave RSA quando solicitado.

#### 2. Remover Contas de Usuário do Dispositivo (Obrigatório antes do Device Owner)
O Android impede a atribuição de um **Device Owner** se o dispositivo possuir contas de usuário cadastradas (Google, e-mail, etc.).
- Vá em **Configurações > Contas** e remova todas as contas antes de prosseguir.

#### 3. Compilar e Instalar o Aplicativo
Execute o script PowerShell fornecido na pasta do projeto:

```powershell
.\scripts\build_and_install.ps1
```

*Nota: Opcionalmente, pode-se especificar a variante de build:*
```powershell
.\scripts\build_and_install.ps1 -BuildVariant "assembleRelease"
```

#### 4. Promover o App a Device Owner
Com o dispositivo conectado e sem contas de usuário cadastradas, execute:

```powershell
.\scripts\setup_device_owner.ps1
```

Se o script indicar `Success`, o aplicativo foi promovido com sucesso a Administrador do Dispositivo com privilégios de **Device Owner**.

---

### ⚙️ Configuração Inicial e Operação

1. **Primeiro Acesso (Setup Wizard):**
   - Ao abrir o aplicativo pela primeira vez, a `SetupWizardActivity` solicitará a definição da **Senha Mestre Administrativa**.
   - Defina a senha de segurança corporativa.

2. **Gerenciamento de Aplicativos (Whitelist):**
   - Acesse o aplicativo e informe a **Senha Mestre** para entrar no **Modo Manutenção**.
   - Clique em **Gerenciar Whitelist** para marcar quais aplicativos instalados estarão disponíveis para o operador.
   - Salve as alterações.

3. **Configuração de Timer de Manutenção:**
   - No painel administrativo, defina o tempo em minutos para o dispositivo permanecer liberado em **Modo Manutenção**.
   - Após o término do timer, o bloqueio do sistema será reativado automaticamente.

4. **Operação Normal:**
   - Quando fora do Modo Manutenção, o aplicativo roda o `MonitoramentoService` em segundo plano.
   - Se o operador tentar abrir qualquer aplicativo fora da Whitelist ou acessar as Configurações do dispositivo, a `BloqueioActivity` interromperá a ação.

---

### 🛠️ Utilities e Debugging

O projeto conta com scripts utilitários na pasta `scripts/`:

- **Monitorar Logs em Tempo Real:**
  ```powershell
  .\scripts\stream_logs.ps1 -FocusArea "bloqueio"
  ```
  *(Opções de FocusArea: `geral`, `bloqueio`, `service`, `setup`, `crash`)*

- **Remover Privilégios de Device Owner (Apenas para Testes/Debug):**
  ```powershell
  .\scripts\remove_device_owner.ps1
  ```
  *Atenção: A remoção só é permitida pelo Android em builds Debug. Em builds Release de produção, a remoção exige um Factory Reset do dispositivo.*

---

## 📄 Licença e Uso Interno

Este software foi desenvolvido para uso em coletores de dados corporativos. Alterações arquiteturais ou de lógica de bloqueio devem ser alinhadas respeitando os padrões de segurança e políticas internas da organização.

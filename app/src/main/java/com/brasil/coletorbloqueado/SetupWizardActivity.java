package com.brasil.coletorbloqueado;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.AppOpsManager;
import android.app.admin.DevicePolicyManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Assistente de configuração inicial do ColetorBloqueado (4 passos).
 *
 * <p>Esta Activity é exibida automaticamente na primeira execução do app (quando a flag
 * {@code setup_completo} nas SharedPreferences {@code ColetorBloqueadoPrefs} é {@code false}).
 * Guia o administrador por cada pré-requisito necessário para o funcionamento completo do
 * sistema de bloqueio.</p>
 *
 * <h2>Os 4 passos do setup</h2>
 * <ol>
 *   <li><b>Remover conta Google:</b> Obrigatório para que o DPM permita a definição de
 *       Device Owner. O Android bloqueia {@code dpm set-device-owner} quando há contas
 *       configuradas no dispositivo. O botão abre Configurações &gt; Contas com uma cascata
 *       de 4 Intents de fallback para garantir compatibilidade com diferentes ROMs.</li>
 *   <li><b>Ativar Device Admin:</b> Necessidade básica para qualquer operação de política.
 *       Abre a tela nativa do Android para o usuário confirmar.</li>
 *   <li><b>Permissão de Uso de Apps (PACKAGE_USAGE_STATS):</b> Necessária para o
 *       {@link MonitoramentoService} detectar qual app está em foreground via
 *       {@link android.app.usage.UsageStatsManager}. Abre {@code ACTION_USAGE_ACCESS_SETTINGS}.</li>
 *   <li><b>Device Owner:</b> O privilégio mais alto. Não pode ser concedido via UI — exige
 *       comando ADB executado uma única vez no PC. Esta Activity exibe e permite copiar
 *       o comando exato. O status é verificado em {@link #onResume} a cada retorno.</li>
 * </ol>
 *
 * <h2>Comportamento da UI</h2>
 * <ul>
 *   <li>Cada passo tem um ícone circular (verde = concluído, vermelho = pendente).</li>
 *   <li>O botão "Finalizar" só aparece quando os passos 1, 2 e 3 estão concluídos.
 *       O Device Owner (Passo 4) não bloqueia o setup pois pode ser feito depois.</li>
 *   <li>O status de cada passo é reavaliado em cada {@link #onResume}, garantindo
 *       que a UI reflita imediatamente as ações feitas em outras telas.</li>
 * </ul>
 *
 * <h2>Fluxo de conclusão</h2>
 * <p>Ao tocar em "Finalizar", a flag {@code setup_completo} é salva, o
 * {@link MonitoramentoService} é iniciado e o usuário é redirecionado para {@link MainActivity}.
 * Esta Activity não é mais exibida nas próximas inicializações.</p>
 *
 * @see MeuAdminReceiver
 * @see MonitoramentoService
 * @see MainActivity
 */
public class SetupWizardActivity extends AppCompatActivity {

    private static final String TAG = "SetupWizard";
    private static final String PREFS_NAME = "ColetorBloqueadoPrefs";
    private static final String KEY_SETUP_COMPLETO = "setup_completo";

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    // Passo 1 - Conta Google
    private TextView iconPasso1, statusPasso1, tvAvisoContaGoogle;
    private Button btnPasso1;

    // Passo 2 - Device Admin
    private TextView iconPasso2, statusPasso2;
    private Button btnPasso2;

    // Passo 3 - Usage Stats
    private TextView iconPasso3, statusPasso3;
    private Button btnPasso3;

    // Passo 4 - Device Owner
    private TextView iconPasso4, statusPasso4;
    private LinearLayout layoutAdbInstrucao;
    private Button btnCopiarAdb;

    // Botao final
    private Button btnFinalizar;
    private TextView tvAvisoFaltando;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup_wizard);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MeuAdminReceiver.class);

        inicializarViews();
        atualizarStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        atualizarStatus();
    }

    private void inicializarViews() {
        // Passo 1 - Conta Google
        iconPasso1 = findViewById(R.id.iconPasso1);
        statusPasso1 = findViewById(R.id.statusPasso1);
        tvAvisoContaGoogle = findViewById(R.id.tvAvisoContaGoogle);
        btnPasso1 = findViewById(R.id.btnPasso1);
        btnPasso1.setOnClickListener(v -> abrirGerenciadorContas());

        // Passo 2 - Device Admin
        iconPasso2 = findViewById(R.id.iconPasso2);
        statusPasso2 = findViewById(R.id.statusPasso2);
        btnPasso2 = findViewById(R.id.btnPasso2);
        btnPasso2.setOnClickListener(v -> ativarDeviceAdmin());

        // Passo 3 - Usage Stats
        iconPasso3 = findViewById(R.id.iconPasso3);
        statusPasso3 = findViewById(R.id.statusPasso3);
        btnPasso3 = findViewById(R.id.btnPasso3);
        btnPasso3.setOnClickListener(v -> abrirUsageStats());

        // Passo 4 - Device Owner
        iconPasso4 = findViewById(R.id.iconPasso4);
        statusPasso4 = findViewById(R.id.statusPasso4);
        layoutAdbInstrucao = findViewById(R.id.layoutAdbInstrucao);
        btnCopiarAdb = findViewById(R.id.btnCopiarAdb);
        btnCopiarAdb.setOnClickListener(v -> copiarComandoAdb());

        // Botao finalizar
        btnFinalizar = findViewById(R.id.btnFinalizar);
        btnFinalizar.setOnClickListener(v -> concluirSetup());

        tvAvisoFaltando = findViewById(R.id.tvAvisoFaltando);
    }

    /**
     * Avalia o estado atual de cada pré-requisito e atualiza a UI de todos os passos.
     *
     * <p>Chamado em {@link #onCreate} e em cada {@link #onResume} para garantir que os
     * ícones e status estejam sempre atualizados quando o usuário retorna de outras telas
     * (ex: após ativar o Device Admin ou remover a conta Google).</p>
     *
     * <p>A lógica de visibilidade do botão Finalizar é calculada aqui:
     * {@code podeFinalizar = semContaGoogle && adminOk && usageOk}. O Device Owner (Passo 4)
     * é mostrado, mas não bloqueia o setup, pois pode ser feito após o Finalizar.</p>
     */
    private void atualizarStatus() {
        boolean semContaGoogle = !temContaGoogle();
        boolean adminOk = isDeviceAdmin();
        boolean usageOk = isUsageStatsPermitido();
        boolean ownerOk = isDeviceOwner();

        // Passo 1 - Conta Google
        if (semContaGoogle) {
            marcarPassoConcluido(iconPasso1, statusPasso1, btnPasso1, "Nenhuma conta Google encontrada");
            tvAvisoContaGoogle.setVisibility(android.view.View.GONE);
        } else {
            marcarPassoPendente(iconPasso1, statusPasso1, btnPasso1,
                    "Conta Google detectada — remova antes de continuar", "Abrir Contas e Remover");
            tvAvisoContaGoogle.setVisibility(android.view.View.VISIBLE);
        }

        // Passo 2 - Device Admin
        if (adminOk) {
            marcarPassoConcluido(iconPasso2, statusPasso2, btnPasso2, "Ativado com sucesso");
        } else {
            marcarPassoPendente(iconPasso2, statusPasso2, btnPasso2,
                    "Toque para ativar o Administrador de Dispositivo", "Ativar Administrador");
        }

        // Passo 3 - Usage Stats
        if (usageOk) {
            marcarPassoConcluido(iconPasso3, statusPasso3, btnPasso3, "Permissao concedida");
        } else {
            marcarPassoPendente(iconPasso3, statusPasso3, btnPasso3,
                    "Ative o app na lista de acesso a uso", "Conceder Permissao");
        }

        // Passo 4 - Device Owner
        if (ownerOk) {
            marcarPassoConcluido(iconPasso4, statusPasso4, null, "Configurado como Dono do Dispositivo");
            layoutAdbInstrucao.setVisibility(android.view.View.GONE);
        } else {
            marcarPassoPendente(iconPasso4, statusPasso4, null,
                    "Requer comando ADB no PC (apenas 1 vez)", null);
            layoutAdbInstrucao.setVisibility(android.view.View.VISIBLE);
        }

        // Botao finalizar: precisa de conta removida + admin + usageStats
        boolean podeFinalizar = semContaGoogle && adminOk && usageOk;
        if (podeFinalizar) {
            btnFinalizar.setVisibility(android.view.View.VISIBLE);
            tvAvisoFaltando.setVisibility(android.view.View.GONE);
        } else {
            btnFinalizar.setVisibility(android.view.View.GONE);
            tvAvisoFaltando.setVisibility(android.view.View.VISIBLE);
        }
    }

    // ── Acoes dos botoes ─────────────────────────────────────────────────────

    // ── Acoes dos botoes ─────────────────────────────────────────────────────

    /**
     * Abre a tela de gerenciamento de contas do sistema usando uma cascata de Intents de fallback.
     *
     * <p>Diferentes fabricantes e versões do Android expoen Intents diferentes para a mesma
     * função. A estratégia é tentar em ordem do mais específico para o mais genérico:</p>
     * <ol>
     *   <li>{@code android.settings.ACCOUNT_SYNC_SETTINGS}: Tela de Contas &amp; Sincronização (AOSP/Samsung/Motorola)</li>
     *   <li>{@link android.provider.Settings#ACTION_SYNC_SETTINGS}: Tela geral de Contas (API 5+)</li>
     *   <li>{@link android.provider.Settings#ACTION_ADD_ACCOUNT}: Seletor de tipo de conta (navegável)</li>
     *   <li>{@link android.provider.Settings#ACTION_SETTINGS}: Configurações gerais (último recurso)</li>
     * </ol>
     * <p>Exibe um Toast orientando o operador sobre como remover a conta após abrir.</p>
     */
    private void abrirGerenciadorContas() {
        // Tentativas em ordem de especificidade — a primeira que funcionar é usada
        Intent[] tentativas = new Intent[]{
                // 1) Tela de Contas & Sincronização (AOSP / Samsung / Motorola)
                new Intent("android.settings.ACCOUNT_SYNC_SETTINGS"),
                // 2) Tela geral de Contas (API 5+)
                new Intent(Settings.ACTION_SYNC_SETTINGS),
                // 3) Fallback Adicionar conta — leva ao seletor de tipo de conta,
                //    de onde o usuário pode navegar para Contas existentes
                new Intent(Settings.ACTION_ADD_ACCOUNT)
                        .putExtra(Settings.EXTRA_ACCOUNT_TYPES, new String[]{"com.google"}),
                // 4) Último recurso: Configurações gerais
                new Intent(Settings.ACTION_SETTINGS)
        };

        for (Intent intent : tentativas) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                Toast.makeText(this,
                        "Vá em Contas > Google, selecione a conta e toque em \"Remover conta\".",
                        Toast.LENGTH_LONG).show();
                return;
            } catch (Exception ignored) {
                // tenta o próximo
            }
        }

        Toast.makeText(this,
                "Abra Configurações > Contas manualmente e remova a conta Google.",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Abre a tela nativa do Android para ativar o Device Admin deste app.
     *
     * <p>Usa a Intent {@link android.app.admin.DevicePolicyManager#ACTION_ADD_DEVICE_ADMIN},
     * que exibe uma dialog de confirmação ao usuário listando as políticas que o app
     * solicita (conforme declarado em {@code res/xml/device_admin_policies.xml}).</p>
     *
     * <p>Após a confirmação pelo usuário, o {@link #onResume} é chamado e
     * {@link #atualizarStatus} atualizará o Passo 2 para "concluído".</p>
     */
    private void ativarDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Necessario para controlar os apps do coletor.");
        startActivity(intent);
    }

    /**
     * Abre a tela de Acesso ao Uso de Apps do sistema ({@link android.provider.Settings#ACTION_USAGE_ACCESS_SETTINGS}).
     *
     * <p>O operador deve localizar o app "Coletor Bloqueado" na lista e ativar manualmente
     * a permissão. Sem ela, o {@link MonitoramentoService} não consegue detectar qual app
     * está em foreground via {@link android.app.usage.UsageStatsManager}, e o bloqueio
     * de apps não funciona.</p>
     *
     * <p>Um Toast orienta o operador sobre o que fazer na tela aberta.</p>
     */
    private void abrirUsageStats() {
        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        Toast.makeText(this,
                "Encontre 'Coletor Bloqueado' na lista e ative o acesso.",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Copia o comando ADB necessário para configurar o Device Owner para a área de transferência.
     *
     * <p>O comando copiado é:</p>
     * <pre>adb shell dpm set-device-owner com.brasil.coletorbloqueado/.MeuAdminReceiver</pre>
     *
     * <p>Este comando deve ser executado <b>uma única vez</b> no PC com o coletor conectado
     * via USB e a Depuração USB ativa. Requer que TODAS as contas de usuário tenham sido
     * removidas do dispositivo antes (Passo 1 do setup).</p>
     *
     * <p>Alternativa automatizada: executar {@code .\scripts\setup_device_owner.ps1}.</p>
     */
    private void copiarComandoAdb() {
        String comando = "adb shell dpm set-device-owner com.brasil.coletorbloqueado/.MeuAdminReceiver";
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Comando ADB", comando);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Comando copiado! Cole no terminal do PC.", Toast.LENGTH_SHORT).show();
    }

    /**
     * Conclui o setup: persiste a flag de conclusão, inicia o serviço e navega para a MainActivity.
     *
     * <p>Esta método é chamado apenas quando {@code podeFinalizar == true} (ou seja, os passos
     * 1, 2 e 3 estão concluídos). O Passo 4 (Device Owner) pode ser feito depois.</p>
     *
     * <p>Ações executadas:</p>
     * <ol>
     *   <li>Salva {@code setup_completo = true} nas SharedPreferences {@code ColetorBloqueadoPrefs}.</li>
     *   <li>Inicia o {@link MonitoramentoService} em foreground.</li>
     *   <li>Abre a {@link MainActivity} com a pilha limpa ({@code FLAG_ACTIVITY_CLEAR_TASK}).</li>
     *   <li>Encerra esta Activity.</li>
     * </ol>
     */
    private void concluirSetup() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_SETUP_COMPLETO, true).apply();

        try {
            Intent serviceIntent = new Intent(this, MonitoramentoService.class);
            startForegroundService(serviceIntent);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Erro ao iniciar servico: " + e.getMessage());
        }

        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ── Helpers visuais ──────────────────────────────────────────────────────

    // ── Helpers visuais ──────────────────────────────────────────────────────

    /**
     * Aplica o estilo visual de "passo concluído" a um conjunto de views.
     *
     * <p>Define o ícone circular como verde com checkmark, o texto de status em verde-ciano,
     * e desabilita o botão (se houver) com título "Concluído".</p>
     *
     * @param icon     TextView circular que exibe o ícone do passo
     * @param status   TextView que exibe o texto descritivo do status
     * @param btn      Button de ação do passo (pode ser {@code null} para o Passo 4)
     * @param mensagem texto a ser exibido no {@code status}
     */
    private void marcarPassoConcluido(TextView icon, TextView status, Button btn, String mensagem) {
        GradientDrawable circulo = new GradientDrawable();
        circulo.setShape(GradientDrawable.OVAL);
        circulo.setColor(Color.parseColor("#22AA55"));
        icon.setBackground(circulo);
        icon.setText("\u2713");

        status.setText(mensagem);
        status.setTextColor(Color.parseColor("#33FF99"));

        if (btn != null) {
            btn.setEnabled(false);
            btn.setText("Concluido");
            btn.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#22AA55")));
            btn.setAlpha(0.7f);
        }
    }

    /**
     * Aplica o estilo visual de "passo pendente" a um conjunto de views.
     *
     * <p>Define o ícone circular como vermelho, o texto de status em cinza e (opcionalmente)
     * reabilita o botão com o texto de ação fornecido.</p>
     *
     * @param icon          TextView circular que exibe o ícone do passo
     * @param status        TextView que exibe o texto descritivo do status
     * @param btn           Button de ação do passo (pode ser {@code null})
     * @param mensagemStatus texto explicativo do que está pendente
     * @param textoBotao    texto do botão ({@code null} se o botão não existir no passo)
     */
    private void marcarPassoPendente(TextView icon, TextView status, Button btn,
                                     String mensagemStatus, String textoBotao) {
        GradientDrawable circulo = new GradientDrawable();
        circulo.setShape(GradientDrawable.OVAL);
        circulo.setColor(Color.parseColor("#E94560"));
        icon.setBackground(circulo);

        status.setText(mensagemStatus);
        status.setTextColor(Color.parseColor("#AAAACC"));

        if (btn != null && textoBotao != null) {
            btn.setEnabled(true);
            btn.setText(textoBotao);
            btn.setBackgroundTintList(
                    android.content.res.ColorStateList.valueOf(Color.parseColor("#E94560")));
            btn.setAlpha(1.0f);
        }
    }

    // ── Verificacoes ─────────────────────────────────────────────────────────

    // ── Verificacoes ─────────────────────────────────────────────────────────

    /**
     * Verifica se existem contas de usuário configuradas no dispositivo.
     *
     * <p>Prioriza a detecção de contas Google ({@code com.google}), pois são elas que
     * tipicamente impedem o {@code dpm set-device-owner}. Retorna {@code true} se qualquer
     * conta Google for encontrada.</p>
     *
     * <p><b>Nota:</b> contas de sistema como {@code com.android.*}, Xiaomi ({@code com.xiaomi})
     * e Huawei ({@code com.huawei.hwid}) não são consideradas bloqueantes para fins deste check,
     * mas podem estar presentes em dispositivos de marca.</p>
     *
     * @return {@code true} se houver pelo menos uma conta Google; {@code false} caso contrário
     */
    private boolean temContaGoogle() {
        try {
            AccountManager am = AccountManager.get(this);
            // Verifica contas do tipo Google
            Account[] contas = am.getAccountsByType("com.google");
            if (contas.length > 0) return true;
            // Verifica outros tipos de conta que possam interferir
            Account[] todasContas = am.getAccounts();
            for (Account conta : todasContas) {
                if (!conta.type.startsWith("com.android") &&
                    !conta.type.equals("com.xiaomi") &&
                    !conta.type.equals("com.huawei.hwid")) {
                    // Conta de terceiros pode bloquear device owner
                    // mas focar apenas em Google para ser preciso
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Verifica se o {@link MeuAdminReceiver} está ativo como Device Admin.
     *
     * @return {@code true} se o Device Admin estiver ativo; {@code false} caso contrário
     */
    private boolean isDeviceAdmin() {
        return dpm.isAdminActive(adminComponent);
    }

    /**
     * Verifica se a permissão {@code GET_USAGE_STATS} (PACKAGE_USAGE_STATS) foi concedida.
     *
     * <p>Usa {@link android.app.AppOpsManager#checkOpNoThrow} para inspecionar o estado
     * da operação sem lançar exceção. Retorna {@code true} apenas se o modo for
     * {@link android.app.AppOpsManager#MODE_ALLOWED}.</p>
     *
     * @return {@code true} se a permissão de uso de apps estiver concedida
     */
    private boolean isUsageStatsPermitido() {
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Verifica se este app está configurado como Device Owner do dispositivo.
     *
     * <p>Device Owner é o privilégio máximo de gestão de dispositivo Android. Sem ele,
     * as APIs mais importantes do sistema (suspensão de pacotes, desativação da barra
     * de status, restrições de usuário) não ficam disponíveis.</p>
     *
     * @return {@code true} se o app for o Device Owner; {@code false} caso contrário
     */
    private boolean isDeviceOwner() {
        return dpm.isDeviceOwnerApp(getPackageName());
    }
}

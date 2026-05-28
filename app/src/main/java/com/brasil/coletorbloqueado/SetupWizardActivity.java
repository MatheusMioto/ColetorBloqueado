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

    private void ativarDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Necessario para controlar os apps do coletor.");
        startActivity(intent);
    }

    private void abrirUsageStats() {
        startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
        Toast.makeText(this,
                "Encontre 'Coletor Bloqueado' na lista e ative o acesso.",
                Toast.LENGTH_LONG).show();
    }

    private void copiarComandoAdb() {
        String comando = "adb shell dpm set-device-owner com.brasil.coletorbloqueado/.MeuAdminReceiver";
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Comando ADB", comando);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "Comando copiado! Cole no terminal do PC.", Toast.LENGTH_SHORT).show();
    }

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

    private boolean isDeviceAdmin() {
        return dpm.isAdminActive(adminComponent);
    }

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

    private boolean isDeviceOwner() {
        return dpm.isDeviceOwnerApp(getPackageName());
    }
}

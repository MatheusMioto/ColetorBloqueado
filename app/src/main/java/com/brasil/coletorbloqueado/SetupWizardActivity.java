package com.brasil.coletorbloqueado;

import android.app.admin.DevicePolicyManager;
import android.app.AppOpsManager;
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

    // Views do Passo 1 - Device Admin
    private TextView iconPasso1, statusPasso1;
    private Button btnPasso1;

    // Views do Passo 2 - Usage Stats
    private TextView iconPasso2, statusPasso2;
    private Button btnPasso2;

    // Views do Passo 3 - Device Owner
    private TextView iconPasso3, statusPasso3;
    private LinearLayout layoutAdbInstrucao;
    private Button btnCopiarAdb;

    // Botão final
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
        // Passo 1
        iconPasso1 = findViewById(R.id.iconPasso1);
        statusPasso1 = findViewById(R.id.statusPasso1);
        btnPasso1 = findViewById(R.id.btnPasso1);
        btnPasso1.setOnClickListener(v -> ativarDeviceAdmin());

        // Passo 2
        iconPasso2 = findViewById(R.id.iconPasso2);
        statusPasso2 = findViewById(R.id.statusPasso2);
        btnPasso2 = findViewById(R.id.btnPasso2);
        btnPasso2.setOnClickListener(v -> abrirUsageStats());

        // Passo 3
        iconPasso3 = findViewById(R.id.iconPasso3);
        statusPasso3 = findViewById(R.id.statusPasso3);
        layoutAdbInstrucao = findViewById(R.id.layoutAdbInstrucao);
        btnCopiarAdb = findViewById(R.id.btnCopiarAdb);
        btnCopiarAdb.setOnClickListener(v -> copiarComandoAdb());

        // Botão finalizar
        btnFinalizar = findViewById(R.id.btnFinalizar);
        btnFinalizar.setOnClickListener(v -> concluirSetup());

        tvAvisoFaltando = findViewById(R.id.tvAvisoFaltando);
    }

    private void atualizarStatus() {
        boolean adminOk = isDeviceAdmin();
        boolean usageOk = isUsageStatsPermitido();
        boolean ownerOk = isDeviceOwner();

        // Passo 1 - Device Admin
        if (adminOk) {
            marcarPassoConcluido(iconPasso1, statusPasso1, btnPasso1, "✓ Ativado com sucesso");
        } else {
            marcarPassoPendente(iconPasso1, statusPasso1, btnPasso1,
                    "Toque para ativar o Administrador de Dispositivo", "Ativar Administrador");
        }

        // Passo 2 - Usage Stats
        if (usageOk) {
            marcarPassoConcluido(iconPasso2, statusPasso2, btnPasso2, "✓ Permissão concedida");
        } else {
            marcarPassoPendente(iconPasso2, statusPasso2, btnPasso2,
                    "Ative o app na lista de acesso a uso", "Conceder Permissão");
        }

        // Passo 3 - Device Owner
        if (ownerOk) {
            marcarPassoConcluido(iconPasso3, statusPasso3, null, "✓ Configurado como Dono do Dispositivo");
            layoutAdbInstrucao.setVisibility(android.view.View.GONE);
        } else {
            marcarPassoPendente(iconPasso3, statusPasso3, null,
                    "Requer comando ADB no PC (apenas 1 vez)", null);
            layoutAdbInstrucao.setVisibility(android.view.View.VISIBLE);
        }

        // Botão finalizar: mostra se Admin + UsageStats estão ok
        // Device Owner é desejável mas não impede o uso básico
        boolean podeFinalizar = adminOk && usageOk;
        if (podeFinalizar) {
            btnFinalizar.setVisibility(android.view.View.VISIBLE);
            tvAvisoFaltando.setVisibility(android.view.View.GONE);
        } else {
            btnFinalizar.setVisibility(android.view.View.GONE);
            tvAvisoFaltando.setVisibility(android.view.View.VISIBLE);
        }
    }

    private void marcarPassoConcluido(TextView icon, TextView status, Button btn, String mensagem) {
        // Círculo verde
        GradientDrawable circulo = new GradientDrawable();
        circulo.setShape(GradientDrawable.OVAL);
        circulo.setColor(Color.parseColor("#22AA55"));
        icon.setBackground(circulo);
        icon.setText("✓");

        status.setText(mensagem);
        status.setTextColor(Color.parseColor("#33FF99"));

        if (btn != null) {
            btn.setEnabled(false);
            btn.setText("Concluído ✓");
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#22AA55")));
            btn.setAlpha(0.7f);
        }
    }

    private void marcarPassoPendente(TextView icon, TextView status, Button btn,
                                     String mensagemStatus, String textoBotao) {
        // Círculo vermelho
        GradientDrawable circulo = new GradientDrawable();
        circulo.setShape(GradientDrawable.OVAL);
        circulo.setColor(Color.parseColor("#E94560"));
        icon.setBackground(circulo);

        status.setText(mensagemStatus);
        status.setTextColor(Color.parseColor("#AAAACC"));

        if (btn != null && textoBotao != null) {
            btn.setEnabled(true);
            btn.setText(textoBotao);
            btn.setBackgroundTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#E94560")));
            btn.setAlpha(1.0f);
        }
    }

    private void ativarDeviceAdmin() {
        Intent intent = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
        intent.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent);
        intent.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                "Necessário para controlar os apps do coletor.");
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
        // Salvar flag de setup completo
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putBoolean(KEY_SETUP_COMPLETO, true).apply();

        // Iniciar serviço de monitoramento
        try {
            Intent serviceIntent = new Intent(this, MonitoramentoService.class);
            startForegroundService(serviceIntent);
        } catch (Exception e) {
            android.util.Log.e(TAG, "Erro ao iniciar serviço: " + e.getMessage());
        }

        // Ir para a MainActivity
        Intent intent = new Intent(this, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ── Verificações ─────────────────────────────────────────────────────────

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

package com.brasil.coletorbloqueado;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.security.MessageDigest;

public class SettingsPasswordActivity extends AppCompatActivity {
    private static final String TAG = "SettingsPassword";
    private static final String SENHA_MESTRE_HASH = "8d969eef6ecad3c29a3a629280e686cf0c3f5d5a86aff3ca12020c923adc6c92";

    private EditText etSenhaSettings;
    private Button btnCancelSettings, btnConfirmSettings;
    private String targetPackage;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings_password);

        targetPackage = getIntent().getStringExtra("target_package");
        if (targetPackage == null || targetPackage.isEmpty()) {
            voltarParaHome();
            return;
        }

        etSenhaSettings = findViewById(R.id.etSenhaSettings);
        btnCancelSettings = findViewById(R.id.btnCancelSettings);
        btnConfirmSettings = findViewById(R.id.btnConfirmSettings);

        btnCancelSettings.setOnClickListener(v -> voltarParaHome());
        btnConfirmSettings.setOnClickListener(v -> validarSenha());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        targetPackage = intent.getStringExtra("target_package");
    }

    private void validarSenha() {
        String senha = etSenhaSettings.getText().toString();
        if (senha.isEmpty()) {
            Toast.makeText(this, "Por favor, digite a senha!", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        String senhaSalvaHash = pref.getString("senha_mestre_hash", SENHA_MESTRE_HASH);
        String hashDigitado = calcularSHA256(senha);

        if (hashDigitado.equals(senhaSalvaHash)) {
            // Desbloqueia temporariamente apenas o pacote selecionado
            MonitoramentoService.unlockedPackages.put(targetPackage, System.currentTimeMillis());
            Toast.makeText(this, "Acesso autorizado!", Toast.LENGTH_SHORT).show();
            finish();
        } else {
            Toast.makeText(this, "Senha Administrativa Incorreta!", Toast.LENGTH_SHORT).show();
            voltarParaHome();
        }
    }

    private void voltarParaHome() {
        // Envia o usuário para a Home do Android, saindo da tela de Configurações
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);
        finish();
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        voltarParaHome();
    }

    private String calcularSHA256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao calcular hash SHA-256", e);
            return "";
        }
    }
}

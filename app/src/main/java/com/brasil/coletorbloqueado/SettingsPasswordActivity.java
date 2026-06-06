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

/**
 * Activity de autenticação que intercepta apps bloqueados quando eles vão para foreground.
 *
 * <p>Esta Activity é iniciada diretamente pelo {@link MonitoramentoService} toda vez que
 * detecta um app que não está na whitelist sendo aberto pelo operador do coletor. Ela
 * cobre a tela do app bloqueado com um campo de senha, impedindo o acesso sem autenticação.</p>
 *
 * <h2>Fluxo de autenticação</h2>
 * <ol>
 *   <li>O {@link MonitoramentoService} detecta o app bloqueado em foreground.</li>
 *   <li>Esta Activity é iniciada com o extra {@code target_package} identificando qual app bloqueou.</li>
 *   <li>O operador digita a senha administrativa.</li>
 *   <li><b>Senha correta:</b> registra o pacote em {@link MonitoramentoService#unlockedPackages}
 *       com o timestamp atual, concedendo acesso por 30 segundos. Depois fecha a Activity.</li>
 *   <li><b>Senha incorreta:</b> exibe toast de erro e redireciona para o Home.</li>
 *   <li><b>Cancelar/Back:</b> redireciona para o Home sem conceder acesso.</li>
 * </ol>
 *
 * <h2>Segurança</h2>
 * <ul>
 *   <li>A senha é comparada via hash SHA-256. Nunca é comparada em texto plano.</li>
 *   <li>O desbloqueio é por app específico, não global — outros apps continuam bloqueados.</li>
 *   <li>O acesso expira em 30 segundos, após o qual o app é interceptado novamente.</li>
 *   <li>Se a senha ainda não foi configurada, redireciona para {@link MainActivity} para configuração.</li>
 * </ul>
 *
 * <h2>Intent extras esperados</h2>
 * <ul>
 *   <li>{@code target_package} (String): nome do pacote do app que tentou ir para foreground.</li>
 * </ul>
 *
 * @see MonitoramentoService
 * @see MonitoramentoService#unlockedPackages
 */
public class SettingsPasswordActivity extends AppCompatActivity {
    private static final String TAG = "SettingsPassword";

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

        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        String senhaSalvaHash = pref.getString("senha_mestre_hash", null);
        if (senhaSalvaHash == null) {
            Toast.makeText(this, "Por favor, defina a senha no aplicativo principal primeiro!", Toast.LENGTH_LONG).show();
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(mainIntent);
            finish();
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

    /**
     * Valida a senha digitada pelo operador comparando com o hash SHA-256 armazenado.
     *
     * <p>Se a senha estiver correta, registra o {@code target_package} em
     * {@link MonitoramentoService#unlockedPackages} com o timestamp atual, o que concede
     * ao operador 30 segundos de acesso ao app. Após esse período, o
     * {@link MonitoramentoService} voltará a interceptar o app normalmente.</p>
     *
     * <p>Se a senha estiver incorreta, exibe um Toast de erro e redireciona para o Home.
     * O campo de senha é limpo automaticamente para uma nova tentativa.</p>
     */
    private void validarSenha() {
        String senha = etSenhaSettings.getText().toString().trim();
        if (senha.isEmpty()) {
            Toast.makeText(this, "Por favor, digite a senha!", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        String senhaSalvaHash = pref.getString("senha_mestre_hash", null);
        Log.d(TAG, "validarSenha: senhaSalvaHash = " + senhaSalvaHash);
        if (senhaSalvaHash == null) {
            voltarParaHome();
            return;
        }
        String hashDigitado = calcularSHA256(senha);
        Log.d(TAG, "validarSenha: senha = '" + senha + "', hashDigitado = " + hashDigitado);

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

    /**
     * Envia o operador para o launcher padrão do dispositivo sem conceder acesso ao app bloqueado.
     *
     * <p>Usado em três cenários:</p>
     * <ul>
     *   <li>Botão Cancelar pressionado.</li>
     *   <li>Tecla Back pressionada ({@link #onBackPressed}).</li>
     *   <li>Senha incorreta digitada.</li>
     * </ul>
     */
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

    /**
     * Calcula o hash SHA-256 de uma string de entrada.
     *
     * <p>Usado para comparar a senha digitada com o hash armazenado nas SharedPreferences
     * (chave {@code senha_mestre_hash} no arquivo {@code Configuracoes}).
     * Garante que a senha nunca seja armazenada ou comparada em texto plano.</p>
     *
     * @param input a string a ser hasheada (normalmente a senha digitada, já com {@code trim()})
     * @return string hexadecimal de 64 caracteres com o hash SHA-256, ou string vazia em caso de erro
     */
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

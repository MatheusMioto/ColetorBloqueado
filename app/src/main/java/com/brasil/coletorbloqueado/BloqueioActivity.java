package com.brasil.coletorbloqueado;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Activity transparente e efêmera usada como intermediária para forçar a troca de foreground
 * de apps bloqueados de forma confiável em todas as versões e ROMs do Android.
 *
 * <h2>Por que esta Activity existe?</h2>
 *
 * <p>A partir do Android 10 (API 29), o Android impôs restrições severas de BAL
 * (Background Activity Launch), que impedem que serviços em background iniciem Activities
 * diretamente em muitos cenários. Essa limitação tornaria inconsistente a interceptação de
 * apps bloqueados pelo {@link MonitoramentoService}.</p>
 *
 * <p>A solução é usar esta Activity como "ponte": o {@link MonitoramentoService} a inicia
 * com {@link Intent#FLAG_ACTIVITY_NEW_TASK}, o que é permitido mesmo de serviços. Uma vez
 * que esta Activity está em foreground (visível), ela pode chamar {@code startActivity()} sem
 * nenhuma restrição de BAL, pois parte de um contexto de Activity ativo.</p>
 *
 * <h2>Fluxo de execução</h2>
 * <ol>
 *   <li>{@link MonitoramentoService} detecta um app bloqueado em foreground.</li>
 *   <li>O serviço inicia {@link SettingsPasswordActivity} diretamente com as flags corretas.</li>
 *   <li>Se necessário usar esta Activity como intermediária para o launcher:
 *       {@code BloqueioActivity} recebe o foco → inicia o Home → encerra a si mesma.</li>
 * </ol>
 *
 * <h2>Características de design</h2>
 * <ul>
 *   <li><b>Transparente:</b> não infla nenhum layout — o tema no AndroidManifest deve ser
 *       transparente ({@code Theme.Translucent.NoTitleBar}) para ser imperceptível ao usuário.</li>
 *   <li><b>Efêmera:</b> chama {@link #finish()} imediatamente após redirecionar.</li>
 *   <li><b>Não aparece no Recents:</b> o {@link MonitoramentoService} adiciona a flag
 *       {@link Intent#FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS} ao iniciar esta Activity.</li>
 * </ul>
 */
public class BloqueioActivity extends Activity {

    private static final String TAG = "BloqueioActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Sem layout — esta Activity é completamente transparente e efêmera.
        // Qualquer interface visual desnecessária quebraria a ilusão de invisibilidade.
    }

    /**
     * Ponto principal de ação. Chamado quando a Activity recebe o foco (foreground).
     *
     * <p>A partir daqui, o Android permite lançar qualquer Activity sem restrições de BAL,
     * pois este código executa dentro de uma Activity visível ativa.</p>
     *
     * <p>Envia o usuário para o launcher padrão do dispositivo e encerra imediatamente
     * para não poluir a pilha de activities (back stack) nem aparecer nos Recents.</p>
     */
    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "BloqueioActivity em foreground. Redirecionando para Home.");

        // Lança o Home a partir da janela visível desta Activity.
        // Iniciado de uma Activity ativa, este startActivity() NUNCA é bloqueado
        // pelo Android, independentemente de versão ou ROM.
        Intent homeIntent = new Intent(Intent.ACTION_MAIN);
        homeIntent.addCategory(Intent.CATEGORY_HOME);
        homeIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(homeIntent);

        // Encerra esta Activity para não ficar na pilha de recentes
        finish();
    }
}

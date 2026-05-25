package com.brasil.coletorbloqueado;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Activity transparente de bloqueio.
 *
 * Técnica: quando o MonitoramentoService detecta Settings ou Play Store em foreground,
 * ele inicia esta Activity com FLAG_ACTIVITY_NEW_TASK. Ela recebe o foco (empurrando o
 * app bloqueado para background), depois lança o Home a partir de sua própria janela
 * visível — o que sempre funciona sem restrições de BAL (Background Activity Launch),
 * diferente de chamar startActivity() diretamente de um Service.
 */
public class BloqueioActivity extends Activity {

    private static final String TAG = "BloqueioActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Sem layout — esta activity é completamente transparente e efêmera
    }

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

        // Encerra esta activity para não ficar na pilha de recentes
        finish();
    }
}

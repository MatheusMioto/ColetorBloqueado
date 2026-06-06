package com.brasil.coletorbloqueado;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Receiver responsável por reiniciar o {@link MonitoramentoService} após o boot do dispositivo.
 *
 * <p>O Android encerra todos os serviços ao desligar o dispositivo. Este receiver escuta o
 * broadcast {@link Intent#ACTION_BOOT_COMPLETED} — emitido pelo sistema ao término do boot —
 * e reinicia o serviço de monitoramento para garantir que a proteção esteja ativa imediatamente
 * após o dispositivo ligar.</p>
 *
 * <p><b>Permissão necessária:</b> {@code RECEIVE_BOOT_COMPLETED} (declarada no AndroidManifest).</p>
 *
 * <p><b>Comportamento por versão do Android:</b></p>
 * <ul>
 *   <li>Android 8.0+ (API 26+): usa {@link Context#startForegroundService} obrigatoriamente,
 *       pois serviços de background têm restrições severas.</li>
 *   <li>Android &lt; 8.0: usa {@link Context#startService} simples.</li>
 * </ul>
 *
 * <p><b>Pré-condição:</b> O setup inicial deve ter sido concluído (flag {@code setup_completo} = true
 * nas SharedPreferences). Caso contrário, o {@link MonitoramentoService} será iniciado mas
 * redirecionará para o {@link SetupWizardActivity} via {@link MainActivity}.</p>
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, MonitoramentoService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+: obrigatório usar startForegroundService para serviços em background.
                // O serviço DEVE chamar startForeground() em até 5 segundos ou será morto pelo sistema.
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
        }
    }
}
package com.brasil.coletorbloqueado;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Receiver que gerencia solicitações externas de alteração do launcher (Home) padrão do dispositivo.
 *
 * <p>Este receiver é invocado por aplicativos externos (ex: Mobile Butler da Totvs) que precisam
 * alterar o launcher padrão do coletor sem intervenção do usuário. A alteração é coordenada
 * de forma segura para evitar que o {@link MonitoramentoService} interfira durante o processo
 * (que requer acesso ao app de Configurações do sistema).</p>
 *
 * <h2>Broadcast suportado</h2>
 * <table border="1">
 *   <tr><th>Campo</th><th>Valor</th></tr>
 *   <tr><td>Action</td><td>{@code com.brasil.coletorbloqueado.ACAO_SOLICITAR_ALTERAR_HOME}</td></tr>
 *   <tr><td>Pacote</td><td>Não requer extra — abre a tela de seleção de Home do sistema</td></tr>
 * </table>
 *
 * <h2>Fluxo de execução ao receber o broadcast</h2>
 * <ol>
 *   <li>Sinaliza para o {@link MonitoramentoService} (via {@code isChangingHome} em SharedPreferences
 *       e via {@link MainActivity#isChangingHome}) que uma troca de launcher está em andamento,
 *       suspendendo temporariamente o loop de interceptação.</li>
 *   <li>Desuspende o app {@code com.android.settings} (Configurações) para que o usuário
 *       possa interagir com a tela de seleção de Home.</li>
 *   <li>Abre {@link Settings#ACTION_HOME_SETTINGS} para que o usuário selecione o novo launcher.</li>
 * </ol>
 *
 * <p><b>Pré-requisito:</b> O app ColetorBloqueado deve ser Device Owner para poder chamar
 * {@link DevicePolicyManager#setPackagesSuspended}.</p>
 *
 * <p><b>Restauração:</b> Após a troca ser concluída pelo usuário, o {@link HomeRestoreReceiver}
 * deve ser acionado com a action {@code ACAO_DEFINIR_HOME_PADRAO} para fixar o novo launcher
 * via DPM e limpar a flag {@code isChangingHome}.</p>
 *
 * @see HomeRestoreReceiver
 * @see MonitoramentoService
 */
public class HomeChangerReceiver extends BroadcastReceiver {

    private static final String TAG = "HomeChangerReceiver";

    /** Action do broadcast que aciona a troca de launcher. */
    public static final String ACAO_SOLICITAR_ALTERAR_HOME =
            "com.brasil.coletorbloqueado.ACAO_SOLICITAR_ALTERAR_HOME";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACAO_SOLICITAR_ALTERAR_HOME.equals(intent.getAction())) {
            return;
        }

        Log.d(TAG, "Solicitação de alteração de Home recebida via Broadcast.");

        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = new ComponentName(context, MeuAdminReceiver.class);

        // 1. Sinaliza para a MainActivity e para o Service que a troca está ocorrendo.
        //    Isso pausa o loop de interceptação do MonitoramentoService enquanto o usuário
        //    interage com a tela de Configurações de Home.
        MainActivity.isChangingHome = true;
        android.content.SharedPreferences pref = context.getSharedPreferences("Configuracoes", Context.MODE_PRIVATE);
        pref.edit().putBoolean("isChangingHome", true).apply();

        // 2. Desuspende temporariamente o app de Configurações do sistema.
        //    O MonitoramentoService normalmente interceptaria qualquer acesso a este app,
        //    mas a flag isChangingHome acima suspende essa verificação.
        try {
            if (dpm != null && dpm.isDeviceOwnerApp(context.getPackageName())) {
                dpm.setPackagesSuspended(adminComponent, new String[]{"com.android.settings"}, false);
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao liberar settings para troca de launcher", e);
        }

        // 3. Abre a tela nativa de escolha do aplicativo Home.
        //    Após o usuário selecionar o launcher, o HomeRestoreReceiver deve ser acionado
        //    para persistir a escolha e limpar a flag isChangingHome.
        Intent changeIntent = new Intent(Settings.ACTION_HOME_SETTINGS);
        changeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(changeIntent);
    }
}

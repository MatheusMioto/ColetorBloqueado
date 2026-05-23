package com.brasil.coletorbloqueado;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

public class HomeChangerReceiver extends BroadcastReceiver {
    private static final String TAG = "HomeChangerReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if ("com.brasil.coletorbloqueado.ACAO_SOLICITAR_ALTERAR_HOME".equals(intent.getAction())) {
            Log.d(TAG, "Solicitação de alteração de Home recebida via Broadcast.");
            
            DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName adminComponent = new ComponentName(context, MeuAdminReceiver.class);

            // 1. Sinaliza para a MainActivity e para o Service que a troca está ocorrendo
            MainActivity.isChangingHome = true;
            android.content.SharedPreferences pref = context.getSharedPreferences("Configuracoes", Context.MODE_PRIVATE);
            pref.edit().putBoolean("isChangingHome", true).apply();

            // 2. Libera temporariamente as configurações do sistema
            try {
                if (dpm != null && dpm.isDeviceOwnerApp(context.getPackageName())) {
                    dpm.setPackagesSuspended(adminComponent, new String[]{"com.android.settings"}, false);
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro ao liberar settings", e);
            }

            // 3. Abre a tela de escolha da Home
            Intent changeIntent = new Intent(Settings.ACTION_HOME_SETTINGS);
            changeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(changeIntent);
        }
    }
}

package com.brasil.coletorbloqueado;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;
import java.util.List;

/**
 * Receptor responsável por alterar ou restaurar o aplicativo Home padrão.
 *
 * Ações de Broadcast suportadas:
 *   Action: "com.brasil.coletorbloqueado.ACAO_DEFINIR_HOME_PADRAO"
 *   Extra:  "pacote" (String) - Opcional. O nome do pacote a ser definido como Home padrão (ex: "com.mobile.butler").
 *           Se omitido ou nulo, restaura o launcher padrão/original do sistema.
 */
public class HomeRestoreReceiver extends BroadcastReceiver {
    private static final String TAG = "HomeRestoreReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!"com.brasil.coletorbloqueado.ACAO_DEFINIR_HOME_PADRAO".equals(action) &&
            !"com.brasil.coletorbloqueado.ACAO_RESTAURAR_HOME_PADRAO".equals(action)) {
            return;
        }

        String targetPackage = intent.getStringExtra("pacote");
        Log.d(TAG, "Solicitação recebida para definir/restaurar Home. Ação: " + action + ", Pacote destino: " + targetPackage);

        DevicePolicyManager dpm =
                (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminComponent = new ComponentName(context, MeuAdminReceiver.class);

        if (dpm == null || !dpm.isDeviceOwnerApp(context.getPackageName())) {
            Log.e(TAG, "Não é Device Owner – impossível alterar configurações de Home.");
            return;
        }

        android.content.SharedPreferences pref = context.getSharedPreferences("Configuracoes", Context.MODE_PRIVATE);

        if (targetPackage == null || targetPackage.trim().isEmpty()) {
            // Se nenhum pacote foi passado, removemos qualquer regra criada anteriormente para deixar o Android decidir nativamente.
            Log.d(TAG, "Nenhum pacote fornecido. Limpando customizações e restaurando o comportamento nativo do Android.");
            String prevPackage = pref.getString("preferred_home_package", null);
            try {
                dpm.clearPackagePersistentPreferredActivities(adminComponent, context.getPackageName());
                if (prevPackage != null) {
                    dpm.clearPackagePersistentPreferredActivities(adminComponent, prevPackage);
                }
            } catch (Exception e) {
                Log.e(TAG, "Erro ao limpar preferências persistentes de launcher", e);
            }

            pref.edit()
                .remove("preferred_home_package")
                .putBoolean("isChangingHome", false)
                .apply();
            MainActivity.isChangingHome = false;
            return;
        }

        // Se um pacote específico foi fornecido
        targetPackage = targetPackage.trim();
        ComponentName targetComponent = getHomeActivityForPackage(context, targetPackage);
        if (targetComponent == null) {
            Log.e(TAG, "Nenhuma activity Home encontrada para o pacote: " + targetPackage);
            return;
        }

        // 1. Cria o filtro de intent para Home
        IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN);
        homeFilter.addCategory(Intent.CATEGORY_HOME);
        homeFilter.addCategory(Intent.CATEGORY_DEFAULT);

        try {
            // 2. Remove qualquer preferência anterior de launcher
            dpm.clearPackagePersistentPreferredActivities(adminComponent, context.getPackageName());
            dpm.clearPackagePersistentPreferredActivities(adminComponent, targetPackage);

            // 3. Define a nova Home padrão de forma persistente (muda sem interação do usuário)
            dpm.addPersistentPreferredActivity(adminComponent, homeFilter, targetComponent);
            Log.d(TAG, "Home padrão alterado com sucesso para: " + targetComponent.flattenToString());



            // 5. Salva o pacote preferido de Home e limpa a flag temporária de troca
            pref.edit()
                .putString("preferred_home_package", targetPackage)
                .putBoolean("isChangingHome", false)
                .apply();
            MainActivity.isChangingHome = false;

        } catch (Exception e) {
            Log.e(TAG, "Erro ao alterar a Home persistente", e);
        }
    }

    /**
     * Busca a Home Activity (Launch activity com categoria HOME) para o pacote fornecido.
     */
    private ComponentName getHomeActivityForPackage(Context context, String packageName) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);
        for (ResolveInfo info : resolveInfos) {
            if (info.activityInfo != null && info.activityInfo.packageName.equalsIgnoreCase(packageName)) {
                return new ComponentName(info.activityInfo.packageName, info.activityInfo.name);
            }
        }
        return null;
    }

    /**
     * Busca o launcher original/padrão do sistema Android (que possui FLAG_SYSTEM).
     */
    private ComponentName getSystemLauncherActivity(Context context) {
        PackageManager pm = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, 0);

        // 1. Busca por launcher do sistema que não seja este próprio app
        for (ResolveInfo info : resolveInfos) {
            if (info.activityInfo != null) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equalsIgnoreCase(context.getPackageName())) {
                    continue;
                }
                if ((info.activityInfo.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                    return new ComponentName(pkg, info.activityInfo.name);
                }
            }
        }

        // 2. Fallback: Primeiro launcher que não seja o nosso app
        for (ResolveInfo info : resolveInfos) {
            if (info.activityInfo != null) {
                String pkg = info.activityInfo.packageName;
                if (!pkg.equalsIgnoreCase(context.getPackageName())) {
                    return new ComponentName(pkg, info.activityInfo.name);
                }
            }
        }
        return null;
    }
}

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
 * Receiver responsável por definir ou restaurar o launcher (Home) padrão do dispositivo de forma
 * persistente via {@link android.app.admin.DevicePolicyManager#addPersistentPreferredActivity}.
 *
 * <p>Esta classe garante que, mesmo após reinicializações do dispositivo, o launcher configurado
 * permaneça como padrão sem exigir interação do usuário — algo impossível sem privilégios de
 * Device Owner.</p>
 *
 * <h2>Broadcasts suportados</h2>
 * <table border="1">
 *   <tr><th>Action</th><th>Extra "pacote"</th><th>Efeito</th></tr>
 *   <tr>
 *     <td>{@code ACAO_DEFINIR_HOME_PADRAO}</td>
 *     <td>Pacote do launcher destino (ex: {@code br.com.totvs.mobilebutler})</td>
 *     <td>Define aquele launcher como Home padrão permanente</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ACAO_DEFINIR_HOME_PADRAO}</td>
 *     <td>{@code null} ou vazio</td>
 *     <td>Remove qualquer regra persistente, deixando o Android decidir nativamente</td>
 *   </tr>
 *   <tr>
 *     <td>{@code ACAO_RESTAURAR_HOME_PADRAO}</td>
 *     <td>Ignorado</td>
 *     <td>Remove regras persistentes e restaura o launcher padrão do sistema</td>
 *   </tr>
 * </table>
 *
 * <h2>Mecanismo de persistência</h2>
 * <p>Usa {@link android.app.admin.DevicePolicyManager#addPersistentPreferredActivity} com um
 * {@link android.content.IntentFilter} para {@link android.content.Intent#ACTION_MAIN} +
 * {@link android.content.Intent#CATEGORY_HOME}. Isso faz o Android sempre abrir o launcher
 * especificado quando o usuário pressiona Home, sem mostrar o seletor de apps.</p>
 *
 * <p><b>Pré-requisito:</b> O app deve ser Device Owner. Sem esse privilégio, todas as
 * operações deste receiver falham silenciosamente com log de erro.</p>
 *
 * @see HomeChangerReceiver
 * @see android.app.admin.DevicePolicyManager#addPersistentPreferredActivity
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
     * Localiza a Activity que responde a intents de Home (ACTION_MAIN + CATEGORY_HOME) dentro
     * do pacote especificado, para ser usada como destino da regra persistente do DPM.
     *
     * @param context     contexto da aplicação
     * @param packageName nome do pacote do launcher (ex: {@code br.com.totvs.mobilebutler})
     * @return {@link ComponentName} da Activity de Home do pacote, ou {@code null} se o pacote
     *         não estiver instalado ou não declarar uma Activity de Home
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
     * Localiza o launcher nativo do sistema Android (aquele que possui a flag
     * {@link android.content.pm.ApplicationInfo#FLAG_SYSTEM}).
     *
     * <p>Usado como fallback para restaurar o launcher original quando nenhum launcher
     * personalizado está configurado.</p>
     *
     * <p><b>Estratégia de busca:</b></p>
     * <ol>
     *   <li>Procura o primeiro launcher com {@code FLAG_SYSTEM} que não seja o próprio app.</li>
     *   <li>Se não encontrar, retorna o primeiro launcher instalado que não seja o próprio app
     *       (fallback genérico).</li>
     * </ol>
     *
     * @param context contexto da aplicação
     * @return {@link ComponentName} do launcher do sistema, ou {@code null} se nenhum for encontrado
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

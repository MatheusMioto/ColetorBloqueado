package com.brasil.coletorbloqueado;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.UserManager;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MonitoramentoService extends Service {
    private static final String TAG = "MonitoramentoService";
    private static final String CHANNEL_ID = "MonitoramentoServiceChannel";
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    public static boolean settingsUnlocked = false;
    public static long settingsUnlockedTime = 0;

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable verificadorRunnable = new Runnable() {
        @Override
        public void run() {
            verificarTimerManutencao();
            verificarConfiguracoesBloqueio();
            handler.postDelayed(this, 500); // Executa a cada 500ms
        }
    };

    private final android.content.BroadcastReceiver shutdownReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "ShutdownReceiver dinâmico recebeu ação: " + action);
            if (Intent.ACTION_SHUTDOWN.equals(action) ||
                "android.intent.action.QUICKBOOT_POWEROFF".equals(action) ||
                "com.htc.intent.action.QUICKBOOT_POWEROFF".equals(action)) {
                
                SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
                boolean modoManutencaoAtivo = pref.getBoolean("modoManutencaoAtivo", false);

                // Só suspende/oculta se NÃO estiver no modo manutenção
                if (!modoManutencaoAtivo && dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                    try {
                        // Suspende Settings (funciona nativamente)
                        dpm.setPackagesSuspended(adminComponent, new String[]{"com.android.settings"}, true);
                        // Oculta a Play Store (como a suspensão falha para vending, ocultar é 100% eficaz)
                        dpm.setApplicationHidden(adminComponent, "com.android.vending", true);
                        Log.d(TAG, "Settings suspenso e Play Store oculta com sucesso no desligamento.");
                    } catch (Exception e) {
                        Log.e(TAG, "Erro ao suspender/ocultar no desligamento dinâmico", e);
                    }
                }
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MeuAdminReceiver.class);
        
        // Ao iniciar o serviço, remove a suspensão de Settings e exibe a Play Store
        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            try {
                dpm.setPackagesSuspended(adminComponent, new String[]{"com.android.settings"}, false);
                dpm.setApplicationHidden(adminComponent, "com.android.vending", false);
                Log.d(TAG, "Serviço iniciado. Settings removido de suspensão e Play Store exibida.");
            } catch (Exception e) {
                Log.e(TAG, "Erro ao restaurar Settings/Play Store ao iniciar serviço", e);
            }
        }

        // Registra o receptor de desligamento dinâmico
        android.content.IntentFilter shutdownFilter = new android.content.IntentFilter();
        shutdownFilter.addAction(Intent.ACTION_SHUTDOWN);
        shutdownFilter.addAction("android.intent.action.QUICKBOOT_POWEROFF");
        shutdownFilter.addAction("com.htc.intent.action.QUICKBOOT_POWEROFF");
        registerReceiver(shutdownReceiver, shutdownFilter);

        createNotificationChannel();
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Segurança Ativa")
                .setContentText("O coletor está protegido em segundo plano.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build();
        startForeground(1, notification);

        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        boolean modoManutencaoAtivo = pref.getBoolean("modoManutencaoAtivo", false);

        if (!modoManutencaoAtivo) {
            aplicarBloqueioSilencioso();
        } else {
            liberarBloqueioSilencioso();
        }

        // Inicia o verificador periódico
        handler.post(verificadorRunnable);
    }

    private void aplicarBloqueioSilencioso() {
        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            try {
                dpm.setStatusBarDisabled(adminComponent, true);
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS);
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
                setAppsSuspended(true);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao aplicar bloqueio silencioso corporativo", e);
            }
        }
    }

    private void liberarBloqueioSilencioso() {
        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            try {
                dpm.setStatusBarDisabled(adminComponent, false);
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS);
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
                setAppsSuspended(false);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao liberar bloqueio de manutencao", e);
            }
        }
    }

    public void setAppsSuspended(boolean suspended) {
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;

        PackageManager pm = getPackageManager();
        List<PackageInfo> packages = pm.getInstalledPackages(0);
        List<String> packagesToSuspend = new ArrayList<>();

        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        Set<String> whitelist = pref.getStringSet("whitelist", new HashSet<>());
        Set<String> launcherPackages = getLauncherPackages();

        for (PackageInfo pkg : packages) {
            String pName = pkg.packageName;
            if (pName.equals(getPackageName())) continue;
            
            // Settings e Play Store não devem ser suspensos de sistema enquanto o serviço está ativo
            if ("com.android.settings".equals(pName) || "com.android.vending".equals(pName)) {
                continue;
            }

            if (whitelist.contains(pName)) continue;

            if (launcherPackages.contains(pName)) continue;

            if (pName.contains("android.overlay") || pName.equals("android") || 
                pName.contains("com.android.systemui")) {
                continue;
            }

            packagesToSuspend.add(pName);
        }

        if (!packagesToSuspend.isEmpty()) {
            try {
                dpm.setPackagesSuspended(adminComponent, packagesToSuspend.toArray(new String[0]), suspended);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao alterar estado de suspensao de pacotes corporativos", e);
            }
        }
    }

    private Set<String> getLauncherPackages() {
        Set<String> launchers = new HashSet<>();
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        List<ResolveInfo> resolveInfos = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL);
        for (ResolveInfo info : resolveInfos) {
            if (info.activityInfo != null) {
                launchers.add(info.activityInfo.packageName);
            }
        }
        return launchers;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Verificação imediata ao subir o serviço: cobre o cenário onde
        // Settings/Play Store já estão em foreground antes do loop periódico iniciar.
        verificarConfiguracoesBloqueio();
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(verificadorRunnable);

        try {
            unregisterReceiver(shutdownReceiver);
        } catch (Exception e) {
            Log.w(TAG, "Erro ao desregistrar shutdownReceiver dinâmico", e);
        }

        // Fail-safe: suspende Settings e oculta Play Store se o serviço for destruído (e não em manutenção)
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        boolean modoManutencaoAtivo = pref.getBoolean("modoManutencaoAtivo", false);

        if (!modoManutencaoAtivo && dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            try {
                dpm.setPackagesSuspended(adminComponent, new String[]{"com.android.settings"}, true);
                dpm.setApplicationHidden(adminComponent, "com.android.vending", true);
                Log.d(TAG, "Serviço encerrado. Settings suspenso e Play Store oculta.");
            } catch (Exception e) {
                Log.e(TAG, "Erro ao suspender/ocultar no onDestroy", e);
            }
        }
    }

    private void verificarTimerManutencao() {
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        boolean modoManutencaoAtivo = pref.getBoolean("modoManutencaoAtivo", false);
        if (modoManutencaoAtivo) {
            long expTime = pref.getLong("manutencao_expiracao_timestamp", 0);
            if (expTime > 0 && System.currentTimeMillis() >= expTime) {
                Log.d(TAG, "Temporizador de manutencao esgotado no Service. Bloqueando coletor...");
                
                pref.edit()
                        .putBoolean("modoManutencaoAtivo", false)
                        .remove("manutencao_expiracao_timestamp")
                        .remove("manutencao_timer_opcao_index")
                        .apply();
                
                aplicarBloqueioSilencioso();
                
                Intent intent = new Intent("com.brasil.coletorbloqueado.ACAO_MANUTENCAO_EXPIRADA");
                sendBroadcast(intent);
            }
        }
    }

    private void verificarConfiguracoesBloqueio() {
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        if (pref.getBoolean("modoManutencaoAtivo", false)) {
            return; // Em modo manutenção o acesso é livre
        }

        // Permite acesso temporário se estiver no fluxo de troca de Home
        if (pref.getBoolean("isChangingHome", false)) {
            return;
        }

        // Verifica se o desbloqueio temporário por senha expirou (limite de 30 segundos)
        if (settingsUnlocked && (System.currentTimeMillis() - settingsUnlockedTime < 30 * 1000)) {
            return; // Permite acesso temporário se desbloqueado recentemente
        } else {
            settingsUnlocked = false; // Expira a liberação temporária
        }

        String foregroundPkg = getForegroundPackage();
        if (foregroundPkg == null) return;

        // Se Settings ou Play Store chegarem ao foreground em modo bloqueio,
        // lança a SettingsPasswordActivity para solicitar senha.
        if ("com.android.settings".equalsIgnoreCase(foregroundPkg)
                || "com.android.vending".equalsIgnoreCase(foregroundPkg)) {
            Log.d(TAG, "Bloqueio ativo: " + foregroundPkg + " em foreground. Iniciando SettingsPasswordActivity.");
            Intent bloqueioIntent = new Intent(this, SettingsPasswordActivity.class);
            bloqueioIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            );
            startActivity(bloqueioIntent);
        }
    }

    /**
     * Detecta o app atualmente em foreground usando UsageEvents (eventos discretos em tempo real).
     * Consulta eventos ACTIVITY_RESUMED nos últimos 2 minutos e retorna o pacote do
     * último evento encontrado — que corresponde ao app efetivamente ativo na tela agora.
     *
     * Vantagens sobre queryUsageStats(INTERVAL_DAILY):
     * - ACTIVITY_RESUMED é disparado no instante exato em que o app vai para foreground
     * - Não depende de lastTimeUsed agregado, que pode apontar para apps não-ativos
     * - A janela de 2 min cobre atrasos de boot sem retornar apps de sessões anteriores
     */
    private String getForegroundPackage() {
        UsageStatsManager usm = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usm == null) return null;

        long now = System.currentTimeMillis();
        // Janela de 2 minutos: cobre atrasos pós-boot, mas não retorna eventos de sessões antigas
        UsageEvents events = usm.queryEvents(now - 2 * 60 * 1000, now);

        String lastForeground = null;
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            // ACTIVITY_RESUMED (API 29+) é o evento correto para Android Q e superior.
            // MOVE_TO_FOREGROUND (valor 1, deprecated mas ainda gerado) cobre Android 9 e anteriores.
            if (type == UsageEvents.Event.ACTIVITY_RESUMED
                    || type == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                lastForeground = event.getPackageName();
            }
        }
        return lastForeground;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Canal de Monitoramento",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }
}
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
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

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

    @Override
    public void onCreate() {
        super.onCreate();
        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MeuAdminReceiver.class);
        
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
            
            if (whitelist.contains(pName)) continue;

            if (launcherPackages.contains(pName)) continue;

            if (pName.equals("com.android.settings") || pName.equals("com.android.vending")) {
                continue;
            }

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
        boolean modoManutencaoAtivo = pref.getBoolean("modoManutencaoAtivo", false);
        if (modoManutencaoAtivo) {
            return; // Se estiver em modo manutenção, o acesso é livre
        }

        String foregroundPkg = getForegroundPackage();
        if (foregroundPkg == null) {
            return;
        }

        // Se o usuário estiver nas configurações do sistema ou na Google Play Store
        if ("com.android.settings".equalsIgnoreCase(foregroundPkg) || "com.android.vending".equalsIgnoreCase(foregroundPkg)) {
            // E as configurações não estiverem desbloqueadas ou o tempo expirou (limite de 5 min)
            if (!settingsUnlocked || (System.currentTimeMillis() - settingsUnlockedTime > 5 * 60 * 1000)) {
                settingsUnlocked = false; // Garante reset
                
                // Abre a tela de senha administrativa do coletor
                Intent lockIntent = new Intent(this, SettingsPasswordActivity.class);
                lockIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(lockIntent);
                Log.d(TAG, "Configurações/PlayStore acessadas. Exibindo tela de bloqueio por senha.");
            }
        } else if (!getPackageName().equalsIgnoreCase(foregroundPkg)) {
            // Se o usuário saiu das configurações e PlayStore E não está na nossa tela de senha, re-bloqueia
            settingsUnlocked = false;
        }
    }

    private String getForegroundPackage() {
        String foregroundProcess = null;
        UsageStatsManager mUsageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (mUsageStatsManager == null) {
            return null;
        }
        long time = System.currentTimeMillis();
        List<UsageStats> stats = mUsageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 10, time);
        if (stats != null) {
            SortedMap<Long, UsageStats> mySortedMap = new TreeMap<>();
            for (UsageStats usageStats : stats) {
                mySortedMap.put(usageStats.getLastTimeUsed(), usageStats);
            }
            if (!mySortedMap.isEmpty()) {
                foregroundProcess = mySortedMap.get(mySortedMap.lastKey()).getPackageName();
            }
        }
        return foregroundProcess;
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
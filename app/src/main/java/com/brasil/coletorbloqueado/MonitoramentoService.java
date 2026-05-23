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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MonitoramentoService extends Service {
    private static final String TAG = "MonitoramentoService";
    private static final String CHANNEL_ID = "MonitoramentoServiceChannel";
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable verificadorRunnable = new Runnable() {
        @Override
        public void run() {
            verificarTimerManutencao();
            handler.postDelayed(this, 5000); // Executa a cada 5 segundos
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
        String defaultLauncher = getDefaultLauncherPackage();

        for (PackageInfo pkg : packages) {
            String pName = pkg.packageName;
            if (pName.equals(getPackageName())) continue;
            
            if (whitelist.contains(pName)) continue;

            if (defaultLauncher != null && pName.equals(defaultLauncher)) continue;

            if (pName.equals("com.android.settings") && pref.getBoolean("isChangingHome", false)) {
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

    private String getDefaultLauncherPackage() {
        PackageManager pm = getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo resolveInfo = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        if (resolveInfo != null && resolveInfo.activityInfo != null) {
            return resolveInfo.activityInfo.packageName;
        }
        return null;
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
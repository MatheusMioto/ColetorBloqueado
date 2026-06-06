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

/**
 * Foreground Service responsável por realizar o monitoramento contínuo dos aplicativos executados
 * em foreground e impor políticas de restrição de acesso e bloqueio no coletor.
 *
 * <p>O serviço executa em segundo plano com uma notificação persistente (Foreground Service)
 * e roda um loop periódico a cada 500ms para verificar qual aplicativo está ativo na tela.
 * Se o aplicativo em foreground for bloqueado (não estiver na whitelist e não for um app
 * do sistema permitido), o serviço inicia a {@link SettingsPasswordActivity} para cobrir
 * a tela e solicitar a senha administrativa.</p>
 *
 * <p>Este serviço também gerencia o temporizador do modo de manutenção, cancelando o modo
 * automaticamente ao expirar o tempo definido.</p>
 *
 * <p><b>Privilégios requeridos:</b>
 * Para funcionar corretamente, o app precisa estar definido como Device Owner e possuir a permissão
 * {@code android.permission.PACKAGE_USAGE_STATS} concedida.</p>
 */
public class MonitoramentoService extends Service {
    private static final String TAG = "MonitoramentoService";
    private static final String CHANNEL_ID = "MonitoramentoServiceChannel";
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;

    /**
     * Map estático contendo os pacotes que foram temporariamente liberados via inserção de senha correta.
     * <p>A chave é o nome do pacote e o valor é o timestamp (System.currentTimeMillis()) em que foi desbloqueado.</p>
     * <p>Esses pacotes permanecem desbloqueados por uma janela de 30 segundos.</p>
     */
    public static final java.util.Map<String, Long> unlockedPackages = new java.util.concurrent.ConcurrentHashMap<>();

    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Runnable executado periodicamente a cada 500ms para gerenciar o timer de manutenção
     * e validar se o aplicativo em foreground deve ser bloqueado.
     */
    private final Runnable verificadorRunnable = new Runnable() {
        @Override
        public void run() {
            verificarTimerManutencao();
            verificarConfiguracoesBloqueio();
            handler.postDelayed(this, 500); // Executa a cada 500ms
        }
    };

    /**
     * BroadcastReceiver registrado dinamicamente para escutar o desligamento do sistema.
     * <p>Como fail-safe, quando o dispositivo é desligado, todos os aplicativos fora da whitelist
     * são suspensos imediatamente para evitar que iniciem desbloqueados no próximo boot antes
     * que este serviço consiga inicializar.</p>
     */
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
                        setAppsSuspended(true);
                        Log.d(TAG, "Todos os aplicativos bloqueados suspensos no desligamento.");
                    } catch (Exception e) {
                        Log.e(TAG, "Erro ao suspender aplicativos no desligamento dinâmico", e);
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
        
        // Ao iniciar o serviço, remove a suspensão de todos os apps e exibe a Play Store
        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            try {
                setAppsSuspended(false);
                Log.d(TAG, "Serviço iniciado. Todos os aplicativos removidos de suspensão.");
            } catch (Exception e) {
                Log.e(TAG, "Erro ao desuspender aplicativos ao iniciar serviço", e);
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

    /**
     * Aplica o bloqueio silencioso corporativo via restrições do DevicePolicyManager (DPM).
     * Desabilita a barra de status e bloqueia instalação e desinstalação de aplicativos.
     */
    private void aplicarBloqueioSilencioso() {
        if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            try {
                dpm.setStatusBarDisabled(adminComponent, true);
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS);
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
                setAppsSuspended(false); // Mantém desuspendido para permitir a interceptação por senha
            } catch (Exception e) {
                Log.e(TAG, "Erro ao aplicar bloqueio silencioso corporativo", e);
            }
        }
    }

    /**
     * Remove o bloqueio de manutenção no dispositivo.
     * Reabilita a barra de status e remove restrições de instalação/desinstalação de apps.
     */
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

    /**
     * Altera o estado de suspensão dos aplicativos que não estão na whitelist.
     * <p>Caso {@code suspended} seja true, os aplicativos fora da whitelist são marcados como suspensos,
     * impedindo sua execução pelo usuário e desbotando seu ícone no launcher. A Play Store também é ocultada.</p>
     *
     * @param suspended true para suspender e ocultar os aplicativos não autorizados, false para liberá-los.
     */
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
            
            // Play Store é tratada via ocultação (setApplicationHidden) pois a suspensão falha nela
            if ("com.android.vending".equals(pName)) {
                continue;
            }

            if (whitelist.contains(pName)) continue;

            if (launcherPackages.contains(pName) && !pName.equals("com.android.settings")) continue;

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

        try {
            dpm.setApplicationHidden(adminComponent, "com.android.vending", suspended);
        } catch (Exception e) {
            Log.e(TAG, "Erro ao ocultar/exibir Play Store", e);
        }
    }

    /**
     * Obtém o conjunto de pacotes de launchers instalados no dispositivo.
     *
     * @return Conjunto contendo os pacotes que respondem à intent de Home.
     */
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

        // Fail-safe: suspende todos os aplicativos se o serviço for destruído (e não em manutenção)
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        boolean modoManutencaoAtivo = pref.getBoolean("modoManutencaoAtivo", false);

        if (!modoManutencaoAtivo && dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
            try {
                setAppsSuspended(true);
                Log.d(TAG, "Serviço encerrado. Todos os aplicativos bloqueados suspensos.");
            } catch (Exception e) {
                Log.e(TAG, "Erro ao suspender aplicativos no onDestroy", e);
            }
        }
    }

    /**
     * Verifica se o pacote fornecido deve ser bloqueado com base nas regras de whitelist e exceções do sistema.
     *
     * @param packageName Nome do pacote a ser verificado.
     * @return true se o aplicativo for bloqueado, false se for permitido.
     */
    private boolean isPackageBlocked(String packageName) {
        if (packageName == null) return false;
        if (packageName.equals(getPackageName())) return false; // Nosso app
        
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        Set<String> whitelist = pref.getStringSet("whitelist", new HashSet<>());
        if (whitelist.contains(packageName)) return false;

        Set<String> launcherPackages = getLauncherPackages();
        if (launcherPackages.contains(packageName) && !packageName.equals("com.android.settings")) return false;

        // Apps de sistema essenciais
        if (packageName.contains("android.overlay") || packageName.equals("android") || 
            packageName.contains("com.android.systemui")) {
            return false;
        }
        
        return true;
    }

    /**
     * Valida o temporizador de expiração do Modo Manutenção.
     * <p>Caso a expiração esteja configurada e o horário atual seja superior ao tempo limite,
     * finaliza o modo manutenção, aplica as restrições novamente e dispara o broadcast de expiração.</p>
     */
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

    /**
     * Analisa o estado atual de bloqueio do coletor.
     * <p>Se o coletor não estiver em modo manutenção ou trocando de launcher, busca qual pacote
     * está atualmente em foreground. Se este pacote estiver bloqueado e não estiver na janela de 30s
     * de liberação temporária, inicia a {@link SettingsPasswordActivity} para interceptá-lo.</p>
     */
    private void verificarConfiguracoesBloqueio() {
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        if (pref.getBoolean("modoManutencaoAtivo", false)) {
            return; // Em modo manutenção o acesso é livre
        }

        // Permite acesso temporário se estiver no fluxo de troca de Home
        if (pref.getBoolean("isChangingHome", false)) {
            return;
        }

        String foregroundPkg = getForegroundPackage();
        if (foregroundPkg == null) return;

        // Se o pacote atual for o desbloqueado temporariamente, e ainda estiver na janela de 30s, permite acesso
        Long unlockedTime = unlockedPackages.get(foregroundPkg);
        if (unlockedTime != null && (System.currentTimeMillis() - unlockedTime < 30 * 1000)) {
            return; // Permite acesso temporário a este app específico
        }

        // Se o aplicativo em foreground for bloqueado, solicita senha
        if (isPackageBlocked(foregroundPkg)) {
            Log.d(TAG, "Bloqueio ativo: " + foregroundPkg + " em foreground. Iniciando SettingsPasswordActivity.");
            Intent bloqueioIntent = new Intent(this, SettingsPasswordActivity.class);
            bloqueioIntent.putExtra("target_package", foregroundPkg);
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
     * <p><b>Vantagens sobre queryUsageStats(INTERVAL_DAILY):</b></p>
     * <ul>
     *   <li>ACTIVITY_RESUMED é disparado no instante exato em que o app vai para foreground</li>
     *   <li>Não depende de lastTimeUsed agregado, que pode apontar para apps não-ativos</li>
     *   <li>A janela de 2 min cobre atrasos de boot sem retornar apps de sessões anteriores</li>
     * </ul>
     *
     * @return O nome do pacote do aplicativo atualmente em foreground, ou null se não detectado.
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

    /**
     * Cria o canal de notificação exigido pelo Android 8.0+ para rodar o serviço em foreground.
     */
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
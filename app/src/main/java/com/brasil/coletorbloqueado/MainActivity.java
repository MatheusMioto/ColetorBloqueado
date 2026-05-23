package com.brasil.coletorbloqueado;

import android.app.AlertDialog;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.UserManager;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import java.security.MessageDigest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private final String SENHA_MESTRE_HASH = "aa749413036a2a5395cb4392560efb7657382e6acd06fdc1857dd7c3443a8fa3";
    private boolean modoManutencaoAtivo = false;
    public static boolean isChangingHome = false;

    private LinearLayout layoutSenha, layoutWhitelist, layoutTimer;
    private EditText etSenha;
    private Button btnDesbloquear, btnEncerrar, btnGerenciarWhitelist, btnAlterarSenha, btnConfigurarTimer;
    private TextView tvLogo;

    private TextView tvTimerRestante;
    private final android.os.Handler timerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            atualizarTimerVisual();
            timerHandler.postDelayed(this, 1000);
        }
    };

    private final android.content.BroadcastReceiver manutencaoExpiradaReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("com.brasil.coletorbloqueado.ACAO_MANUTENCAO_EXPIRADA".equals(intent.getAction())) {
                Toast.makeText(MainActivity.this, "Tempo de manutenção esgotado!", Toast.LENGTH_SHORT).show();
                encerrarManutencao();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 1. Carrega o estado salvo imediatamente
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        modoManutencaoAtivo = pref.getBoolean("modoManutencaoAtivo", false);

        // 2. CORREÇÃO INVISIBILIDADE: Se NÃO estiver em manutenção, minimiza o app instantaneamente
        // Isso evita que a interface apareça durante o boot do dispositivo
        if (!modoManutencaoAtivo) {
            moveTaskToBack(true);
        }

        setContentView(R.layout.activity_main);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MeuAdminReceiver.class);

        tvLogo = findViewById(R.id.tvLogo);
        layoutSenha = findViewById(R.id.layoutSenha);
        layoutWhitelist = findViewById(R.id.layoutWhitelist);
        etSenha = findViewById(R.id.etSenha);
        btnDesbloquear = findViewById(R.id.btnDesbloquear);
        btnEncerrar = findViewById(R.id.btnEncerrar);
        btnGerenciarWhitelist = findViewById(R.id.btnGerenciarWhitelist);
        btnAlterarSenha = findViewById(R.id.btnAlterarSenha);


        layoutTimer = findViewById(R.id.layoutTimer);
        btnConfigurarTimer = findViewById(R.id.btnConfigurarTimer);
        tvTimerRestante = findViewById(R.id.tvTimerRestante);

        layoutSenha.setVisibility(View.VISIBLE);
        if (modoManutencaoAtivo) {
            btnDesbloquear.setVisibility(View.GONE);
            btnEncerrar.setVisibility(View.VISIBLE);
            layoutWhitelist.setVisibility(View.VISIBLE);
            layoutTimer.setVisibility(View.VISIBLE);
            btnAlterarSenha.setVisibility(View.GONE);
            etSenha.setVisibility(View.GONE);
            tvLogo.setText("Modo Manutenção");
            configurarUITimerEIniciar();
        } else {
            btnAlterarSenha.setVisibility(View.VISIBLE);
            etSenha.setVisibility(View.VISIBLE);
            tvLogo.setText("COLETOR BLOQUEADO");
            layoutWhitelist.setVisibility(View.GONE);
            layoutTimer.setVisibility(View.GONE);
            stopTimerUpdates();
        }

        btnDesbloquear.setOnClickListener(v -> verificarSenhaManutencao(etSenha.getText().toString()));
        btnEncerrar.setOnClickListener(v -> encerrarManutencao());

        btnGerenciarWhitelist.setOnClickListener(v -> mostrarDialogoListaApps());
        btnAlterarSenha.setOnClickListener(v -> mostrarDialogoAlterarSenha());



        // Inicia o serviço de background
        Intent serviceIntent = new Intent(this, MonitoramentoService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(manutencaoExpiradaReceiver, new android.content.IntentFilter("com.brasil.coletorbloqueado.ACAO_MANUTENCAO_EXPIRADA"), Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(manutencaoExpiradaReceiver, new android.content.IntentFilter("com.brasil.coletorbloqueado.ACAO_MANUTENCAO_EXPIRADA"));
        }

        aplicarTravasDoSistema();
    }

    private void mostrarDialogoListaApps() {
        PackageManager pm = getPackageManager();

        // Obter todos os pacotes com intent de lançamento para filtrar serviços/background apps
        Intent mainIntent = new Intent(Intent.ACTION_MAIN, null);
        mainIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> launchableActivities = pm.queryIntentActivities(mainIntent, 0);
        Set<String> launchablePackages = new HashSet<>();
        for (ResolveInfo ri : launchableActivities) {
            launchablePackages.add(ri.activityInfo.packageName);
        }

        SharedPreferences preferences = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        Set<String> whitelistSet = preferences.getStringSet("whitelist", new HashSet<>());

        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        final List<AppEntry> allAppsData = new ArrayList<>();
        String defaultLauncher = getDefaultLauncherPackage();
        for (ApplicationInfo app : apps) {
            // Oculta a Play Store para que nao seja exibida nas listas de whitelist
            if (app.packageName.equals("com.android.vending")) continue;

            // Oculta a Home do dispositivo para que o usuário não a remova/bloqueie
            if (defaultLauncher != null && app.packageName.equals(defaultLauncher)) continue;

            // Filtra: mostra apenas se for app iniciável (launcher) OU se já estiver na whitelist (por segurança)
            if (!launchablePackages.contains(app.packageName) && !whitelistSet.contains(app.packageName)) {
                continue;
            }

            allAppsData.add(new AppEntry(
                    app.loadLabel(pm).toString(),
                    app.packageName,
                    app.loadIcon(pm)
            ));
        }

        Collections.sort(allAppsData, (a, b) -> a.name.compareToIgnoreCase(b.name));

        final List<AppEntry> filteredApps = new ArrayList<>();

        View dialogView = getLayoutInflater().inflate(R.layout.dialog_select_app, null);
        EditText etSearch = dialogView.findViewById(R.id.etSearchApp);
        ListView listView = dialogView.findViewById(R.id.lvApps);
        Button btnClearAll = dialogView.findViewById(R.id.btnDialogClearAll);
        Button btnClose = dialogView.findViewById(R.id.btnDialogClose);

        TextView tabBloqueados = dialogView.findViewById(R.id.tabBloqueados);
        TextView tabLiberados = dialogView.findViewById(R.id.tabLiberados);

        btnClearAll.setVisibility(View.GONE);

        final AppAdapter adapter = new AppAdapter(this, filteredApps);
        listView.setAdapter(adapter);

        final boolean[] mostrandoBloqueados = {true};

        final Runnable atualizarTabsVisuais = new Runnable() {
            @Override
            public void run() {
                if (mostrandoBloqueados[0]) {
                    tabBloqueados.setBackgroundColor(Color.parseColor("#2196F3"));
                    tabBloqueados.setTextColor(Color.parseColor("#FFFFFF"));
                    tabLiberados.setBackgroundColor(Color.TRANSPARENT);
                    tabLiberados.setTextColor(Color.parseColor("#777777"));
                    btnClearAll.setVisibility(View.GONE);
                } else {
                    tabLiberados.setBackgroundColor(Color.parseColor("#2196F3"));
                    tabLiberados.setTextColor(Color.parseColor("#FFFFFF"));
                    tabBloqueados.setBackgroundColor(Color.TRANSPARENT);
                    tabBloqueados.setTextColor(Color.parseColor("#777777"));
                    btnClearAll.setVisibility(View.VISIBLE);
                }
            }
        };

        final Runnable recriarLista = new Runnable() {
            @Override
            public void run() {
                SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
                Set<String> whitelistSet = pref.getStringSet("whitelist", new HashSet<>());
                String query = etSearch.getText().toString().toLowerCase().trim();

                filteredApps.clear();
                for (AppEntry app : allAppsData) {
                    if (!query.isEmpty() && !app.name.toLowerCase().contains(query) && !app.packageName.toLowerCase().contains(query)) {
                        continue;
                    }

                    boolean isWhitelisted = whitelistSet.contains(app.packageName);
                    if (mostrandoBloqueados[0]) {
                        if (!isWhitelisted) {
                            filteredApps.add(app);
                        }
                    } else {
                        if (isWhitelisted) {
                            filteredApps.add(app);
                        }
                    }
                }
                adapter.notifyDataSetChanged();
            }
        };

        tabBloqueados.setOnClickListener(v -> {
            if (!mostrandoBloqueados[0]) {
                mostrandoBloqueados[0] = true;
                atualizarTabsVisuais.run();
                recriarLista.run();
            }
        });

        tabLiberados.setOnClickListener(v -> {
            if (mostrandoBloqueados[0]) {
                mostrandoBloqueados[0] = false;
                atualizarTabsVisuais.run();
                recriarLista.run();
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("Gerenciar Whitelist")
                .setView(dialogView)
                .create();

        btnClearAll.setOnClickListener(v -> {
            limparWhitelist();
            recriarLista.run();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                recriarLista.run();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry selected = filteredApps.get(position);
            if (mostrandoBloqueados[0]) {
                adicionarAppWhitelist(selected.packageName);
            } else {
                removerAppWhitelist(selected.packageName);
            }
            recriarLista.run();
        });

        atualizarTabsVisuais.run();
        recriarLista.run();
        dialog.show();
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

    private void adicionarAppWhitelist(String packageName) {
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        Set<String> whitelist = new HashSet<>(pref.getStringSet("whitelist", new HashSet<>()));
        whitelist.add(packageName);
        pref.edit().putStringSet("whitelist", whitelist).apply();
        Toast.makeText(this, "App permitido!", Toast.LENGTH_SHORT).show();

        if (!modoManutencaoAtivo) {
            setAppsSuspended(true);
        }
    }

    private void removerAppWhitelist(String packageName) {
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        Set<String> whitelist = new HashSet<>(pref.getStringSet("whitelist", new HashSet<>()));
        whitelist.remove(packageName);
        pref.edit().putStringSet("whitelist", whitelist).apply();
        Toast.makeText(this, "App removido!", Toast.LENGTH_SHORT).show();

        if (!modoManutencaoAtivo) {
            setAppsSuspended(true);
        }
    }

    private void limparWhitelist() {
        getSharedPreferences("Configuracoes", MODE_PRIVATE).edit().remove("whitelist").apply();
        Toast.makeText(this, "Whitelist limpa!", Toast.LENGTH_SHORT).show();
        if (!modoManutencaoAtivo) {
            setAppsSuspended(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        if (pref.getBoolean("isChangingHome", false)) {
            pref.edit().putBoolean("isChangingHome", false).apply();
            isChangingHome = false;
            if (!modoManutencaoAtivo) {
                setAppsSuspended(true);
            }
        }

        aplicarTravasDoSistema();
        if (modoManutencaoAtivo) {
            configurarUITimerEIniciar();
        } else {
            stopTimerUpdates();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopTimerUpdates();
    }

    private void aplicarTravasDoSistema() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                if (!modoManutencaoAtivo) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                } else {
                    getWindow().setDecorFitsSystemWindows(true);
                    controller.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                }
            }
        }

        try {
            if (dpm != null && dpm.isDeviceOwnerApp(getPackageName())) {
                if (!modoManutencaoAtivo) {
                    dpm.setStatusBarDisabled(adminComponent, true);
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS);
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
                    setAppsSuspended(true);
                } else {
                    dpm.setStatusBarDisabled(adminComponent, false);
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS);
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
                    setAppsSuspended(false);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Erro ao aplicar travas do sistema como Device Owner", e);
        }
    }

    private void setAppsSuspended(boolean suspended) {
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
            if (pName.contains("android.overlay") || pName.equals("android") || pName.contains("com.android.systemui")) {
                continue;
            }
            if (pName.equals("com.android.settings") && pref.getBoolean("isChangingHome", false)) {
                continue;
            }
            packagesToSuspend.add(pName);
        }

        if (!packagesToSuspend.isEmpty()) {
            try {
                dpm.setPackagesSuspended(adminComponent, packagesToSuspend.toArray(new String[0]), suspended);
            } catch (Exception e) {
                Log.e(TAG, "Erro ao alterar estado de suspensao de pacotes", e);
            }
        }
    }

    private String calcularSHA256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes("UTF-8"));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            Log.e(TAG, "Erro ao calcular hash SHA-256 da senha", e);
            return "";
        }
    }

    public void verificarSenhaManutencao(String senhaDigitada) {
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        String senhaSalvaHash = pref.getString("senha_mestre_hash", SENHA_MESTRE_HASH);
        if (calcularSHA256(senhaDigitada).equals(senhaSalvaHash)) {
            modoManutencaoAtivo = true;
            Set<String> whitelist = new HashSet<>(pref.getStringSet("whitelist", new HashSet<>()));
            whitelist.add("com.android.vending");
            
            // Defina o tempo de expiração inicial (5 minutos por padrão)
            long expTime = System.currentTimeMillis() + (5 * 60 * 1000);
            
            pref.edit()
                    .putBoolean("modoManutencaoAtivo", true)
                    .putStringSet("whitelist", whitelist)
                    .putLong("manutencao_expiracao_timestamp", expTime)
                    .putInt("manutencao_timer_opcao_index", 0)
                    .apply();

            aplicarTravasDoSistema();
            configurarUITimerEIniciar();

            btnDesbloquear.setVisibility(View.GONE);
            btnEncerrar.setVisibility(View.VISIBLE);
            layoutWhitelist.setVisibility(View.VISIBLE);
            layoutTimer.setVisibility(View.VISIBLE);
            btnAlterarSenha.setVisibility(View.GONE);
            etSenha.setVisibility(View.GONE);
            etSenha.setText("");
            tvLogo.setText("Modo Manutenção");
            Toast.makeText(this, "MODO MANUTENÇÃO ATIVADO", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Senha Incorreta!", Toast.LENGTH_SHORT).show();
        }
    }

    private void mostrarDialogoAlterarSenha() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_alterar_senha, null);
        EditText etSenhaAntiga = dialogView.findViewById(R.id.etSenhaAntiga);
        EditText etSenhaNova = dialogView.findViewById(R.id.etSenhaNova);
        EditText etSenhaNovaConfirmacao = dialogView.findViewById(R.id.etSenhaNovaConfirmacao);
        Button btnCancel = dialogView.findViewById(R.id.btnDialogCancelAlterar);
        Button btnConfirm = dialogView.findViewById(R.id.btnDialogConfirmAlterar);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .create();

        btnCancel.setOnClickListener(v -> dialog.dismiss());

        btnConfirm.setOnClickListener(v -> {
            String antiga = etSenhaAntiga.getText().toString().trim();
            String nova = etSenhaNova.getText().toString().trim();
            String novaConf = etSenhaNovaConfirmacao.getText().toString().trim();

            if (antiga.isEmpty() || nova.isEmpty() || novaConf.isEmpty()) {
                Toast.makeText(this, "Preencha todos os campos!", Toast.LENGTH_SHORT).show();
                return;
            }

            SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
            String senhaSalvaHash = pref.getString("senha_mestre_hash", SENHA_MESTRE_HASH);

            if (!calcularSHA256(antiga).equals(senhaSalvaHash)) {
                Toast.makeText(this, "Senha antiga incorreta!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!nova.equals(novaConf)) {
                Toast.makeText(this, "As novas senhas nao coincidem!", Toast.LENGTH_SHORT).show();
                return;
            }

            String novaHash = calcularSHA256(nova);
            pref.edit().putString("senha_mestre_hash", novaHash).apply();

            Toast.makeText(this, "Senha administrativa alterada com sucesso!", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }

    public void encerrarManutencao() {
        modoManutencaoAtivo = false;
        stopTimerUpdates();
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        Set<String> whitelist = new HashSet<>(pref.getStringSet("whitelist", new HashSet<>()));
        whitelist.remove("com.android.vending");
        pref.edit()
                .putBoolean("modoManutencaoAtivo", false)
                .putStringSet("whitelist", whitelist)
                .remove("manutencao_expiracao_timestamp")
                .remove("manutencao_timer_opcao_index")
                .apply();

        btnDesbloquear.setVisibility(View.VISIBLE);
        btnEncerrar.setVisibility(View.GONE);
        layoutWhitelist.setVisibility(View.GONE);
        layoutTimer.setVisibility(View.GONE);
        btnAlterarSenha.setVisibility(View.VISIBLE);
        etSenha.setVisibility(View.VISIBLE);
        etSenha.setText("");
        tvLogo.setText("COLETOR BLOQUEADO");

        aplicarTravasDoSistema();
        Toast.makeText(this, "Coletor Trancado!", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    private void configurarUITimerEIniciar() {
        btnConfigurarTimer.setOnClickListener(v -> {
            SharedPreferences preferences = getSharedPreferences("Configuracoes", MODE_PRIVATE);
            int checkedItem = preferences.getInt("manutencao_timer_opcao_index", 0);

            String[] items = {"5 minutos", "15 minutos", "30 minutos", "Desativar temporizador (sem limite)"};

            new AlertDialog.Builder(MainActivity.this)
                    .setTitle("Tempo de Manutenção")
                    .setSingleChoiceItems(items, checkedItem, (dialog, which) -> {
                        long now = System.currentTimeMillis();
                        long newExpTime;
                        if (which == 0) {
                            newExpTime = now + (5 * 60 * 1000);
                        } else if (which == 1) {
                            newExpTime = now + (15 * 60 * 1000);
                        } else if (which == 2) {
                            newExpTime = now + (30 * 60 * 1000);
                        } else {
                            newExpTime = 0;
                        }

                        preferences.edit()
                                .putLong("manutencao_expiracao_timestamp", newExpTime)
                                .putInt("manutencao_timer_opcao_index", which)
                                .apply();

                        if (newExpTime == 0) {
                            tvTimerRestante.setText("Temporizador Desativado (Sem Limite)");
                            tvTimerRestante.setTextColor(android.graphics.Color.parseColor("#008000"));
                            stopTimerUpdates();
                        } else {
                            tvTimerRestante.setTextColor(android.graphics.Color.parseColor("#FF0000"));
                            startTimerUpdates();
                        }

                        dialog.dismiss();
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        });

        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        long expTime = pref.getLong("manutencao_expiracao_timestamp", 0);
        if (expTime == 0) {
            tvTimerRestante.setText("Temporizador Desativado (Sem Limite)");
            tvTimerRestante.setTextColor(android.graphics.Color.parseColor("#008000"));
            stopTimerUpdates();
        } else {
            tvTimerRestante.setTextColor(android.graphics.Color.parseColor("#FF0000"));
            startTimerUpdates();
        }
    }

    private void startTimerUpdates() {
        stopTimerUpdates();
        timerHandler.post(timerRunnable);
    }

    private void stopTimerUpdates() {
        timerHandler.removeCallbacks(timerRunnable);
    }

    private void atualizarTimerVisual() {
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        long expTime = pref.getLong("manutencao_expiracao_timestamp", 0);
        if (expTime == 0) {
            tvTimerRestante.setText("Temporizador Desativado (Sem Limite)");
            tvTimerRestante.setTextColor(android.graphics.Color.parseColor("#008000"));
            stopTimerUpdates();
            return;
        }

        long diff = expTime - System.currentTimeMillis();
        if (diff <= 0) {
            tvTimerRestante.setText("Tempo esgotado!");
            stopTimerUpdates();
            encerrarManutencao();
        } else {
            long totalSegundos = diff / 1000;
            long minutos = totalSegundos / 60;
            long segundos = totalSegundos % 60;
            tvTimerRestante.setText(String.format("Tempo restante: %02d:%02d", minutos, segundos));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(manutencaoExpiradaReceiver);
        } catch (Exception e) {
            // Ignorar se não registrado
        }
        stopTimerUpdates();
    }

    private static class AppEntry {
        String name;
        String packageName;
        Drawable icon;

        AppEntry(String name, String packageName, Drawable icon) {
            this.name = name;
            this.packageName = packageName;
            this.icon = icon;
        }
    }

    private class AppAdapter extends BaseAdapter {
        private Context context;
        private List<AppEntry> apps;

        AppAdapter(Context context, List<AppEntry> apps) {
            this.context = context;
            this.apps = apps;
        }

        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return apps.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_app, parent, false);
            }
            AppEntry app = apps.get(position);
            ImageView ivIcon = convertView.findViewById(R.id.ivAppIcon);
            TextView tvName = convertView.findViewById(R.id.tvAppName);
            TextView tvPackage = convertView.findViewById(R.id.tvPackageName);

            ivIcon.setImageDrawable(app.icon);
            tvName.setText(app.name);
            tvPackage.setText(app.packageName);
            return convertView;
        }
    }
}

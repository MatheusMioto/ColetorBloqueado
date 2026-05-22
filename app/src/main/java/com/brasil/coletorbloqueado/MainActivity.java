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
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private final String SENHA_MESTRE = "12345";
    private boolean modoManutencaoAtivo = false;

    private LinearLayout layoutSenha, layoutWhitelist;
    private EditText etSenha;
    private Button btnDesbloquear, btnEncerrar, btnAbrirListaApps, btnVerWhitelist;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, MeuAdminReceiver.class);

        layoutSenha = findViewById(R.id.layoutSenha);
        layoutWhitelist = findViewById(R.id.layoutWhitelist);
        etSenha = findViewById(R.id.etSenha);
        btnDesbloquear = findViewById(R.id.btnDesbloquear);
        btnEncerrar = findViewById(R.id.btnEncerrar);
        btnAbrirListaApps = findViewById(R.id.btnAbrirListaApps);
        btnVerWhitelist = findViewById(R.id.btnVerWhitelist);

        // Carrega o estado salvo
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        modoManutencaoAtivo = pref.getBoolean("modoManutencaoAtivo", false);

        layoutSenha.setVisibility(View.VISIBLE);
        if (modoManutencaoAtivo) {
            btnDesbloquear.setVisibility(View.GONE);
            btnEncerrar.setVisibility(View.VISIBLE);
            layoutWhitelist.setVisibility(View.VISIBLE);
        }

        btnDesbloquear.setOnClickListener(v -> verificarSenhaManutencao(etSenha.getText().toString()));
        btnEncerrar.setOnClickListener(v -> encerrarManutencao());

        btnAbrirListaApps.setOnClickListener(v -> mostrarDialogoListaApps(false));
        btnVerWhitelist.setOnClickListener(v -> mostrarDialogoListaApps(true));

        // Inicia o serviço de background
        Intent serviceIntent = new Intent(this, MonitoramentoService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        aplicarTravasDoSistema();

        // Se o app abriu "automaticamente" e não está em manutenção, minimiza para ficar em background
        if (!modoManutencaoAtivo && isTaskRoot() && (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            moveTaskToBack(true);
        }
    }

    private void mostrarDialogoListaApps(boolean apenasWhitelist) {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        Set<String> whitelistSet = pref.getStringSet("whitelist", new HashSet<>());

        final List<AppEntry> allAppsData = new ArrayList<>();
        for (ApplicationInfo app : apps) {
            if (apenasWhitelist && !whitelistSet.contains(app.packageName)) continue;
            
            allAppsData.add(new AppEntry(
                app.loadLabel(pm).toString(),
                app.packageName,
                app.loadIcon(pm)
            ));
        }

        Collections.sort(allAppsData, (a, b) -> a.name.compareToIgnoreCase(b.name));
        final List<AppEntry> filteredApps = new ArrayList<>(allAppsData);
        
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_select_app, null);
        EditText etSearch = dialogView.findViewById(R.id.etSearchApp);
        ListView listView = dialogView.findViewById(R.id.lvApps);
        Button btnClearAll = dialogView.findViewById(R.id.btnDialogClearAll
        );
        Button btnClose = dialogView.findViewById(R.id.btnDialogClose);

        if (apenasWhitelist) {
            btnClearAll.setVisibility(View.VISIBLE);
        }

        final AppAdapter adapter = new AppAdapter(this, filteredApps);
        listView.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(apenasWhitelist ? "Remover da Whitelist" : "Adicionar à Whitelist")
                .setView(dialogView)
                .setNegativeButton("Fechar", null)
                .create();

        btnClearAll.setOnClickListener(v -> {
            limparWhitelist();
            dialog.dismiss();
        });

        btnClose.setOnClickListener(v -> dialog.dismiss());

        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().toLowerCase().trim();
                filteredApps.clear();
                for (AppEntry app : allAppsData) {
                    if (app.name.toLowerCase().contains(query) || app.packageName.toLowerCase().contains(query)) {
                        filteredApps.add(app);
                    }
                }
                adapter.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(Editable s) {}
        });

        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry selected = filteredApps.get(position);
            if (apenasWhitelist) {
                removerAppWhitelist(selected.packageName);
                allAppsData.remove(selected);
                filteredApps.remove(selected);
                adapter.notifyDataSetChanged();
                if (filteredApps.isEmpty()) {
                    Toast.makeText(MainActivity.this, "Whitelist Vazia", Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                }
            } else {
                adicionarAppWhitelist(selected.packageName);
                dialog.dismiss();
            }
        });

        dialog.show();
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
        aplicarTravasDoSistema();
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
                    dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_OUTGOING_CALLS);
                    setAppsSuspended(true);
                } else {
                    dpm.setStatusBarDisabled(adminComponent, false);
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_APPS);
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
                    dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_OUTGOING_CALLS);
                    setAppsSuspended(false);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setAppsSuspended(boolean suspended) {
        if (dpm == null || !dpm.isDeviceOwnerApp(getPackageName())) return;

        PackageManager pm = getPackageManager();
        List<PackageInfo> packages = pm.getInstalledPackages(0);
        List<String> packagesToSuspend = new ArrayList<>();

        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        Set<String> whitelist = pref.getStringSet("whitelist", new HashSet<>());

        for (PackageInfo pkg : packages) {
            String pName = pkg.packageName;
            if (pName.equals(getPackageName())) continue;
            
            // Se está na whitelist, garante que não está oculto nem suspenso
            if (whitelist.contains(pName)) {
                try {
                    dpm.setApplicationHidden(adminComponent, pName, false);
                    dpm.setPackagesSuspended(adminComponent, new String[]{pName}, false);
                } catch (Exception e) {}
                continue;
            }

            // Exceções vitais
            if (pName.contains("android.overlay") || pName.equals("android") || 
                pName.contains("com.android.systemui") || pName.equals("com.android.settings")) {
                continue;
            }

            // Alvos Críticos: Play Store e Phone
            boolean isHardTarget = pName.equals("com.android.vending") || 
                                   pName.toLowerCase().contains("phone") || 
                                   pName.toLowerCase().contains("dialer") ||
                                   pName.equals("com.google.android.gms");

            if (suspended) {
                if (isHardTarget) {
                    try { dpm.setApplicationHidden(adminComponent, pName, true); } catch (Exception e) {}
                }
                packagesToSuspend.add(pName);
            } else {
                try { dpm.setApplicationHidden(adminComponent, pName, false); } catch (Exception e) {}
                packagesToSuspend.add(pName);
            }
        }

        if (!packagesToSuspend.isEmpty()) {
            try {
                dpm.setPackagesSuspended(adminComponent, packagesToSuspend.toArray(new String[0]), suspended);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void verificarSenhaManutencao(String senhaDigitada) {
        if (senhaDigitada.equals(SENHA_MESTRE)) {
            modoManutencaoAtivo = true;
            getSharedPreferences("Configuracoes", MODE_PRIVATE)
                    .edit().putBoolean("modoManutencaoAtivo", true).apply();

            aplicarTravasDoSistema();

            btnDesbloquear.setVisibility(View.GONE);
            btnEncerrar.setVisibility(View.VISIBLE);
            layoutWhitelist.setVisibility(View.VISIBLE);
            etSenha.setText("");
            Toast.makeText(this, "MODO MANUTENÇÃO ATIVADO", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "Senha Incorreta!", Toast.LENGTH_SHORT).show();
        }
    }

    public void encerrarManutencao() {
        modoManutencaoAtivo = false;
        getSharedPreferences("Configuracoes", MODE_PRIVATE)
                .edit().putBoolean("modoManutencaoAtivo", false).apply();

        btnDesbloquear.setVisibility(View.VISIBLE);
        btnEncerrar.setVisibility(View.GONE);
        layoutWhitelist.setVisibility(View.GONE);
        etSenha.setText("");
        
        aplicarTravasDoSistema();
        Toast.makeText(this, "Coletor Trancado!", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
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

        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }

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

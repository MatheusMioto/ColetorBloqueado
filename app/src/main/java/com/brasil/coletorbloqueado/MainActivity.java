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

    private LinearLayout layoutSenha, layoutWhitelist;
    private EditText etSenha;
    private Button btnDesbloquear, btnEncerrar, btnAbrirListaApps, btnVerWhitelist, btnAlterarSenha;
    private TextView tvLogo;

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
        btnAbrirListaApps = findViewById(R.id.btnAbrirListaApps);
        btnVerWhitelist = findViewById(R.id.btnVerWhitelist);
        btnAlterarSenha = findViewById(R.id.btnAlterarSenha);

        layoutSenha.setVisibility(View.VISIBLE);
        if (modoManutencaoAtivo) {
            btnDesbloquear.setVisibility(View.GONE);
            btnEncerrar.setVisibility(View.VISIBLE);
            layoutWhitelist.setVisibility(View.VISIBLE);
            btnAlterarSenha.setVisibility(View.GONE);
            etSenha.setVisibility(View.GONE);
            tvLogo.setText("Modo Manutenção");
        } else {
            btnAlterarSenha.setVisibility(View.VISIBLE);
            etSenha.setVisibility(View.VISIBLE);
            tvLogo.setText("COLETOR BLOQUEADO");
        }

        btnDesbloquear.setOnClickListener(v -> verificarSenhaManutencao(etSenha.getText().toString()));
        btnEncerrar.setOnClickListener(v -> encerrarManutencao());

        btnAbrirListaApps.setOnClickListener(v -> mostrarDialogoListaApps(false));
        btnVerWhitelist.setOnClickListener(v -> mostrarDialogoListaApps(true));
        btnAlterarSenha.setOnClickListener(v -> mostrarDialogoAlterarSenha());

        // Inicia o serviço de background
        Intent serviceIntent = new Intent(this, MonitoramentoService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }

        aplicarTravasDoSistema();
    }

    private void mostrarDialogoListaApps(boolean apenasWhitelist) {
        PackageManager pm = getPackageManager();
        List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);

        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        Set<String> whitelistSet = pref.getStringSet("whitelist", new HashSet<>());

        final List<AppEntry> allAppsData = new ArrayList<>();
        for (ApplicationInfo app : apps) {
            // Oculta a Play Store para que nao seja exibida nas listas de whitelist
            if (app.packageName.equals("com.android.vending")) continue;

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
        Button btnClearAll = dialogView.findViewById(R.id.btnDialogClearAll);
        Button btnClose = dialogView.findViewById(R.id.btnDialogClose);

        if (apenasWhitelist) {
            btnClearAll.setVisibility(View.VISIBLE);
        }

        final AppAdapter adapter = new AppAdapter(this, filteredApps);
        listView.setAdapter(adapter);

        // Diálogo sem o botão nativo para não duplicar o "Fechar"
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(apenasWhitelist ? "Remover da Whitelist" : "Adicionar à Whitelist")
                .setView(dialogView)
                .create();

        btnClearAll.setOnClickListener(v -> {
            limparWhitelist();
            dialog.dismiss();
        });

        // Único botão de fechar no canto inferior direito
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
                if (filteredApps.isEmpty()) dialog.dismiss();
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

        for (PackageInfo pkg : packages) {
            if (!pkg.packageName.equals(getPackageName()) &&
                    !whitelist.contains(pkg.packageName) &&
                    !pkg.packageName.contains("android.overlay") &&
                    !pkg.packageName.equals("android") &&
                    !pkg.packageName.contains("com.android.systemui")) {
                packagesToSuspend.add(pkg.packageName);
            }
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
            pref.edit()
                    .putBoolean("modoManutencaoAtivo", true)
                    .putStringSet("whitelist", whitelist)
                    .apply();

            aplicarTravasDoSistema();

            btnDesbloquear.setVisibility(View.GONE);
            btnEncerrar.setVisibility(View.VISIBLE);
            layoutWhitelist.setVisibility(View.VISIBLE);
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
        SharedPreferences pref = getSharedPreferences("Configuracoes", MODE_PRIVATE);
        Set<String> whitelist = new HashSet<>(pref.getStringSet("whitelist", new HashSet<>()));
        whitelist.remove("com.android.vending");
        pref.edit()
                .putBoolean("modoManutencaoAtivo", false)
                .putStringSet("whitelist", whitelist)
                .apply();

        btnDesbloquear.setVisibility(View.VISIBLE);
        btnEncerrar.setVisibility(View.GONE);
        layoutWhitelist.setVisibility(View.GONE);
        btnAlterarSenha.setVisibility(View.VISIBLE);
        etSenha.setVisibility(View.VISIBLE);
        etSenha.setText("");
        tvLogo.setText("COLETOR BLOQUEADO");

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

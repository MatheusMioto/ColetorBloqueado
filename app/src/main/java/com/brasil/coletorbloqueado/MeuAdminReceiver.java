package com.brasil.coletorbloqueado;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;
import androidx.annotation.NonNull;

/**
 * Receptor de eventos do Device Admin / Device Owner do aplicativo ColetorBloqueado.
 *
 * <p>Esta classe estende {@link DeviceAdminReceiver} e atua como o ponto de entrada para
 * todas as APIs de administração de dispositivo Android (Device Policy Manager - DPM).
 * Para que o app possa utilizar as APIs de Device Owner (como suspensão de pacotes,
 * desativação da barra de status e restrições de usuário), este componente DEVE estar
 * registrado no {@code AndroidManifest.xml} com as políticas corretas declaradas em
 * {@code res/xml/device_admin_policies.xml}.</p>
 *
 * <h2>Níveis de privilégio</h2>
 * <ul>
 *   <li><b>Device Admin:</b> Ativado pelo usuário via Intent {@link android.app.admin.DevicePolicyManager#ACTION_ADD_DEVICE_ADMIN}.
 *       Permite algumas restrições básicas. Ativado no Passo 2 do {@link SetupWizardActivity}.</li>
 *   <li><b>Device Owner:</b> Configurado uma única vez via ADB:
 *       {@code adb shell dpm set-device-owner com.brasil.coletorbloqueado/.MeuAdminReceiver}.
 *       Requer que nenhuma conta de usuário esteja presente no dispositivo. Oferece controle
 *       total sobre políticas de segurança do dispositivo.</li>
 * </ul>
 *
 * <h2>Políticas DPM habilitadas (device_admin_policies.xml)</h2>
 * <ul>
 *   <li>{@code uses-policies > limit-password}: política base obrigatória</li>
 *   <li>{@code uses-policies > watch-login}: monitora tentativas de login</li>
 * </ul>
 *
 * <p><b>Nota de segurança:</b> Uma vez configurado como Device Owner em builds Release assinadas,
 * este privilégio NÃO pode ser removido via ADB. A única forma de remover é Factory Reset
 * do dispositivo, ou uma chamada programática a {@link android.app.admin.DevicePolicyManager#clearDeviceOwnerApp}
 * em uma build de Debug.</p>
 *
 * @see android.app.admin.DeviceAdminReceiver
 * @see android.app.admin.DevicePolicyManager
 * @see SetupWizardActivity
 */
public class MeuAdminReceiver extends DeviceAdminReceiver {

    /**
     * Chamado pelo sistema quando o usuário concede privilégios de Device Admin a este app.
     *
     * <p>Exibe uma confirmação visual ao usuário informando que o privilégio foi ativado com
     * sucesso. Após este callback, o app pode usar as APIs básicas de Device Admin.</p>
     *
     * <p><b>Atenção:</b> Device Admin NÃO é o mesmo que Device Owner. As APIs mais poderosas
     * (como {@code setPackagesSuspended} e {@code setStatusBarDisabled}) exigem Device Owner,
     * que é configurado separadamente via ADB.</p>
     *
     * @param context o contexto da aplicação
     * @param intent  o intent que originou esta chamada
     */
    @Override
    public void onEnabled(@NonNull Context context, @NonNull Intent intent) {
        super.onEnabled(context, intent);
        Toast.makeText(context, "Privilégio Administrativo Ativado!", Toast.LENGTH_SHORT).show();
    }
}
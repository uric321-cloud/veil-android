package app.veil.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import app.veil.android.rules.RuleStore
import app.veil.android.vpn.VeilVpnService

/**
 * Restarts protection after a reboot or an app update, if it was on before and the
 * VPN permission is still granted. (Users can also enable "Always-on VPN" for VEIL
 * in Android's VPN settings, which makes the system do this itself.)
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val store = RuleStore.get(context)
        if (!store.protectionWanted) return
        if (VpnService.prepare(context) != null) {
            VeilLog.w("Boot: protection wanted but VPN permission is missing; user must open VEIL")
            return
        }
        VeilLog.i("Boot: restarting protection ($action)")
        try {
            VeilVpnService.start(context)
        } catch (t: Throwable) {
            VeilLog.e("Boot: could not start the service", t)
        }
    }
}

package app.veil.android.admin

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.UserManager
import app.veil.android.VeilLog
import app.veil.android.rules.RuleStore
import app.veil.android.vpn.VeilVpnService
import org.json.JSONObject

/**
 * Lockdown when VEIL is the phone's Device Owner (set up with the dashboard's
 * QR code or `adb shell dpm set-device-owner`). Everything here is a fixed,
 * documented Android policy that protects the filter itself; none of it gives
 * the admin access to the phone's content. Without Device Owner every call is
 * a no-op and the phone runs in normal "paired" mode.
 */
object DeviceOwner {

    data class Policy(
        val alwaysOnVpn: Boolean = true,
        val vpnLockdown: Boolean = false,
        val disallowVpnConfig: Boolean = true,
        val disallowPrivateDnsConfig: Boolean = true,
        val disallowSafeBoot: Boolean = true,
        val disallowFactoryReset: Boolean = true,
        val disallowAddUser: Boolean = true,
        val disallowAppsControl: Boolean = false,
        val disallowUnknownSources: Boolean = false,
        val disallowDebugging: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("alwaysOnVpn", alwaysOnVpn).put("vpnLockdown", vpnLockdown)
            .put("disallowVpnConfig", disallowVpnConfig).put("disallowPrivateDnsConfig", disallowPrivateDnsConfig)
            .put("disallowSafeBoot", disallowSafeBoot).put("disallowFactoryReset", disallowFactoryReset)
            .put("disallowAddUser", disallowAddUser).put("disallowAppsControl", disallowAppsControl)
            .put("disallowUnknownSources", disallowUnknownSources).put("disallowDebugging", disallowDebugging)

        companion object {
            fun from(o: JSONObject): Policy {
                val d = Policy()
                fun b(k: String, def: Boolean) = if (o.opt(k) is Boolean) o.getBoolean(k) else def
                return Policy(
                    alwaysOnVpn = b("alwaysOnVpn", d.alwaysOnVpn),
                    vpnLockdown = b("vpnLockdown", d.vpnLockdown),
                    disallowVpnConfig = b("disallowVpnConfig", d.disallowVpnConfig),
                    disallowPrivateDnsConfig = b("disallowPrivateDnsConfig", d.disallowPrivateDnsConfig),
                    disallowSafeBoot = b("disallowSafeBoot", d.disallowSafeBoot),
                    disallowFactoryReset = b("disallowFactoryReset", d.disallowFactoryReset),
                    disallowAddUser = b("disallowAddUser", d.disallowAddUser),
                    disallowAppsControl = b("disallowAppsControl", d.disallowAppsControl),
                    disallowUnknownSources = b("disallowUnknownSources", d.disallowUnknownSources),
                    disallowDebugging = b("disallowDebugging", d.disallowDebugging)
                )
            }
        }
    }

    // Constants added after our minSdk, by value.
    private const val DISALLOW_CONFIG_PRIVATE_DNS = "disallow_config_private_dns"           // API 29
    private const val DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY = "no_install_unknown_sources_globally" // API 29

    /** Every restriction this class may set, so release() can clear exactly these. */
    private val ALL_RESTRICTIONS = listOf(
        UserManager.DISALLOW_CONFIG_VPN, DISALLOW_CONFIG_PRIVATE_DNS, UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_FACTORY_RESET, UserManager.DISALLOW_ADD_USER, UserManager.DISALLOW_APPS_CONTROL,
        UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY,
        UserManager.DISALLOW_DEBUGGING_FEATURES
    )

    fun admin(context: Context) = ComponentName(context, VeilDeviceAdmin::class.java)

    private fun dpm(context: Context) = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    fun isOwner(context: Context): Boolean = try {
        dpm(context).isDeviceOwnerApp(context.packageName)
    } catch (_: Throwable) {
        false
    }

    private fun prefs(context: Context) = context.getSharedPreferences("veil_owner", Context.MODE_PRIVATE)

    fun currentPolicy(context: Context): Policy = try {
        Policy.from(JSONObject(prefs(context).getString("policy", "{}") ?: "{}"))
    } catch (_: Throwable) {
        Policy()
    }

    /** Things that always hold while VEIL is Device Owner: can't be uninstalled, filter always on. */
    fun applyBaseline(context: Context) {
        if (!isOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        attempt("block uninstall") { dpm.setUninstallBlocked(admin, context.packageName, true) }
        if (Build.VERSION.SDK_INT >= 33) {
            attempt("grant notifications") {
                dpm.setPermissionGrantState(admin, context.packageName, "android.permission.POST_NOTIFICATIONS", DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
        }
        applyPolicy(context, currentPolicy(context))
    }

    fun applyPolicy(context: Context, p: Policy) {
        prefs(context).edit().putString("policy", p.toJson().toString()).apply()
        if (!isOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)

        attempt("always-on VPN") {
            val pkg = if (p.alwaysOnVpn) context.packageName else null
            if (Build.VERSION.SDK_INT >= 29) {
                // VEIL's own traffic (check-ins) bypasses its tunnel, so it must be allowed through lockdown.
                dpm.setAlwaysOnVpnPackage(admin, pkg, p.alwaysOnVpn && p.vpnLockdown, if (pkg != null) setOf(context.packageName) else null)
            } else {
                dpm.setAlwaysOnVpnPackage(admin, pkg, p.alwaysOnVpn && p.vpnLockdown)
            }
        }
        if (p.alwaysOnVpn) {
            val store = RuleStore.get(context)
            if (!store.protectionWanted) store.protectionWanted = true
            if (!VeilVpnService.isRunning) attempt("start filter") { VeilVpnService.start(context) }
        }

        restrict(dpm, admin, UserManager.DISALLOW_CONFIG_VPN, p.disallowVpnConfig)
        if (Build.VERSION.SDK_INT >= 29) restrict(dpm, admin, DISALLOW_CONFIG_PRIVATE_DNS, p.disallowPrivateDnsConfig)
        restrict(dpm, admin, UserManager.DISALLOW_SAFE_BOOT, p.disallowSafeBoot)
        restrict(dpm, admin, UserManager.DISALLOW_FACTORY_RESET, p.disallowFactoryReset)
        restrict(dpm, admin, UserManager.DISALLOW_ADD_USER, p.disallowAddUser)
        restrict(dpm, admin, UserManager.DISALLOW_APPS_CONTROL, p.disallowAppsControl)
        restrict(dpm, admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, p.disallowUnknownSources)
        if (Build.VERSION.SDK_INT >= 29) restrict(dpm, admin, DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, p.disallowUnknownSources)
        restrict(dpm, admin, UserManager.DISALLOW_DEBUGGING_FEATURES, p.disallowDebugging)
        VeilLog.i("Device Owner policy applied: ${p.toJson()}")
    }

    /**
     * Undoes everything and gives up Device Owner, leaving a normal phone with
     * VEIL installed (the user can then uninstall it). Used when the admin
     * releases the phone or the offline recovery code is entered.
     */
    fun release(context: Context) {
        prefs(context).edit().clear().apply()
        if (!isOwner(context)) return
        val dpm = dpm(context)
        val admin = admin(context)
        for (r in ALL_RESTRICTIONS) attempt("clear $r") { dpm.clearUserRestriction(admin, r) }
        attempt("always-on VPN off") { dpm.setAlwaysOnVpnPackage(admin, null, false) }
        attempt("allow uninstall") { dpm.setUninstallBlocked(admin, context.packageName, false) }
        attempt("clear device owner") {
            @Suppress("DEPRECATION")
            dpm.clearDeviceOwnerApp(context.packageName)
        }
        VeilLog.i("Device Owner released")
    }

    private fun restrict(dpm: DevicePolicyManager, admin: ComponentName, key: String, on: Boolean) {
        attempt(key) { if (on) dpm.addUserRestriction(admin, key) else dpm.clearUserRestriction(admin, key) }
    }

    private inline fun attempt(what: String, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            VeilLog.w("Device Owner: $what failed: ${t.javaClass.simpleName}: ${t.message}")
        }
    }
}

package app.veil.android.apps

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import app.veil.android.VeilLog
import app.veil.android.admin.DeviceOwner
import org.json.JSONArray
import org.json.JSONObject

/**
 * Which apps may open, as set by the admin. On a Device Owner phone blocked
 * apps are suspended (greyed out, can't start); otherwise the accessibility
 * service sends them back to the home screen as they open. Essentials — the
 * launcher, phone, SMS, keyboard, Settings and VEIL — are never blocked, so a
 * mistake in the admin's list can't make the phone unusable.
 */
object AppControl {

    data class Policy(
        val mode: String = "off", // off | blocklist | allowlist
        val allowed: Set<String> = emptySet(),
        val blocked: Set<String> = emptySet(),
        val approveNewApps: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().put("mode", mode).put("allowed", JSONArray(allowed.toList()))
            .put("blocked", JSONArray(blocked.toList())).put("approveNewApps", approveNewApps)

        companion object {
            fun from(o: JSONObject?): Policy {
                o ?: return Policy()
                fun set(k: String): Set<String> {
                    val a = o.optJSONArray(k) ?: return emptySet()
                    return (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }.toSet()
                }
                val mode = o.optString("mode", "off").let { if (it in setOf("off", "blocklist", "allowlist")) it else "off" }
                return Policy(mode, set("allowed"), set("blocked"), o.optBoolean("approveNewApps", false))
            }
        }
    }

    data class App(val pkg: String, val label: String, val system: Boolean)

    @Volatile private var blockedCache: Set<String>? = null

    private fun prefs(c: Context) = c.getSharedPreferences("veil_apps", Context.MODE_PRIVATE)

    fun policy(c: Context): Policy = try {
        Policy.from(JSONObject(prefs(c).getString("policy", "{}") ?: "{}"))
    } catch (_: Throwable) {
        Policy()
    }

    /** Stores the admin's policy and enforces it. */
    fun setPolicy(c: Context, p: Policy) {
        val e = prefs(c).edit().putString("policy", p.toJson().toString())
        // "New apps need approval" compares against the apps present when it was switched on.
        if (p.approveNewApps && !prefs(c).contains("known_apps")) {
            e.putStringSet("known_apps", launchableApps(c).map { it.pkg }.toSet())
        } else if (!p.approveNewApps) {
            e.remove("known_apps")
        }
        e.apply()
        apply(c)
    }

    fun launchableApps(c: Context): List<App> {
        val pm = c.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = try {
            if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        } catch (t: Throwable) {
            VeilLog.w("App list failed: ${t.message}")
            emptyList()
        }
        val seen = LinkedHashMap<String, App>()
        for (ri in infos) {
            val ai = ri.activityInfo?.applicationInfo ?: continue
            val pkg = ai.packageName
            if (pkg in seen) continue
            val label = try { ri.loadLabel(pm).toString() } catch (_: Throwable) { pkg }
            seen[pkg] = App(pkg, label, (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
        }
        return seen.values.sortedBy { it.label.lowercase() }
    }

    /** Apps the phone can't work without. Resolved on the phone, so they're right for every manufacturer. */
    fun essentials(c: Context): Set<String> {
        val out = HashSet<String>()
        out += c.packageName
        out += listOf("com.android.settings", "com.android.systemui", "com.android.phone", "com.android.emergency",
            "com.google.android.packageinstaller", "com.android.packageinstaller", "com.google.android.permissioncontroller",
            "com.android.permissioncontroller")
        val pm = c.packageManager
        try {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            @Suppress("DEPRECATION")
            for (ri in pm.queryIntentActivities(home, 0)) ri.activityInfo?.packageName?.let { out += it }
        } catch (_: Throwable) {}
        try { (c.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { out += it } } catch (_: Throwable) {}
        try { Telephony.Sms.getDefaultSmsPackage(c)?.let { out += it } } catch (_: Throwable) {}
        try {
            val imm = c.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            for (im in imm.enabledInputMethodList) out += im.packageName
        } catch (_: Throwable) {}
        try {
            Settings.Secure.getString(c.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')?.let { out += it }
        } catch (_: Throwable) {}
        return out
    }

    /** The packages that must not open right now. */
    fun computeBlocked(c: Context, apps: List<App> = launchableApps(c)): Set<String> {
        val p = policy(c)
        val installed = apps.map { it.pkg }.toSet()
        val result = HashSet<String>()
        when (p.mode) {
            "blocklist" -> result += p.blocked.intersect(installed)
            "allowlist" -> result += installed - p.allowed
        }
        if (p.approveNewApps) {
            val known = prefs(c).getStringSet("known_apps", null)
            if (known != null) result += (installed - known - p.allowed)
        }
        // Downtime / bedtime: block every non-essential app while the window is active.
        if (downtimeActive(c)) result += installed
        return result - essentials(c)
    }

    /** Whether the bedtime/downtime window is active right now. */
    fun downtimeActive(c: Context): Boolean {
        val s = app.veil.android.rules.RuleStore.get(c)
        if (!s.downtimeEnabled) return false
        val cal = java.util.Calendar.getInstance()
        val dow = cal.get(java.util.Calendar.DAY_OF_WEEK) - 1 // Calendar Sunday=1 -> 0
        val minute = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        val days = s.downtimeDays.mapNotNull { it.toIntOrNull() }.toSet()
        return app.veil.android.rules.Schedule.activeAt(true, s.downtimeStart, s.downtimeEnd, days, dow, minute)
    }

    /** Cached for the accessibility service, which asks on every app switch. */
    fun isBlocked(c: Context, pkg: String): Boolean {
        val set = blockedCache ?: (prefs(c).getStringSet("blocked_now", emptySet()) ?: emptySet()).also { blockedCache = it }
        return pkg in set
    }

    fun blockedNow(c: Context): Set<String> =
        blockedCache ?: (prefs(c).getStringSet("blocked_now", emptySet()) ?: emptySet()).also { blockedCache = it }

    /** Recomputes and enforces. Safe to call often (new installs, each check-in). */
    @Synchronized
    fun apply(c: Context) {
        val blocked = computeBlocked(c)
        prefs(c).edit().putStringSet("blocked_now", blocked).apply()
        blockedCache = blocked
        if (!DeviceOwner.isOwner(c)) return
        val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = DeviceOwner.admin(c)
        val suspended = prefs(c).getStringSet("suspended", emptySet()) ?: emptySet()
        val toLift = suspended - blocked
        try {
            if (toLift.isNotEmpty()) dpm.setPackagesSuspended(admin, toLift.toTypedArray(), false)
            val failed = if (blocked.isNotEmpty()) dpm.setPackagesSuspended(admin, blocked.toTypedArray(), true).toSet() else emptySet()
            prefs(c).edit().putStringSet("suspended", blocked - failed).apply()
            if (failed.isNotEmpty()) VeilLog.w("Could not pause ${failed.size} apps: ${failed.take(5)}")
        } catch (t: Throwable) {
            VeilLog.w("App suspension failed: ${t.message}")
        }
    }

    /** Lifts every suspension VEIL made. Called on release, before Device Owner is given up. */
    fun releaseAll(c: Context) {
        val suspended = prefs(c).getStringSet("suspended", emptySet()) ?: emptySet()
        if (suspended.isNotEmpty() && DeviceOwner.isOwner(c)) {
            try {
                val dpm = c.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                dpm.setPackagesSuspended(DeviceOwner.admin(c), suspended.toTypedArray(), false)
            } catch (t: Throwable) {
                VeilLog.w("Could not lift app suspensions: ${t.message}")
            }
        }
        prefs(c).edit().clear().apply()
        blockedCache = emptySet()
    }

    /** The app list for the admin's Apps tab, with what's blocked right now. */
    fun inventory(c: Context): JSONArray {
        val apps = launchableApps(c)
        val blocked = blockedNow(c)
        val arr = JSONArray()
        for (a in apps) arr.put(JSONObject().put("package", a.pkg).put("label", a.label).put("system", a.system).put("blocked", a.pkg in blocked))
        return arr
    }

    fun label(c: Context, pkg: String): String = try {
        val pm = c.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }
}

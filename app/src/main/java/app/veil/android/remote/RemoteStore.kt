package app.veil.android.remote

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Pairing state for remote management: which server, this phone's token, the
 * admin's name, and the queues of things waiting to be sent. Kept apart from
 * RuleStore so unpairing can wipe it in one go without touching the rules.
 */
class RemoteStore private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("veil_remote", Context.MODE_PRIVATE)

    val isPaired: Boolean get() = token.isNotEmpty() && server.isNotEmpty()

    var server: String
        get() = prefs.getString("server", "") ?: ""
        set(v) = prefs.edit().putString("server", v).apply()

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v).apply()

    var deviceId: String
        get() = prefs.getString("device_id", "") ?: ""
        set(v) = prefs.edit().putString("device_id", v).apply()

    var adminName: String
        get() = prefs.getString("admin_name", "") ?: ""
        set(v) = prefs.edit().putString("admin_name", v).apply()

    /** The friendly name the admin gave this phone on the dashboard (e.g. "Dad's phone"). */
    var deviceName: String
        get() = prefs.getString("device_name", "") ?: ""
        set(v) = prefs.edit().putString("device_name", v).apply()

    /** sha256("veil-recovery:" + code); the code itself is only ever shown to the admin. */
    var recoveryHash: String
        get() = prefs.getString("recovery_hash", "") ?: ""
        set(v) = prefs.edit().putString("recovery_hash", v).apply()

    var configVersion: Int
        get() = prefs.getInt("config_version", 0)
        set(v) = prefs.edit().putInt("config_version", v).apply()

    var lastSyncAt: Long
        get() = prefs.getLong("last_sync_at", 0)
        set(v) = prefs.edit().putLong("last_sync_at", v).apply()

    var lastSyncError: String
        get() = prefs.getString("last_sync_error", "") ?: ""
        set(v) = prefs.edit().putString("last_sync_error", v).apply()

    var pollSeconds: Int
        get() = prefs.getInt("poll_seconds", 60).coerceIn(30, 900)
        set(v) = prefs.edit().putInt("poll_seconds", v).apply()

    /** Pairing details handed over by Device Owner provisioning, used once. */
    var pendingPairServer: String
        get() = prefs.getString("pending_server", "") ?: ""
        set(v) = prefs.edit().putString("pending_server", v).apply()

    var pendingPairCode: String
        get() = prefs.getString("pending_code", "") ?: ""
        set(v) = prefs.edit().putString("pending_code", v).apply()

    var recoveryFailures: Int
        get() = prefs.getInt("recovery_failures", 0)
        set(v) = prefs.edit().putInt("recovery_failures", v).apply()

    var recoveryLockedUntil: Long
        get() = prefs.getLong("recovery_locked_until", 0)
        set(v) = prefs.edit().putLong("recovery_locked_until", v).apply()

    /** Last status sent, to turn on→off transitions into tamper events. */
    var lastStatus: JSONObject
        get() = try { JSONObject(prefs.getString("last_status", "{}") ?: "{}") } catch (_: Throwable) { JSONObject() }
        set(v) = prefs.edit().putString("last_status", v.toString()).apply()

    /** Hash of the app list last sent, so it's only uploaded when it changes. */
    var appsHash: String
        get() = prefs.getString("apps_hash", "") ?: ""
        set(v) = prefs.edit().putString("apps_hash", v).apply()

    var lastImagesCovered: Long
        get() = prefs.getLong("last_images_covered", -1)
        set(v) = prefs.edit().putLong("last_images_covered", v).apply()

    var lastTextCovered: Long
        get() = prefs.getLong("last_text_covered", -1)
        set(v) = prefs.edit().putLong("last_text_covered", v).apply()

    // ---- unblock requests raised on this phone ----

    /** kind "site" (host is a domain) or "app" (host is a package name, label its name). */
    class Request(val localId: String, val host: String, val reason: String, val at: Long, var status: String, var until: Long, var sent: Boolean, var note: String,
                  val kind: String = "site", val label: String = "")

    @Synchronized
    fun requests(): List<Request> {
        val arr = try { JSONArray(prefs.getString("requests", "[]")) } catch (_: Throwable) { JSONArray() }
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Request(o.getString("localId"), o.getString("host"), o.optString("reason"), o.getLong("at"),
                o.optString("status", "pending"), o.optLong("until", -1), o.optBoolean("sent"), o.optString("note"),
                o.optString("kind", "site"), o.optString("label"))
        }
    }

    @Synchronized
    fun saveRequests(list: List<Request>) {
        val arr = JSONArray()
        for (r in list.sortedByDescending { it.at }.take(100)) {
            arr.put(JSONObject().put("localId", r.localId).put("host", r.host).put("reason", r.reason).put("at", r.at)
                .put("status", r.status).put("until", r.until).put("sent", r.sent).put("note", r.note)
                .put("kind", r.kind).put("label", r.label))
        }
        prefs.edit().putString("requests", arr.toString()).apply()
    }

    fun checkRecoveryCode(code: String): Boolean {
        val digits = code.filter { it.isDigit() }
        if (digits.length != 8 || recoveryHash.isEmpty()) return false
        return sha256("veil-recovery:$digits").equals(recoveryHash, ignoreCase = true)
    }

    /** Forget the pairing (keeps nothing that could talk to the server again). */
    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        @Volatile private var instance: RemoteStore? = null

        fun get(context: Context): RemoteStore =
            instance ?: synchronized(this) { instance ?: RemoteStore(context.applicationContext).also { instance = it } }

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

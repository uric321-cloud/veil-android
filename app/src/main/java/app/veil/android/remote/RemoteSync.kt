package app.veil.android.remote

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import app.veil.android.BuildConfig
import app.veil.android.R
import app.veil.android.VeilApp
import app.veil.android.VeilLog
import app.veil.android.admin.DeviceOwner
import app.veil.android.apps.AppControl
import app.veil.android.rules.ListSource
import app.veil.android.rules.RuleStore
import app.veil.android.screen.VeilAccessibilityService
import app.veil.android.ui.MainActivity
import app.veil.android.vpn.VeilVpnService
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Talks to the admin backend: pairs, then checks in about once a minute with
 * status, activity, tamper events and unblock requests, and applies whatever
 * the admin changed. Only VEIL's own settings and data travel this way.
 */
object RemoteSync {

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "veil-remote").apply { isDaemon = true }
    }
    private var next: ScheduledFuture<*>? = null
    @Volatile private var appContext: Context? = null
    @Volatile var listener: (() -> Unit)? = null

    /** Starts the check-in loop if paired (or pairs first if provisioning left a code). */
    fun start(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        EventQueue.init(ctx)
        val r = RemoteStore.get(ctx)
        if (!r.isPaired && r.pendingPairCode.isEmpty()) return
        schedule(0)
    }

    fun syncNow(context: Context) {
        appContext = context.applicationContext
        schedule(0)
    }

    @Synchronized
    private fun schedule(delaySeconds: Long) {
        next?.cancel(false)
        next = executor.schedule({ runOnce() }, delaySeconds, TimeUnit.SECONDS)
    }

    private fun runOnce() {
        val ctx = appContext ?: return
        val r = RemoteStore.get(ctx)
        try {
            if (!r.isPaired && r.pendingPairCode.isNotEmpty()) {
                pairBlocking(ctx, r.pendingPairServer, r.pendingPairCode, deviceOwnerFlow = true)
            }
            if (r.isPaired) sync(ctx)
            r.lastSyncError = ""
        } catch (t: Throwable) {
            r.lastSyncError = t.message ?: t.javaClass.simpleName
            VeilLog.w("Remote check-in failed: ${r.lastSyncError}")
        } finally {
            notifyListener()
            // While a request waits for an answer, check back sooner so an AI or admin decision lands in seconds.
            val waiting = r.isPaired && r.requests().any { it.status == "pending" && it.sent }
            if (r.isPaired || r.pendingPairCode.isNotEmpty()) schedule(if (waiting) minOf(20L, r.pollSeconds.toLong()) else r.pollSeconds.toLong())
        }
    }

    // ------------------------------------------------------------------ pairing

    /** Pairs from the Settings screen. [done] runs on the remote thread with null or an error message. */
    fun pair(context: Context, serverInput: String, code: String, done: (String?) -> Unit) {
        val ctx = context.applicationContext
        appContext = ctx
        executor.execute {
            val err = try {
                val server = RemoteClient.normalizeServer(serverInput) ?: throw IllegalArgumentException("Enter the server address your admin gave you (https://…)")
                pairBlocking(ctx, server, code, deviceOwnerFlow = false)
                sync(ctx)
                null
            } catch (t: Throwable) {
                VeilLog.w("Pairing failed: ${t.message}")
                t.message ?: "Pairing failed"
            }
            notifyListener()
            done(err)
            if (err == null) schedule(RemoteStore.get(ctx).pollSeconds.toLong())
        }
    }

    private fun pairBlocking(ctx: Context, server: String, code: String, deviceOwnerFlow: Boolean) {
        val r = RemoteStore.get(ctx)
        val res = try {
            RemoteClient(server, null).post("/api/device/pair", JSONObject()
                .put("code", code)
                .put("deviceName", Build.MODEL ?: "Phone")
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("status", status(ctx)))
        } catch (e: RemoteClient.HttpException) {
            // A bad code from provisioning will never succeed; stop retrying it.
            if (deviceOwnerFlow && e.status == 404) { r.pendingPairCode = ""; r.pendingPairServer = "" }
            throw e
        }
        r.server = server
        r.token = res.getString("token")
        r.deviceId = res.optString("deviceId")
        r.adminName = res.optString("adminName")
        r.recoveryHash = res.optString("recoveryHash")
        r.pollSeconds = res.optInt("pollSeconds", 60)
        r.pendingPairCode = ""
        r.pendingPairServer = ""
        res.optJSONObject("config")?.let {
            RemoteConfig.apply(ctx, it)
            r.configVersion = res.optInt("configVersion", 0)
        }
        EventQueue.refresh(ctx)
        VeilLog.i("Paired with ${r.adminName} at $server")
    }

    // ------------------------------------------------------------------ check-in

    private fun sync(ctx: Context) {
        val r = RemoteStore.get(ctx)
        val store = RuleStore.get(ctx)
        store.pruneTempAllows()
        AppControl.apply(ctx) // catches apps installed since the last check-in
        val inventory = AppControl.inventory(ctx)
        val inventoryHash = RemoteStore.sha256(inventory.toString())

        val st = status(ctx)
        noteTransitions(r, st)
        val covered = store.textCoveredTotal
        if (r.lastTextCovered in 0 until covered) EventQueue.text(covered - r.lastTextCovered)
        r.lastTextCovered = covered

        val (events, unknown) = EventQueue.drain()
        val requests = r.requests()
        val body = JSONObject()
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("appliedConfigVersion", r.configVersion)
            .put("status", st)
            .put("events", JSONArray(events))
            .put("unknownDomains", JSONArray(unknown))
            .put("requests", JSONArray(requests.filter { !it.sent }.map {
                JSONObject().put("localId", it.localId).put("kind", it.kind).put("host", it.host).put("label", it.label).put("reason", it.reason).put("at", it.at)
            }))
        if (inventoryHash != r.appsHash) body.put("apps", inventory)
        body.put("inAppHash", app.veil.android.screen.InAppRules.hash(ctx))
        val res = try {
            RemoteClient(r.server, r.token).post("/api/device/sync", body)
        } catch (t: Throwable) {
            EventQueue.restore(events, unknown)
            if (t is RemoteClient.HttpException && t.status == 401) {
                throw IllegalStateException("The server no longer recognises this phone. Ask your admin, or use the recovery code.")
            }
            throw t
        }
        r.lastStatus = st
        r.appsHash = inventoryHash
        r.lastSyncAt = System.currentTimeMillis()
        r.pollSeconds = res.optInt("pollSeconds", 60)
        res.optString("adminName").takeIf { it.isNotEmpty() }?.let { r.adminName = it }
        if (requests.any { !it.sent }) r.saveRequests(requests.map { it.sent = true; it })

        res.optJSONObject("config")?.let {
            RemoteConfig.apply(ctx, it)
            r.configVersion = res.optInt("configVersion", r.configVersion)
            EventQueue.refresh(ctx)
            VeilLog.i("Applied admin settings v${r.configVersion}")
        }
        applyRequestDecisions(ctx, res.optJSONArray("requests"))
        res.optJSONArray("inAppRules")?.let { app.veil.android.screen.InAppRules.save(ctx, it, res.optString("inAppHash")) }

        val aiVersion = res.optInt("aiBlocklistVersion", 0)
        if (aiVersion > 0 && aiVersion != store.aiBlocklistVersion) fetchAiBlocklist(ctx, aiVersion)

        val commands = res.optJSONArray("commands") ?: JSONArray()
        if (commands.length() > 0) runCommands(ctx, commands)
    }

    private fun runCommands(ctx: Context, commands: JSONArray) {
        val r = RemoteStore.get(ctx)
        val acks = JSONArray()
        var release = false
        for (i in 0 until commands.length()) {
            val c = commands.optJSONObject(i) ?: continue
            when (c.optString("type")) {
                "refresh_lists" -> try {
                    val n = ListSource(ctx).updateFromInternet()
                    val s = RuleStore.get(ctx)
                    s.listDomainCount = n
                    s.listUpdatedAt = System.currentTimeMillis()
                } catch (t: Throwable) {
                    VeilLog.w("Admin-requested list update failed: ${t.message}")
                }
                "release" -> release = true
                else -> {}
            }
            acks.put(c.optString("id"))
        }
        // Acknowledge before acting on a release: afterwards this phone has no credentials left.
        RemoteClient(r.server, r.token).post("/api/device/sync", JSONObject()
            .put("appliedConfigVersion", r.configVersion).put("commandAcks", acks).put("status", status(ctx)))
        if (release) releaseLocally(ctx, "Your admin released this phone. VEIL no longer manages it and can be removed.")
    }

    private fun fetchAiBlocklist(ctx: Context, version: Int) {
        val r = RemoteStore.get(ctx)
        try {
            val res = RemoteClient(r.server, r.token).get("/api/device/ai-blocklist")
            val arr = res.optJSONArray("domains") ?: JSONArray()
            ListSource(ctx).saveAiBlocklist((0 until arr.length()).mapNotNull { RuleStore.normalizeHost(arr.optString(it)) })
            val allow = res.optJSONArray("allow") ?: JSONArray()
            ListSource(ctx).saveAiAllowlist((0 until allow.length()).mapNotNull { RuleStore.normalizeHost(allow.optString(it)) })
            RuleStore.get(ctx).aiBlocklistVersion = res.optInt("version", version)
        } catch (t: Throwable) {
            VeilLog.w("AI blocklist download failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ status + tamper

    fun status(ctx: Context): JSONObject {
        val s = RuleStore.get(ctx)
        return JSONObject()
            .put("protection", s.protectionWanted)
            .put("vpnRunning", VeilVpnService.isRunning)
            .put("screenFilter", s.screenProtectionWanted)
            .put("accessibilityOn", accessibilityOn(ctx))
            .put("overlayAllowed", Settings.canDrawOverlays(ctx))
            .put("deviceOwner", DeviceOwner.isOwner(ctx))
            .put("privateDns", s.privateDnsHost)
            .put("blockedToday", s.blockedToday)
            .put("blockedTotal", s.blockedTotal)
            .put("textCoveredTotal", s.textCoveredTotal)
            .put("androidVersion", Build.VERSION.RELEASE ?: "")
            .put("model", "${Build.MANUFACTURER} ${Build.MODEL}")
    }

    fun accessibilityOn(ctx: Context): Boolean {
        val expected = "${ctx.packageName}/${VeilAccessibilityService::class.java.name}"
        val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return flat.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    /** Something that was on and is now off becomes a tamper alert for the admin. */
    private fun noteTransitions(r: RemoteStore, now: JSONObject) {
        val was = r.lastStatus
        if (was.length() == 0) return
        fun turnedOff(k: String) = was.optBoolean(k, false) && !now.optBoolean(k, false)
        if (turnedOff("vpnRunning") && now.optBoolean("protection")) EventQueue.tamper("vpn_stopped")
        if (turnedOff("protection")) EventQueue.tamper("vpn_stopped", "Protection switched off")
        if (turnedOff("accessibilityOn")) EventQueue.tamper("accessibility_off")
        if (turnedOff("overlayAllowed")) EventQueue.tamper("overlay_off")
        if (turnedOff("deviceOwner")) EventQueue.tamper("not_device_owner")
        val dns = now.optString("privateDns")
        if (dns.isNotEmpty() && dns != was.optString("privateDns")) EventQueue.tamper("private_dns", dns)
    }

    // ------------------------------------------------------------------ unblock requests

    fun requestUnblock(context: Context, host: String, reason: String): Boolean {
        val h = RuleStore.normalizeHost(host) ?: return false
        val r = RemoteStore.get(context)
        if (!r.isPaired) return false
        val list = r.requests().toMutableList()
        if (list.any { it.kind == "site" && it.host == h && it.status == "pending" }) return true
        list.add(RemoteStore.Request(UUID.randomUUID().toString(), h, reason.trim().take(500), System.currentTimeMillis(), "pending", -1, false, "", "site"))
        r.saveRequests(list)
        syncNow(context)
        return true
    }

    fun requestApp(context: Context, pkg: String, label: String, reason: String): Boolean {
        val r = RemoteStore.get(context)
        if (!r.isPaired) return false
        val list = r.requests().toMutableList()
        if (list.any { it.kind == "app" && it.host == pkg && it.status == "pending" }) return true
        list.add(RemoteStore.Request(UUID.randomUUID().toString(), pkg, reason.trim().take(500), System.currentTimeMillis(), "pending", -1, false, "", "app", label.take(80)))
        r.saveRequests(list)
        syncNow(context)
        return true
    }

    private fun applyRequestDecisions(ctx: Context, arr: JSONArray?) {
        arr ?: return
        val r = RemoteStore.get(ctx)
        val list = r.requests()
        var changed = false
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val req = list.firstOrNull { it.localId == o.optString("localId") } ?: continue
            val status = o.optString("status")
            if (status.isEmpty() || status == req.status) continue
            req.status = status
            req.until = if (o.has("until")) o.optLong("until", -1) else -1
            req.note = o.optString("note")
            changed = true
            val name = if (req.kind == "app") req.label.ifEmpty { req.host } else req.host
            val text = when {
                req.kind == "app" && status == "approved" -> "$name is allowed. It may take a minute to open."
                status == "approved" && req.until == 0L -> "${req.host} is now always allowed."
                status == "approved" -> "${req.host} is allowed for ${minutesLeft(req.until)}."
                else -> "$name stays blocked." + if (req.note.isNotEmpty()) " ${r.adminName}: ${req.note}" else ""
            }
            notify(ctx, 40 + (req.localId.hashCode() and 0xff), if (status == "approved") "Request approved" else "Request declined", text)
        }
        if (changed) r.saveRequests(list)
    }

    private fun minutesLeft(until: Long): String {
        val m = ((until - System.currentTimeMillis()) / 60_000).coerceAtLeast(1)
        return if (m >= 120) "${m / 60} hours" else "$m minutes"
    }

    // ------------------------------------------------------------------ release + recovery

    private fun releaseLocally(ctx: Context, message: String) {
        AppControl.releaseAll(ctx)
        app.veil.android.screen.InAppRules.clear(ctx)
        DeviceOwner.release(ctx)
        RemoteStore.get(ctx).clear()
        EventQueue.refresh(ctx)
        ListSource(ctx).saveAiBlocklist(emptyList())
        RuleStore.get(ctx).tempAllows = emptyMap()
        notify(ctx, 39, "VEIL was released", message)
        notifyListener()
    }

    sealed class RecoveryResult {
        object Released : RecoveryResult()
        class Wrong(val attemptsLeft: Int) : RecoveryResult()
        class Locked(val until: Long) : RecoveryResult()
    }

    /**
     * The offline escape hatch: the 8-digit code the admin saw when pairing.
     * Five wrong tries lock it for 30 minutes and alert the admin.
     */
    fun recover(context: Context, code: String): RecoveryResult {
        val r = RemoteStore.get(context)
        val now = System.currentTimeMillis()
        if (r.recoveryLockedUntil > now) return RecoveryResult.Locked(r.recoveryLockedUntil)
        if (!r.checkRecoveryCode(code)) {
            val fails = r.recoveryFailures + 1
            EventQueue.tamper("recovery_failed")
            syncNow(context)
            return if (fails >= 5) {
                r.recoveryFailures = 0
                r.recoveryLockedUntil = now + 30 * 60_000
                RecoveryResult.Locked(r.recoveryLockedUntil)
            } else {
                r.recoveryFailures = fails
                RecoveryResult.Wrong(5 - fails)
            }
        }
        // Tell the admin first (best effort), then release.
        val ctx = context.applicationContext
        executor.execute {
            try {
                EventQueue.tamper("recovery_used")
                sync(ctx)
            } catch (_: Throwable) {}
            releaseLocally(ctx, "The recovery code removed VEIL's lockdown and unpaired this phone.")
        }
        return RecoveryResult.Released
    }

    // ------------------------------------------------------------------ misc

    private fun notifyListener() {
        listener?.let { l -> android.os.Handler(android.os.Looper.getMainLooper()).post { l() } }
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        try {
            val pi = PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_TAB, "activity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, VeilApp.CHANNEL_BLOCKS)
                .setSmallIcon(R.drawable.ic_stat_veil)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
        } catch (t: Throwable) {
            VeilLog.w("Notification failed: ${t.message}")
        }
    }
}

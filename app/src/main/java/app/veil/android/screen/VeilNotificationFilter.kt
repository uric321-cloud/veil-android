package app.veil.android.screen

import android.app.Notification
import android.content.SharedPreferences
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.veil.android.VeilLog
import app.veil.android.rules.RuleStore
import app.veil.android.rules.TextRules

/**
 * Cancels incoming notifications whose text contains blocked words, so a flagged
 * message never even shows in the shade or as a banner - the one place the
 * on-screen text filter can't reliably cover in time. Reuses the same text
 * engine and rules as the screen filter. Reads only the notification's own
 * title/text; nothing is stored or sent. Needs the user to grant notification
 * access once (a separate permission from accessibility).
 */
class VeilNotificationFilter : NotificationListenerService() {

    @Volatile private var engine: TextRuleEngine? = null
    private var store: RuleStore? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in RuleStore.SCREEN_RULE_KEYS) rebuild()
    }

    override fun onListenerConnected() {
        val s = RuleStore.get(this)
        store = s
        rebuild()
        try { s.registerListener(prefsListener) } catch (_: Throwable) {}
        VeilLog.i("Notification filter connected")
    }

    override fun onListenerDisconnected() {
        try { store?.unregisterListener(prefsListener) } catch (_: Throwable) {}
    }

    private fun rebuild() {
        val s = store ?: return
        engine = try { TextRules(this).engine(s) } catch (t: Throwable) {
            VeilLog.e("Notification engine build failed", t); null
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val s = store ?: return
        if (!s.screenProtectionWanted || !s.textEnabled || !s.notificationFilter) return
        if (sbn.packageName == packageName) return // never touch VEIL's own notifications
        val e = engine ?: return
        val ex = sbn.notification?.extras ?: return
        val text = listOfNotNull(
            ex.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
        ).joinToString("  ")
        if (text.isBlank()) return
        val flagged = e.scan(text).any {
            it.action == TextAction.BAR || it.action == TextAction.STRIKE || it.action == TextAction.FROST
        }
        if (!flagged) return
        try {
            cancelNotification(sbn.key)
            s.countTextCovered(1) // the check-in reports the delta to the admin
            VeilLog.i("Blocked a notification from ${sbn.packageName}")
        } catch (t: Throwable) {
            VeilLog.w("Could not cancel notification: ${t.message}")
        }
    }

    companion object {
        /** Whether the user has granted VEIL notification access. */
        fun enabled(ctx: android.content.Context): Boolean {
            val flat = android.provider.Settings.Secure.getString(
                ctx.contentResolver, "enabled_notification_listeners"
            ) ?: return false
            val me = ctx.packageName
            return flat.split(':').any { it.startsWith("$me/") }
        }
    }
}

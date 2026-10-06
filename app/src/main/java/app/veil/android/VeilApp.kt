package app.veil.android

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

class VeilApp : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        createNotificationChannels()
        VeilLog.i("VEIL ${BuildConfig.VERSION_NAME} starting on ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}, Android ${android.os.Build.VERSION.RELEASE}")
        // Re-assert Device Owner lockdown (no-op otherwise) and resume admin check-ins.
        app.veil.android.admin.DeviceOwner.applyBaseline(this)
        app.veil.android.remote.RemoteSync.start(this)
        watchPackages()
    }

    /**
     * New installs are checked straight away (not at the next check-in), so an
     * app that needs approval is blocked before it's first opened. Package
     * broadcasts can't be declared in the manifest, so this lives while the
     * process does (the filter's foreground service keeps it alive).
     */
    private fun watchPackages() {
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED)
            addAction(android.content.Intent.ACTION_PACKAGE_REMOVED)
            addAction(android.content.Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: android.content.Intent) {
                Thread {
                    try {
                        app.veil.android.apps.AppControl.apply(context)
                        if (app.veil.android.remote.RemoteStore.get(context).isPaired) app.veil.android.remote.RemoteSync.syncNow(context)
                    } catch (t: Throwable) {
                        VeilLog.w("Package change handling failed: ${t.message}")
                    }
                }.start()
            }
        }
        try {
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
            else registerReceiver(receiver, filter)
        } catch (t: Throwable) {
            VeilLog.w("Could not watch package changes: ${t.message}")
        }
    }

    /** Writes any uncaught exception to a file so the next launch can offer to share it. */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("VEIL ").append(BuildConfig.VERSION_NAME).append(" crashed on thread ").append(thread.name).append('\n')
                    append(sw.toString()).append("\n--- recent log ---\n").append(VeilLog.dump())
                }
                crashFile(this).writeText(text)
            } catch (_: Throwable) {
                // Nothing sensible to do if even this fails.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Protection status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while VEIL is filtering. Cannot be swiped away while protection is on."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BLOCKS, "Blocked sites", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "A short notice when VEIL blocks a site. Rate-limited per site."
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Protection alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Protection was turned off, another VPN took over, or a bypass was detected."
            }
        )
    }

    companion object {
        const val CHANNEL_STATUS = "veil.status"
        const val CHANNEL_BLOCKS = "veil.blocks"
        const val CHANNEL_ALERTS = "veil.alerts"

        fun crashFile(context: Context): File = File(context.filesDir, "last_crash.txt")
    }
}

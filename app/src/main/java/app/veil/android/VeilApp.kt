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

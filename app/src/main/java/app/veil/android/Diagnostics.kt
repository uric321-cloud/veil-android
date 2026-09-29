package app.veil.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.PowerManager
import android.widget.Toast
import app.veil.android.rules.BlockLog
import app.veil.android.rules.RuleStore
import app.veil.android.vpn.VeilVpnService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the text a tester shares back so problems can be diagnosed without adb. */
object Diagnostics {

    fun report(context: Context): String {
        val store = RuleStore.get(context)
        val sb = StringBuilder()
        sb.append("VEIL diagnostics ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append('\n')
        sb.append("App: ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(") ").append(BuildConfig.APPLICATION_ID).append('\n')
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" / Android ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        sb.append("Service running: ").append(VeilVpnService.isRunning).append('\n')
        VeilVpnService.instance?.let { sb.append("Service: ").append(it.diagnostics()).append('\n') }
        sb.append("VPN permission granted: ").append(VpnService.prepare(context) == null).append('\n')
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            sb.append("Battery optimization ignored: ").append(pm.isIgnoringBatteryOptimizations(context.packageName)).append('\n')
        } catch (_: Throwable) {}
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val n = cm.activeNetwork
            val caps = if (n != null) cm.getNetworkCapabilities(n) else null
            val lp = if (n != null) cm.getLinkProperties(n) else null
            sb.append("Active network: ")
            if (caps == null) sb.append("none") else {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) sb.append("VPN ")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) sb.append("WIFI ")
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) sb.append("CELL ")
            }
            if (lp != null) {
                sb.append("dns=").append(lp.dnsServers.joinToString(",") { it.hostAddress ?: "?" })
                if (Build.VERSION.SDK_INT >= 28) sb.append(" privateDnsActive=").append(lp.isPrivateDnsActive).append(" privateDnsHost=").append(lp.privateDnsServerName ?: "")
            }
            sb.append('\n')
        } catch (t: Throwable) { sb.append("Network info failed: ").append(t.message).append('\n') }
        sb.append("Settings: ").append(store.describe()).append('\n')
        val blocks = BlockLog.snapshot().take(15)
        sb.append("Recent blocks (").append(blocks.size).append("):\n")
        for (b in blocks) sb.append("  ").append(b.host).append(" x").append(b.count).append(" ").append(b.reason).append('\n')
        val crash = VeilApp.crashFile(context)
        if (crash.exists()) sb.append("--- last crash ---\n").append(crash.readText().take(6000)).append('\n')
        sb.append("--- log ---\n").append(VeilLog.dump())
        return sb.toString()
    }

    fun share(context: Context, text: String, subject: String) {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        try {
            context.startActivity(Intent.createChooser(i, "Share $subject"))
        } catch (t: Throwable) {
            copy(context, text)
        }
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("VEIL diagnostics", text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}

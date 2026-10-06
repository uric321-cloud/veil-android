package app.veil.android.vpn

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import app.veil.android.R
import app.veil.android.VeilApp
import app.veil.android.VeilLog
import app.veil.android.dns.DnsMessage
import app.veil.android.dns.Upstream
import app.veil.android.remote.EventQueue
import app.veil.android.remote.RemoteStore
import app.veil.android.rules.BlockLog
import app.veil.android.rules.Decision
import app.veil.android.rules.ListSource
import app.veil.android.rules.Matcher
import app.veil.android.rules.RuleStore
import app.veil.android.ui.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The filter. A local VPN that only captures DNS (and traffic to well-known
 * public resolvers, which it refuses). Every DNS question is checked against the
 * rules: blocked names get NXDOMAIN, SafeSearch names get the safe endpoint's
 * addresses, everything else is forwarded to a public resolver through a socket
 * that bypasses the tunnel. No web traffic passes through this app.
 */
class VeilVpnService : VpnService() {

    private lateinit var store: RuleStore
    private lateinit var lists: ListSource
    @Volatile private var matcher: Matcher? = null
    private var upstream: Upstream? = null
    private var tun: ParcelFileDescriptor? = null
    private var loopThread: Thread? = null
    private val running = AtomicBoolean(false)
    private val starting = AtomicBoolean(false)
    private var tunOut: FileOutputStream? = null
    private val outLock = Any()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val blockNotifyTimes = ConcurrentHashMap<String, Long>()
    @Volatile private var lastBlockNotify = 0L
    @Volatile private var lastStatusUpdate = 0L
    @Volatile private var writeErrorLogged = false

    private class CachedAnswer(val records: List<DnsMessage.Record>, val expires: Long)
    private val safeSearchCache = ConcurrentHashMap<String, CachedAnswer>()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && key in RuleStore.RULE_KEYS) {
            Thread({ rebuildMatcher() }, "veil-rules").start()
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = RuleStore.get(this)
        lists = ListSource(this)
        BlockLog.init(this)
        store.registerListener(prefListener)
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START // null intent = always-on VPN or restart after kill
        VeilLog.i("Service command: $action")
        if (action == ACTION_STOP) {
            showStatusNotificationForeground()
            stopProtection("user")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        showStatusNotificationForeground()
        startProtection()
        return START_STICKY
    }

    override fun onDestroy() {
        VeilLog.i("Service destroyed")
        stopProtection("destroy")
        store.unregisterListener(prefListener)
        instance = null
        super.onDestroy()
    }

    /** Called by Android when another VPN app takes over, or the user removes the VPN. */
    override fun onRevoke() {
        VeilLog.w("VPN revoked by the system (another VPN or user action)")
        store.lastRevokeAt = System.currentTimeMillis()
        EventQueue.tamper("vpn_revoked")
        postAlert(
            "Protection was turned off",
            "Another VPN app took over the connection, or the VPN was removed in Settings. Open VEIL to turn protection back on."
        )
        stopProtection("revoke")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    // ------------------------------------------------------------------ lifecycle

    private fun startProtection() {
        if (running.get() || !starting.compareAndSet(false, true)) return
        Thread({
            try {
                rebuildMatcher()
                val up = Upstream { protect(it) }
                up.start()
                upstream = up
                val pfd = establish()
                if (pfd == null) {
                    VeilLog.e("establish() returned null: VPN permission missing or another VPN is active")
                    up.stop()
                    upstream = null
                    starting.set(false)
                    postAlert("Could not start protection", "Android refused the VPN. Open VEIL and turn protection on again to re-grant permission.")
                    mainHandler.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                    return@Thread
                }
                tun = pfd
                tunOut = FileOutputStream(pfd.fileDescriptor)
                running.set(true)
                isRunning = true
                starting.set(false)
                registerNetworkCallback()
                notifyState(true)
                VeilLog.i("Protection started")
                loopThread = Thread.currentThread()
                packetLoop(pfd)
            } catch (t: Throwable) {
                VeilLog.e("startProtection failed", t)
                starting.set(false)
                stopProtection("error")
            }
        }, "veil-tun").also { it.isDaemon = true }.start()
    }

    private fun stopProtection(why: String) {
        if (!running.getAndSet(false) && tun == null) return
        VeilLog.i("Stopping protection ($why)")
        isRunning = false
        try { tunOut?.close() } catch (_: Throwable) {}
        tunOut = null
        try { tun?.close() } catch (_: Throwable) {}
        tun = null
        upstream?.stop()
        upstream = null
        unregisterNetworkCallback()
        BlockLog.flush()
        notifyState(false)
    }

    private fun establish(): ParcelFileDescriptor? {
        val b = Builder()
        b.setSession("VEIL")
        b.setMtu(1500)
        b.addAddress(TUN_ADDRESS_V4, 24)
        b.addDnsServer(DNS_V4)
        b.addRoute(DNS_V4, 32)
        var v6 = false
        try {
            b.addAddress(TUN_ADDRESS_V6, 64)
            v6 = true
        } catch (t: Throwable) {
            VeilLog.w("IPv6 address not accepted on this device: ${t.message}")
        }
        if (store.bypassProtectionEnabled) {
            for (r in SINKHOLE_V4) try { b.addRoute(r.first, r.second) } catch (t: Throwable) { VeilLog.w("route ${r.first}: ${t.message}") }
            if (v6) for (r in SINKHOLE_V6) try { b.addRoute(r.first, r.second) } catch (t: Throwable) { VeilLog.w("route ${r.first}: ${t.message}") }
        }
        b.setBlocking(true)
        if (Build.VERSION.SDK_INT >= 29) b.setMetered(false)
        try { b.addDisallowedApplication(packageName) } catch (t: Throwable) { VeilLog.w("addDisallowedApplication: ${t.message}") }
        b.setConfigureIntent(mainPendingIntent("home"))
        return b.establish()
    }

    private fun rebuildMatcher() {
        try {
            val m = Matcher.build(store, lists)
            matcher = m
            upstream?.let { it.standardServers = Upstream.parse(STANDARD_RESOLVERS); it.filteredServers = Upstream.parse(FAMILY_RESOLVERS) }
            store.listDomainCount = lists.adultDomainCount()
            VeilLog.i("Rules rebuilt")
        } catch (t: Throwable) {
            VeilLog.e("Rules rebuild failed", t)
        }
    }

    // ------------------------------------------------------------------ packet loop

    private fun packetLoop(pfd: ParcelFileDescriptor) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(32767)
        while (running.get()) {
            val n = try {
                input.read(buf)
            } catch (e: IOException) {
                if (running.get()) VeilLog.w("Tunnel read ended: ${e.message}")
                break
            }
            if (n <= 0) { try { Thread.sleep(20) } catch (_: InterruptedException) { break }; continue }
            try {
                handlePacket(buf, n)
            } catch (t: Throwable) {
                VeilLog.e("Packet handling error", t)
            }
        }
        if (running.get()) {
            // The tunnel died underneath us (rare). Leave the service up so the user sees "not protected".
            stopProtection("tunnel closed")
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int) {
        val pkt = Packets.parse(buf, len) ?: return
        if (pkt.isUdp && pkt.dstPort == 53) {
            handleDns(pkt, buf)
        } else if (pkt.isTcp) {
            // TCP to a sinkholed resolver (DoT/DoH) or DNS-over-TCP: refuse immediately.
            Packets.tcpReset(pkt)?.let { writeToTun(it) }
        }
        // Anything else (e.g. QUIC to a sinkholed resolver) is dropped.
    }

    private fun handleDns(pkt: Packets.Packet, buf: ByteArray) {
        if (pkt.payloadLength < 12) return
        val payload = buf.copyOfRange(pkt.payloadOffset, pkt.payloadOffset + pkt.payloadLength)
        val q = DnsMessage.parseQuestion(payload, payload.size) ?: return
        val m = matcher
        val decision = m?.decide(q.name, q.type) ?: Decision.Allow
        when (decision) {
            is Decision.Block -> {
                val r = DnsMessage.nxDomain(payload, q)
                writeToTun(Packets.udpReply(pkt, r, r.size))
                onBlocked(q.name, decision)
                // In allowed-sites-only mode, unknown sites go to AI classification so safe ones open soon.
                if (decision.reason == Matcher.NOT_ALLOWED) EventQueue.unknownSite(q.name)
            }
            is Decision.NoData -> {
                val r = DnsMessage.noData(payload, q)
                writeToTun(Packets.udpReply(pkt, r, r.size))
            }
            is Decision.Rewrite -> rewrite(pkt, payload, q, decision.canonical)
            is Decision.AllowUnfiltered -> forward(pkt, payload, q, filtered = false)
            is Decision.Allow -> {
                if (q.type == DnsMessage.TYPE_A || q.type == DnsMessage.TYPE_AAAA) EventQueue.unknownSite(q.name)
                forward(pkt, payload, q, filtered = store.upstreamFamilyFilter && store.adultListEnabled)
            }
        }
    }

    private fun forward(pkt: Packets.Packet, payload: ByteArray, q: DnsMessage.Question, filtered: Boolean) {
        val up = upstream
        if (up == null) {
            val r = DnsMessage.servFail(payload, q)
            writeToTun(Packets.udpReply(pkt, r, r.size))
            return
        }
        up.forward(payload, payload.size, filtered) { resp, len ->
            if (resp == null) {
                val r = DnsMessage.servFail(payload, q)
                writeToTun(Packets.udpReply(pkt, r, r.size))
            } else {
                writeToTun(Packets.udpReply(pkt, resp, len))
            }
        }
    }

    /** SafeSearch: answer the question for e.g. www.google.com with forcesafesearch.google.com's addresses. */
    private fun rewrite(pkt: Packets.Packet, payload: ByteArray, q: DnsMessage.Question, canonical: String) {
        if (q.type != DnsMessage.TYPE_A && q.type != DnsMessage.TYPE_AAAA) {
            val r = DnsMessage.noData(payload, q)
            writeToTun(Packets.udpReply(pkt, r, r.size))
            return
        }
        val key = "$canonical/${q.type}"
        val now = System.currentTimeMillis()
        safeSearchCache[key]?.let { c ->
            if (c.expires > now) {
                val r = DnsMessage.answer(payload, q, c.records)
                writeToTun(Packets.udpReply(pkt, r, r.size))
                return
            }
        }
        val up = upstream
        if (up == null) {
            val r = DnsMessage.servFail(payload, q)
            writeToTun(Packets.udpReply(pkt, r, r.size))
            return
        }
        val canonQuery = DnsMessage.buildQuery(0, canonical, q.type)
        up.forward(canonQuery, canonQuery.size, false) { resp, len ->
            if (resp == null) {
                val r = DnsMessage.servFail(payload, q)
                writeToTun(Packets.udpReply(pkt, r, r.size))
            } else {
                val records = DnsMessage.parseAnswers(resp, len)
                if (records.isNotEmpty()) safeSearchCache[key] = CachedAnswer(records, System.currentTimeMillis() + 120_000)
                val r = DnsMessage.answer(payload, q, records)
                writeToTun(Packets.udpReply(pkt, r, r.size))
            }
        }
    }

    private fun writeToTun(packet: ByteArray) {
        val out = tunOut ?: return
        try {
            synchronized(outLock) { out.write(packet) }
        } catch (e: IOException) {
            if (!writeErrorLogged) { writeErrorLogged = true; VeilLog.w("Tunnel write failed: ${e.message}") }
        }
    }

    // ------------------------------------------------------------------ blocks & notifications

    private fun onBlocked(host: String, d: Decision.Block) {
        store.countBlock()
        val fresh = BlockLog.record(host, d.reason, d.rule)
        EventQueue.block(host, d.reason, d.rule)
        val now = System.currentTimeMillis()
        if (now - lastStatusUpdate > 15_000) { lastStatusUpdate = now; updateStatusNotification() }
        if (fresh && store.notifyOnBlock) {
            val last = blockNotifyTimes[host] ?: 0L
            if (now - last > 10 * 60_000 && now - lastBlockNotify > 20_000) {
                blockNotifyTimes[host] = now
                lastBlockNotify = now
                if (blockNotifyTimes.size > 500) blockNotifyTimes.clear()
                postBlockNotification(host, d.reason)
            }
        }
    }

    private fun mainPendingIntent(tab: String): PendingIntent {
        val i = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_TAB, tab)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(this, tab.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun statusNotification(): Notification {
        val today = store.blockedToday
        val admin = RemoteStore.get(this).let { if (it.isPaired) it.adminName.ifEmpty { "an admin" } else null }
        val text = when {
            !running.get() -> "Starting…"
            admin != null -> "Managed by $admin"   // never show counts or site names to a managed user
            today == 1L -> "1 site blocked today"
            else -> "$today sites blocked today"
        }
        return Notification.Builder(this, VeilApp.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_stat_veil)
            .setContentTitle(if (admin != null) "VEIL is protecting this phone · managed by $admin" else "VEIL is protecting this phone")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(mainPendingIntent("home"))
            .build()
    }

    private fun showStatusNotificationForeground() {
        val n = statusNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                try {
                    startForeground(NOTIF_STATUS, n, FGS_TYPE_SYSTEM_EXEMPTED)
                } catch (t: Throwable) {
                    VeilLog.w("systemExempted foreground type refused (${t.javaClass.simpleName}); using specialUse")
                    startForeground(NOTIF_STATUS, n, FGS_TYPE_SPECIAL_USE)
                }
            } else {
                startForeground(NOTIF_STATUS, n)
            }
        } catch (t: Throwable) {
            // Background-start restriction (e.g. system-started always-on VPN): keep going without
            // foreground status; the VPN itself keeps the process alive while the tunnel is up.
            VeilLog.w("startForeground refused: ${t.message}")
        }
    }

    private fun updateStatusNotification() {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_STATUS, statusNotification())
        } catch (t: Throwable) {
            VeilLog.w("Status notification update failed: ${t.message}")
        }
    }

    private fun postBlockNotification(host: String, reason: String) {
        try {
            val admin = RemoteStore.get(this).let { if (it.isPaired) it.adminName.ifEmpty { "your admin" } else null }
            val b = Notification.Builder(this, VeilApp.CHANNEL_BLOCKS)
                .setSmallIcon(R.drawable.ic_stat_veil)
                .setAutoCancel(true)
            if (admin != null) {
                // Managed phone: never reveal the site's name to the user.
                b.setContentTitle("A website was blocked")
                    .setContentText("Open VEIL to ask $admin to allow it.")
                    .setContentIntent(mainPendingIntent("home"))
            } else {
                b.setContentTitle("Blocked $host")
                    .setContentText("$reason · tap to review or allow")
                    .setContentIntent(mainPendingIntent("activity"))
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_BLOCK, b.build())
        } catch (t: Throwable) {
            VeilLog.w("Block notification failed: ${t.message}")
        }
    }

    private fun postAlert(title: String, text: String) {
        try {
            val n = Notification.Builder(this, VeilApp.CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_veil)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(mainPendingIntent("home"))
                .build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ALERT, n)
        } catch (t: Throwable) {
            VeilLog.w("Alert notification failed: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ bypass detection

    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val req = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                    checkPrivateDns(lp)
                }
            }
            cm.registerNetworkCallback(req, cb)
            networkCallback = cb
        } catch (t: Throwable) {
            VeilLog.w("Network callback registration failed: ${t.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        networkCallback = null
        try {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb)
        } catch (_: Throwable) {}
    }

    private fun checkPrivateDns(lp: LinkProperties) {
        if (Build.VERSION.SDK_INT < 28) return
        val host = lp.privateDnsServerName ?: ""
        val previous = store.privateDnsHost
        if (host.isNotEmpty() && host != previous) {
            store.privateDnsHost = host
            VeilLog.w("Private DNS is set to '$host' (strict mode); filtering may be bypassed until it is turned off")
            postAlert(
                "Private DNS is turned on",
                "Android's Private DNS is set to $host. VEIL cannot filter while it is on. Open Settings → Network & internet → Private DNS and choose Automatic or Off."
            )
        } else if (host.isEmpty() && previous.isNotEmpty()) {
            store.privateDnsHost = ""
            VeilLog.i("Private DNS strict mode is off again")
        }
    }

    private fun notifyState(on: Boolean) {
        mainHandler.post { stateListener?.invoke(on) }
    }

    fun diagnostics(): String = buildString {
        append("running=").append(running.get())
        append(" matcher=").append(matcher != null)
        upstream?.let { append(" forwarded=").append(it.queriesForwarded).append(" failed=").append(it.queriesFailed) }
        append(" safeSearchCache=").append(safeSearchCache.size)
    }

    companion object {
        const val ACTION_START = "app.veil.android.action.START"
        const val ACTION_STOP = "app.veil.android.action.STOP"

        const val NOTIF_STATUS = 1
        const val NOTIF_BLOCK = 2
        const val NOTIF_ALERT = 3

        /** ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED (API 34); VPN apps may use it. */
        private const val FGS_TYPE_SYSTEM_EXEMPTED = 1024
        /** ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE (API 34); fallback, declared in the manifest. */
        private const val FGS_TYPE_SPECIAL_USE = 0x40000000

        const val TUN_ADDRESS_V4 = "10.111.222.1"
        const val DNS_V4 = "10.111.222.2"
        const val TUN_ADDRESS_V6 = "fd42:7665:696c::1"

        val STANDARD_RESOLVERS = listOf("1.1.1.1", "1.0.0.1", "9.9.9.9")
        /** Cloudflare for Families (malware + adult content) as an optional extra layer. */
        val FAMILY_RESOLVERS = listOf("1.1.1.3", "1.0.0.3", "1.1.1.1")

        /** Public resolvers whose traffic is pulled into the tunnel and refused (DoH/DoT/plain). */
        val SINKHOLE_V4: List<Pair<String, Int>> = listOf(
            "8.8.8.8" to 32, "8.8.4.4" to 32,
            "1.1.1.1" to 32, "1.0.0.1" to 32, "1.1.1.2" to 32, "1.0.0.2" to 32, "1.1.1.3" to 32, "1.0.0.3" to 32,
            "9.9.9.9" to 32, "149.112.112.112" to 32, "9.9.9.10" to 32, "149.112.112.10" to 32, "9.9.9.11" to 32, "149.112.112.11" to 32,
            "208.67.222.222" to 32, "208.67.220.220" to 32, "208.67.222.123" to 32, "208.67.220.123" to 32,
            "94.140.14.14" to 32, "94.140.15.15" to 32, "94.140.14.15" to 32, "94.140.15.16" to 32, "94.140.14.140" to 32, "94.140.14.141" to 32,
            "45.90.28.0" to 24, "45.90.30.0" to 24,          // NextDNS
            "76.76.2.0" to 24, "76.76.10.0" to 24,           // Control D
            "185.228.168.0" to 24, "185.228.169.0" to 24,    // CleanBrowsing
            "194.242.2.0" to 24,                             // Mullvad DNS
            "193.110.81.0" to 24, "185.253.5.0" to 24,       // dns0.eu
            "64.6.64.6" to 32, "64.6.65.6" to 32,
            "8.26.56.26" to 32, "8.20.247.20" to 32,
            "77.88.8.8" to 32, "77.88.8.1" to 32, "77.88.8.88" to 32, "77.88.8.2" to 32, "77.88.8.7" to 32, "77.88.8.3" to 32,
            "223.5.5.5" to 32, "223.6.6.6" to 32, "119.29.29.29" to 32, "114.114.114.114" to 32, "180.76.76.76" to 32,
            "76.223.122.150" to 32, "76.223.118.150" to 32,
            "149.112.121.10" to 32, "149.112.122.10" to 32, "149.112.121.20" to 32, "149.112.122.20" to 32, "149.112.121.30" to 32, "149.112.122.30" to 32
        )

        val SINKHOLE_V6: List<Pair<String, Int>> = listOf(
            "2001:4860:4860::8888" to 128, "2001:4860:4860::8844" to 128,
            "2606:4700:4700::1111" to 128, "2606:4700:4700::1001" to 128, "2606:4700:4700::1112" to 128, "2606:4700:4700::1002" to 128,
            "2606:4700:4700::1113" to 128, "2606:4700:4700::1003" to 128,
            "2620:fe::fe" to 128, "2620:fe::9" to 128, "2620:fe::10" to 128, "2620:fe::fe:10" to 128, "2620:fe::11" to 128, "2620:fe::fe:11" to 128,
            "2620:119:35::35" to 128, "2620:119:53::53" to 128,
            "2a10:50c0::ad1:ff" to 128, "2a10:50c0::ad2:ff" to 128, "2a10:50c0::bad1:ff" to 128, "2a10:50c0::bad2:ff" to 128,
            "2a07:a8c0::" to 32, "2a07:a8c1::" to 32,        // NextDNS
            "2606:1a40::" to 48, "2606:1a40:1::" to 48,      // Control D
            "2a0d:2a00:1::" to 48, "2a0d:2a00:2::" to 48,    // CleanBrowsing
            "2a07:e340::" to 48,                             // Mullvad DNS
            "2a0f:fc80::" to 32, "2a0f:fc81::" to 32         // dns0.eu
        )

        @Volatile var isRunning: Boolean = false
        @Volatile var instance: VeilVpnService? = null
        /** Set by the UI to be told (on the main thread) when protection starts or stops. */
        @Volatile var stateListener: ((Boolean) -> Unit)? = null

        fun start(context: Context) {
            val i = Intent(context, VeilVpnService::class.java).setAction(ACTION_START)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            val i = Intent(context, VeilVpnService::class.java).setAction(ACTION_STOP)
            context.startForegroundService(i)
        }
    }
}

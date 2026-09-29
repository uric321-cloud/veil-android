package app.veil.android.dns

import app.veil.android.VeilLog
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Forwards allowed DNS queries to public resolvers over a socket the VPN protects
 * (so our own traffic never re-enters the tunnel), and matches replies back to the
 * query by transaction id. Two resolver sets: "standard" for everything, and
 * "filtered" (Cloudflare for Families) when the user opts into upstream adult
 * filtering as an extra layer. Allow-listed domains always use the standard set.
 */
class Upstream(private val protect: (DatagramSocket) -> Boolean) {

    private class Pending(
        val origId: Int,
        val payload: ByteArray,
        val filtered: Boolean,
        val callback: (ByteArray?, Int) -> Unit,
        @Volatile var sentAt: Long,
        @Volatile var attempts: Int
    )

    @Volatile var standardServers: List<InetAddress> = parse(listOf("1.1.1.1", "1.0.0.1", "9.9.9.9"))
    @Volatile var filteredServers: List<InetAddress> = parse(listOf("1.1.1.3", "1.0.0.3", "1.1.1.1"))

    private val pending = ConcurrentHashMap<Int, Pending>()
    private val running = AtomicBoolean(false)
    @Volatile private var socket: DatagramSocket? = null
    private var receiver: Thread? = null
    private var reaper: Thread? = null

    @Volatile var queriesForwarded = 0L
    @Volatile var queriesFailed = 0L

    fun start() {
        if (!running.compareAndSet(false, true)) return
        openSocket()
        receiver = Thread({ receiveLoop() }, "veil-upstream-rx").apply { isDaemon = true; start() }
        reaper = Thread({ reapLoop() }, "veil-upstream-reap").apply { isDaemon = true; start() }
    }

    fun stop() {
        running.set(false)
        socket?.close()
        socket = null
        for ((id, p) in pending) {
            pending.remove(id)
            try { p.callback(null, 0) } catch (_: Throwable) {}
        }
    }

    private fun openSocket() {
        try { socket?.close() } catch (_: Throwable) {}
        val s = DatagramSocket()
        if (!protect(s)) VeilLog.w("Upstream: protect() returned false; queries may loop into the tunnel")
        socket = s
    }

    fun forward(query: ByteArray, len: Int, filtered: Boolean, callback: (ByteArray?, Int) -> Unit) {
        val s = socket
        if (!running.get() || s == null) { callback(null, 0); return }
        val payload = query.copyOf(len)
        val origId = DnsMessage.id(payload)
        var id: Int
        do { id = Random.nextInt(1, 0xFFFF) } while (pending.containsKey(id))
        DnsMessage.setId(payload, id)
        val p = Pending(origId, payload, filtered, callback, System.currentTimeMillis(), 0)
        pending[id] = p
        send(p)
    }

    private fun servers(p: Pending): List<InetAddress> = if (p.filtered) filteredServers else standardServers

    private fun send(p: Pending) {
        val list = servers(p)
        if (list.isEmpty()) return
        val server = list[p.attempts % list.size]
        p.attempts += 1
        p.sentAt = System.currentTimeMillis()
        try {
            socket?.send(DatagramPacket(p.payload, p.payload.size, server, 53))
            queriesForwarded += 1
        } catch (e: IOException) {
            VeilLog.w("Upstream send failed to ${server.hostAddress}: ${e.message}")
            if (running.get()) try { openSocket() } catch (_: Throwable) {}
        }
    }

    private fun receiveLoop() {
        val buf = ByteArray(4096)
        while (running.get()) {
            val s = socket ?: break
            val dp = DatagramPacket(buf, buf.size)
            try {
                s.receive(dp)
            } catch (e: IOException) {
                if (!running.get()) break
                VeilLog.w("Upstream receive error: ${e.message}; reopening socket")
                try { openSocket() } catch (_: Throwable) { Thread.sleep(500) }
                continue
            }
            if (dp.length < 12) continue
            val id = DnsMessage.id(buf)
            val p = pending.remove(id) ?: continue
            val data = buf.copyOf(dp.length)
            DnsMessage.setId(data, p.origId)
            try { p.callback(data, dp.length) } catch (t: Throwable) { VeilLog.e("Upstream callback failed", t) }
        }
    }

    private fun reapLoop() {
        while (running.get()) {
            try { Thread.sleep(400) } catch (_: InterruptedException) { break }
            val now = System.currentTimeMillis()
            for ((id, p) in pending) {
                val age = now - p.sentAt
                if (age > 4500 || (age > 1500 && p.attempts >= 3)) {
                    if (pending.remove(id) != null) {
                        queriesFailed += 1
                        try { p.callback(null, 0) } catch (_: Throwable) {}
                    }
                } else if (age > 1500) {
                    send(p) // retry on the next server
                }
            }
        }
    }

    companion object {
        fun parse(hosts: List<String>): List<InetAddress> = hosts.mapNotNull {
            try { InetAddress.getByName(it) } catch (_: Exception) { null }
        }
    }
}

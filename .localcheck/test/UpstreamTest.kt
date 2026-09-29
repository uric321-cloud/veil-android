import app.veil.android.dns.DnsMessage
import app.veil.android.dns.Upstream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.net.InetAddress

fun resolve(up: Upstream, name: String, type: Int, filtered: Boolean): String {
    val q = DnsMessage.buildQuery(0x4242, name, type)
    val latch = CountDownLatch(1)
    var result = "timeout"
    up.forward(q, q.size, filtered) { resp, len ->
        result = if (resp == null) "null" else {
            val rc = DnsMessage.rcode(resp, len)
            val recs = DnsMessage.parseAnswers(resp, len)
            "rcode=$rc id=${DnsMessage.id(resp)} " + recs.joinToString(",") { InetAddress.getByAddress(it.rdata).hostAddress + "(ttl ${it.ttl})" }
        }
        latch.countDown()
    }
    latch.await(6, TimeUnit.SECONDS)
    return result
}

fun main() {
    val up = Upstream { true }
    up.start()
    val t0 = System.currentTimeMillis()
    for ((name, type) in listOf(
        "example.com" to 1, "example.com" to 28,
        "forcesafesearch.google.com" to 1, "forcesafesearch.google.com" to 28,
        "restrictmoderate.youtube.com" to 1, "restrict.youtube.com" to 28,
        "strict.bing.com" to 1, "safe.duckduckgo.com" to 1,
        "this-domain-does-not-exist-veil-test.com" to 1
    )) {
        println("$name/$type -> " + resolve(up, name, type, false))
    }
    println("--- family resolver ---")
    println("pornhub.com (family) -> " + resolve(up, "pornhub.com", 1, true))
    println("pornhub.com (standard) -> " + resolve(up, "pornhub.com", 1, false))
    println("wikipedia.org (family) -> " + resolve(up, "wikipedia.org", 1, true))
    // burst of 40 concurrent queries
    val latch = CountDownLatch(40)
    var ok = 0
    for (i in 0 until 40) {
        val q = DnsMessage.buildQuery(i, "cloudflare.com", 1)
        up.forward(q, q.size, false) { resp, len -> if (resp != null && DnsMessage.id(resp) == i) synchronized(up) { ok++ }; latch.countDown() }
    }
    latch.await(8, TimeUnit.SECONDS)
    println("burst: $ok/40 answered with matching ids in ${System.currentTimeMillis() - t0} ms total; forwarded=${up.queriesForwarded} failed=${up.queriesFailed}")
    up.stop()
}

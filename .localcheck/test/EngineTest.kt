import app.veil.android.dns.DnsMessage
import app.veil.android.rules.Decision
import app.veil.android.rules.DomainSet
import app.veil.android.rules.Matcher
import app.veil.android.rules.SafeSearch
import app.veil.android.vpn.Packets

var failures = 0
fun check(name: String, cond: Boolean) {
    if (cond) println("  ok   $name") else { failures++; println("  FAIL $name") }
}

fun u16(b: ByteArray, off: Int) = ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

fun ipChecksumOk(b: ByteArray): Boolean {
    var sum = 0L
    var i = 0
    while (i < 20) { sum += u16(b, i); i += 2 }
    while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
    return (sum and 0xFFFF) == 0xFFFFL
}

fun transportChecksumOk(b: ByteArray): Boolean {
    val proto = b[9].toInt() and 0xFF
    val total = u16(b, 2)
    val segLen = total - 20
    var sum = 0L
    var i = 12
    while (i < 20) { sum += u16(b, i); i += 2 }
    sum += proto; sum += segLen
    i = 20
    while (i + 1 < total) { sum += u16(b, i); i += 2 }
    if (i < total) sum += (b[i].toInt() and 0xFF) shl 8
    while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
    return (sum and 0xFFFF) == 0xFFFFL
}

fun main() {
    println("DNS codec")
    val q = DnsMessage.buildQuery(0x1234, "WWW.Example.COM", DnsMessage.TYPE_A)
    val parsed = DnsMessage.parseQuestion(q, q.size)
    check("query parses", parsed != null)
    check("name lowercased", parsed!!.name == "www.example.com")
    check("type A", parsed.type == DnsMessage.TYPE_A)
    check("id", parsed.id == 0x1234)
    check("question end", parsed.questionEnd == q.size)

    val nx = DnsMessage.nxDomain(q, parsed)
    check("nx id kept", DnsMessage.id(nx) == 0x1234)
    check("nx is response", (u16(nx, 2) and 0x8000) != 0)
    check("nx rcode 3", DnsMessage.rcode(nx, nx.size) == 3)
    check("nx RA set", (u16(nx, 2) and 0x0080) != 0)
    check("nx qd=1 an=0", u16(nx, 4) == 1 && u16(nx, 6) == 0)
    check("nx question copied", nx.size == q.size && nx.copyOfRange(12, nx.size).contentEquals(q.copyOfRange(12, q.size)))

    // Response with a compression pointer and two A records
    val resp = ByteArray(q.size + 2 * 16)
    System.arraycopy(q, 0, resp, 0, q.size)
    resp[2] = 0x81.toByte(); resp[3] = 0x80.toByte(); resp[6] = 0; resp[7] = 2
    var off = q.size
    for (ip in listOf(byteArrayOf(93, 184.toByte(), 216.toByte(), 34), byteArrayOf(1, 2, 3, 4))) {
        resp[off] = 0xC0.toByte(); resp[off + 1] = 0x0C
        resp[off + 2] = 0; resp[off + 3] = 1; resp[off + 4] = 0; resp[off + 5] = 1
        resp[off + 6] = 0; resp[off + 7] = 0; resp[off + 8] = 0x0E; resp[off + 9] = 0x10
        resp[off + 10] = 0; resp[off + 11] = 4
        System.arraycopy(ip, 0, resp, off + 12, 4)
        off += 16
    }
    val recs = DnsMessage.parseAnswers(resp, resp.size)
    check("two A records parsed", recs.size == 2 && recs[0].rdata.contentEquals(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)) && recs[0].ttl == 3600L)

    val synth = DnsMessage.answer(q, parsed, recs)
    val synthRecs = DnsMessage.parseAnswers(synth, synth.size)
    check("synthesized answer round-trips", synthRecs.size == 2 && synthRecs[1].rdata.contentEquals(byteArrayOf(1, 2, 3, 4)))
    check("synthesized name via pointer", DnsMessage.readName(synth, synth.size, q.size)?.first == "www.example.com")

    val aaaaQ = DnsMessage.buildQuery(7, "youtube.com", DnsMessage.TYPE_AAAA)
    val aaaaP = DnsMessage.parseQuestion(aaaaQ, aaaaQ.size)!!
    val onlyA = DnsMessage.answer(aaaaQ, aaaaP, recs)
    check("AAAA question gets no A records", u16(onlyA, 6) == 0)

    check("malformed rejected", DnsMessage.parseQuestion(ByteArray(5), 5) == null)
    check("response rejected as query", DnsMessage.parseQuestion(nx, nx.size) == null)

    println("Packets")
    // IPv4/UDP packet from 10.111.222.5:5353 to 10.111.222.2:53 carrying the query
    val udpLen = 8 + q.size
    val pkt = ByteArray(20 + udpLen)
    pkt[0] = 0x45; pkt[2] = ((20 + udpLen) shr 8).toByte(); pkt[3] = ((20 + udpLen) and 0xFF).toByte()
    pkt[8] = 64; pkt[9] = 17
    System.arraycopy(byteArrayOf(10, 111, 222.toByte(), 5), 0, pkt, 12, 4)
    System.arraycopy(byteArrayOf(10, 111, 222.toByte(), 2), 0, pkt, 16, 4)
    pkt[20] = 0x14; pkt[21] = 0xE9.toByte(); pkt[22] = 0; pkt[23] = 53
    pkt[24] = (udpLen shr 8).toByte(); pkt[25] = (udpLen and 0xFF).toByte()
    System.arraycopy(q, 0, pkt, 28, q.size)
    val p = Packets.parse(pkt, pkt.size)
    check("udp parsed", p != null && p.isUdp && p.srcPort == 5353 && p.dstPort == 53 && p.payloadLength == q.size)
    val reply = Packets.udpReply(p!!, nx, nx.size)
    val rp = Packets.parse(reply, reply.size)
    check("reply swaps endpoints", rp != null && rp.srcPort == 53 && rp.dstPort == 5353 && rp.dst.contentEquals(p.src) && rp.src.contentEquals(p.dst))
    check("reply ip checksum", ipChecksumOk(reply))
    check("reply udp checksum", transportChecksumOk(reply))
    check("reply payload", reply.copyOfRange(28, reply.size).contentEquals(nx))

    // TCP SYN to 1.1.1.1:853
    val tcp = ByteArray(40)
    tcp[0] = 0x45; tcp[2] = 0; tcp[3] = 40; tcp[8] = 64; tcp[9] = 6
    System.arraycopy(byteArrayOf(10, 111, 222.toByte(), 5), 0, tcp, 12, 4)
    System.arraycopy(byteArrayOf(1, 1, 1, 1), 0, tcp, 16, 4)
    tcp[20] = 0xC3.toByte(); tcp[21] = 0x50; tcp[22] = 0x03; tcp[23] = 0x55 // 50000 -> 853
    tcp[24] = 0x00; tcp[25] = 0x00; tcp[26] = 0x10; tcp[27] = 0x00 // seq 4096
    tcp[32] = 0x50; tcp[33] = 0x02 // SYN
    val tp = Packets.parse(tcp, tcp.size)
    check("tcp parsed", tp != null && tp.isTcp && tp.dstPort == 853 && tp.tcpSeq == 4096L && (tp.tcpFlags and 0x02) != 0)
    val rst = Packets.tcpReset(tp!!)
    check("rst built", rst != null && rst.size == 40)
    val rr = Packets.parse(rst!!, rst.size)
    check("rst flags RST+ACK", rr != null && (rr.tcpFlags and 0x14) == 0x14 && rr.tcpAck == 4097L)
    check("rst ip checksum", ipChecksumOk(rst))
    check("rst tcp checksum", transportChecksumOk(rst))

    // IPv6 UDP
    val v6 = ByteArray(40 + udpLen)
    v6[0] = 0x60; v6[4] = (udpLen shr 8).toByte(); v6[5] = (udpLen and 0xFF).toByte(); v6[6] = 17; v6[7] = 64
    v6[8] = 0xfd.toByte(); v6[9] = 0x42; v6[23] = 5
    v6[24] = 0x20; v6[25] = 0x01; v6[26] = 0x48; v6[27] = 0x60; v6[28] = 0x48; v6[29] = 0x60; v6[38] = 0x88.toByte(); v6[39] = 0x88.toByte()
    System.arraycopy(pkt, 20, v6, 40, udpLen)
    val p6 = Packets.parse(v6, v6.size)
    check("v6 udp parsed", p6 != null && p6.version == 6 && p6.dstPort == 53 && p6.payloadLength == q.size)
    val r6 = Packets.udpReply(p6!!, nx, nx.size)
    check("v6 reply size", r6.size == 40 + 8 + nx.size && (r6[0].toInt() shr 4 and 0xF) == 6)

    println("Rules")
    val adult = DomainSet(listOf("pornhub.com", "xvideos.com", "bad.example.org"))
    check("exact match", adult.match("pornhub.com") == "pornhub.com")
    check("subdomain match", adult.match("www.pornhub.com") == "pornhub.com")
    check("deep subdomain", adult.match("cdn.a.bad.example.org") == "bad.example.org")
    check("no match sibling", adult.match("good.example.org") == null)
    check("no tld match", adult.match("com") == null)
    check("no partial label", adult.match("notpornhub.com") == null)

    val m = Matcher(
        adult = adult,
        bypassHosts = DomainSet(listOf("dns.google", "cloudflare-dns.com")),
        customBlock = DomainSet(listOf("reddit.com")),
        customAllow = DomainSet(listOf("old.reddit.com", "youtube.com")),
        keywords = listOf("porn", "xxx"),
        safeSearch = true,
        youtubeStrict = false,
        stripHttpsRecords = true
    )
    check("allow wins over block", m.decide("old.reddit.com", 1) is Decision.AllowUnfiltered)
    check("custom block", (m.decide("www.reddit.com", 1) as? Decision.Block)?.reason == "Your block list")
    check("keyword", (m.decide("freeporn.example", 1) as? Decision.Block)?.rule == "porn")
    check("adult list", (m.decide("m.xvideos.com", 1) as? Decision.Block)?.reason == "Adult content list")
    check("bypass host", (m.decide("chrome.cloudflare-dns.com", 1) as? Decision.Block)?.reason == "Encrypted-DNS bypass")
    check("google rewrite", (m.decide("www.google.com", 1) as? Decision.Rewrite)?.canonical == "forcesafesearch.google.com")
    check("google co.uk rewrite", (m.decide("www.google.co.uk", 28) as? Decision.Rewrite)?.canonical == "forcesafesearch.google.com")
    check("mail.google not rewritten", m.decide("mail.google.com", 1) is Decision.Allow)
    check("youtube allow-listed skips safesearch", m.decide("www.youtube.com", 1) is Decision.AllowUnfiltered)
    check("bing rewrite", (m.decide("www.bing.com", 1) as? Decision.Rewrite)?.canonical == "strict.bing.com")
    check("https record stripped", m.decide("example.com", 65) is Decision.NoData)
    check("plain allow", m.decide("wikipedia.org", 1) is Decision.Allow)
    check("single label allowed", m.decide("printer", 1) is Decision.Allow)
    check("yt strict", SafeSearch.canonicalFor("youtubei.googleapis.com", true) == "restrict.youtube.com")

    println("Allowed sites only")
    val strict = Matcher(
        adult = adult, bypassHosts = DomainSet(listOf("dns.google")), customBlock = DomainSet(emptyList()),
        customAllow = DomainSet(listOf("school.example")), keywords = listOf("porn"), safeSearch = true,
        youtubeStrict = false, stripHttpsRecords = true, aiBlock = null, allowOnly = true,
        essentials = DomainSet(listOf("connectivitycheck.gstatic.com", "whatsapp.net")),
        aiAllow = DomainSet(listOf("gov.example"))
    )
    check("allowed site works", strict.decide("www.school.example", 1) is Decision.AllowUnfiltered)
    check("essential service works", strict.decide("connectivitycheck.gstatic.com", 1) is Decision.Allow)
    check("essential subdomain works", strict.decide("g.whatsapp.net", 1) is Decision.Allow)
    check("AI-safe site works", strict.decide("forms.gov.example", 1) is Decision.Allow)
    val other = strict.decide("news.example", 1)
    check("anything else is blocked", other is Decision.Block && other.reason == Matcher.NOT_ALLOWED)
    check("bypass still wins over essentials logic", strict.decide("dns.google", 1).let { it is Decision.Block && it.reason != Matcher.NOT_ALLOWED })
    check("keyword still blocks an AI-safe name", strict.decide("porn.gov.example", 1) is Decision.Block)

    println(if (failures == 0) "ALL PASSED" else "$failures FAILURES")
    if (failures > 0) System.exit(1)
}

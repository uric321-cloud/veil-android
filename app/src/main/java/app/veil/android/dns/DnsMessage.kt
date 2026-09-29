package app.veil.android.dns

/**
 * Minimal DNS wire-format helpers: parse the question of a query, and build
 * NXDOMAIN / NODATA / SERVFAIL / synthesized-answer responses for it.
 * Everything is plain byte arithmetic; no allocations beyond the result.
 */
object DnsMessage {
    const val TYPE_A = 1
    const val TYPE_AAAA = 28
    const val TYPE_HTTPS = 65 // SVCB/HTTPS records carry ECH keys; we strip them.

    const val RCODE_NOERROR = 0
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    class Question(
        val id: Int,
        val flags: Int,
        val name: String,      // lowercase, no trailing dot
        val type: Int,
        val qclass: Int,
        val questionEnd: Int,  // offset just past the question section
        val qdCount: Int
    )

    class Record(val type: Int, val ttl: Long, val rdata: ByteArray)

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, off: Int): Long =
        ((u16(b, off).toLong()) shl 16) or (u16(b, off + 2).toLong() and 0xFFFF)

    /** Parses the first question. Returns null for anything malformed or not a query. */
    fun parseQuestion(b: ByteArray, len: Int): Question? {
        if (len < 12 + 5) return null
        val flags = u16(b, 2)
        if (flags and 0x8000 != 0) return null // a response, not a query
        val qd = u16(b, 4)
        if (qd < 1) return null
        val nameResult = readName(b, len, 12) ?: return null
        val name = nameResult.first
        val off = nameResult.second
        if (off + 4 > len) return null
        val type = u16(b, off)
        val qclass = u16(b, off + 2)
        return Question(u16(b, 0), flags, name, type, qclass, off + 4, qd)
    }

    /**
     * Reads a (possibly compressed) name at [start]. Returns the name and the offset
     * just after the name as it appears at [start] (i.e. after the first pointer).
     */
    fun readName(b: ByteArray, len: Int, start: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var off = start
        var endAfterFirst = -1
        var hops = 0
        var labelBytes = 0
        while (true) {
            if (off >= len) return null
            val l = b[off].toInt() and 0xFF
            when {
                l == 0 -> {
                    off += 1
                    break
                }
                l and 0xC0 == 0xC0 -> {
                    if (off + 1 >= len) return null
                    val ptr = ((l and 0x3F) shl 8) or (b[off + 1].toInt() and 0xFF)
                    if (endAfterFirst < 0) endAfterFirst = off + 2
                    if (++hops > 16 || ptr >= len) return null
                    off = ptr
                }
                l and 0xC0 != 0 -> return null // unsupported label type
                else -> {
                    if (off + 1 + l > len) return null
                    labelBytes += l + 1
                    if (labelBytes > 255) return null
                    if (sb.isNotEmpty()) sb.append('.')
                    for (i in 0 until l) {
                        val c = b[off + 1 + i].toInt() and 0xFF
                        sb.append(if (c in 65..90) (c + 32).toChar() else c.toChar())
                    }
                    off += 1 + l
                }
            }
        }
        return Pair(sb.toString(), if (endAfterFirst >= 0) endAfterFirst else off)
    }

    /** Skips a name at [start] and returns the offset after it (following no pointers). */
    private fun skipName(b: ByteArray, len: Int, start: Int): Int {
        var off = start
        while (off < len) {
            val l = b[off].toInt() and 0xFF
            if (l == 0) return off + 1
            if (l and 0xC0 == 0xC0) return off + 2
            off += 1 + l
        }
        return -1
    }

    /** Extracts A/AAAA records from the answer section of a response. */
    fun parseAnswers(b: ByteArray, len: Int): List<Record> {
        val out = ArrayList<Record>()
        if (len < 12) return out
        val qd = u16(b, 4)
        val an = u16(b, 6)
        var off = 12
        for (i in 0 until qd) {
            off = skipName(b, len, off)
            if (off < 0 || off + 4 > len) return out
            off += 4
        }
        for (i in 0 until an) {
            off = skipName(b, len, off)
            if (off < 0 || off + 10 > len) return out
            val type = u16(b, off)
            val ttl = u32(b, off + 4)
            val rdlen = u16(b, off + 8)
            off += 10
            if (off + rdlen > len) return out
            if (type == TYPE_A && rdlen == 4 || type == TYPE_AAAA && rdlen == 16) {
                out.add(Record(type, ttl, b.copyOfRange(off, off + rdlen)))
            }
            off += rdlen
        }
        return out
    }

    /** Response header + copied question section, with the given RCODE and ANCOUNT. */
    private fun header(query: ByteArray, q: Question, rcode: Int, anCount: Int): ByteArray {
        val qlen = q.questionEnd - 12
        val out = ByteArray(12 + qlen)
        out[0] = query[0]
        out[1] = query[1]
        // QR=1, keep opcode, AA=0, TC=0, copy RD, RA=1, Z=0, RCODE
        val flags = 0x8000 or (q.flags and 0x7800) or (q.flags and 0x0100) or 0x0080 or (rcode and 0xF)
        out[2] = (flags shr 8).toByte()
        out[3] = (flags and 0xFF).toByte()
        out[4] = 0; out[5] = 1                      // QDCOUNT = 1
        out[6] = (anCount shr 8).toByte(); out[7] = (anCount and 0xFF).toByte()
        out[8] = 0; out[9] = 0                      // NSCOUNT
        out[10] = 0; out[11] = 0                    // ARCOUNT (EDNS OPT dropped)
        System.arraycopy(query, 12, out, 12, qlen)
        return out
    }

    fun nxDomain(query: ByteArray, q: Question): ByteArray = header(query, q, RCODE_NXDOMAIN, 0)

    fun noData(query: ByteArray, q: Question): ByteArray = header(query, q, RCODE_NOERROR, 0)

    fun servFail(query: ByteArray, q: Question): ByteArray = header(query, q, RCODE_SERVFAIL, 0)

    /**
     * Builds a NOERROR response for the original question carrying the given
     * A/AAAA records (only those matching the question type are included).
     */
    fun answer(query: ByteArray, q: Question, records: List<Record>, ttl: Long = 60): ByteArray {
        val matching = records.filter { it.type == q.type }
        val head = header(query, q, RCODE_NOERROR, matching.size)
        var size = head.size
        for (r in matching) size += 12 + r.rdata.size
        val out = ByteArray(size)
        System.arraycopy(head, 0, out, 0, head.size)
        var off = head.size
        for (r in matching) {
            out[off] = 0xC0.toByte(); out[off + 1] = 0x0C            // name = pointer to question
            out[off + 2] = (r.type shr 8).toByte(); out[off + 3] = (r.type and 0xFF).toByte()
            out[off + 4] = 0; out[off + 5] = 1                        // class IN
            val t = if (ttl < 0) 0 else ttl
            out[off + 6] = (t shr 24).toByte(); out[off + 7] = (t shr 16).toByte()
            out[off + 8] = (t shr 8).toByte(); out[off + 9] = t.toByte()
            out[off + 10] = (r.rdata.size shr 8).toByte(); out[off + 11] = (r.rdata.size and 0xFF).toByte()
            System.arraycopy(r.rdata, 0, out, off + 12, r.rdata.size)
            off += 12 + r.rdata.size
        }
        return out
    }

    /** Builds a fresh query (new id) for [name] with the given type; used for SafeSearch lookups. */
    fun buildQuery(id: Int, name: String, type: Int): ByteArray {
        val labels = name.trim('.').split('.')
        var nameLen = 1
        for (l in labels) nameLen += 1 + l.length
        val out = ByteArray(12 + nameLen + 4)
        out[0] = (id shr 8).toByte(); out[1] = (id and 0xFF).toByte()
        out[2] = 0x01; out[3] = 0x00           // RD = 1
        out[4] = 0; out[5] = 1                 // QDCOUNT = 1
        var off = 12
        for (l in labels) {
            out[off++] = l.length.toByte()
            for (ch in l) out[off++] = ch.code.toByte()
        }
        out[off++] = 0
        out[off++] = (type shr 8).toByte(); out[off++] = (type and 0xFF).toByte()
        out[off++] = 0; out[off] = 1           // class IN
        return out
    }

    /** Rewrites the transaction id in place. */
    fun setId(b: ByteArray, id: Int) {
        b[0] = (id shr 8).toByte()
        b[1] = (id and 0xFF).toByte()
    }

    fun id(b: ByteArray): Int = u16(b, 0)

    fun rcode(b: ByteArray, len: Int): Int = if (len < 4) -1 else (b[3].toInt() and 0x0F)
}

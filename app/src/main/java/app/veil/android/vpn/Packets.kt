package app.veil.android.vpn

import java.net.InetAddress

/**
 * IPv4/IPv6 + UDP/TCP parsing and building for the few packet shapes the tunnel
 * sees: DNS over UDP (answered or forwarded) and TCP to sinkholed resolvers
 * (answered with RST so apps fail fast instead of hanging).
 */
object Packets {
    const val PROTO_TCP = 6
    const val PROTO_UDP = 17

    class Packet(
        val version: Int,
        val proto: Int,
        val src: ByteArray,
        val dst: ByteArray,
        val transportOffset: Int,
        val srcPort: Int,
        val dstPort: Int,
        val payloadOffset: Int,
        val payloadLength: Int,
        // TCP only
        val tcpSeq: Long,
        val tcpAck: Long,
        val tcpFlags: Int
    ) {
        val isUdp get() = proto == PROTO_UDP
        val isTcp get() = proto == PROTO_TCP
        fun dstAddress(): String = try { InetAddress.getByAddress(dst).hostAddress ?: "?" } catch (_: Exception) { "?" }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, off: Int): Long =
        (u16(b, off).toLong() shl 16) or u16(b, off + 2).toLong()

    private fun put16(b: ByteArray, off: Int, v: Int) {
        b[off] = (v shr 8).toByte(); b[off + 1] = (v and 0xFF).toByte()
    }

    private fun put32(b: ByteArray, off: Int, v: Long) {
        b[off] = (v shr 24).toByte(); b[off + 1] = (v shr 16).toByte()
        b[off + 2] = (v shr 8).toByte(); b[off + 3] = v.toByte()
    }

    fun parse(b: ByteArray, len: Int): Packet? {
        if (len < 20) return null
        val version = (b[0].toInt() and 0xF0) shr 4
        val proto: Int
        val src: ByteArray
        val dst: ByteArray
        val tOff: Int
        if (version == 4) {
            val ihl = (b[0].toInt() and 0x0F) * 4
            if (ihl < 20 || ihl > len) return null
            val total = u16(b, 2)
            if (total > len) return null
            if (u16(b, 6) and 0x1FFF != 0) return null // fragment: ignore
            proto = b[9].toInt() and 0xFF
            src = b.copyOfRange(12, 16)
            dst = b.copyOfRange(16, 20)
            tOff = ihl
        } else if (version == 6) {
            if (len < 40) return null
            proto = b[6].toInt() and 0xFF
            src = b.copyOfRange(8, 24)
            dst = b.copyOfRange(24, 40)
            tOff = 40
            // Extension headers are not handled; such packets are simply dropped.
        } else return null

        return when (proto) {
            PROTO_UDP -> {
                if (tOff + 8 > len) return null
                val udpLen = u16(b, tOff + 4)
                val payloadLen = minOf(udpLen - 8, len - tOff - 8)
                if (payloadLen < 0) return null
                Packet(version, proto, src, dst, tOff, u16(b, tOff), u16(b, tOff + 2), tOff + 8, payloadLen, 0, 0, 0)
            }
            PROTO_TCP -> {
                if (tOff + 20 > len) return null
                val dataOff = ((b[tOff + 12].toInt() and 0xF0) shr 4) * 4
                val payloadLen = maxOf(0, len - tOff - dataOff)
                Packet(version, proto, src, dst, tOff, u16(b, tOff), u16(b, tOff + 2), tOff + dataOff, payloadLen,
                    u32(b, tOff + 4), u32(b, tOff + 8), b[tOff + 13].toInt() and 0xFF)
            }
            else -> Packet(version, proto, src, dst, tOff, 0, 0, tOff, 0, 0, 0, 0)
        }
    }

    /** Builds a UDP datagram from [pkt]'s destination back to its source carrying [payload]. */
    fun udpReply(pkt: Packet, payload: ByteArray, payloadLen: Int): ByteArray {
        return if (pkt.version == 4) buildV4(pkt.dst, pkt.src, PROTO_UDP, udpSegment(pkt.dstPort, pkt.srcPort, payload, payloadLen))
        else buildV6(pkt.dst, pkt.src, PROTO_UDP, udpSegment(pkt.dstPort, pkt.srcPort, payload, payloadLen))
    }

    /** Builds a TCP RST that closes the connection [pkt] belongs to. Returns null if RST itself. */
    fun tcpReset(pkt: Packet): ByteArray? {
        if (pkt.tcpFlags and 0x04 != 0) return null // never answer a RST
        val seg = ByteArray(20)
        put16(seg, 0, pkt.dstPort)
        put16(seg, 2, pkt.srcPort)
        val ackSet = pkt.tcpFlags and 0x10 != 0
        if (ackSet) {
            put32(seg, 4, pkt.tcpAck)              // seq = their ack
            put32(seg, 8, 0)
            seg[13] = 0x04                         // RST
        } else {
            val synLen = if (pkt.tcpFlags and 0x02 != 0) 1 else 0
            val finLen = if (pkt.tcpFlags and 0x01 != 0) 1 else 0
            put32(seg, 4, 0)
            put32(seg, 8, (pkt.tcpSeq + pkt.payloadLength + synLen + finLen) and 0xFFFFFFFFL)
            seg[13] = 0x14                         // RST + ACK
        }
        seg[12] = 0x50                             // data offset 5 words
        put16(seg, 14, 0)                          // window
        return if (pkt.version == 4) buildV4(pkt.dst, pkt.src, PROTO_TCP, seg)
        else buildV6(pkt.dst, pkt.src, PROTO_TCP, seg)
    }

    private fun udpSegment(srcPort: Int, dstPort: Int, payload: ByteArray, payloadLen: Int): ByteArray {
        val seg = ByteArray(8 + payloadLen)
        put16(seg, 0, srcPort)
        put16(seg, 2, dstPort)
        put16(seg, 4, 8 + payloadLen)
        System.arraycopy(payload, 0, seg, 8, payloadLen)
        return seg
    }

    private var ipId = 0

    private fun buildV4(src: ByteArray, dst: ByteArray, proto: Int, segment: ByteArray): ByteArray {
        val total = 20 + segment.size
        val out = ByteArray(total)
        out[0] = 0x45
        out[1] = 0
        put16(out, 2, total)
        ipId = (ipId + 1) and 0xFFFF
        put16(out, 4, ipId)
        put16(out, 6, 0x4000)                      // DF, no fragments
        out[8] = 64
        out[9] = proto.toByte()
        System.arraycopy(src, 0, out, 12, 4)
        System.arraycopy(dst, 0, out, 16, 4)
        put16(out, 10, checksum(out, 0, 20, 0))
        System.arraycopy(segment, 0, out, 20, segment.size)
        // Transport checksum over pseudo-header + segment
        val pseudo = pseudoSum(src, dst, proto, segment.size)
        val csumOff = if (proto == PROTO_TCP) 16 else 6
        put16(out, 20 + csumOff, 0)
        var c = checksum(out, 20, segment.size, pseudo)
        if (proto == PROTO_UDP && c == 0) c = 0xFFFF
        put16(out, 20 + csumOff, c)
        return out
    }

    private fun buildV6(src: ByteArray, dst: ByteArray, proto: Int, segment: ByteArray): ByteArray {
        val out = ByteArray(40 + segment.size)
        out[0] = 0x60
        put16(out, 4, segment.size)
        out[6] = proto.toByte()
        out[7] = 64
        System.arraycopy(src, 0, out, 8, 16)
        System.arraycopy(dst, 0, out, 24, 16)
        System.arraycopy(segment, 0, out, 40, segment.size)
        val pseudo = pseudoSum(src, dst, proto, segment.size)
        val csumOff = if (proto == PROTO_TCP) 16 else 6
        put16(out, 40 + csumOff, 0)
        var c = checksum(out, 40, segment.size, pseudo)
        if (proto == PROTO_UDP && c == 0) c = 0xFFFF
        put16(out, 40 + csumOff, c)
        return out
    }

    /** Sum of the pseudo-header words (not yet folded). */
    private fun pseudoSum(src: ByteArray, dst: ByteArray, proto: Int, length: Int): Long {
        var sum = 0L
        var i = 0
        while (i < src.size) { sum += u16(src, i); i += 2 }
        i = 0
        while (i < dst.size) { sum += u16(dst, i); i += 2 }
        sum += proto
        sum += length
        return sum
    }

    /** Internet checksum of [len] bytes at [off], seeded with [seed]. */
    private fun checksum(b: ByteArray, off: Int, len: Int, seed: Long): Int {
        var sum = seed
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += u16(b, i)
            i += 2
        }
        if (i < end) sum += (b[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }
}

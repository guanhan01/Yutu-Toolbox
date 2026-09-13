package com.mcp.toolbox.feature.capture.net

/** IP 协议号。 */
object IpProto {
    const val ICMP = 1
    const val TCP = 6
    const val UDP = 17
}

const val TCP_FIN = 0x01
const val TCP_SYN = 0x02
const val TCP_RST = 0x04
const val TCP_PSH = 0x08
const val TCP_ACK = 0x10

internal fun u8(b: ByteArray, o: Int): Int = b[o].toInt() and 0xFF

internal fun u16(b: ByteArray, o: Int): Int = (u8(b, o) shl 8) or u8(b, o + 1)

internal fun u32(b: ByteArray, o: Int): Long =
    (u8(b, o).toLong() shl 24) or (u8(b, o + 1).toLong() shl 16) or
        (u8(b, o + 2).toLong() shl 8) or u8(b, o + 3).toLong()

internal fun put8(b: ByteArray, o: Int, v: Int) {
    b[o] = (v and 0xFF).toByte()
}

internal fun put16(b: ByteArray, o: Int, v: Int) {
    put8(b, o, v shr 8)
    put8(b, o + 1, v)
}

internal fun put32(b: ByteArray, o: Int, v: Long) {
    put16(b, o, (v shr 16).toInt())
    put16(b, o + 2, v.toInt())
}

fun ipToText(ip: Int): String =
    "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

fun ipFromText(text: String): Int? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    var v = 0
    for (p in parts) {
        val n = p.toIntOrNull() ?: return null
        if (n !in 0..255) return null
        v = (v shl 8) or n
    }
    return v
}

fun ipToBytes(ip: Int): ByteArray = byteArrayOf(
    ((ip ushr 24) and 0xFF).toByte(),
    ((ip ushr 16) and 0xFF).toByte(),
    ((ip ushr 8) and 0xFF).toByte(),
    (ip and 0xFF).toByte(),
)

fun ipFromBytes(b: ByteArray): Int =
    (u8(b, 0) shl 24) or (u8(b, 1) shl 16) or (u8(b, 2) shl 8) or u8(b, 3)

/** 五元组。 */
data class FlowKey(val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int, val protocol: Int) {
    val endpoint: String get() = "${ipToText(dstIp)}:$dstPort"
    val protocolName: String get() = if (protocol == IpProto.TCP) "TCP" else "UDP"
}

/** 一个 IPv4 报文视图，直接引用 tun 读缓冲区，不复制。 */
class Ipv4Packet(val buf: ByteArray) {
    var length = 0
    var protocol = 0
    var srcIp = 0
    var dstIp = 0
    var ttl = 0
    var ipId = 0
    var srcPort = 0
    var dstPort = 0
    var seq = 0L
    var ack = 0L
    var flags = 0
    var window = 0
    var mss = 1400
    var payloadOffset = 0
    var payloadLength = 0
    var ipHeaderLength = 20

    val isSyn get() = flags and TCP_SYN != 0
    val isAck get() = flags and TCP_ACK != 0
    val isFin get() = flags and TCP_FIN != 0
    val isRst get() = flags and TCP_RST != 0

    fun parse(len: Int): Boolean {
        if (len < 20 || (buf[0].toInt() and 0xF0) != 0x40) return false
        ipHeaderLength = (buf[0].toInt() and 0x0F) * 4
        if (ipHeaderLength < 20 || len < ipHeaderLength) return false
        length = u16(buf, 2)
        if (length > len || length < ipHeaderLength) length = len
        ipId = u16(buf, 4)
        ttl = u8(buf, 8)
        protocol = u8(buf, 9)
        srcIp = (u8(buf, 12) shl 24) or (u8(buf, 13) shl 16) or (u8(buf, 14) shl 8) or u8(buf, 15)
        dstIp = (u8(buf, 16) shl 24) or (u8(buf, 17) shl 16) or (u8(buf, 18) shl 8) or u8(buf, 19)
        val o = ipHeaderLength
        when (protocol) {
            IpProto.TCP -> {
                if (length - o < 20) return false
                srcPort = u16(buf, o)
                dstPort = u16(buf, o + 2)
                seq = u32(buf, o + 4)
                ack = u32(buf, o + 8)
                val dataOffset = (u8(buf, o + 12) shr 4) * 4
                if (dataOffset < 20) return false
                flags = u8(buf, o + 13) and 0x3F
                window = u16(buf, o + 14)
                payloadOffset = o + dataOffset
                payloadLength = (length - o - dataOffset).coerceAtLeast(0)
                if (payloadOffset + payloadLength > len) payloadLength = (len - payloadOffset).coerceAtLeast(0)
                if (isSyn && dataOffset > 20) parseMss(o + 20, o + dataOffset)
            }
            IpProto.UDP -> {
                if (length - o < 8) return false
                srcPort = u16(buf, o)
                dstPort = u16(buf, o + 2)
                val udpLength = u16(buf, o + 4)
                payloadOffset = o + 8
                payloadLength = (udpLength - 8).coerceAtLeast(0)
                if (payloadOffset + payloadLength > len) payloadLength = (len - payloadOffset).coerceAtLeast(0)
            }
            else -> {
                payloadOffset = o
                payloadLength = 0
            }
        }
        return true
    }

    private fun parseMss(from: Int, to: Int) {
        var i = from
        while (i < to) {
            when (u8(buf, i)) {
                0 -> return
                1 -> i += 1
                2 -> {
                    if (i + 3 >= to) return
                    mss = u16(buf, i + 2)
                    i += u8(buf, i + 1).coerceAtLeast(1)
                }
                else -> i += u8(buf, i + 1).coerceAtLeast(1)
            }
        }
    }
}

private fun pseudoHeaderSum(srcIp: Int, dstIp: Int, protocol: Int, length: Int): Long =
    ((srcIp ushr 16) and 0xFFFF).toLong() + (srcIp and 0xFFFF).toLong() +
        ((dstIp ushr 16) and 0xFFFF).toLong() + (dstIp and 0xFFFF).toLong() +
        protocol.toLong() + length.toLong()

private fun sumOf(buf: ByteArray, offset: Int, length: Int, initial: Long): Long {
    var sum = initial
    var i = offset
    val end = offset + length
    while (i + 1 < end) {
        sum += ((u8(buf, i) shl 8) or u8(buf, i + 1)).toLong()
        i += 2
    }
    if (i < end) sum += (u8(buf, i) shl 8).toLong()
    return sum
}

private fun foldChecksum(sum: Long): Int {
    var s = sum
    while ((s ushr 16) != 0L) s = (s and 0xFFFF) + (s ushr 16)
    return (s.inv() and 0xFFFFL).toInt()
}

/** 构造 IPv4 + TCP 报文写入 out，返回总长度。 */
fun buildIpv4Tcp(
    out: ByteArray,
    srcIp: Int,
    dstIp: Int,
    srcPort: Int,
    dstPort: Int,
    seq: Long,
    ack: Long,
    flags: Int,
    window: Int,
    payload: ByteArray?,
    payloadOffset: Int,
    payloadLength: Int,
    ipId: Int,
    ttl: Int = 64,
    mss: Int = 1400,
): Int {
    val withOptions = flags and TCP_SYN != 0
    val tcpHeaderLength = if (withOptions) 24 else 20
    val total = 20 + tcpHeaderLength + payloadLength
    put8(out, 0, 0x45)
    put8(out, 1, 0)
    put16(out, 2, total)
    put16(out, 4, ipId)
    put16(out, 6, 0x4000)
    put8(out, 8, ttl)
    put8(out, 9, IpProto.TCP)
    put16(out, 10, 0)
    put16(out, 12, srcIp ushr 16)
    put16(out, 14, srcIp)
    put16(out, 16, dstIp ushr 16)
    put16(out, 18, dstIp)
    put16(out, 10, foldChecksum(sumOf(out, 0, 20, 0)))

    val o = 20
    put16(out, o, srcPort)
    put16(out, o + 2, dstPort)
    put32(out, o + 4, seq)
    put32(out, o + 8, ack)
    put8(out, o + 12, (tcpHeaderLength / 4) shl 4)
    put8(out, o + 13, flags)
    put16(out, o + 14, window)
    put16(out, o + 16, 0)
    put16(out, o + 18, 0)
    if (withOptions) {
        put8(out, o + 20, 2)
        put8(out, o + 21, 4)
        put16(out, o + 22, mss)
    }
    if (payload != null && payloadLength > 0) {
        System.arraycopy(payload, payloadOffset, out, o + tcpHeaderLength, payloadLength)
    }
    val segmentLength = tcpHeaderLength + payloadLength
    val csum = foldChecksum(
        sumOf(out, o, segmentLength, pseudoHeaderSum(srcIp, dstIp, IpProto.TCP, segmentLength)),
    )
    put16(out, o + 16, csum)
    return total
}

/** 构造 IPv4 + UDP 报文写入 out，返回总长度。 */
fun buildIpv4Udp(
    out: ByteArray,
    srcIp: Int,
    dstIp: Int,
    srcPort: Int,
    dstPort: Int,
    payload: ByteArray,
    payloadOffset: Int,
    payloadLength: Int,
    ipId: Int,
    ttl: Int = 64,
): Int {
    val total = 20 + 8 + payloadLength
    put8(out, 0, 0x45)
    put8(out, 1, 0)
    put16(out, 2, total)
    put16(out, 4, ipId)
    put16(out, 6, 0x4000)
    put8(out, 8, ttl)
    put8(out, 9, IpProto.UDP)
    put16(out, 10, 0)
    put16(out, 12, srcIp ushr 16)
    put16(out, 14, srcIp)
    put16(out, 16, dstIp ushr 16)
    put16(out, 18, dstIp)
    put16(out, 10, foldChecksum(sumOf(out, 0, 20, 0)))

    val o = 20
    put16(out, o, srcPort)
    put16(out, o + 2, dstPort)
    put16(out, o + 4, 8 + payloadLength)
    put16(out, o + 6, 0)
    System.arraycopy(payload, payloadOffset, out, o + 8, payloadLength)
    val segmentLength = 8 + payloadLength
    val csum = foldChecksum(
        sumOf(out, o, segmentLength, pseudoHeaderSum(srcIp, dstIp, IpProto.UDP, segmentLength)),
    )
    put16(out, o + 6, if (csum == 0) 0xFFFF else csum)
    return total
}

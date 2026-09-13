package com.mcp.toolbox.feature.capture.net

/** TLS ClientHello 里明文可见的信息（不做解密，只读握手）。 */
data class TlsHelloInfo(
    val sni: String?,
    val legacyVersion: String?,
    val alpn: List<String>,
    val handshakeLength: Int,
)

object TlsParser {
    fun clientHello(buf: ByteArray, offset: Int, length: Int): TlsHelloInfo? {
        var o = offset
        val end = offset + length
        if (length < 6 || u8(buf, o) != 0x16) return null
        val recordVersion = tlsVersion(u16(buf, o + 1))
        o += 5
        if (o + 4 > end || u8(buf, o) != 0x01) return null
        val hsLength = (u8(buf, o + 1) shl 16) or (u8(buf, o + 2) shl 8) or u8(buf, o + 3)
        o += 4
        var sni: String? = null
        val alpn = mutableListOf<String>()
        if (o + 34 > end) return TlsHelloInfo(null, recordVersion, alpn, hsLength)
        o += 34
        if (o + 1 > end) return TlsHelloInfo(null, recordVersion, alpn, hsLength)
        o += 1 + u8(buf, o)
        if (o + 2 > end) return TlsHelloInfo(null, recordVersion, alpn, hsLength)
        o += 2 + u16(buf, o)
        if (o + 1 > end) return TlsHelloInfo(null, recordVersion, alpn, hsLength)
        o += 1 + u8(buf, o)
        if (o + 2 > end) return TlsHelloInfo(null, recordVersion, alpn, hsLength)
        val extensionsEnd = minOf(end, o + 2 + u16(buf, o))
        o += 2
        while (o + 4 <= extensionsEnd) {
            val type = u16(buf, o)
            val extLength = u16(buf, o + 2)
            val extOffset = o + 4
            val extEnd = minOf(extensionsEnd, extOffset + extLength)
            when (type) {
                0x0000 -> sni = parseSni(buf, extOffset, extEnd)
                0x0010 -> alpn.addAll(parseAlpn(buf, extOffset, extEnd))
            }
            if (extLength <= 0) break
            o = extOffset + extLength
        }
        return TlsHelloInfo(sni, recordVersion, alpn, hsLength)
    }

    private fun parseSni(buf: ByteArray, offset: Int, end: Int): String? {
        if (offset + 2 > end) return null
        var o = offset + 2
        if (o + 3 > end) return null
        val nameType = u8(buf, o)
        val nameLength = u16(buf, o + 1)
        o += 3
        if (nameType != 0 || nameLength <= 0 || o + nameLength > end) return null
        return String(buf, o, nameLength, Charsets.US_ASCII)
    }

    private fun parseAlpn(buf: ByteArray, offset: Int, end: Int): List<String> {
        val result = mutableListOf<String>()
        if (offset + 2 > end) return result
        var o = offset + 2
        while (o < end) {
            val len = u8(buf, o)
            o += 1
            if (len <= 0 || o + len > end) break
            result.add(String(buf, o, len, Charsets.US_ASCII))
            o += len
        }
        return result
    }

    /** ClientHello 只有 legacy_version；0x0301 是 TLS 1.2/1.3 也会照发的兼容值。 */
    fun tlsVersion(value: Int): String? = when (value) {
        0x0301 -> "0x0301（ClientHello 兼容值，实际多为 TLS 1.2/1.3）"
        0x0302 -> "0x0302（TLS 1.1 legacy）"
        0x0303 -> "0x0303（TLS 1.2/1.3 legacy）"
        else -> "0x%04x".format(value)
    }
}

/** 解析出的 HTTP 头部。 */
data class HttpHead(
    val isResponse: Boolean,
    val startLine: String,
    val method: String?,
    val target: String?,
    val statusCode: Int?,
    val reason: String?,
    val headers: List<Pair<String, String>>,
    val headerEnd: Int,
    val contentLength: Int?,
    val chunked: Boolean,
    val version: String?,
) {
    fun header(name: String): String? =
        headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
}

object HttpParser {
    private val METHODS = listOf(
        "GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "TRACE", "CONNECT", "PRI",
    )

    fun parseRequest(buf: ByteArray, offset: Int, length: Int): HttpHead? {
        val head = parse(buf, offset, length) ?: return null
        return if (!head.isResponse) head else null
    }

    fun parseResponse(buf: ByteArray, offset: Int, length: Int): HttpHead? {
        val head = parse(buf, offset, length) ?: return null
        return if (head.isResponse) head else null
    }

    fun parse(buf: ByteArray, offset: Int, length: Int): HttpHead? {
        if (length < 8) return null
        val end = offset + length
        val lineEnd = indexOfCrlf(buf, offset, end)
        if (lineEnd < 0) return null
        val startLine = String(buf, offset, lineEnd - offset, Charsets.ISO_8859_1)
        val isResponse = startLine.startsWith("HTTP/1.") || startLine.startsWith("HTTP/2")
        val parts = startLine.split(' ')
        var method: String? = null
        var target: String? = null
        var statusCode: Int? = null
        var reason: String? = null
        var version: String? = null
        if (isResponse) {
            version = parts.getOrNull(0)
            statusCode = parts.getOrNull(1)?.toIntOrNull()
            reason = parts.drop(2).joinToString(" ").ifBlank { null }
        } else {
            method = parts.getOrNull(0)
            target = parts.getOrNull(1)
            version = parts.getOrNull(2)
            if (method == null || METHODS.none { it == method }) return null
        }
        val headers = mutableListOf<Pair<String, String>>()
        var o = lineEnd + 2
        var headerEnd = -1
        while (o < end) {
            val next = indexOfCrlf(buf, o, end)
            if (next < 0) break
            if (next == o) {
                headerEnd = o + 2
                break
            }
            val line = String(buf, o, next - o, Charsets.ISO_8859_1)
            val colon = line.indexOf(':')
            if (colon > 0) headers.add(line.substring(0, colon).trim() to line.substring(colon + 1).trim())
            o = next + 2
        }
        if (headerEnd < 0) return null
        val contentLength = headers.firstOrNull { it.first.equals("Content-Length", true) }
            ?.second?.toIntOrNull()
        val chunked = headers.any {
            it.first.equals("Transfer-Encoding", true) && it.second.contains("chunked", true)
        }
        return HttpHead(
            isResponse = isResponse,
            startLine = startLine,
            method = method,
            target = target,
            statusCode = statusCode,
            reason = reason,
            headers = headers,
            headerEnd = headerEnd,
            contentLength = contentLength,
            chunked = chunked,
            version = version,
        )
    }

    fun indexOfCrlf(buf: ByteArray, from: Int, end: Int): Int {
        var i = from
        while (i + 1 < end) {
            if (buf[i] == 13.toByte() && buf[i + 1] == 10.toByte()) return i
            i++
        }
        return -1
    }
}

/** 解析出的 DNS 报文（UDP/53 明文）。 */
data class DnsInfo(
    val isQuery: Boolean,
    val id: Int,
    val question: String?,
    val qType: String?,
    val rcode: Int,
    val answers: List<Pair<String, String>>,
)

object DnsParser {
    fun typeName(type: Int): String = when (type) {
        1 -> "A"
        2 -> "NS"
        5 -> "CNAME"
        6 -> "SOA"
        12 -> "PTR"
        15 -> "MX"
        16 -> "TXT"
        28 -> "AAAA"
        33 -> "SRV"
        65 -> "HTTPS"
        255 -> "ANY"
        else -> "TYPE$type"
    }

    /** 读取域名，自动跟随压缩指针；返回「域名 to 下一个偏移」。 */
    fun readName(buf: ByteArray, start: Int, end: Int, base: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var o = start
        var next = -1
        var guard = 0
        while (o < end && guard < 128) {
            guard++
            val len = u8(buf, o)
            if (len == 0) {
                o += 1
                if (next < 0) next = o
                break
            }
            if (len and 0xC0 == 0xC0) {
                if (o + 1 >= end) return null
                val pointer = ((len and 0x3F) shl 8) or u8(buf, o + 1)
                if (next < 0) next = o + 2
                val resolved = readName(buf, base + pointer, end, base) ?: return null
                if (resolved.first.isNotEmpty()) labels.add(resolved.first)
                o = next
                break
            }
            if (len > 63 || o + 1 + len > end) return null
            labels.add(String(buf, o + 1, len, Charsets.ISO_8859_1))
            o += 1 + len
        }
        if (next < 0) next = o
        return labels.joinToString(".") to next
    }
}

object DnsMessageParser {
    fun parse(buf: ByteArray, offset: Int, length: Int): DnsInfo? {
        if (length < 12) return null
        val end = offset + length
        val id = u16(buf, offset)
        val flags = u16(buf, offset + 2)
        val questionCount = u16(buf, offset + 4)
        val answerCount = u16(buf, offset + 6)
        val isQuery = (flags and 0x8000) == 0
        val rcode = flags and 0x000F
        var o = offset + 12
        var question: String? = null
        var qType: String? = null
        if (questionCount > 0) {
            val parsed = DnsParser.readName(buf, o, end, offset)
                ?: return DnsInfo(isQuery, id, null, null, rcode, emptyList())
            question = parsed.first
            o = parsed.second
            if (o + 4 > end) return DnsInfo(isQuery, id, question, null, rcode, emptyList())
            qType = DnsParser.typeName(u16(buf, o))
            o += 4
        }
        val answers = mutableListOf<Pair<String, String>>()
        if (!isQuery) {
            var remaining = answerCount
            while (remaining > 0 && o + 10 <= end && answers.size < 12) {
                val name = DnsParser.readName(buf, o, end, offset) ?: break
                o = name.second
                if (o + 10 > end) break
                val type = u16(buf, o)
                val recordLength = u16(buf, o + 8)
                val dataOffset = o + 10
                if (dataOffset + recordLength > end) break
                val value = when (type) {
                    1 -> if (recordLength == 4) ipToText(ipFromBytes(buf.copyOfRange(dataOffset, dataOffset + 4))) else null
                    28 -> if (recordLength == 16) ipv6Text(buf, dataOffset) else null
                    5 -> DnsParser.readName(buf, dataOffset, end, offset)?.first
                    16 -> String(buf, dataOffset + 1, (recordLength - 1).coerceAtLeast(0), Charsets.ISO_8859_1)
                    else -> null
                }
                if (value != null) answers.add(name.first to "${DnsParser.typeName(type)} $value")
                o = dataOffset + recordLength
                remaining--
            }
        }
        return DnsInfo(isQuery, id, question, qType, rcode, answers)
    }

    private fun ipv6Text(buf: ByteArray, offset: Int): String =
        (0 until 8).joinToString(":") { i -> u16(buf, offset + i * 2).toString(16) }
}

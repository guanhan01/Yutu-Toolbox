package com.mcp.toolbox.feature.decompile.engine

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 最小 X.509 v3 自签证书生成器 —— 手写 DER 编码。
 *
 * 为什么不用 BouncyCastle：bcpkix 会连带打进 8MB 的 bcprov，而本应用只需要签一张自签证书。
 * 为它付出 D8 阶段十几分钟的打包代价（还会把设备内存拖进 swap）不划算。
 * JDK 自带的 KeyPairGenerator / Signature / CertificateFactory 已经够用，
 * 缺的只是"把证书结构编码成 DER"这一步，这里手工拼出来。
 *
 * 支持的 DN 字段：CN / O / OU / C / L / ST，例如 "CN=Rebuild, O=MCP Toolbox, C=CN"。
 * 有效期写 UTCTime（RFC 5280 要求 2050 年前用 UTCTime），所以 days 不要超过 9000。
 */
object SelfSignedCert {

    private const val OID_SHA256_RSA = "1.2.840.113549.1.1.11"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"

    private val DN_OIDS = mapOf(
        "CN" to "2.5.4.3",
        "C" to "2.5.4.6",
        "L" to "2.5.4.7",
        "ST" to "2.5.4.8",
        "O" to "2.5.4.10",
        "OU" to "2.5.4.11",
    )

    fun create(
        pair: KeyPair,
        dname: String,
        notBefore: Date,
        notAfter: Date,
        serial: BigInteger,
    ): X509Certificate {
        val subject = name(dname)
        val tbs = seq(
            listOf(
                // version: [0] EXPLICIT INTEGER 2  => v3（v3 才能带 extensions）
                explicit(0, integer(BigInteger.valueOf(2))),
                integer(serial),
                algorithmIdentifier(OID_SHA256_RSA),
                subject,                                        // issuer == subject（自签）
                seq(listOf(utcTime(notBefore), utcTime(notAfter))),
                subject,
                pair.public.encoded,                            // SubjectPublicKeyInfo，JDK 已给标准 DER
                // extensions [3] EXPLICIT SEQUENCE OF Extension
                explicit(
                    3,
                    seq(
                        listOf(
                            seq(
                                listOf(
                                    oid(OID_BASIC_CONSTRAINTS),
                                    byteArrayOf(0x01, 0x01, 0xFF.toByte()), // critical = TRUE
                                    octetString(seq(listOf()))               // BasicConstraints: cA = FALSE
                                )
                            )
                        )
                    )
                ),
            )
        )

        val signature = Signature.getInstance("SHA256withRSA").apply {
            initSign(pair.private)
            update(tbs)
        }.sign()

        val der = seq(listOf(tbs, algorithmIdentifier(OID_SHA256_RSA), bitString(signature)))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
    }

    // ------------------------------------------------------------------
    // DER 基础编码
    // ------------------------------------------------------------------

    private fun lengthBytes(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n <= 0xFF -> byteArrayOf(0x81.toByte(), n.toByte())
        n <= 0xFFFF -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun concat(items: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        items.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun tagged(tag: Int, body: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + lengthBytes(body.size) + body

    private fun seq(items: List<ByteArray>) = tagged(0x30, concat(items))

    private fun explicit(n: Int, body: ByteArray) = tagged(0xA0 or n, body)

    private fun integer(value: BigInteger) = tagged(0x02, value.toByteArray())

    private fun bitString(bytes: ByteArray) = tagged(0x03, byteArrayOf(0) + bytes)

    private fun octetString(bytes: ByteArray) = tagged(0x04, bytes)

    private fun utf8(text: String) = tagged(0x0C, text.toByteArray(Charsets.UTF_8))

    private fun utcTime(date: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return tagged(0x17, fmt.format(date).toByteArray(Charsets.US_ASCII))
    }

    /** AlgorithmIdentifier：SEQUENCE { OID, NULL }。 */
    private fun algorithmIdentifier(oidText: String) =
        seq(listOf(oid(oidText), byteArrayOf(0x05, 0x00)))

    private fun name(dname: String): ByteArray {
        val rdns = dname.split(',').mapNotNull { part ->
            val kv = part.trim().split('=', limit = 2)
            if (kv.size != 2) return@mapNotNull null
            val key = kv[0].trim().uppercase(Locale.US)
            val value = kv[1].trim()
            val oidText = DN_OIDS[key] ?: return@mapNotNull null
            tagged(0x31, seq(listOf(oid(oidText), utf8(value))))   // RDN = SET OF AttributeTypeAndValue
        }
        require(rdns.isNotEmpty()) { "无法解析 DN：$dname" }
        return seq(rdns)
    }

    /** 点分 OID 文本 -> DER OBJECT IDENTIFIER，支持多字节分量。 */
    private fun oid(text: String): ByteArray {
        val parts = text.split('.').map { it.toLong() }
        require(parts.size >= 2) { "非法 OID：$text" }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (i in 2 until parts.size) {
            var value = parts[i]
            val stack = ArrayList<Int>()
            stack.add((value and 0x7F).toInt())
            value = value shr 7
            while (value > 0) {
                stack.add(((value and 0x7F) or 0x80).toInt())
                value = value shr 7
            }
            for (j in stack.indices.reversed()) out.write(stack[j])
        }
        return tagged(0x06, out.toByteArray())
    }
}

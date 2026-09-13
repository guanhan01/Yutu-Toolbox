package com.mcp.toolbox.feature.capture

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** * 本地 CA：真实生成 RSA 2048 自签 CA 证书（DER 手写 ASN.1）， * 并提供 PEM 导出与安装引导。私钥与证书只保存在应用私有目录。 */
object CertificateAuthority {

    private const val KEY_FILE = "capture-ca.pk8"

    private const val CERT_FILE = "capture-ca.der"

    private const val SUBJECT_NAME = "MCP Toolbox Capture CA"

    private const val ORGANIZATION = "MCP Toolbox"

    data class CaInfo(
        val certificate: X509Certificate,
        val pem: String,
        val der: ByteArray,
        val selfVerified: Boolean,
        val keyFile: File,
        val certFile: File,
    ) {

        val subject: String
            get() = certificate.subjectX500Principal.name

        val notAfter: Long
            get() = certificate.notAfter.time

        val fingerprint: String
            get() = certificate.encoded.joinToString(":") { String.format("%02X", it) }.take(60)
    }

    fun ensure(context: Context): Result<CaInfo> = runCatching {
        ensureInner(context).also { MitmCa.rememberCa(it.certificate) }
    }

    private fun ensureInner(context: Context): CaInfo {
        val dir = File(context.filesDir, "capture-ca").apply { mkdirs() }

        val keyFile = File(dir, KEY_FILE)
        val certFile = File(dir, CERT_FILE)
        if (keyFile.exists() && certFile.exists()) {

            val loaded = load(keyFile, certFile)

            if (loaded != null) return loaded
        }

        return generate(keyFile, certFile)
    }

    private fun load(keyFile: File, certFile: File): CaInfo? =
        runCatching {
                val der = certFile.readBytes()

                val certificate = parseCertificate(der) ?: return null

                val pem = pemOf(der)

                CaInfo(certificate, pem, der, verifySelf(certificate), keyFile, certFile)
            }
            .getOrNull()

    private fun generate(keyFile: File, certFile: File): CaInfo {

        val generator = KeyPairGenerator.getInstance("RSA")

        generator.initialize(2048, SecureRandom())

        val keyPair = generator.generateKeyPair()

        val serial = BigInteger(128, SecureRandom()).abs().add(BigInteger.ONE)

        val notBefore = System.currentTimeMillis() - 86_400_000L

        val notAfter = notBefore + 5L * 365 * 86_400_000L

        val subject = name(oidCommonName to SUBJECT_NAME, oidOrganization to ORGANIZATION)

        val tbs =
            der(
                0x30,
                listOf(
                    der(0xA0, integer(BigInteger.valueOf(2))),
                    integer(serial),
                    algorithmIdentifier,
                    subject,
                    validity(notBefore, notAfter),
                    subject,
                    keyPair.public.encoded,
                    der(0xA3, extensionList()),
                ),
            )

        val signature =
            Signature.getInstance("SHA256withRSA")
                .apply {
                    initSign(keyPair.private)

                    update(tbs)
                }
                .sign()
        val certificateDer =
            der(
                0x30,
                listOf(tbs, algorithmIdentifier, bitString(signature)),
            )
        keyFile.writeBytes(keyPair.private.encoded)
        certFile.writeBytes(certificateDer)
        val certificate =
            parseCertificate(certificateDer) ?: throw IllegalStateException("生成的证书无法被系统解析")
        return CaInfo(
            certificate,
            pemOf(certificateDer),
            certificateDer,
            verifySelf(certificate),
            keyFile,
            certFile)
    }

    private fun parseCertificate(der: ByteArray): X509Certificate? =
        runCatching {
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            }
            .getOrNull()

    private fun verifySelf(certificate: X509Certificate): Boolean =
        runCatching {
                certificate.verify(certificate.publicKey)

                true
            }
            .getOrDefault(false)

    fun pemOf(der: ByteArray): String {

        val base64 = Base64.encodeToString(der, Base64.NO_WRAP)

        val body = base64.chunked(64).joinToString("\n")

        return "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n"
    }

    fun loadPrivateKey(keyFile: File) =
        runCatching {
                KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            }
            .getOrNull()

    /** 安装与限制说明（Android 7+ 用户 CA 默认不被应用信任）。 */
    fun installGuide(): List<String> =
        listOf(
            "Android 7 起应用默认只信任系统 CA，用户 CA 对多数 App 不生效。",
            "安装到用户证书：设置 → 安全 → 加密与凭据 → 从存储安装 CA 证书（选择导出的 PEM/DER）。",
            "也可以「下载 CA 根证书模块 (.zip)」用 Magisk / KernelSU 刷入，开机自动铺证书。",
            "要让 App 真正信任用户 CA：需 root 后把证书放进系统证书目录，或由被调试 App 自行声明信任用户 CA。",
            "要让 HTTPS 明文可见：在抓包页「⋯」打开 HTTPS 解密，并在证书页把 CA 装到系统证书区，" + "然后强制停止并重开目标 App 再抓包。",
            "系统证书区是 tmpfs 覆盖，重启设备自动还原；微信、QQ 等自带根证书或私有协议的 App 仍无法解密。",
        )

    private fun der(tag: Int, content: ByteArray): ByteArray {

        val out = ByteArrayOutputStream()

        out.write(tag)

        val length = content.size

        when {
            length < 0x80 -> out.write(length)

            length < 0x100 -> {

                out.write(0x81)

                out.write(length)
            }

            length < 0x10000 -> {

                out.write(0x82)

                out.write(length shr 8)

                out.write(length and 0xFF)
            }

            else -> {

                out.write(0x83)

                out.write(length shr 16)

                out.write((length shr 8) and 0xFF)

                out.write(length and 0xFF)
            }
        }

        out.write(content)
        return out.toByteArray()
    }

    private fun der(tag: Int, parts: List<ByteArray>): ByteArray {

        val total = parts.sumOf { it.size }

        val merged = ByteArray(total)
        var offset = 0
        parts.forEach { part ->
            System.arraycopy(part, 0, merged, offset, part.size)

            offset += part.size
        }

        return der(tag, merged)
    }

    private fun integer(value: BigInteger): ByteArray = der(0x02, value.toByteArray())

    private fun bitString(bytes: ByteArray): ByteArray = der(0x03, ByteArray(1) + bytes)

    private fun octetString(bytes: ByteArray): ByteArray = der(0x04, bytes)

    private fun oid(vararg value: Int): ByteArray =
        der(0x06, ByteArray(value.size) { value[it].toByte() })

    private fun utf8(value: String): ByteArray = der(0x0C, value.toByteArray())

    private fun nullValue(): ByteArray = der(0x05, ByteArray(0))

    private fun name(vararg entries: Pair<ByteArray, String>): ByteArray =
        der(
            0x30,
            entries.map { (oidBytes, value) ->
                der(0x31, listOf(der(0x30, listOf(oidBytes, utf8(value)))))
            },
        )

    private fun validity(notBefore: Long, notAfter: Long): ByteArray {

        val format =
            SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }

        return der(
            0x30,
            listOf(
                der(0x17, format.format(Date(notBefore)).toByteArray()),
                der(0x17, format.format(Date(notAfter)).toByteArray()),
            ),
        )
    }

    private fun extensionList(): ByteArray =
        der(
            0x30,
            listOf(
                der(
                    0x30,
                    listOf(
                        oidBasicConstraints,
                        der(0x01, byteArrayOf(0xFF.toByte())),
                        octetString(der(0x30, listOf(der(0x01, byteArrayOf(0xFF.toByte()))))),
                    ),
                ),
                der(
                    0x30,
                    listOf(
                        oidKeyUsage,
                        der(0x01, byteArrayOf(0xFF.toByte())),
                        octetString(der(0x03, byteArrayOf(0x01, 0x06))),
                    ),
                ),
            ),
        )

    private val oidSha256Rsa = oid(0x2A, 0x86, 0x48, 0x86, 0xF7, 0x0D, 0x01, 0x01, 0x0B)
    private val oidCommonName = oid(0x55, 0x04, 0x03)
    private val oidOrganization = oid(0x55, 0x04, 0x0A)
    private val oidBasicConstraints = oid(0x55, 0x1D, 0x13)
    private val oidKeyUsage = oid(0x55, 0x1D, 0x0F)
    private val algorithmIdentifier: ByteArray = der(0x30, listOf(oidSha256Rsa, nullValue()))
}

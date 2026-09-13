package com.mcp.toolbox.feature.decompile.engine

import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest

/**
 * 重打包能力的对外门面。
 * apksig 只在 decompile 模块可见（implementation 依赖），所以校验逻辑必须收在这里，
 * 调用方（MCP 工具集 / 界面）不必依赖 apksig。
 */
object RebuildApi {

    data class VerifyReport(
        val verified: Boolean,
        val v1: Boolean,
        val v2: Boolean,
        val v3: Boolean,
        val signerCount: Int,
        val subject: String?,
        val sha256: String?,
        val sourceStamp: String?,
        val errors: List<String>,
        val warnings: List<String>,
    )

    fun verify(apk: File): VerifyReport {
        require(apk.isFile) { "APK 不存在：${apk.absolutePath}" }
        val result = ApkVerifier.Builder(apk).build().verify()
        val certs = result.signerCertificates
        val cert = certs.firstOrNull()
        return VerifyReport(
            verified = result.isVerified,
            v1 = result.isVerifiedUsingV1Scheme,
            v2 = result.isVerifiedUsingV2Scheme,
            v3 = result.isVerifiedUsingV3Scheme,
            signerCount = certs.size,
            subject = cert?.subjectX500Principal?.name,
            sha256 = cert?.let { sha256(it.encoded) },
            sourceStamp = runCatching { result.isSourceStampVerified.toString() }.getOrNull(),
            errors = result.errors.map { it.toString() },
            warnings = result.warnings.map { it.toString() },
        )
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 已存在的签名密钥信息（没有就返回 null，不隐式生成）。 */
    fun keySummary(context: android.content.Context): Map<String, Any?>? {
        val key = SignKeys.load(context) ?: return null
        val cert = key.certificates.first()
        return mapOf(
            "alias" to key.alias,
            "subject" to cert.subjectX500Principal.name,
            "issuer" to cert.issuerX500Principal.name,
            "algorithm" to cert.sigAlgName,
            "keyAlgorithm" to key.privateKey.algorithm,
            "notBefore" to cert.notBefore.toString(),
            "notAfter" to cert.notAfter.toString(),
            "sha256" to sha256(cert.encoded),
            "storePath" to SignKeys.file(context).absolutePath,
            "daysLeft" to ((cert.notAfter.time - System.currentTimeMillis()) / 86_400_000L),
        )
    }
}

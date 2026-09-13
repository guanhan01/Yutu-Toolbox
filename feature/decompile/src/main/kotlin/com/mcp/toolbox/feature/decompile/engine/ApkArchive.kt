package com.mcp.toolbox.feature.decompile.engine

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.mcp.toolbox.feature.decompile.ApkSummary
import com.mcp.toolbox.feature.decompile.CertInfo
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** APK 来源：本应用 / 已安装应用 / SAF 选中的文件。 */
sealed interface ApkSource {
    val label: String

    data object SelfApp : ApkSource {
        override val label: String = "本应用 APK"
    }

    data class InstalledApp(val packageName: String, val appLabel: String) : ApkSource {
        override val label: String get() = appLabel
    }

    data class PickedFile(val uri: Uri, val displayName: String) : ApkSource {
        override val label: String get() = displayName
    }

    /** 文件页 / 内置阅读器直接给的本地路径（root 可读的私有目录同样适用）。 */
    data class LocalPath(val path: String) : ApkSource {
        override val label: String get() = File(path).name
    }

    /** 解析成真实可读文件；SAF 来源会复制到缓存目录。 */
    fun resolve(context: Context): File = when (this) {
        is SelfApp -> File(context.applicationInfo.sourceDir)
        is InstalledApp -> {
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            val path = info.sourceDir ?: throw IllegalStateException("无法获取 $packageName 的 APK 路径")
            val file = File(path)
            if (!file.canRead()) throw IllegalStateException("无权读取 $path（无 root / Shizuku 时部分应用不可读）")
            file
        }
        is LocalPath -> {
            val file = File(path)
            if (!file.canRead()) throw IllegalStateException("无权读取 $path（无 root / Shizuku 时部分应用不可读）")
            file
        }
        is PickedFile -> {
            val target = File(context.cacheDir, "decompile-input/" + displayName.replace('/', '_'))
            target.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("无法打开所选文件")
            target
        }
    }
}

/** 对 APK 的只读访问：条目清单、条目字节、摘要、签名证书、清单与资源表。 */
class ApkArchive(val file: File) : AutoCloseable {

    private val zip = ZipFile(file)

    val entries: List<ZipEntry> get() = zip.entries().toList()

    fun readEntry(name: String): ByteArray? {
        val entry = zip.getEntry(name) ?: return null
        if (entry.isDirectory) return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun dexEntries(): List<String> = zip.entries().toList()
        .map { it.name }
        .filter { it.endsWith(".dex") && it.contains("classes") }
        .sorted()

    fun nativeLibs(): List<String> = zip.entries().toList()
        .map { it.name }
        .filter { it.startsWith("lib/") && it.endsWith(".so") }
        .sorted()

    fun digest(algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                md.update(buffer, 0, read)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 签名证书：优先 PackageManager（覆盖 v2/v3 签名），其次 META-INF PKCS#7。 */
    fun certificate(context: Context?): CertInfo? {
        val fromPm = runCatching { certificateFromPackageManager(context) }.getOrNull()
        if (fromPm != null) return fromPm
        return runCatching { certificateFromMetaInf() }.getOrNull()
    }

    private fun certificateFromPackageManager(context: Context?): CertInfo? {
        val pm = context?.packageManager ?: return null
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val info = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: return null
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        val signer = signatures?.firstOrNull() ?: return null
        val cert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(signer.toByteArray().inputStream()) as? X509Certificate ?: return null
        return cert.toInfo("PackageManager（v2/v3 签名）")
    }

    private fun certificateFromMetaInf(): CertInfo? {
        val signatureEntry = zip.entries().toList().firstOrNull { entry ->
            val n = entry.name.uppercase(Locale.ROOT)
            n.startsWith("META-INF/") && (n.endsWith(".RSA") || n.endsWith(".DSA") || n.endsWith(".EC"))
        } ?: return null
        val bytes = zip.getInputStream(signatureEntry).use { it.readBytes() }
        val certs = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificates(bytes.inputStream())
        val cert = certs.filterIsInstance<X509Certificate>().firstOrNull() ?: return null
        return cert.toInfo(signatureEntry.name.substringAfterLast('/'))
    }

    private fun X509Certificate.toInfo(source: String): CertInfo {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        return CertInfo(
            subject = subjectX500Principal.name,
            issuer = issuerX500Principal.name,
            sha256 = digestHex("SHA-256"),
            sha1 = digestHex("SHA-1"),
            algorithm = sigAlgName,
            serial = serialNumber.toString(16),
            notBefore = fmt.format(Date(notBefore.time)),
            notAfter = fmt.format(Date(notAfter.time)),
            fileName = source,
        )
    }

    private fun X509Certificate.digestHex(algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(encoded).joinToString("") { "%02x".format(it) }
            .chunked(2).joinToString(":")

    fun summary(context: Context?, displayName: String = file.name): ApkSummary {
        val all = entries
        return ApkSummary(
            displayName = displayName,
            path = file.absolutePath,
            sizeBytes = file.length(),
            md5 = digest("MD5"),
            sha256 = digest("SHA-256"),
            entryCount = all.size,
            dexEntries = dexEntries(),
            nativeLibs = nativeLibs(),
            certificate = certificate(context),
        )
    }

    override fun close() {
        runCatching { zip.close() }
    }
}

/** 已安装应用清单，供反编译模块选择目标。 */
object InstalledApks {

    data class Entry(val packageName: String, val appLabel: String, val sourceDir: String, val isSelf: Boolean)

    fun list(context: Context, includeSystem: Boolean = false): List<Entry> {
        val pm = context.packageManager
        val selfPkg = context.packageName
        return pm.getInstalledPackages(0)
            .asSequence()
            .filter { includeSystem || (it.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0 }
            .mapNotNull { info ->
                val app = info.applicationInfo ?: return@mapNotNull null
                val path = app.sourceDir ?: return@mapNotNull null
                Entry(
                    packageName = info.packageName,
                    appLabel = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(info.packageName),
                    sourceDir = path,
                    isSelf = info.packageName == selfPkg,
                )
            }
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<Entry> { it.isSelf }.thenBy { it.appLabel })
            .toList()
    }
}

package com.mcp.toolbox.feature.decompile.engine

import android.content.Context
import com.android.apksig.ApkSigner
import org.jf.smali.Smali
import org.jf.smali.SmaliOptions
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Date
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 重打包闭环：smali 目录 -> dex -> 塞回原 APK -> 重新签名。
 * 全程在设备上完成，不依赖 aapt2/apktool，因此改代码（而非改资源）的场景无需外部工具链。
 */

// ---------------------------------------------------------------------------
// 1. 自签名密钥（重打包后必须换签名，否则与原应用签名不一致无法覆盖安装）
// ---------------------------------------------------------------------------

data class SignKey(
    val alias: String,
    val privateKey: PrivateKey,
    val certificates: List<X509Certificate>,
    val fresh: Boolean,
) {
    val subject: String get() = certificates.firstOrNull()?.subjectX500Principal?.name ?: alias
}

object SignKeys {
    private const val FILE_NAME = "rebuild-keys.p12"
    private const val ALIAS = "mcp-toolbox"
    private const val PASS = "mcp-toolbox-local"
    private const val DNAME = "CN=MCP Toolbox Rebuild,O=MCP Toolbox,C=CN"
    private const val DAYS = 3650L

    fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    fun exists(context: Context): Boolean = file(context).isFile

    fun load(context: Context): SignKey? {
        val f = file(context)
        if (!f.isFile) return null
        return runCatching {
            val ks = KeyStore.getInstance("PKCS12")
            f.inputStream().use { ks.load(it, PASS.toCharArray()) }
            val key = ks.getKey(ALIAS, PASS.toCharArray()) as PrivateKey
            val cert = ks.getCertificate(ALIAS) as X509Certificate
            SignKey(ALIAS, key, listOf(cert), false)
        }.getOrNull()
    }

    /** 有就复用，没有就生成。复用很重要：同一密钥才能覆盖安装自己的多次产出。 */
    fun ensure(context: Context): SignKey = load(context) ?: create(context)

    fun create(context: Context): SignKey {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()

        val now = System.currentTimeMillis()
        val cert = SelfSignedCert.create(
            pair = pair,
            dname = DNAME,
            notBefore = Date(now - 86_400_000L),
            notAfter = Date(now + DAYS * 86_400_000L),
            serial = BigInteger.valueOf(now),
        )

        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, null)
        ks.setKeyEntry(ALIAS, pair.private, PASS.toCharArray(), arrayOf(cert))
        file(context).outputStream().use { ks.store(it, PASS.toCharArray()) }

        return SignKey(ALIAS, pair.private, listOf(cert), true)
    }

    fun delete(context: Context): Boolean = file(context).delete()
}

// ---------------------------------------------------------------------------
// 2. smali -> dex
// ---------------------------------------------------------------------------

object SmaliAssembler {

    data class DexOut(val entryName: String, val file: File, val bytes: Long)

    /**
     * 支持两种目录布局：
     *  - smali/ 下直接是类目录            -> 单个 classes.dex
     *  - smali/classes.dex/、smali/classes2.dex/ -> 每个 dex 独立汇编（绕开单 dex 64K 方法数上限）
     */
    fun assemble(smaliRoot: File, outDir: File, apiLevel: Int): List<DexOut> {
        require(smaliRoot.isDirectory) { "smali 目录不存在：${smaliRoot.absolutePath}" }
        outDir.mkdirs()

        val subDex = smaliRoot.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".dex") }
            ?.sortedBy { it.name }
            .orEmpty()

        val jobs = if (subDex.isNotEmpty()) {
            subDex.map { it.name to it }
        } else {
            listOf("classes.dex" to smaliRoot)
        }

        return jobs.map { (name, dir) ->
            val out = File(outDir, name)
            assembleOne(dir, out, apiLevel)
            DexOut(name, out, out.length())
        }
    }

    private fun assembleOne(srcDir: File, outDex: File, apiLevel: Int) {
        if (outDex.exists()) outDex.delete()
        val options = SmaliOptions().apply {
            outputDexFile = outDex.absolutePath
            this.apiLevel = apiLevel.coerceAtLeast(26)
            verboseErrors = false
        }
        val ok = Smali.assemble(options, listOf(srcDir.absolutePath))
        if (!ok) error("smali 汇编失败：${srcDir.name}")
        if (!outDex.isFile || outDex.length() == 0L) error("smali 汇编产物为空：${srcDir.name}")
    }
}

// ---------------------------------------------------------------------------
// 3. 重打包（替换 dex / 任意条目，剥离旧签名）
// ---------------------------------------------------------------------------

object ApkRebuilder {

    data class Report(
        val outApk: File,
        val size: Long,
        val replaced: List<String>,
        val added: List<String>,
        val removed: List<String>,
    )

    /** 旧签名相关条目必须剔除，否则 v1 与 apksig 冲突。注意只剔签名，保留 META-INF/services 等。 */
    private val SIG_ENTRY = Regex(
        "^META-INF/(MANIFEST\\.MF|[^/]+\\.(SF|RSA|DSA|EC))$",
        RegexOption.IGNORE_CASE,
    )

    /** .so 与 resources.arsc 保持不压缩：前者关系到能否直接从 APK 映射加载。 */
    private fun mustStore(name: String) = name.endsWith(".so") || name == "resources.arsc"

    fun rebuild(base: File, replacements: Map<String, ByteArray>, out: File): Report {
        val replaced = mutableListOf<String>()
        val added = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()

        ZipFile(base).use { zf ->
            val entries = java.util.Collections.list(zf.entries()).sortedBy { it.name }
            ZipOutputStream(BufferedOutputStream(FileOutputStream(out), 1 shl 16)).use { zo ->
                for (e in entries) {
                    if (SIG_ENTRY.matches(e.name)) {
                        removed += e.name
                        continue
                    }
                    val data = replacements[e.name]
                    if (data != null) {
                        writeBytes(zo, e.name, data, mustStore(e.name))
                        replaced += e.name
                    } else {
                        copyEntry(zo, zf, e)
                    }
                    seen += e.name
                }
                for ((name, data) in replacements) {
                    if (name !in seen) {
                        writeBytes(zo, name, data, mustStore(name))
                        added += name
                    }
                }
            }
        }
        return Report(out, out.length(), replaced, added, removed)
    }

    private fun crc32(data: ByteArray): Long {
        val c = CRC32()
        c.update(data)
        return c.value
    }

    private fun writeBytes(zo: ZipOutputStream, name: String, data: ByteArray, stored: Boolean) {
        val e = ZipEntry(name)
        if (stored) {
            e.method = ZipEntry.STORED
            e.size = data.size.toLong()
            e.compressedSize = data.size.toLong()
            e.crc = crc32(data)
        }
        zo.putNextEntry(e)
        zo.write(data)
        zo.closeEntry()
    }

    private fun copyEntry(zo: ZipOutputStream, zf: ZipFile, src: ZipEntry) {
        val stored = mustStore(src.name) || src.method == ZipEntry.STORED
        val e = ZipEntry(src.name)
        if (stored) {
            // 原条目的 crc/size 在中央目录里，STORED 条目一定有效；保险起见异常时重算
            val size = src.size
            val crc = src.crc
            if (size < 0 || crc < 0) {
                val data = zf.getInputStream(src).use { it.readBytes() }
                e.method = ZipEntry.STORED
                e.size = data.size.toLong()
                e.compressedSize = data.size.toLong()
                e.crc = crc32(data)
                zo.putNextEntry(e)
                zo.write(data)
                zo.closeEntry()
                return
            }
            e.method = ZipEntry.STORED
            e.size = size
            e.compressedSize = size
            e.crc = crc
        }
        e.time = src.time
        zo.putNextEntry(e)
        zf.getInputStream(src).use { it.copyTo(zo, 1 shl 16) }
        zo.closeEntry()
    }
}

// ---------------------------------------------------------------------------
// 4. 签名（v1 + v2 + v3，Android 11+ 对 targetSdk>=30 强制要求 v2）
// ---------------------------------------------------------------------------

object ApkSignerEngine {

    data class Report(
        val outApk: File,
        val size: Long,
        val sha256: String,
        val schemes: String,
        val subject: String,
        val alias: String,
    )

    fun sign(
        input: File,
        out: File,
        key: SignKey,
        minSdk: Int = 21,
        v1: Boolean = true,
        v2: Boolean = true,
        v3: Boolean = true,
    ): Report {
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()

        val config = ApkSigner.SignerConfig.Builder(key.alias, key.privateKey, key.certificates).build()
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(out)
            .setMinSdkVersion(minSdk.coerceAtLeast(21))
            .setV1SigningEnabled(v1)
            .setV2SigningEnabled(v2)
            .setV3SigningEnabled(v3)
            .setOtherSignersSignaturesPreserved(false)
            .build()
            .sign()

        return Report(
            outApk = out,
            size = out.length(),
            sha256 = sha256(out),
            schemes = listOfNotNull(
                if (v1) "v1" else null,
                if (v2) "v2" else null,
                if (v3) "v3" else null,
            ).joinToString("+"),
            subject = key.subject,
            alias = key.alias,
        )
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

// ---------------------------------------------------------------------------
// 5. 一条龙：改 smali -> 出签名 APK
// ---------------------------------------------------------------------------

object RebuildPipeline {

    data class Result(
        val unsignedApk: File,
        val signedApk: File,
        val dexes: List<SmaliAssembler.DexOut>,
        val rebuild: ApkRebuilder.Report,
        val sign: ApkSignerEngine.Report,
        val keyCreated: Boolean,
        val steps: List<String>,
    )

    fun run(
        context: Context,
        baseApk: File,
        smaliRoot: File,
        workDir: File,
        outApk: File,
        apiLevel: Int,
        minSdk: Int,
        onStep: (String) -> Unit = {},
    ): Result {
        val steps = mutableListOf<String>()
        fun step(msg: String) {
            steps += msg
            onStep(msg)
        }

        workDir.mkdirs()

        step("汇编 smali -> dex")
        val dexes = SmaliAssembler.assemble(smaliRoot, File(workDir, "dex"), apiLevel)
        step("生成 ${dexes.size} 个 dex：${dexes.joinToString { "${it.entryName}(${it.bytes / 1024}KB)" }}")

        step("替换进原 APK 并剥离旧签名")
        val unsigned = File(workDir, "unsigned.apk")
        val replacements = dexes.associate { it.entryName to it.file.readBytes() }
        val rebuild = ApkRebuilder.rebuild(baseApk, replacements, unsigned)
        step("重打包完成：替换 ${rebuild.replaced.size}、新增 ${rebuild.added.size}、移除签名 ${rebuild.removed.size}")

        step("准备签名密钥")
        val key = SignKeys.ensure(context)
        step(if (key.fresh) "已生成新的自签名密钥（2048 位 RSA）" else "复用已有签名密钥")

        step("签名 v1+v2+v3")
        val report = ApkSignerEngine.sign(unsigned, outApk, key, minSdk)
        step("签名完成：${report.schemes} · ${report.size / 1024}KB")

        return Result(unsigned, outApk, dexes, rebuild, report, key.fresh, steps)
    }
}

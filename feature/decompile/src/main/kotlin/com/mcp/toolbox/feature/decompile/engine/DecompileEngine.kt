package com.mcp.toolbox.feature.decompile.engine

import android.content.Context
import com.mcp.toolbox.feature.decompile.DecompileSession
import com.mcp.toolbox.feature.decompile.TaskRecord
import com.mcp.toolbox.feature.decompile.TaskState
import java.io.File
import java.security.MessageDigest

/** 反编译流水线：APK → 摘要 / 清单 / 资源 / dex 索引，并可产出 smali 与 Java 源码。 */
object DecompileEngine {

    data class Progress(val fraction: Float, val message: String)

    /** 产物根目录：所有输出都在应用沙箱内。 */
    fun workDir(context: Context): File = File(context.filesDir, "decompile").apply { mkdirs() }

    fun sessionDir(context: Context, apkFile: File, label: String): File {
        val key = safeName(label) + "-" + shortHash(apkFile.absolutePath + apkFile.length())
        return File(workDir(context), key).apply { mkdirs() }
    }

    private fun shortHash(text: String): String =
        MessageDigest.getInstance("MD5").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(8)

    fun safeName(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)

    /** 索引 + 清单 + 资源表，构成一次完整分析。 */
    fun analyze(
        context: Context,
        source: ApkSource,
        onProgress: (Progress) -> Unit = {},
    ): DecompileSession {
        onProgress(Progress(0.02f, "定位 APK"))
        val apkFile = source.resolve(context)
        if (!apkFile.isFile) throw IllegalStateException("APK 不存在：${apkFile.absolutePath}")
        val archive = ApkArchive(apkFile)
        val session = archive.use { archive ->
            onProgress(Progress(0.08f, "读取条目与签名"))
            val summary = archive.summary(context, source.label)

            onProgress(Progress(0.18f, "解析 resources.arsc"))
            val arsc = archive.readEntry("resources.arsc")?.let { bytes ->
                runCatching { ArscParser(bytes) }.getOrNull()
            }
            val resources = arsc?.let { runCatching { it.parse() }.getOrNull() }

            onProgress(Progress(0.3f, "解析 AndroidManifest.xml"))
            val manifest = runCatching {
                val bytes = archive.readEntry("AndroidManifest.xml")
                    ?: throw IllegalStateException("APK 内没有 AndroidManifest.xml")
                AxmlParser.toManifest(AxmlParser.parse(bytes) { id -> arsc?.resolve(id) })
            }.getOrNull()

            onProgress(Progress(0.4f, "解析 dex"))
            val index = DexIndexer.build(apkFile) { fraction, message ->
                onProgress(Progress(0.4f + fraction * 0.55f, message))
            }

            onProgress(Progress(0.98f, "整理结果"))
            val dir = sessionDir(context, apkFile, source.label)
            DecompileSession(
                summary = summary,
                classes = index.classes,
                manifest = manifest,
                resources = resources,
                stringPool = index.stringConstants,
                smaliDir = File(dir, "smali").takeIf { it.isDirectory }?.absolutePath,
                javaDir = File(dir, "java").takeIf { it.isDirectory }?.absolutePath,
            )
        }
        onProgress(Progress(1f, "分析完成：${session.classes.size} 个类"))
        return session
    }

    /** smali 反汇编：返回产物统计。 */
    fun disassemble(
        context: Context,
        source: ApkSource,
        onProgress: (Progress) -> Unit = {},
    ): SmaliEngine.Result {
        val apkFile = source.resolve(context)
        val dir = File(sessionDir(context, apkFile, source.label), "smali")
        return SmaliEngine.disassemble(apkFile, dir) { fraction, message ->
            onProgress(Progress(fraction, message))
        }
    }

    /** Java 源码产出的实测结果：目录 + 写出的文件数 + 加载的类数。 */
    data class JavaOutcome(
        val dir: String,
        val writtenFiles: Int,
        val loadedClasses: Int,
        val dexCount: Int = 0,
        val failedDexes: List<String> = emptyList(),
    )

    /**
     * Java 源码：逐个 dex 交给 jadx，写完一个立刻关掉引擎。
     *
     * 一次性把整个 APK 喂给 jadx，20 个 dex 的工程要几个 GB 堆；手机就算开了 largeHeap
     * 也只有 512MB，实测直接 OutOfMemoryError。拆成单个 dex 后，峰值内存只跟最大的那个
     * dex 有关，一个失败也不至于让整次产出归零。
     */
    fun decompileJava(
        context: Context,
        source: ApkSource,
        onProgress: (Progress) -> Unit = {},
    ): JavaOutcome {
        val apkFile = source.resolve(context)
        val dir = File(sessionDir(context, apkFile, source.label), "java")
        dir.mkdirs()
        onProgress(Progress(0.02f, "读取 dex 列表"))
        val dexNames = ApkArchive(apkFile).use { it.dexEntries() }
        val tempDir = File(dir.parentFile ?: dir, "dex-tmp").apply { mkdirs() }
        val failed = mutableListOf<String>()
        var loaded = 0
        dexNames.forEachIndexed { index, name ->
            val shortName = name.substringAfterLast('/')
            val base = index.toFloat() / dexNames.size.coerceAtLeast(1)
            onProgress(Progress(0.05f + base * 0.9f, "反编译 $shortName（${index + 1}/${dexNames.size}）"))
            val single = File(tempDir, shortName)
            val extracted = runCatching {
                ApkArchive(apkFile).use { archive ->
                    val bytes = archive.readEntry(name)
                    if (bytes == null) {
                        false
                    } else {
                        single.writeBytes(bytes)
                        true
                    }
                }
            }.getOrDefault(false)
            if (!extracted) {
                failed += shortName
                return@forEachIndexed
            }
            try {
                val handle = JadxEngine.open(single, dir, threads = 1)
                try {
                    JadxEngine.saveAll(handle)
                    loaded += handle.classes.size
                } finally {
                    JadxEngine.close(handle)
                }
            } catch (error: Throwable) {
                failed += "$shortName（${error.javaClass.simpleName}）"
            } finally {
                single.delete()
            }
        }
        val written = dir.walkTopDown().count { it.isFile && it.extension == "java" }
        onProgress(Progress(1f, "Java 源码 $written 个文件"))
        return JavaOutcome(dir.absolutePath, written, loaded, dexNames.size, failed)
    }

    /** 导出清单文本 / 类清单 CSV / 方法列表 / 字符串列表。 */
    fun exportTexts(session: DecompileSession, dir: File): List<File> {
        dir.mkdirs()
        val written = mutableListOf<File>()
        fun write(name: String, text: String): File {
            val file = File(dir, name)
            file.writeText(text)
            written += file
            return file
        }
        session.manifest?.let { manifest -> write("AndroidManifest.xml", manifest.xml) }
        write(
            "classes.csv",
            buildString {
                append("class,super,methods,fields,dex\n")
                session.classes.forEach { cls ->
                    append("${cls.name},${cls.superName ?: ""},${cls.methods.size},${cls.fields.size},${cls.dexEntry}\n")
                }
            },
        )
        write(
            "methods.txt",
            buildString {
                session.classes.forEach { cls ->
                    cls.methods.forEach { append("${cls.name}->${it.signature} ${it.accessText}\n") }
                }
            },
        )
        write("strings.txt", session.stringPool.joinToString("\n"))
        return written
    }

    fun historyLine(record: TaskRecord): String = buildString {
        append("${record.engine} · ${record.apkName} · ${record.state}")
        if (record.state == TaskState.DONE) {
            append(" · 类 ${record.classCount} / 方法 ${record.methodCount} · ${record.elapsedMs}ms")
        }
        record.error?.let { append(" · $it") }
    }
}

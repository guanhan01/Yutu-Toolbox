package com.mcp.toolbox.feature.decompile.engine

import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.DexFile
import java.io.File

/**
 * smali 引擎：调用 baksmali 把 dex 反汇编为 .smali 文本。
 * 关键点：必须传入 ClassPath（含 APK 自身 dex），否则 baksmali 的类型分析会 NPE 并跳过整类。
 */
object SmaliEngine {

    class SmaliException(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Result(
        val dir: String,
        val writtenClasses: Int,
        val expectedClasses: Int,
        val dexCount: Int,
        val failedDexes: List<String> = emptyList(),
    ) {
        val skipped: Int get() = (expectedClasses - writtenClasses).coerceAtLeast(0)
    }

    fun disassemble(
        apkFile: File,
        outDir: File,
        apiLevel: Int = 34,
        onProgress: (Float, String) -> Unit = { _, _ -> },
    ): Result {
        outDir.mkdirs()
        val container = runCatching { DexFileFactory.loadDexContainer(apkFile, Opcodes.forApi(apiLevel)) }
            .getOrElse { throw SmaliException("dex 容器加载失败：${it.message}", it) }
        val names = container.dexEntryNames.sorted()
        if (names.isEmpty()) throw SmaliException("APK 内没有 dex")

        var expected = 0
        val dexFiles = LinkedHashMap<String, DexFile>()
        names.forEach { name ->
            val entry = container.getEntry(name) ?: return@forEach
            dexFiles[name] = entry.dexFile
            expected += runCatching { entry.dexFile.classes.count() }.getOrDefault(0)
        }
        // 不做类型分析。
        //
        // 设备上没有 android.jar，ClassPath 里必然缺一大批框架类：
        // 强行分析要么因为一个坏类让整个 dex 抛 AssertionError，
        // 要么把阳型也当成异常大片跳过（实测 3.4 万个类只出来一千多）。
        // 改用隐式引用，把该出的类都反汇编出来，这才是这里真正需要的行为。

        var written = 0
        val failed = mutableListOf<String>()
        names.forEachIndexed { index, name ->
            val dex = dexFiles[name] ?: return@forEachIndexed
            onProgress(index.toFloat() / names.size, "反汇编 $name（${dex.classes.count()} 个类）")
            val options = BaksmaliOptions().apply {
                this.apiLevel = apiLevel
                parameterRegisters = true
                localsDirective = true
                sequentialLabels = false
                debugInfo = true
                codeOffsets = false
                accessorComments = true
                normalizeVirtualMethods = true
                implicitReferences = true
            }
            val target = if (names.size == 1) outDir else File(outDir, name.removeSuffix(".dex"))
            target.mkdirs()
            try {
                Baksmali.disassembleDexFile(dex, target, 1, options)
            } catch (error: Throwable) {
                // 单个 dex 出事不能拖垮整个任务：已经写出的文件照样可用，
                // 把原因记下来继续后面的 dex。
                failed += "$name：${error.message ?: error.javaClass.simpleName}"
            }
            written += target.walkTopDown().count { it.isFile && it.extension == "smali" }
        }
        onProgress(1f, "smali 输出完成：$written 个类")
        return Result(
            dir = outDir.absolutePath,
            writtenClasses = written,
            expectedClasses = expected,
            dexCount = names.size,
            failedDexes = failed,
        )
    }

    /** 读取某个类的 smali 文本：优先精确路径，找不到时按文件名兜底搜索。 */
    fun readSmali(outDir: File, className: String): String? {
        val relative = className.replace('.', '/') + ".smali"
        val direct = File(outDir, relative)
        if (direct.isFile) return direct.readText()
        val nested = outDir.listFiles()?.filter { it.isDirectory }?.map { File(it, relative) } ?: emptyList()
        nested.firstOrNull { it.isFile }?.let { return it.readText() }
        val simple = className.substringAfterLast('.') + ".smali"
        return outDir.walkTopDown().firstOrNull { it.isFile && it.name == simple }?.readText()
    }
}

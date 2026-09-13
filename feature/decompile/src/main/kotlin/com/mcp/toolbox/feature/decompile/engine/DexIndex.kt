package com.mcp.toolbox.feature.decompile.engine

import com.mcp.toolbox.feature.decompile.ClassInfo
import com.mcp.toolbox.feature.decompile.MemberInfo
import com.mcp.toolbox.feature.decompile.MemberKind
import com.mcp.toolbox.feature.decompile.SearchHit
import org.jf.dexlib2.AccessFlags
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.ClassDef
import org.jf.dexlib2.iface.DexFile
import org.jf.dexlib2.iface.Method
import org.jf.dexlib2.iface.instruction.ReferenceInstruction
import org.jf.dexlib2.iface.reference.StringReference
import org.jf.dexlib2.util.ReferenceUtil
import java.io.File

/**
 * 基于 dexlib2 的 DEX 索引：多 dex 类 / 方法 / 字段 / 字符串常量。
 * 纯本地解析，不依赖 root。
 */
class DexIndex(
    val classes: List<ClassInfo>,
    val stringConstants: List<String>,
    val dexEntries: List<String>,
) {
    val methodCount: Int get() = classes.sumOf { it.methods.size }
    val fieldCount: Int get() = classes.sumOf { it.fields.size }

    fun classByName(name: String): ClassInfo? = classes.firstOrNull { it.name == name || it.descriptor == name }

    fun search(query: String, limit: Int = 200): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        val q = query.trim()
        val lower = q.lowercase()
        val hits = ArrayList<SearchHit>()
        for (cls in classes) {
            if (cls.name.lowercase().contains(lower)) {
                hits += SearchHit("类", cls.packageName, cls.name, "方法 ${cls.methods.size} · 字段 ${cls.fields.size}")
                if (hits.size >= limit) return hits
            }
            for (m in cls.methods) {
                if (m.name.lowercase().contains(lower) || m.signature.lowercase().contains(lower)) {
                    hits += SearchHit("方法", cls.name, m.signature, m.accessText)
                    if (hits.size >= limit) return hits
                }
            }
            for (f in cls.fields) {
                if (f.name.lowercase().contains(lower) || f.signature.lowercase().contains(lower)) {
                    hits += SearchHit("字段", cls.name, f.signature, f.accessText)
                    if (hits.size >= limit) return hits
                }
            }
        }
        val regex = runCatching { Regex(q) }.getOrNull()
        for (s in stringConstants) {
            val matched = if (regex != null) regex.containsMatchIn(s) else s.lowercase().contains(lower)
            if (matched) {
                hits += SearchHit("字符串", "-", s.take(400), "常量池")
                if (hits.size >= limit) return hits
            }
        }
        return hits
    }
}

object DexIndexer {

    private const val MAX_STRINGS = 40000

    fun build(apkFile: File, onProgress: (Float, String) -> Unit = { _, _ -> }): DexIndex {
        val container = DexFileFactory.loadDexContainer(apkFile, Opcodes.getDefault())
        val names = container.dexEntryNames.sorted()
        // 预分配，避免 3 万+ 类在扩容时反复搬运造成 OOM（详见 19:57 的 OutOfMemoryError 记录）
        val classes = ArrayList<ClassInfo>(8192)
        val strings = LinkedHashSet<String>()
        names.forEachIndexed { index, name ->
            onProgress(index.toFloat() / names.size.coerceAtLeast(1), "解析 $name")
            val entry = container.getEntry(name) ?: return@forEachIndexed
            val dex: DexFile = entry.dexFile
            // 不缓存 ClassDef 列表：dexlib2 的类对象持有整个 dex 字节数组，
            // 迭代结束后立即释放引用，峰值内存只留当前 dex。
            for (cls in dex.classes) {
                classes += cls.toClassInfo(name, strings)
            }
        }
        onProgress(1f, "索引完成：${classes.size} 个类")
        return DexIndex(
            classes = classes.sortedBy { it.name },
            stringConstants = strings.toList(),
            dexEntries = names,
        )
    }

    private fun ClassDef.toClassInfo(dexEntry: String, strings: MutableSet<String>): ClassInfo {
        // 常量池去重集合有上限，避免一次性把几十万条字符串都留在内存里
        fun addString(value: String?) { if (value != null && strings.size < MAX_STRINGS) strings.add(value) }
        val name = typeToName(type)
        val packageName = name.substringBeforeLast('.', "")
        val simpleName = name.substringAfterLast('.')
        val superName = superclass?.let { typeToName(it) }
        addString(type)
        superclass?.let { addString(it) }
        interfaces.forEach { addString(it) }
        return ClassInfo(
            descriptor = type,
            name = name,
            packageName = packageName,
            simpleName = simpleName,
            superName = superName,
            interfaces = interfaces.map { typeToName(it) },
            accessText = accessFlags.accessText(isMethod = false),
            sourceFile = sourceFile,
            methods = methods.map { method ->
                addString(method.name)
                addString(method.returnType)
                method.parameterTypes.forEach { addString(it.toString()) }
                collectInstructionStrings(method, strings)
                MemberInfo(
                    signature = runCatching { ReferenceUtil.getMethodDescriptor(method) }
                        .getOrElse { "${method.definingClass}->${method.name}" },
                    name = method.name,
                    kind = if (method.name == "<init>" || method.name == "<clinit>") {
                        MemberKind.CONSTRUCTOR
                    } else {
                        MemberKind.METHOD
                    },
                    accessText = method.accessFlags.accessText(isMethod = true),
                    isAbstract = AccessFlags.ABSTRACT.isSet(method.accessFlags),
                )
            },
            fields = fields.map { field ->
                addString(field.name)
                addString(field.type)
                MemberInfo(
                    signature = "${field.name}: ${typeToName(field.type)}",
                    name = field.name,
                    kind = MemberKind.FIELD,
                    accessText = field.accessFlags.accessText(isMethod = false),
                    isAbstract = false,
                )
            },
            dexEntry = dexEntry,
        )
    }

    private fun collectInstructionStrings(method: Method, strings: MutableSet<String>) {
        val implementation = method.implementation ?: return
        if (strings.size >= MAX_STRINGS) return
        for (instruction in implementation.instructions) {
            val reference = (instruction as? ReferenceInstruction)?.reference ?: continue
            if (reference is StringReference) strings += reference.string
        }
    }

    fun typeToName(type: String): String = when {
        type.startsWith("L") && type.endsWith(";") -> type.substring(1, type.length - 1).replace('/', '.')
        else -> type
    }

    private val accessFlagsMap = listOf(
        AccessFlags.PUBLIC to "public",
        AccessFlags.PRIVATE to "private",
        AccessFlags.PROTECTED to "protected",
        AccessFlags.STATIC to "static",
        AccessFlags.FINAL to "final",
        AccessFlags.SYNCHRONIZED to "synchronized",
        AccessFlags.VOLATILE to "volatile",
        AccessFlags.TRANSIENT to "transient",
        AccessFlags.BRIDGE to "bridge",
        AccessFlags.VARARGS to "varargs",
        AccessFlags.NATIVE to "native",
        AccessFlags.INTERFACE to "interface",
        AccessFlags.ABSTRACT to "abstract",
        AccessFlags.STRICTFP to "strictfp",
        AccessFlags.SYNTHETIC to "synthetic",
        AccessFlags.ANNOTATION to "annotation",
        AccessFlags.ENUM to "enum",
        AccessFlags.CONSTRUCTOR to "constructor",
        AccessFlags.DECLARED_SYNCHRONIZED to "declared-synchronized",
    )

    private fun Int.accessText(isMethod: Boolean): String {
        val text = accessFlagsMap.filter { (flag, _) -> flag.isSet(this) }
            .joinToString(" ") { it.second }
        val kindText = if (isMethod) "" else if (AccessFlags.INTERFACE.isSet(this)) "interface " else "class "
        return (kindText + text).ifBlank { if (isMethod) "package-private" else "class" }
    }
}

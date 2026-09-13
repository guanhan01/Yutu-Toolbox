package com.mcp.toolbox.feature.mcp

import android.content.Context
import com.mcp.toolbox.feature.decompile.ClassInfo
import com.mcp.toolbox.feature.decompile.MemberInfo
import com.mcp.toolbox.feature.decompile.engine.ApkArchive
import com.mcp.toolbox.feature.decompile.engine.DecompileEngine
import com.mcp.toolbox.feature.decompile.engine.DexIndex
import com.mcp.toolbox.feature.decompile.engine.DexIndexer
import com.mcp.toolbox.feature.decompile.engine.JadxEngine
import com.mcp.toolbox.feature.decompile.engine.SmaliEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 直接调用本应用「反编译」模块的真实引擎（DexIndexer / baksmali / jadx）：
 * dex 索引检索、单类 smali、单类 Java、整包 smali / Java 产出。
 *
 * 这里不做任何模拟：引擎不可用时返回明确错误，不返回假数据。
 */
object BuiltInToolSetDex {

    private const val API_LEVEL = 34

    fun all(context: Context): List<ToolDef> = listOf(
        dexList(context),
        dexSearch(context),
        dexClass(context),
        dexStrings(context),
        dexSmali(context),
        dexJava(context),
        apkSmali(context),
        apkJava(context),
    )

    private var cachedKey: String? = null
    private var cachedIndex: DexIndex? = null

    /** dex 索引要几十秒，同一个 APK 复用；只留一份，避免在手机上把内存吃满。 */
    fun indexOf(apk: File): DexIndex = synchronized(this) {
        val key = apk.absolutePath + "@" + apk.length() + "@" + apk.lastModified()
        val hit = cachedIndex
        if (hit != null && cachedKey == key) return hit
        val built = DexIndexer.build(apk)
        cachedKey = key
        cachedIndex = built
        built
    }

    private fun memberJson(item: MemberInfo): JSONObject = JSONObject().apply {
        put("signature", item.signature)
        put("name", item.name)
        put("kind", item.kind.label)
        put("access", item.accessText)
        put("abstract", item.isAbstract)
    }

    private fun classBrief(cls: ClassInfo): JSONObject = JSONObject().apply {
        put("name", cls.name)
        put("descriptor", cls.descriptor)
        put("package", cls.packageName)
        put("super", cls.superName ?: JSONObject.NULL)
        put("interfaces", JSONArray(cls.interfaces))
        put("access", cls.accessText)
        put("sourceFile", cls.sourceFile ?: JSONObject.NULL)
        put("methodCount", cls.methods.size)
        put("fieldCount", cls.fields.size)
        put("dex", cls.dexEntry)
    }

    /** 归一化类名：接受 com.a.B、La/b/B;、a/b/B 三种写法。 */
    private fun normalizeClass(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("L") && trimmed.endsWith(";") ->
                trimmed.substring(1, trimmed.length - 1).replace('/', '.')
            trimmed.contains('/') && !trimmed.contains('.') -> trimmed.replace('/', '.')
            else -> trimmed
        }
    }

    private fun findClass(index: DexIndex, className: String): ClassInfo? =
        index.classByName(className)
            ?: index.classes.firstOrNull { it.name == className || it.descriptor == className }
            ?: index.classes.firstOrNull { it.name.endsWith(".$className") }

    private fun smaliDir(context: Context, apk: File): File =
        File(DecompileEngine.sessionDir(context, apk, apk.nameWithoutExtension), "smali")

    private fun javaDir(context: Context, apk: File): File =
        File(DecompileEngine.sessionDir(context, apk, apk.nameWithoutExtension), "java")

    private fun dexList(context: Context) = ToolDef(
        name = "dex.list",
        title = "dex 结构概览",
        description = "用本应用反编译模块的 dex 索引统计 APK：每个 classesN.dex 的类数，以及总类数 / 方法数 / 字段数 / 字符串数。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径（如 /sdcard/Download/a.apk），可空", default = ""),
            ),
        ),
        handler = { ctx, args ->
            val apk = BuiltInToolSetApk.target(ctx, args)
            val index = indexOf(apk)
            val perDex = JSONObject()
            index.classes.groupBy { it.dexEntry }.forEach { (dex, list) -> perDex.put(dex, list.size) }
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("dexCount", index.dexEntries.size)
                put("dexEntries", JSONArray(index.dexEntries))
                put("classesPerDex", perDex)
                put("classCount", index.classes.size)
                put("methodCount", index.methodCount)
                put("fieldCount", index.fieldCount)
                put("stringCount", index.stringConstants.size)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun dexSearch(context: Context) = ToolDef(
        name = "dex.search",
        title = "搜索类 / 方法 / 字段 / 字符串",
        description = "在 dex 索引里搜索类名、方法签名、字段与字符串常量（支持正则），返回命中条目与所属 dex。",
        schema = Schema.obj(
            listOf(
                "query" to Schema.string("关键词或正则"),
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
                "limit" to Schema.integer("最多返回条数", default = 80, min = 1, max = 500),
            ),
            required = listOf("query"),
        ),
        handler = { ctx, args ->
            val apk = BuiltInToolSetApk.target(ctx, args)
            val index = indexOf(apk)
            val limit = args.optInt("limit", 80).coerceIn(1, 500)
            val hits = index.search(args.getString("query"), limit)
            val array = JSONArray()
            hits.forEach { hit ->
                array.put(
                    JSONObject().apply {
                        put("kind", hit.kind)
                        put("owner", hit.owner)
                        put("text", hit.text)
                        put("detail", hit.detail)
                    },
                )
            }
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("query", args.getString("query"))
                put("count", array.length())
                put("truncated", array.length() >= limit)
                put("hits", array)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun dexClass(context: Context) = ToolDef(
        name = "dex.class",
        title = "类详情（字段 / 方法）",
        description = "查看某个类的父类、接口、访问级别，以及全部方法与字段签名。类名支持 com.a.B、La/b/B; 两种写法。",
        schema = Schema.obj(
            listOf(
                "class" to Schema.string("类名，例如 com.mcp.toolbox.MainActivity"),
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
                "limit" to Schema.integer("方法与字段各最多返回条数", default = 300, min = 1, max = 2000),
            ),
            required = listOf("class"),
        ),
        handler = { ctx, args ->
            val apk = BuiltInToolSetApk.target(ctx, args)
            val index = indexOf(apk)
            val className = normalizeClass(args.getString("class"))
            val cls = findClass(index, className)
                ?: throw IllegalArgumentException("没有找到类 $className（先用 dex.search 搜索）")
            val limit = args.optInt("limit", 300).coerceIn(1, 2000)
            val methods = JSONArray()
            cls.methods.take(limit).forEach { methods.put(memberJson(it)) }
            val fields = JSONArray()
            cls.fields.take(limit).forEach { fields.put(memberJson(it)) }
            val structured = classBrief(cls).apply {
                put("methodTotal", cls.methods.size)
                put("fieldTotal", cls.fields.size)
                put("methods", methods)
                put("fields", fields)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun dexStrings(context: Context) = ToolDef(
        name = "dex.strings",
        title = "字符串常量搜索",
        description = "在 dex 字符串池里检索（支持正则），常用于找 URL、密钥、提示语、协议关键字。",
        schema = Schema.obj(
            listOf(
                "query" to Schema.string("关键词或正则，可空（为空时按 limit 顺序返回）", default = ""),
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
                "limit" to Schema.integer("最多返回条数", default = 100, min = 1, max = 2000),
            ),
        ),
        handler = { ctx, args ->
            val apk = BuiltInToolSetApk.target(ctx, args)
            val index = indexOf(apk)
            val query = args.optString("query").trim()
            val limit = args.optInt("limit", 100).coerceIn(1, 2000)
            val regex = if (query.isEmpty()) null else runCatching { Regex(query) }.getOrNull()
            val matched = index.stringConstants.asSequence()
                .filter { raw ->
                    if (query.isEmpty()) {
                        true
                    } else if (regex != null) {
                        regex.containsMatchIn(raw)
                    } else {
                        raw.contains(query, ignoreCase = true)
                    }
                }
                .take(limit)
                .toList()
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("query", query)
                put("stringPoolSize", index.stringConstants.size)
                put("count", matched.size)
                put("truncated", matched.size >= limit)
                put("strings", JSONArray(matched))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun dexSmali(context: Context) = ToolDef(
        name = "dex.smali",
        title = "反汇编单个类（smali）",
        description = "用 baksmali 引擎反汇编 APK 并返回指定类的 smali 文本。首次调用需要整包反汇编，之后复用缓存目录。",
        schema = Schema.obj(
            listOf(
                "class" to Schema.string("类名，例如 com.mcp.toolbox.MainActivity"),
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
                "maxChars" to Schema.integer("最多返回字符数", default = 60000, min = 1000, max = 400000),
            ),
            required = listOf("class"),
        ),
        handler = { ctx, args ->
            val apk = BuiltInToolSetApk.target(ctx, args)
            val className = normalizeClass(args.getString("class"))
            val dir = smaliDir(ctx, apk)
            val ready = dir.isDirectory && (dir.listFiles()?.isNotEmpty() == true)
            if (!ready) SmaliEngine.disassemble(apk, dir, API_LEVEL)
            val text = SmaliEngine.readSmali(dir, className)
                ?: throw IllegalArgumentException("没有找到类 $className 的 smali 文件（先用 dex.search 确认类名）")
            val limit = args.optInt("maxChars", 60000).coerceIn(1000, 400000)
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("class", className)
                put("smaliDir", dir.absolutePath)
                put("length", text.length)
                put("truncated", text.length > limit)
                put("smali", text.take(limit))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun dexJava(context: Context) = ToolDef(
        name = "dex.java",
        title = "反编译单个类（Java）",
        description = "用 jadx 引擎把指定类反编译成 Java 源码。先按 dex 索引定位该类所在 dex，只喂这一个 dex，内存可控。",
        schema = Schema.obj(
            listOf(
                "class" to Schema.string("类名，例如 com.mcp.toolbox.MainActivity"),
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
                "maxChars" to Schema.integer("最多返回字符数", default = 60000, min = 1000, max = 400000),
            ),
            required = listOf("class"),
        ),
        handler = { ctx, args ->
            val apk = BuiltInToolSetApk.target(ctx, args)
            val className = normalizeClass(args.getString("class"))
            val index = indexOf(apk)
            val cls = findClass(index, className)
                ?: throw IllegalArgumentException("没有找到类 $className（先用 dex.search 搜索）")
            val dexName = cls.dexEntry
            val cacheDir = File(DecompileEngine.workDir(ctx), "dex-cache").apply { mkdirs() }
            val dexFile = File(cacheDir, dexName.substringAfterLast('/'))
            if (!dexFile.isFile) {
                ApkArchive(apk).use { archive ->
                    val bytes = archive.readEntry(dexName)
                        ?: throw IllegalStateException("APK 内缺少 $dexName")
                    dexFile.writeBytes(bytes)
                }
            }
            JadxEngine.availability()?.let { throw IllegalStateException(it) }
            val handle = JadxEngine.open(dexFile, null, threads = 1)
            try {
                val found = JadxEngine.findClass(handle, cls.name)
                    ?: handle.classes.firstOrNull { JadxEngine.classNameOf(it) == cls.name }
                    ?: handle.classes.firstOrNull { JadxEngine.classNameOf(it).endsWith(".$className") }
                    ?: throw IllegalStateException("jadx 未能加载类 ${cls.name}（该 dex 共 ${handle.classes.size} 个类）")
                val code = JadxEngine.codeOf(found)
                val limit = args.optInt("maxChars", 60000).coerceIn(1000, 400000)
                val structured = JSONObject().apply {
                    put("apk", apk.absolutePath)
                    put("class", JadxEngine.classNameOf(found))
                    put("dex", dexName)
                    put("length", code.length)
                    put("truncated", code.length > limit)
                    put("java", code.take(limit))
                }
                ToolResult(structured, structured.toString(2))
            } finally {
                JadxEngine.close(handle)
            }
        },
    )

    private fun apkSmali(context: Context) = ToolDef(
        name = "apk.smali",
        title = "整包反汇编为 smali",
        description = "对整个 APK 做 smali 反汇编，产物写入应用沙箱的反编译目录，返回写出类数与失败 dex。属于重操作。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
            ),
        ),
        readOnly = false,
        handler = { ctx, args ->
            val apk = BuiltInToolSetApk.target(ctx, args)
            val dir = smaliDir(ctx, apk)
            val result = SmaliEngine.disassemble(apk, dir, API_LEVEL)
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("dir", result.dir)
                put("dexCount", result.dexCount)
                put("writtenClasses", result.writtenClasses)
                put("expectedClasses", result.expectedClasses)
                put("skipped", result.skipped)
                put("failedDexes", JSONArray(result.failedDexes))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun apkJava(context: Context) = ToolDef(
        name = "apk.java",
        title = "整包反编译为 Java",
        description = "用 jadx 逐个 dex 反编译整个 APK 为 Java 源码（逐个 dex 喂是为了避免手机上 OOM），产物在应用沙箱。属于重操作。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
            ),
        ),
        readOnly = false,
        handler = { ctx, args ->
            JadxEngine.availability()?.let { throw IllegalStateException(it) }
            val apk = BuiltInToolSetApk.target(ctx, args)
            val outDir = javaDir(ctx, apk).apply { mkdirs() }
            val dexNames = ApkArchive(apk).use { it.dexEntries() }
            val tempDir = File(outDir.parentFile ?: outDir, "dex-tmp").apply { mkdirs() }
            val failed = mutableListOf<String>()
            var loaded = 0
            dexNames.forEach { name ->
                val single = File(tempDir, name.substringAfterLast('/'))
                val extracted = runCatching {
                    ApkArchive(apk).use { archive ->
                        val bytes = archive.readEntry(name)
                        if (bytes == null) false else {
                            single.writeBytes(bytes)
                            true
                        }
                    }
                }.getOrDefault(false)
                if (!extracted) {
                    failed += name
                    return@forEach
                }
                try {
                    val handle = JadxEngine.open(single, outDir, threads = 1)
                    try {
                        JadxEngine.saveAll(handle)
                        loaded += handle.classes.size
                    } finally {
                        JadxEngine.close(handle)
                    }
                } catch (error: Throwable) {
                    failed += "$name（${error.javaClass.simpleName}）"
                } finally {
                    single.delete()
                }
            }
            val written = outDir.walkTopDown().count { it.isFile && it.extension == "java" }
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("dir", outDir.absolutePath)
                put("writtenFiles", written)
                put("loadedClasses", loaded)
                put("dexCount", dexNames.size)
                put("failedDexes", JSONArray(failed))
            }
            ToolResult(structured, structured.toString(2))
        },
    )
}

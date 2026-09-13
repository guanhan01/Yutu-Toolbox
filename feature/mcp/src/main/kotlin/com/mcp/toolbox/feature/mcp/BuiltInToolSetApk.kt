package com.mcp.toolbox.feature.mcp

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.mcp.toolbox.feature.decompile.CertInfo
import com.mcp.toolbox.feature.decompile.ManifestInfo
import com.mcp.toolbox.feature.decompile.engine.ApkArchive
import com.mcp.toolbox.feature.decompile.engine.ArscParser
import com.mcp.toolbox.feature.decompile.engine.AxmlParser
import com.mcp.toolbox.feature.decompile.engine.DecompileEngine
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 直接调用本应用「反编译」模块的引擎（ApkArchive / AxmlParser / ArscParser）：
 * APK 摘要、清单、权限、组件、签名、条目、资源表与文本导出。
 *
 * 全部结果来自真实解析；引擎读不到就报错，不编造。
 */
object BuiltInToolSetApk {

    fun all(context: Context): List<ToolDef> = listOf(
        apkSources(context),
        apkInfo(context),
        apkManifest(context),
        apkPermissions(context),
        apkComponents(context),
        apkSignature(context),
        apkEntries(context),
        apkExtract(context),
        apkResources(context),
        apkExport(context),
    )

    private fun requireWrite() {
        if (!BuiltInMcpServer.config.value.allowWrite) {
            error("内置 Server 未开启写入：请在 MCP 页面打开「允许写入」后重试")
        }
    }

    /**
     * 目标 APK 解析：
     * - `apk` 为允许根内的路径（/sdcard、/data/local/tmp、产物目录等）
     * - `package` 为已安装应用包名
     * - 两者都空时取本应用自己的 APK
     */
    fun target(context: Context, args: JSONObject): File {
        val path = args.optString("apk").trim()
        if (path.isNotEmpty()) {
            val file = ToolSupport.resolve(context, if (path.startsWith("~/")) path else path)
            require(file.isFile) { "APK 不存在：${file.absolutePath}" }
            return file
        }
        val pkg = args.optString("package").trim()
        if (pkg.isEmpty()) return File(context.applicationInfo.sourceDir)
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        val dir = info.sourceDir ?: throw IllegalStateException("无法获取 $pkg 的 APK 路径")
        val file = File(dir)
        if (!file.canRead()) {
            throw IllegalStateException("无权读取 $dir（部分应用在没有 root / Shizuku 时不可读）")
        }
        return file
    }

    private fun certJson(cert: CertInfo): JSONObject = JSONObject().apply {
        put("subject", cert.subject)
        put("issuer", cert.issuer)
        put("sha256", cert.sha256)
        put("sha1", cert.sha1)
        put("algorithm", cert.algorithm)
        put("serial", cert.serial)
        put("notBefore", cert.notBefore)
        put("notAfter", cert.notAfter)
        put("source", cert.fileName)
    }

    private fun manifestOf(archive: ApkArchive): ManifestInfo? = runCatching {
        val bytes = archive.readEntry("AndroidManifest.xml") ?: return@runCatching null
        val arsc = archive.readEntry("resources.arsc")?.let { raw -> runCatching { ArscParser(raw) }.getOrNull() }
        AxmlParser.toManifest(AxmlParser.parse(bytes) { id -> arsc?.resolve(id) })
    }.getOrNull()

    private fun apkSources(context: Context) = ToolDef(
        name = "apk.sources",
        title = "可分析的 APK 来源",
        description = "列出可分析的 APK：本应用与已安装应用（包名、名称、APK 路径、大小、可读性）。可读性为 false 时需要 root / Shizuku 才能分析。",
        schema = Schema.obj(
            listOf(
                "query" to Schema.string("包名或应用名关键词，可空", default = ""),
                "includeSystem" to Schema.bool("是否包含系统应用", default = false),
                "limit" to Schema.integer("最多返回数量", default = 40, min = 1, max = 300),
            ),
        ),
        handler = { ctx, args ->
            val pm = ctx.packageManager
            val query = args.optString("query").lowercase()
            val includeSystem = args.optBoolean("includeSystem")
            val limit = args.optInt("limit", 40).coerceIn(1, 300)
            val rows = JSONArray()
            pm.getInstalledApplications(PackageManager.GET_META_DATA).forEach { info ->
                val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (system && !includeSystem) return@forEach
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                val hit = query.isEmpty() ||
                    label.lowercase().contains(query) ||
                    info.packageName.lowercase().contains(query)
                if (!hit || rows.length() >= limit) return@forEach
                val apk = info.sourceDir?.let { File(it) }
                rows.put(
                    JSONObject().apply {
                        put("label", label)
                        put("package", info.packageName)
                        put("system", system)
                        put("apkPath", apk?.absolutePath ?: JSONObject.NULL)
                        put("readable", apk?.canRead() ?: false)
                        put("sizeBytes", apk?.length() ?: 0)
                        put("sizeHuman", apk?.let { ToolSupport.human(it.length()) } ?: "-")
                        put("isSelf", info.packageName == ctx.packageName)
                    },
                )
            }
            val structured = JSONObject().apply {
                put("count", rows.length())
                put("self", ctx.applicationInfo.sourceDir)
                put("apps", rows)
                put("hint", "分析指定应用直接传 package=包名；分析下载的 APK 传 apk=/sdcard/Download/xxx.apk")
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun apkInfo(context: Context) = ToolDef(
        name = "apk.info",
        title = "APK 摘要（含包名版本签名）",
        description = "读取 APK 的真实摘要：大小、MD5/SHA-256、条目数、dex 列表、native 库、签名证书，以及清单里的包名 / 版本 / minSdk / targetSdk。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
            ),
        ),
        handler = { ctx, args ->
            val apk = target(ctx, args)
            val structured = ApkArchive(apk).use { archive ->
                val summary = archive.summary(ctx, apk.name)
                val manifest = manifestOf(archive)
                JSONObject().apply {
                    put("apk", summary.path)
                    put("name", summary.displayName)
                    put("sizeBytes", summary.sizeBytes)
                    put("sizeHuman", ToolSupport.human(summary.sizeBytes))
                    put("md5", summary.md5)
                    put("sha256", summary.sha256)
                    put("entryCount", summary.entryCount)
                    put("dexCount", summary.dexEntries.size)
                    put("dexEntries", JSONArray(summary.dexEntries))
                    put("nativeLibs", JSONArray(summary.nativeLibs))
                    put("signed", summary.signed)
                    put("certificate", summary.certificate?.let { certJson(it) } ?: JSONObject.NULL)
                    put("packageName", manifest?.packageName ?: JSONObject.NULL)
                    put("versionName", manifest?.versionName ?: JSONObject.NULL)
                    put("versionCode", manifest?.versionCode ?: JSONObject.NULL)
                    put("minSdk", manifest?.minSdk ?: JSONObject.NULL)
                    put("targetSdk", manifest?.targetSdk ?: JSONObject.NULL)
                    put("permissionCount", manifest?.permissions?.size ?: 0)
                    put("componentCount", manifest?.groups?.sumOf { it.items.size } ?: 0)
                }
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun apkManifest(context: Context) = ToolDef(
        name = "apk.manifest",
        title = "解析 AndroidManifest",
        description = "用本应用的二进制 XML 解析器读取 AndroidManifest.xml：包名、版本、SDK、权限、uses-feature、四类组件，并可返回原始 XML 文本。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
                "includeXml" to Schema.bool("是否返回原始 XML 文本", default = true),
                "maxXmlChars" to Schema.integer("XML 最多返回字符数", default = 20000, min = 500, max = 200000),
            ),
        ),
        handler = { ctx, args ->
            val apk = target(ctx, args)
            val manifest = ApkArchive(apk).use { manifestOf(it) }
                ?: throw IllegalStateException("解析 AndroidManifest.xml 失败（可能不是有效 APK）")
            val groups = JSONArray()
            manifest.groups.forEach { group ->
                groups.put(
                    JSONObject().apply {
                        put("kind", group.kind)
                        put("count", group.items.size)
                        put("items", JSONArray(group.items))
                    },
                )
            }
            val limit = args.optInt("maxXmlChars", 20000).coerceIn(500, 200000)
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("packageName", manifest.packageName)
                put("versionName", manifest.versionName ?: JSONObject.NULL)
                put("versionCode", manifest.versionCode ?: JSONObject.NULL)
                put("minSdk", manifest.minSdk ?: JSONObject.NULL)
                put("targetSdk", manifest.targetSdk ?: JSONObject.NULL)
                put("attributeCount", manifest.attributeCount)
                put("permissions", JSONArray(manifest.permissions))
                put("declaredPermissions", JSONArray(manifest.declaredPermissions))
                put("usesFeatures", JSONArray(manifest.usesFeatures))
                put("components", groups)
                if (args.optBoolean("includeXml", true)) {
                    put("xmlTruncated", manifest.xml.length > limit)
                    put("xml", manifest.xml.take(limit))
                }
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun apkPermissions(context: Context) = ToolDef(
        name = "apk.permissions",
        title = "APK 申请的权限",
        description = "列出 APK 申请的权限（申请 + 自定义声明），并标出其中的危险权限类别，便于评估分析目标。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
            ),
        ),
        handler = { ctx, args ->
            val apk = target(ctx, args)
            val manifest = ApkArchive(apk).use { manifestOf(it) }
                ?: throw IllegalStateException("解析 AndroidManifest.xml 失败")
            val dangerousKeywords = listOf(
                "CAMERA", "RECORD_AUDIO", "READ_CONTACTS", "WRITE_CONTACTS", "ACCESS_FINE_LOCATION",
                "ACCESS_COARSE_LOCATION", "READ_SMS", "SEND_SMS", "READ_CALL_LOG", "WRITE_CALL_LOG",
                "READ_PHONE_STATE", "CALL_PHONE", "READ_EXTERNAL_STORAGE", "WRITE_EXTERNAL_STORAGE",
                "MANAGE_EXTERNAL_STORAGE", "SYSTEM_ALERT_WINDOW", "REQUEST_INSTALL_PACKAGES",
                "QUERY_ALL_PACKAGES", "BIND_ACCESSIBILITY_SERVICE", "PACKAGE_USAGE_STATS",
                "READ_LOGS", "WRITE_SECURE_SETTINGS", "MANAGE_ACCOUNTS", "READ_CALENDAR", "BODY_SENSORS",
            )
            val rows = JSONArray()
            manifest.permissions.forEach { perm ->
                val short = perm.substringAfterLast('.')
                rows.put(
                    JSONObject().apply {
                        put("permission", perm)
                        put("short", short)
                        put("sensitive", dangerousKeywords.any { perm.contains(it) })
                        put("declared", false)
                    },
                )
            }
            manifest.declaredPermissions.forEach { perm ->
                rows.put(JSONObject().put("permission", perm).put("short", perm.substringAfterLast('.')).put("sensitive", false).put("declared", true))
            }
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("packageName", manifest.packageName)
                put("count", rows.length())
                put("sensitiveCount", manifest.permissions.count { perm -> dangerousKeywords.any { perm.contains(it) } })
                put("permissions", rows)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun apkComponents(context: Context) = ToolDef(
        name = "apk.components",
        title = "APK 组件清单",
        description = "列出 APK 的四类组件（Activity / Service / Receiver / Provider）名称与数量，含 exported 相关项的原始名称。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
                "kind" to Schema.string("只看某一类，可空", default = ""),
            ),
        ),
        handler = { ctx, args ->
            val apk = target(ctx, args)
            val manifest = ApkArchive(apk).use { manifestOf(it) }
                ?: throw IllegalStateException("解析 AndroidManifest.xml 失败")
            val want = args.optString("kind").trim().lowercase()
            val rows = JSONArray()
            manifest.groups.forEach { group ->
                if (want.isNotEmpty() && !group.kind.lowercase().contains(want)) return@forEach
                rows.put(
                    JSONObject().apply {
                        put("kind", group.kind)
                        put("count", group.items.size)
                        put("items", JSONArray(group.items))
                    },
                )
            }
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("packageName", manifest.packageName)
                put("total", manifest.groups.sumOf { it.items.size })
                put("groups", rows)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun apkSignature(context: Context) = ToolDef(
        name = "apk.signature",
        title = "APK 签名证书",
        description = "读取 APK 签名证书：主题、签发者、SHA-256 / SHA-1 指纹、算法、序列号与有效期。未签名时明确返回 signed=false。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
            ),
        ),
        handler = { ctx, args ->
            val apk = target(ctx, args)
            val cert = ApkArchive(apk).use { it.certificate(ctx) }
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("signed", cert != null)
                put("certificate", cert?.let { certJson(it) } ?: JSONObject.NULL)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun apkEntries(context: Context) = ToolDef(
        name = "apk.entries",
        title = "APK 内部文件列表",
        description = "列出 APK 压缩包内的条目（路径、压缩/原始大小），支持关键词过滤与按大小排序，用于快速定位 so、资源、assets。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
                "query" to Schema.string("路径关键词，可空", default = ""),
                "sort" to Schema.string("排序方式", default = "size", enum = listOf("size", "name")),
                "limit" to Schema.integer("最多返回条数", default = 100, min = 1, max = 3000),
            ),
        ),
        handler = { ctx, args ->
            val apk = target(ctx, args)
            val query = args.optString("query")
            val limit = args.optInt("limit", 100).coerceIn(1, 3000)
            val sortBySize = args.optString("sort", "size") == "size"
            val all = ApkArchive(apk).use { archive -> archive.entries }
            val filtered = all.asSequence()
                .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
                .toList()
            val ordered = if (sortBySize) filtered.sortedByDescending { it.size } else filtered.sortedBy { it.name }
            val rows = JSONArray()
            var totalBytes = 0L
            filtered.forEach { totalBytes += it.size }
            ordered.take(limit).forEach { entry ->
                rows.put(
                    JSONObject().apply {
                        put("name", entry.name)
                        put("sizeBytes", entry.size)
                        put("sizeHuman", ToolSupport.human(entry.size))
                        put("compressedBytes", entry.compressedSize)
                        put("directory", entry.isDirectory)
                    },
                )
            }
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("query", query)
                put("totalEntries", all.size)
                put("matchedEntries", filtered.size)
                put("matchedBytes", totalBytes)
                put("matchedHuman", ToolSupport.human(totalBytes))
                put("count", rows.length())
                put("truncated", filtered.size > rows.length())
                put("entries", rows)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun apkExtract(context: Context) = ToolDef(
        name = "apk.extract",
        title = "解出 APK 内部文件",
        description = "把 APK 内的某个条目解出来落盘（默认写入应用沙箱 decompile/extracted），并返回 sha256。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "name" to Schema.string("APK 内条目路径，例如 classes.dex 或 lib/arm64-v8a/libx.so"),
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
                "outDir" to Schema.string("输出目录，空则用沙箱 decompile/extracted", default = ""),
                "fileName" to Schema.string("输出文件名，空则用条目名", default = ""),
            ),
            required = listOf("name"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            requireWrite()
            val apk = target(ctx, args)
            val entryName = args.getString("name")
            val bytes = ApkArchive(apk).use { it.readEntry(entryName) }
                ?: throw IllegalArgumentException("APK 内没有条目：$entryName（可用 apk.entries 查看）")
            val dir = args.optString("outDir").trim().let { raw ->
                if (raw.isEmpty()) File(DecompileEngine.workDir(ctx), "extracted") else ToolSupport.resolve(ctx, raw)
            }
            dir.mkdirs()
            val fileName = args.optString("fileName").trim().ifEmpty { entryName.substringAfterLast('/') }
            val out = File(dir, fileName)
            out.writeBytes(bytes)
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("entry", entryName)
                put("path", out.absolutePath)
                put("sizeBytes", bytes.size)
                put("sizeHuman", ToolSupport.human(bytes.size.toLong()))
                put("sha256", ToolSupport.sha256(bytes))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun apkResources(context: Context) = ToolDef(
        name = "apk.resources",
        title = "资源表（resources.arsc）",
        description = "解析 resources.arsc：包名、各类型条目数、配置数，并可按关键词检索资源项（类型 / 名称 / 值 / 资源 ID）。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
                "query" to Schema.string("关键词（匹配名称或值），可空", default = ""),
                "type" to Schema.string("只看某类型，如 string / drawable / layout，可空", default = ""),
                "limit" to Schema.integer("最多返回条数", default = 100, min = 1, max = 3000),
            ),
        ),
        handler = { ctx, args ->
            val apk = target(ctx, args)
            val info = ApkArchive(apk).use { archive ->
                val bytes = archive.readEntry("resources.arsc")
                    ?: throw IllegalStateException("APK 内没有 resources.arsc")
                ArscParser(bytes).parse()
            }
            val query = args.optString("query")
            val type = args.optString("type").trim().lowercase()
            val limit = args.optInt("limit", 100).coerceIn(1, 3000)
            val matched = info.entries.asSequence()
                .filter { type.isEmpty() || it.type.lowercase() == type }
                .filter { query.isEmpty() || it.name.contains(query, true) || it.value.contains(query, true) }
                .take(limit)
                .toList()
            val rows = JSONArray()
            matched.forEach { entry ->
                rows.put(
                    JSONObject().apply {
                        put("type", entry.type)
                        put("name", entry.name)
                        put("value", entry.value)
                        put("resId", "0x%08x".format(entry.resId))
                        put("complex", entry.complex)
                    },
                )
            }
            val typeCounts = JSONObject()
            info.typeCounts.forEach { (kind, count) -> typeCounts.put(kind, count) }
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("packages", JSONArray(info.packages))
                put("configCount", info.configCount)
                put("entryTotal", info.entries.size)
                put("typeCounts", typeCounts)
                put("query", query)
                put("count", rows.length())
                put("truncated", matched.size >= limit)
                put("entries", rows)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun apkExport(context: Context) = ToolDef(
        name = "apk.export",
        title = "导出清单 / 类表 / 方法 / 字符串",
        description = "把分析结果导出为文本：AndroidManifest.xml、classes.csv、methods.txt、strings.txt，写入指定目录（默认沙箱 decompile/exports/<APK 名>）。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("已安装应用包名，可空", default = ""),
                "apk" to Schema.string("允许根内的 APK 路径，可空", default = ""),
                "outDir" to Schema.string("输出目录，空则用沙箱 decompile/exports", default = ""),
            ),
        ),
        readOnly = false,
        handler = { ctx, args ->
            requireWrite()
            val apk = target(ctx, args)
            val base = args.optString("outDir").trim().let { raw ->
                if (raw.isEmpty()) File(DecompileEngine.workDir(ctx), "exports") else ToolSupport.resolve(ctx, raw)
            }
            val dir = File(base, apk.nameWithoutExtension + "-" + System.currentTimeMillis()).apply { mkdirs() }
            val index = BuiltInToolSetDex.indexOf(apk)
            val manifest = ApkArchive(apk).use { manifestOf(it) }
            val written = JSONArray()
            manifest?.let { info ->
                val file = File(dir, "AndroidManifest.xml")
                file.writeText(info.xml)
                written.put(file.absolutePath)
            }
            val csv = File(dir, "classes.csv")
            csv.writeText(
                buildString {
                    append("class,super,methods,fields,dex\n")
                    index.classes.forEach { cls ->
                        append("${cls.name},${cls.superName ?: ""},${cls.methods.size},${cls.fields.size},${cls.dexEntry}\n")
                    }
                },
            )
            written.put(csv.absolutePath)
            val methods = File(dir, "methods.txt")
            methods.writeText(
                buildString {
                    index.classes.forEach { cls ->
                        cls.methods.forEach { append("${cls.name}->${it.signature} ${it.accessText}\n") }
                    }
                },
            )
            written.put(methods.absolutePath)
            val strings = File(dir, "strings.txt")
            strings.writeText(index.stringConstants.joinToString("\n"))
            written.put(strings.absolutePath)
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("dir", dir.absolutePath)
                put("classCount", index.classes.size)
                put("methodCount", index.methodCount)
                put("stringCount", index.stringConstants.size)
                put("files", written)
            }
            ToolResult(structured, structured.toString(2))
        },
    )
}

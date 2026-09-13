package com.mcp.toolbox.feature.mcp

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 文件系统的「动手」工具：复制、移动、改名、打包、解包、校验、批量替换、内容检索。
 * 全部真实落盘，路径一律走 [ToolSupport.resolve] 的允许根校验，越界直接拒绝。
 */
object BuiltInToolSetFs {

    fun all(context: Context): List<ToolDef> = listOf(
        fileCopy(context),
        fileMove(context),
        fileRename(context),
        fileInfo(context),
        fileTree(context),
        fileHash(context),
        fileZip(context),
        fileUnzip(context),
        fileDu(context),
        fileReplace(context),
        fileReadRange(context),
        fileGrep(context),
        fileChmod(context),
    )

    private fun requireWrite() {
        if (!BuiltInMcpServer.config.value.allowWrite) {
            error("内置 Server 未开启写入：请在 MCP 页面打开「允许写入」后重试")
        }
    }

    private fun digestOf(file: File, algorithm: String): String {
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

    private fun copyTree(source: File, target: File): Pair<Int, Long> {
        if (source.isDirectory) {
            target.mkdirs()
            var count = 0
            var bytes = 0L
            source.listFiles()?.forEach { child ->
                val (c, b) = copyTree(child, File(target, child.name))
                count += c
                bytes += b
            }
            return count to bytes
        }
        target.parentFile?.mkdirs()
        source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
        return 1 to target.length()
    }

    private fun fileCopy(context: Context) = ToolDef(
        name = "file.copy",
        title = "复制文件 / 目录",
        description = "把文件或整个目录复制到目标路径（目录递归）。目录已存在时把源放进目标目录下。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "from" to Schema.string("源路径"),
                "to" to Schema.string("目标路径"),
                "overwrite" to Schema.bool("目标已存在时是否覆盖", default = true),
            ),
            required = listOf("from", "to"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            requireWrite()
            val source = ToolSupport.resolve(ctx, args.getString("from"))
            require(source.exists()) { "源不存在：${source.absolutePath}" }
            val rawTarget = ToolSupport.resolve(ctx, args.getString("to"))
            val overwrite = args.optBoolean("overwrite", true)
            val target = if (rawTarget.isDirectory || (source.isDirectory && !rawTarget.exists() && rawTarget.extension.isEmpty())) {
                if (rawTarget.isDirectory) File(rawTarget, source.name) else rawTarget
            } else {
                rawTarget
            }
            if (target.exists() && !overwrite) {
                throw IllegalStateException("目标已存在且 overwrite=false：${target.absolutePath}")
            }
            val (files, bytes) = copyTree(source, target)
            val structured = JSONObject().apply {
                put("from", source.absolutePath)
                put("to", target.absolutePath)
                put("files", files)
                put("bytes", bytes)
                put("human", ToolSupport.human(bytes))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileMove(context: Context) = ToolDef(
        name = "file.move",
        title = "移动 / 剪切文件",
        description = "把文件或目录移动到目标位置（同分区优先 rename，跨分区自动退化为复制后删除）。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "from" to Schema.string("源路径"),
                "to" to Schema.string("目标路径"),
            ),
            required = listOf("from", "to"),
        ),
        readOnly = false,
        dangerous = true,
        handler = { ctx, args ->
            requireWrite()
            val source = ToolSupport.resolve(ctx, args.getString("from"))
            require(source.exists()) { "源不存在：${source.absolutePath}" }
            val resolved = ToolSupport.resolve(ctx, args.getString("to"))
            val target = if (resolved.isDirectory) File(resolved, source.name) else resolved
            if (target.exists()) throw IllegalStateException("目标已存在：${target.absolutePath}")
            val renamed = source.renameTo(target)
            if (renamed) {
                val structured = JSONObject().apply {
                    put("from", source.absolutePath)
                    put("to", target.absolutePath)
                    put("mode", "rename")
                }
                ToolResult(structured, structured.toString(2))
            } else {
            val (files, bytes) = copyTree(source, target)
            val removed = source.deleteRecursively()
            val structured = JSONObject().apply {
                put("from", source.absolutePath)
                put("to", target.absolutePath)
                put("mode", "copy+delete")
                put("files", files)
                put("bytes", bytes)
                put("sourceRemoved", removed)
            }
            ToolResult(structured, structured.toString(2))
            }
        },
    )

    private fun fileRename(context: Context) = ToolDef(
        name = "file.rename",
        title = "重命名",
        description = "在所在目录内重命名文件或目录。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("原路径"),
                "newName" to Schema.string("新名称（只写名字，不含路径）"),
            ),
            required = listOf("path", "newName"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            requireWrite()
            val source = ToolSupport.resolve(ctx, args.getString("path"))
            require(source.exists()) { "路径不存在：${source.absolutePath}" }
            val name = args.getString("newName")
            require(!name.contains('/')) { "newName 只能是名字，不能含 /" }
            val target = File(source.parentFile, name)
            if (target.exists()) throw IllegalStateException("目标已存在：${target.absolutePath}")
            if (!source.renameTo(target)) throw IllegalStateException("重命名失败（可能是权限或跨目录）")
            val structured = JSONObject().apply {
                put("from", source.absolutePath)
                put("to", target.absolutePath)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileInfo(context: Context) = ToolDef(
        name = "file.info",
        title = "文件详情",
        description = "返回文件或目录的详细信息：类型、大小、读写执行权限、修改时间、文本行数、是否二进制、sha256。",
        schema = Schema.obj(
            listOf("path" to Schema.string("目标路径")),
            required = listOf("path"),
        ),
        handler = { ctx, args ->
            val file = ToolSupport.resolve(ctx, args.getString("path"))
            require(file.exists()) { "路径不存在：${file.absolutePath}" }
            val textLike = file.isFile && file.length() <= 4L * 1024 * 1024
            var lines = 0
            var binary = false
            if (textLike) {
                runCatching {
                    file.inputStream().bufferedReader().use { reader ->
                        val sample = CharArray(4096)
                        val read = reader.read(sample)
                        if (read > 0) binary = sample.take(read).any { it == '\u0000' }
                        while (reader.read(sample) > 0) lines++
                    }
                }
            }
            val structured = JSONObject().apply {
                put("path", file.absolutePath)
                put("name", file.name)
                put("parent", file.parent ?: JSONObject.NULL)
                put("directory", file.isDirectory)
                put("file", file.isFile)
                put("sizeBytes", if (file.isDirectory) 0 else file.length())
                put("sizeHuman", if (file.isDirectory) "-" else ToolSupport.human(file.length()))
                put("modifiedAt", file.lastModified())
                put("modifiedText", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(file.lastModified())))
                put("readable", file.canRead())
                put("writable", file.canWrite())
                put("executable", file.canExecute())
                put("hidden", file.isHidden)
                put("textLines", lines)
                put("binary", binary)
                if (file.isFile) put("sha256", digestOf(file, "SHA-256"))
                if (file.isDirectory) put("childCount", file.listFiles()?.size ?: 0)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileTree(context: Context) = ToolDef(
        name = "file.tree",
        title = "目录树",
        description = "递归列目录树（返回相对路径、层级、大小），可限制深度与条数，用于快速了解工程 / 目录结构。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("根路径", default = "/sdcard"),
                "maxDepth" to Schema.integer("最大深度", default = 3, min = 1, max = 12),
                "limit" to Schema.integer("最多返回条数", default = 300, min = 1, max = 3000),
                "dirsOnly" to Schema.bool("只列目录", default = false),
            ),
        ),
        handler = { ctx, args ->
            val root = ToolSupport.resolve(ctx, args.optString("path", "/sdcard"))
            require(root.exists()) { "路径不存在：${root.absolutePath}" }
            val maxDepth = args.optInt("maxDepth", 3).coerceIn(1, 12)
            val limit = args.optInt("limit", 300).coerceIn(1, 3000)
            val dirsOnly = args.optBoolean("dirsOnly")
            val rows = JSONArray()
            if (root.isFile) {
                rows.put(JSONObject().put("path", root.name).put("depth", 0).put("sizeBytes", root.length()))
            } else {
                root.walkTopDown().maxDepth(maxDepth).forEach { file ->
                    if (file == root) return@forEach
                    if (rows.length() >= limit) return@forEach
                    if (dirsOnly && !file.isDirectory) return@forEach
                    rows.put(
                        JSONObject().apply {
                            put("path", file.relativeTo(root).path)
                            put("depth", file.relativeTo(root).path.count { it == '/' } + 1)
                            put("directory", file.isDirectory)
                            put("sizeBytes", if (file.isDirectory) 0 else file.length())
                        },
                    )
                }
            }
            val structured = JSONObject().apply {
                put("root", root.absolutePath)
                put("count", rows.length())
                put("truncated", rows.length() >= limit)
                put("entries", rows)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileHash(context: Context) = ToolDef(
        name = "file.hash",
        title = "文件校验值",
        description = "计算文件的大小与 MD5 / SHA-1 / SHA-256（流式读取，大文件也可用）。",
        schema = Schema.obj(
            listOf("path" to Schema.string("文件路径")),
            required = listOf("path"),
        ),
        handler = { ctx, args ->
            val file = ToolSupport.resolve(ctx, args.getString("path"))
            require(file.isFile) { "不是文件：${file.absolutePath}" }
            val structured = JSONObject().apply {
                put("path", file.absolutePath)
                put("sizeBytes", file.length())
                put("sizeHuman", ToolSupport.human(file.length()))
                put("md5", digestOf(file, "MD5"))
                put("sha1", digestOf(file, "SHA-1"))
                put("sha256", digestOf(file, "SHA-256"))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileZip(context: Context) = ToolDef(
        name = "file.zip",
        title = "打包为 zip",
        description = "把一个或多个文件 / 目录打包成 zip（目录递归，保留相对路径）。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "paths" to Schema.string("源路径，多个用英文逗号分隔"),
                "out" to Schema.string("输出 zip 路径"),
                "level" to Schema.integer("压缩级别 0-9", default = 6, min = 0, max = 9),
            ),
            required = listOf("paths", "out"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            requireWrite()
            val sources = args.getString("paths").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .map { ToolSupport.resolve(ctx, it) }
            require(sources.isNotEmpty()) { "paths 为空" }
            sources.forEach { require(it.exists()) { "源不存在：${it.absolutePath}" } }
            val out = ToolSupport.resolve(ctx, args.getString("out"))
            out.parentFile?.mkdirs()
            var count = 0
            var bytes = 0L
            ZipOutputStream(out.outputStream().buffered()).use { zip ->
                zip.setLevel(args.optInt("level", 6).coerceIn(0, 9))
                sources.forEach { source ->
                    val base = source.parentFile ?: source
                    val files = if (source.isDirectory) source.walkTopDown().filter { it.isFile }.toList() else listOf(source)
                    files.forEach { file ->
                        val entryName = file.relativeTo(base).path.replace('\\', '/')
                        zip.putNextEntry(ZipEntry(entryName))
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        count++
                        bytes += file.length()
                    }
                }
            }
            val structured = JSONObject().apply {
                put("out", out.absolutePath)
                put("entries", count)
                put("sourceBytes", bytes)
                put("zipBytes", out.length())
                put("ratio", if (bytes > 0) "%.1f%%".format(out.length() * 100.0 / bytes) else "-")
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileUnzip(context: Context) = ToolDef(
        name = "file.unzip",
        title = "解压 zip",
        description = "把 zip 解压到目标目录（带 Zip Slip 越界防护）。可用于解开 APK 拿 so / assets / dex。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "zip" to Schema.string("zip 文件路径"),
                "outDir" to Schema.string("输出目录"),
                "query" to Schema.string("只解出路径包含该关键词的条目，可空", default = ""),
                "limit" to Schema.integer("最多解出条目数", default = 2000, min = 1, max = 20000),
            ),
            required = listOf("zip", "outDir"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            requireWrite()
            val zipFile = ToolSupport.resolve(ctx, args.getString("zip"))
            require(zipFile.isFile) { "zip 不存在：${zipFile.absolutePath}" }
            val outDir = ToolSupport.resolve(ctx, args.getString("outDir"))
            val query = args.optString("query")
            val limit = args.optInt("limit", 2000).coerceIn(1, 20000)
            var count = 0
            var bytes = 0L
            val skipped = mutableListOf<String>()
            ZipFile(zipFile).use { zip ->
                val entries = zip.entries().toList()
                entries.forEach { entry ->
                    if (count >= limit) return@forEach
                    if (entry.isDirectory) return@forEach
                    if (query.isNotEmpty() && !entry.name.contains(query, ignoreCase = true)) return@forEach
                    val target = File(outDir, entry.name)
                    val canonical = target.canonicalFile
                    if (!canonical.path.startsWith(outDir.canonicalFile.path + File.separator)) {
                        skipped += entry.name
                        return@forEach
                    }
                    canonical.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> canonical.outputStream().use { input.copyTo(it) } }
                    count++
                    bytes += canonical.length()
                }
            }
            val structured = JSONObject().apply {
                put("zip", zipFile.absolutePath)
                put("outDir", outDir.absolutePath)
                put("extracted", count)
                put("bytes", bytes)
                put("human", ToolSupport.human(bytes))
                put("skippedUnsafe", JSONArray(skipped))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileDu(context: Context) = ToolDef(
        name = "file.du",
        title = "目录占用统计",
        description = "统计目录总大小、文件数，并列出最大的若干文件，用于快速找到是什么吃掉了空间。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("目录路径", default = "/sdcard"),
                "top" to Schema.integer("返回最大的 N 个文件", default = 10, min = 1, max = 100),
                "maxDepth" to Schema.integer("最大递归深度", default = 8, min = 1, max = 20),
            ),
        ),
        handler = { ctx, args ->
            val root = ToolSupport.resolve(ctx, args.optString("path", "/sdcard"))
            require(root.isDirectory) { "不是目录：${root.absolutePath}" }
            val top = args.optInt("top", 10).coerceIn(1, 100)
            val maxDepth = args.optInt("maxDepth", 8).coerceIn(1, 20)
            var total = 0L
            var files = 0
            val biggest = ArrayList<Pair<File, Long>>()
            root.walkTopDown().maxDepth(maxDepth).forEach { file ->
                if (file.isFile) {
                    val size = file.length()
                    total += size
                    files++
                    if (biggest.size < top) {
                        biggest += file to size
                        biggest.sortByDescending { it.second }
                    } else if (biggest.last().second < size) {
                        biggest[biggest.size - 1] = file to size
                        biggest.sortByDescending { it.second }
                    }
                }
            }
            val rows = JSONArray()
            biggest.forEach { (file, size) ->
                rows.put(
                    JSONObject().apply {
                        put("path", file.absolutePath)
                        put("sizeBytes", size)
                        put("sizeHuman", ToolSupport.human(size))
                    },
                )
            }
            val structured = JSONObject().apply {
                put("path", root.absolutePath)
                put("totalBytes", total)
                put("totalHuman", ToolSupport.human(total))
                put("fileCount", files)
                put("largest", rows)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileReplace(context: Context) = ToolDef(
        name = "file.replace",
        title = "文本查找替换",
        description = "在单个文件内做文本替换，支持正则与替换次数限制；默认返回预览，dryRun=false 才真正写盘。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("文件路径"),
                "find" to Schema.string("要查找的文本或正则"),
                "replace" to Schema.string("替换为的文本", default = ""),
                "regex" to Schema.bool("find 是否按正则处理", default = false),
                "dryRun" to Schema.bool("只预览不写盘", default = true),
                "maxReplacements" to Schema.integer("最多替换次数（0 表示不限）", default = 0, min = 0, max = 1000000),
            ),
            required = listOf("path", "find"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            val file = ToolSupport.resolve(ctx, args.getString("path"))
            require(file.isFile) { "不是文件：${file.absolutePath}" }
            require(file.length() < 32L * 1024 * 1024) { "文件过大（>32MB），请用 file.read + file.write 处理" }
            val find = args.getString("find")
            val replacement = args.optString("replace")
            val useRegex = args.optBoolean("regex")
            val dryRun = args.optBoolean("dryRun", true)
            val maxReplacements = args.optInt("maxReplacements", 0).coerceIn(0, 1000000)
            val original = file.readText()
            var hits = 0
            val updated = if (useRegex) {
                val regex = Regex(find)
                regex.replace(original) { match ->
                    if (maxReplacements > 0 && hits >= maxReplacements) {
                        match.value
                    } else {
                        hits++
                        replacement
                    }
                }
            } else {
                buildString {
                    var index = 0
                    while (index <= original.length) {
                        val at = original.indexOf(find, index)
                        if (at < 0) {
                            append(original, index, original.length)
                            break
                        }
                        if (maxReplacements > 0 && hits >= maxReplacements) {
                            append(original, index, original.length)
                            break
                        }
                        append(original, index, at)
                        append(replacement)
                        hits++
                        index = at + find.length
                        if (find.isEmpty()) break
                    }
                }
            }
            if (!dryRun) {
                requireWrite()
                file.writeText(updated)
            }
            val structured = JSONObject().apply {
                put("path", file.absolutePath)
                put("replacements", hits)
                put("dryRun", dryRun)
                put("oldLength", original.length)
                put("newLength", updated.length)
                put("sha256", if (dryRun) JSONObject.NULL else digestOf(file, "SHA-256"))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileReadRange(context: Context) = ToolDef(
        name = "file.readRange",
        title = "按区间读取文件",
        description = "从指定偏移读取指定长度的内容（文本或 base64），适合大文件的片段读取与二进制头部分析。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("文件路径"),
                "offset" to Schema.integer("起始字节偏移", default = 0, min = 0, max = 2147483647),
                "length" to Schema.integer("读取字节数", default = 4096, min = 1, max = 1048576),
                "encoding" to Schema.string("输出编码", default = "utf-8", enum = listOf("utf-8", "base64")),
            ),
            required = listOf("path"),
        ),
        handler = { ctx, args ->
            val file = ToolSupport.resolve(ctx, args.getString("path"))
            require(file.isFile) { "不是文件：${file.absolutePath}" }
            val offset = args.optInt("offset", 0).coerceAtLeast(0).toLong()
            val length = args.optInt("length", 4096).coerceIn(1, 1048576)
            val bytes = ByteArray(length)
            var read = 0
            java.io.RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset)
                while (read < length) {
                    val step = raf.read(bytes, read, length - read)
                    if (step <= 0) break
                    read += step
                }
            }
            val content = bytes.copyOf(read)
            val structured = JSONObject().apply {
                put("path", file.absolutePath)
                put("fileSize", file.length())
                put("offset", offset)
                put("readBytes", read)
                put("hasMore", offset + read < file.length())
                if (args.optString("encoding") == "base64") {
                    put("contentBase64", Base64.encodeToString(content, Base64.NO_WRAP))
                } else {
                    put("content", String(content, Charsets.UTF_8))
                }
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileGrep(context: Context) = ToolDef(
        name = "file.grep",
        title = "文件内容检索",
        description = "在文件或目录里按关键词 / 正则检索文本内容，返回 文件:行号:内容，可限制扫描文件数与命中数。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("文件或目录路径"),
                "query" to Schema.string("关键词或正则"),
                "regex" to Schema.bool("按正则处理", default = false),
                "ignoreCase" to Schema.bool("忽略大小写", default = true),
                "limit" to Schema.integer("最多返回命中行数", default = 100, min = 1, max = 2000),
                "maxFiles" to Schema.integer("最多扫描文件数", default = 2000, min = 1, max = 20000),
                "maxFileSizeKb" to Schema.integer("跳过大于该大小的文件（KB）", default = 2048, min = 1, max = 51200),
            ),
            required = listOf("path", "query"),
        ),
        handler = { ctx, args ->
            val root = ToolSupport.resolve(ctx, args.getString("path"))
            require(root.exists()) { "路径不存在：${root.absolutePath}" }
            val query = args.getString("query")
            val useRegex = args.optBoolean("regex")
            val ignoreCase = args.optBoolean("ignoreCase", true)
            val limit = args.optInt("limit", 100).coerceIn(1, 2000)
            val maxFiles = args.optInt("maxFiles", 2000).coerceIn(1, 20000)
            val maxFileSize = args.optInt("maxFileSizeKb", 2048).toLong() * 1024
            val regex = runCatching {
                if (useRegex) Regex(query, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()) else null
            }.getOrNull()
            val hits = JSONArray()
            var scanned = 0
            var skippedBinary = 0
            val candidates = if (root.isFile) sequenceOf(root) else root.walkTopDown().filter { it.isFile }
            candidates.forEach { file ->
                if (hits.length() >= limit || scanned >= maxFiles) return@forEach
                if (file.length() > maxFileSize) return@forEach
                scanned++
                var lineNo = 0
                var binary = false
                runCatching {
                    file.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            lineNo++
                            if (lineNo == 1 && line.contains('\u0000')) {
                                binary = true
                                break
                            }
                            val ok = if (regex != null) {
                                regex.containsMatchIn(line)
                            } else {
                                line.contains(query, ignoreCase = ignoreCase)
                            }
                            if (ok && hits.length() < limit) {
                                hits.put(
                                    JSONObject().apply {
                                        put("file", file.absolutePath)
                                        put("line", lineNo)
                                        put("text", line.trim().take(400))
                                    },
                                )
                            }
                            if (hits.length() >= limit) break
                        }
                    }
                }.onFailure { skippedBinary++ }
                if (binary) skippedBinary++
            }
            val structured = JSONObject().apply {
                put("root", root.absolutePath)
                put("query", query)
                put("regex", useRegex)
                put("scannedFiles", scanned)
                put("skippedFiles", skippedBinary)
                put("count", hits.length())
                put("truncated", hits.length() >= limit)
                put("hits", hits)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileChmod(context: Context) = ToolDef(
        name = "file.chmod",
        title = "修改文件权限",
        description = "设置文件 / 目录的读、写、执行权限（owner 维度），返回修改前后的实际状态。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("目标路径"),
                "readable" to Schema.bool("可读", default = true),
                "writable" to Schema.bool("可写", default = true),
                "executable" to Schema.bool("可执行", default = false),
            ),
            required = listOf("path"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            requireWrite()
            val file = ToolSupport.resolve(ctx, args.getString("path"))
            require(file.exists()) { "路径不存在：${file.absolutePath}" }
            val before = JSONObject()
                .put("readable", file.canRead())
                .put("writable", file.canWrite())
                .put("executable", file.canExecute())
            val r = file.setReadable(args.optBoolean("readable", true), true)
            val w = file.setWritable(args.optBoolean("writable", true), true)
            val x = file.setExecutable(args.optBoolean("executable"), true)
            val after = JSONObject()
                .put("readable", file.canRead())
                .put("writable", file.canWrite())
                .put("executable", file.canExecute())
            val structured = JSONObject().apply {
                put("path", file.absolutePath)
                put("before", before)
                put("after", after)
                put("applied", JSONObject().put("readable", r).put("writable", w).put("executable", x))
            }
            ToolResult(structured, structured.toString(2))
        },
    )
}

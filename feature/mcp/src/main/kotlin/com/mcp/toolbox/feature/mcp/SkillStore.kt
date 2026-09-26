package com.mcp.toolbox.feature.mcp

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Skill 的本地仓库。
 *
 * 全部落在应用私有目录 `filesDir/skills/<id>/`，不碰外部存储，所以不需要任何
 * 存储权限；导入时通过 SAF 拿到的那一份内容会被完整复制进来，之后即使原文件
 * 被删掉也不影响。
 *
 * **不内置任何 Skill**：首次进入就是空列表。
 */
object SkillStore {

    private const val ROOT_NAME = "skills"
    private const val ENTRY = "SKILL.md"
    private const val META_NAME = ".yutu-skill.json"

    /** 允许随 Skill 一起带进来的子目录（与 Eta / Claude 的约定一致）。 */
    private val ALLOWED_DIRS = setOf("scripts", "references", "assets")

    /** 单个 Skill 允许的最大字节数，避免一次误导入把私有目录塞满。 */
    private const val MAX_BYTES = 32L * 1024 * 1024

    /** 远程导入的大小上限与超时。 */
    private const val REMOTE_LIMIT = 16L * 1024 * 1024
    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 60_000

    private val _skills = MutableStateFlow<List<Skill>>(emptyList())
    val skills: StateFlow<List<Skill>> = _skills.asStateFlow()

    /** 已启用的 Skill，供拼系统提示使用。 */
    val enabled: List<Skill> get() = _skills.value.filter { it.enabled }

    fun root(context: Context): File = File(context.filesDir, ROOT_NAME)

    /** 从磁盘重读一遍。列表页与对话开始前各调一次即可。 */
    suspend fun refresh(context: Context): List<Skill> = withContext(Dispatchers.IO) {
        val dir = root(context)
        val list = dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { readOne(it) }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty()
        _skills.value = list
        list
    }

    /** 读一个 Skill 目录。SKILL.md 缺失或不可解析时返回 null，不抛异常。 */
    private fun readOne(dir: File): Skill? {
        val entry = File(dir, ENTRY)
        if (!entry.isFile) return null
        val text = runCatching { entry.readText() }.getOrNull() ?: return null
        val parsed = SkillMarkdown.parse(text, fallbackName = dir.name)
        val meta = readMeta(dir)
        var bytes = 0L
        val files = mutableListOf<String>()
        dir.walkTopDown().forEach { f ->
            if (f.isFile) {
                bytes += f.length()
                if (f.name != META_NAME) files += f.relativeTo(dir).path
            }
        }
        return Skill(
            id = dir.name,
            name = meta.optString("name").takeIf { it.isNotBlank() } ?: parsed.name,
            description = meta.optString("description").takeIf { it.isNotBlank() } ?: parsed.description,
            version = meta.optString("version").takeIf { it.isNotBlank() } ?: parsed.version,
            enabled = if (meta.has("enabled")) meta.optBoolean("enabled", true) else true,
            source = meta.optString("source"),
            importedAt = meta.optLong("importedAt"),
            bytes = bytes,
            files = files.sorted(),
        )
    }

    private fun readMeta(dir: File): JSONObject =
        runCatching {
            val f = File(dir, META_NAME)
            if (f.isFile) JSONObject(f.readText()) else JSONObject()
        }.getOrDefault(JSONObject())

    private fun writeMeta(dir: File, skill: Skill) {
        val obj = JSONObject()
            .put("name", skill.name)
            .put("description", skill.description)
            .put("version", skill.version)
            .put("enabled", skill.enabled)
            .put("source", skill.source)
            .put("importedAt", skill.importedAt)
        File(dir, META_NAME).writeText(obj.toString(2))
    }

    /**
     * 同步读取正文（不含 frontmatter）。
     *
     * 内置工具的 handler 不是挂起函数，拿不到 [body]；这里给一条纯文件的同步路径。
     */
    fun bodyNow(context: Context, id: String): String {
        val entry = File(File(root(context), id), ENTRY)
        if (!entry.isFile) return ""
        return runCatching { SkillMarkdown.parse(entry.readText(), id).body }.getOrDefault("")
    }

    /** 读取 SKILL.md 正文（不含 frontmatter），供详情页展示。 */
    suspend fun body(context: Context, id: String): String = withContext(Dispatchers.IO) {
        val entry = File(File(root(context), id), ENTRY)
        if (!entry.isFile) return@withContext ""
        SkillMarkdown.parse(entry.readText(), id).body
    }

    // ------------------------------------------------------------------
    // 导入
    // ------------------------------------------------------------------

    /**
     * 落盘一个 draft。
     *
     * 同名时不做覆盖而是追加 `-2`、`-3`：导入是用户的显式动作，静默覆盖会
     * 让人丢掉上一个同名 Skill 里改过的东西。
     */
    suspend fun import(context: Context, draft: SkillDraft, source: String): Skill =
        withContext(Dispatchers.IO) {
            val dir = allocateDir(context, Skill.slugify(draft.name))
            draft.files.forEach { (path, content) ->
                if (!writeSafe(dir, path, content.toByteArray())) {
                    throw IllegalArgumentException("Skill 内含不允许的路径：$path")
                }
            }
            draft.binaryFiles.forEach { (path, bytes) ->
                if (!writeSafe(dir, path, bytes)) {
                    throw IllegalArgumentException("Skill 内含不允许的路径：$path")
                }
            }
            val parsed = SkillMarkdown.parse(draft.body, draft.name)
            val body = if (parsed.body.isBlank()) "" else parsed.body
            File(dir, ENTRY).writeText(
                draft.copy(
                    name = draft.name.ifBlank { parsed.name },
                    description = draft.description.ifBlank { parsed.description },
                    version = draft.version.ifBlank { parsed.version },
                    body = body,
                ).let {
                    buildString {
                        append("---\n")
                        append("name: ").append(it.name).append('\n')
                        if (it.description.isNotBlank()) append("description: ").append(it.description).append('\n')
                        if (it.version.isNotBlank()) append("version: ").append(it.version).append('\n')
                        append("---\n\n")
                        append(it.body.trim()).append('\n')
                    }
                },
            )
            val skill = readOne(dir) ?: throw IllegalStateException("写入后无法读取，请检查 SKILL.md")
            val stamped = skill.copy(source = source, importedAt = System.currentTimeMillis())
            writeMeta(dir, stamped)
            refresh(context)
            readOne(dir) ?: stamped
        }

    /**
     * 目录名去重。
     *
     * 已有 `<id>` 时依次尝试 `<id>-2`、`<id>-3`…，最多 100 次。
     */
    private fun allocateDir(context: Context, base: String): File {
        val rootDir = root(context).apply { mkdirs() }
        var candidate = File(rootDir, base)
        var n = 2
        while (candidate.exists() && n < 100) {
            candidate = File(rootDir, "$base-$n")
            n++
        }
        candidate.mkdirs()
        return candidate
    }

    /**
     * 只允许写根目录下的文件与 [ALLOWED_DIRS] 里的内容。
     *
     * ZIP 里可能带 `../../` 这类相对路径，直接落盘会写穿到私有目录之外。
     */
    private fun writeSafe(dir: File, relative: String, bytes: ByteArray): Boolean {
        val clean = relative.replace('\\', '/').trimStart('/')
        if (clean.isBlank()) return false
        if (clean.split('/').any { it == ".." || it == "." }) return false
        val first = clean.substringBefore('/')
        if (first.contains('/') || (first != ENTRY && first !in ALLOWED_DIRS)) return false
        if (clean == META_NAME || clean.endsWith("/$META_NAME")) return false
        val target = File(dir, clean)
        // 再校验一次解析后的路径确实落在 dir 之内
        if (!target.canonicalPath.startsWith(dir.canonicalPath + File.separator)) return false
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        return true
    }

    /** 从选中的单个 Markdown / 文本文件导入。 */
    suspend fun importFromMarkdown(context: Context, uri: Uri, source: String): Skill =
        withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: throw IllegalStateException("无法读取所选文件")
            val displayName = displayName(context, uri)?.substringBeforeLast('.') ?: "skill"
            val parsed = SkillMarkdown.parse(text, displayName)
            import(context, SkillDraft(name = parsed.name, description = parsed.description, version = parsed.version, body = parsed.body), source)
        }

    /** 从选中的 JSON 文件导入（支持单个对象或数组，见 [SkillMarkdown.parseJson]）。 */
    suspend fun importFromJson(context: Context, uri: Uri, source: String): List<Skill> =
        withContext(Dispatchers.IO) {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: throw IllegalStateException("无法读取所选文件")
            val drafts = SkillMarkdown.parseJson(text)
            if (drafts.isEmpty()) throw IllegalStateException("JSON 里没有可导入的 Skill（缺少 name 字段）")
            drafts.map { import(context, it, source) }
        }

    /** 从剪贴板里的 JSON 文本导入。 */
    suspend fun importFromJsonText(context: Context, text: String, source: String): List<Skill> =
        withContext(Dispatchers.IO) {
            val drafts = SkillMarkdown.parseJson(text)
            if (drafts.isEmpty()) throw IllegalStateException("剪贴板内容里没有可导入的 Skill")
            drafts.map { import(context, it, source) }
        }

    /** 从 URL 导入：支持直接给 SKILL.md、JSON，或一个 ZIP。 */
    suspend fun importFromUrl(context: Context, rawUrl: String, source: String): List<Skill> =
        withContext(Dispatchers.IO) {
            val url = rawUrl.trim()
            require(url.startsWith("http://") || url.startsWith("https://")) { "地址必须以 http:// 或 https:// 开头" }
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                setRequestProperty("User-Agent", "yutu-toolbox/1.0")
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) throw IllegalStateException("下载失败：HTTP $code")
                val declared = conn.contentLengthLong
                if (declared > REMOTE_LIMIT) throw IllegalStateException("文件过大（${Skill.formatBytes(declared)}）")
                val bytes = conn.inputStream.use { it.readLimited(REMOTE_LIMIT) }
                when {
                    url.endsWith(".json", true) || looksLikeJson(bytes) -> {
                        val drafts = SkillMarkdown.parseJson(bytes.toString(Charsets.UTF_8))
                        if (drafts.isEmpty()) throw IllegalStateException("JSON 里没有可导入的 Skill")
                        drafts.map { import(context, it, source) }
                    }

                    isZip(bytes) -> listOf(import(context, draftFromZip(bytes, url), source))

                    else -> {
                        val fallback = url.substringAfterLast('/').substringBeforeLast('.').ifBlank { "skill" }
                        val parsed = SkillMarkdown.parse(bytes.toString(Charsets.UTF_8), fallback)
                        listOf(
                            import(
                                context,
                                SkillDraft(parsed.name, parsed.description, parsed.version, parsed.body),
                                source,
                            ),
                        )
                    }
                }
            } finally {
                conn.disconnect()
            }
        }

    /** 从 SAF 选中的 ZIP 导入。 */
    suspend fun importFromZip(context: Context, uri: Uri, source: String): Skill =
        withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readLimited(REMOTE_LIMIT) }
                ?: throw IllegalStateException("无法读取所选文件")
            if (!isZip(bytes)) throw IllegalStateException("这不是一个 ZIP 压缩包")
            import(context, draftFromZip(bytes, displayName(context, uri) ?: "skill.zip"), source)
        }

    /** 从 SAF 选中的目录导入：整棵目录树复制进来。 */
    suspend fun importFromTree(context: Context, uri: Uri, source: String): Skill =
        withContext(Dispatchers.IO) {
            val tree = DocumentFile.fromTreeUri(context, uri)
                ?: throw IllegalStateException("无法读取所选文件夹")
            val drafts = collectTree(context, tree, tree.name ?: "skill")
            if (drafts.isEmpty()) throw IllegalStateException("文件夹里没有可导入的内容")
            // 一个文件夹按一个 Skill 处理：根层若只有一个 SKILL.md 就用它，否则合并
            val primary = drafts.firstOrNull { it.files.containsKey(ENTRY) } ?: drafts.first()
            import(context, primary, source)
        }

    /**
     * 把一个目录树读成若干 draft。
     *
     * 顶层若含 `SKILL.md` 即视为单个 Skill；否则把每个子目录当一个 Skill
     * （支持一次导入一整个 skills 仓库目录）。
     */
    private fun collectTree(context: Context, tree: DocumentFile, fallbackName: String): List<SkillDraft> {
        fun readInto(dir: DocumentFile, prefix: String, files: MutableMap<String, String>, bin: MutableMap<String, ByteArray>) {
            dir.listFiles().forEach { child ->
                if (child.isDirectory) {
                    val name = child.name ?: return@forEach
                    if (prefix.isEmpty() && name !in ALLOWED_DIRS) return@forEach
                    readInto(child, "$prefix$name/", files, bin)
                } else {
                    val name = child.name ?: return@forEach
                    val relative = prefix + name
                    val bytes = runCatching {
                        context.contentResolver.openInputStream(child.uri)?.use { it.readLimited(REMOTE_LIMIT) }
                    }.getOrNull() ?: return@forEach
                    if (isProbablyText(name)) files[relative] = bytes.toString(Charsets.UTF_8)
                    else bin[relative] = bytes
                }
            }
        }

        val files = linkedMapOf<String, String>()
        val bin = linkedMapOf<String, ByteArray>()
        readInto(tree, "", files, bin)

        // 1) 根层有 SKILL.md：整体当一个 Skill
        if (files.containsKey(ENTRY)) {
            val parsed = SkillMarkdown.parse(files.getValue(ENTRY), fallbackName)
            return listOf(
                SkillDraft(
                    name = parsed.name,
                    description = parsed.description,
                    version = parsed.version,
                    body = parsed.body,
                    files = files - ENTRY,
                    binaryFiles = bin,
                ),
            )
        }

        // 2) 否则按一级子目录拆分（一整个 skills 目录的导入）
        val byDir: Map<String, List<String>> = files.keys
            .map { it.substringBefore('/', "") }
            .filter { it.isNotBlank() }
            .distinct()
            .associateWith { dir -> files.keys.filter { it.startsWith("$dir/") } }
        if (byDir.isEmpty()) {
            return listOf(SkillDraft(name = fallbackName, body = files.values.firstOrNull().orEmpty()))
        }
        return byDir.map { (dir, keys) ->
            val entryKey = "$dir/$ENTRY"
            val parsed = files[entryKey]?.let { SkillMarkdown.parse(it, dir) }
            SkillDraft(
                name = parsed?.name ?: dir,
                description = parsed?.description.orEmpty(),
                version = parsed?.version.orEmpty(),
                body = parsed?.body.orEmpty(),
                files = keys.filter { it != entryKey }
                    .associateWith { files.getValue(it) }
                    .mapKeys { it.key.removePrefix("$dir/") },
                binaryFiles = bin.filterKeys { it.startsWith("$dir/") }
                    .mapKeys { it.key.removePrefix("$dir/") },
            )
        }
    }

    /** 把 ZIP 解成 draft：以顶层 `SKILL.md` 为正文，其余按允许的子目录收集。 */
    private fun draftFromZip(bytes: ByteArray, sourceName: String): SkillDraft {
        val files = linkedMapOf<String, String>()
        val bin = linkedMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var total = 0L
            var entry = zip.nextEntry
            while (entry != null) {
                val rawName = entry.name.replace('\\', '/')
                if (!entry.isDirectory && rawName.isNotBlank() && !rawName.startsWith("__MACOSX/")) {
                    val stripped = stripSingleRoot(rawName)
                    if (stripped.isNotBlank()) {
                        val data = zip.readBytes().let { d ->
                            if (d.size > MAX_BYTES) throw IllegalStateException("压缩包内容过大")
                            d
                        }
                        total += data.size
                        if (total > MAX_BYTES) throw IllegalStateException("解压后超过 ${Skill.formatBytes(MAX_BYTES)}")
                        val first = stripped.substringBefore('/')
                        if (first == ENTRY || first in ALLOWED_DIRS) {
                            if (isProbablyText(stripped)) files[stripped] = data.toString(Charsets.UTF_8)
                            else bin[stripped] = data
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val entryText = files[ENTRY] ?: throw IllegalStateException("压缩包里没有 SKILL.md")
        val parsed = SkillMarkdown.parse(entryText, sourceName.substringBeforeLast('.'))
        return SkillDraft(
            name = parsed.name,
            description = parsed.description,
            version = parsed.version,
            body = parsed.body,
            files = files - ENTRY,
            binaryFiles = bin,
        )
    }

    /** 压缩包里常见多包一层同名目录，剥掉它才能对上 `SKILL.md` 的位置。 */
    private fun stripSingleRoot(path: String): String {
        val parts = path.split('/')
        if (parts.size >= 2 && parts[0].isNotBlank()) {
            val rest = parts.drop(1).joinToString("/")
            if (rest == ENTRY || rest.substringBefore('/') in ALLOWED_DIRS) return rest
        }
        return path
    }

    // ------------------------------------------------------------------
    // 管理
    // ------------------------------------------------------------------

    suspend fun setEnabled(context: Context, id: String, enabled: Boolean) = withContext(Dispatchers.IO) {
        val dir = File(root(context), id)
        if (!dir.isDirectory) return@withContext
        val skill = readOne(dir) ?: return@withContext
        writeMeta(dir, skill.copy(enabled = enabled))
        refresh(context)
    }

    suspend fun rename(context: Context, id: String, newName: String) = withContext(Dispatchers.IO) {
        val name = newName.trim()
        require(name.isNotBlank()) { "名称不能为空" }
        val dir = File(root(context), id)
        if (!dir.isDirectory) return@withContext
        val skill = readOne(dir) ?: return@withContext
        writeMeta(dir, skill.copy(name = name))
        refresh(context)
    }

    /** 导出为 ZIP 到指定的 SAF 目标目录，便于备份与分享。 */
    suspend fun export(context: Context, id: String, targetDir: Uri): String = withContext(Dispatchers.IO) {
        val dir = File(root(context), id)
        if (!dir.isDirectory) throw IllegalStateException("Skill 不存在")
        val out = DocumentFile.fromTreeUri(context, targetDir)
            ?: throw IllegalStateException("无法写入所选目录")
        val fileName = "$id.zip"
        out.findFile(fileName)?.delete()
        val created = out.createFile("application/zip", fileName)
            ?: throw IllegalStateException("无法在所选目录创建文件")
        context.contentResolver.openOutputStream(created.uri)?.use { stream ->
            java.util.zip.ZipOutputStream(stream).use { zip ->
                dir.walkTopDown().filter { it.isFile && it.name != META_NAME }.forEach { f ->
                    val relative = f.relativeTo(dir).path.replace('\\', '/')
                    zip.putNextEntry(java.util.zip.ZipEntry(relative))
                    zip.write(f.readBytes())
                    zip.closeEntry()
                }
            }
        } ?: throw IllegalStateException("无法写入所选目录")
        fileName
    }

    suspend fun delete(context: Context, id: String) = withContext(Dispatchers.IO) {
        File(root(context), id).deleteRecursively()
        refresh(context)
    }

    // ------------------------------------------------------------------
    // 提示词注入
    // ------------------------------------------------------------------

    /**
     * 可用的 Skill 目录，追加到系统提示词里。
     *
     * 只放名称与描述，不放正文：正文可能有几千字，一次全塞进去会挤占窗口；
     * 模型知道「有哪些 Skill、各自解决什么」就够，需要细节时让它用
     * `skill.read` 工具按需取。
     */
    fun buildPrompt(): String? {
        val list = enabled
        if (list.isEmpty()) return null
        return buildString {
            append("## 可用 Skill\n")
            append("以下是用户导入并启用的 Skill，需要时可调用 skill.read 读取完整内容：\n")
            list.forEach { skill ->
                append("- `").append(skill.id).append("` — ").append(skill.name)
                val desc = skill.description.trim()
                if (desc.isNotBlank()) append("：").append(desc.lineSequence().first().take(200))
                append('\n')
            }
        }.trim()
    }

    // ------------------------------------------------------------------
    // 工具函数
    // ------------------------------------------------------------------

    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        DocumentFile.fromSingleUri(context, uri)?.name
    }.getOrNull()

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private fun looksLikeJson(bytes: ByteArray): Boolean {
        val head = bytes.take(64).toByteArray().toString(Charsets.UTF_8).trimStart()
        return head.startsWith("{") || head.startsWith("[")
    }

    private fun isProbablyText(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in setOf(
            "md", "markdown", "txt", "json", "yaml", "yml", "toml", "ini", "cfg",
            "sh", "bash", "py", "js", "ts", "kt", "java", "gradle", "rs", "go",
            "c", "h", "cpp", "xml", "html", "css", "csv", "sql", "properties", "env",
        )
    }

    /** 边读边计数，超过 [limit] 立即中止，避免被超大响应拖垮内存。 */
    private fun java.io.InputStream.readLimited(limit: Long): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) throw IllegalStateException("内容超过 ${Skill.formatBytes(limit)} 上限")
            buffer.write(chunk, 0, n)
        }
        return buffer.toByteArray()
    }

    /** 供 JSON 导出（长按菜单「导出 JSON」用）。 */
    suspend fun exportJson(context: Context, id: String): String = withContext(Dispatchers.IO) {
        val dir = File(root(context), id)
        val skill = readOne(dir) ?: throw IllegalStateException("Skill 不存在")
        val body = body(context, id)
        val obj = JSONObject()
            .put("name", skill.name)
            .put("description", skill.description)
            .put("version", skill.version)
            .put("content", body)
        val files = JSONObject()
        skill.files.filter { it != ENTRY }.forEach { relative ->
            val f = File(dir, relative)
            if (f.isFile && isProbablyText(relative)) files.put(relative, f.readText())
        }
        obj.put("files", files)
        obj.toString(2)
    }

    /** 仅供测试：把 JSON 数组文本转成 draft 数量。 */
    fun draftCount(json: String): Int = SkillMarkdown.parseJson(json).size

    private fun JSONArray.forEachString(action: (String) -> Unit) {
        for (i in 0 until length()) action(optString(i))
    }
}

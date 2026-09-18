package com.mcp.toolbox.feature.mcp

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MCP 输出文件夹（提示词 5.9）：把工具产物落成可浏览、可溯源、可清理的会话目录。
 *
 * session-<yyyyMMdd-HHmmss>/
 *   manifest.json · inputs/ · outputs/ (result.json / result.md / files/) · logs/ · thumbnails/
 */
object ArtifactStore {

    enum class Layout(val label: String, val prefix: (String, String, String) -> String) {
        FLAT("扁平", { _, _, _ -> "" }),
        SERVER_TOOL("server/tool", { server, tool, _ -> "$server/$tool" }),
        DATE_TOOL("date/tool", { _, tool, date -> "$date/$tool" }),
    }

    data class Config(
        val enabled: Boolean = false,
        val layout: Layout = Layout.SERVER_TOOL,
        val template: String = "{tool}_{timestamp}_{seq}",
        val quotaMb: Int = 1024,
        val retentionDays: Int = 7,
        val maskSecrets: Boolean = true,
        val recordResults: Boolean = true,
        val externalSync: Boolean = true,
    )

    private const val PREFS = "mcp-artifacts"
    private const val KEY_CONFIG = "config"
    private const val ROOT_NAME = "mcp-output"
    private const val KEY_ROOT_URI = "externalRootUri"
    private const val KEY_PUBLIC_ROOT = "publicRoot"

    /** 公共目录直写默认路径。 */
    const val DEFAULT_PUBLIC_ROOT = "/storage/emulated/0/MCP产物"

    val config = MutableStateFlow(Config())

    /** 公共目录直写根；非空且可写时产物直接落公共存储，不再依赖 SAF。 */
    private val _publicRoot = MutableStateFlow<String?>(null)
    val publicRoot: StateFlow<String?> = _publicRoot.asStateFlow()
    private val sequence = mutableMapOf<String, Int>()

    /** 是否已获得「所有文件访问权限」。 */
    fun canUsePublicRoot(): Boolean = runCatching { Environment.isExternalStorageManager() }
        .getOrDefault(false)

    private fun usablePublicRoot(): File? {
        val path = _publicRoot.value ?: return null
        val dir = File(path)
        return if (dir.isDirectory && dir.canWrite()) dir else null
    }

    /** 当前是否真的在用公共目录直写。 */
    fun usingPublicRoot(): Boolean = usablePublicRoot() != null

    /** 设置 / 取消公共目录直写；传入目录会被创建，失败返回 false。 */
    fun setPublicRoot(context: Context, path: String?): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val clean = path?.trim()?.takeIf { it.isNotBlank() }
        if (clean == null) {
            _publicRoot.value = null
            prefs.edit().remove(KEY_PUBLIC_ROOT).apply()
            return true
        }
        val dir = File(clean)
        val ok = runCatching { dir.mkdirs(); dir.isDirectory && dir.canWrite() }.getOrDefault(false)
        if (!ok) return false
        _publicRoot.value = clean
        prefs.edit().putString(KEY_PUBLIC_ROOT, clean).apply()
        return true
    }

    /** 产物根目录：优先公共目录直写，否则回退应用私有目录。 */
    fun root(context: Context): File = usablePublicRoot() ?: File(context.filesDir, ROOT_NAME)

    data class Session(
        val id: String,
        val dir: String,
        val server: String,
        val tool: String,
        val startedAt: Long,
        val ok: Boolean,
        val elapsedMs: Long,
        val files: Int,
        val bytes: Long,
        val error: String = "",
        val truncated: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("id", id)
            put("dir", dir)
            put("server", server)
            put("tool", tool)
            put("startedAt", startedAt)
            put("ok", ok)
            put("elapsedMs", elapsedMs)
            put("files", files)
            put("bytes", bytes)
            put("error", error)
            put("truncated", truncated)
        }

        companion object {
            fun fromJson(obj: JSONObject): Session = Session(
                id = obj.optString("id"),
                dir = obj.optString("dir"),
                server = obj.optString("server"),
                tool = obj.optString("tool"),
                startedAt = obj.optLong("startedAt"),
                ok = obj.optBoolean("ok"),
                elapsedMs = obj.optLong("elapsedMs"),
                files = obj.optInt("files"),
                bytes = obj.optLong("bytes"),
                error = obj.optString("error"),
                truncated = obj.optBoolean("truncated"),
            )
        }
    }

    fun load(context: Context) {
        _publicRoot.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PUBLIC_ROOT, null)
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONFIG, null)
        if (raw.isNullOrBlank()) return
        runCatching {
            val obj = JSONObject(raw)
            config.value = Config(
                enabled = obj.optBoolean("enabled"),
                layout = runCatching { Layout.valueOf(obj.optString("layout")) }.getOrDefault(Layout.SERVER_TOOL),
                template = obj.optString("template", "{tool}_{timestamp}_{seq}"),
                quotaMb = obj.optInt("quotaMb", 1024),
                retentionDays = obj.optInt("retentionDays", 7),
                maskSecrets = obj.optBoolean("maskSecrets", true),
                recordResults = obj.optBoolean("recordResults", true),
                externalSync = obj.optBoolean("externalSync", true),
            )
        }
    }

    fun save(context: Context, next: Config) {
        config.value = next
        val obj = JSONObject().apply {
            put("enabled", next.enabled)
            put("layout", next.layout.name)
            put("template", next.template)
            put("quotaMb", next.quotaMb)
            put("retentionDays", next.retentionDays)
            put("maskSecrets", next.maskSecrets)
            put("recordResults", next.recordResults)
            put("externalSync", next.externalSync)
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONFIG, obj.toString()).apply()
    }

    /** 一次会话的写入句柄：调用前落 manifest / inputs / logs，调用后落 outputs。 */
    class Writer internal constructor(
        private val context: Context,
        private val dir: File,
        private val session: Session,
    ) {
        fun finish(resultText: String, ok: Boolean, isError: Boolean, elapsedMs: Long, error: String?): Session {
            val cfg = config.value
            if (cfg.recordResults) {
                File(dir, "outputs/result.json").also { it.parentFile?.mkdirs() }.writeText(resultText)
                File(dir, "outputs/result.md").writeText(
                    buildString {
                        appendLine("# ${session.server} · ${session.tool}")
                        appendLine()
                        appendLine("- 开始：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(session.startedAt))}")
                        appendLine("- 耗时：${elapsedMs}ms")
                        appendLine("- 结果：${if (ok) "成功" else "失败"}")
                        if (error != null) appendLine("- 错误：$error")
                        appendLine()
                        appendLine("```json")
                        appendLine(resultText.take(20000))
                        appendLine("```")
                    },
                )
            }
            File(dir, "logs/response.json").writeText(
                JSONObject().apply {
                    put("ok", ok)
                    put("isError", isError)
                    put("elapsedMs", elapsedMs)
                    put("error", error ?: "")
                    put("at", System.currentTimeMillis())
                }.toString(2),
            )
            File(dir, "outputs/files").mkdirs()
            File(dir, "thumbnails").mkdirs()
            val stat = scan(dir)
            val updated = session.copy(
                ok = ok,
                elapsedMs = elapsedMs,
                files = stat.first,
                bytes = stat.second,
                error = error ?: "",
            )
            writeManifest(dir, updated)
            if (config.value.externalSync && externalRootUri(context) != null) {
                // 不要静默：失败要能在 MCP 页面的产物卡片里看到原因
                runCatching { mirror(context, dir) }
                    .onFailure {
                        mirrorState.value = MirrorReport(
                            at = System.currentTimeMillis(),
                            sessions = 1,
                            files = 0,
                            bytes = 0,
                            error = it.message ?: it::class.simpleName ?: "同步失败",
                        )
                    }
            }
            enforce(context)
            return updated
        }

        val path: String get() = dir.absolutePath
    }

    fun begin(context: Context, server: String, tool: String, argumentsJson: String): Writer? {
        val cfg = config.value
        if (!cfg.enabled) return null
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(now))
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(now))
        val baseName = renderName(cfg.template, tool, stamp, server)
        val parent = File(root(context), cfg.layout.prefix(safe(server), safe(tool), date))
        parent.mkdirs()
        var candidate = File(parent, baseName)
        var index = 1
        while (candidate.exists()) {
            candidate = File(parent, "$baseName-$index")
            index++
        }
        candidate.mkdirs()
        File(candidate, "inputs").mkdirs()
        File(candidate, "outputs").mkdirs()
        File(candidate, "logs").mkdirs()
        val maskedArgs = if (cfg.maskSecrets) mask(argumentsJson) else argumentsJson
        File(candidate, "inputs/arguments.json").writeText(maskedArgs)
        File(candidate, "logs/request.json").writeText(
            JSONObject().apply {
                put("server", server)
                put("tool", tool)
                put("arguments", runCatching { JSONObject(maskedArgs) }.getOrDefault(JSONObject()))
                put("at", now)
            }.toString(2),
        )
        val session = Session(
            id = candidate.name,
            dir = candidate.absolutePath,
            server = server,
            tool = tool,
            startedAt = now,
            ok = false,
            elapsedMs = 0,
            files = 0,
            bytes = 0,
        )
        writeManifest(candidate, session)
        return Writer(context, candidate, session)
    }

    fun list(context: Context): List<Session> {
        val root = root(context)
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile && it.name == "manifest.json" }
            .mapNotNull { manifest ->
                runCatching { Session.fromJson(JSONObject(manifest.readText())) }.getOrNull()
            }
            .sortedByDescending { it.startedAt }
            .toList()
    }

    /** 读取会话关键文件，供产物详情页展示。 */
    fun read(context: Context, dir: String): Map<String, String> {
        val file = File(dir)
        if (!file.isDirectory) return emptyMap()
        val wanted = listOf("manifest.json", "inputs/arguments.json", "outputs/result.json", "outputs/result.md", "logs/request.json", "logs/response.json")
        return wanted.mapNotNull { rel ->
            val f = File(file, rel)
            if (f.isFile) rel to runCatching { f.readText().take(120000) }.getOrDefault("(读取失败)") else null
        }.toMap()
    }

    fun delete(context: Context, dir: String): Boolean {
        val target = File(dir)
        val rootPath = root(context).absolutePath
        if (!target.absolutePath.startsWith(rootPath)) return false
        val removed = target.deleteRecursively()
        // 即使沙箱侧本来就不存在，也要清掉外置副本 —— 否则会留下再也枚举不到的孤儿文件。
        runCatching { deleteMirrored(context, target, root(context)) }
        return removed
    }

    fun clear(context: Context, onlyFailed: Boolean) {
        val sessions = list(context)
        sessions.filter { !onlyFailed || !it.ok }.forEach { delete(context, it.dir) }
        // 「清空全部产物」必须连外置目录一起清：沙箱是唯一事实来源，
        // 它的会话一旦没了，list() 就再也枚举不到对应的外置文件，两边会永久不一致。
        if (!onlyFailed) runCatching { clearMirrored(context) }
    }

    /** 清掉外置目录根下的全部内容（含子目录），用于清空产物后保持镜像一致。 */
    private fun clearMirrored(context: Context) {
        val tree = externalRootUri(context) ?: return
        val target = DocumentFile.fromTreeUri(context, tree) ?: return
        target.listFiles().forEach { doc ->
            runCatching {
                if (doc.isDirectory) DocumentsContract.deleteDocument(context.contentResolver, doc.uri)
                else doc.delete()
            }.recoverCatching { doc.delete() }
        }
    }

    /** 配额与保留天数：超出按最旧会话先淘汰（LRU）。 */
    fun enforce(context: Context) {
        val cfg = config.value
        val sessions = list(context)
        val deadline = System.currentTimeMillis() - cfg.retentionDays * 86400_000L
        sessions.filter { cfg.retentionDays > 0 && it.startedAt < deadline }.forEach { delete(context, it.dir) }
        val quotaBytes = cfg.quotaMb.toLong() * 1024 * 1024
        if (quotaBytes <= 0) return
        var total = list(context).sumOf { it.bytes }
        val ordered = list(context).sortedBy { it.startedAt }
        for (session in ordered) {
            if (total <= quotaBytes) break
            if (delete(context, session.dir)) total -= session.bytes
        }
    }

    fun totalBytes(context: Context): Long = list(context).sumOf { it.bytes }

    /* ---------- 外置产物目录（SAF，系统文件选择器）----------
     * 沙箱目录仍是唯一事实来源；外置目录是它的镜像，用户可在文件管理器里直接翻阅。
     */

    /** 一次镜像同步的结果。 */
    data class MirrorReport(
        val at: Long,
        val sessions: Int,
        val files: Int,
        val bytes: Long,
        val error: String = "",
    )

    val mirrorState = MutableStateFlow<MirrorReport?>(null)

    fun externalRootUri(context: Context): Uri? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ROOT_URI, null)
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /**
     * 探测外置目录当前是否真的可写。
     *
     * SAF 的授权可能因为用户清理、目录被删等情况失效，而 `externalRootUri` 仍然非空，
     * 于是同步会静默失败。这里实际写一个探针文件来判断。
     */
    fun externalRootWritable(context: Context): Boolean {
        val tree = externalRootUri(context) ?: return false
        val target = DocumentFile.fromTreeUri(context, tree) ?: return false
        val probeName = ".write-test"
        return runCatching {
            val doc = target.findFile(probeName) ?: target.createFile("application/octet-stream", probeName)
            if (doc == null) return@runCatching false
            context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
                out.write(byteArrayOf(1))
            } ?: return@runCatching false
            doc.delete()
            true
        }.getOrDefault(false)
    }

    /** 绑定 / 解绑外置目录；绑定会持久化授权，重启后仍可写。 */
    fun setExternalRoot(context: Context, uri: Uri?) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (uri == null) {
            externalRootUri(context)?.let { old ->
                runCatching { context.contentResolver.releasePersistableUriPermission(old, flags) }
            }
            prefs.edit().remove(KEY_ROOT_URI).apply()
            mirrorState.value = null
            return
        }
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        prefs.edit().putString(KEY_ROOT_URI, uri.toString()).apply()
    }

    /** 人类可读的外置目录描述，例如 “内部存储/Documents/MCP产物”。 */
    fun externalRootLabel(context: Context): String {
        val uri = externalRootUri(context) ?: return ""
        return runCatching {
            val parts = DocumentsContract.getTreeDocumentId(uri).split(":", limit = 2)
            val volume = if (parts[0] == "primary") "内部存储" else parts[0]
            val path = parts.getOrNull(1).orEmpty()
            if (path.isBlank()) volume else "$volume/$path"
        }.getOrElse { uri.lastPathSegment ?: uri.toString() }
    }

    /**
     * 把产物镜像到外置目录；only 为空时同步全部会话（即“立即同步 / 迁移既有产物”）。
     * 已存在且大小一致的文件会跳过，因此重复调用是幂等的。
     */
    fun mirror(context: Context, only: File? = null): MirrorReport {
        val tree = externalRootUri(context)
            ?: return MirrorReport(System.currentTimeMillis(), 0, 0, 0, "未选择外置目录").also {
                mirrorState.value = it
            }
        val target = DocumentFile.fromTreeUri(context, tree)
            ?: return MirrorReport(System.currentTimeMillis(), 0, 0, 0, "外置目录授权已失效，请重新选择")
                .also { mirrorState.value = it }
        val base = root(context)
        val sessions = if (only != null) {
            listOf(only)
        } else {
            base.walkTopDown().filter { it.isDirectory && File(it, "manifest.json").isFile }.toList()
        }
        var files = 0
        var bytes = 0L
        var failure = ""
        for (session in sessions) {
            runCatching {
                val copied = copyTree(context, target, base, session)
                files += copied.first
                bytes += copied.second
            }.onFailure { if (failure.isBlank()) failure = it.message ?: "写入失败" }
        }
        val report = MirrorReport(
            at = System.currentTimeMillis(),
            sessions = sessions.size,
            files = files,
            bytes = bytes,
            error = failure,
        )
        mirrorState.value = report
        return report
    }

    /** 递归复制一个会话目录，路径层级与外置目录保持一致。 */
    private fun copyTree(
        context: Context,
        target: DocumentFile,
        base: File,
        session: File,
    ): Pair<Int, Long> {
        var node = target
        for (segment in session.relativeTo(base).path.split(File.separatorChar)) {
            if (segment.isEmpty()) continue
            node = node.findFile(segment) ?: node.createDirectory(segment) ?: node
        }
        var files = 0
        var bytes = 0L
        for (file in session.walkTopDown().filter { it.isFile }) {
            val rel = file.relativeTo(session).path.split(File.separatorChar).filter { it.isNotEmpty() }
            if (rel.isEmpty()) continue
            var parent = node
            for (segment in rel.dropLast(1)) {
                parent = parent.findFile(segment) ?: parent.createDirectory(segment) ?: parent
            }
            val name = rel.last()
            val existing = parent.findFile(name)
            // 长度相同不代表内容相同（manifest.json 每次重写，长度可能正好撞上）；
            // 用修改时间兜底。部分 provider 的 lastModified 恒为 0，那样会退化成总是重写，是安全方向。
            if (existing != null && existing.length() == file.length() &&
                existing.lastModified() >= file.lastModified()
            ) {
                continue
            }
            val doc = existing ?: parent.createFile(mimeOf(name), name) ?: continue
            // 部分 SAF provider 不认 "wt"（truncate），依次回退到 "w" 与 "rwt"
            val stream = context.contentResolver.openOutputStream(doc.uri, "wt")
                ?: context.contentResolver.openOutputStream(doc.uri, "w")
                ?: context.contentResolver.openOutputStream(doc.uri, "rwt")
                ?: throw IllegalStateException("无法打开写入流：$name")
            stream.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            files++
            bytes += file.length()
        }
        return files to bytes
    }

    /** 删除会话时一并删除外置目录里的副本，避免两边不一致。 */
    private fun deleteMirrored(context: Context, session: File, base: File) {
        val tree = externalRootUri(context) ?: return
        var node: DocumentFile? = DocumentFile.fromTreeUri(context, tree) ?: return
        for (segment in session.relativeTo(base).path.split(File.separatorChar)) {
            if (segment.isEmpty()) continue
            node = node?.findFile(segment) ?: return
        }
        val doc = node ?: return
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, doc.uri) }
            .recoverCatching { doc.delete() }
    }

    private fun mimeOf(name: String): String =
        when (name.substringAfterLast('.', "").lowercase()) {
            "json" -> "application/json"
            "md" -> "text/markdown"
            "txt", "log" -> "text/plain"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }

    private fun writeManifest(dir: File, session: Session) {
        File(dir, "manifest.json").writeText(
            JSONObject().apply {
                put("id", session.id)
                put("dir", session.dir)
                put("server", session.server)
                put("tool", session.tool)
                put("startedAt", session.startedAt)
                put("ok", session.ok)
                put("elapsedMs", session.elapsedMs)
                put("files", session.files)
                put("bytes", session.bytes)
                put("error", session.error)
                put("generator", "MCP Toolbox ${BuiltInMcpServer.SERVER_VERSION}")
            }.toString(2),
        )
    }

    private fun scan(dir: File): Pair<Int, Long> {
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        return files.size to files.sumOf { it.length() }
    }

    private fun renderName(template: String, tool: String, stamp: String, server: String): String {
        val key = "$server/$tool"
        val next = (sequence[key] ?: 0) + 1
        sequence[key] = next
        val name = template
            .replace("{tool}", safe(tool))
            .replace("{timestamp}", stamp)
            .replace("{seq}", next.toString().padStart(3, '0'))
            .replace("{server}", safe(server))
        return safe(name.ifBlank { safe(tool) + "_" + stamp })
    }

    private fun safe(text: String): String =
        text.replace(Regex("[^A-Za-z0-9._\\-\\u4e00-\\u9fa5]"), "_").take(60).ifBlank { "item" }

    /** 落盘前按规则脱敏：token / password / secret / authorization 等字段值替换为 ***。 */
    fun mask(argumentsJson: String): String {
        val sensitive = listOf("token", "password", "passwd", "secret", "authorization", "api_key", "apikey")
        return runCatching {
            val obj = JSONObject(argumentsJson)
            val copy = JSONObject(obj.toString())
            copy.keys().forEach { key ->
                val lower = key.lowercase()
                if (sensitive.any { lower.contains(it) }) copy.put(key, "***")
            }
            copy.toString(2)
        }.getOrDefault(argumentsJson)
    }
}

package com.mcp.toolbox.feature.mcp

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** 内置 Server 的一个工具定义。handler 返回结构化结果，由 server 包装成 MCP tools/call 结果。 */
data class ToolDef(
    val name: String,
    val title: String,
    val description: String,
    val schema: JSONObject,
    val readOnly: Boolean = true,
    val dangerous: Boolean = false,
    /** 需要高权限后端（root / Shizuku）才能真正执行；缺失时 handler 返回明确错误。 */
    val requiresPrivilege: Boolean = false,
    val handler: (Context, JSONObject) -> ToolResult,
)

data class ToolResult(
    val structured: JSONObject,
    val text: String,
    val isError: Boolean = false,
)

/** JSON Schema 组装工具。 */
object Schema {
    fun obj(props: List<Pair<String, JSONObject>>, required: List<String> = emptyList()): JSONObject =
        JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().also { o -> props.forEach { (k, v) -> o.put(k, v) } })
            if (required.isNotEmpty()) put("required", JSONArray(required))
            put("additionalProperties", false)
        }

    fun string(desc: String, default: String? = null, format: String? = null, enum: List<String>? = null): JSONObject =
        JSONObject().apply {
            put("type", "string")
            put("description", desc)
            if (default != null) put("default", default)
            if (format != null) put("format", format)
            if (enum != null) put("enum", JSONArray(enum))
        }

    fun integer(desc: String, default: Int? = null, min: Int? = null, max: Int? = null): JSONObject =
        JSONObject().apply {
            put("type", "integer")
            put("description", desc)
            if (default != null) put("default", default)
            if (min != null) put("minimum", min)
            if (max != null) put("maximum", max)
        }

    fun bool(desc: String, default: Boolean = false): JSONObject = JSONObject().apply {
        put("type", "boolean")
        put("description", desc)
        put("default", default)
    }

    fun objectMap(desc: String): JSONObject = JSONObject().apply {
        put("type", "object")
        put("description", desc)
        put("additionalProperties", JSONObject().put("type", "string"))
    }
}

/** 内置工具的公共支持：路径越界校验、摘要、容量信息。 */
object ToolSupport {

    fun allowedRoots(context: Context): List<File> = listOfNotNull(
        File("/storage/emulated/0"),
        File("/sdcard"),
        File("/data/local/tmp"),
        context.filesDir,
        context.cacheDir,
        context.getExternalFilesDir(null),
        ArtifactStore.root(context),
    ).map { runCatching { it.canonicalFile }.getOrDefault(it) }

    /** 拒绝 `..` 与逃逸到允许根之外的绝对路径。 */
    fun resolve(context: Context, rawPath: String): File {
        val path = if (rawPath.startsWith("~/")) rawPath.replaceFirst("~", "/storage/emulated/0") else rawPath
        val canonical = runCatching { File(path).canonicalFile }
            .getOrElse { throw IllegalArgumentException("路径无法解析：$rawPath") }
        val roots = allowedRoots(context)
        val ok = roots.any { root -> canonical == root || canonical.path.startsWith(root.path + File.separator) }
        if (!ok) {
            throw IllegalArgumentException(
                "路径越界被拒绝：${canonical.path} 不在允许根内（${roots.joinToString { it.path }}）",
            )
        }
        return canonical
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun human(bytes: Long): String = when {
        bytes >= 1073741824L -> "%.2f GB".format(bytes / 1073741824.0)
        bytes >= 1048576L -> "%.1f MB".format(bytes / 1048576.0)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

    fun storage(context: Context): JSONObject {
        val stat = StatFs(Environment.getDataDirectory().path)
        val mgr = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo().also { mgr.getMemoryInfo(it) }
        return JSONObject().apply {
            put("dataTotalBytes", stat.totalBytes)
            put("dataFreeBytes", stat.availableBytes)
            put("externalState", Environment.getExternalStorageState())
            put("ramTotalBytes", mem.totalMem)
            put("ramAvailBytes", mem.availMem)
            put("lowMemory", mem.lowMemory)
        }
    }
}

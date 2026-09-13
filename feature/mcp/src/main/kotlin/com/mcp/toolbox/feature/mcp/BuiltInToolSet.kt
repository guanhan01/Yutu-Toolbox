package com.mcp.toolbox.feature.mcp

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内置 MCP Server 暴露的工具集。全部是本机真实能力：
 * 设备信息、文件读写（带越界校验）、SQL 只读查询、真实 HTTP 请求、应用清单、产物目录。
 * 抓包记录（capture.records）因 P5 尚未接入，返回明确的不可用错误而不是假数据。
 */
object BuiltInToolSet {

    fun all(context: Context): List<ToolDef> = listOf(
        deviceInfo(context),
        fileList(context),
        fileRead(context),
        fileWrite(context),
        sqlQuery(context),
        httpRequest(context),
        appList(context),
        artifactList(context),
        captureRecords(),
    ) + BuiltInToolSetExtra.all(context) + BuiltInToolSetApk.all(context) + BuiltInToolSetDex.all(context) + BuiltInToolSetSys.all(context) + BuiltInToolSetFs.all(context) + BuiltInToolSetApps.all(context) + BuiltInToolSetDb.all(context) + BuiltInToolSetCapture.all(context) + BuiltInToolSetReverse.all(context)

    private fun deviceInfo(context: Context) = ToolDef(
        name = "device.info",
        title = "设备信息",
        description = "返回真实的机型、系统版本、ABI、内存与存储容量。",
        schema = Schema.obj(emptyList()),
        handler = { ctx, _ ->
            val structured = JSONObject().apply {
                put("manufacturer", Build.MANUFACTURER)
                put("brand", Build.BRAND)
                put("model", Build.MODEL)
                put("device", Build.DEVICE)
                put("androidRelease", Build.VERSION.RELEASE)
                put("sdkInt", Build.VERSION.SDK_INT)
                put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                put("securityPatch", Build.VERSION.SECURITY_PATCH)
                put("uptimeMillis", android.os.SystemClock.elapsedRealtime())
                put("storage", ToolSupport.storage(ctx))
                put("appPackage", ctx.packageName)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileList(context: Context) = ToolDef(
        name = "file.list",
        title = "列目录",
        description = "列出允许根目录（/sdcard、应用私有目录、/data/local/tmp、产物目录）下的条目。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("目录绝对路径", default = "/sdcard"),
                "limit" to Schema.integer("最多返回条目数", default = 100, min = 1, max = 1000),
            ),
            required = listOf("path"),
        ),
        handler = { ctx, args ->
            val dir = ToolSupport.resolve(ctx, args.optString("path", "/sdcard"))
            if (!dir.isDirectory) throw IllegalArgumentException("不是目录：${dir.path}")
            val limit = args.optInt("limit", 100).coerceIn(1, 1000)
            val entries = (dir.listFiles() ?: emptyArray())
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                .take(limit)
            val array = JSONArray()
            entries.forEach { f ->
                array.put(
                    JSONObject().apply {
                        put("name", f.name)
                        put("path", f.absolutePath)
                        put("dir", f.isDirectory)
                        put("sizeBytes", if (f.isDirectory) 0 else f.length())
                        put("sizeHuman", if (f.isDirectory) "-" else ToolSupport.human(f.length()))
                        put("modifiedAt", f.lastModified())
                        put("readable", f.canRead())
                    },
                )
            }
            val structured = JSONObject().apply {
                put("path", dir.absolutePath)
                put("count", array.length())
                put("entries", array)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileRead(context: Context) = ToolDef(
        name = "file.read",
        title = "读取文件",
        description = "读取文本或二进制文件（二进制返回 base64），带大小上限与 sha256。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("文件绝对路径"),
                "maxBytes" to Schema.integer("最多读取字节数", default = 65536, min = 1, max = 1048576),
                "encoding" to Schema.string("文本编码", default = "utf-8", enum = listOf("utf-8", "base64")),
            ),
            required = listOf("path"),
        ),
        handler = { ctx, args ->
            val file = ToolSupport.resolve(ctx, args.getString("path"))
            if (!file.isFile) throw IllegalArgumentException("不是文件：${file.path}")
            val max = args.optInt("maxBytes", 65536).coerceIn(1, 1048576)
            val length = file.length().coerceAtLeast(1).coerceAtMost(max.toLong()).toInt()
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(length)
                var read = 0
                while (read < buffer.size) {
                    val step = input.read(buffer, read, buffer.size - read)
                    if (step <= 0) break
                    read += step
                }
                buffer.copyOf(read)
            }
            val binary = bytes.take(512).any { it == 0.toByte() }
            val structured = JSONObject().apply {
                put("path", file.absolutePath)
                put("sizeBytes", file.length())
                put("sizeHuman", ToolSupport.human(file.length()))
                put("readBytes", bytes.size)
                put("truncated", file.length() > bytes.size)
                put("binary", binary)
                put("sha256", ToolSupport.sha256(bytes))
                put("modifiedAt", file.lastModified())
                if (binary || args.optString("encoding") == "base64") {
                    put("contentBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                } else {
                    put("content", String(bytes, Charsets.UTF_8))
                }
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileWrite(context: Context) = ToolDef(
        name = "file.write",
        title = "写入文件",
        description = "写入文本或 base64 内容，支持追加。需要在内置 Server 中开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("目标文件绝对路径"),
                "content" to Schema.string("文本内容；encoding=base64 时传 base64"),
                "append" to Schema.bool("是否追加", default = false),
                "encoding" to Schema.string("内容编码", default = "utf-8", enum = listOf("utf-8", "base64")),
            ),
            required = listOf("path", "content"),
        ),
        readOnly = false,
        dangerous = true,
        handler = { ctx, args ->
            if (!BuiltInMcpServer.config.value.allowWrite) {
                throw IllegalStateException("内置 Server 未开启写入：请在 MCP 页面打开「允许写入」后重试")
            }
            val file = ToolSupport.resolve(ctx, args.getString("path"))
            val raw = args.getString("content")
            val bytes = if (args.optString("encoding") == "base64") {
                Base64.decode(raw, Base64.DEFAULT)
            } else {
                raw.toByteArray(Charsets.UTF_8)
            }
            file.parentFile?.mkdirs()
            if (args.optBoolean("append")) file.appendBytes(bytes) else file.writeBytes(bytes)
            val structured = JSONObject().apply {
                put("path", file.absolutePath)
                put("writtenBytes", bytes.size)
                put("append", args.optBoolean("append"))
                put("sizeBytes", file.length())
                put("sha256", ToolSupport.sha256(bytes))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun sqlQuery(context: Context) = ToolDef(
        name = "sql.query",
        title = "SQL 只读查询",
        description = "对示例库（toolbox.db）或其他允许路径下的 SQLite 文件执行只读查询，返回列与行。",
        schema = Schema.obj(
            listOf(
                "sql" to Schema.string("SELECT / PRAGMA / WITH 查询语句"),
                "db" to Schema.string("数据库：sample 或绝对路径", default = "sample"),
                "limit" to Schema.integer("最多返回行数", default = 200, min = 1, max = 1000),
            ),
            required = listOf("sql"),
        ),
        handler = { ctx, args ->
            val sql = args.getString("sql").trim()
            val head = sql.trimStart().substringBefore(' ').uppercase()
            require(head in listOf("SELECT", "PRAGMA", "WITH", "EXPLAIN")) {
                "只读 Server 拒绝非查询语句（$head）：写操作请使用数据库模块或 file.write"
            }
            val dbName = args.optString("db", "sample")
            val file = if (dbName == "sample" || dbName.isBlank()) {
                ctx.getDatabasePath("toolbox.db")
            } else {
                ToolSupport.resolve(ctx, dbName)
            }
            require(file.isFile) { "数据库文件不存在：${file.absolutePath}" }
            val limit = args.optInt("limit", 200).coerceIn(1, 1000)
            val started = System.currentTimeMillis()
            val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val cursor = db.rawQuery(sql, null)
                val columns = JSONArray(cursor.columnNames.toList())
                val rows = JSONArray()
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val row = JSONObject()
                    cursor.columnNames.forEachIndexed { index, name ->
                        row.put(name, cursorValue(cursor, index))
                    }
                    rows.put(row)
                    count++
                }
                cursor.close()
                val structured = JSONObject().apply {
                    put("db", file.absolutePath)
                    put("sql", sql)
                    put("columns", columns)
                    put("rowCount", count)
                    put("rows", rows)
                    put("elapsedMs", System.currentTimeMillis() - started)
                }
                ToolResult(structured, structured.toString(2))
            } finally {
                db.close()
            }
        },
    )

    /** 按游标列类型还原值为 JSON 可用类型。 */
    private fun cursorValue(cursor: android.database.Cursor, index: Int): Any? = when (cursor.getType(index)) {
        android.database.Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
        android.database.Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
        android.database.Cursor.FIELD_TYPE_BLOB -> "blob(${cursor.getBlob(index)?.size ?: 0}B)"
        else -> cursor.getString(index)
    }

    private fun httpRequest(context: Context) = ToolDef(
        name = "http.request",
        title = "HTTP 请求",
        description = "真实发起 HTTP/HTTPS 请求并返回状态码、响应头、正文片段与耗时。",
        schema = Schema.obj(
            listOf(
                "url" to Schema.string("目标 URL", format = "uri"),
                "method" to Schema.string("HTTP 方法", default = "GET", enum = listOf("GET", "POST", "PUT", "DELETE", "HEAD")),
                "headers" to Schema.objectMap("请求头键值对"),
                "body" to Schema.string("请求正文"),
                "timeoutMs" to Schema.integer("超时毫秒", default = 15000, min = 1000, max = 60000),
                "maxBytes" to Schema.integer("正文最多读取字节", default = 65536, min = 256, max = 1048576),
            ),
            required = listOf("url"),
        ),
        handler = { ctx, args ->
            val urlText = args.getString("url")
            val url = URL(urlText)
            require(url.protocol == "http" || url.protocol == "https") { "只支持 http/https：${url.protocol}" }
            val method = args.optString("method", "GET").uppercase()
            val timeout = args.optInt("timeoutMs", 15000).coerceIn(1000, 60000)
            val max = args.optInt("maxBytes", 65536).coerceIn(256, 1048576)
            val started = System.currentTimeMillis()
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeout
                readTimeout = timeout
                instanceFollowRedirects = true
                args.optJSONObject("headers")?.let { headers ->
                    headers.keys().forEach { key -> setRequestProperty(key, headers.optString(key)) }
                }
            }
            val body = args.optString("body")
            if (body.isNotEmpty() && method in listOf("POST", "PUT", "DELETE")) {
                connection.doOutput = true
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { input ->
                val buffer = ByteArray(max)
                var read = 0
                while (read < buffer.size) {
                    val step = input.read(buffer, read, buffer.size - read)
                    if (step <= 0) break
                    read += step
                }
                buffer.copyOf(read)
            } ?: ByteArray(0)
            val headersJson = JSONObject().apply {
                connection.headerFields.forEach { (key, values) ->
                    if (key != null && values != null) put(key, values.joinToString(", "))
                }
            }
            val structured = JSONObject().apply {
                put("url", connection.url.toString())
                put("method", method)
                put("status", status)
                put("statusText", connection.responseMessage ?: "")
                put("headers", headersJson)
                put("bodyBytes", bytes.size)
                put("body", String(bytes, Charsets.UTF_8))
                put("elapsedMs", System.currentTimeMillis() - started)
            }
            connection.disconnect()
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun appList(context: Context) = ToolDef(
        name = "app.list",
        title = "已安装应用",
        description = "通过 PackageManager 返回真实安装的应用清单（名称、包名、版本、体积）。",
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
            val started = System.currentTimeMillis()
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val rows = JSONArray()
            var matched = 0
            installed.forEach { info ->
                val system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (system && !includeSystem) return@forEach
                val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                val hit = query.isEmpty() ||
                    label.lowercase().contains(query) ||
                    info.packageName.lowercase().contains(query)
                if (!hit) return@forEach
                matched++
                if (rows.length() >= limit) return@forEach
                val versionName = runCatching { pm.getPackageInfo(info.packageName, 0).versionName ?: "" }
                    .getOrDefault("")
                val apk = File(info.sourceDir)
                rows.put(
                    JSONObject().apply {
                        put("label", label)
                        put("package", info.packageName)
                        put("versionName", versionName)
                        put("system", system)
                        put("enabled", info.enabled)
                        put("sourceDir", info.sourceDir)
                        put("apkBytes", apk.length())
                        put("apkHuman", ToolSupport.human(apk.length()))
                        put("uid", info.uid)
                    },
                )
            }
            val structured = JSONObject().apply {
                put("matched", matched)
                put("returned", rows.length())
                put("elapsedMs", System.currentTimeMillis() - started)
                put("apps", rows)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun artifactList(context: Context) = ToolDef(
        name = "artifact.list",
        title = "MCP 产物会话",
        description = "列出输出文件夹中已落盘的会话目录（解析 manifest.json）。",
        schema = Schema.obj(
            listOf("limit" to Schema.integer("最多返回会话数", default = 20, min = 1, max = 100)),
        ),
        handler = { ctx, args ->
            val sessions = ArtifactStore.list(ctx).take(args.optInt("limit", 20).coerceIn(1, 100))
            val structured = JSONObject().apply {
                put("enabled", ArtifactStore.config.value.enabled)
                put("root", ArtifactStore.root(ctx).absolutePath)
                put("count", sessions.size)
                put("sessions", JSONArray().also { array -> sessions.forEach { array.put(it.toJson()) } })
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun captureRecords() = ToolDef(
        name = "capture.records",
        title = "抓包记录",
        description = "返回抓包模块（P5）采集到的请求记录。",
        schema = Schema.obj(
            listOf("limit" to Schema.integer("最多返回条数", default = 50, min = 1, max = 500)),
        ),
        handler = { _, _ ->
            val structured = JSONObject().apply {
                put("available", false)
                put("reason", "抓包模块（P5）尚未接入：CaptureVpnService 未启用，本工具暂无数据来源")
            }
            ToolResult(
                structured = structured,
                text = "抓包模块尚未接入，暂不提供记录。启用 VpnService 抓包后此工具会返回真实请求记录。",
                isError = true,
            )
        },
    )
}

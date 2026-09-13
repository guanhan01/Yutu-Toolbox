package com.mcp.toolbox.feature.mcp

import android.content.Context
import android.os.Environment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 内置 MCP Server：真实的 HTTP + SSE JSON-RPC 服务端，暴露本机能力（见 BuiltInToolSet）。
 *
 * 端点：
 *   POST /mcp                   JSON-RPC（streamable HTTP）
 *   GET  /sse + POST /message   legacy SSE 传输（endpoint 事件 + 会话推送）
 *   GET  /health                健康检查
 *   GET  /                      工具清单状态页（浏览器可直接打开）
 */
object BuiltInMcpServer {

    const val PROTOCOL_VERSION = "2025-06-18"
    const val DEFAULT_PORT = 8765
    const val SERVER_NAME = "mcp-toolbox-builtin"
    const val SERVER_VERSION = "0.1.0"

    data class Config(
        val port: Int = DEFAULT_PORT,
        val token: String = "",
        val allowWrite: Boolean = false,
        val localOnly: Boolean = true,
        val enabledTools: Set<String> = emptySet(),
    )

    data class LogEntry(
        val at: Long,
        val kind: String,
        val detail: String,
        val ok: Boolean,
        val elapsedMs: Long,
        val client: String,
    )

    private const val PREFS = "mcp-server"
    private const val KEY_CONFIG = "config"

    val config = MutableStateFlow(Config())
    val running = MutableStateFlow(false)
    val logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val requestCount = MutableStateFlow(0L)
    val startedAt = MutableStateFlow(0L)
    val lastError = MutableStateFlow<String?>(null)

    private var socket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var scope: CoroutineScope? = null
    private val sessions = ConcurrentHashMap<String, SseSession>()
    private var toolsByContext: List<ToolDef> = emptyList()
    private var appContext: Context? = null

    val httpEndpoint: String get() = "http://127.0.0.1:${config.value.port}/mcp"
    val sseEndpoint: String get() = "http://127.0.0.1:${config.value.port}/sse"

    fun lanAddress(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.address.size == 4 }
            ?.hostAddress
    }.getOrNull()

    private class SseSession(val id: String, val out: OutputStream) {
        @Volatile var closed = false

        fun send(event: String, data: String): Boolean = runCatching {
            synchronized(out) {
                out.write("event: $event\ndata: $data\n\n".toByteArray())
                out.flush()
            }
            true
        }.getOrDefault(false)
    }

    fun load(context: Context) {
        appContext = context.applicationContext
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CONFIG, null)
        val loaded = runCatching {
            val obj = JSONObject(raw ?: "{}")
            Config(
                port = obj.optInt("port", DEFAULT_PORT),
                token = obj.optString("token"),
                allowWrite = obj.optBoolean("allowWrite"),
                localOnly = obj.optBoolean("localOnly", true),
                enabledTools = obj.optJSONArray("enabledTools")?.let { arr ->
                    (0 until arr.length()).map { arr.optString(it) }.toSet()
                } ?: emptySet(),
            )
        }.getOrDefault(Config())
        config.value = if (loaded.token.isBlank()) {
            pinnedToken()?.let { loaded.copy(token = it) }
                ?: loaded.copy(token = UUID.randomUUID().toString().replace("-", "").take(24))
        } else {
            loaded
        }
        if (loaded.token.isBlank()) persistConfig(context)
        toolsByContext = BuiltInToolSet.all(context)
    }

    fun saveConfig(context: Context, next: Config) {
        config.value = next.copy(token = next.token.ifBlank { config.value.token })
        persistConfig(context)
        McpRegistry.syncBuiltIn(context)
    }

    // ── token 固定：跨「清理数据 / 卸载重装」保留 ──────────────────────
    // token 默认只存在 app 私有 SharedPreferences 里，清理数据就没了，
    // 于是每次重装都随机生成一个新 token，客户端配置全部失效。
    // 这里把当前 token 额外落盘到公共 Documents 目录（app 私有目录之外，
    // 清数据与卸载重装都不会删），启动时若私有配置为空则优先恢复。
    private const val TOKEN_DIR = "MCPToolbox"
    private const val TOKEN_FILE = "mcp-token.txt"

    /**
     * 固定 token 的落盘候选位置（按优先级）：
     *  1) /storage/emulated/0/Documents/MCPToolbox/mcp-token.txt —— 用户可见，清数据/卸载重装都在；
     *  2) /storage/emulated/0/Android/media/<pkg>/mcp-token.txt —— 无需任何存储权限的兜底。
     */
    fun tokenFiles(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val pkg = appContext?.packageName ?: "com.mcp.toolbox"
        return listOf(
            File(root, "Documents/$TOKEN_DIR/$TOKEN_FILE"),
            File(root, "Android/media/$pkg/$TOKEN_FILE"),
        )
    }

    /** 首选（用户可见、可手工编辑）的固定文件位置。 */
    fun tokenFile(): File = tokenFiles().first()

    /** 读取已固定的 token；依次尝试各候选位置，都为空时返回 null。 */
    fun pinnedToken(): String? {
        for (file in tokenFiles()) {
            val value = runCatching {
                if (file.isFile) file.readText().trim() else null
            }.getOrNull()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    fun isTokenPinned(): Boolean = pinnedToken() != null

    /** 把当前 token 固定到所有候选位置，只要有一处写入成功即算成功。 */
    fun pinToken(): Boolean {
        val token = config.value.token
        var ok = false
        for (file in tokenFiles()) {
            val written = runCatching {
                file.parentFile?.mkdirs()
                file.writeText(token)
                file.isFile
            }.getOrDefault(false)
            if (written) ok = true
        }
        return ok
    }

    /** 取消固定：删除所有候选落盘文件（当前生效的 token 不变）。 */
    fun unpinToken(): Boolean {
        var removed = false
        for (file in tokenFiles()) {
            val gone = runCatching { !file.exists() || file.delete() }.getOrDefault(false)
            if (gone) removed = true
        }
        return removed
    }

    /** 重新随机生成 token；若处于固定状态则同步更新落盘文件。 */
    fun regenerateToken(context: Context): String {
        val next = UUID.randomUUID().toString().replace("-", "").take(24)
        config.value = config.value.copy(token = next)
        persistConfig(context)
        if (isTokenPinned()) pinToken()
        return next
    }

    private fun persistConfig(context: Context) {
        val cfg = config.value
        val obj = JSONObject().apply {
            put("port", cfg.port)
            put("token", cfg.token)
            put("allowWrite", cfg.allowWrite)
            put("localOnly", cfg.localOnly)
            put("enabledTools", JSONArray(cfg.enabledTools.toList()))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CONFIG, obj.toString()).apply()
    }

    fun tools(): List<ToolDef> {
        val enabled = config.value.enabledTools
        return if (enabled.isEmpty()) toolsByContext else toolsByContext.filter { it.name in enabled }
    }

    fun start(context: Context, port: Int = config.value.port): Result<Int> {
        if (running.value) return Result.success(config.value.port)
        return runCatching {
            val cfg = config.value
            val address = InetAddress.getByName(if (cfg.localOnly) "127.0.0.1" else "0.0.0.0")
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(address, port), 32)
            socket = server
            val newScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope = newScope
            startedAt.value = System.currentTimeMillis()
            requestCount.value = 0L
            lastError.value = null
            running.value = true
            acceptJob = newScope.launch {
                while (isActive) {
                    val client = runCatching { server.accept() }.getOrNull() ?: continue
                    launch { handleClient(context, client) }
                }
            }
            addLog("server", "监听 ${address.hostAddress}:${server.localPort} · 工具 ${tools().size} 个", true, 0, "-")
            server.localPort
        }.onFailure {
            running.value = false
            lastError.value = it.message ?: it.javaClass.simpleName
            addLog("server", "启动失败：${lastError.value}", false, 0, "-")
        }
    }

    fun stop() {
        sessions.values.forEach { runCatching { it.out.close() } }
        sessions.clear()
        runCatching { acceptJob?.cancel() }
        runCatching { socket?.close() }
        runCatching { scope?.cancel() }
        socket = null
        acceptJob = null
        scope = null
        running.value = false
        addLog("server", "已停止", true, 0, "-")
    }

    private fun addLog(kind: String, detail: String, ok: Boolean, elapsedMs: Long, client: String) {
        logs.value = (
            listOf(LogEntry(System.currentTimeMillis(), kind, detail, ok, elapsedMs, client)) + logs.value
            ).take(200)
    }

    private fun handleClient(context: Context, client: Socket) {
        var keepAlive = false
        try {
            client.soTimeout = 20000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.trim().split(" ")
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val target = parts[1]
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) readBody(input, length) else ""
            val path = target.substringBefore('?')
            val sessionId = target.substringAfter("sessionId=", "").substringBefore('&')
            val clientLabel = runCatching { client.inetAddress.hostAddress ?: "-" }.getOrDefault("-")
            val out = client.getOutputStream()

            when {
                method == "GET" && path == "/health" -> {
                    val payload = JSONObject().apply {
                        put("status", "ok")
                        put("server", SERVER_NAME)
                        put("version", SERVER_VERSION)
                        put("protocolVersion", PROTOCOL_VERSION)
                        put("tools", tools().size)
                        put("requests", requestCount.value)
                        put("uptimeMs", System.currentTimeMillis() - startedAt.value)
                        put("authRequired", config.value.token.isNotBlank())
                    }
                    writeResponse(out, 200, "OK", "application/json", payload.toString(2))
                }

                method == "GET" && (path == "/" || path == "/index.html") ->
                    writeResponse(out, 200, "OK", "text/html; charset=utf-8", statusPage())

                method == "GET" && path == "/sse" -> {
                    if (!authorized(headers)) {
                        writeResponse(out, 401, "Unauthorized", "application/json", errorBody("缺少或错误的 token"))
                        return
                    }
                    val id = UUID.randomUUID().toString().take(8)
                    val session = SseSession(id, out)
                    sessions[id] = session
                    writeRaw(out, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n\r\n")
                    session.send("endpoint", "/message?sessionId=$id")
                    addLog("sse", "建立 SSE 会话 $id", true, 0, clientLabel)
                    keepAlive = true
                    var alive = 1800000L
                    while (alive > 0 && !session.closed) {
                        Thread.sleep(15000)
                        alive -= 15000
                        if (!session.send("ping", "{}")) session.closed = true
                    }
                    sessions.remove(id)
                    addLog("sse", "SSE 会话 $id 结束", true, 0, clientLabel)
                }

                method == "POST" && path == "/message" -> {
                    if (!authorized(headers)) {
                        writeResponse(out, 401, "Unauthorized", "application/json", errorBody("缺少或错误的 token"))
                        return
                    }
                    val session = sessions[sessionId]
                    val response = dispatch(context, body, clientLabel)
                    if (session == null) {
                        writeResponse(out, 404, "Not Found", "application/json", errorBody("SSE 会话不存在：$sessionId"))
                    } else {
                        writeResponse(out, 202, "Accepted", "application/json", JSONObject().put("accepted", true).toString())
                        if (response != null) session.send("message", response.toString())
                    }
                }

                method == "POST" && (path == "/mcp" || path == "/") -> {
                    if (!authorized(headers)) {
                        writeResponse(out, 401, "Unauthorized", "application/json", errorBody("缺少或错误的 token"))
                        return
                    }
                    val response = dispatch(context, body, clientLabel)
                    writeResponse(out, 200, "OK", "application/json", response?.toString() ?: JSONObject().put("accepted", true).toString())
                }

                else -> writeResponse(out, 404, "Not Found", "application/json", errorBody("未知路径：$target"))
            }
        } catch (t: Throwable) {
            addLog("error", t.message ?: t.javaClass.simpleName, false, 0, "-")
        } finally {
            if (!keepAlive) runCatching { client.close() }
        }
    }

    private fun authorized(headers: Map<String, String>): Boolean {
        val token = config.value.token
        if (token.isBlank()) return true
        val auth = headers["authorization"]?.removePrefix("Bearer ")?.trim()
        val direct = headers["x-mcp-token"]
        return auth == token || direct == token
    }

    private fun readLine(input: InputStream): String? {
        val buffer = StringBuilder()
        var read = input.read()
        if (read < 0) return null
        while (read >= 0) {
            val ch = read.toChar()
            if (ch == '\n') break
            if (ch != '\r') buffer.append(ch)
            read = input.read()
        }
        return buffer.toString()
    }

    private fun readBody(input: InputStream, length: Int): String {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val step = input.read(bytes, read, length - read)
            if (step <= 0) break
            read += step
        }
        return String(bytes, 0, read, Charsets.UTF_8)
    }

    private fun writeRaw(out: OutputStream, text: String) {
        out.write(text.toByteArray())
        out.flush()
    }

    private fun writeResponse(out: OutputStream, status: Int, reason: String, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: authorization, content-type, x-mcp-token\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray())
        out.write(bytes)
        out.flush()
    }

    private fun errorBody(message: String): String =
        JSONObject().put("error", message).toString(2)

    /** JSON-RPC 分发；通知（id 缺失）返回 null。 */
    private fun dispatch(context: Context, body: String, clientLabel: String): JSONObject? {
        val started = System.currentTimeMillis()
        requestCount.value += 1
        if (body.isBlank()) {
            addLog("rpc", "空请求体", false, 0, clientLabel)
            return JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", JSONObject.NULL)
                put("error", JSONObject().put("code", -32700).put("message", "Parse error: 空请求体"))
            }
        }
        val request = runCatching { JSONObject(body) }.getOrElse {
            return JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", JSONObject.NULL)
                put("error", JSONObject().put("code", -32700).put("message", "Parse error: ${it.message}"))
            }
        }
        val method = request.optString("method")
        val response = handleRpc(context, request)
        val elapsed = System.currentTimeMillis() - started
        val toolName = request.optJSONObject("params")?.optString("name").orEmpty()
        val toolErrored = response?.optJSONObject("result")?.optBoolean("isError") == true
        val detail = if (toolName.isNotBlank()) {
            toolName + if (toolErrored) " · 工具报错" else ""
        } else {
            method
        }
        addLog(
            kind = if (method == "tools/call") "tools/call" else method.ifBlank { "rpc" },
            detail = detail,
            ok = response?.has("error") != true,
            elapsedMs = elapsed,
            client = clientLabel,
        )
        return response
    }

    private fun handleRpc(context: Context, request: JSONObject): JSONObject? {
        val method = request.optString("method")
        val id = if (request.has("id")) request.opt("id") else null
        if (method.startsWith("notifications/")) return null
        val params = request.optJSONObject("params") ?: JSONObject()
        fun ok(result: JSONObject): JSONObject = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("result", result)
        }
        fun fail(code: Int, message: String): JSONObject = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", id ?: JSONObject.NULL)
            put("error", JSONObject().put("code", code).put("message", message))
        }
        return try {
            when (method) {
                "initialize" -> ok(
                    JSONObject().apply {
                        put("protocolVersion", request.optJSONObject("params")?.optString("protocolVersion").takeIf { !it.isNullOrBlank() } ?: PROTOCOL_VERSION)
                        put("capabilities", JSONObject().apply {
                            put("tools", JSONObject().put("listChanged", false))
                            put("resources", JSONObject().put("subscribe", false))
                            put("prompts", JSONObject())
                        })
                        put("serverInfo", JSONObject().apply {
                            put("name", SERVER_NAME)
                            put("title", "MCP Toolbox 内置 Server")
                            put("version", SERVER_VERSION)
                        })
                        put(
                            "instructions",
                            "本机工具箱内置 Server：提供设备信息、文件读写、SQL 只读查询、HTTP 请求、应用清单与产物目录工具。" +
                                "写入类工具需在 App 内开启「允许写入」。",
                        )
                    },
                )

                "ping" -> ok(JSONObject())

                "tools/list" -> ok(
                    JSONObject().put(
                        "tools",
                        JSONArray().also { array ->
                            tools().forEach { tool ->
                                array.put(
                                    JSONObject().apply {
                                        put("name", tool.name)
                                        put("title", tool.title)
                                        put("description", tool.description)
                                        put("inputSchema", tool.schema)
                                        put("annotations", JSONObject().apply {
                                            put("readOnlyHint", tool.readOnly)
                                            put("destructiveHint", tool.dangerous)
                                        })
                                    },
                                )
                            }
                        },
                    ),
                )

                "tools/call" -> {
                    val name = params.optString("name")
                    val args = params.optJSONObject("arguments") ?: JSONObject()
                    val tool = tools().firstOrNull { it.name == name }
                    if (tool == null) fail(-32602, "未知工具：$name") else ok(runTool(context, tool, args))
                }

                "resources/list" -> ok(
                    JSONObject().put(
                        "resources",
                        JSONArray().also { array ->
                            listOf(
                                Triple("mcp-output://sessions", "MCP 产物会话", "application/json"),
                                Triple("device://storage", "存储与内存容量", "application/json"),
                            ).forEach { (uri, label, mime) ->
                                array.put(
                                    JSONObject().apply {
                                        put("uri", uri)
                                        put("name", label)
                                        put("mimeType", mime)
                                        put("description", "由内置 Server 实时生成")
                                    },
                                )
                            }
                        },
                    ),
                )

                "resources/read" -> {
                    val uri = params.optString("uri")
                    val payload = when (uri) {
                        "mcp-output://sessions" -> JSONObject().apply {
                            put("root", ArtifactStore.root(context).absolutePath)
                            put("sessions", JSONArray().also { array ->
                                ArtifactStore.list(context).forEach { array.put(it.toJson()) }
                            })
                        }
                        "device://storage" -> ToolSupport.storage(context)
                        else -> null
                    }
                    if (payload == null) {
                        fail(-32602, "未知资源：$uri")
                    } else {
                        ok(
                            JSONObject().put(
                                "contents",
                                JSONArray().put(
                                    JSONObject().apply {
                                        put("uri", uri)
                                        put("mimeType", "application/json")
                                        put("text", payload.toString(2))
                                    },
                                ),
                            ),
                        )
                    }
                }

                "prompts/list" -> ok(JSONObject().put("prompts", JSONArray()))

                else -> fail(-32601, "Method not found: $method")
            }
        } catch (t: Throwable) {
            fail(-32000, t.message ?: t.javaClass.simpleName)
        }
    }

    /** 执行工具并包装成 MCP tools/call 结果；工具异常作为 isError 结果返回而非 JSON-RPC 错误。 */
    private fun runTool(context: Context, tool: ToolDef, args: JSONObject): JSONObject {
        val started = System.currentTimeMillis()
        return try {
            val result = tool.handler(context, args)
            JSONObject().apply {
                put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.text)))
                put("structuredContent", result.structured)
                put("isError", result.isError)
                put("_meta", JSONObject().put("elapsedMs", System.currentTimeMillis() - started))
            }
        } catch (t: Throwable) {
            val message = t.message ?: t.javaClass.simpleName
            JSONObject().apply {
                put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "工具执行失败：$message")))
                put("isError", true)
                put("_meta", JSONObject().put("elapsedMs", System.currentTimeMillis() - started))
            }
        }
    }

    /** 浏览器可直接打开的状态页，便于人工核对服务端真实状态。 */
    private fun statusPage(): String {
        val cfg = config.value
        val rows = tools().joinToString("") { tool ->
            "<tr><td><code>${tool.name}</code></td><td>${tool.title}</td><td>${tool.description}</td>" +
                "<td>${if (tool.readOnly) "只读" else "可写"}</td></tr>"
        }
        return """
            <!doctype html><html lang="zh"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>MCP Toolbox 内置 Server</title>
            <style>
            body{font-family:system-ui,-apple-system,sans-serif;background:#121212;color:#e6e1e5;margin:0;padding:24px}
            h1{font-size:20px}code{color:#7fd1c1}
            table{border-collapse:collapse;width:100%;font-size:13px}
            td,th{border-bottom:1px solid #333;padding:8px;text-align:left;vertical-align:top}
            .pill{display:inline-block;padding:2px 10px;border:1px solid #7fd1c1;border-radius:999px;color:#7fd1c1;font-size:12px}
            </style></head><body>
            <h1>MCP Toolbox 内置 Server <span class="pill">运行中</span></h1>
            <p>协议版本 <code>${PROTOCOL_VERSION}</code> · 服务名 <code>${SERVER_NAME}</code> · 版本 <code>${SERVER_VERSION}</code></p>
            <p>端点：<code>POST /mcp</code>（JSON-RPC）、<code>GET /sse</code> + <code>POST /message</code>（SSE）、<code>GET /health</code></p>
            <p>鉴权：${if (cfg.token.isBlank()) "未启用" else "需要 Bearer token（已在 App 内生成）"} ·
            写入：${if (cfg.allowWrite) "允许" else "禁止"} · 监听：${if (cfg.localOnly) "仅本机 127.0.0.1" else "局域网可访问"}</p>
            <p>请求数 ${requestCount.value} · 工具 ${tools().size} 个 · 运行时长 ${(System.currentTimeMillis() - startedAt.value) / 1000}s</p>
            <table><tr><th>工具</th><th>标题</th><th>说明</th><th>属性</th></tr>$rows</table>
            </body></html>
        """.trimIndent()
    }
}

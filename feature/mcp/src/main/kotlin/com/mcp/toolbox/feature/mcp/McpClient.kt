package com.mcp.toolbox.feature.mcp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** MCP 传输抽象：一次 JSON-RPC 请求-响应。 */
interface McpTransportClient {
    val label: String
    fun request(method: String, params: JSONObject? = null): JSONObject
    fun close() {}

    companion object {
        fun create(config: McpServerConfig): McpTransportClient = when (config.transport) {
            McpTransport.SSE -> SseTransportClient(config)
            McpTransport.HTTP -> HttpTransportClient(config)
            McpTransport.STDIO -> throw IllegalStateException(
                "stdio 传输需要 Shizuku / root / Termux 桥接进程，当前未提供可执行的 MCP server 二进制；请改用 HTTP 或 SSE。",
            )
        }
    }
}

/** JSON-RPC 错误 -> 异常。 */
class McpRpcException(val code: Int, message: String) : Exception(message)

private fun applyCredentials(connection: HttpURLConnection, config: McpServerConfig) {
    connection.setRequestProperty("Content-Type", "application/json")
    connection.setRequestProperty("Accept", "application/json, text/event-stream")
    connection.setRequestProperty("User-Agent", "MCPToolbox/${BuiltInMcpServer.SERVER_VERSION}")
    if (config.token.isNotBlank()) connection.setRequestProperty("Authorization", "Bearer ${config.token}")
    config.headers.forEach { (key, value) -> connection.setRequestProperty(key, value) }
}

/** 从可能被 SSE 包装的响应体里取出 JSON-RPC 报文。 */
private fun extractJsonRpc(text: String): JSONObject {
    val trimmed = text.trim()
    if (trimmed.startsWith("{")) return JSONObject(trimmed)
    val dataLines = trimmed.lines().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
    val last = dataLines.lastOrNull { it.startsWith("{") }
        ?: throw IllegalStateException("响应不是有效 JSON-RPC：${trimmed.take(200)}")
    return JSONObject(last)
}

private fun unwrap(response: JSONObject): JSONObject {
    val error = response.optJSONObject("error")
    if (error != null) throw McpRpcException(error.optInt("code"), error.optString("message"))
    return response.optJSONObject("result") ?: JSONObject()
}

/** streamable HTTP 传输：每个请求一次 POST。 */
class HttpTransportClient(private val config: McpServerConfig) : McpTransportClient {

    override val label = "HTTP"
    private var sessionId: String? = null

    override fun request(method: String, params: JSONObject?): JSONObject {
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", RpcId.next())
            put("method", method)
            if (params != null) put("params", params)
        }
        val connection = (URL(config.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = config.timeoutMs
            readTimeout = config.timeoutMs
            instanceFollowRedirects = true
            applyCredentials(this, config)
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
        }
        connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        val status = connection.responseCode
        connection.getHeaderField("Mcp-Session-Id")?.let { sessionId = it }
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val text = stream?.let { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { it.readText() }
        }.orEmpty()
        if (status >= 400) {
            val detail = runCatching { JSONObject(text).optString("error") }.getOrNull()
            throw IllegalStateException("HTTP $status ${connection.responseMessage}${detail?.let { "：$it" } ?: ""}")
        }
        return unwrap(extractJsonRpc(text))
    }
}

/** legacy SSE 传输：GET /sse 拿到 endpoint 事件，再 POST 到会话地址，响应从流里按 id 回投。 */
class SseTransportClient(private val config: McpServerConfig) : McpTransportClient {

    override val label = "SSE"

    private val pending = ConcurrentHashMap<String, LinkedBlockingQueue<JSONObject>>()
    private val started = AtomicBoolean(false)
    @Volatile private var endpoint: String? = null
    @Volatile private var failure: String? = null
    private var worker: Thread? = null

    private fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        val thread = Thread {
            try {
                val connection = (URL(config.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = config.timeoutMs
                    readTimeout = 0
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Cache-Control", "no-cache")
                    if (config.token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${config.token}")
                    config.headers.forEach { (key, value) -> setRequestProperty(key, value) }
                }
                val status = connection.responseCode
                if (status >= 400) {
                    failure = "SSE 连接失败：HTTP $status ${connection.responseMessage}"
                    return@Thread
                }
                val reader = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                var event = "message"
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isEmpty() -> event = "message"
                        line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            val data = line.removePrefix("data:").trim()
                            when (event) {
                                "endpoint" -> endpoint = resolve(data)
                                "message" -> deliver(data)
                            }
                        }
                    }
                }
            } catch (t: Throwable) {
                failure = t.message ?: t.javaClass.simpleName
            }
        }
        thread.isDaemon = true
        thread.start()
        worker = thread
    }

    private fun resolve(path: String): String {
        if (path.startsWith("http")) return path
        val base = URL(config.url)
        val port = if (base.port > 0) base.port else if (base.protocol == "https") 443 else 80
        return "${base.protocol}://${base.host}:$port$path"
    }

    private fun deliver(data: String) {
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return
        val id = json.opt("id")?.toString() ?: return
        pending[id]?.offer(json)
    }

    override fun request(method: String, params: JSONObject?): JSONObject {
        ensureStarted()
        var waited = 0
        while (endpoint == null && failure == null && waited < config.timeoutMs) {
            Thread.sleep(50)
            waited += 50
        }
        failure?.let { throw IllegalStateException(it) }
        val target = endpoint ?: throw IllegalStateException("SSE 会话未就绪：未收到 endpoint 事件")
        val payload = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", RpcId.next())
            put("method", method)
            if (params != null) put("params", params)
        }
        val id = payload.optInt("id").toString()
        val queue = LinkedBlockingQueue<JSONObject>()
        pending[id] = queue
        try {
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = config.timeoutMs
                readTimeout = config.timeoutMs
                applyCredentials(this, config)
            }
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status >= 400) throw IllegalStateException("SSE 投递失败：HTTP $status")
            val response = queue.poll(config.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
                ?: throw IllegalStateException("等待 SSE 响应超时（${config.timeoutMs}ms）")
            return unwrap(response)
        } finally {
            pending.remove(id)
        }
    }

    override fun close() {
        runCatching { worker?.interrupt() }
    }
}

/** 一次 connect / call 的高层封装：握手 + 能力发现 + 工具调用。 */
object McpClient {

    private val INITIALIZE_PARAMS = JSONObject().apply {
        put("protocolVersion", BuiltInMcpServer.PROTOCOL_VERSION)
        put("capabilities", JSONObject().apply {
            put("roots", JSONObject().put("listChanged", false))
            put("sampling", JSONObject())
        })
        put("clientInfo", JSONObject().apply {
            put("name", "mcp-toolbox-android")
            put("title", "MCP Toolbox")
            put("version", BuiltInMcpServer.SERVER_VERSION)
        })
    }

    /** 连接：initialize 握手 + tools/resources/prompts 能力发现，全程真实网络调用。 */
    suspend fun connect(context: Context, config: McpServerConfig): McpServerState =
        withContext(Dispatchers.IO) {
            McpRegistry.setState(McpServerState(config.id, McpConnectionState.CONNECTING, message = "握手…"))
            val started = System.currentTimeMillis()
            try {
                val client = McpTransportClient.create(config)
                try {
                    val init = client.request("initialize", INITIALIZE_PARAMS)
                    val latency = System.currentTimeMillis() - started
                    runCatching { client.request("notifications/initialized", null) }
                    val toolsResult = runCatching { client.request("tools/list", JSONObject()) }.getOrElse { JSONObject() }
                    val resourcesResult = runCatching { client.request("resources/list", JSONObject()) }.getOrElse { JSONObject() }
                    val promptsResult = runCatching { client.request("prompts/list", JSONObject()) }.getOrElse { JSONObject() }

                    val tools = parseTools(toolsResult)
                    val resources = parseResources(resourcesResult)
                    val prompts = parsePrompts(promptsResult)
                    McpRegistry.setCatalog(config.id, tools, resources, prompts)

                    val info = init.optJSONObject("serverInfo") ?: JSONObject()
                    val state = McpServerState(
                        serverId = config.id,
                        state = McpConnectionState.READY,
                        serverName = info.optString("name").ifBlank { config.name },
                        serverVersion = info.optString("version"),
                        protocolVersion = init.optString("protocolVersion"),
                        instructions = init.optString("instructions"),
                        latencyMs = latency,
                        toolCount = tools.size,
                        resourceCount = resources.size,
                        promptCount = prompts.size,
                        message = "${client.label} 传输 · ${tools.size} 个工具",
                        at = System.currentTimeMillis(),
                    )
                    McpRegistry.setState(state)
                    state
                } finally {
                    client.close()
                }
            } catch (t: Throwable) {
                val state = McpServerState(
                    serverId = config.id,
                    state = McpConnectionState.ERROR,
                    latencyMs = System.currentTimeMillis() - started,
                    message = t.message ?: t.javaClass.simpleName,
                    at = System.currentTimeMillis(),
                )
                McpRegistry.setState(state)
                state
            }
        }

    /** 调用工具：真实请求 + 计时 + 产物落盘（输出文件夹开启时）+ 调用日志。 */
    suspend fun callTool(
        context: Context,
        config: McpServerConfig,
        tool: McpToolInfo,
        arguments: JSONObject,
    ): McpCallRecord = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val writer = ArtifactStore.begin(context, config.name, tool.name, arguments.toString(2))
        val payload = JSONObject().apply {
            put("name", tool.name)
            put("arguments", arguments)
        }
        var resultText = ""
        var ok = false
        var isError = false
        var error: String? = null
        try {
            val client = McpTransportClient.create(config)
            try {
                val result = client.request("tools/call", payload)
                isError = result.optBoolean("isError")
                resultText = mcpContentToText(result)
                ok = !isError
                if (resultText.isBlank()) resultText = result.toString(2)
            } finally {
                client.close()
            }
        } catch (t: Throwable) {
            error = t.message ?: t.javaClass.simpleName
            resultText = error
        }
        val elapsed = System.currentTimeMillis() - started
        val session = writer?.finish(resultText, ok, isError, elapsed, error)
        val record = McpCallRecord(
            id = java.util.UUID.randomUUID().toString().take(8),
            serverId = config.id,
            serverName = config.name,
            toolName = tool.name,
            argumentsJson = arguments.toString(2),
            ok = ok,
            isError = isError,
            startedAt = started,
            elapsedMs = elapsed,
            resultText = resultText,
            error = error,
            artifactDir = session?.dir ?: writer?.path,
            artifactFiles = session?.files ?: 0,
            bytes = resultText.toByteArray().size,
        )
        McpRegistry.record(context, record)
        record
    }

    fun parseTools(result: JSONObject): List<McpToolInfo> {
        val array = result.optJSONArray("tools") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { McpToolInfo.fromJson(it) }
        }
    }

    fun parseResources(result: JSONObject): List<McpResourceInfo> {
        val array = result.optJSONArray("resources") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let {
                McpResourceInfo(
                    uri = it.optString("uri"),
                    name = it.optString("name"),
                    mimeType = it.optString("mimeType"),
                    description = it.optString("description"),
                )
            }
        }
    }

    fun parsePrompts(result: JSONObject): List<McpPromptInfo> {
        val array = result.optJSONArray("prompts") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let {
                McpPromptInfo(
                    name = it.optString("name"),
                    description = it.optString("description"),
                    argumentsJson = it.optJSONArray("arguments")?.toString() ?: "[]",
                )
            }
        }
    }
}

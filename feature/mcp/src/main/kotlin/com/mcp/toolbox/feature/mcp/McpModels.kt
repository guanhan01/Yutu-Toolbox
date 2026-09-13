package com.mcp.toolbox.feature.mcp

import org.json.JSONArray
import org.json.JSONObject

/** MCP 传输方式。stdio 需要 root / Shizuku / Termux 桥接。 */
enum class McpTransport(val label: String) {
    HTTP("HTTP (streamable)"),
    SSE("SSE (legacy)"),
    STDIO("stdio (需桥接)"),
}

/** 一个 MCP Server 连接配置。 */
data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: McpTransport = McpTransport.HTTP,
    val url: String = "",
    val command: String = "",
    val headers: Map<String, String> = emptyMap(),
    val token: String = "",
    val timeoutMs: Int = 8000,
    val autoConnect: Boolean = false,
    val builtIn: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("transport", transport.name)
        put("url", url)
        put("command", command)
        put("token", token)
        put("timeoutMs", timeoutMs)
        put("autoConnect", autoConnect)
        put("builtIn", builtIn)
        put("headers", JSONObject().also { obj -> headers.forEach { (k, v) -> obj.put(k, v) } })
    }

    companion object {
        fun fromJson(obj: JSONObject): McpServerConfig = McpServerConfig(
            id = obj.optString("id"),
            name = obj.optString("name"),
            transport = runCatching { McpTransport.valueOf(obj.optString("transport")) }
                .getOrDefault(McpTransport.HTTP),
            url = obj.optString("url"),
            command = obj.optString("command"),
            token = obj.optString("token"),
            timeoutMs = obj.optInt("timeoutMs", 8000),
            autoConnect = obj.optBoolean("autoConnect"),
            builtIn = obj.optBoolean("builtIn"),
            headers = obj.optJSONObject("headers")?.let { h ->
                h.keys().asSequence().associateWith { h.optString(it) }
            } ?: emptyMap(),
        )
    }
}

/** tools/list 返回的工具描述，inputSchema 保留原始 JSON 文本供 Schema 表单解析。 */
data class McpToolInfo(
    val name: String,
    val title: String = "",
    val description: String = "",
    val inputSchema: String = "{}",
    val readOnly: Boolean = true,
    val destructive: Boolean = false,
) {
    val displayName: String get() = title.ifBlank { name }

    val paramNames: List<String> get() = runCatching {
        val props = JSONObject(inputSchema).optJSONObject("properties") ?: return emptyList()
        props.keys().asSequence().toList()
    }.getOrDefault(emptyList())

    val required: Set<String> get() = runCatching {
        val arr = JSONObject(inputSchema).optJSONArray("required") ?: return emptySet()
        (0 until arr.length()).map { arr.getString(it) }.toSet()
    }.getOrDefault(emptySet())

    /** 危险工具：schema 标注 destructive 或名称命中高风险词 -> 调用前二次确认。 */
    val isDangerous: Boolean
        get() = destructive || DANGEROUS_WORDS.any { name.lowercase().contains(it) }

    companion object {
        val DANGEROUS_WORDS = listOf("delete", "remove", "uninstall", "exec", "shell", "write", "kill", "freeze")

        fun fromJson(obj: JSONObject): McpToolInfo = McpToolInfo(
            name = obj.optString("name"),
            title = obj.optString("title"),
            description = obj.optString("description"),
            inputSchema = obj.optJSONObject("inputSchema")?.toString() ?: "{}",
            readOnly = obj.optJSONObject("annotations")?.optBoolean("readOnlyHint", true) ?: true,
            destructive = obj.optJSONObject("annotations")?.optBoolean("destructiveHint", false) ?: false,
        )
    }
}

data class McpResourceInfo(
    val uri: String,
    val name: String = "",
    val mimeType: String = "",
    val description: String = "",
)

data class McpPromptInfo(
    val name: String,
    val description: String = "",
    val argumentsJson: String = "[]",
)

enum class McpConnectionState(val label: String) {
    DISCONNECTED("未连接"),
    CONNECTING("连接中"),
    READY("就绪"),
    ERROR("失败"),
}

/** 连接后的 Server 状态快照。 */
data class McpServerState(
    val serverId: String,
    val state: McpConnectionState = McpConnectionState.DISCONNECTED,
    val serverName: String = "",
    val serverVersion: String = "",
    val protocolVersion: String = "",
    val instructions: String = "",
    val latencyMs: Long = 0,
    val toolCount: Int = 0,
    val resourceCount: Int = 0,
    val promptCount: Int = 0,
    val message: String = "",
    val at: Long = 0,
)

/** 一次 tools/call 的记录，同时用于调用日志与产物索引。 */
data class McpCallRecord(
    val id: String,
    val serverId: String,
    val serverName: String,
    val toolName: String,
    val argumentsJson: String,
    val ok: Boolean,
    val isError: Boolean,
    val startedAt: Long,
    val elapsedMs: Long,
    val resultText: String,
    val error: String? = null,
    val artifactDir: String? = null,
    val artifactFiles: Int = 0,
    val bytes: Int = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("serverId", serverId)
        put("server", serverName)
        put("tool", toolName)
        put("arguments", runCatching { JSONObject(argumentsJson) }.getOrDefault(JSONObject()))
        put("ok", ok)
        put("isError", isError)
        put("startedAt", startedAt)
        put("elapsedMs", elapsedMs)
        put("bytes", bytes)
        put("error", error ?: "")
        put("artifactDir", artifactDir ?: "")
        put("result", resultText)
    }

    companion object {
        fun fromJson(obj: JSONObject): McpCallRecord = McpCallRecord(
            id = obj.optString("id"),
            serverId = obj.optString("serverId"),
            serverName = obj.optString("server"),
            toolName = obj.optString("tool"),
            argumentsJson = obj.optJSONObject("arguments")?.toString() ?: "{}",
            ok = obj.optBoolean("ok"),
            isError = obj.optBoolean("isError"),
            startedAt = obj.optLong("startedAt"),
            elapsedMs = obj.optLong("elapsedMs"),
            resultText = obj.optString("result"),
            error = obj.optString("error").ifBlank { null },
            artifactDir = obj.optString("artifactDir").ifBlank { null },
            bytes = obj.optInt("bytes"),
        )
    }
}

/** JSON-RPC 2.0 请求 id 生成器。 */
object RpcId {
    private var counter = 0
    @Synchronized
    fun next(): Int = ++counter
}

/** 把 MCP tools/call 的 content 数组拼成可读文本。 */
fun mcpContentToText(result: JSONObject): String {
    val content = result.optJSONArray("content")
    if (content != null) {
        val parts = (0 until content.length()).mapNotNull { index ->
            val item = content.optJSONObject(index) ?: return@mapNotNull null
            when (item.optString("type")) {
                "text" -> item.optString("text")
                "image" -> "[image ${item.optString("mimeType")}, ${item.optString("data").length} base64 chars]"
                "resource" -> item.optJSONObject("resource")?.toString(2) ?: ""
                else -> item.toString(2)
            }
        }
        if (parts.isNotEmpty()) return parts.joinToString("\n")
    }
    val structured = result.optJSONObject("structuredContent")
    if (structured != null) return structured.toString(2)
    return result.toString(2)
}

fun JSONArray.toStringList(): List<String> = (0 until length()).map { optString(it) }

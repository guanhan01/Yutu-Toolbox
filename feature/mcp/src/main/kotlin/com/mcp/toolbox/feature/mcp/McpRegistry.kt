package com.mcp.toolbox.feature.mcp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray

/**
 * MCP 连接与调用的进程内状态中心 + 持久化。
 * Server 列表、工具缓存、连接状态、调用日志落 SharedPreferences，重启后仍可查看。
 */
object McpRegistry {

    private const val PREFS = "mcp-registry"
    private const val KEY_SERVERS = "servers"
    private const val KEY_LOG = "call-log"

    const val BUILT_IN_ID = "builtin-local"

    val servers = MutableStateFlow<List<McpServerConfig>>(emptyList())
    val states = MutableStateFlow<Map<String, McpServerState>>(emptyMap())
    val tools = MutableStateFlow<Map<String, List<McpToolInfo>>>(emptyMap())
    val resources = MutableStateFlow<Map<String, List<McpResourceInfo>>>(emptyMap())
    val prompts = MutableStateFlow<Map<String, List<McpPromptInfo>>>(emptyMap())
    val calls = MutableStateFlow<List<McpCallRecord>>(emptyList())

    fun state(serverId: String): McpServerState =
        states.value[serverId] ?: McpServerState(serverId = serverId)

    fun toolList(serverId: String): List<McpToolInfo> = tools.value[serverId].orEmpty()

    fun allTools(): List<Pair<McpServerConfig, McpToolInfo>> =
        servers.value.flatMap { server -> toolList(server.id).map { server to it } }

    fun load(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (servers.value.isEmpty()) {
            val raw = prefs.getString(KEY_SERVERS, null)
            servers.value = runCatching {
                val array = JSONArray(raw ?: "[]")
                (0 until array.length()).map { McpServerConfig.fromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        if (calls.value.isEmpty()) {
            val raw = prefs.getString(KEY_LOG, null)
            calls.value = runCatching {
                val array = JSONArray(raw ?: "[]")
                (0 until array.length()).map { McpCallRecord.fromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        ensureBuiltIn()
    }

    /** 内置 Server 的客户端配置始终存在，方便本机端到端自测。 */
    fun ensureBuiltIn() {
        if (servers.value.any { it.builtIn }) return
        val cfg = BuiltInMcpServer.config.value
        servers.value = listOf(
            McpServerConfig(
                id = BUILT_IN_ID,
                name = "本机内置 Server",
                transport = McpTransport.HTTP,
                url = "http://127.0.0.1:${cfg.port}/mcp",
                token = cfg.token,
                timeoutMs = 6000,
                builtIn = true,
            ),
        ) + servers.value
    }

    fun upsert(context: Context, config: McpServerConfig) {
        val list = servers.value.toMutableList()
        val index = list.indexOfFirst { it.id == config.id }
        if (index >= 0) list[index] = config else list.add(config)
        servers.value = list
        persistServers(context)
    }

    fun remove(context: Context, id: String) {
        servers.value = servers.value.filterNot { it.id == id }
        states.value = states.value - id
        tools.value = tools.value - id
        resources.value = resources.value - id
        prompts.value = prompts.value - id
        persistServers(context)
    }

    /** 内置 Server 的端口 / token 变化后同步客户端配置。 */
    fun syncBuiltIn(context: Context) {
        val cfg = BuiltInMcpServer.config.value
        val current = servers.value.firstOrNull { it.builtIn } ?: return
        upsert(context, current.copy(url = builtInUrlFor(current), token = cfg.token))
    }

    /** 内置 Server 端口 / token 变化时同步客户端地址；SSE 配置下保留 /sse 端点。 */
    private fun builtInUrlFor(current: McpServerConfig): String {
        val http = "http://127.0.0.1:${BuiltInMcpServer.config.value.port}/mcp"
        return if (current.transport == McpTransport.SSE) http.replace("/mcp", "/sse") else http
    }

    fun setState(state: McpServerState) {
        states.value = states.value + (state.serverId to state)
    }

    fun setCatalog(
        serverId: String,
        tools: List<McpToolInfo>,
        resources: List<McpResourceInfo>,
        prompts: List<McpPromptInfo>,
    ) {
        this.tools.value = this.tools.value + (serverId to tools)
        this.resources.value = this.resources.value + (serverId to resources)
        this.prompts.value = this.prompts.value + (serverId to prompts)
    }

    fun record(context: Context, record: McpCallRecord) {
        calls.value = (listOf(record) + calls.value).take(200)
        persistLog(context)
    }

    fun clearLog(context: Context) {
        calls.value = emptyList()
        persistLog(context)
    }

    fun persistServers(context: Context) {
        val array = JSONArray()
        servers.value.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SERVERS, array.toString()).apply()
    }

    private fun persistLog(context: Context) {
        val array = JSONArray()
        calls.value.take(100).forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LOG, array.toString()).apply()
    }
}

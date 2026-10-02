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

    /** 旧版内置 Server 的客户端条目 id，仅用于读取时清理历史配置。 */
    private const val LEGACY_BUILT_IN_ID = "builtin-local"

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
                (0 until array.length())
                    .map { array.getJSONObject(it) }
                    // 旧版本把「本机内置 Server」写进了配置。Server 已移除，
                    // 留着会变成一个永远连不上的死条目，读取时按旧标记直接丢弃。
                    .filterNot { it.optBoolean("builtIn") || it.optString("id") == LEGACY_BUILT_IN_ID }
                    .map { McpServerConfig.fromJson(it) }
            }.getOrDefault(emptyList())
        }
        if (calls.value.isEmpty()) {
            val raw = prefs.getString(KEY_LOG, null)
            calls.value = runCatching {
                val array = JSONArray(raw ?: "[]")
                (0 until array.length()).map { McpCallRecord.fromJson(array.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
    }

    fun upsert(context: Context, config: McpServerConfig) {
        val list = servers.value.toMutableList()
        val index = list.indexOfFirst { it.id == config.id }
        if (index >= 0) list[index] = config else list.add(config)
        servers.value = list
        persistServers(context)
    }

    fun remove(context: Context, id: String) {
        McpAiAccess.forget(context, id)
        servers.value = servers.value.filterNot { it.id == id }
        states.value = states.value - id
        tools.value = tools.value - id
        resources.value = resources.value - id
        prompts.value = prompts.value - id
        persistServers(context)
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

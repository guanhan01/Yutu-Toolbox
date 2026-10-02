package com.mcp.toolbox.feature.mcp

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 接入 AI 对话的外部 MCP Server 白名单。
 *
 * 外部 Server 是人手动添加并连接的，能力不可预知：对方可能暴露 `delete_file`、
 * `exec` 这类破坏性工具，也可能一次返回几十个工具把上下文塞满。所以**默认一个都不接**，
 * 由用户在 MCP 页面逐个把可信的 Server 显式打开。
 *
 * 这是「暴露给模型」的开关，与「是否已连接」是两件事：连接只代表对话页能用它的工具，
 * 不代表模型能看到；连接成功也不会自动勾选。
 */
object McpAiAccess {

    private const val PREFS = "mcp-ai-access"
    private const val KEY_IDS = "serverIds"

    private val _enabledIds = MutableStateFlow<Set<String>>(emptySet())
    val enabledIds: StateFlow<Set<String>> = _enabledIds.asStateFlow()

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_IDS, emptySet())
            .orEmpty()
        _enabledIds.value = raw.toSet()
        loaded = true
    }

    fun isEnabled(context: Context, id: String): Boolean {
        load(context)
        return id in _enabledIds.value
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        load(context)
        _enabledIds.value = if (enabled) _enabledIds.value + id else _enabledIds.value - id
        persist(context)
    }

    /** Server 被删除时清掉它的勾选，避免残留 id 在重连同名 Server 时误开。 */
    fun forget(context: Context, id: String) {
        load(context)
        if (id !in _enabledIds.value) return
        _enabledIds.value = _enabledIds.value - id
        persist(context)
    }

    private fun persist(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_IDS, _enabledIds.value).apply()
    }
}

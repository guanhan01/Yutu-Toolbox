package com.mcp.toolbox.feature.mcp

import android.content.Context
import org.json.JSONObject

/**
 * 外部 MCP 工具在 AI 对话里的出口。
 *
 * 内置工具（[BuiltInToolSet]，103 个）由模型名直接映射到本地 handler；外部 Server 的工具
 * 则要经过一次网络往返。两者对外必须长得一样，否则调用侧（`AiToolBridge`）得同时处理
 * 两套命名与两套执行路径。
 *
 * 命名约定：`ext_<serverId>_<toolName>`。
 *
 * 选这个前缀是因为内置工具名只用 `[a-z0-9._]`，把点换成下划线后可能出现 `a_b`，
 * 所以入站解析**必须先问内置注册表**（`AiToolBridge.toToolName`）再落到这里，
 * 顺序反了会把 `file_write` 误判成外部工具。前缀里的 serverId 让同名工具天然隔离：
 * 两个 Server 各自暴露 `search` 也不会撞车。
 */
object McpExternalTools {

    /** 函数名前缀，同时用作「这是外部工具」的判据。 */
    const val PREFIX = "ext_"

    /** 单个 Server 最多注入的工具数：防止一个巨型 Server 吃掉整个上下文窗口。 */
    const val MAX_TOOLS_PER_SERVER = 40

    data class Entry(
        val server: McpServerConfig,
        val tool: McpToolInfo,
    ) {
        val functionName: String = buildFunctionName(server.id, tool.name)
    }

    /**
     * 函数名：`ext_<server>_<tool>_<hash>`。
     *
     * 末尾的哈希不是装饰。`sanitize` 把非法字符统一换成 `_`，于是
     * `server="a_b", tool="c"` 与 `server="a", tool="b_c"` 会拼出同一个名字，
     * 其中一个工具就永远调不到（[find] 只会命中先出现的那条）。
     * 加上由 `serverId + NUL + toolName` 派生的短哈希后，不同工具必然不同名，
     * 且同一工具每轮算出的名字稳定不变（`String.hashCode` 有确定定义）。
     */
    fun buildFunctionName(serverId: String, toolName: String): String =
        PREFIX + sanitize(serverId) + "_" + sanitize(toolName) + "_" + shortHash(serverId, toolName)

    /**
     * 可暴露给模型的工具。
     *
     * 只取用户显式打开、且当前处于就绪状态的 Server——没连上的 Server 即使勾了也注入不了，
     * 注入进去只会让模型调用到必然失败的工具。
     */
    fun available(context: Context): List<Entry> {
        McpAiAccess.load(context)
        val enabledServers = McpAiAccess.enabledIds.value
        if (enabledServers.isEmpty()) return emptyList()
        return McpRegistry.servers.value
            .filter { it.id in enabledServers && McpRegistry.state(it.id).state == McpConnectionState.READY }
            .flatMap { server ->
                McpRegistry.toolList(server.id)
                    .take(MAX_TOOLS_PER_SERVER)
                    .map { Entry(server, it) }
            }
    }

    /** 按函数名找工具；找不到返回 null（可能是 Server 已断开或已被取消勾选）。 */
    fun find(context: Context, functionName: String): Entry? =
        available(context).firstOrNull { it.functionName == functionName }

    /** 调一次外部工具，返回给模型的文本结果。异常转成可读文本，不中断整轮对话。 */
    suspend fun invoke(context: Context, entry: Entry, argumentsJson: String?): String {
        val args = runCatching {
            JSONObject(argumentsJson?.takeIf { it.isNotBlank() } ?: "{}")
        }.getOrElse { return "错误：参数不是合法 JSON（${it.message}）" }
        return runCatching {
            val record = McpClient.callTool(context, entry.server, entry.tool, args)
            if (record.resultText.isBlank()) {
                if (record.ok) "（工具未返回内容）" else "错误：" + (record.error?.ifBlank { "调用失败" } ?: "调用失败")
            } else {
                record.resultText
            }
        }.getOrElse { "错误：${it.message ?: it::class.simpleName}" }
    }

    /**
     * 工具名清洗：只留 `[A-Za-z0-9_]`。
     *
     * 该名字要进函数名，而 OpenAI 只接受 `[A-Za-z0-9_-]`；外部工具名却可能带点、斜杠、
     * 空格甚至中文。非法字符一律换成下划线（不用删除，避免 `a.b` 与 `ab` 撞名）。
     */
    /** 由 serverId 与工具名派生的 4 位短哈希，仅用于避免拼接歧义。 */
    private fun shortHash(serverId: String, toolName: String): String {
        val h = (serverId + '\u0000' + toolName).hashCode()
        return (h.toUInt() and 0xFFFFu).toString(16).padStart(4, '0')
    }

    private fun sanitize(raw: String): String = buildString {
        raw.forEach { ch ->
            append(if (ch.isLetterOrDigit() && ch.code < 128) ch else '_')
        }
    }.ifBlank { "x" }
}

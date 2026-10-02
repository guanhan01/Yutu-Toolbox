package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.feature.mcp.BuiltInToolSet
import com.mcp.toolbox.feature.mcp.McpExternalTools
import org.json.JSONArray
import org.json.JSONObject

/**
 * 把内置 MCP 工具集桥接成 OpenAI 的 function calling 形式。
 *
 * 工具的 `schema` 本身就是 JSON Schema，可直接作为 `parameters`；
 * 唯一需要处理的是函数名——OpenAI 只接受 `[A-Za-z0-9_-]`，而 MCP 工具名带点号
 * （如 `device.info`），因此出站时把点换成下划线，入站再换回来。
 */
object AiToolBridge {

    private const val SEPARATOR = "_"

    /**
     * 当前会话 id。
     *
     * 计划与记忆工具需要知道自己被哪个会话调用，而 ToolDef 的 handler 签名里
     * 没有会话概念。ChatRunner 同一时刻只跑一个请求（isBusy 拦截），所以在
     * 请求开始前写一次、结束时清掉即可，不会有并发覆盖。
     */
    @Volatile
    var sessionId: String? = null

    /** 出站：MCP 工具名 -> 函数名。 */
    fun toFunctionName(toolName: String): String = toolName.replace('.', '_')

    /**
     * 入站：函数名 -> MCP 工具名。
     *
     * 不能简单地把下划线换回点：`linux.file_list` 出站变成 `linux_file_list`，
     * 盲目替换会得到 `linux.file.list`，而这个工具根本不存在——AI 调用它就会
     * 收到「没有名为 … 的工具」。这里改为按注册表反查，映射始终可逆。
     */
    fun toToolName(functionName: String, context: Context? = null): String {
        if (context != null) {
            BuiltInToolSet.all(context)
                .firstOrNull { toFunctionName(it.name) == functionName }
                ?.let { return it.name }
        }
        return functionName.replace(SEPARATOR, ".")
    }

    /**
     * 一条 provider 中立的工具描述。
     *
     * 三家服务商的 tools 结构不同（OpenAI 用 `function.parameters`、Anthropic 用
     * `input_schema`、Gemini 用 `functionDeclarations[].parameters` 且 schema 要清洗），
     * 但「有哪些工具、各自叫什么、入参 JSON Schema 是什么」是同一份数据。
     * 统一从这里取，避免某个后端漏掉外部工具。
     */
    data class ToolSpec(val name: String, val description: String, val schema: JSONObject)

    /**
     * 模型当前可用的全部工具：内置 + 用户已接入的外部 MCP 工具。
     *
     * 外部工具只在用户显式开启且 Server 当前连通时才出现
     * （见 [McpExternalTools.available]），这里不做额外过滤。
     *
     * 名称已经把点换成下划线（OpenAI 的约束），三家共用同一套名字。
     */
    fun toolSpecs(context: Context): List<ToolSpec> {
        val builtIn = BuiltInToolSet.all(context).map {
            ToolSpec(toFunctionName(it.name), it.description, it.schema)
        }
        val external = McpExternalTools.available(context).mapNotNull { entry ->
            runCatching {
                val schema = JSONObject(entry.tool.inputSchema)
                if (!schema.has("type")) schema.put("type", "object")
                val description = buildString {
                    append(entry.tool.description)
                    append("\n\n（来自外部 MCP Server「")
                    append(entry.server.name)
                    append("」的工具 ")
                    append(entry.tool.name)
                    append("）")
                }
                ToolSpec(entry.functionName, description, schema)
            }.getOrNull()
        }
        return builtIn + external
    }

    /** 供 OpenAI 兼容协议使用的 tools 数组。 */
    fun toolsPayload(context: Context): JSONArray {
        val arr = JSONArray()
        toolSpecs(context).forEach { spec ->
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", spec.name)
                            .put("description", spec.description)
                            .put("parameters", spec.schema),
                    ),
            )
        }
        return arr
    }

    /** 按函数名取工具定义（界面展示标题时用，与 invoke 的解析保持一致）。 */
    fun defFor(context: Context, functionName: String) =
        BuiltInToolSet.all(context).firstOrNull { toFunctionName(it.name) == functionName }

    /** 工具总数（内置 + 已接入的外部），供界面展示。 */
    fun toolCount(context: Context): Int = toolSpecs(context).size

    /**
     * 执行一次工具调用，返回给模型的文本结果。
     *
     * 任何异常都转成可读文本回填，让模型自己决定下一步，而不是中断整轮对话。
     *
     * 解析顺序是**先内置、后外部**，不能颠倒：内置工具名里的点会在出站时换成下划线
     * （`file.write` -> `file_write`），反过来解析时如果把 `file_write` 当成外部工具，
     * 就会去找一个不存在的 Server。外部工具统一带 `ext_` 前缀，排在后面判断是安全的。
     *
     * 是挂起函数，因为外部工具要走一次网络往返（[McpExternalTools.invoke]）。
     */
    suspend fun invoke(context: Context, functionName: String, argumentsJson: String?): String {
        val toolName = toToolName(functionName, context)
        val def = BuiltInToolSet.all(context).firstOrNull { it.name == toolName }
        if (def != null) {
            val args = runCatching {
                JSONObject(argumentsJson?.takeIf { it.isNotBlank() } ?: "{}")
            }.getOrElse { return "错误：参数不是合法 JSON（${it.message}）" }
            return runCatching { def.handler(context, args).text }
                .getOrElse { "错误：${it.message ?: it::class.simpleName}" }
        }

        if (functionName.startsWith(McpExternalTools.PREFIX)) {
            val entry = McpExternalTools.find(context, functionName)
                ?: return "错误：外部工具 $functionName 当前不可用（Server 未连接，或已在 MCP 页面关闭接入）"
            return McpExternalTools.invoke(context, entry, argumentsJson)
        }

        return "错误：没有名为 $toolName 的工具"
    }
}

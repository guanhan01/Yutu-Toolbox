package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.core.common.PrivilegeManager
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
    fun toolSpecs(context: Context, decision: AiToolScope.Decision? = null): List<ToolSpec> {
        val builtIn = BuiltInToolSet.all(context).map { def ->
            val group = def.name.substringBefore('.')
            if (decision == null || decision.isExpanded(group)) {
                ToolSpec(toFunctionName(def.name), def.description, def.schema)
            } else {
                // 精简版：名字与一句话描述照旧（**绝不隐藏工具**），只把
                // 参数 schema 换成占位，并把参数名摘要写进描述 —— 多数工具
                // 凭摘要就能直接调用，省掉一次「先查参数」的往返。
                val params = AiToolScope.summarizeParams(def.schema)
                val suffix = if (params.isEmpty()) "" else "【参数：$params】"
                ToolSpec(
                    name = toFunctionName(def.name),
                    description = def.description + (if (suffix.isEmpty()) "" else " " + suffix),
                    schema = AiToolScope.placeholderSchema(),
                )
            }
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
        // 合成一个「展开工具」：AI 看到精简工具却想要完整参数时调它。
        // 它是自救手段，因此**永远完整下发**。
        return builtIn + external + listOf(describeSpec())
    }

    /**
     * 参数查询工具的规格：给入函数名数组，返回它们的完整 JSON Schema。
     *
     * 没有参数可传的极简工具（如 `device.info`）不需要它，所以它本身不参与精简。
     */
    private fun describeSpec(): ToolSpec = ToolSpec(
        name = toFunctionName(AiToolScope.TOOL_DESCRIBE),
        description = "查看指定工具的完整参数说明。当你看到某个工具的说明里有" +
            "「【参数：…】」但需要确切的类型/枚举/默认值时调用它。" +
            "传入该工具的函数名（可多个）。",
        schema = org.json.JSONObject()
            .put("type", "object")
            .put(
                "properties",
                org.json.JSONObject().put(
                    "names",
                    org.json.JSONObject()
                        .put("type", "array")
                        .put("description", "要查看的工具函数名，可多个")
                        .put("items", org.json.JSONObject().put("type", "string")),
                ),
            )
            .put("required", org.json.JSONArray().put("names"))
            .put("additionalProperties", false),
    )

    /**
     * 无障碍服务是否已连接。用于判断 `ui.*` 组是否值得下发完整 schema。
     *
     * 读的是项目既有的无障碍服务状态，不新增探测逻辑。
     */
    val accessibilityReady: Boolean
        get() = com.mcp.toolbox.core.common.ToolboxAccessibilityService.connected

    /**
     * 是否具备任一高权限后端（root / Shizuku）。
     * 没有时 `capture.*` 这类需要提权的工具展开也白搭。
     */
    fun privilegeReady(context: Context): Boolean =
        PrivilegeManager.status(context).usable

    /** 供 OpenAI 兼容协议使用的 tools 数组。 */
    fun toolsPayload(context: Context, decision: AiToolScope.Decision? = null): JSONArray {
        val arr = JSONArray()
        toolSpecs(context, decision).forEach { spec ->
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

    /**
     * 参数查询工具的实现：按函数名回传完整参数 schema。
     *
     * 返回的是**可直接照抄的结构**（参数名、类型、是否必填、枚举、默认值），
     * 而不是原始 JSON Schema —— 后者对模型来说噪音太大（$ref、anyOf 之类）。
     */
    private fun describeTools(context: Context, argumentsJson: String?): String {
        val args = runCatching {
            org.json.JSONObject(argumentsJson?.takeIf { it.isNotBlank() } ?: "{}")
        }.getOrElse { return "错误：参数不是合法 JSON（${it.message}）" }

        val names = args.optJSONArray("names")
            ?: return "错误：缺少 names 参数（字符串数组）"
        if (names.length() == 0) return "错误：names 不能为空"

        val all = BuiltInToolSet.all(context)
        return buildString {
            for (i in 0 until names.length()) {
                val fn = names.optString(i)
                val def = all.firstOrNull { toFunctionName(it.name) == fn }
                if (def == null) {
                    append("· ").append(fn).append("：没有这个工具\n")
                    continue
                }
                append("· ").append(fn).append(" —— ").append(def.title).append('\n')
                append("  说明：").append(def.description).append('\n')
                append("  参数：").append(renderSchema(def.schema)).append('\n')
            }
        }.trimEnd()
    }

    /** 把 JSON Schema 渲染成模型好读的逐行参数表。 */
    private fun renderSchema(schema: org.json.JSONObject): String {
        val props = schema.optJSONObject("properties")
        if (props == null || props.length() == 0) return "无"
        val required = schema.optJSONArray("required")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() }
            ?: emptySet()
        return (0 until props.length()).joinToString("；") { i ->
            val key = props.names().optString(i)
            val p = props.optJSONObject(key) ?: org.json.JSONObject()
            buildString {
                append(key)
                if (key in required) append("(必填)") else append("(可选)")
                append(":").append(p.optString("type", "any"))
                p.optString("description").takeIf { it.isNotBlank() }
                    ?.let { append(" ").append(it) }
                p.optJSONArray("enum")?.let { e ->
                    append(" 取值=")
                    append((0 until e.length()).joinToString("|") { e.optString(it) })
                }
                if (p.has("default")) append(" 默认=").append(p.opt("default"))
            }
        }
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

        // 「展开工具」是合成出来的，不在 BuiltInToolSet 里，先单独处理。
        if (toolName == AiToolScope.TOOL_DESCRIBE) {
            return describeTools(context, argumentsJson)
        }
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

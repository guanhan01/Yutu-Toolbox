package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.feature.mcp.BuiltInToolSet
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

    /** 出站：MCP 工具名 -> 函数名。 */
    fun toFunctionName(toolName: String): String = toolName.replace('.', '_')

    /** 入站：函数名 -> MCP 工具名。 */
    fun toToolName(functionName: String): String =
        functionName.replace(SEPARATOR, ".")

    /** 供请求使用的 tools 数组。 */
    fun toolsPayload(context: Context): JSONArray {
        val arr = JSONArray()
        BuiltInToolSet.all(context).forEach { def ->
            arr.put(
                JSONObject()
                    .put("type", "function")
                    .put(
                        "function",
                        JSONObject()
                            .put("name", toFunctionName(def.name))
                            .put("description", def.description)
                            .put("parameters", def.schema),
                    ),
            )
        }
        return arr
    }

    /** 工具总数，供界面展示。 */
    fun toolCount(context: Context): Int = BuiltInToolSet.all(context).size

    /**
     * 执行一次工具调用，返回给模型的文本结果。
     *
     * 任何异常都转成可读文本回填，让模型自己决定下一步，而不是中断整轮对话。
     */
    fun invoke(context: Context, functionName: String, argumentsJson: String?): String {
        val toolName = toToolName(functionName)
        val def = BuiltInToolSet.all(context).firstOrNull { it.name == toolName }
            ?: return "错误：没有名为 $toolName 的工具"

        val args = runCatching {
            JSONObject(argumentsJson?.takeIf { it.isNotBlank() } ?: "{}")
        }.getOrElse { return "错误：参数不是合法 JSON（${it.message}）" }

        return runCatching { def.handler(context, args).text }
            .getOrElse { "错误：${it.message ?: it::class.simpleName}" }
    }
}

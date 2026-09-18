package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.feature.home.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini 原生 API 适配。
 *
 * 与 OpenAI 的主要差异：
 * - 鉴权用查询参数 `?key=`，模型名写在路径里
 * - 消息叫 `contents`，角色是 `user` / `model`（不是 assistant）
 * - 系统提示是 `systemInstruction`
 * - 多模态是 `parts` 里的 `inline_data`
 * - 工具是 `tools[].functionDeclarations[]`
 * - 流式走 `streamGenerateContent?alt=sse`，文本在 `candidates[0].content.parts[].text`
 */
internal object GeminiBackend {

    private const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"

    fun baseUrl(config: AiConfig): String =
        (config.baseUrl.ifBlank { DEFAULT_BASE_URL }).trimEnd('/').removeSuffix("/openai")

    fun endpoint(config: AiConfig): String =
        "${baseUrl(config)}/models/${config.model}:streamGenerateContent?alt=sse"

    fun nonStreamEndpoint(config: AiConfig): String =
        "${baseUrl(config)}/models/${config.model}:generateContent"

    fun headers(config: AiConfig): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "x-goog-api-key" to config.apiKey,
    )

    /** 追加 key 查询参数（部分网关只认查询参数）。 */
    fun withKey(url: String, config: AiConfig): String =
        if (config.apiKey.isBlank()) url
        else url + if (url.contains('?')) "&key=${config.apiKey}" else "?key=${config.apiKey}"

    fun buildPayload(
        context: Context,
        config: AiConfig,
        history: List<ChatMessage>,
        systemPrompt: String?,
        tools: Boolean,
    ): String {
        val contents = JSONArray()
        history.forEach { m ->
            when (m.role) {
                ChatMessage.Role.SYSTEM -> Unit // 系统提示单独走 systemInstruction
                else -> contents.put(
                    JSONObject()
                        .put("role", if (m.role == ChatMessage.Role.USER) "user" else "model")
                        .put("parts", buildParts(context, m)),
                )
            }
        }

        val root = JSONObject().put("contents", contents)
        systemPrompt?.takeIf { it.isNotBlank() }?.let {
            root.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", it))),
            )
        }
        root.put(
            "generationConfig",
            JSONObject()
                .put("temperature", config.temperature.toDouble()),
        )

        if (tools) {
            root.put(
                "tools",
                JSONArray().put(
                    JSONObject().put(
                        "functionDeclarations",
                        JSONArray().also { arr ->
                            com.mcp.toolbox.feature.mcp.BuiltInToolSet.all(context).forEach { def ->
                                arr.put(
                                    JSONObject()
                                        .put("name", AiToolBridge.toFunctionName(def.name))
                                        .put("description", def.description)
                                        .put("parameters", sanitizeSchema(def.schema)),
                                )
                            }
                        },
                    ),
                ),
            )
        }
        return root.toString()
    }

    /**
     * Gemini 对 JSON Schema 的支持较窄：不接受 `additionalProperties`，
     * 且 `default` 有时会报错，这里做一层剔除。
     */
    private fun sanitizeSchema(schema: JSONObject): JSONObject {
        val copy = JSONObject(schema.toString())
        copy.remove("additionalProperties")
        copy.remove("default")
        copy.optJSONObject("properties")?.let { props ->
            props.keys().forEach { key ->
                val child = props.optJSONObject(key) ?: return@forEach
                child.remove("additionalProperties")
                child.remove("default")
            }
        }
        return copy
    }

    private fun buildParts(context: Context, message: ChatMessage): JSONArray {
        val parts = JSONArray()
        if (message.content.isNotBlank()) parts.put(JSONObject().put("text", message.content))
        message.imageUris.forEach { uri ->
            MediaReader.read(context, uri)?.let { (mime, base64) ->
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", mime).put("data", base64),
                    ),
                )
            }
        }
        return parts
    }

    /**
     * 解析一行 SSE。
     *
     * 文本：`candidates[0].content.parts[].text`
     * 工具调用：`candidates[0].content.parts[].functionCall = { name, args }`
     */
    fun parseEvent(data: String, accumulator: ToolCallAccumulator): String {
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return ""
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: return ""

        val text = StringBuilder()
        (0 until parts.length()).forEach { i ->
            val part = parts.optJSONObject(i) ?: return@forEach
            part.optString("text").takeIf { it.isNotEmpty() }?.let { text.append(it) }
            part.optJSONObject("functionCall")?.let { call ->
                val name = call.optString("name")
                val args = call.opt("args")?.toString() ?: "{}"
                val index = accumulator.size()
                accumulator.start(index, "call_$index", name)
                accumulator.append(index, args)
            }
        }
        return text.toString()
    }

    /** 工具结果回填：`role: "function"` + `functionResponse`。 */
    fun toolResponseContent(
        results: List<Triple<String, String, String>>,
    ): JSONObject {
        val parts = JSONArray()
        results.forEach { (_, name, content) ->
            parts.put(
                JSONObject().put(
                    "functionResponse",
                    JSONObject()
                        .put("name", name)
                        .put(
                            "response",
                            JSONObject().put("result", content),
                        ),
                ),
            )
        }
        return JSONObject().put("role", "function").put("parts", parts)
    }

    /** 把模型的 functionCall 回填进历史。 */
    fun modelFunctionCallContent(
        text: String,
        calls: List<ToolCallAccumulator.Call>,
    ): JSONObject {
        val parts = JSONArray()
        if (text.isNotBlank()) parts.put(JSONObject().put("text", text))
        calls.forEach { c ->
            parts.put(
                JSONObject().put(
                    "functionCall",
                    JSONObject()
                        .put("name", c.name)
                        .put(
                            "args",
                            runCatching { JSONObject(c.arguments.toString().ifBlank { "{}" }) }
                                .getOrDefault(JSONObject()),
                        ),
                ),
            )
        }
        return JSONObject().put("role", "model").put("parts", parts)
    }

    /** 从非流式响应里取文本。 */
    fun parseNonStream(body: String): String {
        val root = JSONObject(body)
        val parts = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?: error("响应缺少 candidates：${body.take(200)}")
        val text = StringBuilder()
        (0 until parts.length()).forEach { i ->
            text.append(parts.optJSONObject(i)?.optString("text").orEmpty())
        }
        return text.toString().ifBlank { error("回复内容为空") }
    }
}

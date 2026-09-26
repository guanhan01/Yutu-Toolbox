package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.feature.home.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Messages API 适配。
 *
 * 与 OpenAI 的主要差异：
 * - 认证走 `x-api-key` 而不是 Bearer；必须带 `anthropic-version`
 * - `system` 是顶层字段，不在 messages 里
 * - 工具 schema 字段叫 `input_schema`，不是 `parameters`
 * - 流式事件是 `content_block_delta`，文本在 `delta.text`
 * - 工具调用是 `tool_use` block，结果以 `tool_result` 回填
 */
internal object AnthropicBackend {

    private const val VERSION = "2023-06-01"
    private const val MAX_TOKENS = 4096
    private const val DEFAULT_BASE_URL = "https://api.anthropic.com"

    fun baseUrl(config: AiConfig): String {
        val raw = config.baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
        // 去掉可能残留的 OpenAI 兼容后缀
        return raw.removeSuffix("/v1").removeSuffix("/v1/")
    }

    fun endpoint(config: AiConfig): String = baseUrl(config) + "/v1/messages"

    fun headers(config: AiConfig): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "x-api-key" to config.apiKey,
        "anthropic-version" to VERSION,
        "Accept" to "text/event-stream",
    )

    fun buildPayload(
        context: Context,
        config: AiConfig,
        history: List<ChatMessage>,
        systemPrompt: String?,
        stream: Boolean,
        tools: Boolean,
    ): String {
        val messages = JSONArray()
        history.forEach { m ->
            when (m.role) {
                ChatMessage.Role.SYSTEM -> Unit // Anthropic 的 messages 不接受 system
                else -> messages.put(
                    JSONObject()
                        .put(
                            "role",
                            if (m.role == ChatMessage.Role.USER) "user" else "assistant",
                        )
                        .put("content", buildContent(context, m)),
                )
            }
        }

        val thinking = thinkingBudget(config)
        val root = JSONObject()
            .put("model", config.model)
            .put("max_tokens", MAX_TOKENS)
            .put("messages", messages)
            .put("stream", stream)
            // 开扩展思考时 temperature 只能为 1，否则接口直接报错
            .put("temperature", if (thinking != null) 1.0 else config.temperature.toDouble())
        thinking?.let {
            root.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", it))
        }

        systemPrompt?.takeIf { it.isNotBlank() }?.let { root.put("system", it) }

        if (tools) {
            root.put(
                "tools",
                JSONArray().also { arr ->
                    com.mcp.toolbox.feature.mcp.BuiltInToolSet.all(context).forEach { def ->
                        arr.put(
                            JSONObject()
                                .put("name", AiToolBridge.toFunctionName(def.name))
                                .put("description", def.description)
                                .put("input_schema", def.schema),
                        )
                    }
                },
            )
        }
        return root.toString()
    }

    /** 纯文本时用字符串，含图片时用 block 数组。 */
    private fun buildContent(context: Context, message: ChatMessage): Any {
        val images = message.imageUris.mapNotNull { MediaReader.read(context, it) }
        if (images.isEmpty()) return message.content
        return JSONArray().also { arr ->
            if (message.content.isNotBlank()) {
                arr.put(JSONObject().put("type", "text").put("text", message.content))
            }
            images.forEach { (mime, base64) ->
                arr.put(
                    JSONObject()
                        .put("type", "image")
                        .put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", mime)
                                .put("data", base64),
                        ),
                )
            }
        }
    }

    /**
     * 解析一行 SSE，返回本次的正文增量与推理增量。
     *
     * Anthropic 的事件形如：
     * - `content_block_start`：`content_block.type == tool_use` 带 id / name；
     *   `type == thinking` 表示扩展思考块开始
     * - `content_block_delta`：`text_delta` 取 `delta.text`；
     *   `thinking_delta` 取 `delta.thinking`；`input_json_delta` 追加参数
     */
    fun parseEvent(data: String, accumulator: ToolCallAccumulator): StreamDelta? {
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return null
        return when (root.optString("type")) {
            "content_block_start" -> {
                val block = root.optJSONObject("content_block")
                if (block?.optString("type") == "tool_use") {
                    accumulator.start(
                        index = root.optInt("index", 0),
                        id = block.optString("id"),
                        name = block.optString("name"),
                    )
                }
                StreamDelta()
            }

            // message_start 带输入用量；message_delta 带累计输出用量
            "message_start" -> {
                val usage = root.optJSONObject("message")?.optJSONObject("usage")
                StreamDelta(promptTokens = usage?.optInt("input_tokens", 0) ?: 0)
            }

            "message_delta" -> {
                val usage = root.optJSONObject("usage")
                StreamDelta(completionTokens = usage?.optInt("output_tokens", 0) ?: 0)
            }

            "content_block_delta" -> {
                val delta = root.optJSONObject("delta")
                when (delta?.optString("type")) {
                    "text_delta" -> StreamDelta(text = delta.optString("text"))
                    "thinking_delta" -> StreamDelta(reasoning = delta.optString("thinking"))
                    "signature_delta" -> StreamDelta(signature = delta.optString("signature"))
                    "input_json_delta" -> {
                        accumulator.append(
                            index = root.optInt("index", 0),
                            partial = delta.optString("partial_json"),
                        )
                        StreamDelta()
                    }

                    else -> StreamDelta()
                }
            }

            else -> null
        }
    }

    /**
     * 是否启用扩展思考。
     *
     * Off / Default 不下发：Default 的语义是「按服务商默认」，Anthropic 的默认
     * 是不思考，显式下发反而会挤占 max_tokens。
     */
    fun thinkingBudget(config: AiConfig): Int? = when (config.reasoning) {
        ReasoningEffort.OFF, ReasoningEffort.DEFAULT -> null
        ReasoningEffort.MINIMAL, ReasoningEffort.LOW -> 1024
        ReasoningEffort.MEDIUM -> 2048
        else -> 3072
    }

    /** 把 tool_use 结果转成下一轮的 user 消息块。 */
    fun toolResultsBlock(results: List<Triple<String, String, String>>): JSONObject {
        val parts = JSONArray()
        results.forEach { (toolUseId, _, content) ->
            parts.put(
                JSONObject()
                    .put("type", "tool_result")
                    .put("tool_use_id", toolUseId)
                    .put("content", content),
            )
        }
        return JSONObject().put("role", "user").put("content", parts)
    }

    /**
     * 把 assistant 的 tool_use 回填进历史。
     *
     * 开启扩展思考后，Anthropic 要求把当轮的 thinking 块（含签名）原样回传，
     * 且必须排在 tool_use 之前，否则下一轮直接返回 400。
     */
    fun assistantToolUseBlock(
        text: String,
        calls: List<ToolCallAccumulator.Call>,
        thinking: String = "",
        signature: String = "",
    ): JSONObject {
        val parts = JSONArray()
        if (thinking.isNotBlank()) {
            parts.put(
                JSONObject()
                    .put("type", "thinking")
                    .put("thinking", thinking)
                    .put("signature", signature),
            )
        }
        if (text.isNotBlank()) parts.put(JSONObject().put("type", "text").put("text", text))
        calls.forEach { c ->
            parts.put(
                JSONObject()
                    .put("type", "tool_use")
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("input", runCatching { JSONObject(c.arguments.toString().ifBlank { "{}" }) }
                        .getOrDefault(JSONObject())),
            )
        }
        return JSONObject().put("role", "assistant").put("content", parts)
    }
}

/** 按 index 聚合流式工具调用片段。 */
internal class ToolCallAccumulator {
    class Call(val id: String, val name: String, val arguments: StringBuilder = StringBuilder())

    private val order = mutableListOf<Int>()
    private val slots = mutableMapOf<Int, Call>()

    fun start(index: Int, id: String, name: String) {
        if (!slots.containsKey(index)) order.add(index)
        slots[index] = Call(id, name, slots[index]?.arguments ?: StringBuilder())
    }

    fun append(index: Int, partial: String) {
        val existing = slots[index] ?: return
        slots[index] = Call(existing.id, existing.name, existing.arguments).also {
            it.arguments.append(partial)
        }
    }

    fun calls(): List<Call> = order.mapNotNull { slots[it] }

    fun size(): Int = slots.size

    fun isEmpty(): Boolean = slots.isEmpty()

    fun clear() {
        order.clear()
        slots.clear()
    }
}

/** 读图并转成 (mime, base64)，供原生协议复用。 */
internal object MediaReader {
    fun read(context: Context, uri: String): Pair<String, String>? = runCatching {
        val parsed = android.net.Uri.parse(uri)
        val raw = context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
            ?: return null
        val mime = context.contentResolver.getType(parsed) ?: "image/jpeg"
        val bytes = if (raw.size <= 2 * 1024 * 1024) {
            raw
        } else {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                ?: return null
            val longest = maxOf(bitmap.width, bitmap.height)
            val scaled = if (longest > 1568) {
                val ratio = 1568f / longest
                android.graphics.Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * ratio).toInt().coerceAtLeast(1),
                    (bitmap.height * ratio).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            java.io.ByteArrayOutputStream().use { out ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                out.toByteArray()
            }
        }
        mime to android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }.getOrNull()
}

/** 一次流式增量：正文、推理、思考签名与用量分开。 */
internal data class StreamDelta(
    val text: String = "",
    val reasoning: String = "",
    val signature: String = "",
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
)

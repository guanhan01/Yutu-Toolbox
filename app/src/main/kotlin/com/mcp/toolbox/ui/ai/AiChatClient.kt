package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.feature.home.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容协议的对话客户端。
 *
 * - [complete]：一次性返回
 * - [completeStream]：SSE 流式，并可选开启内置 MCP 工具的 function calling 循环
 */
object AiChatClient {

    private const val TIMEOUT_MS = 60_000
    private const val MAX_TOOL_ROUNDS = 8
    private const val MAX_TOOL_RESULT_CHARS = 16_000
    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    private const val MAX_IMAGE_EDGE = 1568

    /** 一次性返回完整回复。 */
    suspend fun complete(
        context: Context,
        config: AiConfig,
        history: List<ChatMessage>,
        systemPrompt: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            validate(config)
            val payload = buildPayload(
                config = config,
                messages = buildMessages(context, history, systemPrompt),
                stream = false,
                tools = null,
            )
            parseContent(post(config, payload))
        }
    }

    /**
     * 流式返回。开启 [enableTools] 时按 function calling 循环：
     * 模型请求调用 -> 本地执行 -> 结果回填 -> 继续请求，直到模型给出最终文本。
     * 取消协程即可中断整个循环。
     */
    suspend fun completeStream(
        context: Context,
        config: AiConfig,
        history: List<ChatMessage>,
        systemPrompt: String? = null,
        enableTools: Boolean = true,
        onToolCall: (name: String, arguments: String) -> Unit = { _, _ -> },
        onToolResult: (name: String, result: String) -> Unit = { _, _ -> },
        onDelta: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            validate(config)
            val messages = buildMessages(context, history, systemPrompt)

            repeat(MAX_TOOL_ROUNDS) { round ->
                val payload = buildPayload(
                    config = config,
                    messages = messages,
                    stream = true,
                    tools = if (enableTools) AiToolBridge.toolsPayload(context) else null,
                )

                val roundText = StringBuilder()
                val calls = mutableListOf<ToolCallAcc>()
                val conn = open(config, payload)
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        error("HTTP $code：${extractError(err).take(200)}")
                    }
                    readSse(conn.inputStream.bufferedReader()) { content, callDelta ->
                        if (content.isNotEmpty()) {
                            roundText.append(content)
                            onDelta(content)
                        }
                        callDelta?.let { mergeToolCall(calls, it) }
                    }
                } finally {
                    conn.disconnect()
                }

                // 没有工具调用即为最终答复
                if (calls.none { it.name.isNotBlank() }) {
                    return@runCatching roundText.toString().ifBlank { error("回复内容为空") }
                }

                // 回填 assistant 的 tool_calls
                messages.put(
                    JSONObject()
                        .put("role", "assistant")
                        .put("content", roundText.toString())
                        .put(
                            "tool_calls",
                            JSONArray().also { arr ->
                                calls.filter { it.name.isNotBlank() }.forEach { c ->
                                    arr.put(
                                        JSONObject()
                                            .put("id", c.id)
                                            .put("type", "function")
                                            .put(
                                                "function",
                                                JSONObject()
                                                    .put("name", c.name)
                                                    .put("arguments", c.arguments.toString().ifBlank { "{}" }),
                                            ),
                                    )
                                }
                            },
                        ),
                )

                // 逐条执行并回填结果
                calls.filter { it.name.isNotBlank() }.forEach { c ->
                    onToolCall(c.name, c.arguments.toString())
                    val result = AiToolBridge.invoke(context, c.name, c.arguments.toString())
                    onToolResult(c.name, result)
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", c.id)
                            .put("content", result.take(MAX_TOOL_RESULT_CHARS)),
                    )
                }

                if (round == MAX_TOOL_ROUNDS - 1) {
                    return@runCatching roundText.toString().ifBlank { error("工具调用轮数已达上限") }
                }
            }
            error("工具调用轮数已达上限")
        }
    }

    /** 拉取该服务商可用模型列表（OpenAI 兼容的 GET /models）。 */
    suspend fun listModels(config: AiConfig): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.baseUrl.isNotBlank()) { "未配置接口地址" }
            val conn = (URL(config.baseUrl.trimEnd('/') + "/models").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
                if (config.apiKey.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                }
            }
            try {
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) error("HTTP $code：${extractError(body).take(200)}")
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                (0 until data.length()).mapNotNull { i ->
                    data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                }.distinct().sorted()
            } finally {
                conn.disconnect()
            }
        }
    }

    // ---------- 请求 ----------

    private fun validate(config: AiConfig) {
        require(config.baseUrl.isNotBlank()) { "未配置接口地址" }
        require(config.model.isNotBlank()) { "未配置模型" }
        require(config.apiKey.isNotBlank()) { "未配置 API Key" }
    }

    private fun open(config: AiConfig, payload: String): HttpURLConnection =
        (URL(config.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            setRequestProperty("Accept", "text/event-stream")
            outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        }

    private fun post(config: AiConfig, payload: String): String {
        val conn = open(config, payload)
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code：${extractError(body).take(200)}")
            body
        } finally {
            conn.disconnect()
        }
    }

    /** 逐行读 SSE，产出文本增量与（可能分片的）tool_calls 增量。 */
    private fun readSse(
        reader: BufferedReader,
        onChunk: (content: String, toolCall: JSONObject?) -> Unit,
    ) {
        reader.useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (!line.startsWith("data:")) return@forEach
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") return@forEach
                runCatching {
                    val choices = JSONObject(data).optJSONArray("choices") ?: return@runCatching
                    val d = choices.optJSONObject(0)?.optJSONObject("delta") ?: return@runCatching
                    d.optJSONArray("tool_calls")?.let { calls ->
                        (0 until calls.length()).forEach { i ->
                            calls.optJSONObject(i)?.let { onChunk("", it) }
                        }
                    }
                    val content = d.optString("content")
                    if (content.isNotEmpty()) onChunk(content, null)
                }
            }
        }
    }

    /** 一次工具调用的累积片段（流式下 name/arguments 会分多片到达）。 */
    private class ToolCallAcc(
        val id: String = "",
        val name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    )

    private fun mergeToolCall(list: MutableList<ToolCallAcc>, delta: JSONObject) {
        val index = delta.optInt("index", 0)
        while (list.size <= index) list.add(ToolCallAcc())

        val slot = list[index]
        val id = delta.optString("id").takeIf { it.isNotBlank() } ?: slot.id
        val fn = delta.optJSONObject("function")
        val name = fn?.optString("name")?.takeIf { it.isNotBlank() } ?: slot.name
        list[index] = ToolCallAcc(id, name, slot.arguments)
        fn?.optString("arguments")?.takeIf { it.isNotEmpty() }?.let { list[index].arguments.append(it) }
    }

    // ---------- 载荷 ----------

    private fun buildMessages(
        context: Context,
        history: List<ChatMessage>,
        systemPrompt: String?,
    ): JSONArray {
        val messages = JSONArray()
        systemPrompt?.takeIf { it.isNotBlank() }?.let {
            messages.put(JSONObject().put("role", "system").put("content", it))
        }
        history.forEach { m ->
            val role = when (m.role) {
                ChatMessage.Role.USER -> "user"
                ChatMessage.Role.ASSISTANT -> "assistant"
                ChatMessage.Role.SYSTEM -> "system"
            }
            val images = m.imageUris.mapNotNull { encodeImage(context, it) }
            if (images.isEmpty()) {
                messages.put(JSONObject().put("role", role).put("content", m.content))
            } else {
                val parts = JSONArray()
                if (m.content.isNotBlank()) {
                    parts.put(JSONObject().put("type", "text").put("text", m.content))
                }
                images.forEach { dataUri ->
                    parts.put(
                        JSONObject()
                            .put("type", "image_url")
                            .put("image_url", JSONObject().put("url", dataUri)),
                    )
                }
                messages.put(JSONObject().put("role", role).put("content", parts))
            }
        }
        return messages
    }

    private fun buildPayload(
        config: AiConfig,
        messages: JSONArray,
        stream: Boolean,
        tools: JSONArray?,
    ): String {
        val root = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", config.temperature.toDouble())
            .put("stream", stream)
        // Default 不下发该字段；其余按 OpenAI 兼容的 reasoning_effort 传递
        config.reasoning.apiValue?.let { root.put("reasoning_effort", it) }
        tools?.takeIf { it.length() > 0 }?.let { root.put("tools", it) }
        return root.toString()
    }

    private fun parseContent(body: String): String {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices") ?: error("响应缺少 choices：${body.take(200)}")
        val first = choices.optJSONObject(0) ?: error("choices 为空")
        val message = first.optJSONObject("message")
        return message?.optString("content")?.takeIf { it.isNotBlank() }
            ?: first.optString("text").takeIf { it.isNotBlank() }
            ?: error("回复内容为空")
    }

    /** 图片读成 data URI；过大时缩放压成 JPEG，仍超限则放弃该图。 */
    private fun encodeImage(context: Context, uri: String): String? = runCatching {
        val parsed = android.net.Uri.parse(uri)
        val raw = context.contentResolver.openInputStream(parsed)?.use { it.readBytes() }
            ?: return null
        val mime = context.contentResolver.getType(parsed) ?: "image/jpeg"
        val bytes = if (raw.size <= MAX_IMAGE_BYTES) {
            raw
        } else {
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size)
                ?: return null
            val scaled = scaleDown(bitmap, MAX_IMAGE_EDGE)
            java.io.ByteArrayOutputStream().use { out ->
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
                out.toByteArray()
            }
        }
        if (bytes.size > MAX_IMAGE_BYTES * 3) return null
        "data:$mime;base64," + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }.getOrNull()

    private fun scaleDown(bitmap: android.graphics.Bitmap, maxEdge: Int): android.graphics.Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val ratio = maxEdge.toFloat() / longest
        return android.graphics.Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun extractError(body: String): String = runCatching {
        val err = JSONObject(body).opt("error")
        when (err) {
            is JSONObject -> err.optString("message").ifBlank { body }
            is String -> err
            else -> body
        }
    }.getOrDefault(body)
}

package com.mcp.toolbox.ui.ai

import com.mcp.toolbox.feature.home.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * OpenAI 兼容协议的对话客户端。
 *
 * 请求 `POST {baseUrl}/chat/completions`，非流式返回首条回复文本。
 * 各家差异（Anthropic 原生协议、Gemini 原生协议）后续按需在此扩展。
 */
object AiChatClient {

    private const val TIMEOUT_MS = 60_000

    suspend fun complete(
        context: android.content.Context,
        config: AiConfig,
        history: List<ChatMessage>,
        systemPrompt: String? = null,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.baseUrl.isNotBlank()) { "未配置接口地址" }
            require(config.model.isNotBlank()) { "未配置模型" }
            require(config.apiKey.isNotBlank()) { "未配置 API Key" }

            val url = URL(config.baseUrl.trimEnd('/') + "/chat/completions")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                setRequestProperty("Accept", "application/json")
            }

            try {
                val payload = buildPayload(context, config, history, systemPrompt)
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                if (code !in 200..299) {
                    error("HTTP $code：${extractError(body).take(200)}")
                }
                parseContent(body)
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 拉取该服务商可用模型列表（OpenAI 兼容的 GET /models）。 */
    suspend fun listModels(config: AiConfig): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.baseUrl.isNotBlank()) { "未配置接口地址" }
            val url = URL(config.baseUrl.trimEnd('/') + "/models")
            val conn = (url.openConnection() as HttpURLConnection).apply {
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
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()
                if (code !in 200..299) {
                    error("HTTP $code：${extractError(body).take(200)}")
                }
                val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
                (0 until data.length()).mapNotNull { i ->
                    data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                }.distinct().sorted()
            } finally {
                conn.disconnect()
            }
        }
    }

    // ---------- 内部 ----------

    private fun buildPayload(
        context: android.content.Context,
        config: AiConfig,
        history: List<ChatMessage>,
        systemPrompt: String?,
    ): String {
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
                // 多模态：content 变成数组，文本 + 若干 image_url（data URI）
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
        return JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", config.temperature.toDouble())
            .put("stream", false)
            .toString()
    }

    private fun parseContent(body: String): String {
        val root = JSONObject(body)
        val choices = root.optJSONArray("choices")
            ?: error("响应缺少 choices：${body.take(200)}")
        val first = choices.optJSONObject(0) ?: error("choices 为空")
        val message = first.optJSONObject("message")
        val content = message?.optString("content")?.takeIf { it.isNotBlank() }
            ?: first.optString("text").takeIf { it.isNotBlank() }
            ?: error("回复内容为空")
        return content
    }

    /**
     * 把图片读成 data URI。
     *
     * 超过 [MAX_IMAGE_BYTES] 时先按最长边 1568px 缩放再压成 JPEG（质量 85），
     * 仍超限则放弃该图，避免请求体过大被服务商拒绝。
     */
    private fun encodeImage(context: android.content.Context, uri: String): String? = runCatching {
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

    private fun scaleDown(
        bitmap: android.graphics.Bitmap,
        maxEdge: Int,
    ): android.graphics.Bitmap {
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

    private const val MAX_IMAGE_BYTES = 2 * 1024 * 1024
    private const val MAX_IMAGE_EDGE = 1568

    private fun extractError(body: String): String = runCatching {
        val root = JSONObject(body)
        val err = root.opt("error")
        when (err) {
            is JSONObject -> err.optString("message").ifBlank { body }
            is String -> err
            else -> body
        }
    }.getOrDefault(body)
}

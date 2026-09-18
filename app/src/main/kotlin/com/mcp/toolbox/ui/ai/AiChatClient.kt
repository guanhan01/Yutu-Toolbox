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
                val payload = buildPayload(config, history, systemPrompt)
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
            messages.put(JSONObject().put("role", role).put("content", m.content))
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

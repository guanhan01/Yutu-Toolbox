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
        systemPrompt: String? = config.systemPrompt.takeIf { it.isNotBlank() },
        enableTools: Boolean = true,
        onToolCall: (round: Int, seq: Int, name: String, arguments: String) -> Unit =
            { _, _, _, _ -> },
        onToolResult: (round: Int, seq: Int, name: String, result: String) -> Unit =
            { _, _, _, _ -> },
        /**
         * 深度思考增量（DeepSeek 等返回的 reasoning_content）。
         *
         * 带 [round]：同一轮里思考一定先于该轮的工具调用到达，界面据此把
         * 「第 N 轮思考」排在同轮工具卡片之上、下一轮思考自然落到卡片之下。
         */
        onReasoning: (round: Int, text: String) -> Unit = { _, _ -> },
        /**
         * 带工具调用那一轮的正文旁白。
         *
         * 这类轮次里模型的正文不是最终答复，而是「我要去调用工具」的过渡语句；
         * 此前直接并进最终回复，导致回复里混入中间过程。
         */
        onNarration: (round: Int, text: String) -> Unit = { _, _ -> },
        /**
         * 本次请求的真实用量（prompt / completion，单位 token）。
         *
         * 由服务端返回，不是本地估算：OpenAI 兼容走 stream_options.include_usage，
         * Anthropic 走 message_start / message_delta，Gemini 走 usageMetadata。
         * 都是「最后一轮」的值，也就是当前上下文实际占用的量。
         */
        onUsage: (promptTokens: Int, completionTokens: Int) -> Unit = { _, _ -> },
        onDelta: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        when (config.current) {
            AiProvider.ANTHROPIC -> return@withContext anthropicStream(
                context, config, history, systemPrompt, enableTools,
                onToolCall, onToolResult, onReasoning, onNarration, onUsage, onDelta,
            )

            AiProvider.GEMINI -> return@withContext geminiStream(
                context, config, history, systemPrompt, enableTools,
                onToolCall, onToolResult, onReasoning, onNarration, onUsage, onDelta,
            )

            else -> Unit // OpenAI 兼容协议继续走下面的实现
        }
        runCatching {
            validate(config)
            val messages = buildMessages(context, history, systemPrompt)

            var lastPrompt = 0
            var lastCompletion = 0
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
                    readSse(conn.inputStream.bufferedReader()) { content, callDelta, reasoning, usage ->
                        if (content.isNotEmpty()) {
                            roundText.append(content)
                            onDelta(content)
                        }
                        if (reasoning.isNotEmpty()) onReasoning(round, reasoning)
                        callDelta?.let { mergeToolCall(calls, it) }
                        usage?.let { u ->
                            val prompt = u.optInt("prompt_tokens", 0)
                            val completion = u.optInt("completion_tokens", 0)
                            // 末轮的值才是当前上下文的真实占用，直接覆盖
                            if (prompt > 0) lastPrompt = prompt
                            if (completion > 0) lastCompletion = completion
                            if (prompt > 0 || completion > 0) onUsage(lastPrompt, lastCompletion)
                        }
                    }
                } finally {
                    conn.disconnect()
                }

                // 没有工具调用即为最终答复
                if (calls.none { it.name.isNotBlank() }) {
                    return@runCatching roundText.toString().ifBlank { error("回复内容为空") }
                }

                // 本轮带工具调用：上面的正文只是过渡旁白，交给界面归档到时间线
                if (roundText.isNotBlank()) onNarration(round, roundText.toString())

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
                calls.filter { it.name.isNotBlank() }.forEachIndexed { seq, c ->
                    onToolCall(round, seq, c.name, c.arguments.toString())
                    val result = AiToolBridge.invoke(context, c.name, c.arguments.toString())
                    onToolResult(round, seq, c.name, result)
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

    // ---------- Anthropic 原生 ----------

    private suspend fun anthropicStream(
        context: Context,
        config: AiConfig,
        history: List<ChatMessage>,
        systemPrompt: String?,
        enableTools: Boolean,
        onToolCall: (Int, Int, String, String) -> Unit,
        onToolResult: (Int, Int, String, String) -> Unit,
        onReasoning: (Int, String) -> Unit,
        onNarration: (Int, String) -> Unit,
        onUsage: (Int, Int) -> Unit,
        onDelta: (String) -> Unit,
    ): Result<String> = runCatching {
        // 把历史摊成可变列表，便于逐轮回填
        val working = JSONArray()
        val initial = JSONObject(
            AnthropicBackend.buildPayload(
                context, config, history, systemPrompt, stream = true, tools = enableTools,
            ),
        ).optJSONArray("messages")
        (0 until (initial?.length() ?: 0)).forEach { working.put(initial!!.opt(it)) }
        var lastPrompt = 0
        var lastCompletion = 0
        val system = systemPrompt?.takeIf { it.isNotBlank() }

        repeat(MAX_TOOL_ROUNDS) { round ->
            val payload = JSONObject()
                .put("model", config.model)
                .put("max_tokens", 4096)
                .put("messages", working)
                .put("stream", true)
                .put("temperature", config.temperature.toDouble())
                .also { root ->
                    system?.let { root.put("system", it) }
                    if (enableTools) {
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
                }

            val conn = openRaw(
                url = AnthropicBackend.endpoint(config),
                payload = payload.toString(),
                headers = AnthropicBackend.headers(config),
                config = config,
            )
            val text = StringBuilder()
            // 扩展思考的正文与签名要按轮累积：签名与思考块必须成对回传
            val thinking = StringBuilder()
            val signature = StringBuilder()
            val acc = ToolCallAccumulator()
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    error("HTTP $code：${extractError(err).take(200)}")
                }
                conn.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        val line = raw.trim()
                        if (!line.startsWith("data:")) return@forEach
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty() || data == "[DONE]") return@forEach
                        val event = AnthropicBackend.parseEvent(data, acc) ?: return@forEach
                        if (event.reasoning.isNotEmpty()) {
                            thinking.append(event.reasoning)
                            onReasoning(round, event.reasoning)
                        }
                        if (event.signature.isNotEmpty()) signature.append(event.signature)
                        if (event.text.isNotEmpty()) {
                            text.append(event.text)
                            onDelta(event.text)
                        }
                        // 用量分两处到达：message_start 给输入，message_delta 给输出
                        if (event.promptTokens > 0) lastPrompt = event.promptTokens
                        if (event.completionTokens > 0) lastCompletion = event.completionTokens
                        if (lastPrompt > 0 || lastCompletion > 0) {
                            onUsage(lastPrompt, lastCompletion)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            val calls = acc.calls()
            if (calls.isEmpty()) {
                return@runCatching text.toString().ifBlank { error("回复内容为空") }
            }

            if (text.isNotBlank()) onNarration(round, text.toString())
            working.put(
                AnthropicBackend.assistantToolUseBlock(
                    text = text.toString(),
                    calls = calls,
                    thinking = thinking.toString(),
                    signature = signature.toString(),
                ),
            )
            val results = calls.mapIndexed { seq, c ->
                onToolCall(round, seq, c.name, c.arguments.toString())
                val result = AiToolBridge.invoke(context, c.name, c.arguments.toString())
                    .take(MAX_TOOL_RESULT_CHARS)
                onToolResult(round, seq, c.name, result)
                Triple(c.id, c.name, result)
            }
            working.put(AnthropicBackend.toolResultsBlock(results))

            if (round == MAX_TOOL_ROUNDS - 1) {
                return@runCatching text.toString().ifBlank { error("工具调用轮数已达上限") }
            }
        }
        error("工具调用轮数已达上限")
    }

    // ---------- Gemini 原生 ----------

    private suspend fun geminiStream(
        context: Context,
        config: AiConfig,
        history: List<ChatMessage>,
        systemPrompt: String?,
        enableTools: Boolean,
        onToolCall: (Int, Int, String, String) -> Unit,
        onToolResult: (Int, Int, String, String) -> Unit,
        onReasoning: (Int, String) -> Unit,
        onNarration: (Int, String) -> Unit,
        onUsage: (Int, Int) -> Unit,
        onDelta: (String) -> Unit,
    ): Result<String> = runCatching {
        val working = JSONArray()
        val initial = JSONObject(
            GeminiBackend.buildPayload(
                context, config, history, systemPrompt, tools = enableTools,
            ),
        ).optJSONArray("contents")
        (0 until (initial?.length() ?: 0)).forEach { working.put(initial!!.opt(it)) }

        repeat(MAX_TOOL_ROUNDS) { round ->
            val payload = JSONObject()
                .put("contents", working)
                .put(
                    "generationConfig",
                    JSONObject().put("temperature", config.temperature.toDouble()),
                )
                .also { root ->
                    systemPrompt?.takeIf { it.isNotBlank() }?.let {
                        root.put(
                            "systemInstruction",
                            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", it))),
                        )
                    }
                }

            val conn = openRaw(
                url = GeminiBackend.withKey(GeminiBackend.endpoint(config), config),
                payload = payload.toString(),
                headers = GeminiBackend.headers(config),
                config = config,
            )
            val text = StringBuilder()
            val acc = ToolCallAccumulator()
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    error("HTTP $code：${extractError(err).take(200)}")
                }
                conn.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { raw ->
                        val line = raw.trim()
                        if (!line.startsWith("data:")) return@forEach
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty() || data == "[DONE]") return@forEach
                        val event = GeminiBackend.parseEvent(data, acc)
                        if (event.reasoning.isNotEmpty()) onReasoning(round, event.reasoning)
                        if (event.text.isNotEmpty()) {
                            text.append(event.text)
                            onDelta(event.text)
                        }
                        if (event.promptTokens > 0 || event.completionTokens > 0) {
                            onUsage(event.promptTokens, event.completionTokens)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }

            val calls = acc.calls()
            if (calls.isEmpty()) {
                return@runCatching text.toString().ifBlank { error("回复内容为空") }
            }

            if (text.isNotBlank()) onNarration(round, text.toString())
            working.put(GeminiBackend.modelFunctionCallContent(text.toString(), calls))
            val results = calls.mapIndexed { seq, c ->
                onToolCall(round, seq, c.name, c.arguments.toString())
                val result = AiToolBridge.invoke(context, c.name, c.arguments.toString())
                    .take(MAX_TOOL_RESULT_CHARS)
                onToolResult(round, seq, c.name, result)
                Triple(c.id, c.name, result)
            }
            working.put(GeminiBackend.toolResponseContent(results))

            if (round == MAX_TOOL_ROUNDS - 1) {
                return@runCatching text.toString().ifBlank { error("工具调用轮数已达上限") }
            }
        }
        error("工具调用轮数已达上限")
    }

    /** 通用 POST，headers 由调用方给定。 */
    private fun openRaw(
        url: String,
        payload: String,
        headers: Map<String, String>,
        config: AiConfig,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            config.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
            outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
        }

    /**
     * 指定服务商与凭据拉取模型列表。
     *
     * 供设置页「测试连接」与模型页「拉取」使用：这两处可能还没保存当前输入。
     */
    suspend fun listModelsFor(
        provider: AiProvider,
        baseUrl: String,
        apiKey: String,
    ): Result<List<RemoteModel>> {
        val probe = AiConfig(
            current = provider,
            perProvider = mapOf(
                provider to ProviderConfig(
                    provider = provider,
                    baseUrl = baseUrl,
                    apiKey = apiKey,
                ),
            ),
        )
        return listModels(probe)
    }

    /**
     * 拉取该服务商可用模型列表，**包含服务端给出的真实上下文档位**。
     *
     * 三个协议各自有独立的列表接口，字段名也不同：
     * - OpenAI 兼容 `/models`：标准字段没有窗口大小，但部分网关会额外给出
     *   `context_length` / `context_window` / `max_context_length`，有就取
     * - Anthropic `/v1/models`：`max_input_tokens` / `max_tokens`
     * - Gemini `/v1beta/models`：`inputTokenLimit` / `outputTokenLimit`
     *
     * 拿不到就留 null（不猜）：模型页会显示为待填，用户可在模型编辑里手写。
     */
    suspend fun listModels(config: AiConfig): Result<List<RemoteModel>> =
        withContext(Dispatchers.IO) {
            runCatching {
                when (config.current) {
                    AiProvider.ANTHROPIC -> listAnthropicModels(config)
                    AiProvider.GEMINI -> listGeminiModels(config)
                    else -> listOpenAiModels(config)
                }
            }
        }

    /** GET {base}/models，读 OpenAI 兼容字段。 */
    private fun listOpenAiModels(config: AiConfig): List<RemoteModel> {
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
            val seen = mutableSetOf<String>()
            return (0 until data.length()).mapNotNull { i ->
                val o = data.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                if (!seen.add(id)) return@mapNotNull null
                RemoteModel(
                    id = id,
                    displayName = o.optString("name"),
                    contextWindow = firstPositive(
                        o.optInt("context_length", 0),
                        o.optInt("context_window", 0),
                        o.optInt("max_context_length", 0),
                        o.optInt("max_input_tokens", 0),
                        // 少数网关把它放在顶层或 capabilities 里
                        o.optJSONObject("capabilities")?.optInt("context_length", 0) ?: 0,
                    ),
                    supportsReasoning = o.optBoolean("supports_reasoning", false),
                )
            }.sortedBy { it.id }
        } finally {
            conn.disconnect()
        }
    }

    /** Anthropic：GET {base}/v1/models，读 max_input_tokens。 */
    private fun listAnthropicModels(config: AiConfig): List<RemoteModel> {
        val base = AnthropicBackend.baseUrl(config)
        val conn = (URL("$base/v1/models?limit=1000").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-api-key", config.apiKey)
            AnthropicBackend.headers(config).forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code：${extractError(body).take(200)}")
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            return (0 until data.length()).mapNotNull { i ->
                val o = data.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                RemoteModel(
                    id = id,
                    displayName = o.optString("display_name"),
                    contextWindow = firstPositive(
                        o.optInt("max_input_tokens", 0),
                        o.optInt("max_tokens", 0),
                    ),
                    // 支持扩展思考的模型才会带 thinking 能力标记
                    supportsReasoning = o.optJSONObject("capabilities")
                        ?.optJSONObject("thinking") != null ||
                        o.optBoolean("supports_thinking", false),
                )
            }.sortedBy { it.id }
        } finally {
            conn.disconnect()
        }
    }

    /** Gemini：GET {base}/models?key=…，读 inputTokenLimit。 */
    private fun listGeminiModels(config: AiConfig): List<RemoteModel> {
        val url = GeminiBackend.withKey(
            GeminiBackend.baseUrl(config) + "/models?pageSize=1000",
            config,
        )
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            GeminiBackend.headers(config).forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code：${extractError(body).take(200)}")
            val data = JSONObject(body).optJSONArray("models") ?: JSONArray()
            return (0 until data.length()).mapNotNull { i ->
                val o = data.optJSONObject(i) ?: return@mapNotNull null
                // name 形如 "models/gemini-2.0-flash"
                val id = o.optString("name").substringAfterLast('/').takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                RemoteModel(
                    id = id,
                    displayName = o.optString("displayName"),
                    contextWindow = firstPositive(o.optInt("inputTokenLimit", 0)),
                    supportsReasoning = o.optBoolean("thinking", false) ||
                        id.contains("thinking", true),
                )
            }.sortedBy { it.id }
        } finally {
            conn.disconnect()
        }
    }

    /** 取第一个大于 0 的值；全为 0 时返回 null（表示服务端未提供）。 */
    private fun firstPositive(vararg values: Int): Int? =
        values.firstOrNull { it > 0 }

    /**
     * 单独查一个模型的真实上下文窗口。
     *
     * 列表接口常常不含窗口（很多 OpenAI 兼容网关只返回 id），但单模型详情接口
     * 往往有：Gemini 的 `/models/{id}` 给 `inputTokenLimit`，Anthropic 的
     * `/v1/models/{id}` 给 `max_input_tokens`。查不到就返回 null——绝不猜。
     */
    suspend fun fetchContextWindow(config: AiConfig): Result<Int?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val model = config.model
                require(model.isNotBlank()) { "未配置模型" }
                when (config.current) {
                    AiProvider.GEMINI -> {
                        val url = GeminiBackend.withKey(
                            GeminiBackend.baseUrl(config) + "/models/" + model,
                            config,
                        )
                        val body = getJson(url, GeminiBackend.headers(config))
                        JSONObject(body).optInt("inputTokenLimit", 0).takeIf { it > 0 }
                    }

                    AiProvider.ANTHROPIC -> {
                        val url = "${AnthropicBackend.baseUrl(config)}/v1/models/$model"
                        val body = getJson(url, AnthropicBackend.headers(config))
                        val o = JSONObject(body)
                        firstPositive(
                            o.optInt("max_input_tokens", 0),
                            o.optInt("max_tokens", 0),
                        )
                    }

                    else -> {
                        // OpenAI 兼容：多数网关只有 id / created / owned_by，
                        // 少数会带 context_length 之类，有就取
                        require(config.baseUrl.isNotBlank()) { "未配置接口地址" }
                        val url = config.baseUrl.trimEnd('/') + "/models/" + model
                        val headers = mutableMapOf("Accept" to "application/json")
                        if (config.apiKey.isNotBlank()) {
                            headers["Authorization"] = "Bearer ${config.apiKey}"
                        }
                        val body = getJson(url, headers)
                        val o = JSONObject(body)
                        firstPositive(
                            o.optInt("context_length", 0),
                            o.optInt("context_window", 0),
                            o.optInt("max_context_length", 0),
                            o.optInt("max_input_tokens", 0),
                            o.optJSONObject("data")?.optInt("context_length", 0) ?: 0,
                        )
                    }
                }
            }
        }

    /** GET 一个 JSON 接口，非 2xx 抛可读错误。 */
    private fun getJson(url: String, headers: Map<String, String>): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
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

    // ---------- 请求 ----------

    private fun validate(config: AiConfig) {
        require(config.enabled) { "该服务商已被停用" }
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
            // 用户自定义请求头，可覆盖上面的默认值
            config.customHeaders.forEach { (k, v) -> setRequestProperty(k, v) }
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
        onChunk: (
            content: String,
            toolCall: JSONObject?,
            reasoning: String,
            usage: JSONObject?,
        ) -> Unit,
    ) {
        reader.useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (!line.startsWith("data:")) return@forEach
                val data = line.removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") return@forEach
                runCatching {
                    val root = JSONObject(data)
                    // 末条 chunk（choices 为空）才带 usage
                    root.optJSONObject("usage")?.let { u -> onChunk("", null, "", u) }
                    val choices = root.optJSONArray("choices") ?: return@runCatching
                    val d = choices.optJSONObject(0)?.optJSONObject("delta") ?: return@runCatching
                    d.optJSONArray("tool_calls")?.let { calls ->
                        (0 until calls.length()).forEach { i ->
                            calls.optJSONObject(i)?.let { onChunk("", it, "", null) }
                        }
                    }
                    // 深度思考增量：DeepSeek 等在此字段返回推理过程。
                    // 注意 optString 对 JSON null 会返回字符串 "null"，必须用 opt 判类型。
                    val reasoning = (d.opt("reasoning_content") as? String)
                        ?.takeIf { it.isNotBlank() && it != "null" }
                        ?: (d.opt("reasoning") as? String)
                            ?.takeIf { it.isNotBlank() && it != "null" }
                        ?: ""
                    val content = (d.opt("content") as? String)
                        ?.takeIf { it != "null" }
                        .orEmpty()
                    when {
                        content.isNotEmpty() -> onChunk(content, null, reasoning, null)
                        reasoning.isNotEmpty() -> onChunk("", null, reasoning, null)
                    }
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
                // 思考过程与工具卡片不回传给模型（工具结果已在循环内回填）
                ChatMessage.Role.REASONING -> return@forEach
                ChatMessage.Role.TOOL -> return@forEach
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
        // 只有流式才需要显式索要用量：非流式响应本来就带 usage 字段
        if (stream) {
            root.put("stream_options", JSONObject().put("include_usage", true))
        }
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

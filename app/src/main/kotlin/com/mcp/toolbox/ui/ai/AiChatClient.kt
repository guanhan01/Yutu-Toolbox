package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.feature.home.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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

    /**
     * 工具调用循环的安全上限。
     *
     * 正常结束条件是「模型不再请求工具」，所以这里不是「任务能做多少」的额度，
     * 而是防跑飞的保险丝：模型陷入反复调用同一个工具的循环时，靠它兜住。
     * 定得足够宽（按每轮可并发多个工具算，实际可完成上千次调用），
     * 现实中的长任务不会撞到它。
     *
     * 之所以保留一个上限而不是真的 `while (true)`：一旦模型打转，没有任何上限
     * 就只会一直耗 token、一直发请求，用户只能自己发现并点停止。
     */
    private const val MAX_TOOL_ROUNDS = 200

    /**
     * 撞上安全上限时的收尾说明。
     *
     * 这**不是失败**：过程都已经跑完并落盘，只是模型还想继续。所以返回一段
     * 可读的说明而不是抛错——抛错会被界面渲染成「请求失败：…」，让用户以为
     * 整轮白跑了。
     */
    /**
     * 单次请求里累积的工具结果总字符上限。
     *
     * 轮数上限放宽后，真正会先把请求撑爆的是**结果总量**而不是圈数：连续读几十个
     * 大文件、拉几次大响应，上下文很快就到模型窗口之上，之后每一圈都只会收到
     * 400（context length exceeded）——用户看到的就是「一直失败」。
     *
     * 所以这里按结果总量兜一道：到顶就带说明收尾，而不是继续发注定失败的请求。
     * 40 万字符约合 10 万 token 量级，留足余量给系统提示与历史。
     */
    private const val MAX_TOOL_CONTEXT_CHARS = 400_000

    /** 工具结果总量到顶时的收尾说明。 */
    private const val CONTEXT_BUDGET_NOTICE =
        "⚠️ 本次请求累积的工具结果已经很多，继续调用会把上下文撑爆（后续请求只会返回" +
            "超长错误）。\n\n已完成的步骤都在上方，回复「继续」我会接着往下做。"

    private const val ROUND_CEILING_NOTICE =
        "⚠️ 本次工具调用已达到安全上限（200 轮），任务可能尚未完成。\n\n" +
            "已完成的步骤都在上方，回复「继续」我就接着往下做。"

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
            // Responses 的报文与 Chat Completions 完全不同，必须单独走
            if (config.activeProtocol == AiProtocol.RESPONSES) {
                return@runCatching responsesComplete(context, config, history, systemPrompt)
            }
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
        /**
         * 60s 没有收到任何数据（stall watchdog）。
         *
         * 不是「超时抛异常」而是**主动报告**：界面据此把光标停掉、提示重试，
         * 而不是让用户看着一个永远转圈的界面猜是不是卡死了。
         */
        onStall: () -> Unit = {},
        onDelta: (String) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        // 按「协议」而不是「厂商」分派：自定义供应商由用户自己选协议，
        // 选 Anthropic 就该走 anthropicStream，与它挂在哪个枚举上无关。
        when (config.activeProtocol) {
            AiProtocol.ANTHROPIC -> return@withContext anthropicStream(
                context, config, history, systemPrompt, enableTools,
                onToolCall, onToolResult, onReasoning, onNarration, onUsage, onStall, onDelta,
            )

            AiProtocol.GEMINI -> return@withContext geminiStream(
                context, config, history, systemPrompt, enableTools,
                onToolCall, onToolResult, onReasoning, onNarration, onUsage, onStall, onDelta,
            )

            AiProtocol.RESPONSES -> return@withContext responsesStream(
                context, config, history, systemPrompt, enableTools,
                onToolCall, onToolResult, onReasoning, onNarration, onUsage, onStall, onDelta,
            )

            else -> Unit // OpenAI 兼容协议继续走下面的实现
        }
        runCatching {
            validate(config)
            val messages = buildMessages(context, history, systemPrompt)

            var lastPrompt = 0
            var lastCompletion = 0
            // 本段已累积的工具结果字符数，按量兜住上下文膨胀（见 MAX_TOOL_CONTEXT_CHARS）
            var toolChars = 0
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
                    // stall watchdog：60s 收不到任何数据就主动上报，界面据此停止等待。
                    // 用 lastEventAt 而不是「读超时」：HttpURLConnection 的 readTimeout
                    // 只在真正阻塞时触发，而 SSE 的心跳包会让它永远不触发。
                    val lastEventAt = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
                    val watchdog = startStallWatchdog(conn, lastEventAt, onStall)
                    try {
                        // 正文一律**逐 token** 上报，保证逐字流式的手感。
                        //
                        // 「这一轮是过渡旁白还是最终答复」要到收尾才知道，所以判定的
                        // 责任不在这一层：界面收到 `onNarration` 时会把**同一段**正文
                        // 原地改标成旁白段（同 id、同位置），于是工具卡自然排在它下面，
                        // 而最终答复留在正文区。见 `AgentTimeline` 的 Narration 分支。
                        //
                        // （曾试过「整轮先缓冲、收尾再派发」，那会牺牲逐字流式，已回滚。）
                        readSse(conn.inputStream.bufferedReader()) { content, callDelta, reasoning, usage ->
                        lastEventAt.set(System.currentTimeMillis())
                        if (content.isNotEmpty()) {
                            roundText.append(content)
                            onDelta(content)
                        }
                        if (reasoning.isNotEmpty()) onReasoning(round, reasoning)
                        callDelta?.let { mergeToolCall(calls, it) }
                        // 注意：这里不能用 `usage?.let { ... }`。
                        // 最外层是 repeat 的 lambda，`return@let` 会跳过后面所有的
                        // `if (prompt > 0)` 判断，导致用量永远不上报（实测踩到）。
                        if (usage != null) {
                            val prompt = usage.optInt("prompt_tokens", 0)
                            val completion = usage.optInt("completion_tokens", 0)
                            // 末轮的值才是当前上下文的真实占用，直接覆盖
                            if (prompt > 0) lastPrompt = prompt
                            if (completion > 0) lastCompletion = completion
                            if (prompt > 0 || completion > 0) onUsage(lastPrompt, lastCompletion)
                        }
                        }
                    } finally {
                        watchdog.cancel()
                    }
                } finally {
                    conn.disconnect()
                }

                // 没有工具调用即为最终答复（正文已在流式过程中上报过，这里只需返回）
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
                    toolChars += result.length
                    onToolResult(round, seq, c.name, result)
                    messages.put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", c.id)
                            .put("content", result.take(MAX_TOOL_RESULT_CHARS)),
                    )
                }

                // 结果总量先于轮数到顶：继续只会发注定 400 的请求，及时收尾
                if (toolChars >= MAX_TOOL_CONTEXT_CHARS) {
                    return@runCatching CONTEXT_BUDGET_NOTICE
                }
                if (round == MAX_TOOL_ROUNDS - 1) {
                    // 安全上限：过程已全部执行完。不能把本轮的正文当答复返回
                    // ——它已经作为旁白进过时间线，再返回就会重复出现一次。
                    return@runCatching ROUND_CEILING_NOTICE
                }
            }
            ROUND_CEILING_NOTICE
        }
    }

    // ---------- OpenAI Responses ----------

    /**
     * Responses API 的流式对话 + 函数调用循环。
     *
     * 与 [anthropicStream] 同构：每轮把「模型请求的工具调用」和「本地执行结果」
     * 追加回 input，再发下一轮。区别在于回填字段：Responses 用 `call_id` 关联
     * 结果，且 assistant 那侧不需要显式声明 content 数组。
     */
    private suspend fun responsesStream(
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
        onStall: () -> Unit = {},
        onDelta: (String) -> Unit,
    ): Result<String> = runCatching {
        validate(config)
        // input 摊成可变数组，便于逐轮回填。
        // 注意不能用 JSONArray(jsonArray)：org.json 的这个构造函数只接受原始数组，
        // 传 JSONArray 会抛 "Not a primitive array"。
        val working = JSONArray()
        val initial = JSONObject(
            ResponsesBackend.buildPayload(
                context, config, history, systemPrompt, stream = true, enableTools = enableTools,
            ),
        ).optJSONArray("input")
        (0 until (initial?.length() ?: 0)).forEach { working.put(initial!!.get(it)) }

        var lastPrompt = 0
        var lastCompletion = 0
        val system = systemPrompt?.takeIf { it.isNotBlank() }

        // 本段已累积的工具结果字符数，按量兜住上下文膨胀（见 MAX_TOOL_CONTEXT_CHARS）
        var toolChars = 0
        repeat(MAX_TOOL_ROUNDS) { round ->
            val payload = JSONObject()
                .put("model", config.model)
                .put("input", working)
                .put("stream", true)
                .put("temperature", config.temperature.toDouble())
                .put("store", false)
                .also { root ->
                    system?.let { root.put("instructions", it) }
                    if (enableTools) {
                        root.put(
                            "tools",
                            JSONArray().also { arr ->
                                AiToolBridge.toolSpecs(context).forEach { spec ->
                                    arr.put(
                                        JSONObject()
                                            .put("type", "function")
                                            .put("name", spec.name)
                                            .put("description", spec.description)
                                            .put("parameters", spec.schema),
                                    )
                                }
                            },
                        )
                    }
                }

            val conn = openRaw(
                url = ResponsesBackend.endpoint(config),
                payload = payload.toString(),
                headers = ResponsesBackend.headers(config),
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
                        // Responses 的 SSE 主要靠 data:；event: 行内容与 data.type 重复
                        if (!line.startsWith("data:")) return@forEach
                        val data = line.removePrefix("data:").trim()
                        if (data.isEmpty() || data == "[DONE]") return@forEach
                        val event = ResponsesBackend.parseEvent(data, acc) ?: return@forEach
                        if (event.reasoning.isNotEmpty()) onReasoning(round, event.reasoning)
                        if (event.text.isNotEmpty()) {
                            text.append(event.text)
                            onDelta(event.text)
                        }
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

            val calls = acc.calls().filter { it.name.isNotBlank() }
            if (calls.isEmpty()) {
                return@runCatching text.toString().ifBlank { error("回复内容为空") }
            }

            if (text.isNotBlank()) onNarration(round, text.toString())
            ResponsesBackend.functionCallInputs(calls).forEach { working.put(it) }

            val results = calls.mapIndexed { seq, c ->
                onToolCall(round, seq, c.name, c.arguments.toString())
                val result = AiToolBridge.invoke(context, c.name, c.arguments.toString())
                    .take(MAX_TOOL_RESULT_CHARS)
                toolChars += result.length
                onToolResult(round, seq, c.name, result)
                Triple(c.id, c.name, result)
            }
            ResponsesBackend.functionCallOutputs(results).forEach { working.put(it) }

            // 结果总量先于轮数到顶：继续只会发注定 400 的请求，及时收尾
            if (toolChars >= MAX_TOOL_CONTEXT_CHARS) {
                return@runCatching CONTEXT_BUDGET_NOTICE
            }
            if (round == MAX_TOOL_ROUNDS - 1) {
                // 同理：本轮正文已经作为旁白归档，这里只回可读的收尾说明
                return@runCatching ROUND_CEILING_NOTICE
            }
        }
        ROUND_CEILING_NOTICE
    }

    /** 非流式 Responses 请求，供摘要压缩这类一次性调用使用。 */
    private fun responsesComplete(
        context: Context,
        config: AiConfig,
        history: List<ChatMessage>,
        systemPrompt: String?,
    ): String {
        val payload = ResponsesBackend.buildPayload(
            context, config, history, systemPrompt, stream = false, enableTools = false,
        )
        val conn = openRaw(
            url = ResponsesBackend.endpoint(config),
            payload = payload,
            headers = ResponsesBackend.headers(config),
            config = config,
        )
        val body = try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code：${extractError(text).take(200)}")
            text
        } finally {
            conn.disconnect()
        }
        return ResponsesBackend.parseNonStream(body)
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
        onStall: () -> Unit = {},
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

        // 本段已累积的工具结果字符数，按量兜住上下文膨胀（见 MAX_TOOL_CONTEXT_CHARS）
        var toolChars = 0
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
                toolChars += result.length
                onToolResult(round, seq, c.name, result)
                Triple(c.id, c.name, result)
            }
            working.put(AnthropicBackend.toolResultsBlock(results))

            // 结果总量先于轮数到顶：继续只会发注定 400 的请求，及时收尾
            if (toolChars >= MAX_TOOL_CONTEXT_CHARS) {
                return@runCatching CONTEXT_BUDGET_NOTICE
            }
            if (round == MAX_TOOL_ROUNDS - 1) {
                // 同理：本轮正文已经作为旁白归档，这里只回可读的收尾说明
                return@runCatching ROUND_CEILING_NOTICE
            }
        }
        ROUND_CEILING_NOTICE
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
        onStall: () -> Unit = {},
        onDelta: (String) -> Unit,
    ): Result<String> = runCatching {
        val working = JSONArray()
        val initial = JSONObject(
            GeminiBackend.buildPayload(
                context, config, history, systemPrompt, tools = enableTools,
            ),
        ).optJSONArray("contents")
        (0 until (initial?.length() ?: 0)).forEach { working.put(initial!!.opt(it)) }

        // 本段已累积的工具结果字符数，按量兜住上下文膨胀（见 MAX_TOOL_CONTEXT_CHARS）
        var toolChars = 0
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
                toolChars += result.length
                onToolResult(round, seq, c.name, result)
                Triple(c.id, c.name, result)
            }
            working.put(GeminiBackend.toolResponseContent(results))

            // 结果总量先于轮数到顶：继续只会发注定 400 的请求，及时收尾
            if (toolChars >= MAX_TOOL_CONTEXT_CHARS) {
                return@runCatching CONTEXT_BUDGET_NOTICE
            }
            if (round == MAX_TOOL_ROUNDS - 1) {
                // 同理：本轮正文已经作为旁白归档，这里只回可读的收尾说明
                return@runCatching ROUND_CEILING_NOTICE
            }
        }
        ROUND_CEILING_NOTICE
    }

    /** 通用 POST，headers 由调用方给定。 */
    private fun openRaw(
        url: String,
        payload: String,
        headers: Map<String, String>,
        config: AiConfig,
    ): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            // 登记为「正在读取的连接」，取消时会被断开（见 abortInflight）
            inflight = this
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
        /** 自定义供应商传入它选的协议；内置留空即按厂商推导。 */
        protocol: AiProtocol = provider.protocol(),
    ): Result<List<RemoteModel>> {
        // 立一条临时自定义供应商来承载 protocol：activeProtocol 会优先读它。
        // 用固定 id 且不进列表，纯粹作为这次探测的载体。
        val probe = AiConfig(
            current = provider,
            currentCustomId = "probe",
            customProviders = listOf(
                CustomProvider(
                    id = "probe",
                    name = "probe",
                    protocol = protocol,
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
                when (config.activeProtocol) {
                    AiProtocol.ANTHROPIC -> listAnthropicModels(config)
                    AiProtocol.GEMINI -> listGeminiModels(config)
                    // Responses 与 Chat Completions 共用 GET /models：模型目录是同一个
                    AiProtocol.OPENAI, AiProtocol.RESPONSES -> listOpenAiModels(config)
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
                when (config.activeProtocol) {
                    AiProtocol.GEMINI -> {
                        val url = GeminiBackend.withKey(
                            GeminiBackend.baseUrl(config) + "/models/" + model,
                            config,
                        )
                        val body = getJson(url, GeminiBackend.headers(config))
                        JSONObject(body).optInt("inputTokenLimit", 0).takeIf { it > 0 }
                    }

                    AiProtocol.ANTHROPIC -> {
                        val url = "${AnthropicBackend.baseUrl(config)}/v1/models/$model"
                        val body = getJson(url, AnthropicBackend.headers(config))
                        val o = JSONObject(body)
                        firstPositive(
                            o.optInt("max_input_tokens", 0),
                            o.optInt("max_tokens", 0),
                        )
                    }

                    else -> {
                        // OpenAI 兼容（含 Responses，模型目录同源）：多数网关只有
                        // id / created / owned_by，少数会带 context_length 之类，有就取
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


    /** stall 检测间隔。 */
    private const val STALL_CHECK_INTERVAL_MS = 5_000L

    /** 判定 stall 的静默阈值（Spec 第 8.3 节：60s 无数据自动中止）。 */
    private const val STALL_TIMEOUT_MS = 60_000L

    /**
     * 当前正在读取的连接。
     *
     * ## 为什么必须显式关闭它
     *
     * `job.cancel()` 只能打断**挂起点**，而 `bufferedReader().readLine()` 是阻塞 IO——
     * 只要服务端还在推数据它就一直读下去，协程的取消标志根本没机会被检查。
     * 结果是：界面停了、上游还在烧 token（实测「点停止后它继续做」）。
     *
     * 用一个引用就够：同一时刻只可能有**一个**请求在跑（`ChatRunner.isBusy()` 保证）。
     * 不用集合，也就不需要在每个分支的 finally 里注销，少一圈容易写漏的配对。
     */
    @Volatile
    private var inflight: HttpURLConnection? = null

    /**
     * 掐断在跑的上游连接。由「点停止」调用。
     *
     * 关掉之后阻塞的读立刻抛 IOException，请求随之结束——这才是「真的停」。
     */
    fun abortInflight() {
        runCatching { inflight?.disconnect() }
        inflight = null
    }

    /** watchdog 只读网络、不碰界面，用 IO 调度即可。 */
    private val scopeForWatchdog =
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)


    /**
     * stall watchdog（Spec 第 8.3 节：60s 无数据自动中止）。
     *
     * 为什么不能只靠 `HttpURLConnection.readTimeout`：SSE 会周期性发心跳包，
     * 只要有心跳，读就永远不会超时阻塞——连接可能已经「活着但不产出任何内容」。
     * 所以这里跟踪的是**距上一次有效事件的时间**。
     */
    private fun startStallWatchdog(
        conn: HttpURLConnection,
        lastEventAt: java.util.concurrent.atomic.AtomicLong,
        onStall: () -> Unit,
    ): kotlinx.coroutines.Job = scopeForWatchdog.launch {
        while (true) {
            kotlinx.coroutines.delay(STALL_CHECK_INTERVAL_MS)
            if (System.currentTimeMillis() - lastEventAt.get() >= STALL_TIMEOUT_MS) {
                runCatching { conn.disconnect() }
                onStall()
                return@launch
            }
        }
    }

    private fun open(config: AiConfig, payload: String): HttpURLConnection =
        (URL(config.baseUrl.trimEnd('/') + "/chat/completions").openConnection() as HttpURLConnection).apply {
            // 登记为「正在读取的连接」，取消时会被断开（见 abortInflight）
            inflight = this
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

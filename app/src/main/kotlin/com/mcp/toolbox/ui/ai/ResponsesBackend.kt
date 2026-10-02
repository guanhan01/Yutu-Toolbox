package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.feature.home.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI Responses API 适配（`POST /responses`）。
 *
 * 与 Chat Completions（[AiProtocol.OPENAI]）的差异不只是路径，报文结构也换了：
 * - 入参叫 `input`，不是 `messages`
 * - system 提示走顶层 `instructions`，不作为一条消息
 * - 工具是**扁平**的 `{type, name, parameters}`，不像 Chat Completions 那样
 *   再套一层 `function`
 * - 输出不是 `choices[].message`，而是 `output[]` 里一组 item
 * - 流式事件名完全不同（`response.output_text.delta` 等），且**事件既在 `event:`
 *   行也在 `data.type`**，这里只读后者
 * - 工具结果回填成 `{type: "function_call_output", call_id, output}`
 *
 * 只实现对话与函数调用这两条主线：Web Search、File Search、Code Interpreter
 * 这类托管工具没有纳入。
 */
internal object ResponsesBackend {

    private const val DEFAULT_BASE_URL = "https://api.openai.com/v1"

    /**
     * `/responses` 的地址。
     *
     * 用户填的 baseUrl 习惯带 `/v1`（与 OpenAI 兼容协议一致），这里只去掉重复的
     * 后缀，不强行剥 `/v1`——有些反代把 Responses 挂在别的前缀下。
     */
    fun endpoint(config: AiConfig): String {
        val base = config.baseUrl.ifBlank { DEFAULT_BASE_URL }.trimEnd('/')
        return base + "/responses"
    }

    fun headers(config: AiConfig): Map<String, String> = mapOf(
        "Content-Type" to "application/json",
        "Authorization" to "Bearer ${config.apiKey}",
        "Accept" to "text/event-stream",
    )

    /** 请求体。`input` 传的是可逐轮回填的 item 数组。 */
    fun buildPayload(
        context: Context,
        config: AiConfig,
        history: List<ChatMessage>,
        systemPrompt: String?,
        stream: Boolean,
        enableTools: Boolean,
    ): String {
        val input = JSONArray()
        history.forEach { m ->
            when (m.role) {
                // system 在 Responses 里由顶层 instructions 承载，不能混进 input
                ChatMessage.Role.SYSTEM -> Unit
                ChatMessage.Role.REASONING, ChatMessage.Role.TOOL -> Unit
                else -> input.put(
                    JSONObject()
                        .put("type", "message")
                        .put(
                            "role",
                            if (m.role == ChatMessage.Role.USER) "user" else "assistant",
                        )
                        .put("content", buildContent(context, m)),
                )
            }
        }

        val root = JSONObject()
            .put("model", config.model)
            .put("input", input)
            .put("stream", stream)
            .put("temperature", config.temperature.toDouble())
            // 不在服务端留存会话：每次请求都自带完整 input，留档既无必要也涉及隐私
            .put("store", false)

        systemPrompt?.takeIf { it.isNotBlank() }?.let { root.put("instructions", it) }
        config.reasoning.apiValue?.let { root.put("reasoning_effort", it) }

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
        return root.toString()
    }

    /** 纯文本时用字符串，含图片时用 `input_text` / `input_image` 数组。 */
    private fun buildContent(context: Context, message: ChatMessage): Any {
        val images = message.imageUris.mapNotNull { MediaReader.read(context, it) }
        if (images.isEmpty()) return message.content
        return JSONArray().also { arr ->
            if (message.content.isNotBlank()) {
                arr.put(JSONObject().put("type", "input_text").put("text", message.content))
            }
            images.forEach { (mime, base64) ->
                arr.put(
                    JSONObject()
                        .put("type", "input_image")
                        .put("image_url", "data:$mime;base64,$base64"),
                )
            }
        }
    }

    /**
     * 解析一行 SSE。返回值含义与 [StreamDelta] 一致。
     *
     * 事件名散在 `type` 字段里，Data 行同时承载；`event:` 行被调用方忽略。
     * 工具调用分两类增量：`output_item.added` 给 id 与函数名，
     * `function_call_arguments.delta` 给参数片段，二者用 `output_index` 对齐。
     */
    fun parseEvent(data: String, accumulator: ToolCallAccumulator): StreamDelta? {
        val root = runCatching { JSONObject(data) }.getOrNull() ?: return null
        return when (root.optString("type")) {
            "response.output_item.added" -> {
                val item = root.optJSONObject("item")
                if (item?.optString("type") == "function_call") {
                    accumulator.start(
                        index = root.optInt("output_index", 0),
                        // 回填结果时要用 call_id，不是 item 的 id；前者缺省才退回后者
                        id = item.optString("call_id").ifBlank { item.optString("id") },
                        name = item.optString("name"),
                    )
                }
                StreamDelta()
            }

            "response.function_call_arguments.delta" -> {
                accumulator.append(
                    index = root.optInt("output_index", 0),
                    partial = root.optString("delta"),
                )
                StreamDelta()
            }

            // 推理摘要在 Responses 里是单独一类事件
            "response.reasoning_summary_text.delta" -> StreamDelta(reasoning = root.optString("delta"))

            "response.output_text.delta" -> StreamDelta(text = root.optString("delta"))

            // 用量只在 completed 上给一次：input/output 都在这
            "response.completed" -> {
                val usage = root.optJSONObject("response")?.optJSONObject("usage")
                StreamDelta(
                    promptTokens = usage?.optInt("input_tokens", 0) ?: 0,
                    completionTokens = usage?.optInt("output_tokens", 0) ?: 0,
                )
            }

            "response.failed", "response.incomplete" -> {
                val resp = root.optJSONObject("response")
                val reason = resp?.optJSONObject("error")?.optString("message").orEmpty()
                    .ifBlank { resp?.optJSONObject("incomplete_details")?.optString("reason").orEmpty() }
                error(reason.ifBlank { "响应未完成" })
            }

            else -> null
        }
    }

    /**
     * 从非流式响应里取文本。
     *
     * 正文在 `output[]` 中 `type == "message"` 的项里，逐段拼 `output_text`。
     * `reasoning` 与 `function_call` 这类项不含最终答复，跳过。
     */
    fun parseNonStream(body: String): String {
        val output = JSONObject(body).optJSONArray("output")
            ?: error("响应缺少 output：${body.take(200)}")
        val text = StringBuilder()
        (0 until output.length()).forEach { i ->
            val item = output.optJSONObject(i) ?: return@forEach
            if (item.optString("type") != "message") return@forEach
            val content = item.optJSONArray("content") ?: return@forEach
            (0 until content.length()).forEach { j ->
                val part = content.optJSONObject(j) ?: return@forEach
                if (part.optString("type") == "output_text") text.append(part.optString("text"))
            }
        }
        return text.toString().ifBlank { error("回复内容为空") }
    }

    /** 把本轮的函数调用回填成下一轮的 input item。 */
    fun functionCallInputs(calls: List<ToolCallAccumulator.Call>): List<JSONObject> =
        calls.filter { it.name.isNotBlank() }.map { c ->
            JSONObject()
                .put("type", "function_call")
                .put("call_id", c.id)
                .put("name", c.name)
                .put("arguments", c.arguments.toString().ifBlank { "{}" })
        }

    /** 工具执行结果。Responses 用 `call_id` 关联，不是 Chat Completions 的 `tool_call_id`。 */
    fun functionCallOutputs(results: List<Triple<String, String, String>>): List<JSONObject> =
        results.map { (callId, _, content) ->
            JSONObject()
                .put("type", "function_call_output")
                .put("call_id", callId)
                .put("output", content)
        }
}

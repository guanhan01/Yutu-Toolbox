package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.feature.home.ChatMessage
import com.mcp.toolbox.feature.home.ChatStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 对话任务的持有者。
 *
 * 请求跑在应用级作用域而不是界面的 rememberCoroutineScope，
 * 这样切到别的页面、进后台都不会中断；只有点停止才取消。
 * 界面通过 [running] 订阅当前进度，回到聊天页即可接续看到。
 */
object ChatRunner {

    /** 正在进行的请求。 */
    data class Running(
        val sessionId: String,
        val streamed: String = "",
        /** 正在进行的推理文本。 */
        val reasoning: String = "",
        /** 本轮已发生的工具调用（名称 + 结果摘要）。 */
        val tools: List<ToolStep> = emptyList(),
    )

    /** 一次工具调用记录。 */
    data class ToolStep(val name: String, val result: String = "")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    private val _running = MutableStateFlow<Running?>(null)
    val running: StateFlow<Running?> = _running.asStateFlow()

    fun isBusy(): Boolean = job?.isActive == true

    /**
     * 发起一次请求。[history] 是本次要发给模型的消息；回复会写回 [sessionId] 会话。
     */
    fun start(
        context: Context,
        config: AiConfig,
        sessionId: String,
        history: List<ChatMessage>,
        onFallbackReply: String,
    ) {
        if (isBusy()) return
        _running.value = Running(sessionId = sessionId)
        job = scope.launch {
            try {
                val result = AiChatClient.completeStream(
                    context = context,
                    config = config,
                    history = history,
                    systemPrompt = null,
                    enableTools = true,
                    onToolCall = { name, _ ->
                        val current = _running.value ?: return@completeStream
                        _running.value = current.copy(tools = current.tools + ToolStep(name))
                    },
                    onToolResult = { name, result ->
                        val current = _running.value ?: return@completeStream
                        _running.value = current.copy(
                            tools = current.tools.map {
                                if (it.name == name && it.result.isEmpty()) {
                                    it.copy(result = result.take(200))
                                } else {
                                    it
                                }
                            },
                        )
                    },
                    onReasoning = { delta ->
                        val current = _running.value ?: return@completeStream
                        _running.value = current.copy(reasoning = current.reasoning + delta)
                    },
                    onDelta = { delta ->
                        val current = _running.value ?: return@completeStream
                        _running.value = current.copy(streamed = current.streamed + delta)
                    },
                )
                val reply = result.getOrElse { onFallbackReply.format(it.message ?: "") }

                // 先把思考过程（推理 + 工具调用）落成一条消息，再落最终回复
                val finished = _running.value
                val reasoning = finished?.reasoning.orEmpty()
                val tools = finished?.tools.orEmpty()
                if (reasoning.isNotBlank() || tools.isNotEmpty()) {
                    ChatStore.append(
                        context, sessionId,
                        ChatMessage(
                            role = ChatMessage.Role.REASONING,
                            content = buildReasoningText(reasoning, tools),
                        ),
                    )
                }
                ChatStore.append(
                    context, sessionId,
                    ChatMessage(role = ChatMessage.Role.ASSISTANT, content = reply),
                )
            } catch (t: Throwable) {
                // 取消时静默收尾，其他异常写回会话
                if (t !is kotlinx.coroutines.CancellationException) {
                    ChatStore.append(
                        context, sessionId,
                        ChatMessage(
                            role = ChatMessage.Role.ASSISTANT,
                            content = onFallbackReply.format(t.message ?: ""),
                        ),
                    )
                }
            } finally {
                _running.value = null
                job = null
            }
        }
    }

    /** 只有用户主动点停止才会中断。 */
    fun stop() {
        job?.cancel()
        job = null
        _running.value = null
    }

}

/** 把推理文本与工具调用拼成一条可折叠展示的内容。 */
private fun buildReasoningText(reasoning: String, tools: List<ChatRunner.ToolStep>): String =
    buildString {
        if (reasoning.isNotBlank()) append(reasoning.trim())
        tools.forEach { step ->
            if (isNotEmpty()) append("\n\n")
            append("\u00b7 \u8c03\u7528 ").append(step.name)
            if (step.result.isNotBlank()) {
                append("\n").append(step.result.lineSequence().take(4).joinToString("\n"))
            }
        }
    }

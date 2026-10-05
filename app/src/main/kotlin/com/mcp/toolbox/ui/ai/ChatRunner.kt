package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.feature.home.ChatMessage
import com.mcp.toolbox.feature.home.agent.AgentStreamBus
import com.mcp.toolbox.feature.home.agent.SystemKind
import com.mcp.toolbox.feature.home.ChatMemoryStore
import com.mcp.toolbox.feature.home.TurnState
import com.mcp.toolbox.feature.home.ChatStore
import com.mcp.toolbox.feature.home.ChatUsageStore
import com.mcp.toolbox.feature.mcp.SkillStore
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
        /**
         * 本轮过程要写进哪条 AI 消息。
         *
         * 发送时界面先往会话里落一条 `Running` 占位，id 一并传进来；
         * 回答区不在场时（用户切页后才点停止）就用它做兜底落盘。
         */
        val messageId: String = "",
        /** 已经流出的正文。 */
        val streamed: String = "",
        /** 最近一次请求的真实用量（服务端返回）；未收到时保持 0。 */
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    /**
     * 「第几轮」计数。
     *
     * 停止后立刻再发一条时，被取消那轮的 finally 会晚一步跑到。它若无条件清
     * [_running]，就会把新一轮刚建立的时间线一起抹掉。每轮取一个自增号，
     * 收尾时只在号码仍是最新时才动共享状态。
     */
    private var generation = 0

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
        assistantMessageId: String,
        history: List<ChatMessage>,
        onFallbackReply: String,
    ) {
        if (isBusy()) return
        _running.value = Running(sessionId = sessionId, messageId = assistantMessageId)
        // 回答区先把乐观占位卡摆上屏（Spec 第 5.2 节：避免发送后 200ms 毫无反应）
        AgentStreamBus.emit { it.onStarted() }
        // 让 memory.write / memory.delete 知道自己属于哪个会话。
        AiToolBridge.sessionId = sessionId
        val myGeneration = ++generation
        job = scope.launch {
            try {
                val result = AiChatClient.completeStream(
                    context = context,
                    config = config,
                    history = history,
                    systemPrompt = buildSystemPrompt(context, config),
                    enableTools = true,
                    // 旧回答 UI 的思考 / 工具 / 旁白时间线已移除；这些回调暂时只做
                    // 用量统计与正文累积，等重构方案定了再决定新的呈现方式。
                    onDelta = { delta ->
                        val current = _running.value ?: return@completeStream
                        _running.value = current.copy(streamed = current.streamed + delta)
                        // 真实正文增量 → 回答区（经 TokenBuffer 节流后落屏）
                        AgentStreamBus.emit { it.onDelta(delta) }
                    },
                    // 深度思考增量：回答区按轮次排布（第 N 轮思考在同轮工具卡之上）
                    onReasoning = { round, text ->
                        AgentStreamBus.emit { it.onThinking(round, text) }
                    },
                    // 工具调用那一轮的过渡旁白，不并进最终回复
                    onNarration = { round, text ->
                        AgentStreamBus.emit { it.onNarration(round, text) }
                    },
                    onToolCall = { round, seq, name, args ->
                        AgentStreamBus.emit { it.onToolCall(round, seq, name, args) }
                    },
                    onToolResult = { round, seq, name, result ->
                        AgentStreamBus.emit { it.onToolResult(round, seq, name, result, true) }
                    },
                    // 60s 无数据兜底：真的掐断上游，而不是让界面一直转圈
                    onStall = {
                        AgentStreamBus.emit { it.onFailed(SystemKind.Stall, "", 0) }
                    },
                    onUsage = { prompt, completion ->
                        val current = _running.value ?: return@completeStream
                        _running.value = current.copy(
                            promptTokens = prompt,
                            completionTokens = completion,
                        )
                        // 立刻写回用量表，输入栏的上下文条不必等整轮跑完才动。
                        scope.launch {
                            ChatUsageStore.record(context, sessionId, prompt, completion)
                        }
                    },
                )
            // ── 收尾 ──
            // 不再在这里 `ChatStore.append` 一条纯文本：回答区持有完整时间线
            // （思考 / 工具 / 正文 / 系统卡），由它 upsert 到本轮那条消息上。
            // 两边都写就会落下两条记录：一条带过程、一条纯文本。
            if (result.isSuccess) {
                // 冲刷残缓冲正文 + 把仍在 Running 的段定型
                AgentStreamBus.emitFinished()
            } else {
                AgentStreamBus.emit {
                    it.onFailed(SystemKind.ServerError, result.exceptionOrNull()?.message.orEmpty(), 0)
                }
            }
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
                // 只有还是最新一轮时才清状态：旧任务的收尾不能覆盖新任务。
                if (myGeneration == generation) {
                    AiToolBridge.sessionId = null
                    _running.value = null
                    job = null
                }
            }
        }
    }

    /**
     * 用户主动点停止。
     *
     * 请求中断，但已经流出的正文不丢：落盘后界面立刻能看到。
     */
    fun stop(context: Context) {
        // 请求其实已经跑完时不再补落盘：正常路径已经写过一遍，
        // 这里再写就成了两份一模一样的记录。
        val stillRunning = job?.isActive == true
        val snapshot = _running.value.takeIf { stillRunning }
        // 先掐断上游：`job.cancel()` 打不断阻塞的 `readLine()`，
        // 只关协程的话服务端还会继续推、继续计费（实测「点停止后它继续做」）。
        AiChatClient.abortInflight()
        job?.cancel()
        job = null
        // 号码前进一格，被取消那轮的 finally 便不再有权改写共享状态
        generation++
        AiToolBridge.sessionId = null
        _running.value = null
        // 过程与正文由回答区统一落盘（它持有完整时间线）。
        // 只有「回答区已经不在了」（用户切到别的页面后才点停止）才退回写纯文本，
        // 否则同一轮会落成两条：一条带过程的、一条纯文本的。
        val delivered = AgentStreamBus.emitInterrupted()
        if (!delivered && snapshot != null) {
            val left = snapshot.streamed.trim()
            if (left.isNotEmpty()) {
                scope.launch {
                    ChatStore.upsertAssistant(
                        context = context,
                        sessionId = snapshot.sessionId,
                        messageId = snapshot.messageId,
                        content = left,
                        segmentsJson = null,
                        turnState = TurnState.Interrupted,
                    )
                }
            }
        }
    }

    /**
     * 拼装 system prompt。
     *
     * 三部分按优先级拼接：用户自定义提示 → 长期记忆 → 启用的 Skill。
     */
    private fun buildSystemPrompt(
        context: Context,
        config: AiConfig,
    ): String? {
        val parts = buildList {
            add(BASE_PROMPT)
            config.systemPrompt.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
            // 记忆预算跟着当前模型窗口走，换模型后限额自动收敛
            ChatMemoryStore.syncModelWindow(context, config.active.contextWindowOrNull() ?: 0)
            ChatMemoryStore.buildPrompt()?.let { add(it) }
            // 用户导入并启用的 Skill：只给目录（名称 + 描述），正文按需用 skill.read 取
            SkillStore.buildPrompt()?.let { add(it) }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

}

/**
 * 内置基础提示词。
 *
 * 用户没填系统提示词时，此前这里什么都不下发，模型连「可以用表情」都不知道，
 * 回复会刻意干瘪。这段只声明允许的表达方式，不规定人格与内容，用户自己的
 * 系统提示词仍原样追加在后面。
 */
private const val BASE_PROMPT = """你在一个 Android 工具箱应用里回答用户。
- 回答可以自然使用表情符号来辅助表达（例如 ✅ ⚠️ 📌 以及常见的 emoji）。
- 支持 Markdown：标题、列表、表格、代码块、行内代码、加粗、链接。
- 涉及代码、路径、命令时用代码块，不要贴大段无格式纯文本。
"""

package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.feature.home.ChatMessage
import com.mcp.toolbox.feature.home.ChatMemoryStore
import com.mcp.toolbox.feature.home.ChatStore
import com.mcp.toolbox.feature.home.ChatUsageStore
import com.mcp.toolbox.feature.home.CompressionStore
import com.mcp.toolbox.feature.home.PlanStore
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
        val streamed: String = "",
        /**
         * 本轮回复的时间线。
         *
         * 一次回复里「思考 → 工具调用 → 再思考 → 再调用 → 最终文本」是交替发生的，
         * 所以这里按发生顺序整条保留，而不是按类型分成两个桶——分桶会让所有思考
         * 聚成一块，看着像「思考全部发生在工具之前」。
         */
        val timeline: List<Step> = emptyList(),
        /** 最近一次请求的真实用量（服务端返回）；未收到时保持 0。 */
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
    )

    /** 时间线上的一步。 */
    sealed interface Step {
        val round: Int

        /** 第 [round] 轮的深度思考文本；[live] 表示仍在接收增量。 */
        data class Thinking(
            override val round: Int,
            val text: String,
            val live: Boolean = false,
            val elapsedMs: Long = 0,
        ) : Step

        /** 第 [round] 轮里模型带工具调用时的过渡旁白（不是最终答复）。 */
        data class Narration(override val round: Int, val text: String) : Step

        /** 第 [round] 轮的第 [seq] 次工具调用。 */
        data class Tool(
            override val round: Int,
            val seq: Int,
            val name: String,
            val arguments: String = "",
            val result: String = "",
            val elapsedMs: Long = 0,
        ) : Step
    }

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
        history: List<ChatMessage>,
        onFallbackReply: String,
    ) {
        if (isBusy()) return
        _running.value = Running(sessionId = sessionId)
        // 让 plan.update / memory.write 知道自己属于哪个会话。
        AiToolBridge.sessionId = sessionId
        // 同一时刻只有一个思考流 / 一次工具调用在跑，用单个时间戳足够
        var toolStartedAt = System.currentTimeMillis()
        var thinkingStartedAt = System.currentTimeMillis()
        val myGeneration = ++generation
        job = scope.launch {
            try {
                val result = AiChatClient.completeStream(
                    context = context,
                    config = config,
                    // 压缩只改「发给模型的历史」：磁盘上原文一条不动
                    history = CompressionStore.modelHistoryFor(sessionId, history),
                    systemPrompt = buildSystemPrompt(context, config, sessionId),
                    enableTools = true,
                    onToolCall = { round, seq, name, args ->
                        val current = _running.value ?: return@completeStream
                        val now = System.currentTimeMillis()
                        toolStartedAt = now
                        _running.value = current.copy(
                            timeline = current.timeline.map { step ->
                                if (step is Step.Thinking && step.live) {
                                    step.copy(live = false, elapsedMs = now - thinkingStartedAt)
                                } else {
                                    step
                                }
                            } + Step.Tool(
                                round = round, seq = seq, name = name, arguments = args,
                            ),
                            // 本轮旁白已归档进时间线，清掉实时正文的累积——
                            // 否则「我来查一下…」会和下一轮的最终答复拼成一段。
                            streamed = "",
                        )
                    },
                    onToolResult = { round, seq, _, result ->
                        val current = _running.value ?: return@completeStream
                        val cost = (System.currentTimeMillis() - toolStartedAt).coerceAtLeast(0)
                        // 按 (round, seq) 定位，避免同名工具或重复调用时匹配错
                        _running.value = current.copy(
                            timeline = current.timeline.map { step ->
                                if (step is Step.Tool && step.round == round && step.seq == seq) {
                                    step.copy(result = result.take(200), elapsedMs = cost)
                                } else {
                                    step
                                }
                            },
                        )
                    },
                    // 同一个 round 的思考增量累积到该条上；新 round 追加新条，
                    // 于是「工具之后的思考」自然排到工具卡片下面。
                    // 每次思考增量都把整条时间线里其它思考的 live 清掉：
                    // 只有正在接收增量的那一段才是「进行中」。
                    onReasoning = { round, delta ->
                        val current = _running.value ?: return@completeStream
                        val last = current.timeline.lastOrNull()
                        val now = System.currentTimeMillis()
                        _running.value = if (last is Step.Thinking && last.round == round) {
                            current.copy(
                                timeline = current.timeline.dropLast(1) +
                                    last.copy(
                                        text = last.text + delta,
                                        live = true,
                                        elapsedMs = now - thinkingStartedAt,
                                    ),
                            )
                        } else {
                            // 新的一段思考开始，重新计时
                            thinkingStartedAt = now
                            current.copy(
                                timeline = current.timeline + Step.Thinking(
                                    round = round,
                                    text = delta,
                                    live = true,
                                    elapsedMs = 0,
                                ),
                            )
                        }
                    },
                    // 旁白或工具一出现，说明上一段思考已经结束：立刻把 live 收掉，
                    // 界面便不会再显示「深度思考中…」。
                    onNarration = { round, text ->
                        val current = _running.value ?: return@completeStream
                        _running.value = current.copy(
                            timeline = current.timeline.map { step ->
                                if (step is Step.Thinking) step.copy(live = false) else step
                            } + Step.Narration(round, text),
                        )
                    },
                    onDelta = { delta ->
                        val current = _running.value ?: return@completeStream
                        _running.value = current.copy(streamed = current.streamed + delta)
                    },
                    onUsage = { prompt, completion ->
                        val current = _running.value ?: return@completeStream
                        _running.value = current.copy(
                            promptTokens = prompt,
                            completionTokens = completion,
                        )
                        // 立刻写回用量表，进度条不必等整轮跑完才动。
                        // 回调不是挂起函数，另起一次写入即可，不阻塞流读取。
                        scope.launch {
                            ChatUsageStore.record(context, sessionId, prompt, completion)
                        }
                    },
                )
                val reply = result.getOrElse { onFallbackReply.format(it.message ?: "") }

                // 思考 / 旁白 / 工具调用按发生顺序落成消息，再落最终回复。
                // 顺序在这里决定界面排版：落到工具之后的思考，渲染时就在工具下面。
                _running.value?.timeline.orEmpty().forEach { step ->
                    when (step) {
                        is Step.Thinking -> {
                            if (step.text.isNotBlank()) {
                                ChatStore.append(
                                    context, sessionId,
                                    ChatMessage(
                                        role = ChatMessage.Role.REASONING,
                                        content = step.text.trim(),
                                        elapsedMs = step.elapsedMs,
                                    ),
                                )
                            }
                        }
                        // 旁白是模型带工具那轮的正文，单独成条，不并进最终回复
                        is Step.Narration -> {
                            if (step.text.isNotBlank()) {
                                ChatStore.append(
                                    context, sessionId,
                                    ChatMessage(
                                        role = ChatMessage.Role.ASSISTANT,
                                        content = step.text.trim(),
                                        // 中间旁白：不显示操作按钮
                                        intermediate = true,
                                    ),
                                )
                            }
                        }
                        is Step.Tool -> ChatStore.append(
                            context, sessionId,
                            ChatMessage(
                                role = ChatMessage.Role.TOOL,
                                content = step.result,
                                toolName = step.name,
                                toolArguments = step.arguments,
                                elapsedMs = step.elapsedMs,
                            ),
                        )
                    }
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
     * 不能只 cancel：已跑完的思考段落、工具结果和已经流出的正文都是用户看着
     * 攒出来的，直接丢掉等于「点停止就把整轮抹掉」。先把它们按正常顺序落盘，
     * 再中断请求——落盘走的是和正常结束完全相同的路径，界面立刻能看到。
     */
    fun stop(context: Context) {
        // 请求其实已经跑完时不再补落盘：正常路径已经写过一遍，
        // 这里再写就成了两份一模一样的记录。
        val stillRunning = job?.isActive == true
        val snapshot = _running.value.takeIf { stillRunning }
        job?.cancel()
        job = null
        // 号码前进一格，被取消那轮的 finally 便不再有权改写共享状态
        generation++
        AiToolBridge.sessionId = null
        _running.value = null
        if (snapshot != null) {
            scope.launch { persistPartial(context, snapshot) }
        }
    }

    /**
     * 把一轮中途停止的回复落成消息。
     *
     * 顺序与正常结束一致：思考 / 旁白 / 工具按发生顺序，最后是已流出的正文。
     * 正文为空时不落一条空 ASSISTANT，避免历史里出现空气泡。
     */
    private suspend fun persistPartial(context: Context, running: Running) {
        running.timeline.forEach { step ->
            when (step) {
                is Step.Thinking -> if (step.text.isNotBlank()) {
                    ChatStore.append(
                        context, running.sessionId,
                        ChatMessage(
                            role = ChatMessage.Role.REASONING,
                            content = step.text.trim(),
                            elapsedMs = step.elapsedMs,
                        ),
                    )
                }
                is Step.Narration -> if (step.text.isNotBlank()) {
                    ChatStore.append(
                        context, running.sessionId,
                        ChatMessage(
                            role = ChatMessage.Role.ASSISTANT,
                            content = step.text.trim(),
                            intermediate = true,
                        ),
                    )
                }
                is Step.Tool -> ChatStore.append(
                    context, running.sessionId,
                    ChatMessage(
                        role = ChatMessage.Role.TOOL,
                        content = step.result,
                        toolName = step.name,
                        toolArguments = step.arguments,
                        elapsedMs = step.elapsedMs,
                    ),
                )
            }
        }
        val left = running.streamed.trim()
        if (left.isNotEmpty()) {
            ChatStore.append(
                context, running.sessionId,
                ChatMessage(role = ChatMessage.Role.ASSISTANT, content = left),
            )
        }
    }

    /**
     * 拼装 system prompt。
     *
     * 三部分按优先级拼接：用户自定义提示 → 长期记忆 → 当前计划。
     * 此前这里传的是 null，用户配置的系统提示实际从未下发过。
     */
    private fun buildSystemPrompt(
        context: Context,
        config: AiConfig,
        sessionId: String,
    ): String? {
        val parts = buildList {
            add(BASE_PROMPT)
            config.systemPrompt.takeIf { it.isNotBlank() }?.let { add(it.trim()) }
            // 记忆预算跟着当前模型窗口走，换模型后限额自动收敛
            ChatMemoryStore.syncModelWindow(context, config.active.contextWindowOrNull() ?: 0)
            ChatMemoryStore.buildPrompt()?.let { add(it) }
            PlanStore.buildPrompt(sessionId)?.let { add(it) }
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

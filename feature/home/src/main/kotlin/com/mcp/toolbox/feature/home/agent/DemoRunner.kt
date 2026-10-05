package com.mcp.toolbox.feature.home.agent

import kotlinx.coroutines.delay
import com.mcp.toolbox.feature.home.BuildConfig
import com.mcp.toolbox.feature.home.ChatMessage
import com.mcp.toolbox.feature.home.ChatStore
import com.mcp.toolbox.feature.home.AttachmentRef
import com.mcp.toolbox.feature.home.TurnState

/**
 * 回答区演示脚本的触发开关（仅 debug）。
 *
 * ## 为什么用 intent extra 而不是界面按钮
 *
 * 曾经在顶栏放过一枚 ★ 按钮，结果是「用户能看见一个不该存在的东西」——已移除。
 * 现在改为识别 Activity 启动 Intent 里的 extra：
 *
 * ```sh
 * am start -n com.Yutu.Agent.beta/com.mcp.toolbox.MainActivity \
 *     --es eta_demo full
 * ```
 *
 * 好处：**零界面痕迹**、可脚本化重复触发、release 里整段路径不可达。
 * 支持的值：`full`（完整一轮）/ `tools`（工具六态）/ `errors`（异常矩阵）。
 */
object DemoRunner {

    const val EXTRA = "eta_demo"

    /** 当前要跑的脚本；null 表示不跑。由 `MainActivity` 从 Intent 里读出来后设置。 */
    var pending: String? = null

    val enabled: Boolean = BuildConfig.DEBUG

    /**
     * 准备一个真实会话并绑定轮次，然后跑 [block]。
     *
     * 刻意走**真实落盘路径**（建会话 → 落一条用户消息 → 落 Running 占位 → 绑定），
     * 这样演示不仅验证渲染，也顺带验证「过程落盘 / 冷启动恢复」这两条最容易坏的链路。
     */
    private suspend fun withTurn(
        context: android.content.Context,
        state: AgentChatState,
        prompt: String,
        block: suspend (AgentChatState) -> Unit,
    ) {
        state.detachTurn()
        state.resetAll()
        val session = ChatStore.newSession(context, prompt.take(24))
        ChatStore.append(
            context,
            session.id,
            ChatMessage(role = ChatMessage.Role.USER, content = prompt),
        )
        val assistantId = java.util.UUID.randomUUID().toString()
        ChatStore.upsertAssistant(
            context = context,
            sessionId = session.id,
            messageId = assistantId,
            content = "",
            segmentsJson = null,
            turnState = TurnState.Running,
        )
        state.bindTurn(session.id, assistantId)
        block(state)
    }

    suspend fun playAll(context: android.content.Context, state: AgentChatState) =
        withTurn(context, state, "演示：完整一轮回答") { AgentDemoScript.play(it) }

    suspend fun playTools(context: android.content.Context, state: AgentChatState) =
        withTurn(context, state, "演示：工具卡六态") { AgentDemoScript.playTools(it) }

    suspend fun playErrors(context: android.content.Context, state: AgentChatState) =
        withTurn(context, state, "演示：异常矩阵") {
            // 异常矩阵一轮里塞不下，只能逐段跑；这里只跑到第一个失败卡片为止
            AgentDemoScript.playErrors(it)
        }

    /**
     * 连跑两轮：验证「第二个问题也有工具卡、且不串到第一轮」。
     *
     * 这是用户实际报告过的场景。它同时覆盖两件事：
     * 1. 每轮的时间线独立（`(round, seq)` 不会命中上一轮的卡片）
     * 2. 新问题排在旧回答**下面**，而不是跑到旧回答的过程块上面
     */
    suspend fun playTwoTurns(context: android.content.Context, state: AgentChatState) {
        withTurn(context, state, "演示：第一轮") { AgentDemoScript.play(it, brief = true) }
        kotlinx.coroutines.delay(400)
        withTurn(context, state, "演示：第二轮") { AgentDemoScript.play(it, brief = true) }
    }

    /**
     * 验证「用户气泡带附件」：一条消息里同时有图片、文件、文件夹、手输路径。
     *
     * 同时验证它们**只产生一个用户气泡**（早先附件上下文被当成第二条
     * `role = USER` 消息，界面上会出现两个气泡、第二个是一大段原始上下文）。
     */
    suspend fun playAttachments(context: android.content.Context, state: AgentChatState) {
        state.detachTurn()
        state.resetAll()
        val session = ChatStore.newSession(context, "演示：附件气泡")
        ChatStore.append(
            context,
            session.id,
            ChatMessage(
                role = ChatMessage.Role.USER,
                content = "帮我看看这几份材料",
                attachments = listOf(
                    AttachmentRef(AttachmentRef.Kind.FILE, "report-final-v3.pdf"),
                    AttachmentRef(AttachmentRef.Kind.FOLDER, "…/proj/src"),
                    AttachmentRef(AttachmentRef.Kind.PATH, "…/tmp/build.log"),
                ),
                modelContext = "【文件】report-final-v3.pdf 的内容：……",
            ),
        )
        ChatStore.append(
            context,
            session.id,
            ChatMessage(
                role = ChatMessage.Role.ASSISTANT,
                content = "收到 3 项材料，我来逐个看。",
                segmentsJson = AgentSegmentCodec.encode(
                    listOf(
                        ThinkingSegment(
                            id = "t1",
                            createdAt = System.currentTimeMillis(),
                            text = "先确认材料类型。",
                            status = RunStatus.Done,
                            elapsedMs = 2400,
                        ),
                        AnswerSegment(
                            id = "a1",
                            createdAt = System.currentTimeMillis(),
                            markdown = "收到 3 项材料，我来逐个看。",
                            status = RunStatus.Done,
                        ),
                    ),
                ),
                turnState = TurnState.Done,
            ),
        )
    }

    /**
     * 验证「点停止」的落盘：跑到一半打断，过程与已生成正文都要留在消息里。
     */
    suspend fun playStopped(context: android.content.Context, state: AgentChatState) {
        withTurn(context, state, "演示：中途停止") {
            it.onThinking(1, "先规划一下步骤。")
            kotlinx.coroutines.delay(60)
            it.onToolCall(1, 0, "file.list", """{"path":"/sdcard"}""")
            kotlinx.coroutines.delay(80)
            it.onToolResult(1, 0, "file.list", "a.txt\nb.txt", true)
            it.onDelta("已经生成了一部分正文，然后用户按了停止。")
            kotlinx.coroutines.delay(80)
            // 用户点了停止：状态机必须把思考/工具/正文全部定型并落盘
            it.onInterrupted()
        }
    }

    /**
     * **同一个会话**里连发两轮。
     *
     * 这是用户实际报告的场景：第二个问题会带上第一个问题的思考与回答。
     * 根因是 `bindTurn` 只换 id、不清时间线，新事件的增量被追加到旧过程后面。
     */
    suspend fun playSameSession(context: android.content.Context, state: AgentChatState) {
        state.detachTurn()
        state.resetAll()
        val session = ChatStore.newSession(context, "演示：同会话两轮")
        repeat(2) { i ->
            val prompt = "演示：同会话第 ${i + 1} 问"
            ChatStore.append(
                context,
                session.id,
                ChatMessage(role = ChatMessage.Role.USER, content = prompt),
            )
            val assistantId = java.util.UUID.randomUUID().toString()
            ChatStore.upsertAssistant(
                context = context,
                sessionId = session.id,
                messageId = assistantId,
                content = "",
                segmentsJson = null,
                turnState = TurnState.Running,
            )
            state.bindTurn(session.id, assistantId)
            AgentDemoScript.play(state, brief = true)
            kotlinx.coroutines.delay(300)
        }
    }

    /**
     * 链接点击探针：**零数据足迹**。
     *
     * 绑定一个假的 sessionId —— `ChatStore.upsertAssistant` 是按 id 在现有会话里
     * 查找并更新的，找不到就什么都不做。所以这个探针不会在用户的会话列表里
     * 留下任何东西，却能在界面上造出带链接的回答，用来验证点击链路。
     */
    suspend fun playLinkProbe(state: AgentChatState) {
        state.detachTurn()
        state.resetAll()
        state.bindTurn("__probe__", "__probe__")
        state.onStarted(round = 1)
        state.onThinking(1, "准备一个链接。")
        delay(120)
        state.onDelta("这是 Markdown 链接：[百度](https://www.baidu.com)\n\n")
        delay(80)
        state.onDelta("这是裸链接：https://www.example.com/path?a=1#top\n")
        delay(80)
        state.onFinished()
    }

    /** 按 [pending] 派发。 */
    suspend fun runPending(context: android.content.Context, state: AgentChatState): Boolean {
        if (!enabled) return false
        val which = pending ?: return false
        pending = null
        when (which) {
            "full" -> playAll(context, state)
            "tools" -> playTools(context, state)
            "errors" -> playErrors(context, state)
            "two" -> playTwoTurns(context, state)
            "attach" -> playAttachments(context, state)
            "stop" -> playStopped(context, state)
            "same" -> playSameSession(context, state)
            "link" -> playLinkProbe(state)
            "long" -> withTurn(context, state, "演示：长流（滚动验证）") {
                AgentDemoScript.playLongStream(it)
            }
            else -> return false
        }
        return true
    }
}

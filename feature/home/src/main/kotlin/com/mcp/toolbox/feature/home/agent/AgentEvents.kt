package com.mcp.toolbox.feature.home.agent

/**
 * 网络层事件（UI/动效 Spec 第 3.3 节的状态机输入）。
 *
 * 这一层刻意**不依赖任何 Android / Compose 类型**：它是纯数据，
 * 可以被单元测试直接喂进去、断言产出的 Segment 列表。
 *
 * 与现有的 [com.mcp.toolbox.ui.ai.AiChatClient.completeStream] 回调一一对应：
 * `onReasoning` → [ThinkingDelta]、`onToolCall` → [ToolCallStarted]、
 * `onToolResult` → [ToolCompleted]、`onNarration` → [Narration]、
 * `onDelta` → [AnswerDelta]、收尾 → [Finished]。
 */
sealed interface AgentEvent {

    /**
     * 一轮请求开始。**乐观渲染**：点发送后立刻发这个事件，界面插入一条空 AI 卡片，
     * 避免「发送后 200ms 界面毫无反应」（Spec 第 5.2 节）。
     */
    data class Started(val round: Int = 1) : AgentEvent

    /** 深度思考增量。同一轮里思考一定先于该轮工具调用到达。 */
    data class ThinkingDelta(val round: Int, val text: String) : AgentEvent

    /** 工具调用那一轮的正文旁白——是「我要去调用工具」的过渡语句，不是最终答复。 */
    data class Narration(val round: Int, val text: String) : AgentEvent

    data class ToolCallStarted(
        val round: Int,
        val seq: Int,
        val name: String,
        val argsJson: String? = null,
    ) : AgentEvent

    data class ToolCompleted(
        val round: Int,
        val seq: Int,
        val name: String,
        val result: String,
        val success: Boolean = true,
        val sources: List<WebSource>? = null,
    ) : AgentEvent

    /** 需要用户授权的工具（Human-in-the-loop）。 */
    data class ToolNeedsApproval(
        val round: Int,
        val seq: Int,
        val name: String,
        val argsJson: String?,
        val risk: String,
    ) : AgentEvent

    /** 用户对审批卡的选择。 */
    data class ToolApprovalResolved(val round: Int, val seq: Int, val allowed: Boolean) : AgentEvent

    /** 最终回答增量。 */
    data class AnswerDelta(val text: String) : AgentEvent

    /** 整轮正常收尾。 */
    data object Finished : AgentEvent

    /** 用户主动停止。 */
    data object Interrupted : AgentEvent

    /**
     * 失败。异常矩阵（Spec 第 8.3 节）全部收敛到这里，
     * [kind] 决定渲染哪张卡片，**不允许落到「什么都不显示」**。
     */
    data class Failed(
        val kind: SystemKind,
        val message: String = "",
        val retryAfterSeconds: Int = 0,
    ) : AgentEvent

    /**
     * 长度上限截断 / 0 token / 只有工具没有总结——
     * 这些不是「失败」，是需要在尾部追加一个可操作提示的情况。
     */
    data class Anomaly(val kind: SystemKind, val payload: String = "") : AgentEvent
}

package com.mcp.toolbox.feature.home.agent

/**
 * 流式事件的落点。
 *
 * ## 为什么要这个接口
 * 发起请求的 [com.mcp.toolbox.ui.ai.ChatRunner] 在 `app` 模块，而回答区状态
 * [AgentChatState] 在 `feature:home` 模块。`app → feature:home` 是允许的依赖方向，
 * 但反过来不行，所以不能把状态对象直接交给 Runner。
 *
 * 这里定义接口放在 `feature:home`：Runner 只认识接口，界面只负责注册实现，
 * 两个模块都不需要跨层引用对方的具体类型。
 */
interface AgentStreamSink {
    fun onStarted(round: Int = 1)
    fun onThinking(round: Int, text: String)
    fun onNarration(round: Int, text: String)
    fun onToolCall(round: Int, seq: Int, name: String, args: String?)
    fun onToolResult(round: Int, seq: Int, name: String, result: String, success: Boolean = true)
    fun onToolNeedsApproval(round: Int, seq: Int, name: String, args: String?, risk: String)
    fun onDelta(text: String)
    fun onFinished()
    fun onInterrupted()
    fun onFailed(kind: SystemKind, message: String = "", retryAfterSeconds: Int = 0)
    fun onAnomaly(kind: SystemKind, payload: String = "")
}

/**
 * 当前活跃的落点。
 *
 * 应用同一时刻只可能有一个对话页在前台（[ChatRunner][com.mcp.toolbox.ui.ai.ChatRunner]
 * 也用 `isBusy()` 保证同时只跑一个请求），所以单个引用足够，不需要多播。
 * 用 `@Volatile` 是因为写入发生在组合期、读取发生在 IO 线程的回调里。
 */
object AgentStreamBus {
    @Volatile
    var sink: AgentStreamSink? = null

    /**
     * 派发一个事件。返回**是否有人接收**。
     *
     * 返回值很重要：回答区负责把「本轮过程 + 正文 + 终态」落盘，
     * 而它可能已经不在（用户切到别的页面后才点停止）。
     * 调用方据此决定要不要退回「至少把纯文本写进去」的兜底路径。
     */
    fun emit(block: (AgentStreamSink) -> Unit): Boolean {
        val target = sink ?: return false
        block(target)
        return true
    }

    /** 通知一轮被中断。返回是否有回答区接收。 */
    fun emitInterrupted(): Boolean = emit { it.onInterrupted() }

    /** 通知一轮正常收尾。返回是否有回答区接收。 */
    fun emitFinished(): Boolean = emit { it.onFinished() }
}

package com.mcp.toolbox.feature.home.agent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import com.mcp.toolbox.core.design.component.MdBlock
import com.mcp.toolbox.core.design.component.parseMarkdown
import com.mcp.toolbox.feature.home.TurnState
import com.mcp.toolbox.feature.home.agent.stream.AutoScrollTracker
import com.mcp.toolbox.feature.home.agent.stream.MarkdownChunker
import com.mcp.toolbox.feature.home.agent.stream.TokenBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 回答区的状态宿主。
 *
 * ## 单一真相
 *
 * 这一轮的过程时间线（思考 / 工具 / 正文 / 系统卡）是**消息的内容**，不是「界面临时状态」。
 * 所以 [segments] 会被落盘（通过 [persist]），冷启动时从消息里读回来。
 *
 * 早先时间线只在内存里、而 `ChatStore` 只存纯文本，直接导致四个 bug：
 * 切后台回来过程丢失、点停止没有过程记录、第二轮工具卡不出现、
 * 新一轮的问题排到上一轮过程块下面。
 *
 * ## 隔离重组
 *
 * [segments] 是**不可变列表**（`mutableStateOf(emptyList())`）而不是 `SnapshotStateList`。
 * 用可变列表 + `clear()/addAll()` 会让 LazyColumn 观察到「中间态空列表」，
 * 拿旧的 index 去 get 空列表 → `IndexOutOfBoundsException` 崩溃（实测踩到）。
 * 整体替换一个不可变列表既避免这个竞态，也让「哪些 item 变了」由 `equals` 决定：
 * Compose 会跳过没变的段，已完成的思考块与工具卡不重组。
 */
class AgentChatState internal constructor() : AgentStreamSink {

    /** 渲染用时间线。**整体替换**，不做原地增删。 */
    var segments: List<Segment> by mutableStateOf(emptyList())
        private set

    private val timeline = AgentTimeline()
    private val buffer = TokenBuffer()
    private val chunker = MarkdownChunker()

    val autoScroll = AutoScrollTracker()

    /**
     * 落盘回调。由界面层注入（`ChatStore.upsertAssistant`）。
     *
     * 用回调而不是让 state 直接依赖 `ChatStore`：状态层因此保持可单测，
     * 也避免 `feature:home` 内部互相引用形成环。
     */
    var persist: ((segments: List<Segment>, content: String, state: TurnState) -> Unit)? = null

    /** 本轮是否已被打开过（决定要不要落盘占位）。 */
    private var started = false

    /** 本轮过程要写进哪条会话的哪条消息。由界面层在发送前绑定。 */
    var sessionId: String? by mutableStateOf(null)
        private set

    var messageId: String? by mutableStateOf(null)
        private set

    /** 本轮终态。 */
    var turnState: TurnState = TurnState.Done
        private set

    // ---- 正文增量缓存（只对当前流式那一段有效）----

    var closedBlocks: List<MdBlock> by mutableStateOf(emptyList())
        private set

    var streamTail: String by mutableStateOf("")
        private set

    var streamInFence: Boolean by mutableStateOf(false)
        private set

    /** 当前正由 [chunker] 跟踪的那一段正文。只有它可以用 [closedBlocks]。 */
    var streamingAnswerId: String? by mutableStateOf(null)
        private set

    var streaming: Boolean by mutableStateOf(false)
        private set

    var hasAnswer: Boolean by mutableStateOf(false)
        private set

    /** 思考计时用的时钟，500ms 一跳（500ms 是因为 100ms 纯属浪费重组）。 */
    var clockMs: Long by mutableStateOf(0L)
        private set

    /** 输入栏上方的提示条。 */
    var banner: SystemSegment? by mutableStateOf(null)
        private set

    val tokenCommits: Int get() = buffer.commits

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var persistJob: Job? = null

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 开始新一轮。
     *
     * **必须清空时间线**：不清的话，上一轮的思考/工具块会留在列表里，
     * 而新问题被渲染在它们下面（因为「历史消息」在前、「当前时间线」在后），
     * 于是出现「新问题跑到上一个问题下面」。
     */
    /**
     * 一轮请求开始。
     *
     * ⚠️ **必须无条件重置本轮流水线**（缓冲、chunker、正文段 id）。
     *
     * 早先这里用 `if (started) return` 拦住重复调用，而 `bindTurn` 也会把
     * `started` 置 true —— 两者叠加的结果是：第二次发送时早退，于是
     * - 上一轮的「当前流式正文段」id 还留着，新正文被追加进**旧段**，
     *   「重新生成」看起来像「在刚刚的回答下面继续写」
     * - `TokenBuffer` 的已提交文本没有清空，正文里混进上一轮的结尾
     *
     * 重复的 `Started` 事件本身不需要防（重置是幂等的），所以直接去掉那个守卫。
     */
    override fun onStarted(round: Int) {
        started = true
        resetTurn()
        turnState = TurnState.Running
        timeline.apply(AgentEvent.Started(round))
        syncList()
        // 落一条 Running 占位：这样切后台被回收后，冷启动能知道「上次没跑完」。
        // 用 debounce 而不是立即写盘：`bindTurn` 刚刚写过一次同样的内容，
        // 这里再来一次纯属重复 IO。
        schedulePersist()
    }

    private fun resetTurn() {
        buffer.reset()
        chunker.reset()
        streamTail = ""
        closedBlocks = emptyList()
        streamInFence = false
        streamingAnswerId = null
        streaming = false
        hasAnswer = false
        banner = null
    }

    /**
     * 把本轮绑定到某条 AI 消息。
     *
     * 发送时调用：会话里已经先落了一条 `Running` 占位，这里把 id 记下来，
     * 之后所有过程进展都 upsert 到这一条上（**幂等**，不会堆出多条）。
     */
    fun bindTurn(sessionId: String, messageId: String) {
        // ⚠️ 必须**先清空**时间线再绑定。
        //
        // 早先只改两个 id 就返回，于是：
        // - 上一条消息的过程留在 [segments] 里，新问题的新增量被追加到它后面，
        //   表现就是「发一个问题会带上前几次的思考和回答」、「新会话也带着上个会话的内容」
        // - `onStarted` 因为 `started` 已是 true 而直接 return，新一轮的
        //   `placeholder` 与轮次都没重建
        // 这两件事合起来还会让「重新生成」变成「在旧回答下面继续写」。
        resetTurn()
        timeline.reset()
        segments = emptyList()
        this.sessionId = sessionId
        this.messageId = messageId
        started = false
        turnState = TurnState.Running
    }

    /**
     * 解绑。切会话 / 开新一轮前调用。
     *
     * **先冲刷再解绑**：解绑后就没法再写那条消息了，未落盘的进展会丢。
     */
    fun detachTurn() {
        if (sessionId != null && messageId != null) persistNow()
        // 刻意**不**清 groupPrefs / blockExpanded：
        // 两者的 key 都是全局唯一的 id，不会串台；而用户刚发完新问题时，
        // 正是他要回头翻上一条回答的时刻，此时丢掉展开态最不合时宜。
        sessionId = null
        messageId = null
        started = false
        turnState = TurnState.Done
    }

    /**
     * 只清内存，**不落盘**。
     *
     * 早先这里会 `persist?.invoke(emptyList(), "", Done)`，于是「清屏」会把空数据
     * 写到当前绑定的那条消息上——上一轮的过程就被自己抹掉了（实测：连跑两轮时
     * 第一轮的内容与时间线全空）。清屏是纯界面动作，不该有写副作用；
     * 真要丢弃内容由 [detachTurn] 之后的 `bindTurn` 覆盖来完成。
     */
    fun resetAll() {
        resetTurn()
        timeline.reset()
        segments = emptyList()
        autoScroll.reset()
    }

    /**
     * 从已落盘的一轮恢复（冷启动 / 切回会话）。
     *
     * [segments] 来自消息的 `segmentsJson`；[running] 为 true 表示这轮上次没跑完，
     * 界面据此渲染断点卡片。
     */
    fun restore(restored: List<Segment>, state: TurnState) {
        resetTurn()
        started = true
        turnState = state
        timeline.restore(restored)
        syncList()
    }

    // ------------------------------------------------------------------
    // 流式事件
    // ------------------------------------------------------------------

    override fun onThinking(round: Int, text: String) {
        timeline.apply(AgentEvent.ThinkingDelta(round, text))
        syncList()
        schedulePersist()
    }

    override fun onNarration(round: Int, text: String) {
        timeline.apply(AgentEvent.Narration(round, text))
        syncList()
        schedulePersist()
    }

    override fun onToolCall(round: Int, seq: Int, name: String, args: String?) {
        timeline.apply(AgentEvent.ToolCallStarted(round, seq, name, args))
        syncList()
        schedulePersist()
    }

    override fun onToolResult(
        round: Int,
        seq: Int,
        name: String,
        result: String,
        success: Boolean,
    ) {
        timeline.apply(AgentEvent.ToolCompleted(round, seq, name, result, success))
        syncList()
        schedulePersist()
    }

    override fun onToolRetried(round: Int, seq: Int, success: Boolean, result: String) {
        retryTool(round, seq, success, result)
    }

    override fun onToolNeedsApproval(
        round: Int,
        seq: Int,
        name: String,
        args: String?,
        risk: String,
    ) {
        timeline.apply(AgentEvent.ToolNeedsApproval(round, seq, name, args, risk))
        syncList()
        schedulePersist()
    }

    /**
     * 卡片上的「重试」：只重跑这一个工具，**不清空已经跑完的前面那些**。
     *
     * 与 [onToolResult] 分开是因为语义不同：[onToolResult] 把段标成 Success/Failed，
     * 而这里必须能标成 [ToolStatus.Timeout]（前端兜底超时），
     * 后者不是协议里的「结果」，是界面侧的判定。
     *
     * 返回结果不是给模型看的（工具调用不进消息历史，见 `ChatMessage.content` 的说明）；
     * 界面上的工具卡本身就是这次重试的产出。因此**不再**触发一轮模型续写 ——
     * 既然模型从来没看到过这些工具结果，把它叫回来「接着上次的想」只会得到
     * 一份上下文错位的回答。
     */
    fun retryTool(round: Int, seq: Int, success: Boolean, result: String) {
        timeline.apply(AgentEvent.ToolCompleted(round, seq, currentToolName(round, seq), result, success))
        syncList()
        persistNow()
    }

    /** 取某段当前的工具名；段不存在时给空串，事件本身仍能定位。 */
    private fun currentToolName(round: Int, seq: Int): String =
        segments.firstOrNull { it is ToolCallSegment && it.round == round && it.seq == seq }
            ?.let { (it as ToolCallSegment).toolName }
            .orEmpty()

    fun resolveApproval(round: Int, seq: Int, allowed: Boolean) {
        timeline.apply(AgentEvent.ToolApprovalResolved(round, seq, allowed))
        syncList()
        schedulePersist()
    }

    /**
     * 把某个工具卡标成「正在重试」。返回 false 表示时间线里没有这一段。
     */
    fun markToolRetrying(round: Int, seq: Int): Boolean {
        val ok = timeline.retryTool(round, seq)
        if (ok) {
            syncList()
            persistNow()
        }
        return ok
    }

    /**
     * 正文增量：先进 [TokenBuffer]，够条件才落到界面与磁盘。
     *
     * 首个正文 token 到达时安排一次「延时自动收起」（延迟 600ms 是为了避免
     * 刚冒出答案就立刻收起、用户还没看清思考摘要的抖动）。
     */
    override fun onDelta(text: String) {
        val first = !hasAnswer
        streaming = true
        hasAnswer = true
        if (first) scheduleAutoCollapse()
        val committed = buffer.offer(text) ?: return
        commitAnswer(committed)
    }

    override fun onFinished() {
        buffer.flush()?.let { commitAnswer(it) }
        streaming = false
        turnState = TurnState.Done
        timeline.apply(AgentEvent.Finished)
        syncList()
        persistNow()
    }

    override fun onInterrupted() {
        buffer.flush()?.let { commitAnswer(it) }
        streaming = false
        turnState = TurnState.Interrupted
        timeline.apply(AgentEvent.Interrupted)
        syncList()
        persistNow()
    }

    override fun onFailed(kind: SystemKind, message: String, retryAfterSeconds: Int) {
        buffer.flush()?.let { commitAnswer(it) }
        streaming = false
        turnState = TurnState.Failed
        timeline.apply(AgentEvent.Failed(kind, message, retryAfterSeconds))
        if (kind == SystemKind.Offline || kind == SystemKind.NetworkLost || kind == SystemKind.Stall) {
            banner = SystemSegment.new(kind, System.currentTimeMillis(), message, retryAfterSeconds)
        }
        syncList()
        persistNow()
    }

    override fun onAnomaly(kind: SystemKind, payload: String) {
        buffer.flush()?.let { commitAnswer(it) }
        streaming = false
        turnState = TurnState.Failed
        timeline.apply(AgentEvent.Anomaly(kind, payload))
        syncList()
        persistNow()
    }

    /**
     * 未配置模型就发消息。
     *
     * 这不是错误，是「还没完成必要的准备」——所以给 CTA 卡（进设置填 Key），
     * 而不是一段错误文字。
     */
    fun onNotConfigured() {
        if (!started) {
            started = true
            resetTurn()
        }
        streaming = false
        hasAnswer = false
        turnState = TurnState.Failed
        timeline.apply(AgentEvent.Failed(SystemKind.AuthRequired, "", 0))
        syncList()
        persistNow()
    }

    /** 答案到达后自动收起过程块（调用方负责延时触发）。 */
    fun autoCollapse() {
        if (timeline.autoCollapseProcess()) {
            syncList()
            schedulePersist()
        }
    }

    fun toggleThinking(id: String) {
        if (timeline.toggleThinking(id)) {
            syncList()
            schedulePersist()
        }
    }

    fun toggleToolDetail(id: String) {
        if (timeline.toggleToolDetail(id)) {
            syncList()
            schedulePersist()
        }
    }

    /**
     * 过程组的展开态。
     *
     * 只记**用户明确表过态**的组；没有记录的组走「组内还有在跑的块就展开」的默认值。
     * 这样既能「全部结束后自动收起」，又不会覆盖用户的手动选择。
     */
    private var groupPrefs by mutableStateOf(emptyMap<String, Boolean>())

    /**
     * 单个块（思考 / 工具卡）的展开态，按 **segment id** 记录。
     *
     * ## 为什么不能只用 timeline 里那个 `isExpanded`
     *
     * 早先展开态写在内存 timeline 的 segment 上。问题是：**只有当前这一轮**
     * 才由 timeline 承载，历史轮次的段落是每次渲染时从 `segmentsJson`
     * 重新解码出来的**副本**——点它改的是副本，下一帧又被覆盖回去，
     * 用户看到的就是「回答完成后工具和思考内容无法展开」（实测反馈）。
     *
     * 改成一个按 id 索引的偏好表后，live 与历史轮次走同一条路径，
     * 行为一致，也不需要为了纯 UI 状态去回写磁盘。
     *
     * 不清空它：segment id 全局唯一，不会串台；而用户在翻旧回答时，
     * 展开态本就应该留到他离开这个页面为止。
     */
    private var blockExpanded by mutableStateOf(emptyMap<String, Boolean>())

    /** 块是否展开；[fallback] 是落盘/解码时带的值。 */
    fun isBlockExpanded(segmentId: String, fallback: Boolean): Boolean =
        blockExpanded[segmentId] ?: fallback

    /**
     * 用户点了某个块的展开/收起。
     *
     * 同时通知 timeline（若这一段正在 timeline 里）：`userToggled` 需要被置位，
     * 否则「答案到达后自动收起」会把用户刚展开的块又收起来。
     */
    fun toggleBlockExpanded(segmentId: String, currentlyExpanded: Boolean) {
        val next = !currentlyExpanded
        blockExpanded = blockExpanded + (segmentId to next)
        timeline.setExpanded(segmentId, next)
        syncList()
    }

    /** 组是否展开。[autoExpanded] 是用户没表过态时的默认值。 */
    fun isGroupExpanded(groupId: String, autoExpanded: Boolean): Boolean =
        groupPrefs[groupId] ?: autoExpanded

    /** 用户点了组头。记录选择后不再被默认值影响。 */
    fun toggleGroup(groupId: String, currentlyExpanded: Boolean) {
        groupPrefs = groupPrefs + (groupId to !currentlyExpanded)
    }

    /** 本轮全部正文段拼起来，供「复制全文」。 */
    fun answerText(): String =
        segments.filterIsInstance<AnswerSegment>()
            .joinToString("\n\n") { it.markdown }
            .trim()

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun commitAnswer(text: String) {
        timeline.apply(AgentEvent.AnswerDelta(text))
        // 段可能是新 append 的，所以要在 apply 之后再取 id
        streamingAnswerId = timeline.snapshot()
            .lastOrNull { it is AnswerSegment && it.isStreaming }
            ?.id
        chunker.append(text)
        val out = chunker.current()
        closedBlocks = out.closedBlocks
        streamTail = out.tail
        streamInFence = out.tailInOpenFence
        syncList()
        schedulePersist()
    }

    private fun syncList() {
        // 整体替换不可变列表：避免 LazyColumn 观察到中间态而越界
        segments = timeline.snapshot()
    }

    private fun scheduleAutoCollapse() {
        scope.launch {
            delay(AUTO_COLLAPSE_DELAY_MS)
            autoCollapse()
        }
    }

    /**
     * 节流落盘。
     *
     * 每次 token 都写文件会打爆 IO；但也不能不写——进程随时可能被杀。
     * 500ms 是一个折中：最坏丢 500ms 的过程，换掉每帧一次的文件写入。
     */
    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            persistNow()
        }
    }

    /** 立即落盘。收尾、中断、失败、`onStop` 时调用。 */
    fun persistNow() {
        persistJob?.cancel()
        persist?.invoke(segments, answerText(), turnState)
    }

    /** 页面进后台：强制同步落盘（`onStop` 语义）。 */
    fun onBackground() {
        persistNow()
    }

    /**
     * 时钟与帧对齐的驱动器。
     *
     * 每 500ms 更新一次思考计时；流式期间每帧对齐一次 vsync，
     * 避免网络突发导致同帧多次重组。
     */
    @Composable
    internal fun Drive() {
        if (LocalInspectionMode.current) return
        LaunchedEffect(streaming) {
            while (streaming) {
                androidx.compose.runtime.withFrameMillis { }
            }
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(CLOCK_INTERVAL_MS)
                clockMs = System.currentTimeMillis()
                if (timeline.tickElapsed(clockMs)) {
                    syncList()
                    schedulePersist()
                }
            }
        }
    }

    private companion object {
        const val CLOCK_INTERVAL_MS = 500L

        /** 答案首 token 到达后，延时多久自动收起过程块。 */
        const val AUTO_COLLAPSE_DELAY_MS = 600L

        /** 落盘节流。 */
        const val PERSIST_DEBOUNCE_MS = 500L
    }
}

@Composable
fun rememberAgentChatState(): AgentChatState {
    val state = remember { AgentChatState() }
    // 把自身注册为流式事件的落点：ChatRunner 在 app 模块，只认识 AgentStreamSink 接口。
    DisposableEffect(state) {
        AgentStreamBus.sink = state
        onDispose {
            if (AgentStreamBus.sink === state) AgentStreamBus.sink = null
        }
    }
    state.Drive()
    return state
}

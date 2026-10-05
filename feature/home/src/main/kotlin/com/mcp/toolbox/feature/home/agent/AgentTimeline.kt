package com.mcp.toolbox.feature.home.agent

/**
 * 把扁平的事件流归约成有序 Segment 列表（纯函数，可单测）。
 *
 * ## 关键约定
 *
 * 1. **顺序**：同一轮里思考 → 旁白 → 工具卡 → 下一轮思考，靠 [AgentEvent.ThinkingDelta]
 *    携带的 `round` 落位；工具卡按 `(round, seq)` 定位，不靠到达顺序。
 * 2. **乱序容错**：`tool_result` 先到而 `tool_call` 后到时，先按 result 建段并标完成；
 *    随后到达的 call **合并进同一段**而不是再插一张卡。
 * 3. **缺失 tool_result**：整轮收尾（[AgentEvent.Finished]）时仍是 Running 的工具
 *    卡会被强制标记为 [ToolStatus.Timeout]——**绝不允许无限转圈**（Spec 第 7.1 节）。
 * 4. **独立性**：每个 Segment 的展开态与 `userToggled` 只由自己承载；
 *    唯一的全局联动是 [autoCollapseProcess]，且它跳过 `userToggled == true` 的段。
 */
class AgentTimeline {

    private val items = mutableListOf<Segment>()

    /** 工具段的定位索引：`round to seq` → items 下标。 */
    private val toolIndex = mutableMapOf<Pair<Int, Int>, Int>()

    /** 当前轮次，用于 [AgentEvent.AnswerDelta] 这类不带 round 的事件。 */
    private var currentRound = 0

    /** 本轮是否已经产生过正文，用于区分「旁白」与「最终回答」。 */
    private var sawAnswer = false

    /**
     * 已废弃的「乐观占位卡」id 字段。
     *
     * 占位改由「消息先落一条 Running 记录」承担（见 `AgentEvent.Started`），
     * 这里保留为 null 只是为了不动 `dropPlaceholder()` 的调用点。
     */
    private var placeholderId: String? = null

    /** 是否已收到过任何 [AgentEvent.Started]。 */
    var started = false
        private set

    fun snapshot(): List<Segment> = items.toList()

    fun segment(id: String): Segment? = items.firstOrNull { it.id == id }

    /**
     * 清空整条时间线，准备接纳新一轮。
     *
     * **必须做**。工具的定位索引是 `(round, seq)`，而 `ChatRunner` 每次请求都从
     * `round = 0` 重新计数；不清空的话，第二个问题的第 0 轮第 0 号工具会**命中
     * 第一个问题留下的那张卡**，只更新旧卡、不新建——表现就是「第二个问题没有工具界面」。
     */
    fun reset() {
        items.clear()
        toolIndex.clear()
        currentRound = 0
        sawAnswer = false
        started = false
        placeholderId = null
    }

    /**
     * 用已落盘的时间线恢复（冷启动 / 切回会话）。
     *
     * 恢复后**不重建工具索引**：已完成的轮次不会再收到事件，
     * 后续新事件一律走 appendNew，不会误命中历史卡片。
     */
    fun restore(segments: List<Segment>) {
        reset()
        items.addAll(segments)
        started = true
        currentRound = segments.filterIsInstance<ThinkingSegment>().maxOfOrNull { it.round } ?: 0
        sawAnswer = segments.any { it is AnswerSegment && it.markdown.isNotEmpty() }
    }

    // ------------------------------------------------------------------
    // 归约
    // ------------------------------------------------------------------

    /** 应用一个事件，返回是否真的改变了内容（false 表示可以跳过重组）。 */
    fun apply(event: AgentEvent): Boolean = when (event) {
        is AgentEvent.Started -> {
            started = true
            currentRound = event.round
            sawAnswer = false
            // 刻意**不**预建空的 AnswerSegment。
            //
            // 「乐观渲染」现在由「消息本身先落一条 Running 记录」承担——消息就是占位。
            // 再建一个空 segment 只会带来两件坏事：
            // 1. 请求中途死掉时留下「空正文段」，界面渲染成一个空气泡
            // 2. 让它成为「最后一段流式正文」，抢走真正正文该落的位置
            // 正文段改为在首个 [AgentEvent.AnswerDelta] 到达时惰性创建。
            true
        }

        is AgentEvent.ThinkingDelta -> {
            if (event.text.isEmpty()) return false
            dropPlaceholder()
            // 进入新一轮 ⇒ 之前所有还在 Running 的思考都已经结束了。
            // 不封印的话，它们的计时会一直涨到整轮 Finished 为止
            //（实测：多轮工具调用时，第一个思考胶囊的 00:37 一直在涨）。
            sealThinking(round = event.round, includeSameRound = false)
            currentRound = maxOf(currentRound, event.round)
            val index = items.indexOfLast {
                it is ThinkingSegment && it.round == event.round && it.isRunning
            }
            if (index >= 0) {
                val old = items[index] as ThinkingSegment
                items[index] = old.copy(text = old.text + event.text)
            } else {
                appendNew(
                    ThinkingSegment(
                        id = newSegmentId("think"),
                        createdAt = now(),
                        text = event.text,
                        round = event.round,
                    ),
                )
            }
            true
        }

        /**
         * 过渡旁白。
         *
         * ## 为什么不能直接 append 一段
         *
         * 模型在工具轮里也会输出正文（「我先跑几个轻量调用…」），而网络层是**逐 token
         * 走 `onDelta`** 推给界面的——那时还不知道这一轮会不会调用工具。所以界面会
         * 先建出一个 `AnswerSegment`，等这一轮结束才收到 narration，得知「刚才那段
         * 其实是过渡语」。若这里再 append 一个 `NarrationSegment`，就会出现：
         *
         * - 过渡正文先占住一个正文段 → **排到所有工具卡之前**
         * - 最终回答被追加进**同一个**正文段 → 与过渡正文拼在一起
         * - 过渡正文又作为旁白独立出现一次 → **同一句话显示两遍**
         *
         * 正确做法是**原地改造**：把那个刚建的、还在流式的正文段换成旁白段。
         * 位置不变（仍在同轮工具卡之前），内容不重复。
         */
        is AgentEvent.Narration -> {
            if (event.text.isBlank()) return false
            dropPlaceholder()
            // 同一轮里由 onDelta 抢建出来的过渡正文段
            val provisional = items.indexOfLast {
                it is AnswerSegment && it.isStreaming && it.markdown.isNotBlank()
            }
            if (provisional >= 0) {
                val old = items[provisional] as AnswerSegment
                items[provisional] = NarrationSegment(
                    id = old.id,
                    createdAt = old.createdAt,
                    text = old.markdown,
                    round = event.round,
                )
            } else {
                appendNew(
                    NarrationSegment(
                        id = newSegmentId("narr"),
                        createdAt = now(),
                        text = event.text,
                        round = event.round,
                    ),
                )
            }
            // 思考已经产出完毕：这一轮后面紧跟工具调用，计时到此为止
            sealThinking(round = event.round, includeSameRound = true)
            true
        }

        is AgentEvent.ToolCallStarted -> sealThinking(event.round, includeSameRound = true).let {
            upsertTool(event.round, event.seq) { existing ->
            if (existing == null) {
                ToolCallSegment(
                    id = newSegmentId("tool"),
                    createdAt = now(),
                    toolName = event.name,
                    displayName = ToolVisuals.displayName(event.name),
                    iconRes = ToolVisuals.icon(event.name),
                    round = event.round,
                    seq = event.seq,
                    argsJson = event.argsJson,
                    status = ToolStatus.Running,
                )
            } else {
                // 结果先到、调用后到：合并进已有段，保留它已经算出的状态与结果
                existing.copy(
                    toolName = event.name,
                    displayName = ToolVisuals.displayName(event.name),
                    iconRes = ToolVisuals.icon(event.name),
                    argsJson = event.argsJson ?: existing.argsJson,
                    status = if (existing.endedAt != null) existing.status else ToolStatus.Running,
                )
            }
        } }

        is AgentEvent.ToolCompleted -> dropPlaceholder().let { upsertTool(event.round, event.seq) { existing ->
            val base = existing ?: ToolCallSegment(
                id = newSegmentId("tool"),
                createdAt = now(),
                toolName = event.name,
                displayName = ToolVisuals.displayName(event.name),
                iconRes = ToolVisuals.icon(event.name),
                round = event.round,
                seq = event.seq,
                startedAt = now(),
            )
            val failed = !event.success
            base.copy(
                status = if (failed) ToolStatus.Failed else ToolStatus.Success,
                endedAt = now(),
                result = ToolVisuals.buildResult(
                    toolName = event.name,
                    raw = event.result,
                    success = !failed,
                    sources = event.sources,
                ),
            )
        } }

        is AgentEvent.ToolNeedsApproval -> dropPlaceholder().let { upsertTool(event.round, event.seq) { existing ->
            val base = existing ?: ToolCallSegment(
                id = newSegmentId("tool"),
                createdAt = now(),
                toolName = event.name,
                displayName = ToolVisuals.displayName(event.name),
                iconRes = ToolVisuals.icon(event.name),
                round = event.round,
                seq = event.seq,
            )
            // 审批卡不参与自动收起，所以直接置 userToggled
            base.copy(
                status = ToolStatus.Pending,
                argsJson = event.argsJson,
                awaitingApproval = true,
                risk = event.risk,
                isDetailExpanded = true,
                userToggled = true,
            )
        } }

        is AgentEvent.ToolApprovalResolved -> upsertTool(event.round, event.seq) { existing ->
            if (existing == null) return@upsertTool null
            val base = existing
            base.copy(
                status = if (event.allowed) ToolStatus.Running else ToolStatus.Denied,
                awaitingApproval = false,
                startedAt = if (event.allowed) now() else base.startedAt,
                endedAt = if (event.allowed) null else now(),
                result = ToolResult(summary = if (event.allowed) "已允许" else "已拒绝"),
            )
        }

        is AgentEvent.AnswerDelta -> {
            if (event.text.isEmpty()) return false
            sawAnswer = true
            // 最终正文开始 ⇒ 不可能再有新的思考段落了，全部封印
            sealThinking(round = Int.MAX_VALUE, includeSameRound = false)
            /**
             * 最终正文的处理规则：**只追加到「末尾那段就是流式正文」时**，
             * 否则另起一段落在最末。
             *
             * 这条规则同时解决两件事：
             * - 工具轮里的过渡正文已经被改成旁白段，这里不会再把最终回答拼进它
             * - 即使某轮没有 narration 事件而残留了一个流式正文段，只要它不在末尾
             *   （前面有工具卡），也**不会**被复用——否则最终回答会排到工具卡前面
             */
            val last = items.lastOrNull()
            if (last is AnswerSegment && last.isStreaming) {
                items[items.lastIndex] = last.copy(markdown = last.markdown + event.text)
            } else {
                appendNew(
                    AnswerSegment(
                        id = newSegmentId("ans"),
                        createdAt = now(),
                        markdown = event.text,
                    ),
                )
            }
            placeholderId = null
            true
        }

        AgentEvent.Finished -> {
            var changed = false
            // 整轮收尾：所有还在 Running 的思考一律封印并冻结计时
            if (sealThinking(round = Int.MAX_VALUE, includeSameRound = true)) changed = true
            for (i in items.indices) {
                val seg = items[i]
                // 仍在 Running 的工具：前端兜底判超时，而不是继续转圈
                if (seg is ToolCallSegment && seg.isRunning) {
                    items[i] = seg.copy(status = ToolStatus.Timeout, endedAt = now())
                    changed = true
                }
                // 思考的封印交给 sealThinking（它同时会冻结计时），这里不重复处理
                if (seg is AnswerSegment && seg.isStreaming) {
                    items[i] = seg.copy(status = RunStatus.Done)
                    changed = true
                }
                if (seg is NarrationSegment) {
                    items[i] = seg
                }
            }
            changed
        }

        AgentEvent.Interrupted -> {
            var changed = false
            for (i in items.indices) {
                when (val seg = items[i]) {
                    is ThinkingSegment ->
                        if (seg.isRunning) {
                            items[i] = seg.copy(
                                status = RunStatus.Interrupted,
                                // 中断也要冻结：否则「已停止」的胶囊数字仍在涨
                                elapsedMs = (now() - seg.createdAt).coerceAtLeast(seg.elapsedMs),
                            )
                            changed = true
                        }

                    is ToolCallSegment ->
                        if (seg.isRunning) {
                            items[i] = seg.copy(status = ToolStatus.Failed, endedAt = now())
                            changed = true
                        }

                    is AnswerSegment ->
                        if (seg.isStreaming) {
                            items[i] = seg.copy(status = RunStatus.Interrupted)
                            changed = true
                        }

                    else -> Unit
                }
            }
            changed
        }

        is AgentEvent.Failed -> appendNew(
            SystemSegment(
                id = newSegmentId("sys"),
                createdAt = now(),
                kind = event.kind,
                payload = event.message,
                retryAfterSeconds = event.retryAfterSeconds,
            ),
        )

        is AgentEvent.Anomaly -> appendNew(
            SystemSegment(
                id = newSegmentId("sys"),
                createdAt = now(),
                kind = event.kind,
                payload = event.payload,
                preservedChars = event.payload.length,
            ),
        )
    }

    // ------------------------------------------------------------------
    // 允许的唯一全局联动
    // ------------------------------------------------------------------

    /**
     * 答案首 token 到达后（延时 600ms 触发，避免抖动）自动收起全部过程块。
     *
     * 只留一行「已思考 24s · 3 次工具调用」摘要 chip。
     * **跳过 `userToggled == true` 的段**——用户手动展开过就不再自动收，尊重用户。
     */
    fun autoCollapseProcess(): Boolean {
        var changed = false
        for (i in items.indices) {
            when (val seg = items[i]) {
                is ThinkingSegment ->
                    if (!seg.userToggled && seg.isExpanded) {
                        items[i] = seg.copy(isExpanded = false)
                        changed = true
                    }

                is ToolCallSegment ->
                    if (!seg.userToggled && seg.isDetailExpanded) {
                        items[i] = seg.copy(isDetailExpanded = false)
                        changed = true
                    }

                else -> Unit
            }
        }
        return changed
    }

    /**
     * 直接设定某一段的展开态（同时置位 `userToggled`）。
     *
     * 供界面层的「按 id 记录的展开偏好」调用：界面才是展开态的唯一来源，
     * 这里只负责让自动收起逻辑知道「用户动过手了」。
     */
    fun setExpanded(id: String, expanded: Boolean): Boolean {
        val i = items.indexOfFirst { it.id == id }
        when (val seg = items.getOrNull(i)) {
            is ThinkingSegment -> items[i] = seg.copy(isExpanded = expanded, userToggled = true)
            is ToolCallSegment -> if (!seg.awaitingApproval) {
                items[i] = seg.copy(isDetailExpanded = expanded, userToggled = true)
            }

            else -> return false
        }
        return true
    }

    /** 用户切换思考块展开态。切换即置 `userToggled`，此后不再自动收展。 */
    fun toggleThinking(id: String): Boolean {
        val i = items.indexOfFirst { it.id == id }
        val seg = items.getOrNull(i) as? ThinkingSegment ?: return false
        items[i] = seg.copy(isExpanded = !seg.isExpanded, userToggled = true)
        return true
    }

    /** 用户切换工具卡详情展开态。审批卡不受影响（它恒为展开）。 */
    fun toggleToolDetail(id: String): Boolean {
        val i = items.indexOfFirst { it.id == id }
        val seg = items.getOrNull(i) as? ToolCallSegment ?: return false
        items[i] = seg.copy(isDetailExpanded = !seg.isDetailExpanded, userToggled = true)
        return true
    }

    /** 思考计时。只更新仍 Running 的段，Done/Interrupted 的耗时就此冻结。 */
    fun tickElapsed(now: Long): Boolean {
        var changed = false
        for (i in items.indices) {
            val seg = items[i]
            if (seg is ThinkingSegment && seg.isRunning) {
                items[i] = seg.copy(elapsedMs = now - seg.createdAt)
                changed = true
            }
        }
        return changed
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /**
     * 把已产出完毕的思考段封印成 `Done` 并冻结计时。
     *
     * ## 为什么要「轮次级」封印，而不是等整轮 Finished
     *
     * 一次回答常常是「思考 → 工具 → 思考 → 工具 → … → 正文」。
     * 早先只在整轮 `Finished` 时才把 `Running` 的思考标成 `Done`，
     * 于是多轮工具调用期间，**前面那些早就想完的思考段仍然在计时**，
     * 界面上表现为「一个已经显示对勾的胶囊，数字一直在涨」。
     *
     * 正确的结束信号是**轮次边界**：
     * - 同轮出现旁白 / 工具调用 ⇒ 这轮思考已产出完毕
     * - 出现下一轮思考、或最终正文开始 ⇒ 更早的思考必然已结束
     *
     * @param includeSameRound 是否连同 [round] 这一轮一起封印
     *   （同轮出现工具调用时为 true）
     */
    private fun sealThinking(round: Int, includeSameRound: Boolean): Boolean {
        var changed = false
        for (i in items.indices) {
            val seg = items[i] as? ThinkingSegment ?: continue
            if (!seg.isRunning) continue
            val shouldSeal = if (includeSameRound) seg.round <= round else seg.round < round
            if (!shouldSeal) continue
            items[i] = seg.copy(
                status = RunStatus.Done,
                // 用最后一次 tick 的时长；若从未 tick 过则至少给出创建到现在的间隔
                elapsedMs = (now() - seg.createdAt).coerceAtLeast(seg.elapsedMs),
            )
            changed = true
        }
        return changed
    }

    /**
     * 让乐观占位卡退场。
     *
     * 只在它**还是空的**时候移除：一旦它已经装进了正文，说明那就是正文该待的位置。
     */
    private fun dropPlaceholder() {
        val id = placeholderId ?: return
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) {
            val seg = items[i] as? AnswerSegment
            if (seg != null && seg.markdown.isEmpty()) items.removeAt(i)
        }
        placeholderId = null
    }

    private fun appendNew(segment: Segment): Boolean {
        if (segment is ToolCallSegment) toolIndex[segment.round to segment.seq] = items.size
        items += segment
        return true
    }

    /**
     * 按 `(round, seq)` 就地更新工具段；不存在时先创建再交给 [transform]。
     *
     * 这套 upsert 是「乱序容错」的核心：事件到达顺序与逻辑顺序解耦，
     * 同一次工具调用无论 call/result 谁先到，最终只会有一张卡。
     */
    private fun upsertTool(
        round: Int,
        seq: Int,
        transform: (ToolCallSegment?) -> ToolCallSegment?,
    ): Boolean {
        val key = round to seq
        val existingIndex = toolIndex[key]
        val existing = existingIndex?.let { items.getOrNull(it) as? ToolCallSegment }
        val next = transform(existing) ?: return false
        return if (existingIndex != null && existingIndex < items.size) {
            items[existingIndex] = next
            true
        } else {
            appendNew(next)
        }
    }

    private fun now(): Long = System.currentTimeMillis()
}

package com.mcp.toolbox.feature.home.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentTimeline] 的归约行为验收。
 *
 * 重点覆盖 Spec 第 15 节 P1 要求的三类边界：
 * **乱序事件**、**缺失 tool_result**、**状态隔离**。
 */
class AgentTimelineTest {

    @Test
    fun `Started 不再预建空回答段，正文惰性创建`() {
        // 回归测试：早先 Started 会先插一张空 AnswerSegment 当占位，
        // 请求中途死掉时这个空段就留在消息里，界面渲染成一个空气泡。
        // 现在占位由「消息本身先落一条 Running 记录」承担，段是惰性的。
        val t = AgentTimeline()
        t.apply(AgentEvent.Started(round = 1))
        assertTrue("Started 不应产生任何段", t.snapshot().isEmpty())

        t.apply(AgentEvent.AnswerDelta("正文"))
        val segs = t.snapshot()
        assertEquals(1, segs.size)
        assertEquals("正文", (segs.single() as AnswerSegment).markdown)
    }

    @Test
    fun `只有过程没有正文时不会留下空正文段`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started(round = 1))
        t.apply(AgentEvent.ThinkingDelta(round = 1, text = "想了"))
        t.apply(AgentEvent.ToolCallStarted(round = 1, seq = 0, name = "device.info"))
        t.apply(AgentEvent.ToolCompleted(round = 1, seq = 0, name = "device.info", result = "ok"))
        t.apply(AgentEvent.Finished)
        // 完全没有 AnswerSegment
        assertTrue(t.snapshot().none { it is AnswerSegment })
    }

    @Test
    fun `思考与正文按 round 分流`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started(round = 1))
        t.apply(AgentEvent.ThinkingDelta(round = 1, text = "先看看"))
        t.apply(AgentEvent.ThinkingDelta(round = 1, text = "再想想"))
        t.apply(AgentEvent.ToolCallStarted(round = 1, seq = 1, name = "file.list"))
        t.apply(AgentEvent.ToolCompleted(round = 1, seq = 1, name = "file.list", result = "3 个文件"))
        t.apply(AgentEvent.ThinkingDelta(round = 2, text = "换个方向"))
        t.apply(AgentEvent.AnswerDelta("结论如下"))
        t.apply(AgentEvent.Finished)

        val segs = t.snapshot()
        // 顺序：思考1 → 工具1 → 思考2 → 正文
        val types = segs.map { it::class.simpleName }
        assertEquals(
            listOf("ThinkingSegment", "ToolCallSegment", "ThinkingSegment", "AnswerSegment"),
            types,
        )
        // 两轮思考各自独立成段
        val th = segs.filterIsInstance<ThinkingSegment>()
        assertEquals(2, th.size)
        assertEquals("先看看再想想", th[0].text)
        assertEquals("换个方向", th[1].text)
        // 第 2 轮思考标题不同，避免重复感
        assertEquals("深度思考", th[0].title)
        assertEquals("继续思考 · 第 2 轮", th[1].title)
    }

    @Test
    fun `乱序——tool_result 先到再收到 tool_call，也只生成一张卡`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.ToolCompleted(round = 1, seq = 1, name = "web.fetch", result = "ok"))
        t.apply(AgentEvent.ToolCallStarted(round = 1, seq = 1, name = "web.fetch", argsJson = "{}"))

        val tools = t.snapshot().filterIsInstance<ToolCallSegment>()
        assertEquals(1, tools.size)
        val tool = tools.single()
        // 已经算出来的完成状态不能被后到的 call 覆盖回去
        assertEquals(ToolStatus.Success, tool.status)
        assertEquals("{}", tool.argsJson)
        assertNotNull(tool.endedAt)
    }

    @Test
    fun `缺失 tool_result——Finished 时强制判超时而不是一直转圈`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.ToolCallStarted(round = 1, seq = 1, name = "linux.shell"))
        // 模型没有再回 tool_result，直接收尾
        t.apply(AgentEvent.Finished)

        val tool = t.snapshot().filterIsInstance<ToolCallSegment>().single()
        assertEquals(ToolStatus.Timeout, tool.status)
        assertFalse(tool.isRunning)
    }

    @Test
    fun `中断时未完成的工具标失败、正文定格`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.ThinkingDelta(round = 1, text = "思考中"))
        t.apply(AgentEvent.ToolCallStarted(round = 1, seq = 1, name = "db.query"))
        t.apply(AgentEvent.AnswerDelta("已生成一半"))
        t.apply(AgentEvent.Interrupted)

        val segs = t.snapshot()
        // 思考在「同轮出现工具调用」时就已经封印为 Done —— 它的**计时必须停在那里**，
        // 不能等整轮 Interrupted 才收（否则一个早就想完的胶囊会一直涨秒）。
        assertEquals(RunStatus.Done, segs.filterIsInstance<ThinkingSegment>().single().status)
        assertEquals(ToolStatus.Failed, segs.filterIsInstance<ToolCallSegment>().single().status)
        val ans = segs.filterIsInstance<AnswerSegment>().last()
        assertEquals(RunStatus.Interrupted, ans.status)
        // 已生成内容不丢
        assertEquals("已生成一半", ans.markdown)
    }

    @Test
    fun `自动收起只影响未干预过的段`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.ThinkingDelta(round = 1, text = "A"))
        t.apply(AgentEvent.ToolCallStarted(round = 1, seq = 1, name = "file.list"))
        t.apply(AgentEvent.ToolCompleted(round = 1, seq = 1, name = "file.list", result = "x"))

        // 用户手动展开了思考块 A
        val thinkingId = t.snapshot().filterIsInstance<ThinkingSegment>().single().id
        t.toggleThinking(thinkingId)
        // 同时把工具卡也手动展开
        val toolId = t.snapshot().filterIsInstance<ToolCallSegment>().single().id
        t.toggleToolDetail(toolId)

        // 答案到达 → 自动收起
        val changed = t.autoCollapseProcess()
        assertFalse("用户干预过的段不应被自动收起", changed)

        val segs = t.snapshot()
        assertTrue(segs.filterIsInstance<ThinkingSegment>().single().isExpanded)
        assertTrue(segs.filterIsInstance<ToolCallSegment>().single().isDetailExpanded)
    }

    @Test
    fun `未干预的段会被自动收起`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.ThinkingDelta(round = 1, text = "A"))
        // 通过 toggle 展开（同时置 userToggled）
        val id = t.snapshot().filterIsInstance<ThinkingSegment>().single().id
        t.toggleThinking(id)
        // 再 toggle 回去，等价于「用户点开又点收」——userToggled 仍为 true
        t.toggleThinking(id)
        val seg = t.snapshot().filterIsInstance<ThinkingSegment>().single()
        assertFalse(seg.isExpanded)
        assertTrue(seg.userToggled)
    }

    @Test
    fun `审批卡不参与自动收起`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(
            AgentEvent.ToolNeedsApproval(
                round = 1, seq = 1, name = "file.delete",
                argsJson = "{}", risk = "将永久删除 /sdcard/a.txt",
            ),
        )
        assertFalse(t.autoCollapseProcess())
        val tool = t.snapshot().filterIsInstance<ToolCallSegment>().single()
        assertTrue(tool.awaitingApproval)
        assertTrue(tool.isDetailExpanded)
        assertTrue(tool.userToggled)
    }

    @Test
    fun `审批通过转 Running、拒绝转 Denied`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.ToolNeedsApproval(1, 1, "file.delete", "{}", "风险"))

        t.apply(AgentEvent.ToolApprovalResolved(round = 1, seq = 1, allowed = true))
        assertEquals(ToolStatus.Running, t.snapshot().filterIsInstance<ToolCallSegment>().single().status)

        t.apply(AgentEvent.ToolApprovalResolved(round = 1, seq = 1, allowed = false))
        assertEquals(ToolStatus.Denied, t.snapshot().filterIsInstance<ToolCallSegment>().single().status)
    }

    @Test
    fun `有过程块时乐观占位卡让位，正文落在末尾`() {
        // 回归测试：占位卡若不让位，第一段思考/工具会排在它下面，
        // 最终正文还会被写进那张位置错误的卡里。
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.ThinkingDelta(round = 1, text = "先想"))
        t.apply(AgentEvent.ToolCallStarted(round = 1, seq = 1, name = "file.list"))
        t.apply(AgentEvent.ToolCompleted(round = 1, seq = 1, name = "file.list", result = "ok"))
        t.apply(AgentEvent.AnswerDelta("答案"))

        val segs = t.snapshot()
        assertEquals(
            listOf("ThinkingSegment", "ToolCallSegment", "AnswerSegment"),
            segs.map { it::class.simpleName },
        )
        assertEquals("答案", segs.filterIsInstance<AnswerSegment>().single().markdown)
        // 无论有没有过程块，正文都只有一个段（不会出现空段+正文段两个）
        assertEquals(1, segs.filterIsInstance<AnswerSegment>().size)
    }

    @Test
    fun `纯正文回答也只有一个正文段`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.AnswerDelta("直接回答"))
        val segs = t.snapshot()
        assertEquals(1, segs.size)
        assertEquals("直接回答", (segs.single() as AnswerSegment).markdown)
    }

    @Test
    fun `失败收敛成系统卡片而不是空分支`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.Failed(SystemKind.RateLimited, message = "请求过于频繁", retryAfterSeconds = 12))

        val sys = t.snapshot().filterIsInstance<SystemSegment>().single()
        assertEquals(SystemKind.RateLimited, sys.kind)
        assertEquals(12, sys.retryAfterSeconds)
    }

    @Test
    fun `计时只更新仍在运行的思考段`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.ThinkingDelta(round = 1, text = "A"))
        assertTrue(t.tickElapsed(System.currentTimeMillis()))

        t.apply(AgentEvent.Finished)
        // 收尾后思考段已 Done，计时冻结
        assertFalse(t.tickElapsed(System.currentTimeMillis() + 5000))
    }

    // ------------------------------------------------------------------
    // 本轮修复的回归测试
    // ------------------------------------------------------------------

    @Test
    fun `工具轮的过渡正文不算最终回答`() {
        // 回归：早先 AiChatClient 对每一轮都发 onDelta，工具轮的正文因此
        // 先落进正文区，随后工具卡被追加在它下面（用户截图里的错位）。
        // 现在工具轮的正文只走 Narration。
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.ThinkingDelta(round = 1, text = "看一下"))
        t.apply(AgentEvent.Narration(round = 1, text = "我先跑个轻量调用"))
        t.apply(AgentEvent.ToolCallStarted(round = 1, seq = 0, name = "device.info"))
        t.apply(AgentEvent.ToolCompleted(round = 1, seq = 0, name = "device.info", result = "ok"))
        t.apply(AgentEvent.AnswerDelta("结论如下"))
        t.apply(AgentEvent.Finished)

        assertEquals(
            listOf("ThinkingSegment", "NarrationSegment", "ToolCallSegment", "AnswerSegment"),
            t.snapshot().map { it::class.simpleName },
        )
        val answers = t.snapshot().filterIsInstance<AnswerSegment>()
        assertEquals(1, answers.size)
        assertEquals("结论如下", answers.single().markdown)
    }

    @Test
    fun `已完成的思考不再继续计时`() {
        // 回归：用户截图里的 00:37 —— 一个已显示对勾的思考胶囊还在涨秒。
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.ThinkingDelta(round = 1, text = "第一轮思考"))
        t.apply(AgentEvent.ToolCallStarted(round = 1, seq = 0, name = "device.info"))
        val sealed = t.snapshot().filterIsInstance<ThinkingSegment>().single().elapsedMs

        assertFalse(t.tickElapsed(System.currentTimeMillis() + 37_000))
        assertEquals(sealed, t.snapshot().filterIsInstance<ThinkingSegment>().single().elapsedMs)
    }

    @Test
    fun `多轮思考各自独立停表`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.ThinkingDelta(round = 1, text = "第一轮"))
        t.apply(AgentEvent.ToolCallStarted(round = 1, seq = 0, name = "a"))
        t.apply(AgentEvent.ToolCompleted(round = 1, seq = 0, name = "a", result = "ok"))
        t.apply(AgentEvent.ThinkingDelta(round = 2, text = "第二轮"))

        val thinks = t.snapshot().filterIsInstance<ThinkingSegment>()
        assertEquals(2, thinks.size)
        assertEquals(RunStatus.Done, thinks[0].status)
        assertEquals(RunStatus.Running, thinks[1].status)

        t.apply(AgentEvent.AnswerDelta("答案"))
        assertEquals(
            RunStatus.Done,
            t.snapshot().filterIsInstance<ThinkingSegment>()[1].status,
        )
        assertFalse(t.tickElapsed(System.currentTimeMillis() + 10_000))
    }

    @Test
    fun `Started 重复触发是幂等的`() {
        // 回归：早先 onStarted 用 `if (started) return` 拦截，而 bindTurn 也会把
        // started 置 true —— 第二次发送时早退，上一轮的「当前流式正文段」id 残留，
        // 新正文被追加进旧段（看起来像「重新生成变成了在下面继续写」）。
        val t = AgentTimeline()
        // 重复的 Started 不产生任何段（不预建占位）
        t.apply(AgentEvent.Started())
        t.apply(AgentEvent.Started())
        assertTrue(t.snapshot().isEmpty())
        // 同一轮内再收到 Started 也**不清**已有时间线：
        // 「清空」是 bindTurn（跨轮）的职责，放在这里会把同轮已收到的
        // 思考/工具一起抹掉。
        t.apply(AgentEvent.AnswerDelta("A"))
        t.apply(AgentEvent.Started())
        assertEquals(1, t.snapshot().filterIsInstance<AnswerSegment>().size)
    }

    @Test
    fun `retryTool 只重置目标那一段，其它过程原样保留`() {
        // 回归：工具卡上的「重试」此前接的是整轮重跑（startNewTurn），
        // 点一下就把已经跑完的工具卡全清掉、再从头答一遍（用户实测反馈）。
        // 这里钉住真正该有的行为：就地重跑一个工具，其余段一动不动。
        val t = AgentTimeline()
        t.apply(AgentEvent.Started(round = 1))
        t.apply(AgentEvent.ToolCallStarted(1, 0, "device.info"))
        t.apply(AgentEvent.ToolCompleted(1, 0, "device.info", "型号=X", success = true))
        t.apply(AgentEvent.ToolCallStarted(1, 1, "linux.env"))
        t.apply(AgentEvent.ToolCompleted(1, 1, "linux.env", "长时间没有返回", success = false))

        val before = t.snapshot()
        assertEquals("开始前应有两张工具卡", 2, before.size)

        assertTrue("重试应命中已存在的段", t.retryTool(1, 1))

        val after = t.snapshot()
        assertEquals("段数不能变：重试不是重来一轮", before.size, after.size)

        // 目标段被重置为「正在执行」，旧结果清掉
        val target = after[1] as ToolCallSegment
        assertEquals(ToolStatus.Running, target.status)
        assertNull("重试后不应还挂着上一次的结果", target.result)
        assertTrue(target.isRunning)

        // 前面那张卡必须原样保留 —— 这正是这个 bug 的核心
        val untouched = after[0] as ToolCallSegment
        assertEquals("device.info", untouched.toolName)
        assertEquals(ToolStatus.Success, untouched.status)
        assertEquals("型号=X", untouched.result?.summary)
        assertEquals((before[0] as ToolCallSegment).createdAt, untouched.createdAt)
    }

    @Test
    fun `retryTool 对不存在的段返回 false`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started(round = 1))
        t.apply(AgentEvent.ToolCallStarted(1, 0, "device.info"))
        assertFalse("没有 round=2 seq=9 这一段", t.retryTool(2, 9))
    }

    @Test
    fun `restore 之后工具卡仍可被 (round,seq) 定位`() {
        // 回归（用户实测）：「重启后点重试没有任何反应，只弹一行提示」。
        // restore() 里有 reset()，而 reset() 会清空 toolIndex；工具卡却靠这个索引
        // 定位（upsertTool / retryTool 都查它）。不重建索引，恢复出来的卡就是
        // 「看得见、改不到」—— retryTool 永远返回 false。
        val origin = AgentTimeline()
        origin.apply(AgentEvent.Started(round = 1))
        origin.apply(AgentEvent.ToolCallStarted(1, 0, "device.info"))
        origin.apply(AgentEvent.ToolCompleted(1, 0, "device.info", "型号=X", success = true))
        origin.apply(AgentEvent.ToolCallStarted(1, 1, "linux.env"))
        origin.apply(AgentEvent.ToolCompleted(1, 1, "linux.env", "失败", success = false))

        val restored = AgentTimeline()
        restored.restore(origin.snapshot())

        assertTrue(
            "restore 后必须能按 (round,seq) 定位到工具段",
            restored.retryTool(1, 1),
        )
        val seg = restored.snapshot().filterIsInstance<ToolCallSegment>().first { it.seq == 1 }
        assertEquals(ToolStatus.Running, seg.status)

        // 相邻那张也要还在
        assertEquals(2, restored.snapshot().filterIsInstance<ToolCallSegment>().size)
    }

    @Test
    fun `maxRound 取已有内容里的最大轮次`() {
        // 续写要用它当基址：新一轮若从 1 开始，会和旧工具卡撞 (round, seq)
        val t = AgentTimeline()
        assertEquals(0, t.maxRound())
        t.apply(AgentEvent.Started(round = 1))
        t.apply(AgentEvent.ThinkingDelta(2, "第二轮的思考"))
        t.apply(AgentEvent.ThinkingDelta(3, "第三轮"))
        assertEquals(3, t.maxRound())
    }

    @Test
    fun `续写基址要接着已有工具轮次，否则新一轮会撞卡`() {
        // 「一轮里只有工具调用、没有思考」（模型直接调工具，一句思考都没吐）
        // 时，若 maxRound 只看 ThinkingSegment 就会漏，基址偏低 → 新一轮的
        // (round, seq) 命中旧卡、只更新不新建。
        val t = AgentTimeline()
        t.apply(AgentEvent.Started(round = 1))
        t.apply(AgentEvent.ToolCallStarted(2, 0, "device.info"))
        t.apply(AgentEvent.ToolCompleted(2, 0, "device.info", "x", success = true))

        assertEquals("maxRound 必须把工具轮次算进去", 2, t.maxRound())
    }

    @Test
    fun `续写的轮次要整体排在已有内容之后`() {
        val t = AgentTimeline()
        t.apply(AgentEvent.Started(round = 1))
        t.apply(AgentEvent.ToolCallStarted(1, 0, "a"))
        assertEquals(1, t.maxRound())

        // 模拟续写：偏移 1，于是新一轮从 2 开始
        t.apply(AgentEvent.Started(round = 2))
        t.apply(AgentEvent.ThinkingDelta(2, "续写的思考"))
        t.apply(AgentEvent.ToolCallStarted(2, 0, "b"))

        val tools = t.snapshot().filterIsInstance<ToolCallSegment>()
        assertEquals("旧卡与新卡应并存，而不是被覆盖", 2, tools.size)
        assertEquals(setOf(1, 2), tools.map { it.round }.toSet())
    }
}

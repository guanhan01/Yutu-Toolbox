package com.mcp.toolbox.feature.home.agent

import androidx.annotation.DrawableRes
import java.util.UUID

/**
 * 一条 AI 消息 = 有序的 Segment 序列（UI/动效 Spec 第 3 节）。
 *
 * ## 为什么不用「一个 Message + 一堆可选字段」
 *
 * 回答过程天然是**分阶段、可重入、可并行**的：一次思考 → 一次工具调用 → 再思考 →
 * 再工具 → 终于出正文。如果把这些塞进同一个对象的字段里（`thinking: String?`、
 * `tools: List<Tool>`、`answer: String?`），就得到一个「顺序信息丢失、无法表达
 * 两轮思考之间的工具调用」的扁平结构。
 *
 * 序列化成一个有序列表后：
 * - 顺序天然保留（LazyColumn 直接按列表渲染）
 * - 每段可以有自己的状态机（思考 Running/Done，工具 6 态）
 * - 每段可以有自己的 UI 状态（展开/收起、用户是否干预过）
 *
 * ## 独立性（Spec 明确的重点）
 *
 * 「各个思考过程和工具展开互不影响」由三点保证：
 * 1. 展开态**下沉到 Segment 自身字段**（[ThinkingSegment.isExpanded]、
 *    [ToolCallSegment.isDetailExpanded]），不放在父组件、不放在全局。
 * 2. LazyColumn 每项 `key = segment.id`，列表刷新时状态不会错位。
 * 3. 动画用**局部** `Animatable`/`animate*AsState`，禁止跨 Segment 共享 transition。
 *
 * 唯一允许的全局联动：答案首 token 到达后自动收起「用户没手动干预过」的过程块
 * （见 [AgentReducer.autoCollapseOnAnswer]）。
 */
sealed interface Segment {
    /** 全局唯一，同时用作 LazyColumn 的 key 与落盘主键。 */
    val id: String
    val createdAt: Long
}

/** 思考块 / 回答段共用的运行态。 */
enum class RunStatus {
    Running,
    Done,
    Interrupted,
}

/**
 * 一段深度思考（reasoning）。
 *
 * [round] 是「第几轮」：同一轮里思考一定先于该轮的工具调用到达，界面据此把
 * 「第 N 轮思考」排在同轮工具卡之上，下一轮思考自然落到卡片之下。
 */
data class ThinkingSegment(
    override val id: String,
    override val createdAt: Long,
    /** 已累积的思考正文（流式追加后的快照）。 */
    val text: String = "",
    val status: RunStatus = RunStatus.Running,
    val elapsedMs: Long = 0L,
    /** 展开态。★ 每段独立。 */
    val isExpanded: Boolean = false,
    /** ★ 用户手动干预过之后置 true，此后**永不自动收展**。 */
    val userToggled: Boolean = false,
    val round: Int = 0,
) : Segment {
    val isRunning: Boolean get() = status == RunStatus.Running

    /** 折叠胶囊上的标题。第 2、3 轮显示「继续思考 · 第 N 轮」，避免重复感。 */
    val title: String get() = if (round <= 1) "深度思考" else "继续思考 · 第 $round 轮"

    /** 胶囊里轮播的那一行：取最后一行、截断。 */
    val latestLine: String
        get() = text.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            .orEmpty()
}

/** 工具调用的六态（Spec 第 7.1 节）。 */
enum class ToolStatus {
    /** 已决定调用，还没发出去。 */
    Pending,
    Running,
    Success,
    Failed,

    /** 前端侧 45s 兜底超时。**绝不允许无限转圈**。 */
    Timeout,

    /** 需要用户授权的工具被拒绝。 */
    Denied,
}

/** 工具结果的结构化容器（Spec 第 7.2 节的四层）。 */
data class ToolResult(
    /** L1：一句话结果 + 关键数字，永远展示。 */
    val summary: String,
    /** L2：来源卡片（网页搜索专属）。 */
    val sources: List<WebSource>? = null,
    /** L4：完整原始输出，默认折叠，超过 500 字截断 + BottomSheet。 */
    val rawText: String? = null,
    /** L2：表格 / 代码 / 图片等结构化块。 */
    val structuredBlocks: List<ResultBlock>? = null,
) {
    // 注意：这里曾有一个 `rawTextNeedsSheet`（按长度决定要不要给「查看原始输出」入口）。
    // 现在「结果」块**任何长度都可点开**完整输出，长度不再是判据，故删除——
    // 留着会让人误以为短结果没有完整展示的入口。
}

/** 网页搜索的一条来源。 */
data class WebSource(
    val title: String,
    val url: String,
    val domain: String = url.hostOf(),
    val snippet: String = "",
) {
    private companion object {
        fun String.hostOf(): String =
            substringAfter("//").substringBefore('/').substringBefore('?')
    }
}

/** L2 层的结构化结果块。 */
sealed interface ResultBlock {
    data class Table(val header: List<String>, val rows: List<List<String>>) : ResultBlock
    data class Code(val code: String, val language: String = "") : ResultBlock
    data class Bullets(val items: List<String>) : ResultBlock
    data class Image(val url: String, val alt: String = "") : ResultBlock
}

/**
 * 一次工具调用。
 *
 * [seq] 是同一条消息内的工具序号（1 起），显示在卡片右上角的 badge 上，
 * 便于和正文里的引用对照。
 */
data class ToolCallSegment(
    override val id: String,
    override val createdAt: Long,
    val toolName: String,
    val displayName: String,
    @DrawableRes val iconRes: Int,
    val round: Int = 0,
    val seq: Int = 0,
    val argsJson: String? = null,
    val status: ToolStatus = ToolStatus.Pending,
    val startedAt: Long = createdAt,
    val endedAt: Long? = null,
    /**
     * 正在等待用户授权（Human-in-the-loop）。
     *
     * 必须是**显式字段**：早先用「argsJson 等于某个魔法字符串」来判断，
     * 结果 `needsApproval` 永远为 false，审批卡被当成普通工具卡参与了自动收起。
     */
    val awaitingApproval: Boolean = false,
    /** 需要授权时展示的风险说明。 */
    val risk: String? = null,
    /** 详情展开态。★ 独立。 */
    val isDetailExpanded: Boolean = false,
    /** ★ 用户手动干预过之后置 true（审批卡恒为 true，不参与自动收起）。 */
    val userToggled: Boolean = false,
    val result: ToolResult? = null,
) : Segment {
    /** 已完成时的耗时。Spec 第 7.1 节显示「1.2s」。 */
    val durationMs: Long? get() = endedAt?.let { (it - startedAt).coerceAtLeast(0) }

    /** 正在执行（等待授权不算：它不是「转圈中」，而是「等用户决定」）。 */
    val isRunning: Boolean
        get() = !awaitingApproval && (status == ToolStatus.Pending || status == ToolStatus.Running)
}

/**
 * 回答正文。
 *
 * [markdown] 是流式累积后的快照。真正的增量渲染由 `StreamPipeline` 处理：
 * 已闭合的块只解析一次并缓存，只有尾块会重解析（见 Spec 第 5.1 节）。
 */
data class AnswerSegment(
    override val id: String,
    override val createdAt: Long,
    val markdown: String = "",
    val status: RunStatus = RunStatus.Running,
) : Segment {
    val isStreaming: Boolean get() = status == RunStatus.Running
}

/** 系统级卡片：错误 / 中断 / 重试 / 断点 / 各类异常分支（Spec 第 8.3 节矩阵）。 */
enum class SystemKind {
    /** 流式中途断网：保留内容 + 「连接中断 · 重试」。 */
    NetworkLost,

    /** 发送前就没网。 */
    Offline,

    /** 60s 无数据 stall watchdog 触发。 */
    Stall,

    /** 用户主动停止。 */
    UserStopped,

    /** 冷启动发现上次回答被中断。 */
    Checkpoint,

    /** 401：渲染登录 CTA 卡，**不是**报错红条。 */
    AuthRequired,

    /** 429：带倒计时。 */
    RateLimited,

    /** 5xx：服务暂时不可用。 */
    ServerError,

    /** 内容安全拦截：灰卡 + 修改建议。 */
    Blocked,

    /** 已达长度上限：尾部「继续生成」。 */
    Truncated,

    /** 0 token 空回答。 */
    EmptyAnswer,

    /** 只有工具没有总结。 */
    ToolOnly,
}

data class SystemSegment(
    override val id: String,
    override val createdAt: Long,
    val kind: SystemKind,
    /** 细节文案（错误原因、域名等）。 */
    val payload: String = "",
    /** [SystemKind.RateLimited] 的倒计时秒数。 */
    val retryAfterSeconds: Int = 0,
    /** [SystemKind.Checkpoint] 已保留的字数。 */
    val preservedChars: Int = 0,
) : Segment {
    companion object {
        fun new(kind: SystemKind, now: Long, payload: String = "", retryAfterSeconds: Int = 0,
                preservedChars: Int = 0): SystemSegment =
            SystemSegment(
                id = "sys-${kind.name}",
                createdAt = now,
                kind = kind,
                payload = payload,
                retryAfterSeconds = retryAfterSeconds,
                preservedChars = preservedChars,
            )
    }
}

/** 生成一个不会与既有 Segment 冲突的 id。 */
internal fun newSegmentId(prefix: String): String = "$prefix-${UUID.randomUUID()}"

/**
 * 工具调用那一轮里模型的过渡旁白。
 *
 * 这类轮次的正文不是最终答复，而是「我要去调用工具」的过渡语句。此前直接并进
 * 最终回复，导致回复里混入中间过程；现在独立成段，视觉上比正文弱一档（14sp / 60%）。
 */
data class NarrationSegment(
    override val id: String,
    override val createdAt: Long,
    val text: String,
    val round: Int = 0,
) : Segment

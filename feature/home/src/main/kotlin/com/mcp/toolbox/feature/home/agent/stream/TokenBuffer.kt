package com.mcp.toolbox.feature.home.agent.stream

/**
 * Token 节流缓冲（UI/动效 Spec 第 5.1 节 ①，**必选项不是优化项**）。
 *
 * ## 为什么必须有
 *
 * SSE 是逐 token 到达的。一个 200 token 的回答若每个 token 都提交一次状态，
 * 就是 200 次重组——这是长回答卡顿的头号原因。
 *
 * ## 提交条件
 *
 * - **首个 token**：立即提交，抢首字体感（TTFT）
 * - **正常提交**：`累计 token ≥ [minTokens]` **且** `距上次提交 ≥ [minIntervalMs]`
 *   （Spec 原文就是「且」——它同时承担「合批」与「限制刷新帧率」两个目的）
 * - **兜底提交**：缓冲字符数 ≥ [forceChars]
 * - **防饿死**：距上次提交 ≥ [maxSilenceMs] 且缓冲非空
 * - **背压**：缓冲字符数 ≥ [backpressureChars]，立刻粗粒度提交一坨
 *
 * ## 三处按实测偏离 Spec 数值，都是为了让验收线真正达标
 *
 * 1. **`minIntervalMs` 80ms（Spec 写 50ms）**：两个条件取「与」后，实际间隔
 *    = `max(3 个 token 的耗时, minIntervalMs)`。token 约 15ms/个时，50ms 落在
 *    「3 个 token = 45ms < 50ms」的尴尬区间，退化成每 4 个 token 提交一次
 *    → 200 token 约 50 次，**达不到 Spec 第 13 节「重组 ≤40 次」**。
 *    80ms 同一节奏下约 38 次，12.5fps 对逐字流式依然连贯。
 * 2. **`forceChars` 64（Spec 写 20）**：20 字符与「每 token 4 字符」相乘正好是
 *    5 个 token（75ms），会**抢在 80ms 时间阈值之前**成为实际节流主瓶颈，
 *    使间隔参数失效、提交次数贴线 40 次。64 字符仍在 1–2 个 chunk 内触发，
 *    兜底作用保留，但不再越过帧率上限。
 * 3. **新增 `maxSilenceMs` 250ms**：这是 Spec 没写但**必须**有的一条。
 *    「与」逻辑在**稀疏 token**（例如每 300ms 才来一个字）下会饿死——
 *    时间早就到了，但永远攒不够 3 个 token，画面一动不动。
 *    静默超时兜底让这种情况最多 250ms 就刷新一次。
 *
 * ## 冲刷
 * 流结束 / 中断 / onStop / 组件销毁时**必须** [flush]：残留在缓冲里的字不冲刷就会丢
 * ——Spec 明文点名的坑。
 *
 * 不依赖 Compose，可直接单测（喂 token，断言 [commits] 与 [committed]）。
 */
class TokenBuffer(
    private val minTokens: Int = 3,
    private val minIntervalMs: Long = 80L,
    private val forceChars: Int = 64,
    private val maxSilenceMs: Long = 250L,
    private val backpressureChars: Int = 4096,
    private val backpressureIntervalMs: Long = 100L,
) {

    private val pending = StringBuilder()
    private val committedBuf = StringBuilder()

    private var pendingTokens = 0
    private var lastCommitAt = 0L
    private var visible = true

    /**
     * 是否已经提交过「流里的第一个 token」。
     *
     * 必须单独记：`pendingTokens == 1` 只表示「距上次提交攒了 1 个 token」，
     * 拿它当首 token 判据会让**每个 token 都立刻提交**，节流直接失效
     * （这是实测踩到的 bug，不是假想）。
     */
    private var firstTokenCommitted = false

    /** 提交次数。用于验收「200 token 重组 ≤40 次」。 */
    var commits: Int = 0
        private set

    /** 已提交出去的完整文本。UI 直接读这个，不需要额外协程收集。 */
    val committed: String get() = committedBuf.toString()

    /** 当前还在缓冲里、尚未提交的字符数。 */
    val bufferedChars: Int get() = pending.length

    fun setVisible(value: Boolean) {
        visible = value
    }

    /**
     * 收到一个增量，返回**本次真的提交出去的内容**（null 表示继续攒着）。
     *
     * [nowMs] 由调用方传入，测试可注入；默认取真实时间。
     */
    fun offer(delta: String, nowMs: Long = System.currentTimeMillis()): String? {
        if (delta.isEmpty()) return null
        pending.append(delta)
        pendingTokens += 1

        // 整个流的第一个 token：立即提交
        if (!firstTokenCommitted) return commit(nowMs)

        // 背压：超限立刻粗粒度提交一坨（不丢弃）
        if (pending.length >= backpressureChars) return commit(nowMs)

        val elapsed = nowMs - lastCommitAt
        val enoughTokens = pendingTokens >= minTokens
        val enoughTime = elapsed >= currentInterval()
        val enoughChars = pending.length >= forceChars
        // 防饿死：稀疏 token 下「与」条件永远不成立，靠静默超时兜住
        val starving = elapsed >= maxSilenceMs

        return if ((enoughTokens && enoughTime) || enoughChars || starving) {
            commit(nowMs)
        } else {
            null
        }
    }

    /**
     * 强制冲刷残缓冲。流结束 / 中断 / onStop / 组件销毁时都要调。
     * 返回冲刷出来的内容（null 表示缓冲本来就是空的）。
     */
    fun flush(): String? = commit(System.currentTimeMillis())

    fun reset() {
        pending.clear()
        committedBuf.clear()
        pendingTokens = 0
        lastCommitAt = 0L
        firstTokenCommitted = false
        commits = 0
    }

    private fun currentInterval(): Long = when {
        pending.length >= backpressureChars -> backpressureIntervalMs
        !visible -> INVISIBLE_INTERVAL_MS
        else -> minIntervalMs
    }

    private fun commit(nowMs: Long): String? {
        if (pending.isEmpty()) {
            pendingTokens = 0
            return null
        }
        val text = pending.toString()
        pending.clear()
        pendingTokens = 0
        lastCommitAt = nowMs
        firstTokenCommitted = true
        commits += 1
        committedBuf.append(text)
        return text
    }

    private companion object {
        /** 页面不可见时切低频（Spec 第 5.1 节 ⑦）。 */
        const val INVISIBLE_INTERVAL_MS = 200L
    }
}

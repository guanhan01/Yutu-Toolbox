package com.mcp.toolbox.feature.home.agent.stream

import com.mcp.toolbox.core.design.component.MdBlock
import com.mcp.toolbox.core.design.component.parseMarkdown

/**
 * Markdown 的**块级增量**切分（UI/动效 Spec 第 5.1 节 ③④）。
 *
 * ## 核心不变式
 * 文本被切成两段：
 * - **已闭合前缀**：以空行结尾、且不处在未闭合代码围栏内的部分。它**只解析一次**，
 *   结果缓存在 [closedBlocks]，之后永不重解析、永不重排。
 * - **尾块**：剩余部分。每次提交只重解析它。
 *
 * 于是「200 token 的回答」不会触发 200 次全量解析，只有尾块在做小规模解析。
 *
 * ## 围栏与容错
 * 围栏只看**完整行**：没有以 `\n` 收尾的最后一行永远不参与围栏判定，否则输入到一半的
 * ```` `` ```` 会被误判成围栏开关，代码块会先出现再消失。
 *
 * 尾块里的未闭合语法不做「补全猜测」，只做**稳定渲染**：
 * - 未闭合围栏：尾块整块按代码渲染（而不是先当正文、闭合后又跳成代码）
 * - 未闭合 `**` / `[`：交给行内解析，匹配不到就按纯文本画，天然不闪烁
 *
 * 复杂度：每次 [append] 只扫描新增的完整行，O(新增字符)。
 */
class MarkdownChunker {

    private val text = StringBuilder()

    /** 已闭合前缀的字符数。 */
    private var closedEnd = 0

    /** 已扫描过围栏的字符数，一定停在行边界上。 */
    private var scannedTo = 0

    /** 最后一个「可闭合位置」，即最近一个处在围栏外的空行末尾。 */
    private var lastBlankAt = 0

    /** 当前围栏是否开着（只看已扫描的完整行）。 */
    private var openFence = false

    /** 已闭合的块缓存。只增不改。 */
    private val closedBlocks = mutableListOf<MdBlock>()

    /** 已缓存的闭合块数量。 */
    val closedCount: Int get() = closedBlocks.size

    fun append(delta: String): Chunked {
        if (delta.isEmpty()) return current()
        text.append(delta)
        scanNewCompleteLines()
        closeUpToLastBlank()
        return current()
    }

    /** 全量重置（重新生成、切换消息时用）。 */
    fun reset() {
        text.clear()
        closedEnd = 0
        scannedTo = 0
        lastBlankAt = 0
        openFence = false
        closedBlocks.clear()
    }

    fun current(): Chunked {
        val tail = if (closedEnd >= text.length) "" else text.substring(closedEnd)
        return Chunked(
            /**
             * ⚠️ 必须交**快照**（`toList()`），不能把内部可变列表直接交出去。
             *
             * 交引用的话调用方拿到的那个对象会随着后续 `append` 继续增长，
             * 于是「已闭合块」与「尾块」出现重叠内容，界面上同一段正文被画两遍
             * （实测：一段正文先正常渲染、下面又原样带 `**` 出现一遍）。
             * 另一个后果是 `remember(closedBlocks, tail)` 的 key 永远不变，
             * 缓存判断失效。
             */
            closedBlocks = closedBlocks.toList(),
            tail = tail,
            /**
             * 尾块是否处在未闭合的代码围栏内。
             *
             * 只作为**诊断信息**保留：渲染路径不再据此改画法（见 `AnswerBlock`），
             * 因为「解析后的块 + 原样代码块」两条路径同时出内容就是上面那个重复的成因。
             */
            tailInOpenFence = openFence || hasUnclosedFenceInTail(),
        )
    }

    // ------------------------------------------------------------------
    // 增量扫描
    // ------------------------------------------------------------------

    /**
     * 只扫描新出现的**完整行**，增量维护围栏状态与最近的可闭合位置。
     */
    private fun scanNewCompleteLines() {
        val lineEnd = lastCompleteLineEnd()
        if (lineEnd <= scannedTo) return
        var lineStart = scannedTo
        var i = scannedTo
        while (i < lineEnd) {
            if (text[i] == '\n') {
                when {
                    isFenceLine(lineStart, i) -> openFence = !openFence
                    // 围栏外的空行 = 一个安全的切块点
                    !openFence && isBlankLine(lineStart, i) -> lastBlankAt = i + 1
                }
                lineStart = i + 1
            }
            i++
        }
        scannedTo = lineEnd
    }

    /** 最后一个以 `\n` 结尾的位置；它之后的行可能还在流入，不参与判定。 */
    private fun lastCompleteLineEnd(): Int {
        val idx = text.lastIndexOf('\n')
        return if (idx < 0) 0 else idx + 1
    }

    private fun isBlankLine(start: Int, end: Int): Boolean {
        for (k in start until end) {
            val c = text[k]
            if (c != ' ' && c != '\t' && c != '\r') return false
        }
        return true
    }

    /** [start, end) 是否是一行代码围栏（三个及以上的 ` 或 ~，允许前导空白）。 */
    private fun isFenceLine(start: Int, end: Int): Boolean {
        var s = start
        while (s < end && (text[s] == ' ' || text[s] == '\t')) s++
        if (end - s < 3) return false
        val c = text[s]
        if (c != '`' && c != '~') return false
        var count = 0
        while (s + count < end && text[s + count] == c) count++
        return count >= 3
    }

    /**
     * 尾块里围栏数是奇数时，说明有一个还没闭合的围栏
     * （例如围栏刚开了个头、还没到换行）。
     */
    private fun hasUnclosedFenceInTail(): Boolean {
        var count = 0
        var lineStart = closedEnd
        var i = closedEnd
        while (i < text.length) {
            if (text[i] == '\n') {
                if (isFenceLine(lineStart, i)) count++
                lineStart = i + 1
            }
            i++
        }
        if (isFenceLine(lineStart, text.length)) count++
        return count % 2 == 1
    }

    /** 有新的可闭合位置时，把新闭合的那一段解析一次并追加进缓存。 */
    private fun closeUpToLastBlank() {
        val target = lastBlankAt
        if (target <= closedEnd) return
        val region = text.substring(closedEnd, target)
        if (region.isNotBlank()) closedBlocks += parseMarkdown(region)
        closedEnd = target
    }

    /** 每次 [append] 的产物。 */
    data class Chunked(
        /** 已闭合、已解析、**不要重复解析**的块。 */
        val closedBlocks: List<MdBlock>,
        /** 尾块原文（流式中，每次重解析）。 */
        val tail: String,
        /** 尾块是否应整块按代码渲染。 */
        val tailInOpenFence: Boolean,
    )
}

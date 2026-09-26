package com.mcp.toolbox.feature.home

/**
 * 一段被压缩的历史。
 *
 * 方案 A ——「压缩不丢失」：原文**永久保留在磁盘上**，只把**发给模型的上下文**
 * 换成 [summary]；界面把它渲染成一段可展开的摘要，展开后仍能看到原文。
 *
 * 用消息 **id** 而不是下标记录范围：用户可以删除 / 编辑 / 重说消息，下标会整体
 * 移位，按下标存的压缩记录会指向错的内容。
 */
data class CompressedRange(
    val id: String,
    val messageIds: List<String>,
    val summary: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val messageCount: Int get() = messageIds.size
}

/** 会话的压缩记录：一个会话可能被压缩多次（聊长了就压一次）。 */
data class CompressionState(
    val sessionId: String,
    val ranges: List<CompressedRange> = emptyList(),
) {
    val isEmpty: Boolean get() = ranges.isEmpty()

    /** 该消息属于哪一段压缩；不属于任何一段时返回 null。 */
    fun rangeOf(messageId: String): CompressedRange? =
        ranges.firstOrNull { messageId in it.messageIds }

    /**
     * 该消息是否是所在压缩段的「第一条仍在历史里的消息」。
     *
     * 段首消息被删掉时，摘要块落到下一条存留的消息上，不会整段消失。
     */
    fun isVisibleStart(messageId: String, orderedIds: List<String>): Boolean {
        val range = rangeOf(messageId) ?: return false
        val firstAlive = orderedIds.firstOrNull { it in range.messageIds }
        return firstAlive == messageId
    }
}

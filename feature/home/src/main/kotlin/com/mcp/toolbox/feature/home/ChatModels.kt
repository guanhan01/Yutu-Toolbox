package com.mcp.toolbox.feature.home

import java.util.UUID

/** 一条对话消息。 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val time: Long = System.currentTimeMillis(),
) {
    enum class Role { USER, ASSISTANT, SYSTEM }
}

/** 一次对话（会话），包含其全部消息。 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
) {
    /** 列表里显示的标题：优先用首条用户消息，其次用创建时的时间标题。 */
    val displayTitle: String
        get() = messages.firstOrNull { it.role == ChatMessage.Role.USER }
            ?.content
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.take(24)
            ?.takeIf { it.isNotEmpty() }
            ?: title
}

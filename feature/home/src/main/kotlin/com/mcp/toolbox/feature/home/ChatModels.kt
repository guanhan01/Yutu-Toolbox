package com.mcp.toolbox.feature.home

import java.util.UUID


/** 一条对话消息。 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val time: Long = System.currentTimeMillis(),
    /**
     * 随本条消息一起发出的图片（content URI）。
     *
     * 只用于当次请求的多模态输入，不写入本地会话文件：会话记录只保留文本，
     * 避免把 base64 或失效的临时 URI 长期留在磁盘上。
     */
    val imageUris: List<String> = emptyList(),
    /** 工具调用名称（role == TOOL 时有值）。 */
    val toolName: String = "",
    /** 工具调用参数（role == TOOL 时有值）。 */
    val toolArguments: String = "",
    /** 用户编辑过本条消息。 */
    val edited: Boolean = false,
) {
    enum class Role {
        USER,
        ASSISTANT,
        SYSTEM,

        /** 深度思考过程：模型推理文本。 */
        REASONING,

        /** 一次工具调用：content 为结果，toolName/toolArguments 为调用信息。 */
        TOOL,
    }
}

/**
 * 正在进行的工具调用。
 *
 * 只用于实时展示：请求结束后这些会落成 Role.TOOL 的正式消息。
 */
data class RunningTool(
    val name: String,
    val arguments: String = "",
    val result: String = "",
)

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

/**
 * 可供切换的一个模型。
 *
 * 跨服务商聚合：只有已拉取过模型列表的服务商才会出现在切换菜单里。
 */
data class ModelOption(
    val providerName: String,
    val providerTitle: String,
    @androidx.annotation.DrawableRes val providerIconRes: Int,
    val modelId: String,
    val isCurrent: Boolean,
)

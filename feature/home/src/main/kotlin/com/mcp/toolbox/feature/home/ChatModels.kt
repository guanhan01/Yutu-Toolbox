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
    /**
     * 这是过程里的中间旁白，不是最终答复。
     *
     * 带工具调用那一轮模型的正文只是「我要去调用工具」的过渡语句。它要以
     * ASSISTANT 身份留给模型看，但界面上不该出现复制 / 重说 / 删除——
     * 用户要操作的是最终答复，不是过程的碎片。
     */
    val intermediate: Boolean = false,
    /**
     * 该步骤的耗时（毫秒）。
     *
     * 只对过程消息（深度思考 / 工具调用）有意义：结果落盘后仍要显示「用时 N 秒」，
     * 所以必须持久化，不能只留在内存里的实时状态。
     */
    val elapsedMs: Long = 0,
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
 * 正在进行的回复里的一步。
 *
 * 只用于实时展示：请求结束后这些会按同一顺序落成正式消息
 * （Thinking -> REASONING，Tool -> TOOL，Narration / 最终文本 -> ASSISTANT）。
 *
 * 之所以是一条混排的时间线而不是按类型分开的两个列表：一次回复里
 * 「思考 → 工具 → 继续思考 → 再调用工具」是交替的，分成两个列表就只能
 * 整块渲染，界面必然显示成「所有思考都在所有工具之上」。
 */
sealed interface RunningStep {
    /**
     * 一段深度思考。
     *
     * [live] 表示这段思考仍在（或刚刚结束）生成。只有时间线上**最后一条**且
     * [live] 为 true 时界面才显示「深度思考中…」；一旦后面出现了工具调用或正文，
     * 同一条会原地变成「已深度思考」，不会残留在思考中状态。
     */
    data class Thinking(
        val text: String,
        val live: Boolean = false,
        val elapsedMs: Long = 0,
    ) : RunningStep

    /** 模型带工具调用那一轮的过渡正文。 */
    data class Narration(val text: String) : RunningStep

    /** 一次工具调用。 */
    data class Tool(
        val name: String,
        val arguments: String = "",
        val result: String = "",
        val elapsedMs: Long = 0,
    ) : RunningStep
}

/** 一次对话（会话），包含其全部消息。 */
data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
    /**
     * 用户手动设置的标题。
     *
     * 与 [title] 的区别：`title` 是新建对话时按时间生成的默认名，用户改它没有意义
     * ——[displayTitle] 永远优先取首条用户消息，改了也看不出来。所以重命名单独记在
     * 这里，并拥有最高优先级；为空表示没改过，仍按原文推导。
     */
    val customTitle: String? = null,
) {
    /** 列表与顶栏显示的名字：用户改过的名字 > 首条用户消息 > 创建时的时间标题。 */
    val displayTitle: String
        get() = customTitle?.takeIf { it.isNotBlank() }
            ?: messages.firstOrNull { it.role == ChatMessage.Role.USER }
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

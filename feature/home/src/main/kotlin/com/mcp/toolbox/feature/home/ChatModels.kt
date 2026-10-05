package com.mcp.toolbox.feature.home

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一条对话消息。
 *
 * ## 关键设计：AI 的「过程」属于消息本身
 *
 * 思考、工具调用、正文是**同一轮回答的内容**，不是「界面临时状态」。所以
 * [segmentsJson] 里存着这一轮的完整过程时间线，和消息一起落盘。
 *
 * 早先时间线只活在界面内存、而消息只存纯文本，直接导致：
 * 切后台回来过程丢失、点停止没有过程记录、第二轮工具卡不出现、
 * 新一轮的问题跑到上一轮过程块下面。这些都不是独立的 bug，是同一个错误的四种表现。
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: Role,
    val content: String,
    val time: Long = System.currentTimeMillis(),

    /**
     * 随本条消息一起发出的图片（content URI）。只用于当次请求的多模态输入。
     *
     * 保留在消息里（不再像早先那样落盘时剥掉）是为了让气泡能画出缩略图——
     * 用户发了图却在对话里看不到任何痕迹，是一个明显的体感缺陷。
     * URI 可能随进程死亡失效，渲染端必须能退回只显示标签。
     */
    val imageUris: List<String> = emptyList(),

    /** 随消息一起发出的文件 / 文件夹 / 手输路径（只存展示所需的信息）。 */
    val attachments: List<AttachmentRef> = emptyList(),

    /**
     * 只发给模型、**不上屏**的上下文文本（附件内容等）。
     *
     * 早先它被当成第二条 `role = USER` 的消息塞进会话，结果界面上出现两个用户气泡、
     * 第二个还是一大段原始上下文。现在把它挂在同一条消息上，仅在建请求时拼接。
     */
    val modelContext: String? = null,

    /** AI 消息：这一轮的过程时间线（JSON 数组）。由 `AgentSegmentCodec` 编解码。 */
    val segmentsJson: String? = null,

    /** AI 消息：这一轮的终态。用于渲染断点卡片、区分正常完成与中断。 */
    val turnState: TurnState = TurnState.Done,

    /** 用户编辑过本条消息。 */
    val edited: Boolean = false,
) {
    enum class Role {
        USER,
        ASSISTANT,
        SYSTEM,
    }

    /** 是否是「被中断 / 失败」的一轮（冷启动时要渲染恢复卡片）。 */
    val isUnfinished: Boolean
        get() = role == Role.ASSISTANT &&
            (turnState == TurnState.Running || turnState == TurnState.Interrupted)
}

/** 一轮回答的终态。 */
enum class TurnState {
    /** 正在跑（进程被杀后冷启动读到它，就说明上次没跑完）。 */
    Running,
    Done,

    /** 用户主动停止。 */
    Interrupted,

    /** 请求失败。 */
    Failed,
}

/**
 * 附件的展示信息。
 *
 * 只存「画出来需要什么」，不存内容：文件正文已经进了 [ChatMessage.modelContext]，
 * 图片走 [ChatMessage.imageUris]。
 */
data class AttachmentRef(
    val kind: Kind,
    val label: String,
) {
    enum class Kind {
        FILE,
        FOLDER,
        PATH,
        IMAGE,
    }

    companion object {
        fun encode(list: List<AttachmentRef>): String {
            val arr = JSONArray()
            list.forEach { a ->
                arr.put(JSONObject().put("k", a.kind.name).put("l", a.label))
            }
            return arr.toString()
        }

        fun decode(text: String?): List<AttachmentRef> {
            if (text.isNullOrBlank()) return emptyList()
            return runCatching {
                val arr = JSONArray(text)
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val kind = runCatching {
                        Kind.valueOf(o.optString("k"))
                    }.getOrNull() ?: return@mapNotNull null
                    AttachmentRef(kind, o.optString("l"))
                }
            }.getOrDefault(emptyList())
        }
    }
}

/**
 * 会把 [modelContext] 拼进 `content` 的历史副本。
 *
 * 只影响发给模型的请求，不动界面渲染与落盘的数据——
 * 这样「界面上看到一条干净的用户消息」与「模型拿到完整附件上下文」可以同时成立。
 */
fun List<ChatMessage>.forModel(): List<ChatMessage> = map { m ->
    val extra = m.modelContext?.takeIf { it.isNotBlank() }
    if (extra == null) m else m.copy(content = m.content + "\n\n" + extra)
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
    /** 非空表示这一项来自自定义供应商，选中时要按 id 切换而不是切内置枚举。 */
    val customId: String? = null,
)

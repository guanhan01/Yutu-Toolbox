package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.feature.home.ChatMessage
import com.mcp.toolbox.feature.home.ChatSession
import com.mcp.toolbox.feature.home.ChatUsageStore
import com.mcp.toolbox.feature.home.CompressedRange
import com.mcp.toolbox.feature.home.CompressionStore
import com.mcp.toolbox.feature.home.MemoryBudget
import java.util.UUID

/**
 * 上下文压缩。
 *
 * 方案 A：**原文永久保留**。这里只做一件事——让模型把旧内容写成一份摘要，
 * 然后把「发给模型的历史」换成这份摘要（见 [CompressionStore.modelHistory]）。
 * 磁盘上的消息一条不动，所以用户随时能看到、编辑、撤销。
 *
 * 压缩对象包含深度思考与工具调用内容：那两类在正常请求里不回传（见
 * `buildMessages`），但它们是「为什么这么做」的关键上下文，丢掉会导致摘要
 * 说不清来龙去脉，所以要显式喂给压缩用的模型。
 */
object AiCompressor {

    private const val MAX_INPUT_CHARS = 24_000
    private const val MIN_KEEP_MESSAGES = 6

    private const val SYSTEM_PROMPT = """你是上下文压缩器。把给定的对话历史压缩成一份摘要。

要求：
1. 保留全部仍然有效的事实：结论、代码改动、文件路径、命令、参数、用户偏好与约束。
2. 合并重复内容；删除已被后续对话推翻或取代的中间过程。
3. 深度思考与工具调用里的关键推理和结果要保留，过程细节可以合并。
4. 用要点列表输出，直接给摘要正文，不要任何前言或解释。
5. 摘要里不要出现「以上」「如下」这类依赖上下文的指代。"""

    /**
     * 压缩 [session] 里除最近 [MIN_KEEP_MESSAGES] 条之外的全部历史。
     *
     * 已经落在既有压缩段里的消息不再重复喂给模型，但仍算进新段——这样一段摘要
     * 覆盖的是连续区间，二次压缩不会把历史切碎。
     */
    suspend fun compress(
        context: Context,
        config: AiConfig,
        session: ChatSession,
    ): Result<CompressedRange> = runCatching {
        require(config.ready) { "尚未配置模型，无法压缩" }

        val total = session.messages.size
        val upTo = (total - MIN_KEEP_MESSAGES).coerceAtLeast(0)
        require(upTo > 0) { "可压缩的消息不足：至少要有 ${MIN_KEEP_MESSAGES + 1} 条历史" }

        val existing = CompressionStore.of(session.id)
        // 只把「还没被摘要过」的消息发给模型：已经是摘要的部分再压一次只会丢信息
        val fresh = session.messages.take(upTo).filter { m ->
            existing?.rangeOf(m.id) == null
        }
        if (fresh.isEmpty() && existing != null) {
            throw IllegalStateException("没有新的内容可压缩：请先继续对话")
        }

        val sb = StringBuilder()
        fresh.forEach { m ->
            val label = when (m.role) {
                ChatMessage.Role.USER -> "用户"
                ChatMessage.Role.ASSISTANT -> "助手"
                ChatMessage.Role.REASONING -> "深度思考"
                ChatMessage.Role.TOOL -> "工具调用 ${m.toolName}"
                ChatMessage.Role.SYSTEM -> "系统"
            }
            sb.append("【$label】\n")
            if (m.role == ChatMessage.Role.TOOL && m.toolArguments.isNotBlank()) {
                sb.append("参数 ").append(m.toolArguments).append('\n')
            }
            sb.append(m.content).append("\n\n")
        }
        // 既有摘要本身也要跟着走，否则新摘要会丢掉更早的信息
        existing?.ranges?.forEach { r ->
            if (session.messages.take(upTo).any { it.id in r.messageIds }) {
                sb.append("【此前的压缩摘要】\n").append(r.summary).append("\n\n")
            }
        }

        var raw = sb.toString()
        if (raw.length > MAX_INPUT_CHARS) {
            // 超长时从开头截断：越靠前的消息越老，信息价值越低
            raw = "（更早的部分已省略）\n" + raw.takeLast(MAX_INPUT_CHARS)
        }

        val result = AiChatClient.complete(
            context = context,
            config = config,
            history = listOf(
                ChatMessage(
                    role = ChatMessage.Role.USER,
                    content = "请压缩以下对话历史：\n\n$raw",
                ),
            ),
            systemPrompt = SYSTEM_PROMPT,
        )
        val summary = result.getOrElse { throw it }
        require(summary.isNotBlank()) { "压缩结果为空" }

        val range = CompressedRange(
            id = UUID.randomUUID().toString(),
            messageIds = session.messages.take(upTo).map { it.id },
            summary = summary.trim(),
        )
        CompressionStore.add(context, session.id, range)
        // 压缩后旧的用量值立刻失真，先用估算占位，下一轮请求回来会被真实值覆盖
        ChatUsageStore.resetAfterCompress(
            context = context,
            sessionId = session.id,
            estimated = MemoryBudget.estimateTokens(summary),
        )
        range
    }

    /** 压缩后该会话实际会发给模型的消息数（供界面提示用）。 */
    fun modelMessageCount(session: ChatSession): Int =
        CompressionStore.modelHistory(session).size
}

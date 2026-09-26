package com.mcp.toolbox.feature.home

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 一次请求的用量快照。
 *
 * [promptTokens] 是「这次真正发出去的全部输入」，包含 system prompt（含记忆与计划）、
 * 历史消息、工具 schema 与工具结果；[completionTokens] 只含本次生成。
 * 上下文占用看的是前者——后者的累计会被压缩掉，不能当占用。
 */
data class UsageSnapshot(
    val sessionId: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * 上下文用量。
 *
 * 每个会话一份，跟着模型切换重新算（窗口大小取当前模型的）。与记忆预算相互独立：
 * 记忆本身是 prompt 的一部分，已经算进 [UsageSnapshot.promptTokens]，这里不再重复计。
 *
 * 尚未跑过请求时没有服务端数据，界面上回退到本地估算（见 [MemoryBudget]），
 * 估算值与真实值可能有偏差，所以只在没有真值时使用。
 */
object ChatUsageStore {

    private const val FILE_NAME = "chat-usage.json"

    private val _usage = MutableStateFlow<Map<String, UsageSnapshot>>(emptyMap())
    val usage: StateFlow<Map<String, UsageSnapshot>> = _usage.asStateFlow()

    /**
     * 压缩阈值：占用超过窗口的 75% 就提示/自动压缩。
     *
     * 与界面提示共用同一个常量，避免两处各写一个数字后不一致。
     */
    const val COMPRESS_THRESHOLD = 0.75f

    private var loaded = false

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) parse(file.readText(Charsets.UTF_8)) else emptyMap()
        }.onSuccess { _usage.value = it }
        loaded = true
    }

    /** 记录一次服务端返回的真实用量。 */
    suspend fun record(
        context: Context,
        sessionId: String,
        promptTokens: Int,
        completionTokens: Int,
    ) = withContext(Dispatchers.IO) {
        if (promptTokens <= 0 && completionTokens <= 0) return@withContext
        _usage.value = _usage.value + (
            sessionId to UsageSnapshot(
                sessionId = sessionId,
                promptTokens = promptTokens,
                completionTokens = completionTokens,
            )
        )
        persist(context)
    }

    /**
     * 本地估算当前上下文的占用量（没有服务端数据时使用）。
     *
     * 把历史里会发给模型的部分全部算进去：用户消息、助手回复、以及记忆与计划
     * （后两者属于固定前缀，长度可从 store 取）。
     */
    fun estimate(context: Context, session: ChatSession?, memoryPrompt: String?): Int {
        if (session == null) return 0
        var total = 0
        memoryPrompt?.let { total += MemoryBudget.estimateTokens(it) }
        session.messages.forEach { m ->
            when (m.role) {
                // 与 buildMessages 一致：思考与工具卡片不回传，不计入
                ChatMessage.Role.REASONING, ChatMessage.Role.TOOL -> Unit
                else -> total += MemoryBudget.estimateTokens(m.content) + 4
            }
        }
        return total
    }

    /** 当前会话的用量：优先真实值，没有则返回估算值。 */
    fun resolve(
        context: Context,
        session: ChatSession?,
        memoryPrompt: String?,
    ): Int {
        val real = session?.id?.let { _usage.value[it]?.promptTokens } ?: 0
        return if (real > 0) real else estimate(context, session, memoryPrompt)
    }

    /** 是否已经拿到服务端真实用量（界面据此决定要不要标注「估算」）。 */
    fun hasRealValue(sessionId: String?): Boolean =
        (sessionId?.let { _usage.value[it]?.promptTokens } ?: 0) > 0

    /** 会话被删除时清掉它的用量。 */
    suspend fun forget(context: Context, sessionId: String) = withContext(Dispatchers.IO) {
        _usage.value = _usage.value - sessionId
        persist(context)
    }

    /**
     * 压缩完成后重设占用。
     *
     * 压缩把历史换成了摘要，服务端给的旧值立刻失真；这里先用估算值占位，
     * 下一轮请求回来会被真实值覆盖。
     */
    suspend fun resetAfterCompress(context: Context, sessionId: String, estimated: Int) =
        withContext(Dispatchers.IO) {
            _usage.value = _usage.value + (
                sessionId to UsageSnapshot(
                    sessionId = sessionId,
                    promptTokens = estimated,
                    completionTokens = 0,
                )
            )
            persist(context)
        }

    private suspend fun persist(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject()
            _usage.value.values.forEach { u ->
                root.put(
                    u.sessionId,
                    JSONObject()
                        .put("prompt", u.promptTokens)
                        .put("completion", u.completionTokens)
                        .put("updatedAt", u.updatedAt),
                )
            }
            File(context.filesDir, FILE_NAME).writeText(root.toString(), Charsets.UTF_8)
        }
    }

    private fun parse(text: String): Map<String, UsageSnapshot> {
        val root = JSONObject(text)
        val map = mutableMapOf<String, UsageSnapshot>()
        root.keys().forEach { id ->
            val o = root.optJSONObject(id) ?: return@forEach
            map[id] = UsageSnapshot(
                sessionId = id,
                promptTokens = o.optInt("prompt", 0),
                completionTokens = o.optInt("completion", 0),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
        return map
    }
}

/**
 * token 数量的显示格式。
 *
 * 1000 以上用 k：进度条旁边的数字很窄，128000 这种原值会挤掉剩余空间的提示。
 */
fun formatTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> oneDecimal(tokens / 1_000_000.0) + "M"
    tokens >= 1_000 -> oneDecimal(tokens / 1_000.0) + "k"
    else -> tokens.toString()
}

/**
 * 保留一位小数并去掉没意义的尾零：「1.0k」显示成「1k」，「1.9k」保持「1.9k」。
 *
 * 用 Locale.US：默认 Locale 在部分地区用逗号当小数点，「1,9k」在小屏上
 * 会被误读成千位分隔符。
 */
private fun oneDecimal(value: Double): String =
    java.lang.String.format(java.util.Locale.US, "%.1f", value).removeSuffix(".0")

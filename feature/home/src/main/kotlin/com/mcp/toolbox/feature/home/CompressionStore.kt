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
import java.util.UUID

/** 压缩记录存储。原文不动，这里只记「哪几段被摘要替换了」。 */
object CompressionStore {

    private const val FILE_NAME = "chat-compress.json"

    private val _states = MutableStateFlow<Map<String, CompressionState>>(emptyMap())
    val states: StateFlow<Map<String, CompressionState>> = _states.asStateFlow()

    private var loaded = false

    fun of(sessionId: String?): CompressionState? =
        sessionId?.let { _states.value[it] }?.takeIf { !it.isEmpty }

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) parse(file.readText(Charsets.UTF_8)) else emptyMap()
        }.onSuccess { _states.value = it }
        loaded = true
    }

    /** 追加一段压缩记录。 */
    suspend fun add(context: Context, sessionId: String, range: CompressedRange) =
        withContext(Dispatchers.IO) {
            val current = _states.value[sessionId] ?: CompressionState(sessionId)
            _states.value = _states.value + (
                sessionId to current.copy(ranges = current.ranges + range)
            )
            persist(context)
        }

    /** 撤销某一段压缩（原文一直都在，所以撤销只是删记录）。 */
    suspend fun remove(context: Context, sessionId: String, rangeId: String) =
        withContext(Dispatchers.IO) {
            val current = _states.value[sessionId] ?: return@withContext
            val left = current.ranges.filterNot { it.id == rangeId }
            _states.value = if (left.isEmpty()) {
                _states.value - sessionId
            } else {
                _states.value + (sessionId to current.copy(ranges = left))
            }
            persist(context)
        }

    suspend fun clear(context: Context, sessionId: String) = withContext(Dispatchers.IO) {
        _states.value = _states.value - sessionId
        persist(context)
    }

    /**
     * 把「模型能看到的历史」按压缩记录拼出来。
     *
     * 被压缩区间只保留一条摘要消息；其余消息原样保留。这是唯一影响发给模型内容的
     * 地方——界面渲染走的是原始消息数组，所以用户看到的历史始终完整。
     */
    fun modelHistory(session: ChatSession): List<ChatMessage> {
        val state = of(session.id) ?: return session.messages
        val out = mutableListOf<ChatMessage>()
        val emitted = mutableSetOf<String>()
        session.messages.forEach { message ->
            val range = state.rangeOf(message.id)
            if (range == null) {
                out += message
            } else if (emitted.add(range.id)) {
                // 区间内仍存留的第一条位置上放摘要，其余同段消息跳过
                out += ChatMessage(
                    id = "compress-${range.id}",
                    role = ChatMessage.Role.ASSISTANT,
                    content = "[以下是此前 ${range.messageCount} 条对话的压缩摘要]\n${range.summary}",
                    time = range.createdAt,
                )
            }
        }
        return out
    }

    /**
     * 对任意一份「即将发出去的历史」套用压缩。
     *
     * 传进来的历史不一定等于会话里的消息数组（发送时还会临时拼上附件上下文），
     * 所以按消息 id 过滤，而不是直接取值。id 不在任何压缩段里的消息原样保留。
     */
    fun modelHistoryFor(sessionId: String?, history: List<ChatMessage>): List<ChatMessage> {
        val state = of(sessionId) ?: return history
        val out = mutableListOf<ChatMessage>()
        val emitted = mutableSetOf<String>()
        history.forEach { message ->
            val range = state.rangeOf(message.id)
            if (range == null) {
                out += message
            } else if (emitted.add(range.id)) {
                out += ChatMessage(
                    id = "compress-${range.id}",
                    role = ChatMessage.Role.ASSISTANT,
                    content = "[以下是此前 ${range.messageCount} 条对话的压缩摘要]\n${range.summary}",
                    time = range.createdAt,
                )
            }
        }
        return out
    }

    /** 一段压缩覆盖的消息是否还都在历史里（用于界面上显示「原文 N 条」）。 */
    fun aliveCount(session: ChatSession, range: CompressedRange): Int {
        val ids = session.messages.map { it.id }.toSet()
        return range.messageIds.count { it in ids }
    }

    private suspend fun persist(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject()
            _states.value.values.forEach { state ->
                val arr = JSONArray()
                state.ranges.forEach { r ->
                    arr.put(
                        JSONObject()
                            .put("id", r.id)
                            .put("ids", JSONArray(r.messageIds))
                            .put("summary", r.summary)
                            .put("createdAt", r.createdAt),
                    )
                }
                root.put(state.sessionId, arr)
            }
            File(context.filesDir, FILE_NAME).writeText(root.toString(), Charsets.UTF_8)
        }
    }

    private fun parse(text: String): Map<String, CompressionState> {
        val root = JSONObject(text)
        val map = mutableMapOf<String, CompressionState>()
        root.keys().forEach { sessionId ->
            val arr = root.optJSONArray(sessionId) ?: return@forEach
            val ranges = (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val summary = o.optString("summary")
                if (summary.isBlank()) return@mapNotNull null
                val ids = o.optJSONArray("ids") ?: JSONArray()
                val list = (0 until ids.length()).mapNotNull { ids.optString(it).takeIf { s -> s.isNotBlank() } }
                if (list.isEmpty()) return@mapNotNull null
                CompressedRange(
                    id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                    messageIds = list,
                    summary = summary,
                    createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                )
            }
            if (ranges.isNotEmpty()) {
                map[sessionId] = CompressionState(sessionId, ranges)
            }
        }
        return map
    }
}

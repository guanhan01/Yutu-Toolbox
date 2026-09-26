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
 * 对话记录的本地存储。
 *
 * 落盘为 filesDir 下的一个 JSON 文件，全程不上传；文件损坏时按空列表降级，
 * 对话记录只保留文本，消息上的图片 URI 不写入磁盘。
 * 不会因为一条坏记录导致整个对话页打不开。
 */
object ChatStore {

    private const val FILE_NAME = "chat-sessions.json"
    private const val MAX_SESSIONS = 100
    private const val MAX_MESSAGES_PER_SESSION = 500

    private val _sessions = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = _sessions.asStateFlow()

    private val _currentId = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = _currentId.asStateFlow()

    private var loaded = false

    fun current(): ChatSession? =
        _sessions.value.firstOrNull { it.id == _currentId.value }

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        val file = File(context.filesDir, FILE_NAME)
        _sessions.value = runCatching {
            if (!file.exists()) emptyList() else parse(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
        _currentId.value = _sessions.value.firstOrNull()?.id
        loaded = true
    }

    /** 新建会话并设为当前会话。 */
    suspend fun newSession(context: Context, title: String): ChatSession {
        val session = ChatSession(title = title)
        _sessions.value = listOf(session) + _sessions.value
        _currentId.value = session.id
        persist(context)
        return session
    }

    suspend fun select(id: String) {
        if (_sessions.value.any { it.id == id }) _currentId.value = id
    }

    suspend fun append(context: Context, sessionId: String, message: ChatMessage) {
        _sessions.value = _sessions.value
            .map { session ->
                if (session.id != sessionId) session
                else session.copy(
                    messages = (session.messages + message).takeLast(MAX_MESSAGES_PER_SESSION),
                    updatedAt = message.time,
                )
            }
            .sortedByDescending { it.updatedAt }
        persist(context)
    }

    /**
     * 就地更新一条消息（用户长按编辑）。
     * 编辑会刷新会话 updatedAt，会话因此在列表里上浮。
     */
    suspend fun updateMessage(
        context: Context,
        sessionId: String,
        messageId: String,
        content: String,
        imageUris: List<String> = emptyList(),
    ) {
        _sessions.value = _sessions.value
            .map { session ->
                if (session.id != sessionId) session
                else session.copy(
                    messages = session.messages.map { message ->
                        if (message.id != messageId) message
                        else message.copy(
                            content = content,
                            imageUris = imageUris,
                            edited = true,
                            time = System.currentTimeMillis(),
                        )
                    },
                    updatedAt = System.currentTimeMillis(),
                )
            }
            .sortedByDescending { it.updatedAt }
        persist(context)
    }

    /** 删除一条消息（用户长按删除 / AI 回复下方删除按钮）。 */
    suspend fun deleteMessage(context: Context, sessionId: String, messageId: String) {
        _sessions.value = _sessions.value
            .map { session ->
                if (session.id != sessionId) session
                else session.copy(messages = session.messages.filterNot { it.id == messageId })
            }
        persist(context)
    }

    /**
     * 删除 [messageId] **以及**其后的一切消息，用于「重说」：
     * 这条回复整个作废，调用方把截断后的历史交回模型重新生成。
     */
    suspend fun truncateFrom(context: Context, sessionId: String, messageId: String): List<ChatMessage> {
        var kept: List<ChatMessage> = emptyList()
        _sessions.value = _sessions.value
            .map { session ->
                if (session.id != sessionId) session
                else {
                    val index = session.messages.indexOfFirst { it.id == messageId }
                    if (index < 0) session
                    else session.copy(messages = session.messages.subList(0, index)).also {
                        kept = it.messages
                    }
                }
            }
        persist(context)
        return kept
    }

    /**
     * 只删除 [messageId] **之后**的消息，保留它本身，用于「编辑并重发」。
     *
     * 与 [truncateFrom] 的区别很关键：编辑要先把用户那条消息留下，
     * 之后的回复才作废。原先编辑走的是 truncateFrom，把用户消息也删了，
     * 紧接着 updateMessage 按 id 找不到目标，于是「改了等于没改」，
     * 界面上看起来就是那条对话消失了。
     */
    suspend fun truncateAfter(context: Context, sessionId: String, messageId: String): List<ChatMessage> {
        var kept: List<ChatMessage> = emptyList()
        _sessions.value = _sessions.value
            .map { session ->
                if (session.id != sessionId) session
                else {
                    val index = session.messages.indexOfFirst { it.id == messageId }
                    if (index < 0) session
                    else session.copy(
                        messages = session.messages.subList(0, index + 1),
                    ).also { kept = it.messages }
                }
            }
        persist(context)
        return kept
    }

    suspend fun delete(context: Context, id: String) {
        _sessions.value = _sessions.value.filterNot { it.id == id }
        if (_currentId.value == id) _currentId.value = _sessions.value.firstOrNull()?.id
        persist(context)
    }

    private suspend fun persist(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            File(context.filesDir, FILE_NAME)
                .writeText(serialize(_sessions.value.take(MAX_SESSIONS)), Charsets.UTF_8)
        }
    }

    // ---------- JSON ----------

    private fun serialize(sessions: List<ChatSession>): String {
        val arr = JSONArray()
        sessions.forEach { s ->
            val msgs = JSONArray()
            s.messages.forEach { m ->
                msgs.put(
                    JSONObject()
                        .put("id", m.id)
                        .put("role", m.role.name)
                        .put("content", m.content)
                        .put("time", m.time)
                        .put("toolName", m.toolName)
                        .put("toolArgs", m.toolArguments)
                        .put("edited", m.edited)
                        .put("elapsedMs", m.elapsedMs)
                        .put("intermediate", m.intermediate),
                )
            }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    .put("createdAt", s.createdAt)
                    .put("updatedAt", s.updatedAt)
                    .put("messages", msgs),
            )
        }
        return arr.toString()
    }

    private fun parse(text: String): List<ChatSession> {
        if (text.isBlank()) return emptyList()
        val arr = JSONArray(text)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val msgsArr = o.optJSONArray("messages") ?: JSONArray()
            val messages = (0 until msgsArr.length()).mapNotNull { j ->
                val m = msgsArr.optJSONObject(j) ?: return@mapNotNull null
                ChatMessage(
                    id = m.optString("id"),
                    role = runCatching {
                        ChatMessage.Role.valueOf(m.optString("role"))
                    }.getOrDefault(ChatMessage.Role.USER),
                    content = m.optString("content"),
                    time = m.optLong("time"),
                    toolName = m.optString("toolName"),
                    toolArguments = m.optString("toolArgs"),
                    edited = m.optBoolean("edited", false),
                    elapsedMs = m.optLong("elapsedMs", 0),
                    intermediate = m.optBoolean("intermediate", false),
                )
            }
            ChatSession(
                id = o.optString("id"),
                title = o.optString("title"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                // 迁移：`intermediate` 是后加的字段，此前落盘的中间旁白没有标记。
                // 它们的特征很明确——ASSISTANT 后面紧跟 TOOL，就是模型「我要去调用
                // 工具」的过渡语句。不改的话这些历史消息会一直带着复制/重说/删除按钮。
                messages = markIntermediates(messages),
            )
        }
    }
}

/** 按「assistant 紧跟 tool」的特征，把历史里的中间旁白补上标记。 */
private fun markIntermediates(messages: List<ChatMessage>): List<ChatMessage> {
    if (messages.isEmpty()) return messages
    return messages.mapIndexed { index, message ->
        val nextIsTool = messages.getOrNull(index + 1)?.role == ChatMessage.Role.TOOL
        if (message.role == ChatMessage.Role.ASSISTANT &&
            !message.intermediate &&
            !message.edited &&
            nextIsTool
        ) {
            message.copy(intermediate = true)
        } else {
            message
        }
    }
}

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
                        .put("time", m.time),
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
                )
            }
            ChatSession(
                id = o.optString("id"),
                title = o.optString("title"),
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                messages = messages,
            )
        }
    }
}

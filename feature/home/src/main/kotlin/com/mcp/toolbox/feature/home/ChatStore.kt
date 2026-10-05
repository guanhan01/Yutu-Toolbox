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
 * 落盘为 filesDir 下的一个 JSON 文件，全程不上传；文件损坏时按空列表降级。
 * 不会因为一条坏记录导致整个对话页打不开。
 *
 * ## 为什么 AI 的过程也要落盘
 *
 * 一条 AI 消息 = 一段完整的过程时间线（思考 / 工具 / 正文 / 系统卡），
 * 见 [ChatMessage.segmentsJson]。这一点必须持久化，否则会出现一串连带缺陷：
 * 切后台回来过程丢失、停止后没有过程记录、第二轮工具卡不出现、
 * 新一轮的问题排到上一轮过程块下面。详见 `AgentSegmentCodec` 的注释。
 */
object ChatStore {

    private const val FILE_NAME = "chat-sessions.json"
    private const val MAX_SESSIONS = 100
    private const val MAX_MESSAGES_PER_SESSION = 500

    /** 过程时间线很大，单条消息的段数也要有上限，避免一条超长回答把文件撑爆。 */
    private const val MAX_SEGMENTS_KEPT = 400

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
        val parsed = runCatching {
            if (!file.exists()) emptyList() else parse(file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
        // 读回来的 `Running` 一律定型为「被中断」：进程都已经重启了，
        // 那轮不可能还在跑。不定型的话界面会把它当成**仍在流式**——
        // 光标一直闪、计时从 00:00 重新开始，而且永远不会停。
        val (fixed, hadRunning) = sealRunningTurns(parsed)
        _sessions.value = fixed
        _currentId.value = fixed.firstOrNull()?.id
        loaded = true
        // 定型结果要写回，否则下次冷启动又得重算一遍
        if (hadRunning) persist(context)
    }

    /**
     * 把 `turnState == Running` 的消息定型为 `Interrupted`，
     * 并把它时间线里仍在 `Running` 的段一并冻结。
     *
     * 返回「定型后的会话列表」与「是否真的改过」。
     */
    private fun sealRunningTurns(
        sessions: List<ChatSession>,
    ): Pair<List<ChatSession>, Boolean> {
        var changed = false
        val out = sessions.map { session ->
            var sessionChanged = false
            val messages = session.messages.map { m ->
                if (m.turnState != TurnState.Running) return@map m
                sessionChanged = true
                m.copy(
                    turnState = TurnState.Interrupted,
                    segmentsJson = m.segmentsJson?.let { json ->
                        com.mcp.toolbox.feature.home.agent.AgentSegmentCodec
                            .sealRunning(json)
                    },
                )
            }
            if (sessionChanged) changed = true
            if (sessionChanged) session.copy(messages = messages) else session
        }
        return out to changed
    }

    /** 新建会话并设为当前会话。 */
    suspend fun newSession(context: Context, title: String): ChatSession {
        val session = ChatSession(title = title)
        // 先丢掉上一个从没写过消息的草稿：空白会话不进侧边栏，留着只会
        // 在后台越堆越多，而且下次「新建」看着和没建一样。
        val blanks = _sessions.value.filter { it.messages.isEmpty() }
        _sessions.value = listOf(session) + (_sessions.value - blanks.toSet())
        _currentId.value = session.id
        persist(context)
        return session
    }

    /**
     * 重命名会话。
     *
     * 传空字符串表示恢复默认（回到「首条用户消息」推导的名字）。
     */
    suspend fun rename(context: Context, id: String, title: String) {
        val name = title.trim()
        _sessions.value = _sessions.value.map { s ->
            if (s.id == id) s.copy(customTitle = name.ifBlank { null }) else s
        }
        persist(context)
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
     * 把一轮 AI 回答落盘（**幂等**）。
     *
     * 一次请求的生命周期里会调用多次：
     * - 请求开始时用一条空的 `Running` 消息占位（这样切后台再回来，能看到「上次没跑完」）
     * - 每有过程进展（思考增量 / 工具状态变化 / 正文提交）更新同一条
     * - 收尾时写入终态与最终正文
     *
     * 用 [messageId] 定位同一条，所以**重复调用不会堆积多条**。
     * 早先每轮只在最后 `append` 一条纯文本，导致中断与进程死亡时过程全丢。
     */
    suspend fun upsertAssistant(
        context: Context,
        sessionId: String,
        messageId: String,
        content: String,
        segmentsJson: String?,
        turnState: TurnState,
        time: Long = System.currentTimeMillis(),
    ) {
        _sessions.value = _sessions.value
            .map { session ->
                if (session.id != sessionId) session
                else {
                    val existing = session.messages.indexOfFirst { it.id == messageId }
                    val updated = if (existing < 0) {
                        session.messages + ChatMessage(
                            id = messageId,
                            role = ChatMessage.Role.ASSISTANT,
                            content = content,
                            time = time,
                            segmentsJson = segmentsJson,
                            turnState = turnState,
                        )
                    } else {
                        val old = session.messages[existing]
                        session.messages.toMutableList().also { list ->
                            list[existing] = old.copy(
                                content = content,
                                segmentsJson = segmentsJson,
                                turnState = turnState,
                            )
                        }
                    }
                    session.copy(
                        messages = updated.takeLast(MAX_MESSAGES_PER_SESSION),
                        updatedAt = time,
                    )
                }
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

    /**
     * 补写用户消息上的「附件展示信息」与「只发给模型的上下文」。
     *
     * 单独开一个方法而不是扩充 [updateMessage]：那个方法的签名被会话列表、
     * 编辑等好几处使用，加两个只有编辑路径需要的参数会让所有调用点都要跟着改。
     */
    suspend fun patchUserExtras(
        context: Context,
        sessionId: String,
        messageId: String,
        attachments: List<AttachmentRef>,
        modelContext: String?,
    ) {
        _sessions.value = _sessions.value.map { session ->
            if (session.id != sessionId) session
            else session.copy(
                messages = session.messages.map { m ->
                    if (m.id != messageId) m
                    else m.copy(attachments = attachments, modelContext = modelContext)
                },
            )
        }
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
                        .put("edited", m.edited)
                        .put("turnState", m.turnState.name)
                        .put("imageUris", m.imageUris.let { list -> JSONArray().also { a -> list.forEach(a::put) } })
                        .put("attachments", m.attachments.takeIf { it.isNotEmpty() }
                            ?.let { AttachmentRef.encode(it) } ?: JSONObject.NULL)
                        .put("modelContext", m.modelContext ?: JSONObject.NULL)
                        .put("segments", m.segmentsJson ?: JSONObject.NULL)
                        .put(
                            "toolTrace",
                            m.toolTrace.takeIf { it.isNotEmpty() }?.let { list ->
                                JSONArray().also { arr ->
                                    list.forEach { t ->
                                        arr.put(
                                            JSONObject()
                                                .put("n", t.name)
                                                .put("a", t.argsJson ?: JSONObject.NULL)
                                                .put("r", t.result)
                                                .put("ok", t.success),
                                        )
                                    }
                                }
                            } ?: JSONObject.NULL,
                        ),
                )
            }
            arr.put(
                JSONObject()
                    .put("id", s.id)
                    .put("title", s.title)
                    // 自定义标题：没改过就不写，保持落盘文件干净
                    .put("customTitle", s.customTitle ?: JSONObject.NULL)
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
                    imageUris = m.optJSONArray("imageUris")?.let { a ->
                        (0 until a.length()).mapNotNull { k -> a.optString(k).takeIf { it.isNotBlank() } }
                    }.orEmpty(),
                    attachments = AttachmentRef.decode(
                        m.optString("attachments").takeIf { it.isNotBlank() && it != "null" },
                    ),
                    modelContext = m.optString("modelContext").takeIf { it.isNotBlank() && it != "null" },
                    // 只保留末尾若干段：超长回答可能积累出成百上千段，
                    // 全量读回来会让冷启动变慢，而用户真正会看的是结尾。
                    segmentsJson = m.optString("segments").takeIf { it.isNotBlank() && it != "null" }
                        ?.let(::trimSegments),
                    toolTrace = m.optJSONArray("toolTrace")?.let { a ->
                        (0 until a.length()).mapNotNull { k ->
                            a.optJSONObject(k)?.let { o ->
                                ToolTrace(
                                    name = o.optString("n"),
                                    argsJson = o.optString("a").takeIf { it.isNotBlank() && it != "null" },
                                    result = o.optString("r"),
                                    success = o.optBoolean("ok", true),
                                )
                            }
                        }
                    }.orEmpty(),
                    // 旧数据没有这个字段：默认 Done，避免把历史消息全判成「未完成」
                    turnState = runCatching {
                        TurnState.valueOf(m.optString("turnState"))
                    }.getOrDefault(TurnState.Done),
                    edited = m.optBoolean("edited", false),
                )
            }
            ChatSession(
                id = o.optString("id"),
                title = o.optString("title"),
                // 旧数据没有这个字段，optString 会给出 "null" 字面量，必须过滤掉
                customTitle = o.optString("customTitle")
                    .takeIf { it.isNotBlank() && it != "null" },
                createdAt = o.optLong("createdAt"),
                updatedAt = o.optLong("updatedAt"),
                messages = messages,
            )
        }
    }

    /**
     * 段落数超限时**保留头部与尾部**：头部的思考/工具是「这轮做了什么」的索引，
     * 尾部是用户最可能回看的结论，砍中间最不心疼。
     */
    private fun trimSegments(json: String): String = runCatching {
        val arr = JSONArray(json)
        if (arr.length() <= MAX_SEGMENTS_KEPT) return@runCatching json
        val head = MAX_SEGMENTS_KEPT / 3
        val tail = MAX_SEGMENTS_KEPT - head
        val kept = JSONArray()
        (0 until head).forEach { kept.put(arr.get(it)) }
        (arr.length() - tail until arr.length()).forEach { kept.put(arr.get(it)) }
        kept.toString()
    }.getOrDefault(json)
}

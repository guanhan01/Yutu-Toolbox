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

/**
 * 一条子记忆。
 *
 * 子记忆由 AI 创建（`memory.write`），也可由用户在记忆页手写；[enabled] 决定它
 * 是否参与注入——关掉的子记忆仍然留在磁盘上，只是不再占预算、不发给模型。
 */
data class MemoryEntry(
    val id: String = UUID.randomUUID().toString(),
    /** 短标题，用于记忆页列表。 */
    val title: String,
    /** 正文。 */
    val content: String,
    val enabled: Boolean = true,
    /** 由 AI 创建（记忆页据此标注来源，便于用户辨别哪些是模型自己写的）。 */
    val fromAi: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)

/** 记忆设置。 */
data class MemorySettings(
    /** 总开关：关掉后记忆完全不注入。 */
    val enabled: Boolean = true,
    /** 用户设定的记忆预算上限（token），实际生效值还会被模型窗口上限压低。 */
    val limitTokens: Int = DEFAULT_LIMIT_TOKENS,
) {
    companion object {
        const val DEFAULT_LIMIT_TOKENS = 1500
    }
}

/**
 * 记忆存储。
 *
 * 与上下文用量是两套独立的东西：记忆是「长期保留、每次请求都注入」的稳定前缀，
 * 上下文用量只统计当前会话的消息。二者唯一的交点是预算——记忆总量不能超过
 * 模型窗口能给它的份额，所以这里存一份模型窗口，由 app 模块在注入前刷新。
 */
object ChatMemoryStore {

    private const val FILE_NAME = "chat-memory.json"
    private const val PREFS = "ai-memory"
    private const val KEY_LIMIT_PREFS = "limitTokens"
    private const val KEY_ENABLED_PREFS = "enabled"

    /**
     * 记忆最多能占模型窗口的比例。
     *
     * 5% 是刻意的保守值：记忆是每轮都发的前缀，占用过多会持续挤掉对话本身的空间。
     */
    private const val MEMORY_WINDOW_SHARE = 0.05

    /** 模型窗口未知时用 0 表示；此时不额外收紧用户设定的上限。 */
    private const val UNKNOWN_MODEL_WINDOW = 0

    private val _core = MutableStateFlow("")
    val core: StateFlow<String> = _core.asStateFlow()

    private val _entries = MutableStateFlow<List<MemoryEntry>>(emptyList())
    val entries: StateFlow<List<MemoryEntry>> = _entries.asStateFlow()

    private val _settings = MutableStateFlow(MemorySettings())
    val settings: StateFlow<MemorySettings> = _settings.asStateFlow()

    /** 当前模型的**真实**窗口；0 表示未知，由 app 模块在注入前刷新。 */
    private val _modelWindow = MutableStateFlow(UNKNOWN_MODEL_WINDOW)
    val modelWindow: StateFlow<Int> = _modelWindow.asStateFlow()

    private var loaded = false

    // ---------- 预算 ----------

    /**
     * 记忆总预算（token）。
     *
     * 取「用户设置」与「模型窗口份额」中较小者：用户可以把上限调小，
     * 但调不大过模型能承受的份额——否则记忆本身就会把上下文挤爆。
     */
    fun effectiveLimitTokens(): Int {
        val window = _modelWindow.value
        // 窗口未知时不拿猜测值当上限：只按用户设定，避免算出一个假的天花板
        if (window <= 0) return _settings.value.limitTokens.coerceAtLeast(200)
        val cap = (window * MEMORY_WINDOW_SHARE).toInt().coerceAtLeast(200)
        return _settings.value.limitTokens.coerceIn(200, cap)
    }

    /** 刷新模型窗口（app 模块在拼装 system prompt 前调用）。 */
    fun syncModelWindow(context: Context, window: Int) {
        if (window <= 0) return
        _modelWindow.value = window
    }

    /** 当前已启用的记忆总量（token 估算）。 */
    fun usedTokens(): Int {
        val all = buildString {
            append(_core.value)
            _entries.value.filter { it.enabled }.forEach { append('\n').append(it.content) }
        }
        return MemoryBudget.estimateTokens(all)
    }

    // ---------- 读写 ----------

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _settings.value = MemorySettings(
            enabled = prefs.getBoolean(KEY_ENABLED_PREFS, true),
            limitTokens = prefs.getInt(KEY_LIMIT_PREFS, MemorySettings.DEFAULT_LIMIT_TOKENS),
        )
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) parse(file.readText(Charsets.UTF_8)) else "" to emptyList()
        }.onSuccess { snapshot ->
            _core.value = snapshot.first
            _entries.value = snapshot.second
        }
        loaded = true
    }

    /** 写入核心记忆。用户与 AI 都可以改，这里是唯一入口。 */
    suspend fun saveCore(context: Context, text: String) = withContext(Dispatchers.IO) {
        _core.value = text
        persist(context)
    }

    /** 设置总开关。 */
    suspend fun setEnabled(context: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        _settings.value = _settings.value.copy(enabled = enabled)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED_PREFS, enabled).apply()
    }

    /** 设置记忆预算上限。 */
    suspend fun setLimitTokens(context: Context, tokens: Int) = withContext(Dispatchers.IO) {
        val value = tokens.coerceAtLeast(200)
        _settings.value = _settings.value.copy(limitTokens = value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LIMIT_PREFS, value).apply()
    }

    /**
     * 新增或更新一条子记忆。
     *
     * AI 侧按 [title] 覆盖同名条目，避免模型每次「补充记忆」都堆一条新的出来。
     */
    suspend fun upsert(context: Context, title: String, content: String, fromAi: Boolean): MemoryEntry =
        withContext(Dispatchers.IO) {
            val existing = _entries.value.firstOrNull { it.title.equals(title.trim(), true) }
            val entry = existing?.copy(
                content = content,
                enabled = true,
                fromAi = fromAi,
                updatedAt = System.currentTimeMillis(),
            ) ?: MemoryEntry(title = title.trim(), content = content, fromAi = fromAi)
            _entries.value = if (existing == null) {
                _entries.value + entry
            } else {
                _entries.value.map { if (it.id == existing.id) entry else it }
            }
            persist(context)
            entry
        }

    /** 直接写一条子记忆（记忆页手写，按 id 精确更新）。 */
    suspend fun updateEntry(context: Context, entry: MemoryEntry) = withContext(Dispatchers.IO) {
        _entries.value = _entries.value.map { if (it.id == entry.id) entry else it }
        persist(context)
    }

    /** 删除一条子记忆。 */
    suspend fun deleteEntry(context: Context, id: String) = withContext(Dispatchers.IO) {
        _entries.value = _entries.value.filterNot { it.id == id }
        persist(context)
    }

    /** 开关一条子记忆。 */
    suspend fun setEntryEnabled(context: Context, id: String, enabled: Boolean) =
        withContext(Dispatchers.IO) {
            _entries.value = _entries.value.map {
                if (it.id == id) it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else it
            }
            persist(context)
        }

    // ---------- 注入 ----------

    /**
     * 组装要注入 system prompt 的记忆文本；没有内容或已关闭时返回 null。
     *
     * 注入顺序：核心记忆在前、子记忆在后。超出预算时从**子记忆末尾**往前丢——
     * 核心记忆是用户亲手写的高优先级信息，任何情况下都不因为预算被裁掉。
     */
    fun buildPrompt(): String? {
        if (!_settings.value.enabled) return null
        val core = _core.value.trim()
        val entries = _entries.value.filter { it.enabled && it.content.isNotBlank() }
        if (core.isEmpty() && entries.isEmpty()) return null

        val budget = effectiveLimitTokens()
        val sb = StringBuilder()
        sb.append("以下是用户的长期记忆，请在回答时遵守并加以利用。\n")

        var used = MemoryBudget.estimateTokens(sb.toString())
        if (core.isNotEmpty()) {
            val block = "【核心记忆】\n$core\n"
            sb.append(block)
            used += MemoryBudget.estimateTokens(block)
        }

        var keptCount = 0
        for (entry in entries) {
            val block = "【记忆：${entry.title}】\n${entry.content}\n"
            val cost = MemoryBudget.estimateTokens(block)
            if (used + cost > budget) break
            sb.append(block)
            used += cost
            keptCount++
        }

        val dropped = entries.size - keptCount
        if (dropped > 0) {
            sb.append("（另有 $dropped 条子记忆因超出记忆预算未载入）\n")
        }
        return sb.toString().trim()
    }

    /** 供记忆页展示：AI 可见的子记忆条数 / 总条数。 */
    fun counts(): Pair<Int, Int> {
        val all = _entries.value
        return all.count { it.enabled } to all.size
    }

    // ---------- 落盘 ----------

    private suspend fun persist(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val entries = JSONArray()
            _entries.value.forEach { e ->
                entries.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("title", e.title)
                        .put("content", e.content)
                        .put("enabled", e.enabled)
                        .put("fromAi", e.fromAi)
                        .put("updatedAt", e.updatedAt),
                )
            }
            val root = JSONObject()
                .put("core", _core.value)
                .put("entries", entries)
            File(context.filesDir, FILE_NAME).writeText(root.toString(), Charsets.UTF_8)
        }
    }

    private fun parse(text: String): Pair<String, List<MemoryEntry>> {
        val root = JSONObject(text)
        val entries = root.optJSONArray("entries") ?: JSONArray()
        val list = (0 until entries.length()).mapNotNull { i ->
            val o = entries.optJSONObject(i) ?: return@mapNotNull null
            val content = o.optString("content")
            if (content.isBlank()) return@mapNotNull null
            MemoryEntry(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                title = o.optString("title").ifBlank { "记忆" },
                content = content,
                enabled = o.optBoolean("enabled", true),
                fromAi = o.optBoolean("fromAi", false),
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
        }
        return root.optString("core") to list
    }
}

/**
 * 文本 → token 的粗略估算。
 *
 * 没有离线 tokenizer 可用，所以按字符类别估：CJK / 全角标点约 1 token 一个字，
 * 其余按 4 字符 1 token。两种语言混排时误差通常在 ±15% 以内，用于「预算控制」
 * 足够；界面上的用量数字另有服务端返回的真实值，不依赖这里。
 */
object MemoryBudget {
    fun estimateTokens(text: String): Int {
        if (text.isEmpty()) return 0
        var wide = 0
        var other = 0
        text.forEach { ch ->
            if (isWide(ch)) wide++ else other++
        }
        return wide + (other + 3) / 4
    }

    private fun isWide(ch: Char): Boolean {
        val code = ch.code
        return code in 0x1100..0x115F ||
            code in 0x2E80..0xA4CF ||
            code in 0xAC00..0xD7A3 ||
            code in 0xF900..0xFAFF ||
            code in 0xFE30..0xFE4F ||
            code in 0xFF00..0xFF60 ||
            code in 0xFFE0..0xFFE6
    }
}

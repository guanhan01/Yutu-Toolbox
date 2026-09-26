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
 * 一条子计划。
 *
 * [status] 用字符串而不是枚举：这些值会直接出现在发给模型的工具结果里，
 * 也让 AI 能自己改写状态而不必猜枚举名。
 */
data class PlanTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val status: String = STATUS_PENDING,
    val note: String = "",
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_DOING = "doing"
        const val STATUS_DONE = "done"
        const val STATUS_SKIPPED = "skipped"

        val ALL = listOf(STATUS_PENDING, STATUS_DOING, STATUS_DONE, STATUS_SKIPPED)
    }
}

/**
 * 一份计划：总目标 + 若干子计划。
 *
 * 计划是**真实状态**而不是提示词里的约定：它落盘、跨请求保留，AI 通过
 * `plan.update` 工具读写，界面据此画出进度。刷新或切会话都不会丢。
 */
data class PlanState(
    val sessionId: String,
    /** 总计划（总目标）。空表示这条会话还没有计划。 */
    val goal: String = "",
    val tasks: List<PlanTask> = emptyList(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val isEmpty: Boolean get() = goal.isBlank() && tasks.isEmpty()
    val doneCount: Int get() = tasks.count { it.status == PlanTask.STATUS_DONE }
    val progress: Float
        get() = if (tasks.isEmpty()) 0f else doneCount.toFloat() / tasks.size
}

/**
 * 计划模式开关与计划内容。
 *
 * 开关是**全局配置**（跟模型无关，用户想常开就常开），内容是**按会话**存的：
 * 一个会话一份计划，新建对话不会继承上一份。
 */
object PlanStore {

    private const val FILE_NAME = "chat-plans.json"
    private const val PREFS = "ai-plan"
    private const val KEY_ENABLED = "enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _plans = MutableStateFlow<Map<String, PlanState>>(emptyMap())
    val plans: StateFlow<Map<String, PlanState>> = _plans.asStateFlow()

    private var loaded = false

    fun planOf(sessionId: String?): PlanState? =
        sessionId?.let { _plans.value[it] }?.takeIf { !it.isEmpty }

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        _enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
        runCatching {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists()) parse(file.readText(Charsets.UTF_8)) else emptyMap()
        }.onSuccess { _plans.value = it }
        loaded = true
    }

    suspend fun setEnabled(context: Context, enabled: Boolean) = withContext(Dispatchers.IO) {
        _enabled.value = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * 覆盖式更新一份计划（AI 侧 `plan.update` 与界面勾选共用）。
     *
     * [tasks] 里若带了已有 id 就保留该 id（界面上正在展开的项不会因为重排而错位）；
     * 没有 id 的按顺序新建。
     */
    suspend fun update(
        context: Context,
        sessionId: String,
        goal: String?,
        tasks: List<PlanTask>?,
    ): PlanState = withContext(Dispatchers.IO) {
        val current = _plans.value[sessionId] ?: PlanState(sessionId = sessionId)
        val next = current.copy(
            goal = goal?.takeIf { it.isNotBlank() } ?: current.goal,
            tasks = tasks ?: current.tasks,
            updatedAt = System.currentTimeMillis(),
        )
        _plans.value = _plans.value + (sessionId to next)
        persist(context)
        next
    }

    /** 更新单个子计划的状态（界面点勾）。 */
    suspend fun setTaskStatus(
        context: Context,
        sessionId: String,
        taskId: String,
        status: String,
    ) = withContext(Dispatchers.IO) {
        val current = _plans.value[sessionId] ?: return@withContext
        val next = current.copy(
            tasks = current.tasks.map { if (it.id == taskId) it.copy(status = status) else it },
            updatedAt = System.currentTimeMillis(),
        )
        _plans.value = _plans.value + (sessionId to next)
        persist(context)
    }

    /** 清空某会话的计划。 */
    suspend fun clear(context: Context, sessionId: String) = withContext(Dispatchers.IO) {
        _plans.value = _plans.value - sessionId
        persist(context)
    }

    /** 供注入：把当前计划写成给模型看的文本。 */
    fun buildPrompt(sessionId: String?): String? {
        if (!_enabled.value) return null
        val plan = planOf(sessionId) ?: return null
        val sb = StringBuilder()
        sb.append("当前计划模式已开启，以下是本会话的计划：\n")
        if (plan.goal.isNotBlank()) sb.append("总计划：${plan.goal}\n")
        if (plan.tasks.isNotEmpty()) {
            sb.append("子计划：\n")
            plan.tasks.forEachIndexed { index, task ->
                val mark = when (task.status) {
                    PlanTask.STATUS_DONE -> "[已完成]"
                    PlanTask.STATUS_DOING -> "[进行中]"
                    PlanTask.STATUS_SKIPPED -> "[已跳过]"
                    else -> "[待办]"
                }
                sb.append("${index + 1}. $mark ${task.title}")
                if (task.note.isNotBlank()) sb.append("（${task.note}）")
                sb.append('\n')
            }
            sb.append("请按计划推进；完成或调整时调用 plan.update 同步状态。\n")
        } else {
            sb.append("还没有子计划，请先用 plan.update 制定总计划与子计划。\n")
        }
        return sb.toString().trim()
    }

    private suspend fun persist(context: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject()
            _plans.value.values.forEach { plan ->
                val tasks = JSONArray()
                plan.tasks.forEach { t ->
                    tasks.put(
                        JSONObject()
                            .put("id", t.id)
                            .put("title", t.title)
                            .put("status", t.status)
                            .put("note", t.note),
                    )
                }
                root.put(
                    plan.sessionId,
                    JSONObject()
                        .put("goal", plan.goal)
                        .put("tasks", tasks)
                        .put("updatedAt", plan.updatedAt),
                )
            }
            File(context.filesDir, FILE_NAME).writeText(root.toString(), Charsets.UTF_8)
        }
    }

    private fun parse(text: String): Map<String, PlanState> {
        val root = JSONObject(text)
        val map = mutableMapOf<String, PlanState>()
        root.keys().forEach { sessionId ->
            val o = root.optJSONObject(sessionId) ?: return@forEach
            val tasksArr = o.optJSONArray("tasks") ?: JSONArray()
            val tasks = (0 until tasksArr.length()).mapNotNull { i ->
                val t = tasksArr.optJSONObject(i) ?: return@mapNotNull null
                val title = t.optString("title")
                if (title.isBlank()) return@mapNotNull null
                PlanTask(
                    id = t.optString("id").ifBlank { UUID.randomUUID().toString() },
                    title = title,
                    status = t.optString("status").takeIf { it in PlanTask.ALL }
                        ?: PlanTask.STATUS_PENDING,
                    note = t.optString("note"),
                )
            }
            val state = PlanState(
                sessionId = sessionId,
                goal = o.optString("goal"),
                tasks = tasks,
                updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
            )
            if (!state.isEmpty) map[sessionId] = state
        }
        return map
    }
}

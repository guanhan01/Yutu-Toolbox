package com.mcp.toolbox.feature.decompile.engine

import android.content.Context
import com.mcp.toolbox.feature.decompile.DecompileSession
import com.mcp.toolbox.feature.decompile.TaskRecord
import com.mcp.toolbox.feature.decompile.TaskState
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * 反编译任务的进程内状态中心：前台服务写入，Compose 界面订阅。
 * 会话历史持久化到 SharedPreferences，重启后仍可查看。
 */
object DecompileHub {

    private const val PREFS = "decompile-tasks"
    private const val KEY_HISTORY = "history"

    val session = MutableStateFlow<DecompileSession?>(null)
    val tasks = MutableStateFlow<List<TaskRecord>>(emptyList())
    val running = MutableStateFlow<TaskRecord?>(null)
    val activeSource = MutableStateFlow<ApkSource?>(null)

    /** 文件页 / 内置阅读器点「反编译」带来的待处理路径；反编译页取走后立即清空。 */
    val pendingPath = MutableStateFlow<String?>(null)

    /** Java 源码按类缓存，避免重复反编译。 */
    val javaCache = mutableMapOf<String, String>()

    /** smali 目录下的类文件树快照。 */
    val smaliFiles = MutableStateFlow<List<String>>(emptyList())

    fun loadHistory(context: Context) {
        if (tasks.value.isNotEmpty()) return
        tasks.value = readHistory(context)
    }

    /** 清空任务中心：内存列表和持久化历史一起清掉。 */
    fun clearTasks(context: Context) {
        tasks.value = emptyList()
        running.value = null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY_HISTORY).apply()
    }

    fun startTask(record: TaskRecord) {
        running.value = record
        tasks.value = (listOf(record) + tasks.value).take(50)
    }

    fun updateTask(id: String, transform: (TaskRecord) -> TaskRecord) {
        tasks.value = tasks.value.map { if (it.id == id) transform(it) else it }
        running.value = running.value?.let { if (it.id == id) transform(it) else it }
        if (tasks.value.firstOrNull()?.id == id) {
            val record = tasks.value.first { it.id == id }
            if (record.state != TaskState.RUNNING && record.state != TaskState.QUEUED) running.value = null
        }
    }

    fun persist(context: Context) {
        val array = JSONArray()
        tasks.value.take(30).forEach { record ->
            array.put(
                JSONObject().apply {
                    put("id", record.id)
                    put("apk", record.apkName)
                    put("engine", record.engine)
                    put("state", record.state.name)
                    put("message", record.message)
                    put("startedAt", record.startedAt)
                    put("finishedAt", record.finishedAt ?: 0L)
                    put("classes", record.classCount)
                    put("methods", record.methodCount)
                    put("fields", record.fieldCount)
                    put("strings", record.stringCount)
                    put("elapsed", record.elapsedMs)
                    put("error", record.error ?: "")
                    put("output", record.outputDir ?: "")
                },
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, array.toString()).apply()
    }

    private fun readHistory(context: Context): List<TaskRecord> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                val item = array.getJSONObject(index)
                TaskRecord(
                    id = item.optString("id"),
                    apkName = item.optString("apk"),
                    engine = item.optString("engine"),
                    state = runCatching { TaskState.valueOf(item.optString("state")) }.getOrDefault(TaskState.DONE),
                    progress = 1f,
                    message = item.optString("message"),
                    startedAt = item.optLong("startedAt"),
                    finishedAt = item.optLong("finishedAt").takeIf { it > 0 },
                    classCount = item.optInt("classes"),
                    methodCount = item.optInt("methods"),
                    fieldCount = item.optInt("fields"),
                    stringCount = item.optInt("strings"),
                    error = item.optString("error").ifBlank { null },
                    outputDir = item.optString("output").ifBlank { null },
                    elapsedMs = item.optLong("elapsed"),
                )
            }
        }.getOrDefault(emptyList())
    }
}

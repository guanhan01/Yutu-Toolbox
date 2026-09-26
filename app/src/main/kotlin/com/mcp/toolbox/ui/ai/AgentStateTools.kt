package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.feature.home.ChatMemoryStore
import com.mcp.toolbox.feature.home.MemoryBudget
import com.mcp.toolbox.feature.home.PlanStore
import com.mcp.toolbox.feature.home.PlanTask
import com.mcp.toolbox.feature.mcp.Schema
import com.mcp.toolbox.feature.mcp.ToolDef
import com.mcp.toolbox.feature.mcp.ToolResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * 计划模式与记忆两组工具。
 *
 * 放在 app 模块是因为它们要读写 feature:home 里的 [PlanStore] / [ChatMemoryStore]：
 * 那两者属于会话数据层，让 feature:mcp 反向依赖会成环，所以走 BuiltInToolSet 的
 * 注入点，与 Linux 工具同一套路。
 *
 * 这两个工具需要知道「当前是哪个会话」，而从 [ToolDef] 的签名里拿不到；
 * 由 [AiToolBridge.sessionId] 在每次请求前写入。ChatRunner 同一时刻只跑一个请求
 * （isBusy 拦截），所以一个共享字段足够，不存在两个会话互相覆盖的情况。
 */
object AgentStateTools {

    fun all(context: Context): List<ToolDef> = listOf(planUpdate(), memoryWrite(), memoryDelete())

    // ---------- 计划 ----------

    private fun planUpdate() = ToolDef(
        name = "plan.update",
        title = "更新计划",
        description = "制定或更新当前会话的计划：总计划 + 子计划清单。" +
            "任务会自动保留标题相同的已有项状态；不传 tasks 表示只改总计划。" +
            "每次调整进度后都应调用，让界面与后续推理看到最新状态。",
        schema = Schema.obj(
            listOf(
                "goal" to Schema.string("总计划/总目标；留空表示保持原值"),
                "tasks" to JSONObject().apply {
                    put("type", "array")
                    put("description", "子计划清单，按执行顺序排列。status 可选 pending/doing/done/skipped")
                    put(
                        "items",
                        JSONObject().apply {
                            put("type", "object")
                            put(
                                "properties",
                                JSONObject().apply {
                                    put("title", Schema.string("子计划内容"))
                                    put("status", Schema.string("状态", enum = PlanTask.ALL))
                                    put("note", Schema.string("备注，可空"))
                                },
                            )
                            put("required", JSONArray(listOf("title")))
                        },
                    )
                },
            ),
        ),
        readOnly = false,
        handler = { ctx, args ->
            val sessionId = AiToolBridge.sessionId
                ?: throw IllegalStateException("当前没有进行中的会话，无法写入计划")
            val goal = args.optString("goal").takeIf { it.isNotBlank() }
            val tasks = args.optJSONArray("tasks")?.let { arr ->
                val current = PlanStore.planOf(sessionId)?.tasks.orEmpty()
                (0 until arr.length()).mapNotNull { i ->
                    val o = arr.optJSONObject(i) ?: return@mapNotNull null
                    val title = o.optString("title").trim()
                    if (title.isEmpty()) return@mapNotNull null
                    // 标题相同的沿用原 id：界面上已展开的子项不会因为重排而错位
                    val existing = current.firstOrNull { it.title == title }
                    PlanTask(
                        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                        title = title,
                        status = o.optString("status").takeIf { it in PlanTask.ALL }
                            ?: existing?.status ?: PlanTask.STATUS_PENDING,
                        note = o.optString("note").ifBlank { existing?.note.orEmpty() },
                    )
                }
            }
            val state = runBlocking { PlanStore.update(ctx, sessionId, goal, tasks) }
            val structured = JSONObject()
                .put("goal", state.goal)
                .put("total", state.tasks.size)
                .put("done", state.doneCount)
                .put(
                    "tasks",
                    JSONArray().also { arr ->
                        state.tasks.forEach { t ->
                            arr.put(JSONObject().put("title", t.title).put("status", t.status))
                        }
                    },
                )
            ToolResult(structured, "计划已更新：共 ${state.tasks.size} 项，已完成 ${state.doneCount} 项")
        },
    )

    // ---------- 记忆 ----------

    private fun memoryWrite() = ToolDef(
        name = "memory.write",
        title = "写入记忆",
        description = "把值得长期记住的信息写入记忆，跨会话有效。target=core 写核心记忆" +
            "（用户也能编辑，请谨慎覆盖，务必保留原有内容中有用的部分）；" +
            "target=entry 写一条子记忆，标题相同的会被覆盖。" +
            "适用于用户偏好、长期目标、既定事实；不要把一次性的对话细节写进来。",
        schema = Schema.obj(
            listOf(
                "target" to Schema.string("写入位置", default = "entry", enum = listOf("core", "entry")),
                "title" to Schema.string("子记忆标题（target=entry 时必填，简短，如「代码风格偏好」）"),
                "content" to Schema.string("记忆正文"),
            ),
            required = listOf("content"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            val content = args.getString("content").trim()
            if (content.isEmpty()) throw IllegalArgumentException("记忆正文不能为空")
            val target = args.optString("target", "entry")

            val usage = if (target == "core") {
                runBlocking { ChatMemoryStore.saveCore(ctx, content) }
                val used = ChatMemoryStore.usedTokens()
                val limit = ChatMemoryStore.effectiveLimitTokens()
                if (used > limit) {
                    throw IllegalStateException(
                        "核心记忆写入后总记忆量 ${used} 超过上限 ${limit} token：" +
                            "请先精简已有记忆再写入",
                    )
                }
                "核心记忆已更新，当前记忆用量 $used/$limit token"
            } else {
                val title = args.optString("title").trim().ifBlank {
                    throw IllegalArgumentException("写子记忆时必须提供 title")
                }
                val used = ChatMemoryStore.usedTokens()
                val limit = ChatMemoryStore.effectiveLimitTokens()
                val cost = MemoryBudget.estimateTokens(content) + MemoryBudget.estimateTokens(title)
                if (used + cost > limit) {
                    throw IllegalStateException(
                        "记忆量已达上限（$used/$limit token）：请先调用 memory.delete 删除过时记忆，" +
                            "或把内容压缩后再写入",
                    )
                }
                runBlocking { ChatMemoryStore.upsert(ctx, title, content, fromAi = true) }
                "子记忆「$title」已写入，当前记忆用量 ${used + cost}/$limit token"
            }
            ToolResult(JSONObject().put("target", target).put("ok", true), usage)
        },
    )

    private fun memoryDelete() = ToolDef(
        name = "memory.delete",
        title = "删除记忆",
        description = "删除一条子记忆（按标题匹配）。核心记忆不能通过工具删除，需要用户自己改。",
        schema = Schema.obj(
            listOf("title" to Schema.string("要删除的子记忆标题")),
            required = listOf("title"),
        ),
        readOnly = false,
        dangerous = true,
        handler = { ctx, args ->
            val title = args.getString("title").trim()
            val hit = ChatMemoryStore.entries.value.firstOrNull { it.title.equals(title, true) }
                ?: throw IllegalArgumentException("没有标题为「$title」的子记忆")
            runBlocking { ChatMemoryStore.deleteEntry(ctx, hit.id) }
            ToolResult(
                JSONObject().put("deleted", hit.title),
                "已删除子记忆「${hit.title}」",
            )
        },
    )
}

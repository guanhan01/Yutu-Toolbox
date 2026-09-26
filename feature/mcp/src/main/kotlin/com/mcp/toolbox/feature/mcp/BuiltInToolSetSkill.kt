package com.mcp.toolbox.feature.mcp

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Skill 相关的内置工具。
 *
 * 只暴露「读」这一个动作：Skill 的内容由用户自己维护，让模型改本地文件
 * 会把导入的内容和模型输出混在一起，出了问题很难分辨是谁写的。
 */
object BuiltInToolSetSkill {

    private const val MAX_BODY_CHARS = 24_000
    private const val MAX_FILE_CHARS = 16_000

    fun all(): List<ToolDef> = listOf(listSkills, readSkill)

    private val listSkills = ToolDef(
        name = "skill.list",
        title = "Skill 列表",
        description = "列出用户已启用的 Skill（本地知识包）及其描述与 id。",
        schema = Schema.obj(emptyList()),
        handler = { _, _ ->
            val enabled = SkillStore.enabled
            if (enabled.isEmpty()) {
                ToolResult(
                    JSONObject().put("skills", JSONArray()),
                    "当前没有已启用的 Skill。",
                )
            } else {
                val arr = JSONArray()
                enabled.forEach { skill ->
                    arr.put(
                        JSONObject()
                            .put("id", skill.id)
                            .put("name", skill.name)
                            .put("description", skill.description),
                    )
                }
                val text = buildString {
                    append("已启用 ").append(enabled.size).append(" 个 Skill：\n")
                    enabled.forEach { append("- ").append(it.id).append("（").append(it.name).append("）\n") }
                }.trim()
                ToolResult(JSONObject().put("skills", arr), text)
            }
        },
    )

    private val readSkill = ToolDef(
        name = "skill.read",
        title = "读取 Skill",
        description = "按 id 读取某个 Skill 的完整内容：默认是 SKILL.md 正文，也可读附带文件。",
        schema = Schema.obj(
            listOf(
                "id" to Schema.string("Skill 的 id，取自 skill.list 或系统提示里的清单"),
                "file" to Schema.string("可选。读取附带文件而非 SKILL.md，例如 references/guide.md", default = ""),
            ),
            required = listOf("id"),
        ),
        handler = { ctx, args ->
            val id = args.optString("id").trim()
            if (id.isBlank()) error("缺少参数 id")
            val all = SkillStore.skills.value
            val skill = all.firstOrNull { it.id == id }
                ?: error("没有名为 $id 的 Skill。可用：${all.joinToString { it.id }}")
            val file = args.optString("file").trim()
            if (file.isBlank()) {
                val body = SkillStore.bodyNow(ctx, id)
                if (body.isBlank()) error("Skill $id 的正文为空")
                val truncated = body.length > MAX_BODY_CHARS
                val content = body.take(MAX_BODY_CHARS)
                ToolResult(
                    JSONObject()
                        .put("id", skill.id)
                        .put("name", skill.name)
                        .put("content", content),
                    "# ${skill.name}\n\n$content" + if (truncated) "\n\n（内容过长，已截断）" else "",
                )
            } else {
                if (file.contains("..") || file.startsWith("/")) error("非法的文件路径：$file")
                if (file !in skill.files) {
                    error("Skill $id 里没有 $file。可用文件：${skill.files.joinToString()}")
                }
                val target = File(File(SkillStore.root(ctx), skill.id), file)
                if (!target.isFile) error("文件不存在：$file")
                val text = runCatching { target.readText() }
                    .getOrElse { error("无法读取（可能是二进制文件）：$file") }
                val truncated = text.length > MAX_FILE_CHARS
                val content = text.take(MAX_FILE_CHARS)
                ToolResult(
                    JSONObject().put("id", skill.id).put("file", file).put("content", content),
                    content + if (truncated) "\n\n（内容过长，已截断）" else "",
                )
            }
        },
    )
}

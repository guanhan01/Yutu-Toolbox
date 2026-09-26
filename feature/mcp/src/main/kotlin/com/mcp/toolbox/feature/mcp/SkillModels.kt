package com.mcp.toolbox.feature.mcp

import org.json.JSONObject
import java.util.Locale

/**
 * 一个本地 Skill。
 *
 * 目录约定与 Eta / Claude 的 skill 一致：`skills/<id>/SKILL.md`，
 * 正文之前是一段 YAML frontmatter（`name` / `description` / `version`），
 * 其余当作说明正文。附带资源放同目录的 `scripts/`、`references/`、`assets/`。
 *
 * 应用本身**不内置任何 Skill**，全部由用户导入。
 */
data class Skill(
    /** 目录名兼唯一标识，由名称派生并去重。 */
    val id: String,
    val name: String,
    val description: String = "",
    val version: String = "",
    /** 是否启用；只有启用的 Skill 才会进入 AI 的系统提示。 */
    val enabled: Boolean = true,
    /** 导入方式的展示名（文件 / ZIP / 文件夹 / URL / JSON / 剪贴板）。 */
    val source: String = "",
    val importedAt: Long = 0L,
    /** 目录总字节数，用于列表展示。 */
    val bytes: Long = 0L,
    /** 相对目录的文件清单，长按详情里展示。 */
    val files: List<String> = emptyList(),
) {
    /** 列表副标题：优先描述，没有描述时退回来源与大小。 */
    val summary: String
        get() = description.ifBlank {
            buildString {
                if (source.isNotBlank()) append("来自").append(source)
                if (bytes > 0) {
                    if (isNotEmpty()) append(" · ")
                    append(formatBytes(bytes))
                }
            }.ifBlank { "（无描述）" }
        }

    /** 从内存模型渲染回 SKILL.md 文本。 */
    fun toMarkdown(body: String): String = buildString {
        append("---\n")
        append("name: ").append(name).append('\n')
        if (description.isNotBlank()) append("description: ").append(description).append('\n')
        if (version.isNotBlank()) append("version: ").append(version).append('\n')
        append("---\n\n")
        append(body.trim()).append('\n')
    }

    companion object {
        fun formatBytes(bytes: Long): String = when {
            bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
            bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }

        /**
         * 由名称派生目录名。
         *
         * 只留小写字母、数字与连字符：目录名同时是工具里引用的 id，
         * 出现空格、斜杠或中文会让路径处理与引用都变复杂。
         */
        fun slugify(raw: String): String {
            val lowered = raw.trim().lowercase(Locale.ROOT)
            val sb = StringBuilder()
            var lastDash = false
            lowered.forEach { ch ->
                when {
                    ch.isLetterOrDigit() && ch.code < 128 -> {
                        sb.append(ch)
                        lastDash = false
                    }

                    ch == '-' || ch == '_' || ch == ' ' || ch == '.' -> {
                        if (sb.isNotEmpty() && !lastDash) {
                            sb.append('-')
                            lastDash = true
                        }
                    }
                    // 非 ASCII（中文等）直接跳过，避免生成不可读的目录名
                    else -> Unit
                }
            }
            val slug = sb.toString().trim('-')
            return slug.ifBlank { "skill" }
        }
    }
}

/**
 * SKILL.md 的解析结果。
 *
 * [body] 是 frontmatter 之后的正文；[extra] 保留未识别的键，导出时原样写回，
 * 以免把用户自己加的自定义字段洗掉。
 */
data class ParsedSkill(
    val name: String,
    val description: String,
    val version: String,
    val body: String,
    val extra: Map<String, String> = emptyMap(),
)

object SkillMarkdown {

    private val FRONTMATTER = Regex("^\\s*---\\s*\\r?\\n(.*?)\\r?\\n---\\s*\\r?\\n?", RegexOption.DOT_MATCHES_ALL)

    /**
     * 解析 SKILL.md。
     *
     * 没有 frontmatter 时不算失败：整篇当正文，名称取 [fallbackName]。
     * 用户从别处抄来的一份纯 Markdown 说明也应当能直接导入。
     */
    fun parse(text: String, fallbackName: String): ParsedSkill {
        val normalized = text.replace("\r\n", "\n")
        val match = FRONTMATTER.find(normalized)
        if (match == null) {
            return ParsedSkill(
                name = fallbackName,
                description = "",
                version = "",
                body = normalized.trim(),
            )
        }
        val head = match.groupValues[1]
        val body = normalized.substring(match.range.last + 1).trim()
        val map = linkedMapOf<String, String>()
        head.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val idx = trimmed.indexOf(':')
            if (idx <= 0) return@forEach
            val key = trimmed.substring(0, idx).trim()
            val value = trimmed.substring(idx + 1).trim().trim('"', '\'')
            if (key.isNotEmpty()) map[key] = value
        }
        return ParsedSkill(
            name = map["name"]?.takeIf { it.isNotBlank() } ?: fallbackName,
            description = map["description"].orEmpty(),
            version = map["version"].orEmpty(),
            body = body,
            extra = map.filterKeys { it !in KNOWN_KEYS },
        )
    }

    private val KNOWN_KEYS = setOf("name", "description", "version")

    /**
     * 解析导入用的 JSON。
     *
     * 接受单个对象，也接受对象数组（一次导入多个）。字段：
     * `name`（必需）、`description`、`version`、`content`（正文）、
     * `files`（可选，`{"相对路径": "文本内容"}`）。
     */
    fun parseJson(text: String): List<SkillDraft> {
        val trimmed = text.trim()
        val array = when {
            trimmed.startsWith("[") -> org.json.JSONArray(trimmed)
            else -> org.json.JSONArray().put(JSONObject(trimmed))
        }
        val drafts = mutableListOf<SkillDraft>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val name = obj.optString("name").trim()
            if (name.isBlank()) continue
            val files = linkedMapOf<String, String>()
            obj.optJSONObject("files")?.let { f ->
                f.keys().forEach { key -> files[key] = f.optString(key) }
            }
            drafts += SkillDraft(
                name = name,
                description = obj.optString("description"),
                version = obj.optString("version"),
                body = obj.optString("content"),
                files = files,
            )
        }
        return drafts
    }
}

/** 一次导入的中间产物：正文 + 附带文件，尚未落盘。 */
data class SkillDraft(
    val name: String,
    val description: String = "",
    val version: String = "",
    val body: String = "",
    /** 相对路径 -> 文本内容。二进制资源用 [binaryFiles]。 */
    val files: Map<String, String> = emptyMap(),
    /** 相对路径 -> 原始字节（ZIP / 文件夹导入的二进制资源）。 */
    val binaryFiles: Map<String, ByteArray> = emptyMap(),
)

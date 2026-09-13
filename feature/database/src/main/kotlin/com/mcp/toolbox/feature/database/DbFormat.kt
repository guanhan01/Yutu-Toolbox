package com.mcp.toolbox.feature.database

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import java.io.File

/** 通用格式化：字节数、时间、单元格摘要。 */
object DbFormat {

    fun bytes(size: Long): String = when {
        size < 1024 -> "$size B"
        size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
        size < 1024L * 1024 * 1024 -> "%.1f MB".format(size / 1024.0 / 1024.0)
        else -> "%.2f GB".format(size / 1024.0 / 1024.0 / 1024.0)
    }

    fun cell(value: String?, maxChars: Int = 42): String {
        val text = value.orEmpty()
        if (text.length <= maxChars) return text
        return text.take(maxChars - 1) + "…"
    }

    fun columnBadge(column: ColumnInfo): String = buildString {
        append(column.type.ifBlank { "ANY" })
        if (column.primaryKey) append(" · PK")
        if (column.notNull) append(" · NN")
    }
}

/**
 * SQL 自动补全：关键字 + 当前库真实的表名与列名，供编辑器下方的建议条使用。
 */
object SqlSuggestions {

    val keywords = listOf(
        "SELECT", "FROM", "WHERE", "GROUP BY", "ORDER BY", "HAVING", "LIMIT", "OFFSET", "JOIN",
        "LEFT JOIN", "INNER JOIN", "ON", "INSERT INTO", "VALUES", "UPDATE", "SET", "DELETE FROM",
        "CREATE TABLE", "CREATE INDEX", "DROP TABLE", "ALTER TABLE", "PRAGMA table_info",
        "EXPLAIN QUERY PLAN", "VACUUM", "BEGIN", "COMMIT", "ROLLBACK", "DISTINCT", "COUNT(*)",
        "SUM(", "AVG(", "MAX(", "MIN(", "AS", "AND", "OR", "NOT", "NULL", "IS NULL", "LIKE", "IN (",
    )

    /** 依据光标前的词返回候选；空输入时给关键字与表名。 */
    fun suggest(script: String, tables: List<String>, columns: Map<String, List<String>>, limit: Int = 12): List<String> {
        val tail = script.takeLast(64).split(Regex("[^A-Za-z0-9_.]")).lastOrNull().orEmpty()
        val pool = buildList {
            addAll(tables)
            columns.values.forEach { addAll(it) }
            addAll(keywords)
        }.distinct()
        if (tail.isBlank()) {
            return (keywords.take(6) + tables).distinct().take(limit)
        }
        return pool.filter { it.startsWith(tail, ignoreCase = true) && !it.equals(tail, ignoreCase = true) }
            .take(limit)
    }

    /** 简单 SQL 模板：选中表时直接生成可执行语句。 */
    fun templates(table: String, columns: List<String>): List<Pair<String, String>> {
        val columnList = columns.joinToString(", ")
        val first = columns.firstOrNull() ?: "id"
        return listOf(
            "查前 20 行" to "SELECT * FROM \"$table\" LIMIT 20;",
            "统计行数" to "SELECT COUNT(*) AS total FROM \"$table\";",
            "列清单" to "SELECT $columnList FROM \"$table\" LIMIT 50;",
            "按列分组" to "SELECT \"$first\", COUNT(*) AS cnt FROM \"$table\" GROUP BY \"$first\" ORDER BY cnt DESC LIMIT 20;",
            "表结构" to "PRAGMA table_info(\"$table\");",
        )
    }
}

/** 导出目标：优先使用用户授权的 SAF 目录，未授权时落到应用私有导出目录。 */
object DbExport {

    fun defaultDirectory(context: Context): File = File(context.filesDir, "mcp-exports").apply { mkdirs() }

    /** 真实写入：SAF 目录经 DocumentsContract 创建文档，未授权时落应用私有目录。 */
    fun write(context: Context, treeUri: String?, fileName: String, content: String): Pair<File?, String> {
        val safeName = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val local = File(defaultDirectory(context), safeName)
        if (treeUri != null) {
            val written = runCatching {
                val tree = Uri.parse(treeUri)
                val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                val created = DocumentsContract.createDocument(
                    context.contentResolver,
                    parent,
                    "text/plain",
                    safeName,
                ) ?: return@runCatching false
                context.contentResolver.openOutputStream(created, "wt")?.use { it.write(content.toByteArray()) }
                true
            }.getOrDefault(false)
            if (written) return null to "已导出到所选目录：$safeName"
        }
        return runCatching {
            local.writeText(content)
            local to "已导出到 ${local.absolutePath}"
        }.getOrElse { null to "导出失败：${it.message}" }
    }

    /** 把真实数据库文件的字节写回 SAF 原文件（用于编辑后保存）。 */
    fun writeBack(context: Context, uri: String, file: File): Pair<Boolean, String> = runCatching {
        context.contentResolver.openOutputStream(Uri.parse(uri), "wt")?.use { output ->
            file.inputStream().use { input -> input.copyTo(output) }
        } ?: return false to "无法打开目标文件"
        true to "已保存回原文件（${DbFormat.bytes(file.length())}）"
    }.getOrElse { false to "保存失败：${it.message ?: it.javaClass.simpleName}" }

    fun historyJson(items: List<String>): String = JSONArray(items).toString(2)
}

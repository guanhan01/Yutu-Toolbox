package com.mcp.toolbox.feature.mcp

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.mcp.toolbox.feature.database.SqliteEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 直接调用本应用「数据库」模块的引擎（SqliteEngine）：
 * 列表、结构、分页数据、只读查询、写执行、整库 dump、CSV 导入。
 *
 * 写操作一律要求开启「允许写入」，只读查询走 OPEN_READONLY。
 */
object BuiltInToolSetDb {

    fun all(context: Context): List<ToolDef> = listOf(
        dbList(context),
        dbTables(context),
        dbSchema(context),
        dbRows(context),
        dbQuery(context),
        dbExec(context),
        dbDump(context),
        dbImportCsv(context),
    )

    private fun requireWrite() {
        if (!BuiltInMcpServer.config.value.allowWrite) {
            error("内置 Server 未开启写入：请在 MCP 页面打开「允许写入」后重试")
        }
    }

    /** db 参数：sample / 空 表示应用示例库，其余按允许根内的路径解析。 */
    private fun dbFile(context: Context, raw: String): File {
        val name = raw.trim()
        return if (name.isEmpty() || name == "sample") context.getDatabasePath("toolbox.db") else ToolSupport.resolve(context, name)
    }

    private fun open(context: Context, raw: String, readOnly: Boolean): Pair<SQLiteDatabase, File> {
        val file = dbFile(context, raw)
        require(file.isFile) { "数据库文件不存在：${file.absolutePath}" }
        val db = SqliteEngine.open(file.absolutePath, readOnly)
        return db to file
    }

    private fun rowsJson(columns: List<String>, rows: List<List<String>>): JSONObject {
        val array = JSONArray()
        rows.forEach { row ->
            val obj = JSONObject()
            columns.forEachIndexed { index, name -> obj.put(name, row.getOrNull(index) ?: JSONObject.NULL) }
            array.put(obj)
        }
        return JSONObject().put("columns", JSONArray(columns)).put("rowCount", array.length()).put("rows", array)
    }

    private fun dbList(context: Context) = ToolDef(
        name = "db.list",
        title = "可访问的数据库",
        description = "列出应用数据库目录与常见位置下的 SQLite 文件（.db/.sqlite），含大小与修改时间；sample 指向应用内置示例库。",
        schema = Schema.obj(
            listOf("path" to Schema.string("额外扫描的目录，默认扫描应用数据库目录与 /sdcard/Download", default = "")),
        ),
        handler = { ctx, args ->
            val files = JSONArray()
            val dirs = mutableListOf(ctx.getDatabasePath("toolbox.db").parentFile, File("/sdcard/Download"))
            val extra = args.optString("path").trim()
            if (extra.isNotEmpty()) dirs.add(ToolSupport.resolve(ctx, extra))
            dirs.filterNotNull().forEach { dir ->
                if (!dir.isDirectory) return@forEach
                dir.walkTopDown().maxDepth(3).forEach { file ->
                    if (file.isFile && (file.extension == "db" || file.extension == "sqlite" || file.extension == "sqlite3")) {
                        files.put(
                            JSONObject().apply {
                                put("path", file.absolutePath)
                                put("name", file.name)
                                put("sizeBytes", file.length())
                                put("sizeHuman", ToolSupport.human(file.length()))
                                put("modifiedAt", file.lastModified())
                            },
                        )
                    }
                }
            }
            val sample = ctx.getDatabasePath("toolbox.db")
            val structured = JSONObject().apply {
                put("samplePath", sample.absolutePath)
                put("sampleExists", sample.isFile)
                put("count", files.length())
                put("databases", files)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun dbTables(context: Context) = ToolDef(
        name = "db.tables",
        title = "数据表与视图",
        description = "读取数据库的全部表与视图（含行数估计），用于快速了解库结构。",
        schema = Schema.obj(
            listOf("db" to Schema.string("sample 或数据库文件路径", default = "sample")),
        ),
        handler = { ctx, args ->
            val (db, file) = open(ctx, args.optString("db"), true)
            try {
                val schemas = runBlocking { SqliteEngine.schemas(db) }
                val rows = JSONArray()
                schemas.forEach { schema ->
                    rows.put(
                        JSONObject().apply {
                            put("name", schema.name)
                            put("type", schema.type)
                            put("rowCount", schema.rowCount)
                            put("columnCount", schema.columns.size)
                        },
                    )
                }
                val structured = JSONObject().apply {
                    put("db", file.absolutePath)
                    put("count", rows.length())
                    put("tables", rows)
                }
                ToolResult(structured, structured.toString(2))
            } finally {
                db.close()
            }
        },
    )

    private fun dbSchema(context: Context) = ToolDef(
        name = "db.schema",
        title = "表结构",
        description = "返回指定表的字段定义（名称、类型、非空、默认值、主键）与行数；不传 table 时返回所有表的字段。",
        schema = Schema.obj(
            listOf(
                "db" to Schema.string("sample 或数据库文件路径", default = "sample"),
                "table" to Schema.string("表名，可空（空表示全部表）", default = ""),
            ),
        ),
        handler = { ctx, args ->
            val (db, file) = open(ctx, args.optString("db"), true)
            try {
                val schemas = runBlocking { SqliteEngine.schemas(db) }
                val want = args.optString("table").trim()
                val rows = JSONArray()
                schemas.filter { want.isEmpty() || it.name == want }.forEach { schema ->
                    val columns = JSONArray()
                    schema.columns.forEach { column ->
                        columns.put(
                            JSONObject().apply {
                                put("name", column.name)
                                put("type", column.type)
                                put("notNull", column.notNull)
                                put("defaultValue", column.defaultValue ?: JSONObject.NULL)
                                put("primaryKey", column.primaryKey)
                            },
                        )
                    }
                    rows.put(
                        JSONObject().apply {
                            put("table", schema.name)
                            put("type", schema.type)
                            put("rowCount", schema.rowCount)
                            put("columns", columns)
                        },
                    )
                }
                val structured = JSONObject().apply {
                    put("db", file.absolutePath)
                    put("count", rows.length())
                    put("schemas", rows)
                }
                ToolResult(structured, structured.toString(2))
            } finally {
                db.close()
            }
        },
    )

    private fun dbRows(context: Context) = ToolDef(
        name = "db.rows",
        title = "分页读取表数据",
        description = "按 limit/offset 读取表数据，可指定排序字段与升降序，返回列名与行数据（防止一次性拉爆大表）。",
        schema = Schema.obj(
            listOf(
                "table" to Schema.string("表名"),
                "db" to Schema.string("sample 或数据库文件路径", default = "sample"),
                "limit" to Schema.integer("每页行数", default = 50, min = 1, max = 1000),
                "offset" to Schema.integer("偏移行数", default = 0, min = 0, max = 10000000),
                "orderBy" to Schema.string("排序字段，可空", default = ""),
                "desc" to Schema.bool("是否倒序", default = false),
            ),
            required = listOf("table"),
        ),
        handler = { ctx, args ->
            val (db, file) = open(ctx, args.optString("db"), true)
            try {
                val table = args.getString("table")
                val limit = args.optInt("limit", 50).coerceIn(1, 1000)
                val offset = args.optInt("offset", 0).coerceAtLeast(0)
                val orderBy = args.optString("orderBy").trim()
                val sql = buildString {
                    append("SELECT * FROM \"")
                    append(table.replace("\"", "\"\""))
                    append("\"")
                    if (orderBy.isNotEmpty()) {
                        append(" ORDER BY \"")
                        append(orderBy.replace("\"", "\"\""))
                        append("\"")
                        if (args.optBoolean("desc")) append(" DESC")
                    }
                    append(" LIMIT ")
                    append(limit)
                    append(" OFFSET ")
                    append(offset)
                }
                val started = System.currentTimeMillis()
                val cursor = db.rawQuery(sql, null)
                val columns = cursor.columnNames.toList()
                val data = mutableListOf<List<String>>()
                while (cursor.moveToNext()) {
                    data.add(columns.indices.map { index -> cursor.getString(index) ?: "" })
                }
                cursor.close()
                val structured = rowsJson(columns, data).apply {
                    put("db", file.absolutePath)
                    put("table", table)
                    put("sql", sql)
                    put("limit", limit)
                    put("offset", offset)
                    put("elapsedMs", System.currentTimeMillis() - started)
                }
                ToolResult(structured, structured.toString(2))
            } finally {
                db.close()
            }
        },
    )

    private fun dbQuery(context: Context) = ToolDef(
        name = "db.query",
        title = "只读 SQL 查询",
        description = "对指定数据库执行只读 SQL（SELECT / PRAGMA / WITH / EXPLAIN），返回列、行与耗时。",
        schema = Schema.obj(
            listOf(
                "sql" to Schema.string("只读 SQL 语句"),
                "db" to Schema.string("sample 或数据库文件路径", default = "sample"),
                "limit" to Schema.integer("最多返回行数", default = 200, min = 1, max = 5000),
            ),
            required = listOf("sql"),
        ),
        handler = { ctx, args ->
            val sql = args.getString("sql").trim()
            val head = sql.substringBefore(' ').uppercase()
            require(head in listOf("SELECT", "PRAGMA", "WITH", "EXPLAIN")) {
                "只读工具拒绝 $head：写操作请用 db.exec（需开启「允许写入」）"
            }
            val (db, file) = open(ctx, args.optString("db"), true)
            try {
                val limit = args.optInt("limit", 200).coerceIn(1, 5000)
                val started = System.currentTimeMillis()
                val cursor = db.rawQuery(sql, null)
                val columns = cursor.columnNames.toList()
                val data = mutableListOf<List<String>>()
                while (cursor.moveToNext() && data.size < limit) {
                    data.add(columns.indices.map { index -> cursor.getString(index) ?: "" })
                }
                cursor.close()
                val structured = rowsJson(columns, data).apply {
                    put("db", file.absolutePath)
                    put("sql", sql)
                    put("limit", limit)
                    put("elapsedMs", System.currentTimeMillis() - started)
                }
                ToolResult(structured, structured.toString(2))
            } finally {
                db.close()
            }
        },
    )

    private fun dbExec(context: Context) = ToolDef(
        name = "db.exec",
        title = "执行 SQL（可写）",
        description = "执行任意 SQL 脚本（支持多语句：建表、增删改、事务、VACUUM 等），返回每条语句的结果。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "sql" to Schema.string("SQL 脚本，可含多条语句（用分号分隔）"),
                "db" to Schema.string("sample 或数据库文件路径", default = "sample"),
            ),
            required = listOf("sql"),
        ),
        readOnly = false,
        dangerous = true,
        handler = { ctx, args ->
            requireWrite()
            val (db, file) = open(ctx, args.optString("db"), false)
            try {
                val result = runBlocking { SqliteEngine.execScript(db, args.getString("sql")) }
                val structured = JSONObject().apply {
                    put("db", file.absolutePath)
                    put("statements", result.statements)
                    put("isError", result.isError)
                    put("message", result.message)
                    put("elapsedMs", result.elapsedMs)
                    put("columns", JSONArray(result.columns))
                    val rows = JSONArray()
                    result.rows.forEach { row -> rows.put(JSONArray(row)) }
                    put("rowCount", rows.length())
                    put("rows", rows)
                    put("csv", runCatching { SqliteEngine.toCsv(result) }.getOrNull() ?: JSONObject.NULL)
                }
                ToolResult(structured, structured.toString(2))
            } finally {
                db.close()
            }
        },
    )

    private fun dbDump(context: Context) = ToolDef(
        name = "db.dump",
        title = "导出整库 SQL",
        description = "把数据库导出为可重建的 SQL 脚本（建表 + 数据 + 索引），并可写入文件。需要开启「允许写入」才落盘。",
        schema = Schema.obj(
            listOf(
                "db" to Schema.string("sample 或数据库文件路径", default = "sample"),
                "out" to Schema.string("输出 .sql 路径，可空（空则只返回文本片段）", default = ""),
                "maxChars" to Schema.integer("返回文本的最大字符数", default = 20000, min = 500, max = 400000),
            ),
        ),
        readOnly = false,
        handler = { ctx, args ->
            val (db, file) = open(ctx, args.optString("db"), true)
            try {
                val sql = runBlocking { SqliteEngine.dumpSql(db) }
                var written: File? = null
                val out = args.optString("out").trim()
                if (out.isNotEmpty()) {
                    requireWrite()
                    val target = ToolSupport.resolve(ctx, out)
                    target.parentFile?.mkdirs()
                    target.writeText(sql)
                    written = target
                }
                val limit = args.optInt("maxChars", 20000).coerceIn(500, 400000)
                val structured = JSONObject().apply {
                    put("db", file.absolutePath)
                    put("sqlLength", sql.length)
                    put("written", written?.absolutePath ?: JSONObject.NULL)
                    put("truncated", sql.length > limit)
                    put("sql", sql.take(limit))
                }
                ToolResult(structured, structured.toString(2))
            } finally {
                db.close()
            }
        },
    )

    private fun dbImportCsv(context: Context) = ToolDef(
        name = "db.importCsv",
        title = "导入 CSV 到表",
        description = "把 CSV 文本导入指定表（表需已存在），返回插入结果。适合先用 db.exec 建表再灌数据。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "table" to Schema.string("目标表名"),
                "csv" to Schema.string("CSV 文本（第一行为表头）"),
                "db" to Schema.string("sample 或数据库文件路径", default = "sample"),
            ),
            required = listOf("table", "csv"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            requireWrite()
            val (db, file) = open(ctx, args.optString("db"), false)
            try {
                val (ok, message) = runBlocking {
                    SqliteEngine.importCsv(db, args.getString("table"), args.getString("csv"))
                }
                val structured = JSONObject().apply {
                    put("db", file.absolutePath)
                    put("table", args.getString("table"))
                    put("ok", ok)
                    put("message", message)
                }
                ToolResult(structured, structured.toString(2))
            } finally {
                db.close()
            }
        },
    )
}

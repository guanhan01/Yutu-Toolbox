package com.mcp.toolbox.feature.database

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ---------------------------------------------------------------------------
// 领域模型
// ---------------------------------------------------------------------------

/** 一次 SQL 执行的结果：查询返回结果集，写操作返回受影响说明。 */
data class QueryResult(
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val elapsedMs: Long = 0,
    val message: String = "",
    val isError: Boolean = false,
    val statements: Int = 1,
) {
    val isGrid: Boolean get() = columns.isNotEmpty()
}

data class ColumnInfo(
    val name: String,
    val type: String,
    val notNull: Boolean,
    val defaultValue: String?,
    val primaryKey: Boolean,
)

data class IndexInfo(val name: String, val unique: Boolean, val columns: List<String>)

data class TableSchema(
    val name: String,
    val type: String,
    val rowCount: Int,
    val columns: List<ColumnInfo>,
    val indexes: List<IndexInfo>,
    val triggers: List<String>,
    val sql: String?,
)

/** 数据表分页结果；rowIds 用于单元格编辑定位（WITHOUT ROWID 表为空）。 */
data class GridPage(
    val table: String = "",
    val columns: List<String> = emptyList(),
    val rows: List<List<String>> = emptyList(),
    val rowIds: List<Long?> = emptyList(),
    val total: Int = 0,
    val offset: Int = 0,
    val nulls: List<List<Boolean>> = emptyList(),
    val elapsedMs: Long = 0,
    val error: String? = null,
)

/** 数据库来源：决定能否写回、如何保存。 */
sealed interface DbOrigin {
    val label: String

    data class Demo(override val label: String = "示例库（应用私有）") : DbOrigin
    data class Sandbox(val file: File) : DbOrigin {
        override val label: String get() = "应用沙箱"
    }
    data class Saf(val uri: String, val name: String) : DbOrigin {
        override val label: String get() = "外部文件（SAF）"
    }
    data class RootCopy(val source: String) : DbOrigin {
        override val label: String get() = "root 只读副本"
    }
}

data class DbHandle(
    val path: String,
    val db: SQLiteDatabase,
    val readOnly: Boolean,
    val origin: DbOrigin,
    val sizeBytes: Long,
    val pageSize: Int,
    val pageCount: Int,
    val userVersion: Int,
    val journalMode: String,
    val encoding: String,
) {
    val name: String get() = path.substringAfterLast('/')
    val writable: Boolean get() = !readOnly
}

data class RootEntry(val name: String, val path: String, val isDirectory: Boolean, val sizeBytes: Long)

data class RootResult(val code: Int, val stdout: String, val stderr: String) {
    val ok: Boolean get() = code == 0
}

private const val OPEN_READONLY = 0x00000001
private const val OPEN_READWRITE = 0x00000000

// ---------------------------------------------------------------------------
// 引擎：全部为真实 SQLite / 真实文件 IO
// ---------------------------------------------------------------------------

object SqliteEngine {

    /** 打开磁盘上的 SQLite 文件；readOnly 时使用 OPEN_READONLY 句柄。 */
    fun open(path: String, readOnly: Boolean): SQLiteDatabase =
        SQLiteDatabase.openDatabase(path, null, if (readOnly) OPEN_READONLY else OPEN_READWRITE)

    suspend fun handle(path: String, db: SQLiteDatabase, readOnly: Boolean, origin: DbOrigin): DbHandle =
        withContext(Dispatchers.IO) {
            fun pragmaInt(sql: String, fallback: Int): Int =
                runCatching { db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else fallback } }
                    .getOrDefault(fallback)

            fun pragmaText(sql: String, fallback: String): String =
                runCatching {
                    db.rawQuery(sql, null).use { if (it.moveToFirst()) it.getString(0) ?: fallback else fallback }
                }.getOrDefault(fallback)

            DbHandle(
                path = path,
                db = db,
                readOnly = readOnly,
                origin = origin,
                sizeBytes = File(path).length(),
                pageSize = pragmaInt("PRAGMA page_size", 0),
                pageCount = pragmaInt("PRAGMA page_count", 0),
                userVersion = pragmaInt("PRAGMA user_version", 0),
                journalMode = pragmaText("PRAGMA journal_mode", "unknown"),
                encoding = pragmaText("PRAGMA encoding", "UTF-8"),
            )
        }

    /** 结构浏览：表、视图、索引、触发器，全部来自 sqlite_master + PRAGMA。 */
    suspend fun schemas(db: SQLiteDatabase): List<TableSchema> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = mutableListOf<Triple<String, String, String?>>()
            db.rawQuery(
                "SELECT type, name, sql FROM sqlite_master WHERE type IN ('table','view') " +
                    "AND name NOT LIKE 'sqlite_%' ORDER BY type, name",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    raw += Triple(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                }
            }
            val triggerByTable = mutableMapOf<String, MutableList<String>>()
            db.rawQuery("SELECT name, tbl_name FROM sqlite_master WHERE type='trigger'", null).use { cursor ->
                while (cursor.moveToNext()) {
                    triggerByTable.getOrPut(cursor.getString(1)) { mutableListOf() } += cursor.getString(0)
                }
            }
            raw.map { (type, name, sql) ->
                val count = if (type == "table") {
                    runCatching {
                        db.rawQuery("SELECT COUNT(*) FROM \"$name\"", null)
                            .use { if (it.moveToFirst()) it.getInt(0) else 0 }
                    }.getOrDefault(-1)
                } else {
                    -1
                }
                TableSchema(
                    name = name,
                    type = type,
                    rowCount = count,
                    columns = columnsOf(db, name),
                    indexes = indexesOf(db, name),
                    triggers = triggerByTable[name].orEmpty(),
                    sql = sql,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun columnsOf(db: SQLiteDatabase, table: String): List<ColumnInfo> = runCatching {
        db.rawQuery("PRAGMA table_info(\"$table\")", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        ColumnInfo(
                            name = cursor.getString(1),
                            type = cursor.getString(2).orEmpty(),
                            notNull = cursor.getInt(3) == 1,
                            defaultValue = cursor.getString(4),
                            primaryKey = cursor.getInt(5) > 0,
                        ),
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    fun indexesOf(db: SQLiteDatabase, table: String): List<IndexInfo> = runCatching {
        val list = mutableListOf<IndexInfo>()
        db.rawQuery("PRAGMA index_list(\"$table\")", null).use { cursor ->
            while (cursor.moveToNext()) {
                list += IndexInfo(cursor.getString(1), cursor.getInt(2) == 1, indexColumns(db, cursor.getString(1)))
            }
        }
        list
    }.getOrDefault(emptyList())

    private fun indexColumns(db: SQLiteDatabase, index: String): List<String> = runCatching {
        db.rawQuery("PRAGMA index_info(\"$index\")", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(2)) }
        }
    }.getOrDefault(emptyList())

    /** 数据浏览：真实 LIMIT/OFFSET 分页 + 排序，附带 rowid 以便编辑。 */
    suspend fun gridPage(
        db: SQLiteDatabase,
        table: String,
        orderBy: String?,
        descending: Boolean,
        limit: Int,
        offset: Int,
    ): GridPage = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val order = orderBy?.takeIf { it.isNotBlank() }
            ?.let { " ORDER BY \"$it\" ${if (descending) "DESC" else "ASC"}" }
            ?: ""
        val withRowId = "SELECT rowid AS __rid, * FROM \"$table\"$order LIMIT $limit OFFSET $offset"
        val plain = "SELECT * FROM \"$table\"$order LIMIT $limit OFFSET $offset"

        fun read(cursor: Cursor, hasRid: Boolean): GridPage {
            val names = cursor.columnNames.toList()
            val columns = if (hasRid) names.drop(1) else names
            val rows = mutableListOf<List<String>>()
            val nulls = mutableListOf<List<Boolean>>()
            val rowIds = mutableListOf<Long?>()
            while (cursor.moveToNext()) {
                val values = mutableListOf<String>()
                val isNull = mutableListOf<Boolean>()
                for (index in columns.indices) {
                    val cursorIndex = if (hasRid) index + 1 else index
                    values += cursor.getString(cursorIndex) ?: "NULL"
                    isNull += cursor.isNull(cursorIndex)
                }
                rows += values
                nulls += isNull
                rowIds += if (hasRid) cursor.getLong(0) else null
            }
            return GridPage(
                table = table,
                columns = columns,
                rows = rows,
                rowIds = rowIds,
                total = countRows(db, table),
                offset = offset,
                nulls = nulls,
                elapsedMs = System.currentTimeMillis() - started,
            )
        }

        runCatching { db.rawQuery(withRowId, null).use { read(it, true) } }
            .recoverCatching { db.rawQuery(plain, null).use { read(it, false) } }
            .getOrElse { throwable ->
                GridPage(table = table, error = throwable.message ?: throwable.javaClass.simpleName, offset = offset)
            }
    }

    fun countRows(db: SQLiteDatabase, table: String): Int = runCatching {
        db.rawQuery("SELECT COUNT(*) FROM \"$table\"", null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }.getOrDefault(-1)

    /** 单元格更新 / 行删除 / 行插入：仅对 rowid 表开放，写操作由调用方二次确认。 */
    suspend fun updateCell(
        db: SQLiteDatabase,
        table: String,
        rowId: Long,
        column: String,
        value: String?,
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        runCatching {
            if (value == null) {
                db.execSQL("UPDATE \"$table\" SET \"$column\"=NULL WHERE rowid=$rowId")
            } else {
                db.execSQL("UPDATE \"$table\" SET \"$column\"=? WHERE rowid=$rowId", arrayOf(value))
            }
            true to "已更新 1 行"
        }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }
    }

    suspend fun deleteRow(db: SQLiteDatabase, table: String, rowId: Long): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                db.execSQL("DELETE FROM \"$table\" WHERE rowid=$rowId")
                true to "已删除 1 行"
            }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }
        }

    suspend fun insertRow(db: SQLiteDatabase, table: String, values: Map<String, String?>): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val columns = values.keys.toList()
                val quoted = columns.joinToString(", ") { "\"$it\"" }
                val placeholders = columns.joinToString(", ") { "?" }
                db.execSQL(
                    "INSERT INTO \"$table\" ($quoted) VALUES ($placeholders)",
                    columns.map { values[it] }.toTypedArray(),
                )
                true to "已插入 1 行"
            }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }
        }

    /**
     * 脚本执行：按真实语句边界切分（跳过字符串内部与行注释、块注释里的分号），
     * 逐条执行，返回最后一条查询语句的结果集。
     */
    suspend fun execScript(db: SQLiteDatabase, script: String): QueryResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val statements = splitStatements(script)
        if (statements.isEmpty()) {
            return@withContext QueryResult(message = "没有可执行的语句", elapsedMs = 0, statements = 0)
        }
        var last = QueryResult(statements = statements.size)
        var executed = 0
        try {
            statements.forEachIndexed { index, sql ->
                val head = sql.trimStart().substringBefore(' ').substringBefore('\n').lowercase()
                last = if (head in setOf("select", "pragma", "explain", "with", "values")) {
                    db.rawQuery(sql, null).use { cursor ->
                        val names = cursor.columnNames.toList()
                        val rows = buildList {
                            while (cursor.moveToNext()) {
                                add(names.indices.map { i -> cursor.getString(i) ?: "NULL" })
                            }
                        }
                        QueryResult(
                            columns = names,
                            rows = rows,
                            message = "第 ${index + 1} 条返回 ${rows.size} 行",
                            statements = statements.size,
                            elapsedMs = System.currentTimeMillis() - started,
                        )
                    }
                } else {
                    db.execSQL(sql)
                    QueryResult(
                        message = "第 ${index + 1} 条执行成功",
                        statements = statements.size,
                        elapsedMs = System.currentTimeMillis() - started,
                    )
                }
                executed++
            }
            last.copy(
                elapsedMs = System.currentTimeMillis() - started,
                message = if (statements.size == 1) {
                    last.message
                } else {
                    "共执行 $executed 条语句 · ${last.message}"
                },
                statements = statements.size,
            )
        } catch (throwable: Throwable) {
            QueryResult(
                elapsedMs = System.currentTimeMillis() - started,
                message = "第 ${executed + 1} 条出错：${throwable.message ?: throwable.javaClass.simpleName}",
                isError = true,
                statements = statements.size,
            )
        }
    }

    /** 语句切分：忽略引号内部与注释里的分号。 */
    fun splitStatements(script: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var index = 0
        while (index < script.length) {
            val c = script[index]
            when {
                quote != null -> {
                    current.append(c)
                    if (c == quote) quote = null
                    index++
                }
                c == '\'' || c == '"' || c == '`' -> {
                    quote = c
                    current.append(c)
                    index++
                }
                c == '-' && index + 1 < script.length && script[index + 1] == '-' -> {
                    while (index < script.length && script[index] != '\n') index++
                    current.append('\n')
                }
                c == '/' && index + 1 < script.length && script[index + 1] == '*' -> {
                    index += 2
                    while (index + 1 < script.length && !(script[index] == '*' && script[index + 1] == '/')) index++
                    index += 2
                }
                c == ';' -> {
                    if (current.isNotBlank()) result += current.toString().trim()
                    current.clear()
                    index++
                }
                else -> {
                    current.append(c)
                    index++
                }
            }
        }
        if (current.isNotBlank()) result += current.toString().trim()
        return result
    }

    // -----------------------------------------------------------------------
    // 导入导出
    // -----------------------------------------------------------------------

    fun toCsv(result: QueryResult): String {
        fun cell(value: String): String =
            if (value.contains(',') || value.contains('"') || value.contains('\n')) {
                "\"" + value.replace("\"", "\"\"") + "\""
            } else {
                value
            }
        return buildString {
            appendLine(result.columns.joinToString(",") { cell(it) })
            result.rows.forEach { row -> appendLine(row.joinToString(",") { cell(it) }) }
        }
    }

    fun toJson(result: QueryResult): String {
        val array = JSONArray()
        result.rows.forEach { row ->
            val obj = JSONObject()
            result.columns.forEachIndexed { index, column -> obj.put(column, row.getOrNull(index).orEmpty()) }
            array.put(obj)
        }
        return JSONObject()
            .put("columns", JSONArray(result.columns))
            .put("rowCount", result.rows.size)
            .put("rows", array)
            .toString(2)
    }

    /** 整库 SQL dump：schema + 全量 INSERT，可直接重建。 */
    suspend fun dumpSql(db: SQLiteDatabase): String = withContext(Dispatchers.IO) {
        val builder = StringBuilder()
        builder.appendLine("-- MCP Toolbox SQLite dump")
        builder.appendLine("PRAGMA foreign_keys=OFF;")
        builder.appendLine("BEGIN TRANSACTION;")
        val tables = mutableListOf<String>()
        runCatching {
            db.rawQuery(
                "SELECT name, sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    tables += cursor.getString(0)
                    cursor.getString(1)?.takeIf { it.isNotBlank() }?.let { builder.appendLine("$it;") }
                }
            }
            db.rawQuery(
                "SELECT sql FROM sqlite_master WHERE type IN ('index','view','trigger') AND sql IS NOT NULL",
                null,
            ).use { cursor ->
                while (cursor.moveToNext()) builder.appendLine("${cursor.getString(0)};")
            }
            tables.forEach { table ->
                val columns = columnsOf(db, table).map { it.name }
                if (columns.isEmpty()) return@forEach
                val columnList = columns.joinToString(", ") { "\"$it\"" }
                db.rawQuery("SELECT * FROM \"$table\"", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        val values = columns.indices.joinToString(", ") { index ->
                            if (cursor.isNull(index)) {
                                "NULL"
                            } else {
                                "'" + (cursor.getString(index) ?: "").replace("'", "''") + "'"
                            }
                        }
                        builder.appendLine("INSERT INTO \"$table\" ($columnList) VALUES ($values);")
                    }
                }
            }
        }
        builder.appendLine("COMMIT;")
        builder.toString()
    }

    /** CSV 导入：无表则按表头建表（全 TEXT），有表则按表头匹配列名，整体一个事务。 */
    suspend fun importCsv(db: SQLiteDatabase, table: String, csv: String): Pair<Boolean, String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val lines = parseCsvLines(csv)
                if (lines.isEmpty()) return@runCatching false to "CSV 为空"
                val header = lines.first()
                val existing = columnsOf(db, table).map { it.name }
                val created = existing.isEmpty()
                if (created) {
                    val definition = header.joinToString(", ") { "\"$it\" TEXT" }
                    db.execSQL("CREATE TABLE IF NOT EXISTS \"$table\" ($definition)")
                }
                val target = if (created) header else existing
                val indexMap = header.mapIndexedNotNull { index, name ->
                    if (target.contains(name)) name to index else null
                }
                if (indexMap.isEmpty()) return@runCatching false to "表头与目标表列名不匹配"
                val columnList = indexMap.joinToString(", ") { "\"${it.first}\"" }
                val placeholders = indexMap.joinToString(", ") { "?" }
                var inserted = 0
                db.beginTransaction()
                try {
                    val statement = db.compileStatement("INSERT INTO \"$table\" ($columnList) VALUES ($placeholders)")
                    lines.drop(1).forEach { row ->
                        if (row.all { it.isBlank() }) return@forEach
                        statement.clearBindings()
                        indexMap.forEachIndexed { slot, pair ->
                            statement.bindString(slot + 1, row.getOrElse(pair.second) { "" })
                        }
                        statement.executeInsert()
                        inserted++
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
                true to "导入 $inserted 行" + if (created) "（已新建表 $table）" else ""
            }.getOrElse { false to (it.message ?: it.javaClass.simpleName) }
        }

    /** RFC4180 风格解析：支持引号包裹、转义引号与逗号/换行。 */
    fun parseCsvLines(csv: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < csv.length) {
            val c = csv[index]
            when {
                inQuotes && c == '"' && index + 1 < csv.length && csv[index + 1] == '"' -> {
                    cell.append('"')
                    index += 2
                }
                c == '"' -> {
                    inQuotes = !inQuotes
                    index++
                }
                !inQuotes && c == ',' -> {
                    row += cell.toString()
                    cell.clear()
                    index++
                }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && index + 1 < csv.length && csv[index + 1] == '\n') index++
                    row += cell.toString()
                    cell.clear()
                    rows += row.toList()
                    row.clear()
                    index++
                }
                else -> {
                    cell.append(c)
                    index++
                }
            }
        }
        if (cell.isNotEmpty() || row.isNotEmpty()) {
            row += cell.toString()
            rows += row.toList()
        }
        return rows
    }

    // -----------------------------------------------------------------------
    // Room schema.json 校验
    // -----------------------------------------------------------------------

    /** 用真实 sqlite_master/PRAGMA 校验 Room 导出的 schema.json：表、列、类型 affinity、索引。 */
    suspend fun validateRoomSchema(db: SQLiteDatabase, schemaJson: String): Pair<Boolean, List<String>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val issues = mutableListOf<String>()
                val root = JSONObject(schemaJson)
                val database = root.optJSONArray("database")?.optJSONObject(0)
                    ?: return@runCatching false to listOf("不是合法的 Room schema：缺少 database[0]")
                val entities = database.optJSONArray("entities")
                    ?: return@runCatching false to listOf("不是合法的 Room schema：缺少 entities")
                val actual = schemas(db).associateBy { it.name }
                for (index in 0 until entities.length()) {
                    val entity = entities.getJSONObject(index)
                    val tableName = entity.optString("tableName").ifBlank { entity.optString("tableName") }
                    val table = actual[tableName]
                    if (table == null) {
                        issues += "缺少表 $tableName"
                        continue
                    }
                    val declared = mutableMapOf<String, String>()
                    val fields = entity.optJSONArray("fields") ?: JSONArray()
                    for (f in 0 until fields.length()) {
                        val field = fields.getJSONObject(f)
                        declared[field.optString("columnName")] = field.optString("affinity")
                    }
                    val real = table.columns.associate { it.name to it.type }
                    declared.forEach { (column, affinity) ->
                        val realType = real[column]
                        when {
                            realType == null -> issues += "表 $tableName 缺少列 $column"
                            targetAffinity(affinity) != targetAffinity(realType) ->
                                issues += "列 $tableName.$column 类型不一致：schema=$affinity 实际=$realType"
                        }
                    }
                    real.keys.filterNot { declared.containsKey(it) }.forEach {
                        issues += "表 $tableName 有 schema 未声明的列 $it"
                    }
                    val indices = entity.optJSONArray("indices") ?: JSONArray()
                    for (i in 0 until indices.length()) {
                        val name = indices.getJSONObject(i).optString("name")
                        if (table.indexes.none { it.name == name }) issues += "表 $tableName 缺少索引 $name"
                    }
                }
                issues.isEmpty() to issues
            }.getOrElse { false to listOf("解析失败：${it.message ?: it.javaClass.simpleName}") }
        }

    /** SQLite 类型亲和性判定（对应官方 affinity 规则）。 */
    fun targetAffinity(type: String): String {
        val upper = type.uppercase()
        return when {
            upper.contains("INT") -> "INTEGER"
            upper.contains("CHAR") || upper.contains("CLOB") || upper.contains("TEXT") -> "TEXT"
            upper.contains("BLOB") || upper.isEmpty() -> "BLOB"
            upper.contains("REAL") || upper.contains("FLOA") || upper.contains("DOUB") -> "REAL"
            else -> "NUMERIC"
        }
    }

    // -----------------------------------------------------------------------
    // root 只读通道（可选增强，失败即降级）
    // -----------------------------------------------------------------------

    /** 优先探测可用 su；false 表示当前设备没有可用 root 通道。 */
    suspend fun rootAvailable(): Boolean = withContext(Dispatchers.IO) {
        runRoot("id").stdout.contains("uid=0")
    }

    suspend fun runRoot(command: String, timeoutMs: Long = 15_000): RootResult = withContext(Dispatchers.IO) {
        val candidates = listOf("su", "/system/bin/su", "/system/xbin/su", "/sbin/su")
        var last = RootResult(-1, "", "没有可用的 su")
        for (binary in candidates) {
            val result = runCatching {
                val process = ProcessBuilder(binary, "-c", command).redirectErrorStream(false).start()
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val finished = process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) process.destroy()
                RootResult(if (finished) process.exitValue() else -1, stdout, stderr)
            }.getOrElse { RootResult(-1, "", it.message ?: "执行失败") }
            last = result
            if (result.ok && result.stdout.isNotEmpty()) return@withContext result
        }
        last
    }

    /** 通过 root 把数据库（含 -wal / -shm）复制到缓存目录后以只读句柄打开。 */
    suspend fun copyFromRoot(context: Context, source: String): Pair<File?, String> = withContext(Dispatchers.IO) {
        val directory = File(context.cacheDir, "rootcopy").apply { mkdirs() }
        val destination = File(directory, source.substringAfterLast('/').ifBlank { "database.db" })
        val probe = runRoot("test -f '$source' && echo yes")
        if (probe.stdout.trim() != "yes") {
            return@withContext null to "无法访问 $source（不存在或无权限）"
        }
        listOf("", "-wal", "-shm").forEach { suffix ->
            val side = "$source$suffix"
            val encoded = runRoot("base64 '$side' 2>/dev/null")
            if (encoded.ok && encoded.stdout.isNotBlank()) {
                val bytes = runCatching {
                    android.util.Base64.decode(encoded.stdout.replace("\n", ""), android.util.Base64.DEFAULT)
                }.getOrNull() ?: return@forEach
                val target = if (suffix.isEmpty()) destination else File(directory, destination.name + suffix)
                target.writeBytes(bytes)
            }
        }
        if (!destination.exists() || destination.length() == 0L) {
            destination to "复制失败：空文件"
        } else {
            destination to "已复制 ${destination.length()} 字节到 ${destination.absolutePath}"
        }
    }

    /** root 目录浏览：解析 ls -lA 输出，只读元数据。 */
    suspend fun listRootDirectory(path: String): Pair<List<RootEntry>, String> = withContext(Dispatchers.IO) {
        val result = runRoot("ls -lA '$path'")
        if (!result.ok) {
            return@withContext emptyList<RootEntry>() to result.stderr.ifBlank { "无法读取 $path（需要 root）" }.trim()
        }
        val entries = result.stdout.lineSequence().mapNotNull { line ->
            if (line.isBlank() || line.startsWith("total")) return@mapNotNull null
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 8) return@mapNotNull null
            val name = parts.last()
            if (name == "." || name == "..") return@mapNotNull null
            RootEntry(
                name = name,
                path = if (path.endsWith("/")) "$path$name" else "$path/$name",
                isDirectory = parts[0].startsWith("d"),
                sizeBytes = parts[4].toLongOrNull() ?: 0L,
            )
        }.toList()
        entries to ""
    }

    /** 列出某应用真实存在的 databases 目录文件（需要 root）。 */
    suspend fun listPackageDatabases(packageName: String): Pair<List<RootEntry>, String> = withContext(Dispatchers.IO) {
        val candidates = listOf("/data/data/$packageName/databases", "/data/user/0/$packageName/databases")
        for (path in candidates) {
            val (entries, error) = listRootDirectory(path)
            if (entries.isNotEmpty()) return@withContext entries.filterNot { it.isDirectory } to ""
            if (error.isNotBlank() && !error.contains("No such file")) return@withContext entries to error
        }
        emptyList<RootEntry>() to "该应用没有 databases 目录"
    }

    /** 校验 SQLite 文件头魔数。 */
    fun isSqliteFile(header: ByteArray): Boolean =
        header.size >= 16 && String(header, 0, 15, Charsets.US_ASCII) == "SQLite format 3"
}

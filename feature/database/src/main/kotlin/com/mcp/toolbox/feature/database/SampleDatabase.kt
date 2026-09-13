package com.mcp.toolbox.feature.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 示例库：真实创建一个 SQLite 文件（含主键、索引、视图与触发器），
 * 让「结构 / 数据 / SQL」三个视图在没有任何外部文件时也有真实内容可浏览。
 */
object SampleDatabase {

    const val FILE_NAME = "toolbox.db"

    /** 确保示例库已建表并写入种子数据（首启或库被清空后调用）。 */
    suspend fun ensure(context: Context) = withContext(Dispatchers.IO) {
        runCatching { open(context).close() }
    }

    fun open(context: Context): SQLiteDatabase {
        val file: File = context.getDatabasePath(FILE_NAME).also { it.parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS users (" +
                "id TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, status TEXT DEFAULT 'offline', " +
                "score REAL DEFAULT 0, last_seen TEXT)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS orders (" +
                "id TEXT PRIMARY KEY NOT NULL, user_id TEXT NOT NULL, amount REAL NOT NULL, created_at TEXT)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS products (" +
                "sku TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, price REAL NOT NULL, stock INTEGER DEFAULT 0)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_orders_user ON orders(user_id)")
        db.execSQL("CREATE VIEW IF NOT EXISTS online_users AS SELECT id, name, last_seen FROM users WHERE status = 'online'")
        db.execSQL(
            "CREATE TRIGGER IF NOT EXISTS trg_order_created AFTER INSERT ON orders " +
                "BEGIN UPDATE users SET last_seen = NEW.created_at WHERE id = NEW.user_id; END",
        )
        fun isEmpty(table: String): Boolean = db.rawQuery("SELECT COUNT(*) FROM \"$table\"", null)
            .use { if (it.moveToFirst()) it.getInt(0) == 0 else true }

        db.beginTransaction()
        try {
            if (isEmpty("users")) {
                listOf(
                    Triple("u-1001", "Alice Wang", "online"),
                    Triple("u-1002", "Bob Smith", "pending"),
                    Triple("u-1003", "Carol Lee", "online"),
                    Triple("u-1004", "Dan Zhang", "error"),
                    Triple("u-1005", "Eve Kim", "offline"),
                ).forEachIndexed { index, (id, name, status) ->
                    db.execSQL(
                        "INSERT INTO users VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any>(id, name, status, (index + 1) * 12.5, "2024-05-2${index + 1} 0${index + 1}:30"),
                    )
                }
            }
            if (isEmpty("orders")) {
                listOf(
                    Triple("o-2001", "u-1001", 199.0),
                    Triple("o-2002", "u-1001", 39.9),
                    Triple("o-2003", "u-1003", 1288.0),
                    Triple("o-2004", "u-1005", 12.0),
                ).forEachIndexed { index, (id, userId, amount) ->
                    db.execSQL(
                        "INSERT INTO orders VALUES (?, ?, ?, ?)",
                        arrayOf<Any>(id, userId, amount, "2024-05-2${index + 1} 1${index}:05"),
                    )
                }
            }
            if (isEmpty("products")) {
                listOf(
                    Triple("SKU-01", "USB-C 线", 19.9),
                    Triple("SKU-02", "65W 充电器", 129.0),
                    Triple("SKU-03", "机械键盘", 499.0),
                ).forEachIndexed { index, (sku, title, price) ->
                    db.execSQL("INSERT INTO products VALUES (?, ?, ?, ?)", arrayOf<Any>(sku, title, price, (index + 1) * 7))
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return db
    }
}

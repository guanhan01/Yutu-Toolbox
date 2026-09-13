package com.mcp.toolbox.feature.mcp

import android.content.Context
import com.mcp.toolbox.feature.capture.CaptureRecord
import com.mcp.toolbox.feature.capture.CaptureStore
import com.mcp.toolbox.feature.capture.CaptureToolBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 直接读取本应用「抓包」模块的实时记录（CaptureStore）：
 * 会话列表、单条详情、统计、清空与导出。
 *
 * 抓包本身要先在抓包页启动 VPN，这里只读已经捕获到的真实数据；
 * 没有记录时返回空列表并说明原因，不编造流量。
 */
object BuiltInToolSetCapture {

    fun all(context: Context): List<ToolDef> = listOf(
        captureList(context),
        captureDetail(context),
        captureStats(context),
        captureExport(context),
        captureClear(context),
    )

    private fun requireWrite() {
        if (!BuiltInMcpServer.config.value.allowWrite) {
            error("内置 Server 未开启写入：请在 MCP 页面打开「允许写入」后重试")
        }
    }

    private fun recordJson(record: CaptureRecord): JSONObject = JSONObject().apply {
        put("id", record.id)
        put("kind", record.kind.name)
        put("protocol", record.protocol)
        put("src", "${record.srcIp}:${record.srcPort}")
        put("dst", "${record.dstIp}:${record.dstPort}")
        put("host", record.host ?: JSONObject.NULL)
        put("scheme", record.scheme)
        put("method", record.method ?: JSONObject.NULL)
        put("path", record.path ?: JSONObject.NULL)
    }

    private fun matches(record: CaptureRecord, host: String, method: String, protocol: String): Boolean {
        if (host.isNotEmpty() && !(record.host?.contains(host, ignoreCase = true) ?: false)) return false
        if (method.isNotEmpty() && !(record.method?.equals(method, ignoreCase = true) ?: false)) return false
        if (protocol.isNotEmpty() && !record.protocol.equals(protocol, ignoreCase = true)) return false
        return true
    }

    private fun captureList(context: Context) = ToolDef(
        name = "capture.list",
        title = "抓包记录列表",
        description = "列出抓包模块当前的会话记录（按时间倒序），可按 host / 方法 / 协议过滤。抓包需先在抓包页启动。",
        schema = Schema.obj(
            listOf(
                "host" to Schema.string("按域名过滤，可空", default = ""),
                "method" to Schema.string("按 HTTP 方法过滤，可空", default = ""),
                "protocol" to Schema.string("按协议过滤（TCP/UDP/DNS/HTTP），可空", default = ""),
                "limit" to Schema.integer("最多返回条数", default = 50, min = 1, max = 500),
            ),
        ),
        handler = { ctx, args ->
            val all = CaptureToolBridge.list()
            val host = args.optString("host").trim()
            val method = args.optString("method").trim()
            val protocol = args.optString("protocol").trim()
            val limit = args.optInt("limit", 50).coerceIn(1, 500)
            val rows = JSONArray()
            all.asReversed().filter { matches(it, host, method, protocol) }.take(limit).forEach { rows.put(recordJson(it)) }
            val structured = JSONObject().apply {
                put("total", all.size)
                put("count", rows.length())
                put("filter", JSONObject().put("host", host).put("method", method).put("protocol", protocol))
                put("records", rows)
                put("hint", "若为空：先在「抓包」页启动 VPN 抓包；域名要等引擎回填 host 后才会出现")
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun captureDetail(context: Context) = ToolDef(
        name = "capture.detail",
        title = "单条抓包记录详情",
        description = "按 id 读取一条抓包记录的完整字段（含时间、端口、域名解析结果等）。id 可从 capture.list 获取。",
        schema = Schema.obj(
            listOf("id" to Schema.integer("记录 id", min = 0, max = 2000000000)),
            required = listOf("id"),
        ),
        handler = { ctx, args ->
            val id = args.getInt("id").toLong()
            val record = CaptureStore.find(id) ?: throw IllegalArgumentException("没有 id=$id 的记录（先用 capture.list 查看当前会话）")
            val structured = recordJson(record).apply {
                put("raw", record.toString())
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun captureStats(context: Context) = ToolDef(
        name = "capture.stats",
        title = "抓包统计",
        description = "返回当前抓包会话的统计信息（上下行字节、丢弃、DNS 观测数、记录数等原始字段）。",
        schema = Schema.obj(emptyList()),
        handler = { ctx, _ ->
            val statsText = CaptureToolBridge.statsText()
            val records = CaptureToolBridge.list()
            val byProtocol = JSONObject()
            records.groupBy { it.protocol }.forEach { (protocol, list) -> byProtocol.put(protocol, list.size) }
            val structured = JSONObject().apply {
                put("recordCount", records.size)
                put("byProtocol", byProtocol)
                put("stats", statsText)
                put("error", JSONObject.NULL)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun captureExport(context: Context) = ToolDef(
        name = "capture.export",
        title = "导出抓包记录",
        description = "把当前抓包记录导出为 JSON 或 CSV 文件（默认 /sdcard/Documents/mcp-toolbox）。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "format" to Schema.string("导出格式", default = "json", enum = listOf("json", "csv")),
                "out" to Schema.string("输出文件路径，可空", default = ""),
                "limit" to Schema.integer("最多导出条数", default = 2000, min = 1, max = 20000),
            ),
        ),
        readOnly = false,
        handler = { ctx, args ->
            requireWrite()
            val format = args.optString("format", "json").lowercase()
            val limit = args.optInt("limit", 2000).coerceIn(1, 20000)
            val records = CaptureToolBridge.list().asReversed().take(limit)
            val target = args.optString("out").trim().let { raw ->
                if (raw.isNotEmpty()) {
                    ToolSupport.resolve(ctx, raw)
                } else {
                    File(
                        "/sdcard/Documents/mcp-toolbox",
                        "capture-" + System.currentTimeMillis() + "." + format,
                    )
                }
            }
            target.parentFile?.mkdirs()
            val text = if (format == "csv") {
                buildString {
                    append("id,kind,protocol,src,dst,host,scheme,method,path\n")
                    records.forEach { record ->
                        append(
                            listOf(
                                record.id.toString(),
                                record.kind.name,
                                record.protocol,
                                "${record.srcIp}:${record.srcPort}",
                                "${record.dstIp}:${record.dstPort}",
                                record.host ?: "",
                                record.scheme,
                                record.method ?: "",
                                record.path ?: "",
                            ).joinToString(",") { cell -> "\"" + cell.replace("\"", "\"\"") + "\"" },
                        )
                        append("\n")
                    }
                }
            } else {
                val array = JSONArray()
                records.forEach { array.put(recordJson(it)) }
                array.toString(2)
            }
            target.writeText(text)
            val structured = JSONObject().apply {
                put("path", target.absolutePath)
                put("format", format)
                put("count", records.size)
                put("sizeBytes", target.length())
                put("sizeHuman", ToolSupport.human(target.length()))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun captureClear(context: Context) = ToolDef(
        name = "capture.clear",
        title = "清空抓包记录",
        description = "清空当前抓包会话的记录（可选同时重置统计）。危险操作，需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "resetStats" to Schema.bool("是否同时重置上下行统计", default = true),
                "id" to Schema.integer("只删这一条记录（0 表示全清）", default = 0, min = 0, max = 2000000000),
            ),
        ),
        readOnly = false,
        dangerous = true,
        handler = { ctx, args ->
            requireWrite()
            val id = args.optInt("id", 0)
            val before = CaptureToolBridge.list().size
            if (id > 0) {
                CaptureStore.removeRecord(id.toLong())
            } else {
                CaptureToolBridge.clearAll(args.optBoolean("resetStats", true))
                
            }
            val after = CaptureToolBridge.list().size
            val structured = JSONObject().apply {
                put("before", before)
                put("after", after)
                put("removed", before - after)
                put("mode", if (id > 0) "single" else "all")
            }
            ToolResult(structured, structured.toString(2))
        },
    )
}

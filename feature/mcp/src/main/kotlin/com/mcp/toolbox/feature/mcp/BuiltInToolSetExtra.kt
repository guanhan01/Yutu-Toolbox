package com.mcp.toolbox.feature.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.provider.Settings
import com.mcp.toolbox.core.common.PrivilegeManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections

/**
 * 内置 Server 的第二批工具：写操作与命令执行。
 *
 * 系统信息类工具（getprop / ps / logcat / dumpsys package 等）已移除，
 * 由本文件的 [shellExec] 以一条命令等价覆盖；
 * [netInfo] 走 NetworkInterface、不需要任何权限，故保留。
 *
 * 与 [BuiltInToolSet] 的分工是这里有副作用。写工具一律 `readOnly = false`；
 * 缺权限时返回明确的错误结果，不做假成功。
 */
object BuiltInToolSetExtra {

    fun all(context: Context): List<ToolDef> = listOf(
        fileDelete(context),
        fileMkdir(context),
        fileSearch(context),
        settingsGet(context),
        settingsPut(context),
        netInfo(context),
        clipboardGet(context),
        clipboardSet(context),
        shellExec(context),
    )

    /** 「允许写入」总开关，与 file.write 用同一个。 */
    private fun requireWrite() = WriteGuard.requireWrite()

    private fun fileDelete(context: Context) = ToolDef(
        name = "file.delete",
        title = "删除文件或目录",
        description = "删除文件，目录会递归删除。受允许根目录限制，需要开启「允许写入」。",
        schema = Schema.obj(
            listOf("path" to Schema.string("要删除的绝对路径")),
            required = listOf("path"),
        ),
        readOnly = false,
        dangerous = true,
        handler = { ctx, args ->
            requireWrite()
            val target = ToolSupport.resolve(ctx, args.getString("path"))
            if (!target.exists()) throw IllegalArgumentException("路径不存在：${target.absolutePath}")
            val size = if (target.isDirectory) target.walkTopDown().filter { it.isFile }.sumOf { it.length() } else target.length()
            val removed = target.deleteRecursively()
            if (!removed) throw IllegalStateException("删除失败，可能是权限不足：${target.absolutePath}")
            val structured = JSONObject().apply {
                put("path", target.absolutePath)
                put("wasDirectory", target.isDirectory)
                put("freedBytes", size)
                put("deleted", true)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileMkdir(context: Context) = ToolDef(
        name = "file.mkdir",
        title = "创建目录",
        description = "递归创建目录，已存在时返回 existing=true。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf("path" to Schema.string("目录绝对路径")),
            required = listOf("path"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            requireWrite()
            val target = ToolSupport.resolve(ctx, args.getString("path"))
            val existed = target.isDirectory
            val created = if (existed) true else target.mkdirs()
            if (!created) throw IllegalStateException("创建目录失败：${target.absolutePath}")
            val structured = JSONObject().apply {
                put("path", target.absolutePath)
                put("existing", existed)
                put("created", !existed)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileSearch(context: Context) = ToolDef(
        name = "file.search",
        title = "递归搜索文件",
        description = "在允许根目录内按名称片段递归搜索，可限制深度与结果数量。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("搜索起点绝对路径", default = "/sdcard"),
                "query" to Schema.string("文件名包含的关键词（忽略大小写）"),
                "maxDepth" to Schema.integer("最大递归深度", default = 6),
                "limit" to Schema.integer("最多返回条数", default = 50),
            ),
            required = listOf("query"),
        ),
        handler = { ctx, args ->
            val root = ToolSupport.resolve(ctx, args.optString("path", "/sdcard"))
            val query = args.getString("query").lowercase()
            val maxDepth = args.optInt("maxDepth", 6).coerceIn(1, 20)
            val limit = args.optInt("limit", 50).coerceIn(1, 500)
            val hits = JSONArray()
            var scanned = 0
            root.walkTopDown()
                .maxDepth(maxDepth)
                .onEnter { scanned++ < 200_000 }
                .forEach { file ->
                    if (hits.length() < limit && file.name.lowercase().contains(query)) {
                        hits.put(
                            JSONObject().apply {
                                put("path", file.absolutePath)
                                put("name", file.name)
                                put("directory", file.isDirectory)
                                put("sizeBytes", if (file.isFile) file.length() else 0)
                                put("lastModified", file.lastModified())
                            },
                        )
                    }
                }
            val structured = JSONObject().apply {
                put("root", root.absolutePath)
                put("query", query)
                put("truncated", hits.length() >= limit)
                put("results", hits)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    /** 单引号包裹，避免参数里的空格或特殊字符被 shell 拆开。 */
    private fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\\\''") + "'"

    private fun settingsGet(context: Context) = ToolDef(
        name = "settings.get",
        title = "读取系统设置",
        description = "读取 system/secure/global 三个命名空间下的设置值。",
        schema = Schema.obj(
            listOf(
                "namespace" to Schema.string("命名空间", default = "system", enum = listOf("system", "secure", "global")),
                "key" to Schema.string("设置键名"),
            ),
            required = listOf("key"),
        ),
        handler = { ctx, args ->
            val namespace = args.optString("namespace", "system")
            val key = args.getString("key")
            val value = when (namespace) {
                "secure" -> Settings.Secure.getString(ctx.contentResolver, key)
                "global" -> Settings.Global.getString(ctx.contentResolver, key)
                else -> Settings.System.getString(ctx.contentResolver, key)
            }
            val structured = JSONObject().apply {
                put("namespace", namespace)
                put("key", key)
                put("value", value ?: JSONObject.NULL)
                put("found", value != null)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun settingsPut(context: Context) = ToolDef(
        name = "settings.put",
        title = "写入系统设置",
        description = "写入系统设置。secure/global 下的多数键需要高权限，失败会明确报错。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "namespace" to Schema.string("命名空间", default = "system", enum = listOf("system", "secure", "global")),
                "key" to Schema.string("设置键名"),
                "value" to Schema.string("新值"),
            ),
            required = listOf("key", "value"),
        ),
        readOnly = false,
        dangerous = true,
        handler = { ctx, args ->
            requireWrite()
            val namespace = args.optString("namespace", "system")
            val key = args.getString("key")
            val value = args.getString("value")
            val previous = runCatching {
                when (namespace) {
                    "secure" -> Settings.Secure.getString(ctx.contentResolver, key)
                    "global" -> Settings.Global.getString(ctx.contentResolver, key)
                    else -> Settings.System.getString(ctx.contentResolver, key)
                }
            }.getOrNull()

            val appOk = runCatching {
                when (namespace) {
                    "secure" -> Settings.Secure.putString(ctx.contentResolver, key, value)
                    "global" -> Settings.Global.putString(ctx.contentResolver, key, value)
                    else -> Settings.System.putString(ctx.contentResolver, key, value)
                }
            }.getOrDefault(false)

            if (!appOk) {
                // 应用侧被拒时退回高权限 shell，可覆盖 secure/global 里多数系统键。
                val result = PrivilegeManager.exec(
                    "settings put $namespace ${shellQuote(key)} ${shellQuote(value)}", 15_000L,
                )
                if (!result.ok) {
                    throw IllegalStateException(
                        "写入被拒绝（$namespace/$key）：应用侧无权限，高权限兜底也失败（${result.output.ifBlank { "无输出" }}）",
                    )
                }
            }
            val structured = JSONObject().apply {
                put("namespace", namespace)
                put("key", key)
                put("value", value)
                put("previous", previous ?: JSONObject.NULL)
                put("applied", true)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun netInfo(context: Context) = ToolDef(
        name = "net.info",
        title = "网络接口信息",
        description = "列出本机网络接口、地址、MTU 与启用状态。",
        schema = Schema.obj(emptyList()),
        handler = { _, _ ->
            val interfaces = JSONArray()
            runCatching {
                Collections.list(NetworkInterface.getNetworkInterfaces()).forEach { nif ->
                    val addresses = JSONArray()
                    Collections.list(nif.inetAddresses).forEach { addr -> addresses.put(addr.hostAddress ?: "") }
                    interfaces.put(
                        JSONObject().apply {
                            put("name", nif.name)
                            put("up", nif.isUp)
                            put("loopback", nif.isLoopback)
                            put("mtu", nif.mtu)
                            put("addresses", addresses)
                        },
                    )
                }
            }
            val structured = JSONObject().apply {
                put("interfaceCount", interfaces.length())
                put("interfaces", interfaces)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun clipboardGet(context: Context) = ToolDef(
        name = "clipboard.get",
        title = "读取剪贴板",
        description = "读取系统剪贴板文本。Android 10+ 仅在应用处于前台时允许读取。",
        schema = Schema.obj(emptyList()),
        handler = { ctx, _ ->
            val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val hasClip = manager.hasPrimaryClip()
            val text = if (hasClip) manager.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() else null
            val structured = JSONObject().apply {
                put("hasClip", hasClip)
                put("length", text?.length ?: 0)
                put("text", text ?: "")
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun clipboardSet(context: Context) = ToolDef(
        name = "clipboard.set",
        title = "写入剪贴板",
        description = "把文本写入系统剪贴板。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf("text" to Schema.string("要写入的文本")),
            required = listOf("text"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            requireWrite()
            val text = args.getString("text")
            val manager = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("mcp-toolbox", text))
            val structured = JSONObject().apply {
                put("length", text.length)
                put("written", true)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun shellExec(context: Context) = ToolDef(
        name = "shell.exec",
        title = "执行 shell 命令",
        description = "执行任意 shell 命令，返回 stdout/stderr 与退出码。" +
            "有 root 或 Shizuku 时用高权限执行（能看别的进程、改系统设置）；" +
            "都没有时退回**应用自身身份**的 Android shell（与「Android 本机」终端窗口同一个环境，" +
            "能跑 ls/ps/dumpsys/pm 那套，但看不到需要提权的内容）。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "command" to Schema.string("要执行的命令"),
                "timeoutMs" to Schema.integer("超时毫秒（1000-120000）", default = 30000),
            ),
            required = listOf("command"),
        ),
        readOnly = false,
        dangerous = true,
        handler = { ctx, args ->
            requireWrite()
            val command = args.getString("command")
            val timeout = args.optInt("timeoutMs", 30_000).coerceIn(1_000, 120_000).toLong()
            // allowLocal：高权限后端缺席时不当失败，改以应用身份执行，保证无 root 设备也有一条真终端。
            val result = PrivilegeManager.exec(command, timeout, allowLocal = true)
            val structured = JSONObject().apply {
                put("command", command)
                put("backend", result.backend.name)
                put("exitCode", result.code)
                put("timedOut", result.timedOut)
                put("ok", result.ok)
                put("stdout", result.stdout.take(200_000))
                put("stderr", result.stderr.take(200_000))
                result.failure?.let { put("failure", it) }
            }
            ToolResult(structured, structured.toString(2), isError = !result.ok)
        },
    )
}

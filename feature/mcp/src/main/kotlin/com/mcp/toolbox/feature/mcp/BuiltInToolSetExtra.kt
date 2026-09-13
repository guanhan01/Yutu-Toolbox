package com.mcp.toolbox.feature.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.mcp.toolbox.core.common.PrivilegeManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

/**
 * 内置 Server 的第二批工具：写操作、系统信息与高权限执行。
 *
 * 与 [BuiltInToolSet] 的分工是这里有副作用。写工具一律 `readOnly = false`，
 * 依赖 root/Shizuku 的再额外标 `requiresPrivilege = true`；
 * 缺权限时返回明确的错误结果，不做假成功。
 */
object BuiltInToolSetExtra {

    fun all(context: Context): List<ToolDef> = listOf(
        fileDelete(context),
        fileMkdir(context),
        fileSearch(context),
        systemProp(context),
        processList(context),
        logcatRead(context),
        settingsGet(context),
        settingsPut(context),
        netInfo(context),
        clipboardGet(context),
        clipboardSet(context),
        packageInfo(context),
        packageForceStop(context),
        shellExec(context),
    )

    /** 「允许写入」总开关，与 file.write 用同一个。 */
    private fun requireWrite() {
        if (!BuiltInMcpServer.config.value.allowWrite) {
            error("内置 Server 未开启写入：请在 MCP 页面打开「允许写入」后重试")
        }
    }

    /** 高权限工具的前置检查，失败信息里带上具体缺什么。 */
    private fun requirePrivilege(context: Context) {
        val status = PrivilegeManager.status(context)
        if (!status.usable) {
            error("需要高权限后端（${status.detail}）：请在设置页完成 root 授权，或安装并启动 Shizuku 后重试")
        }
    }

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

    private fun systemProp(context: Context) = ToolDef(
        name = "system.prop",
        title = "系统属性",
        description = "读取 getprop 属性；传 key 返回单条，不传返回全部（键值对）。",
        schema = Schema.obj(listOf("key" to Schema.string("属性名，例如 ro.build.version.sdk"))),
        handler = { _, args ->
            val key = args.optString("key")
            val result = PrivilegeManager.exec(
                if (key.isBlank()) "getprop" else "getprop ${shellQuote(key)}",
                timeoutMs = 15_000L,
            )
            if (key.isBlank()) {
                val map = JSONObject()
                result.stdout.lineSequence().forEach { line ->
                    val m = Regex("^\\\\[(.+?)\\\\]: \\\\[(.*)\\\\]$").find(line.trim()) ?: return@forEach
                    map.put(m.groupValues[1], m.groupValues[2])
                }
                if (map.length() == 0) throw IllegalStateException(result.output.ifBlank { "getprop 无输出" })
                ToolResult(JSONObject().put("count", map.length()).put("props", map), map.toString(2))
            } else {
                val value = result.stdout.trim()
                val structured = JSONObject().apply {
                    put("key", key)
                    put("value", value)
                    put("found", value.isNotEmpty())
                }
                ToolResult(structured, structured.toString(2))
            }
        },
    )

    /** 单引号包裹，避免参数里的空格或特殊字符被 shell 拆开。 */
    private fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\\\''") + "'"

    /**
     * 先走高权限后端，失败再退回普通子进程。
     * 部分信息（进程名、自己应用的日志）不要 root 也能读到，没必要因为没授权就整个不可用。
     */
    private fun execPreferPrivilege(command: String, timeoutMs: Long): Pair<String, String> {
        val privileged = PrivilegeManager.exec(command, timeoutMs)
        if (privileged.ok && privileged.stdout.isNotBlank()) return privileged.stdout to privileged.backend.name
        val plain = runCatching {
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            text
        }.getOrDefault("")
        return plain to "SHELL"
    }

    private fun processList(context: Context) = ToolDef(
        name = "process.list",
        title = "进程列表",
        description = "列出运行中的进程。有高权限时能看到完整列表，否则退回普通子进程（信息较少）。",
        schema = Schema.obj(listOf("filter" to Schema.string("按进程名过滤的关键词"))),
        handler = { _, args ->
            val filter = args.optString("filter")
            val (text, source) = execPreferPrivilege(
                if (filter.isBlank()) "ps -A" else "ps -A | grep -i ${shellQuote(filter)}",
                20_000L,
            )
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            val structured = JSONObject().apply {
                put("source", source)
                put("lineCount", lines.size)
                put("raw", lines.take(400).joinToString("\n"))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun logcatRead(context: Context) = ToolDef(
        name = "logcat.read",
        title = "读取日志",
        description = "读取最近日志（logcat -d）；有高权限时可读全部，否则仅本应用相关。",
        schema = Schema.obj(
            listOf(
                "lines" to Schema.integer("最多返回行数", default = 200),
                "filter" to Schema.string("过滤关键词（在已读日志中做文本匹配）"),
            ),
        ),
        handler = { _, args ->
            val lines = args.optInt("lines", 200).coerceIn(10, 2000)
            val (text, source) = execPreferPrivilege("logcat -d -t $lines", 25_000L)
            val filter = args.optString("filter")
            val kept = text.lineSequence().filter { it.isNotBlank() }
                .let { seq -> if (filter.isBlank()) seq else seq.filter { it.contains(filter, ignoreCase = true) } }
                .toList()
            val structured = JSONObject().apply {
                put("source", source)
                put("lineCount", kept.size)
                put("filter", filter)
                put("raw", kept.takeLast(2000).joinToString("\n"))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

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

    private fun packageInfo(context: Context) = ToolDef(
        name = "package.info",
        title = "应用详情",
        description = "返回包名的版本、安装时间、uid、targetSdk、标志位、APK 路径与申请权限。",
        schema = Schema.obj(
            listOf("package" to Schema.string("目标包名")),
            required = listOf("package"),
        ),
        handler = { ctx, args ->
            val pkg = args.getString("package")
            val info = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_PERMISSIONS)
            val app = info.applicationInfo ?: error("无法读取应用信息：$pkg")
            val structured = JSONObject().apply {
                put("package", pkg)
                put("versionName", info.versionName ?: "")
                put("versionCode", if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
                put("firstInstallTime", info.firstInstallTime)
                put("lastUpdateTime", info.lastUpdateTime)
                put("uid", app.uid)
                put("targetSdk", app.targetSdkVersion)
                put("minSdk", if (Build.VERSION.SDK_INT >= 24) app.minSdkVersion else 0)
                put("debuggable", (app.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                put("system", (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                put("enabled", app.enabled)
                put("apkPath", app.sourceDir)
                put("dataDir", app.dataDir)
                put("permissions", JSONArray(info.requestedPermissions?.toList() ?: emptyList<String>()))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun packageForceStop(context: Context) = ToolDef(
        name = "package.forceStop",
        title = "强制停止应用",
        description = "通过 am force-stop 停止目标应用。需要高权限后端与「允许写入」。",
        schema = Schema.obj(
            listOf("package" to Schema.string("目标包名")),
            required = listOf("package"),
        ),
        readOnly = false,
        dangerous = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            requireWrite()
            requirePrivilege(ctx)
            val pkg = args.getString("package")
            val result = PrivilegeManager.exec("am force-stop ${shellQuote(pkg)}", 20_000L)
            if (!result.ok) throw IllegalStateException("force-stop 失败：${result.output.ifBlank { "无输出" }}")
            val structured = JSONObject().apply {
                put("package", pkg)
                put("backend", result.backend.name)
                put("stopped", true)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun shellExec(context: Context) = ToolDef(
        name = "shell.exec",
        title = "执行 shell 命令",
        description = "以高权限后端执行任意 shell 命令，返回 stdout/stderr 与退出码。" +
            "需要 root 或 Shizuku，并且需要开启「允许写入」与相应的高权限开关。",
        schema = Schema.obj(
            listOf(
                "command" to Schema.string("要执行的命令"),
                "timeoutMs" to Schema.integer("超时毫秒（1000-120000）", default = 30000),
            ),
            required = listOf("command"),
        ),
        readOnly = false,
        dangerous = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            requireWrite()
            requirePrivilege(ctx)
            val command = args.getString("command")
            val timeout = args.optInt("timeoutMs", 30_000).coerceIn(1_000, 120_000).toLong()
            val result = PrivilegeManager.exec(command, timeout)
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

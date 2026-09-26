package com.mcp.toolbox.feature.mcp

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Environment
import android.os.StatFs
import com.mcp.toolbox.core.common.PrivilegeManager
import com.mcp.toolbox.core.common.ToolboxAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 真正能「动手机」的一组工具：界面层级、点击、滑动、输入、按键、截图，
 * 以及电池 / 内存 / 存储 / 进程 / 通知 / 传感器 / 电源 / Wi-Fi 等系统状态。
 *
 * 界面类操作有两条通道，**优先走无障碍（免 root）**：
 *  1. 无障碍服务：`dispatchGesture` / `ACTION_SET_TEXT` / `takeScreenshot`，
 *     只需用户在设置里开启一次，不需要任何提权；
 *  2. 高权限后端：`input` / `screencap` / `uiautomator`，需要 root 或 Shizuku。
 * 第一条不可用时自动回落到第二条；两条都不行才返回明确错误，绝不假装成功。
 */
object BuiltInToolSetSys {

    fun all(context: Context): List<ToolDef> = listOf(
        uiCurrent(context),
        uiTree(context),
        uiTap(context),
        uiSwipe(context),
        uiText(context),
        uiKey(context),
        uiScreenshot(context),
        systemBattery(context),
        systemMemory(context),
        systemStorage(context),
        systemTop(context),
        systemNotifications(context),
        systemSensors(context),
        systemPower(context),
        systemWifi(context),
        systemKill(context),
    )

    private fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    /** 先走高权限后端，失败再退回普通子进程（部分命令不 root 也能跑）。 */
    private fun exec(command: String, timeoutMs: Long = 20000L): Triple<String, Boolean, String> {
        val privileged = PrivilegeManager.exec(command, timeoutMs)
        if (privileged.ok && privileged.stdout.isNotBlank()) {
            return Triple(privileged.stdout, true, privileged.backend.name)
        }
        val plain = runCatching {
            val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val text = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            text
        }.getOrDefault("")
        if (plain.isNotBlank()) return Triple(plain, true, "SHELL")
        return Triple(privileged.output.ifBlank { plain }, false, privileged.backend.name)
    }

    /** 是否有任意一条可用通道（无障碍优先，其次 root / Shizuku）。 */
    private fun hasUiChannel(context: Context): Boolean =
        ToolboxAccessibilityService.connected || PrivilegeManager.status(context).usable

    /**
     * 系统级操作的前置检查（结束进程、改系统设置等）。
     *
     * 与 [requireUiChannel] 的区别：这些操作**没有**无障碍替代路径，
     * 只能走 root / Shizuku，所以仍然要求提权后端。
     */
    private fun requirePrivilege(context: Context) {
        val status = PrivilegeManager.status(context)
        if (!status.usable) {
            error("需要高权限后端（${status.detail}）：请在设置页完成 root 授权，或安装并启动 Shizuku 后重试")
        }
    }

    /**
     * 界面类工具的前置检查。
     *
     * 报错文案要同时给出两条路，否则用户会以为必须先 root。
     */
    private fun requireUiChannel(context: Context) {
        if (hasUiChannel(context)) return
        val status = PrivilegeManager.status(context)
        error(
            "界面操作需要无障碍服务（推荐，免 root）或高权限后端。" +
                "请在「权限检测与申请」里开启无障碍；" +
                "或完成 root 授权 / 启动 Shizuku（当前：${status.detail}）",
        )
    }

    private fun grep(text: String, keyword: String, limit: Int = 30): List<String> =
        text.lineSequence().filter { it.contains(keyword, ignoreCase = true) }.take(limit).toList()

    private fun uiCurrent(context: Context) = ToolDef(
        name = "ui.current",
        title = "当前前台界面",
        description = "返回当前前台应用包名、Activity 与窗口焦点，用来判断手机此刻停在哪个界面。" +
            "优先走无障碍（免 root），不可用时回落到 dumpsys。",
        schema = Schema.obj(emptyList()),
        handler = { ctx, _ ->
            if (ToolboxAccessibilityService.connected) {
                val events = ToolboxAccessibilityService.currentWindow()
                if (events != null) {
                    val structured = JSONObject().apply {
                        put("focus", events.focus)
                        put("package", events.packageName)
                        put("activity", events.activity)
                        put("backend", "ACCESSIBILITY")
                    }
                    return@ToolDef ToolResult(structured, structured.toString(2))
                }
            }
            val window = exec("dumpsys window 2>/dev/null | grep -m3 mCurrentFocus")
            val activity = exec("dumpsys activity activities 2>/dev/null | grep -m3 -E 'topResumedActivity|mResumedActivity'")
            val focusLines = window.first.lineSequence().filter { it.isNotBlank() }.toList()
            val focus = focusLines.firstOrNull { it.contains("mCurrentFocus") }?.substringAfter("mCurrentFocus=")?.trim()
            val component = focus?.let { line ->
                Regex("\\s([a-zA-Z0-9_.]+/[a-zA-Z0-9_.\\$]+)\\}").find(line)?.groupValues?.get(1)
            }
            val structured = JSONObject().apply {
                put("focus", focus ?: JSONObject.NULL)
                put("component", component ?: JSONObject.NULL)
                put("package", component?.substringBefore('/') ?: JSONObject.NULL)
                put("backend", window.third)
                put("raw", focusLines.joinToString("\n"))
                put("activityRaw", activity.first.lineSequence().filter { it.isNotBlank() }.take(6).joinToString("\n"))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun uiTree(context: Context) = ToolDef(
        name = "ui.tree",
        title = "界面控件树",
        description = "读取当前界面的控件树，返回可读文本与节点数，用于定位可点击控件的坐标与文本。" +
            "优先走无障碍（免 root），不可用时回落到 uiautomator dump。",
        schema = Schema.obj(
            listOf(
                "maxChars" to Schema.integer("最多返回字符数", default = 40000, min = 500, max = 400000),
                "query" to Schema.string("只保留包含该关键词的行，可空", default = ""),
            ),
        ),
        handler = { ctx, args ->
            val limit = args.optInt("maxChars", 40000).coerceIn(500, 400000)
            val query = args.optString("query").trim()
            fun pack(text: String, backend: String, nodes: Int): ToolResult {
                val body = if (query.isEmpty()) text
                else text.lineSequence().filter { it.contains(query, ignoreCase = true) }.joinToString("\n")
                val structured = JSONObject().apply {
                    put("nodes", nodes)
                    put("length", body.length)
                    put("truncated", body.length > limit)
                    put("backend", backend)
                    put("xml", body.take(limit))
                    put("hint", "坐标单位是设备像素；配合 ui.tap 使用")
                }
                return ToolResult(structured, structured.toString(2))
            }

            if (ToolboxAccessibilityService.connected) {
                val tree = ToolboxAccessibilityService.dumpTree()
                if (tree.isSuccess) {
                    val text = tree.getOrThrow()
                    return@ToolDef pack(text, "ACCESSIBILITY", text.lineSequence().count { it.isNotBlank() })
                }
            }

            val out = "/data/local/tmp/eta-ui-dump.xml"
            val dump = exec("uiautomator dump $out 2>&1")
            if (!dump.second) throw IllegalStateException("uiautomator dump 失败：${dump.first.take(300)}")
            val text = runCatching { File(out).readText() }.getOrElse {
                throw IllegalStateException("dump 文件读取失败：$out（${it.message}）")
            }
            File(out).delete()
            pack(text, dump.third, Regex("<node").findAll(text).count())
        },
    )

    private fun uiTap(context: Context) = ToolDef(
        name = "ui.tap",
        title = "点击屏幕",
        description = "在指定坐标点击屏幕。优先走无障碍（免 root），不可用时回落到 input tap。",
        schema = Schema.obj(
            listOf(
                "x" to Schema.integer("X 坐标（设备像素）", min = 0, max = 20000),
                "y" to Schema.integer("Y 坐标（设备像素）", min = 0, max = 20000),
            ),
            required = listOf("x", "y"),
        ),
        readOnly = false,
        requiresPrivilege = false,
        handler = { ctx, args ->
            requireUiChannel(ctx)
            val x = args.getInt("x")
            val y = args.getInt("y")
            val viaAccessibility = ToolboxAccessibilityService.tap(x, y)
            if (viaAccessibility.isSuccess) {
                val structured = JSONObject().apply {
                    put("x", x)
                    put("y", y)
                    put("backend", "ACCESSIBILITY")
                    put("executed", true)
                }
                return@ToolDef ToolResult(structured, structured.toString(2))
            }
            val result = exec("input tap $x $y", 15000L)
            val structured = JSONObject().apply {
                put("x", x)
                put("y", y)
                put("backend", result.third)
                put("executed", result.second)
                put("output", result.first.take(300))
                put("accessibilityError", viaAccessibility.exceptionOrNull()?.message)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun uiSwipe(context: Context) = ToolDef(
        name = "ui.swipe",
        title = "滑动屏幕",
        description = "从起点滑到终点，可指定时长。优先走无障碍（免 root），不可用时回落到 input swipe。",
        schema = Schema.obj(
            listOf(
                "x1" to Schema.integer("起点 X", min = 0, max = 20000),
                "y1" to Schema.integer("起点 Y", min = 0, max = 20000),
                "x2" to Schema.integer("终点 X", min = 0, max = 20000),
                "y2" to Schema.integer("终点 Y", min = 0, max = 20000),
                "durationMs" to Schema.integer("滑动时长毫秒", default = 300, min = 50, max = 5000),
            ),
            required = listOf("x1", "y1", "x2", "y2"),
        ),
        readOnly = false,
        requiresPrivilege = false,
        handler = { ctx, args ->
            requireUiChannel(ctx)
            val duration = args.optInt("durationMs", 300).coerceIn(50, 5000)
            val x1 = args.getInt("x1")
            val y1 = args.getInt("y1")
            val x2 = args.getInt("x2")
            val y2 = args.getInt("y2")
            val viaAccessibility =
                ToolboxAccessibilityService.swipe(x1, y1, x2, y2, duration.toLong())
            if (viaAccessibility.isSuccess) {
                val structured = JSONObject().apply {
                    put("from", "$x1,$y1")
                    put("to", "$x2,$y2")
                    put("durationMs", duration)
                    put("backend", "ACCESSIBILITY")
                    put("executed", true)
                }
                return@ToolDef ToolResult(structured, structured.toString(2))
            }
            val result = exec("input swipe $x1 $y1 $x2 $y2 $duration", 20000L)
            val structured = JSONObject().apply {
                put("from", "$x1,$y1")
                put("to", "$x2,$y2")
                put("durationMs", duration)
                put("backend", result.third)
                put("output", result.first.take(300))
                put("accessibilityError", viaAccessibility.exceptionOrNull()?.message)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun uiText(context: Context) = ToolDef(
        name = "ui.text",
        title = "输入文本",
        description = "向当前焦点输入框写入文本。走无障碍时支持中文与 emoji；" +
            "回落到 input text 时只支持 ASCII。",
        schema = Schema.obj(
            listOf("text" to Schema.string("要输入的文本")),
            required = listOf("text"),
        ),
        readOnly = false,
        requiresPrivilege = false,
        handler = { ctx, args ->
            requireUiChannel(ctx)
            val text = args.getString("text")
            if (ToolboxAccessibilityService.connected) {
                val done = ToolboxAccessibilityService.setText(text)
                if (done.getOrDefault(false)) {
                    val structured = JSONObject().apply {
                        put("text", text)
                        put("length", text.length)
                        put("backend", "ACCESSIBILITY")
                        put("executed", true)
                    }
                    return@ToolDef ToolResult(structured, structured.toString(2))
                }
                error("没有找到处于焦点的输入框：请先用 ui.tap 点一下目标输入框再输入")
            }
            val nonAscii = text.any { it.code > 127 }
            if (nonAscii) {
                throw IllegalArgumentException(
                    "系统 input text 不支持非 ASCII 字符（中文 / emoji）：请开启无障碍服务，" +
                        "或改用 clipboard.set 写剪贴板后用 ui.key 发送 PASTE(279) 粘贴",
                )
            }
            val escaped = text.replace(" ", "%s")
            val result = exec("input text ${shellQuote(escaped)}", 20000L)
            val structured = JSONObject().apply {
                put("text", text)
                put("length", text.length)
                put("backend", result.third)
                put("output", result.first.take(300))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun uiKey(context: Context) = ToolDef(
        name = "ui.key",
        title = "发送按键",
        description = "发送系统按键：HOME / BACK / ENTER / RECENTS / POWER / VOLUME_UP / VOLUME_DOWN / " +
            "PASTE(279) 等。HOME、BACK、RECENTS、NOTIFICATIONS 优先走无障碍全局动作（免 root）。",
        schema = Schema.obj(
            listOf(
                "key" to Schema.string(
                    "按键名或 keycode 数字",
                    default = "HOME",
                    enum = listOf(
                        "HOME", "BACK", "ENTER", "RECENTS", "NOTIFICATIONS", "POWER", "MENU", "DELETE",
                        "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE", "CAMERA", "SEARCH",
                        "MEDIA_PLAY_PAUSE", "MEDIA_NEXT", "MEDIA_PREVIOUS", "ESCAPE", "TAB", "PASTE",
                    ),
                ),
            ),
            required = listOf("key"),
        ),
        readOnly = false,
        requiresPrivilege = false,
        handler = { ctx, args ->
            requireUiChannel(ctx)
            val raw = args.getString("key").trim()
            val globalAction = when (raw.uppercase()) {
                "HOME" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                "BACK" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK
                "RECENTS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS
                "NOTIFICATIONS" -> android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                else -> null
            }
            if (globalAction != null && ToolboxAccessibilityService.connected) {
                if (ToolboxAccessibilityService.performGlobal(globalAction).isSuccess) {
                    val structured = JSONObject().apply {
                        put("key", raw)
                        put("backend", "ACCESSIBILITY")
                        put("executed", true)
                    }
                    return@ToolDef ToolResult(structured, structured.toString(2))
                }
            }
            val code = when (raw.uppercase()) {
                "PASTE" -> "279"
                "HOME" -> "KEYCODE_HOME"
                "BACK" -> "KEYCODE_BACK"
                "ENTER" -> "KEYCODE_ENTER"
                "RECENTS" -> "KEYCODE_APP_SWITCH"
                "NOTIFICATIONS" -> "KEYCODE_NOTIFICATION"
                "POWER" -> "KEYCODE_POWER"
                "MENU" -> "KEYCODE_MENU"
                "DELETE" -> "KEYCODE_DEL"
                "ESCAPE" -> "KEYCODE_ESCAPE"
                "TAB" -> "KEYCODE_TAB"
                "SEARCH" -> "KEYCODE_SEARCH"
                "CAMERA" -> "KEYCODE_CAMERA"
                "VOLUME_UP" -> "KEYCODE_VOLUME_UP"
                "VOLUME_DOWN" -> "KEYCODE_VOLUME_DOWN"
                "VOLUME_MUTE" -> "KEYCODE_VOLUME_MUTE"
                "MEDIA_PLAY_PAUSE" -> "KEYCODE_MEDIA_PLAY_PAUSE"
                "MEDIA_NEXT" -> "KEYCODE_MEDIA_NEXT"
                "MEDIA_PREVIOUS" -> "KEYCODE_MEDIA_PREVIOUS"
                else -> raw
            }
            val result = exec("input keyevent $code", 15000L)
            val structured = JSONObject().apply {
                put("key", raw)
                put("keycode", code)
                put("backend", result.third)
                put("output", result.first.take(300))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun uiScreenshot(context: Context) = ToolDef(
        name = "ui.screenshot",
        title = "截屏到文件",
        description = "截取当前屏幕并保存为 PNG，返回文件路径、大小与尺寸。" +
            "优先走无障碍（免 root，Android 11+），不可用时回落到 screencap。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("输出路径，空则写到 /sdcard/Pictures/mcp-toolbox/screen-<时间>.png", default = ""),
            ),
        ),
        readOnly = false,
        requiresPrivilege = false,
        handler = { ctx, args ->
            if (!BuiltInMcpServer.config.value.allowWrite) error("内置 Server 未开启写入：请先打开「允许写入」")
            requireUiChannel(ctx)
            val target = args.optString("path").trim().let { raw ->
                when {
                    raw.isEmpty() -> File(
                        "/sdcard/Pictures/mcp-toolbox",
                        "screen-" + System.currentTimeMillis() + ".png",
                    )
                    raw.startsWith("~/") -> File(raw.replaceFirst("~", "/storage/emulated/0"))
                    else -> ToolSupport.resolve(ctx, raw)
                }
            }
            target.parentFile?.mkdirs()

            // 无障碍截屏：直接拿到位图，不依赖 screencap，也不需要提权
            val viaAccessibility = ToolboxAccessibilityService.screenshot()
            if (viaAccessibility.isSuccess) {
                val bytes = viaAccessibility.getOrThrow()
                target.writeBytes(bytes)
                val structured = JSONObject().apply {
                    put("path", target.absolutePath)
                    put("sizeBytes", target.length())
                    put("sizeHuman", ToolSupport.human(target.length()))
                    put("backend", "ACCESSIBILITY")
                    put("hint", "用 file.read（encoding=base64）可把图片内容读出来")
                }
                return@ToolDef ToolResult(structured, structured.toString(2))
            }

            val result = exec("screencap -p ${shellQuote(target.absolutePath)}", 25000L)
            if (!target.isFile) {
                throw IllegalStateException(
                    "截屏失败：${viaAccessibility.exceptionOrNull()?.message ?: result.first.take(300)}",
                )
            }
            val structured = JSONObject().apply {
                put("path", target.absolutePath)
                put("sizeBytes", target.length())
                put("sizeHuman", ToolSupport.human(target.length()))
                put("backend", result.third)
                put("hint", "用 file.read（encoding=base64）可把图片内容读出来")
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun systemBattery(context: Context) = ToolDef(
        name = "system.battery",
        title = "电池与充电状态",
        description = "读取 dumpsys battery 的真实数据：电量、温度、电压、充电方式、健康状态、是否省电模式。",
        schema = Schema.obj(emptyList()),
        handler = { ctx, _ ->
            val result = exec("dumpsys battery", 20000L)
            val map = JSONObject()
            result.first.lineSequence().forEach { line ->
                val m = Regex("^\\s*([a-zA-Z ]+):\\s*(.+)$").find(line) ?: return@forEach
                map.put(m.groupValues[1].trim(), m.groupValues[2].trim())
            }
            val level = map.optString("level").toIntOrNull()
            val temperature = map.optString("temperature").toDoubleOrNull()?.div(10)
            val structured = JSONObject().apply {
                put("level", level ?: JSONObject.NULL)
                put("status", map.optString("status", ""))
                put("plugged", map.optString("plugged", ""))
                put("health", map.optString("health", ""))
                put("temperatureC", temperature ?: JSONObject.NULL)
                put("voltageMv", map.optString("voltage").toIntOrNull() ?: JSONObject.NULL)
                put("powerSave", map.optString("Power save mode", ""))
                put("raw", map)
            }
            if (level == null && map.length() == 0) {
                throw IllegalStateException("读取电池信息失败：${result.first.take(200)}")
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun systemMemory(context: Context) = ToolDef(
        name = "system.memory",
        title = "内存占用",
        description = "返回真实内存数据：MemTotal / MemAvailable / Swap，以及当前应用进程的 Java 堆与系统内存状态。",
        schema = Schema.obj(emptyList()),
        handler = { ctx, _ ->
            val meminfo = runCatching { File("/proc/meminfo").readLines() }.getOrDefault(emptyList())
            fun kb(key: String): Long? = meminfo.firstOrNull { it.startsWith(key) }
                ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            val mgr = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo().also { mgr.getMemoryInfo(it) }
            val structured = JSONObject().apply {
                put("memTotalBytes", (kb("MemTotal") ?: 0L) * 1024)
                put("memAvailableBytes", (kb("MemAvailable") ?: 0L) * 1024)
                put("swapTotalBytes", (kb("SwapTotal") ?: 0L) * 1024)
                put("swapFreeBytes", (kb("SwapFree") ?: 0L) * 1024)
                put("memTotalHuman", ToolSupport.human((kb("MemTotal") ?: 0L) * 1024))
                put("memAvailableHuman", ToolSupport.human((kb("MemAvailable") ?: 0L) * 1024))
                put("lowMemory", info.lowMemory)
                put("thresholdBytes", info.threshold)
                put("appHeapUsedBytes", Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                put("appHeapMaxBytes", Runtime.getRuntime().maxMemory())
                put("cachedProcesses", mgr.runningAppProcesses?.size ?: 0)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun systemStorage(context: Context) = ToolDef(
        name = "system.storage",
        title = "存储占用",
        description = "返回数据分区与外置存储的真实容量（StatFs）以及关键挂载点的 df 输出，用于判断空间是否吃紧。",
        schema = Schema.obj(emptyList()),
        handler = { ctx, _ ->
            val data = StatFs(Environment.getDataDirectory().path)
            val ext = runCatching { StatFs(Environment.getExternalStorageDirectory().path) }.getOrNull()
            val df = exec("df -h 2>/dev/null | head -20", 15000L)
            val structured = JSONObject().apply {
                put("dataTotalBytes", data.totalBytes)
                put("dataFreeBytes", data.availableBytes)
                put("dataTotalHuman", ToolSupport.human(data.totalBytes))
                put("dataFreeHuman", ToolSupport.human(data.availableBytes))
                put("externalTotalHuman", ext?.let { ToolSupport.human(it.totalBytes) } ?: JSONObject.NULL)
                put("externalFreeHuman", ext?.let { ToolSupport.human(it.availableBytes) } ?: JSONObject.NULL)
                put("externalState", Environment.getExternalStorageState())
                put("df", df.first.lineSequence().take(20).joinToString("\n"))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun systemTop(context: Context) = ToolDef(
        name = "system.top",
        title = "内存占用最高的进程",
        description = "按 RSS 排序列出占用最高的进程（真实 ps 输出），可用来找吃内存的应用。",
        schema = Schema.obj(
            listOf("limit" to Schema.integer("最多返回数量", default = 15, min = 1, max = 60)),
        ),
        handler = { ctx, args ->
            val limit = args.optInt("limit", 15).coerceIn(1, 60)
            val result = exec("ps -A -o PID,RSS,NAME | sort -k2 -rn | head -60", 20000L)
            val lines = result.first.lineSequence().filter { it.isNotBlank() }.toList()
            val rows = JSONArray()
            lines.drop(1).take(limit).forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 3) {
                    val rssKb = parts[1].toLongOrNull() ?: return@forEach
                    rows.put(
                        JSONObject().apply {
                            put("pid", parts[0])
                            put("rssBytes", rssKb * 1024)
                            put("rssHuman", ToolSupport.human(rssKb * 1024))
                            put("name", parts.drop(2).joinToString(" "))
                        },
                    )
                }
            }
            val structured = JSONObject().apply {
                put("count", rows.length())
                put("processes", rows)
                put("backend", result.third)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun systemNotifications(context: Context) = ToolDef(
        name = "system.notifications",
        title = "当前通知",
        description = "读取通知栏里正在显示的通知（dumpsys notification），返回包名、标题与正文片段，可替代手工看通知栏。",
        schema = Schema.obj(
            listOf("limit" to Schema.integer("最多返回条数", default = 20, min = 1, max = 100)),
        ),
        handler = { ctx, args ->
            val limit = args.optInt("limit", 20).coerceIn(1, 100)
            val result = exec("dumpsys notification --noredact 2>/dev/null", 25000L)
            val text = result.first
            if (text.isBlank()) throw IllegalStateException("读取通知失败（高权限后端不可用）")
            val blocks = text.split(Regex("NotificationRecord\\("))
            val rows = JSONArray()
            blocks.drop(1).take(limit).forEach { block ->
                val pkg = Regex("pkg=([a-zA-Z0-9_.]+)").find(block)?.groupValues?.get(1)
                val channel = Regex("channel=([a-zA-Z0-9_.-]+)").find(block)?.groupValues?.get(1)
                val title = Regex("android\\.title=(.+)").find(block)?.groupValues?.get(1)?.trim()
                val body = Regex("android\\.text=(.+)").find(block)?.groupValues?.get(1)?.trim()
                if (pkg != null) {
                    rows.put(
                        JSONObject().apply {
                            put("package", pkg)
                            put("channel", channel ?: JSONObject.NULL)
                            put("title", title ?: JSONObject.NULL)
                            put("text", body?.take(200) ?: JSONObject.NULL)
                        },
                    )
                }
            }
            val structured = JSONObject().apply {
                put("count", rows.length())
                put("notifications", rows)
                put("backend", result.third)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun systemSensors(context: Context) = ToolDef(
        name = "system.sensors",
        title = "传感器清单",
        description = "列出本机传感器（类型、名称、厂商、功耗、量程）。",
        schema = Schema.obj(emptyList()),
        handler = { ctx, _ ->
            val manager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            val list = runCatching { manager.getSensorList(Sensor.TYPE_ALL) }.getOrDefault(emptyList())
            val rows = JSONArray()
            list.forEach { sensor ->
                rows.put(
                    JSONObject().apply {
                        put("name", sensor.name)
                        put("type", sensor.stringType)
                        put("vendor", sensor.vendor)
                        put("power", sensor.power)
                        put("maxRange", sensor.maximumRange)
                        put("resolution", sensor.resolution)
                    },
                )
            }
            val structured = JSONObject().apply {
                put("count", rows.length())
                put("sensors", rows)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun systemPower(context: Context) = ToolDef(
        name = "system.power",
        title = "电源与亮屏状态",
        description = "读取亮屏 / 熄屏、唤醒锁与最近唤醒原因，用于判断设备是否处于可用状态。",
        schema = Schema.obj(emptyList()),
        handler = { ctx, _ ->
            val result = exec("dumpsys power 2>/dev/null | grep -m12 -E 'mWakefulness|mScreenOn|Display Power|mHoldingWakeLockSuspendBlocker'", 20000L)
            val wakefulness = Regex("mWakefulness=([A-Za-z]+)").find(result.first)?.groupValues?.get(1)
            val structured = JSONObject().apply {
                put("wakefulness", wakefulness ?: JSONObject.NULL)
                put("screenOn", wakefulness?.equals("Awake", true) ?: false)
                put("raw", result.first.lineSequence().filter { it.isNotBlank() }.take(12).joinToString("\n"))
                put("backend", result.third)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun systemWifi(context: Context) = ToolDef(
        name = "system.wifi",
        title = "Wi-Fi 连接详情",
        description = "读取当前 Wi-Fi 的 SSID、BSSID、频段、链路速率、信号强度与 IP（cmd wifi status + dumpsys wifi 解析）。",
        schema = Schema.obj(emptyList()),
        handler = { ctx, _ ->
            val status = exec("cmd wifi status 2>/dev/null", 20000L)
            val text = status.first
            val structured = JSONObject().apply {
                put("raw", text.lineSequence().filter { it.isNotBlank() }.take(15).joinToString("\n"))
                put("ssid", Regex("SSID:\\s*\"?([^\"\\n,]+)\"?").find(text)?.groupValues?.get(1)?.trim() ?: JSONObject.NULL)
                put("bssid", Regex("BSSID:\\s*([0-9a-fA-F:]{17})").find(text)?.groupValues?.get(1) ?: JSONObject.NULL)
                put("ip", Regex("IP:\\s*([0-9.]+)").find(text)?.groupValues?.get(1) ?: JSONObject.NULL)
                put("rssi", Regex("RSSI:\\s*(-?\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: JSONObject.NULL)
                put("linkSpeed", Regex("Link speed:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: JSONObject.NULL)
                put("frequency", Regex("Frequency:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: JSONObject.NULL)
                put("backend", status.third)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun systemKill(context: Context) = ToolDef(
        name = "system.kill",
        title = "结束进程 / 停止应用",
        description = "强制停止一个包（am force-stop），或用 kill -9 结束指定 PID（需要高权限）。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("要强制停止的包名，可空", default = ""),
                "pid" to Schema.integer("要杀掉的 PID，可空（0 表示不用）", default = 0, min = 0, max = 999999),
            ),
        ),
        readOnly = false,
        dangerous = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            requirePrivilege(ctx)
            val pkg = args.optString("package").trim()
            val pid = args.optInt("pid", 0)
            if (pkg.isEmpty() && pid <= 0) throw IllegalArgumentException("package 与 pid 至少要给一个")
            val structured = JSONObject()
            if (pkg.isNotEmpty()) {
                val result = exec("am force-stop $pkg", 15000L)
                structured.put("package", pkg)
                structured.put("stopOutput", result.first.take(300))
                structured.put("backend", result.third)
            }
            if (pid > 0) {
                val result = exec("kill -9 $pid", 15000L)
                structured.put("pid", pid)
                structured.put("killOutput", result.first.take(300))
                structured.put("backend", result.third)
            }
            structured.put("applied", true)
            ToolResult(structured, structured.toString(2))
        },
    )
}

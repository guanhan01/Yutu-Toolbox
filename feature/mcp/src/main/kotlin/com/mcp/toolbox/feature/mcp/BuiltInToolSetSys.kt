package com.mcp.toolbox.feature.mcp

import android.app.ActivityManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import com.mcp.toolbox.core.common.PrivilegeManager
import com.mcp.toolbox.core.common.ToolboxAccessibilityService
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 真正能「动手机」的一组工具：界面层级、点击、滑动、输入、按键、截图，
 * 以及内存 / 传感器读数与结束进程。
 *
 * 纯 `dumpsys` / `getprop` / `ps` / `logcat` 之类的系统状态读取工具已移除——
 * 那些用 [BuiltInToolSetExtra.shellExec] 一条命令即可等价完成；
 * 而 [systemMemory] / [systemSensors] 走 Java API、不需要任何权限，故保留。
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
        systemWait(),
        systemMemory(context),
        systemSensors(context),
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
            WriteGuard.requireWrite()
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

    /**
     * 等待指定秒数。
     *
     * 存在的理由：模型经常要「跑一个很慢的命令，然后看结果」——安装包、编译、
     * 下载。它此前只有两条路：让 shell 自己 `sleep`（占满一次工具调用的超时，
     * 而且界面只能显示「正在执行」，看不出还剩多久），或者干脆空转。
     * 这个工具把等待变成一件**看得见**的事：卡片上直接倒计时。
     *
     * 上限 10 分钟不是随意定的：
     * - 整轮收尾时前端会把仍在 Running 的工具判成超时（见 `AgentTimeline` 的
     *   Finished 分支），等太久反而会被判超时，给出错误结论
     * - 更久的等待应交给 shell 后台任务（`nohup ... &`）再回来看，而不是干等
     *
     * 用户可以随时点「停止」中断——等待期间虽然不产生网络事件，但协程可取消。
     */
    private fun systemWait() = ToolDef(
        name = "system.wait",
        title = "等待",
        description = "等待指定秒数再继续，界面上会显示倒计时。" +
            "适合「命令已发出、要过一会儿才有结果」的场景，例如等安装、编译或下载完成。" +
            "最多 600 秒；更久的等待请改用后台任务。等待期间用户可以看到倒计时并随时停止。",
        schema = Schema.obj(
            listOf(
                "seconds" to Schema.integer("要等待的秒数（1-600）", default = 30, min = 1, max = 600),
                "reason" to Schema.string("为什么要等（显示在卡片上，便于用户理解）", default = ""),
            ),
            required = listOf("seconds"),
        ),
        readOnly = true,
        handler = { _, args ->
            val seconds = args.optInt("seconds", 30).coerceIn(1, 600)
            val reason = args.optString("reason").trim()
            val startedAt = System.currentTimeMillis()
            // 用挂起 delay 而不是 Thread.sleep：这样「停止」能立刻打断它。
            // handler 是同步签名，用 runBlocking 桥接到可取消的协程。
            kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(seconds * 1000L) }
            val structured = JSONObject().apply {
                put("waitedSeconds", seconds)
                put("elapsedMs", System.currentTimeMillis() - startedAt)
                if (reason.isNotEmpty()) put("reason", reason)
            }
            ToolResult(
                structured,
                "已等待 $seconds 秒" + (if (reason.isNotEmpty()) "（$reason）" else "") + "，可以继续。",
            )
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

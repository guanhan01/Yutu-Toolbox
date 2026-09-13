package com.mcp.toolbox.feature.mcp

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.mcp.toolbox.core.common.PrivilegeManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 应用管理工具：详情、组件、权限、启动、导出 APK、清数据、安装/卸载、禁用/启用。
 * 需要系统级动作的走高权限后端；缺权限时明确报错。
 */
object BuiltInToolSetApps {

    fun all(context: Context): List<ToolDef> = listOf(
        appInfo(context),
        appComponents(context),
        appPermissions(context),
        appLaunch(context),
        appExport(context),
        appDataDir(context),
        appClearData(context),
        appInstall(context),
        appUninstall(context),
        appDisable(context),
        appEnable(context),
    )

    private fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    private fun requireWrite() {
        if (!BuiltInMcpServer.config.value.allowWrite) {
            error("内置 Server 未开启写入：请在 MCP 页面打开「允许写入」后重试")
        }
    }

    private fun requirePrivilege(context: Context) {
        val status = PrivilegeManager.status(context)
        if (!status.usable) {
            error("需要高权限后端（${status.detail}）：请在设置页完成 root 授权，或安装并启动 Shizuku 后重试")
        }
    }

    private fun exec(command: String, timeoutMs: Long = 25000L): Pair<String, String> {
        val privileged = PrivilegeManager.exec(command, timeoutMs)
        if (privileged.ok) return privileged.stdout to privileged.backend.name
        return (privileged.output.ifBlank { privileged.stdout }) to privileged.backend.name
    }

    private fun info(context: Context, args: JSONObject): PackageInfo {
        val pkg = args.optString("package").trim()
        require(pkg.isNotEmpty()) { "缺少 package 参数" }
        val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or
            PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        @Suppress("DEPRECATION")
        return context.packageManager.getPackageInfo(pkg, flags)
    }

    private fun appInfo(context: Context) = ToolDef(
        name = "app.info",
        title = "应用详情",
        description = "读取已安装应用的详细信息：版本、uid、targetSdk、安装/更新时间、APK 路径与大小、是否系统应用、是否可卸载、数据目录大小（有高权限时）。",
        schema = Schema.obj(
            listOf("package" to Schema.string("应用包名")),
            required = listOf("package"),
        ),
        handler = { ctx, args ->
            val pm = ctx.packageManager
            val info = info(ctx, args)
            val app = info.applicationInfo
            val apk = app?.sourceDir?.let { File(it) }
            val dataDir = app?.dataDir
            val du = if (dataDir != null) exec("du -sh $dataDir 2>/dev/null") else "" to "NONE"
            val structured = JSONObject().apply {
                put("package", info.packageName)
                put("label", runCatching { pm.getApplicationLabel(app!!).toString() }.getOrDefault(info.packageName))
                put("versionName", info.versionName ?: JSONObject.NULL)
                put("versionCode", if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong())
                put("uid", app?.uid ?: JSONObject.NULL)
                put("targetSdk", app?.targetSdkVersion ?: JSONObject.NULL)
                put("minSdk", app?.minSdkVersion ?: JSONObject.NULL)
                put("enabled", app?.enabled ?: false)
                put("system", ((app?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) != 0)
                put("updatedSystemApp", ((app?.flags ?: 0) and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                put("flags", app?.flags ?: 0)
                put("apkPath", apk?.absolutePath ?: JSONObject.NULL)
                put("apkSizeBytes", apk?.length() ?: 0)
                put("apkSizeHuman", apk?.let { ToolSupport.human(it.length()) } ?: "-")
                put("apkReadable", apk?.canRead() ?: false)
                put("dataDir", dataDir ?: JSONObject.NULL)
                put("dataDirSize", du.first.trim().ifBlank { JSONObject.NULL })
                put("dataDirBackend", du.second)
                put("installTime", info.firstInstallTime)
                put("updateTime", info.lastUpdateTime)
                put("firstInstallText", java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(info.firstInstallTime)))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun appComponents(context: Context) = ToolDef(
        name = "app.components",
        title = "已安装应用组件",
        description = "从 PackageManager 读取应用的四类组件与启动入口 Activity，用于构造 am start 参数或分析可被外部唤起的面。",
        schema = Schema.obj(
            listOf("package" to Schema.string("应用包名")),
            required = listOf("package"),
        ),
        handler = { ctx, args ->
            val info = info(ctx, args)
            val launcher = ctx.packageManager.getLaunchIntentForPackage(info.packageName)?.component
            fun names(items: Array<out Any>?): JSONArray {
                val array = JSONArray()
                items?.forEach { array.put(it.toString()) }
                return array
            }
            val structured = JSONObject().apply {
                put("package", info.packageName)
                put("launcherActivity", launcher?.className ?: JSONObject.NULL)
                put("launcherComponent", launcher?.flattenToString() ?: JSONObject.NULL)
                put("activities", names(info.activities))
                put("services", names(info.services))
                put("receivers", names(info.receivers))
                put("providers", names(info.providers))
                put("activityCount", info.activities?.size ?: 0)
                put("serviceCount", info.services?.size ?: 0)
                put("receiverCount", info.receivers?.size ?: 0)
                put("providerCount", info.providers?.size ?: 0)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun appPermissions(context: Context) = ToolDef(
        name = "app.permissions",
        title = "已安装应用权限状态",
        description = "列出应用申请的全部权限及是否已授予（含运行时权限的授予结果），比只看清单更接近真实状态。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("应用包名"),
                "grantedOnly" to Schema.bool("只看已授予", default = false),
            ),
            required = listOf("package"),
        ),
        handler = { ctx, args ->
            val info = info(ctx, args)
            val grantedOnly = args.optBoolean("grantedOnly")
            val requested = info.requestedPermissions ?: emptyArray()
            val flags = info.requestedPermissionsFlags ?: IntArray(0)
            val rows = JSONArray()
            var grantedCount = 0
            requested.forEachIndexed { index, permission ->
                val granted = index < flags.size && (flags[index] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
                if (granted) grantedCount++
                if (grantedOnly && !granted) return@forEachIndexed
                rows.put(
                    JSONObject().apply {
                        put("permission", permission)
                        put("short", permission.substringAfterLast('.'))
                        put("granted", granted)
                    },
                )
            }
            val structured = JSONObject().apply {
                put("package", info.packageName)
                put("requestedCount", requested.size)
                put("grantedCount", grantedCount)
                put("permissions", rows)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun appLaunch(context: Context) = ToolDef(
        name = "app.launch",
        title = "启动应用",
        description = "启动已安装应用（优先用它自己的启动入口 Activity，找不到时退回 monkey）。需要高权限后端。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("应用包名"),
                "activity" to Schema.string("指定 Activity 全名，可空", default = ""),
            ),
            required = listOf("package"),
        ),
        readOnly = false,
        requiresPrivilege = true,
        handler = { ctx, args ->
            requirePrivilege(ctx)
            val pkg = args.getString("package").trim()
            val explicit = args.optString("activity").trim()
            val component = when {
                explicit.isNotEmpty() -> "$pkg/$explicit"
                else -> ctx.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToString()
                    ?: "$pkg/.MainActivity"
            }
            val result = exec("am start -n $component 2>&1", 25000L)
            val structured = JSONObject().apply {
                put("package", pkg)
                put("component", component)
                put("backend", result.second)
                put("ok", !result.first.contains("Error") && !result.first.contains("Exception"))
                put("output", result.first.take(500))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun appExport(context: Context) = ToolDef(
        name = "app.export",
        title = "导出已安装应用的 APK",
        description = "把已安装应用的 APK 复制到目标路径（优先直接读，读不到时用高权限 cp）。需要开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("应用包名"),
                "outDir" to Schema.string("输出目录", default = "/sdcard/Download/apk"),
                "fileName" to Schema.string("输出文件名，空则用 包名.apk", default = ""),
            ),
            required = listOf("package"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            requireWrite()
            val pkg = args.getString("package").trim()
            val app = ctx.packageManager.getApplicationInfo(pkg, 0)
            val source = app.sourceDir?.let { File(it) } ?: throw IllegalStateException("拿不到 $pkg 的 APK 路径")
            val outDir = ToolSupport.resolve(ctx, args.optString("outDir", "/sdcard/Download/apk"))
            outDir.mkdirs()
            val name = args.optString("fileName").trim().ifEmpty { "$pkg.apk" }
            val target = File(outDir, name)
            var mode = "direct"
            var ok = runCatching {
                source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            }.isSuccess && target.length() > 0
            if (!ok) {
                mode = "privileged-cp"
                val result = exec("cp ${shellQuote(source.absolutePath)} ${shellQuote(target.absolutePath)} && chmod 644 ${shellQuote(target.absolutePath)}")
                ok = target.isFile && target.length() > 0
                if (!ok) throw IllegalStateException("导出失败：${result.first.take(300)}（该应用 APK 可能不可读）")
            }
            val structured = JSONObject().apply {
                put("package", pkg)
                put("source", source.absolutePath)
                put("target", target.absolutePath)
                put("sizeBytes", target.length())
                put("sizeHuman", ToolSupport.human(target.length()))
                put("mode", mode)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun appDataDir(context: Context) = ToolDef(
        name = "app.dataDir",
        title = "应用数据目录",
        description = "返回应用的数据目录路径、是否存在、占用（有高权限时用 du 统计），以及 root 权限下能否列出文件。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("应用包名"),
                "list" to Schema.bool("是否顺便列出目录内容（需要高权限）", default = false),
                "limit" to Schema.integer("最多列出条目数", default = 60, min = 1, max = 400),
            ),
            required = listOf("package"),
        ),
        handler = { ctx, args ->
            val pkg = args.getString("package").trim()
            val app = ctx.packageManager.getApplicationInfo(pkg, 0)
            val dir = app.dataDir ?: throw IllegalStateException("拿不到 $pkg 的数据目录")
            val du = exec("du -sh $dir 2>/dev/null")
            val structured = JSONObject().apply {
                put("package", pkg)
                put("dataDir", dir)
                put("du", du.first.trim().ifBlank { JSONObject.NULL })
                if (args.optBoolean("list")) {
                    val limit = args.optInt("limit", 60).coerceIn(1, 400)
                    val result = exec("ls -la $dir 2>&1 | head -401")
                    put("listing", result.first.lineSequence().take(limit).joinToString("\n"))
                    put("listingBackend", result.second)
                }
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun appClearData(context: Context) = ToolDef(
        name = "app.clearData",
        title = "清除应用数据",
        description = "执行 pm clear 清空指定应用的全部数据。危险操作，需要开启「允许写入」与高权限后端。",
        schema = Schema.obj(
            listOf("package" to Schema.string("应用包名")),
            required = listOf("package"),
        ),
        readOnly = false,
        dangerous = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            requireWrite()
            requirePrivilege(ctx)
            val pkg = args.getString("package").trim()
            val result = exec("pm clear $pkg 2>&1", 30000L)
            val structured = JSONObject().apply {
                put("package", pkg)
                put("output", result.first.take(300))
                put("ok", result.first.contains("Success", ignoreCase = true))
                put("backend", result.second)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun appInstall(context: Context) = ToolDef(
        name = "app.install",
        title = "安装 APK",
        description = "执行 pm install 安装指定 APK（-r 覆盖、-d 允许降级、-g 授予全部运行时权限）。需要开启「允许写入」与高权限后端。",
        schema = Schema.obj(
            listOf(
                "apk" to Schema.string("APK 文件路径（允许根内）"),
                "reinstall" to Schema.bool("覆盖安装（-r）", default = true),
                "downgrade" to Schema.bool("允许降级（-d）", default = true),
                "grantAll" to Schema.bool("安装时授予全部权限（-g）", default = true),
            ),
            required = listOf("apk"),
        ),
        readOnly = false,
        dangerous = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            requireWrite()
            requirePrivilege(ctx)
            val apk = ToolSupport.resolve(ctx, args.getString("apk"))
            require(apk.isFile) { "APK 不存在：${apk.absolutePath}" }
            val flags = buildString {
                if (args.optBoolean("reinstall", true)) append(" -r")
                if (args.optBoolean("downgrade", true)) append(" -d")
                if (args.optBoolean("grantAll", true)) append(" -g")
            }
            val result = exec("pm install$flags ${shellQuote(apk.absolutePath)} 2>&1", 120000L)
            val out = result.first
            val structured = JSONObject().apply {
                put("apk", apk.absolutePath)
                put("sizeBytes", apk.length())
                put("output", out.take(600))
                put("ok", out.contains("Success", ignoreCase = true))
                put("backend", result.second)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun appUninstall(context: Context) = ToolDef(
        name = "app.uninstall",
        title = "卸载应用",
        description = "卸载指定应用（保留数据可选 -k）。危险操作，需要开启「允许写入」与高权限后端。",
        schema = Schema.obj(
            listOf(
                "package" to Schema.string("应用包名"),
                "keepData" to Schema.bool("保留数据与缓存（-k）", default = false),
            ),
            required = listOf("package"),
        ),
        readOnly = false,
        dangerous = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            requireWrite()
            requirePrivilege(ctx)
            val pkg = args.getString("package").trim()
            val keep = if (args.optBoolean("keepData")) " -k" else ""
            val result = exec("pm uninstall$keep --user 0 $pkg 2>&1", 60000L)
            val structured = JSONObject().apply {
                put("package", pkg)
                put("output", result.first.take(400))
                put("ok", result.first.contains("Success", ignoreCase = true))
                put("backend", result.second)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun appDisable(context: Context) = ToolDef(
        name = "app.disable",
        title = "禁用应用",
        description = "禁用指定应用（pm disable-user，等价于系统设置里的「停用」）。危险操作，需要高权限。",
        schema = Schema.obj(
            listOf("package" to Schema.string("应用包名")),
            required = listOf("package"),
        ),
        readOnly = false,
        dangerous = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            requirePrivilege(ctx)
            val pkg = args.getString("package").trim()
            val result = exec("pm disable-user --user 0 $pkg 2>&1", 30000L)
            val structured = JSONObject().apply {
                put("package", pkg)
                put("output", result.first.take(300))
                put("ok", result.first.contains("new state", ignoreCase = true))
                put("backend", result.second)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun appEnable(context: Context) = ToolDef(
        name = "app.enable",
        title = "启用应用",
        description = "重新启用被禁用 / 冻结的应用（pm enable）。需要高权限后端。",
        schema = Schema.obj(
            listOf("package" to Schema.string("应用包名")),
            required = listOf("package"),
        ),
        readOnly = false,
        requiresPrivilege = true,
        handler = { ctx, args ->
            requirePrivilege(ctx)
            val pkg = args.getString("package").trim()
            val result = exec("pm enable --user 0 $pkg 2>&1", 30000L)
            val structured = JSONObject().apply {
                put("package", pkg)
                put("output", result.first.take(300))
                put("ok", result.first.contains("new state", ignoreCase = true))
                put("backend", result.second)
            }
            ToolResult(structured, structured.toString(2))
        },
    )
}

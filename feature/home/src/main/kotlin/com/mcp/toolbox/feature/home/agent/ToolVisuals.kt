package com.mcp.toolbox.feature.home.agent

import androidx.annotation.DrawableRes
import com.mcp.toolbox.core.design.icon.AgentIcons

/**
 * 工具的「视觉身份」：图标 + 中文名 + 结果分层。
 *
 * 115 个内置工具 + 外部 MCP 工具都要能落到一枚图标上。做法是**按工具名的前缀
 * （命名空间）与关键词匹配**，而不是逐个枚举——新工具加入时无需改这里，
 * 落不到具体规则也能命中 [fallback] 兜底，不会出现空白图标。
 *
 * 图标全部来自 `core:designsystem` 的 AI Agent 单色图标集（364 枚），
 * 单色 → 由调用点 tint 成语义色，因此这里只表达「画什么」。
 */
object ToolVisuals {

    /** 工具的中文展示名。命中规则时返回具体名，否则回退成工具名本身。 */
    fun displayName(toolName: String): String {
        TOOL_NAMES[toolName]?.let { return it }
        val ns = toolName.substringBefore('.')
        val leaf = toolName.substringAfter('.', toolName)
        return NAMESPACE_LABEL[ns]?.let { "$it · $leaf" } ?: toolName
    }

    /** 工具的图标。 */
    @DrawableRes
    fun icon(toolName: String): Int {
        // 1) 精确命中
        TOOL_ICONS[toolName]?.let { return it }
        // 2) 命名空间命中
        NAMESPACE_ICON[toolName.substringBefore('.')]?.let { return it }
        // 3) 关键词命中（取第一个匹配的关键词规则）
        val lower = toolName.lowercase()
        KEYWORD_ICONS.firstOrNull { (kw, _) -> kw in lower }?.let { return it.second }
        return fallback
    }

    /** 工具的动词文案，用于 Running 状态的「正在搜索…」。 */
    fun runningLabel(toolName: String): String =
        TOOL_VERBS[toolName]
            ?: NAMESPACE_VERB[toolName.substringBefore('.')]
            ?: "正在执行…"

    /**
     * 把「还要等多久」格式化成 `12s` / `1:05`。
     *
     * 纯函数，单测覆盖边界（负数、零、超过一分钟、超过一分钟的整秒）。
     * 负数按 0 处理：调用方可能因为时序拿到刚过期的 deadline，不该显示成 `-1s`。
     */
    fun formatEta(remainingMs: Long): String {
        val total = (remainingMs / 1000L).coerceAtLeast(0L)
        return if (total < 60L) {
            "${total}s"
        } else {
            "%d:%02d".format(total / 60L, total % 60L)
        }
    }

    /** 兜底图标：未知工具也用一枚「插件」而不是空白。 */
    @DrawableRes
    val fallback: Int = AgentIcons.ic_plugin

    /**
     * 把工具的原始文本结果整理成四层结构（Spec 第 7.2 节）。
     *
     * - L1 摘要：永远展示，取首行并压缩长度
     * - L2 来源：网页搜索专属，由调用方传入
     * - L4 原始输出：完整文本，默认折叠
     *
     * 刻意**不做**「把整段结果当摘要」——那正是 Spec 要避免的「上千 dp 的滚动噩梦」。
     */
    fun buildResult(
        toolName: String,
        raw: String,
        success: Boolean,
        sources: List<WebSource>? = null,
    ): ToolResult {
        val trimmed = raw.trim()
        val summary = when {
            !success -> trimmed.lineSequence().firstOrNull { it.isNotBlank() }
                ?.take(SUMMARY_LIMIT) ?: "执行失败"

            trimmed.isEmpty() -> "已完成，无输出"

            else -> summarize(trimmed)
        }
        return ToolResult(
            summary = summary,
            sources = sources?.takeIf { it.isNotEmpty() },
            rawText = trimmed.takeIf { it.isNotEmpty() },
        )
    }

    private const val SUMMARY_LIMIT = 80

    /** 摘要：首行 + 行数提示，让用户知道「还有更多」。 */
    private fun summarize(text: String): String {
        val lines = text.lines().filter { it.isNotBlank() }
        val first = lines.firstOrNull().orEmpty().trim().take(SUMMARY_LIMIT)
        return if (lines.size > 1) "$first …（共 ${lines.size} 行）" else first
    }

    // ------------------------------------------------------------------
    // 规则表
    // ------------------------------------------------------------------

    /** 精确工具名 → 图标。只给「语义明显、值得单独一枚」的工具。 */
    private val TOOL_ICONS: Map<String, Int> = mapOf(
        // 搜索与信息
        "web.fetch" to AgentIcons.ic_web_search,
        "http.request" to AgentIcons.ic_http_request,
        "net.info" to AgentIcons.ic_wifi,
        // 代码与开发
        "shell.exec" to AgentIcons.ic_terminal,
        "linux.shell" to AgentIcons.ic_terminal,
        "linux.exec" to AgentIcons.ic_terminal,
        "linux.shell_status" to AgentIcons.ic_terminal,
        "linux.shell_kill" to AgentIcons.ic_agent_stop,
        "linux.env" to AgentIcons.ic_docker,
        // 文件与存储
        "file.list" to AgentIcons.ic_folder,
        "file.tree" to AgentIcons.ic_folder,
        "file.info" to AgentIcons.ic_file,
        "file.read" to AgentIcons.ic_file_text,
        "file.write" to AgentIcons.ic_file_text,
        "file.grep" to AgentIcons.ic_search,
        "file.search" to AgentIcons.ic_search,
        "file.hash" to AgentIcons.ic_shield_check,
        "file.zip" to AgentIcons.ic_file_zip,
        "file.unzip" to AgentIcons.ic_file_zip,
        "file.delete" to AgentIcons.ic_trash,
        "file.mkdir" to AgentIcons.ic_folder_add,
        "file.copy" to AgentIcons.ic_copy,
        "file.move" to AgentIcons.ic_drag_handle,
        "file.rename" to AgentIcons.ic_edit,
        "file.chmod" to AgentIcons.ic_lock,
        "file.replace" to AgentIcons.ic_rewrite,
        "file.du" to AgentIcons.ic_svc_storage_bucket,
        // 应用与包
        "app.list" to AgentIcons.ic_app_shortcut,
        "app.info" to AgentIcons.ic_info,
        "app.launch" to AgentIcons.ic_play_circle,
        "app.install" to AgentIcons.ic_download,
        "app.uninstall" to AgentIcons.ic_trash,
        "app.export" to AgentIcons.ic_export,
        "app.enable" to AgentIcons.ic_check,
        "app.disable" to AgentIcons.ic_blocked,
        "app.permissions" to AgentIcons.ic_shield_check,
        "app.components" to AgentIcons.ic_package,
        // 反编译
        "apk.info" to AgentIcons.ic_package,
        "apk.manifest" to AgentIcons.ic_schema,
        "apk.permissions" to AgentIcons.ic_shield,
        "apk.signature" to AgentIcons.ic_signature,
        "apk.entries" to AgentIcons.ic_archive,
        "apk.extract" to AgentIcons.ic_archive,
        "apk.export" to AgentIcons.ic_export,
        "apk.resources" to AgentIcons.ic_svc_storage_bucket,
        "apk.smali" to AgentIcons.ic_code_block,
        "apk.java" to AgentIcons.ic_code,
        "apk.sources" to AgentIcons.ic_code,
        "dex.list" to AgentIcons.ic_menu,
        "dex.class" to AgentIcons.ic_code,
        "dex.java" to AgentIcons.ic_code,
        "dex.smali" to AgentIcons.ic_code_block,
        "dex.strings" to AgentIcons.ic_file_text,
        "dex.search" to AgentIcons.ic_search,
        "decode.smali" to AgentIcons.ic_code_block,
        // 构建
        "build.assemble" to AgentIcons.ic_deploy,
        "build.rebuild" to AgentIcons.ic_refresh,
        "build.sign" to AgentIcons.ic_signature,
        "build.verify" to AgentIcons.ic_shield_check,
        "android.install_apk" to AgentIcons.ic_download,
        "keystore.manage" to AgentIcons.ic_key,
        // 数据库
        "db.list" to AgentIcons.ic_svc_database_sql,
        "db.tables" to AgentIcons.ic_table,
        "db.schema" to AgentIcons.ic_schema,
        "db.query" to AgentIcons.ic_code,
        "db.exec" to AgentIcons.ic_terminal,
        "db.rows" to AgentIcons.ic_add_row,
        "db.dump" to AgentIcons.ic_snapshot,
        "sql.query" to AgentIcons.ic_svc_database_sql,
        // 抓包
        "capture.list" to AgentIcons.ic_svc_logs,
        "capture.detail" to AgentIcons.ic_info,
        "capture.records" to AgentIcons.ic_svc_logs,
        "capture.export" to AgentIcons.ic_export,
        "capture.stats" to AgentIcons.ic_statistics,
        "capture.clear" to AgentIcons.ic_clean,
        // 系统
        "device.info" to AgentIcons.ic_phone_device,
        "system.memory" to AgentIcons.ic_memory,
        "system.wait" to AgentIcons.ic_clock,
        "system.cpu" to AgentIcons.ic_cpu,
        "system.sensors" to AgentIcons.ic_speed,
        "system.kill" to AgentIcons.ic_agent_stop,
        "settings.get" to AgentIcons.ic_settings,
        "settings.put" to AgentIcons.ic_settings_slider,
        "clipboard.get" to AgentIcons.ic_copy,
        "clipboard.set" to AgentIcons.ic_copy,
        // UI 自动化
        "ui.tree" to AgentIcons.ic_split_view,
        "ui.current" to AgentIcons.ic_app_bar,
        "ui.tap" to AgentIcons.ic_agent_handoff_human,
        "ui.swipe" to AgentIcons.ic_swipe,
        "ui.text" to AgentIcons.ic_keyboard,
        "ui.key" to AgentIcons.ic_keyboard,
        "ui.screenshot" to AgentIcons.ic_screenshot,
        // 记忆 / Skill / 产物
        "memory.write" to AgentIcons.ic_agent_memory,
        "memory.delete" to AgentIcons.ic_trash,
        "skill.list" to AgentIcons.ic_plugin,
        "skill.read" to AgentIcons.ic_book,
        "artifact.list" to AgentIcons.ic_archive,
        // Agent 编排（app 模块注入的三个）
        "agent.plan" to AgentIcons.ic_agent_plan,
        "agent.stop" to AgentIcons.ic_agent_stop,
    )

    /** 命名空间 → 图标（精确规则漏掉时的第二层）。 */
    private val NAMESPACE_ICON: Map<String, Int> = mapOf(
        "web" to AgentIcons.ic_browser,
        "http" to AgentIcons.ic_http_request,
        "net" to AgentIcons.ic_globe,
        "shell" to AgentIcons.ic_terminal,
        "linux" to AgentIcons.ic_docker,
        "file" to AgentIcons.ic_file,
        "app" to AgentIcons.ic_app_shortcut,
        "apk" to AgentIcons.ic_package,
        "dex" to AgentIcons.ic_code_block,
        "decode" to AgentIcons.ic_code,
        "build" to AgentIcons.ic_deploy,
        "db" to AgentIcons.ic_svc_database_sql,
        "sql" to AgentIcons.ic_svc_database_sql,
        "capture" to AgentIcons.ic_svc_logs,
        "system" to AgentIcons.ic_cpu,
        "device" to AgentIcons.ic_phone_device,
        "process" to AgentIcons.ic_menu,
        "package" to AgentIcons.ic_package,
        "settings" to AgentIcons.ic_settings,
        "clipboard" to AgentIcons.ic_copy,
        "ui" to AgentIcons.ic_agent_handoff_human,
        "memory" to AgentIcons.ic_agent_memory,
        "skill" to AgentIcons.ic_plugin,
        "artifact" to AgentIcons.ic_archive,
        "keystore" to AgentIcons.ic_key,
        "android" to AgentIcons.ic_phone_device,
        "agent" to AgentIcons.ic_bot,
        "logcat" to AgentIcons.ic_svc_logs,
    )

    /** 关键词 → 图标（第三层，处理外部 MCP 工具的自由命名）。 */
    private val KEYWORD_ICONS: List<Pair<String, Int>> = listOf(
        "search" to AgentIcons.ic_search,
        "fetch" to AgentIcons.ic_web_search,
        "http" to AgentIcons.ic_http_request,
        "sql" to AgentIcons.ic_svc_database_sql,
        "database" to AgentIcons.ic_database,
        "file" to AgentIcons.ic_file,
        "folder" to AgentIcons.ic_folder,
        "dir" to AgentIcons.ic_folder,
        "read" to AgentIcons.ic_file_text,
        "write" to AgentIcons.ic_edit,
        "delete" to AgentIcons.ic_trash,
        "exec" to AgentIcons.ic_terminal,
        "shell" to AgentIcons.ic_terminal,
        "code" to AgentIcons.ic_code,
        "image" to AgentIcons.ic_image,
        "video" to AgentIcons.ic_video,
        "audio" to AgentIcons.ic_music,
        "memory" to AgentIcons.ic_agent_memory,
        "plan" to AgentIcons.ic_agent_plan,
        "time" to AgentIcons.ic_clock,
        "mail" to AgentIcons.ic_email,
        "calendar" to AgentIcons.ic_calendar,
        "chart" to AgentIcons.ic_chart_bar,
        "log" to AgentIcons.ic_svc_logs,
    )

    /** 工具名 → 中文名。表里没有的走 [NAMESPACE_LABEL] 组合。 */
    private val TOOL_NAMES: Map<String, String> = mapOf(
        "web.fetch" to "网页抓取",
        "http.request" to "HTTP 请求",
        "net.info" to "网络信息",
        "shell.exec" to "执行命令",
        "system.wait" to "等待中",
        "linux.shell" to "Linux 终端",
        "linux.exec" to "Linux 执行",
        "linux.shell_status" to "终端状态",
        "linux.shell_kill" to "结束终端",
        "linux.env" to "Linux 环境",
        "linux.file_list" to "Linux 文件列表",
        "linux.file_read" to "Linux 读文件",
        "linux.file_write" to "Linux 写文件",
        "linux.mount_list" to "挂载列表",
        "linux.mount_add" to "添加挂载",
        "linux.mount_remove" to "移除挂载",
        "file.list" to "文件列表",
        "file.tree" to "目录树",
        "file.read" to "读取文件",
        "file.write" to "写入文件",
        "file.grep" to "文本检索",
        "file.search" to "文件搜索",
        "file.info" to "文件信息",
        "file.hash" to "文件校验",
        "file.delete" to "删除文件",
        "file.zip" to "压缩",
        "file.unzip" to "解压",
        "app.list" to "应用列表",
        "app.info" to "应用信息",
        "app.launch" to "启动应用",
        "app.install" to "安装应用",
        "app.uninstall" to "卸载应用",
        "app.permissions" to "应用权限",
        "apk.info" to "APK 信息",
        "apk.manifest" to "清单解析",
        "apk.signature" to "签名校验",
        "apk.smali" to "反编译 Smali",
        "apk.java" to "反编译 Java",
        "dex.list" to "Dex 列表",
        "dex.search" to "Dex 检索",
        "build.assemble" to "构建 APK",
        "build.rebuild" to "重打包",
        "build.sign" to "签名",
        "build.verify" to "验证签名",
        "db.query" to "SQL 查询",
        "db.tables" to "数据表",
        "db.schema" to "表结构",
        "capture.list" to "抓包列表",
        "device.info" to "设备信息",
        "system.memory" to "内存信息",
        "system.wait" to "等待",
        "settings.get" to "读取设置",
        "settings.put" to "修改设置",
        "ui.tree" to "界面树",
        "ui.tap" to "点击",
        "ui.swipe" to "滑动",
        "ui.text" to "输入文本",
        "ui.screenshot" to "截图",
        "memory.write" to "写入记忆",
        "memory.delete" to "删除记忆",
        "skill.list" to "Skill 列表",
        "skill.read" to "读取 Skill",
        "artifact.list" to "产物列表",
        "agent.plan" to "制定计划",
        "agent.stop" to "停止 Agent",
    )

    /** 命名空间 → 中文前缀。 */
    private val NAMESPACE_LABEL: Map<String, String> = mapOf(
        "web" to "网页",
        "http" to "网络",
        "net" to "网络",
        "shell" to "命令",
        "linux" to "Linux",
        "file" to "文件",
        "app" to "应用",
        "apk" to "APK",
        "dex" to "Dex",
        "decode" to "反编译",
        "build" to "构建",
        "db" to "数据库",
        "sql" to "SQL",
        "capture" to "抓包",
        "system" to "系统",
        "device" to "设备",
        "process" to "进程",
        "package" to "包管理",
        "settings" to "设置",
        "clipboard" to "剪贴板",
        "ui" to "界面",
        "memory" to "记忆",
        "skill" to "Skill",
        "artifact" to "产物",
        "keystore" to "密钥",
        "android" to "安装",
        "agent" to "Agent",
        "logcat" to "日志",
    )

    /** Running 状态的动词文案（Spec 第 7.1 节的「正在搜索…」）。 */
    private val TOOL_VERBS: Map<String, String> = mapOf(
        "web.fetch" to "正在抓取网页…",
        "http.request" to "正在请求…",
        "file.read" to "正在读取…",
        "file.grep" to "正在检索…",
        "file.search" to "正在搜索…",
        "shell.exec" to "正在执行…",
        "db.query" to "正在查询…",
        "apk.smali" to "正在反编译…",
        "apk.java" to "正在反编译…",
        "build.assemble" to "正在构建…",
        "ui.screenshot" to "正在截图…",
        "ui.tree" to "正在读取界面…",
    )

    private val NAMESPACE_VERB: Map<String, String> = mapOf(
        "web" to "正在联网…",
        "net" to "正在联网…",
        "http" to "正在请求…",
        "file" to "正在处理文件…",
        "db" to "正在查询…",
        "sql" to "正在查询…",
        "shell" to "正在执行…",
        "linux" to "正在执行…",
        "system" to "正在读取系统…",
        "device" to "正在读取设备…",
        "ui" to "正在操作界面…",
        "apk" to "正在解析 APK…",
        "dex" to "正在解析 Dex…",
    )
}

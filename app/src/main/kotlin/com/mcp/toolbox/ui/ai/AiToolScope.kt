package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.feature.mcp.BuiltInToolSet
import com.mcp.toolbox.feature.mcp.ToolDef
import org.json.JSONObject

/**
 * 工具 schema 的**按需下发**范围计算。
 *
 * ## 为什么要做这件事
 *
 * 内置 89 个工具的 JSON Schema 每轮固定占约 **16.7k prompt token**（实测统计：
 * 描述 11.1k 字节 + 558 个 schema 属性）。对 1M 窗口只占 1.6%，但：
 * - 它是**每轮都重发**的固定成本，与用户问了什么无关；
 * - 其中一大半是「专项分析」工具（apk / dex / db / build），
 *   日常对话里根本用不到，却照样把参数说明全发过去。
 *
 * 业界实测（JetBrains，SWE-bench）：**减少无关上下文比事后压缩更划算**，
 * 且"防患"优于"压缩"。这里就属于防患 —— 一开始就不发用不上的东西。
 *
 * ## 关键设计：**永不隐藏工具**
 *
 * 只压缩**参数 schema**，不删工具本身 —— 每个工具的名字与一句话描述始终下发。
 * 理由：AI 必须先"看得见"一个工具才可能用它；直接藏掉会让它以为不具备该能力，
 * 表现成"明明有工具却说做不到"。
 *
 * 所以精简版长这样（`parameters` 换成极小占位，description 里补参数摘要）：
 *
 * ```json
 * { "name": "apk_decompile",
 *   "description": "反编译 APK。参数：package(包名), apk(路径), output(输出目录)",
 *   "parameters": { "type": "object", "additionalProperties": true } }
 * ```
 *
 * ## 为什么不能干脆删掉 `parameters`
 *
 * 四家后端都把它当必填（实测确认）：
 * - Anthropic：`input_schema` 必填
 * - Gemini：`parameters` 必填，且不接受 `additionalProperties`（另有 sanitize）
 * - Responses / OpenAI 兼容：`parameters` 惯例必填
 *
 * 所以只能换占位，不能删。
 *
 * ## 三档展开（丙方案）
 *
 * 1. **核心组**：永远完整；
 * 2. **关键词命中**：用户消息里出现该组的关键词 → 整组展开（零往返，覆盖大多数场景）；
 * 3. **AI 主动展开**：都没命中时，AI 仍可调用参数查询工具拿完整说明
 *    （见 [TOOL_DESCRIBE]）。
 */
object AiToolScope {

    /**
     * 「AI 主动展开」用的元工具名。
     *
     * 它**永远完整下发**（自己在 [CORE] 里），否则 AI 连展开手段都没有。
     */
    const val TOOL_DESCRIBE = "tool.describe"

    /**
     * 核心组：这些**永远下发完整参数**。
     *
     * 判据是「通用、且常常是调用其他功能的前置」：
     * 任何任务都可能要先 `file.read` 拿到目标、先 `app.list` 找到包名，
     * 所以它们不能等关键词命中才展开。
     *
     * 注意 `apk` / `dex` 这类**专项分析**工具**不在**核心组 ——
     * 它们体积大（apk 2.3k + dex 1.4k token），且只在明确要分析安装包时才用。
     * 需要时由关键词或参数查询工具展开，不会用不上。
     */
    private val CORE = setOf(
        "file",       // 通用读写：几乎所有任务的前置
        "app",        // 查已安装应用：拿到包名才能做后续
        "device",     // 设备信息
        "shell",      // 通用命令
        "system",     // 系统信息
        "settings",   // 系统设置
        "clipboard",  // 剪贴板
        "skill",      // 技能：模型自省的入口
        "artifact",   // 产物：与文件配套
        "http",       // 网络请求
        "web",        // 网页抓取
        "net",        // 网络信息
    )

    /**
     * 关键词 → 工具组。命中即整组展开。
     *
     * 关键词要**宽一点**：漏判的代价是 AI 多一次「先查参数」的往返，
     * 误判的代价只是多发一点 schema。所以宁可多列。
     */
    private val HINTS: Map<String, List<String>> = mapOf(
        "apk" to listOf(
            "apk", "安装包", "反编译", "签名", "证书", "manifest", "清单",
            "渠道", "applicationid", "打包", "解包", "dex",
        ),
        "dex" to listOf(
            "dex", "smali", "字节码", "反汇编", "方法数", "类列表", "方法表",
        ),
        "db" to listOf(
            "数据库", "sqlite", "sql", "表结构", "建表", "查询语句", "记录",
        ),
        "build" to listOf(
            "gradle", "编译", "构建", "依赖", "sdk 版本", "targetsdk",
        ),
        "ui" to listOf(
            "界面", "控件", "点击", "滑动", "输入框", "按钮", "无障碍",
            "automation", "节点", "坐标",
        ),
        "capture" to listOf(
            "截图", "截屏", "录屏", "抓包", "录包", "packet", "抓取流量",
        ),
        "sql" to listOf(
            "sql", "查询", "select", "insert", "update", "delete",
        ),
        "decode" to listOf(
            "解码", "编码", "base64", "hex", "url 编码",
        ),
        "keystore" to listOf(
            "keystore", "密钥", "签名文件", "jks", "p12",
        ),
    )

    /**
     * 需要高权限才真正可用的组。
     *
     * 环境不具备（无 root / Shizuku、无障碍没开）时，这些组的完整 schema 发了也白发 ——
     * 工具本身会返回「权限不足」。所以直接压成名字+一句话。
     */
    private val PRIVILEGED_GROUPS = mapOf(
        "ui" to PrivilegeKind.ACCESSIBILITY,
        "capture" to PrivilegeKind.PRIVILEGE,
    )

    private enum class PrivilegeKind { ACCESSIBILITY, PRIVILEGE }

    /** 一次请求的展开决定。 */
    data class Decision(
        /** 该完整下发的组。 */
        val expandedGroups: Set<String>,
        /** 未展开的组（只给名字 + 参数摘要 + 占位 schema）。 */
        val compactedGroups: Set<String>,
    ) {
        fun isExpanded(group: String): Boolean = group !in compactedGroups
    }

    /**
     * 算出这次该展开哪些组。
     *
     * 入参直接给 `history`，由本函数自己取最近两条用户消息 ——
     * 这样四个后端只要各改一行调用，不必各自复制一份「怎么取文本」的逻辑。
     *
     * 为什么取两条：单条里常有「它」「这个」这类指代，带上一条能提高命中率；
     * 再多会让旧话题的关键词一直粘着，把无关的组也撑开（费 token 且没必要）。
     *
     * @param history 当前请求的完整消息列表；null/空表示只下发核心组。
     */
    fun decide(context: Context, history: List<com.mcp.toolbox.feature.home.ChatMessage>?): Decision {
        val text = history.orEmpty()
            .asReversed()
            .filter { it.role == com.mcp.toolbox.feature.home.ChatMessage.Role.USER }
            .take(2)
            .joinToString("\n") { it.content }
            .lowercase()
        val expanded = CORE.toMutableSet()

        // ② 关键词命中 -> 展开整组
        HINTS.forEach { (group, words) ->
            if (words.any { text.contains(it) }) expanded.add(group)
        }

        // ③ 「展开工具」这个元工具必须一直可用，否则 AI 没有自救手段
        //    （它由 toolSpecs 以合成工具的形式加入，不占组名）

        // 权限闸门：环境不具备的组强制压缩，哪怕关键词命中了
        val allGroups = groupsOf(context)
        val compacted = allGroups - expanded + allGroups.filter { forcedCompact(context, it) }
        return Decision(expandedGroups = expanded, compactedGroups = compacted)
    }

    /** 强制压缩：权限不具备时，展开也没意义。 */
    private fun forcedCompact(context: Context, group: String): Boolean =
        when (PRIVILEGED_GROUPS[group]) {
            PrivilegeKind.ACCESSIBILITY -> !AiToolBridge.accessibilityReady
            PrivilegeKind.PRIVILEGE -> !AiToolBridge.privilegeReady(context)
            null -> false
        }

    /** 从工具名前缀推出全部组名。 */
    fun groupsOf(context: Context): Set<String> =
        BuiltInToolSet.all(context).map { it.name.substringBefore('.') }.toSet()

    /**
     * 精简版 schema 占位。
     *
     * 给 `additionalProperties = true` 而不是空 object：这样即使 AI 硬着头皮
     * 不展开就调用，也能带上参数（不会被本地/远端 schema 校验挡掉）。
     */
    fun placeholderSchema(): JSONObject = JSONObject()
        .put("type", "object")
        .put("additionalProperties", true)

    /**
     * 把完整 schema 压成一行「参数摘要」，写进 description。
     *
     * 这一步是**省掉往返的关键**：多数工具的参数就两三个，摘要足以让 AI 直接调用，
     * 不必先查参数。只有参数复杂时才需要展开。
     */
    fun summarizeParams(schema: JSONObject): String {
        val props = schema.optJSONObject("properties") ?: return ""
        val names = props.keys().asSequence().toList()
        if (names.isEmpty()) return "无参数"
        val required = schema.optJSONArray("required")
            ?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() }
            ?: emptySet()
        return names.joinToString(", ") { name ->
            if (name in required) "$name*" else name
        }
    }
}

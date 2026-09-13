package com.mcp.toolbox.feature.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.CodeLanguage
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonSize
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixCodeText
import com.mcp.toolbox.core.design.component.MiuixDialog
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixBottomSheet
import com.mcp.toolbox.core.design.component.MiuixSegmentedButton
import com.mcp.toolbox.core.design.component.MiuixSlider
import com.mcp.toolbox.core.design.component.MiuixSuperSwitch
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 表单初值：优先用重放时传入的入参，其次用 schema 默认值。 */
private fun initialFormValues(fields: List<SchemaField>, argumentsJson: String?): Map<String, String> {
    val base = SchemaForm.initialValues(fields)
    if (argumentsJson.isNullOrBlank()) return base
    val obj = runCatching { JSONObject(argumentsJson) }.getOrNull() ?: return base
    return base.mapValues { (key, fallback) ->
        if (obj.has(key)) {
            val value = obj.opt(key)
            when (value) {
                null, JSONObject.NULL -> fallback
                is String -> value
                else -> value.toString()
            }
        } else {
            fallback
        }
    }
}

/** 工具调用面板：Schema 表单 + 危险确认 + 真实调用 + 结果与产物路径。 */
@Composable
internal fun ToolInvokeSheet(
    server: McpServerConfig,
    tool: McpToolInfo,
    initialArguments: String? = null,
    onDismiss: () -> Unit,
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val fields = remember(tool.inputSchema) { SchemaForm.parse(tool.inputSchema) }
    var values by remember(tool.name, initialArguments) { mutableStateOf(initialFormValues(fields, initialArguments)) }
    var errors by remember { mutableStateOf(emptyMap<String, String>()) }
    var confirmOpen by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var record by remember { mutableStateOf<McpCallRecord?>(null) }

    fun run() {
        val validation = SchemaForm.validate(fields, values)
        errors = validation
        if (validation.isNotEmpty()) {
            onToast("表单校验未通过：${validation.entries.first().let { "${it.key} ${it.value}" }}")
            return
        }
        running = true
        scope.launch {
            val result = McpClient.callTool(context, server, tool, SchemaForm.buildArguments(fields, values))
            running = false
            record = result
            onToast(
                when {
                    result.ok -> "调用成功 · ${result.elapsedMs}ms"
                    result.isError -> "工具返回错误 · ${result.elapsedMs}ms"
                    else -> "调用失败：${result.error ?: "未知错误"}"
                },
            )
        }
    }

    MiuixBottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxHeight(0.85f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageHorizontal)
                .navigationBarsPadding(),
        ) {
            MiuixText(text = tool.displayName, style = MiuixTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiuixTag(text = server.name.take(16))
                Spacer(Modifier.width(spacing.sm))
                MiuixTag(text = server.transport.label)
                if (tool.isDangerous) {
                    Spacer(Modifier.width(spacing.sm))
                    MiuixTag(text = "危险操作", color = colors.error)
                }
            }
            MiuixText(text = tool.name, style = MiuixTheme.typography.labelSmall, color = colors.primary)
            if (tool.description.isNotBlank()) {
                Spacer(Modifier.height(spacing.xs))
                MiuixText(
                    text = tool.description,
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(spacing.md))
            MiuixDivider()
            Spacer(Modifier.height(spacing.md))
            SchemaFields(
                fields = fields,
                values = values,
                errors = errors,
                onChange = { name, value -> values = values + (name to value) },
            )
            Spacer(Modifier.height(spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                MiuixButton(
                    text = "调用工具",
                    onClick = { if (tool.isDangerous) confirmOpen = true else run() },
                    loading = running,
                    leadingIcon = Icons.Outlined.PlayArrow,
                )
                MiuixButton(
                    text = "重置表单",
                    onClick = {
                        values = initialFormValues(fields, initialArguments)
                        errors = emptyMap()
                    },
                    variant = MiuixButtonVariant.OUTLINED,
                    leadingIcon = Icons.Outlined.Refresh,
                )
            }
            record?.let { result ->
                Spacer(Modifier.height(spacing.md))
                MiuixDivider()
                Spacer(Modifier.height(spacing.md))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiuixTag(
                        text = if (result.ok) "成功" else if (result.isError) "工具报错" else "失败",
                        color = if (result.ok) colors.success else colors.error,
                    )
                    Spacer(Modifier.width(spacing.sm))
                    MiuixTag(text = "${result.elapsedMs}ms")
                    Spacer(Modifier.width(spacing.sm))
                    MiuixTag(text = "${result.bytes}B")
                }
                Spacer(Modifier.height(spacing.sm))
                MiuixCodeText(
                    code = result.resultText,
                    language = CodeLanguage.JSON,
                    showLineNumbers = false,
                )
                result.artifactDir?.let { dir ->
                    Spacer(Modifier.height(spacing.sm))
                    MiuixText(
                        text = "产物目录：$dir",
                        style = MiuixTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(MiuixTheme.dimens.spacing.xxl))
        }
    }

    if (confirmOpen) {
        MiuixDialog(
            visible = true,
            onDismiss = { confirmOpen = false },
            title = "确认调用危险工具",
            message = "${tool.displayName}（${tool.name}）可能修改本机数据或执行高权限操作，" +
                "请确认入参无误。本次调用会计入日志" +
                if (ArtifactStore.config.value.enabled) "并落盘到输出文件夹。" else "（输出文件夹未开启）。",
            confirmText = "继续调用",
            destructive = true,
            onConfirm = {
                confirmOpen = false
                run()
            },
        )
    }
}

/** 服务器添加 / 编辑面板。 */
@Composable
internal fun ServerEditSheet(
    initial: McpServerConfig?,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
) {
    val spacing = MiuixTheme.dimens.spacing
    val colors = MiuixTheme.colors
    val isNew = initial == null
    var name by remember { mutableStateOf(initial?.name ?: "本地 MCP Server") }
    var transport by remember { mutableStateOf(initial?.transport ?: McpTransport.HTTP) }
    var url by remember { mutableStateOf(initial?.url ?: "http://127.0.0.1:3000/mcp") }
    var sseUrl by remember { mutableStateOf(initial?.url ?: "http://127.0.0.1:3000/sse") }
    var token by remember { mutableStateOf(initial?.token.orEmpty()) }
    var timeout by remember { mutableStateOf((initial?.timeoutMs ?: 8000).toString()) }
    var headers by remember {
        mutableStateOf(initial?.headers?.entries?.joinToString("\n") { "${it.key}: ${it.value}" }.orEmpty())
    }
    var autoConnect by remember { mutableStateOf(initial?.autoConnect ?: false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    MiuixBottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxHeight(0.85f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageHorizontal)
                .navigationBarsPadding(),
        ) {
            MiuixText(
                text = if (isNew) "添加 MCP Server" else "编辑 ${initial?.name.orEmpty()}",
                style = MiuixTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(spacing.sm))
            MiuixText(text = "传输方式", style = MiuixTheme.typography.labelLarge)
            MiuixSegmentedButton(
                options = McpTransport.entries.toList(),
                selected = transport,
                onSelect = { next -> if (next == McpTransport.SSE && sseUrl == url) sseUrl = url.replace("/mcp", "/sse"); transport = next },
                label = { it.label },
            )
            Spacer(Modifier.height(spacing.sm))
            MiuixTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "显示名称",
            )
            Spacer(Modifier.height(spacing.sm))
            if (transport == McpTransport.STDIO) {
                MiuixText(
                    text = "stdio 需要 Shizuku / root / Termux 提供可执行的 MCP server 进程；当前仅登记命令，连接时会给出明确降级提示。",
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.warning,
                )
            } else {
                MiuixTextField(
                    value = if (transport == McpTransport.SSE) sseUrl else url,
                    onValueChange = { if (transport == McpTransport.SSE) sseUrl = it else url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = if (transport == McpTransport.SSE) "http://host:port/sse" else "http://host:port/mcp",
                )
            }
            val currentUrl = if (transport == McpTransport.SSE) sseUrl else url
            if (transport != McpTransport.STDIO && currentUrl.startsWith("http://")) {
                MiuixText(
                    text = "明文 HTTP：仅建议本机或可信局域网内的 MCP Server 使用。",
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.warning,
                )
            }
            Spacer(Modifier.height(spacing.sm))
            MiuixTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "Bearer token（可留空）",
            )
            Spacer(Modifier.height(spacing.sm))
            MiuixTextField(
                value = timeout,
                onValueChange = { timeout = it.filter { ch -> ch.isDigit() } },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "超时毫秒（默认 8000）",
            )
            Spacer(Modifier.height(spacing.sm))
            MiuixTextField(
                value = headers,
                onValueChange = { headers = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "额外请求头，每行 Key: Value",
                singleLine = false,
                minLines = 2,
            )
            MiuixSuperSwitch(
                title = "保存后立即连接",
                checked = autoConnect,
                onCheckedChange = { autoConnect = it },
            )
            errorText?.let {
                Spacer(Modifier.height(spacing.sm))
                MiuixText(text = it, style = MiuixTheme.typography.bodySmall, color = colors.error)
            }
            Spacer(Modifier.height(spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                MiuixButton(
                    text = if (isNew) "添加" else "保存",
                    onClick = {
                        val target = if (transport == McpTransport.SSE) sseUrl.trim() else url.trim()
                        if (transport != McpTransport.STDIO && !target.startsWith("http")) {
                            errorText = "地址必须以 http:// 或 https:// 开头"
                            return@MiuixButton
                        }
                        val parsedHeaders = headers.lines()
                            .mapNotNull { line ->
                                val idx = line.indexOf(':')
                                if (idx <= 0) null else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                            }
                            .toMap()
                        val base = initial ?: McpServerConfig(
                            id = "srv-" + System.currentTimeMillis().toString().takeLast(6),
                            name = name,
                        )
                        onSave(
                            base.copy(
                                name = name.ifBlank { "未命名 Server" },
                                transport = transport,
                                url = target,
                                command = if (transport == McpTransport.STDIO) target else "",
                                token = token.trim(),
                                timeoutMs = timeout.toIntOrNull()?.coerceIn(1000, 120000) ?: 8000,
                                headers = parsedHeaders,
                                autoConnect = autoConnect,
                            ),
                        )
                    },
                )
                MiuixButton(text = "取消", onClick = onDismiss, variant = MiuixButtonVariant.OUTLINED)
            }
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

/** 调用日志详情：入参、结果、产物路径，支持重放与导出。 */
@Composable
internal fun LogDetailSheet(
    record: McpCallRecord,
    onDismiss: () -> Unit,
    onReplay: (McpCallRecord) -> Unit,
    onExport: (McpCallRecord) -> Unit,
) {
    val spacing = MiuixTheme.dimens.spacing
    val colors = MiuixTheme.colors
    val context = LocalContext.current
    var showArgs by remember { mutableStateOf(true) }

    MiuixBottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxHeight(0.85f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageHorizontal)
                .navigationBarsPadding(),
        ) {
            MiuixText(text = record.toolName, style = MiuixTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiuixTag(
                    text = if (record.ok) "成功" else if (record.isError) "工具报错" else "失败",
                    color = if (record.ok) colors.success else colors.error,
                )
                Spacer(Modifier.width(spacing.sm))
                MiuixTag(text = "${record.elapsedMs}ms")
                Spacer(Modifier.width(spacing.sm))
                MiuixTag(text = "${record.bytes}B")
            }
            MiuixText(
                text = "${record.serverName} · ${formatAt(record.startedAt)}",
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            record.artifactDir?.let {
                Spacer(Modifier.height(spacing.xs))
                MiuixText(
                    text = "产物：$it（${record.artifactFiles} 个文件）",
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.primary,
                )
            }
            Spacer(Modifier.height(spacing.md))
            MiuixSegmentedButton(
                options = listOf("入参", "结果"),
                selected = if (showArgs) "入参" else "结果",
                onSelect = { showArgs = it == "入参" },
                label = { it },
            )
            Spacer(Modifier.height(spacing.sm))
            MiuixCodeText(
                code = if (showArgs) record.argumentsJson else record.resultText,
                language = CodeLanguage.JSON,
                showLineNumbers = false,
            )
            Spacer(Modifier.height(spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                MiuixButton(
                    text = "重新执行",
                    onClick = { onReplay(record) },
                    size = MiuixButtonSize.SMALL,
                    leadingIcon = Icons.Outlined.Refresh,
                )
                MiuixButton(
                    text = "导出 JSON",
                    onClick = { onExport(record) },
                    size = MiuixButtonSize.SMALL,
                    variant = MiuixButtonVariant.OUTLINED,
                    leadingIcon = Icons.Outlined.ContentCopy,
                )
                MiuixButton(
                    text = "复制结果",
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("mcp-result", record.resultText),
                        )
                    },
                    size = MiuixButtonSize.SMALL,
                    variant = MiuixButtonVariant.TEXT,
                )
            }
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

/** 输出文件夹（5.9）设置面板：滚动区 + 常驻底部操作栏，按钮始终可点。 */
@Composable
internal fun OutputFolderSheet(
    config: ArtifactStore.Config,
    rootPath: String,
    refreshTick: Int,
    onDismiss: () -> Unit,
    onSave: (ArtifactStore.Config) -> Unit,
    onOpenArtifacts: () -> Unit,
    onClear: (Boolean) -> Unit,
    onPickRoot: () -> Unit,
    onUnbindRoot: () -> Unit,
    onSyncNow: () -> Unit,
) {
    val spacing = MiuixTheme.dimens.spacing
    val colors = MiuixTheme.colors
    var draft by remember { mutableStateOf(config) }
    var template by remember { mutableStateOf(config.template) }
    val context = LocalContext.current

    MiuixBottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageHorizontal)
                .navigationBarsPadding(),
        ) {
            Column(Modifier.fillMaxHeight(0.6f).verticalScroll(rememberScrollState())) {
                MiuixText(text = "MCP 输出文件夹", style = MiuixTheme.typography.titleMedium)
                MiuixText(
                    text = "开启后每次 tools/call 生成一个会话目录：manifest.json、inputs 入参、" +
                        "outputs 结果、logs 报文日志，可浏览、可溯源、可一键清理。",
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.sm))
                MiuixSuperSwitch(
                    title = "启用输出文件夹",
                    subtitle = if (draft.enabled) "已开启：调用结果自动落盘" else "默认关闭",
                    checked = draft.enabled,
                    onCheckedChange = { draft = draft.copy(enabled = it) },
                )
                MiuixText(
                    text = "根目录：$rootPath",
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.sm))
                val publicPath by ArtifactStore.publicRoot.collectAsState()
                MiuixText(text = "直接写入公共目录（推荐）", style = MiuixTheme.typography.labelLarge)
                MiuixText(
                    text = when {
                        publicPath != null ->
                            "已启用：产物直接写入 $publicPath，文件管理器可见，不需要系统选择器"
                        ArtifactStore.canUsePublicRoot() ->
                            "已获「所有文件访问权限」，可一键直写公共目录。" +
                                "系统选择器会以隐私为由拒绝根目录下的文件夹，跟这个权限是两回事。"
                        else -> "需要「所有文件访问权限」：先到系统设置里授予，再回来点下面的按钮"
                    },
                    style = MiuixTheme.typography.labelSmall,
                    color = if (publicPath != null) colors.primary else colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.xs))
                MiuixButton(
                    text = if (publicPath == null) "直写公共目录" else "取消直写，改回沙箱",
                    onClick = {
                        if (publicPath == null) {
                            ArtifactStore.setPublicRoot(context, ArtifactStore.DEFAULT_PUBLIC_ROOT)
                        } else {
                            ArtifactStore.setPublicRoot(context, null)
                        }
                    },
                    size = MiuixButtonSize.SMALL,
                )
                Spacer(Modifier.height(spacing.sm))
                MiuixText(text = "外置目录（系统文件选择器）", style = MiuixTheme.typography.labelLarge)
                val extLabel = remember(refreshTick) { ArtifactStore.externalRootLabel(context) }
                val mirror = ArtifactStore.mirrorState.collectAsState().value
                MiuixText(
                    text = if (extLabel.isBlank()) {
                        "未绑定：产物只在应用沙箱内，文件管理器看不到"
                    } else {
                        "已绑定：$extLabel"
                    },
                    style = MiuixTheme.typography.labelSmall,
                    color = if (extLabel.isBlank()) colors.onSurfaceVariant else colors.primary,
                )
                MiuixText(
                    text = mirror?.let { report ->
                        val tail = if (report.error.isBlank()) "" else " · 失败：${report.error}"
                        "上次同步：${formatAt(report.at)} · 会话 ${report.sessions} · 新写入 ${report.files} 个文件（" +
                            ToolSupport.human(report.bytes) + "）$tail"
                    } ?: "尚未同步",
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.xs))
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    MiuixButton(
                        text = if (extLabel.isBlank()) "选择目录" else "更换目录",
                        onClick = onPickRoot,
                        size = MiuixButtonSize.SMALL,
                    )
                    MiuixButton(
                        text = "立即同步",
                        onClick = onSyncNow,
                        size = MiuixButtonSize.SMALL,
                        variant = MiuixButtonVariant.OUTLINED,
                        enabled = extLabel.isNotBlank(),
                    )
                    if (extLabel.isNotBlank()) {
                        MiuixButton(
                            text = "解除绑定",
                            onClick = onUnbindRoot,
                            size = MiuixButtonSize.SMALL,
                            variant = MiuixButtonVariant.TEXT,
                        )
                    }
                }
                Spacer(Modifier.height(spacing.xs))
                MiuixSuperSwitch(
                    title = "自动同步",
                    subtitle = "绑定后每次调用结束就镜像过去（已同步过的文件不重复写入）",
                    checked = draft.externalSync,
                    onCheckedChange = { draft = draft.copy(externalSync = it) },
                )
                Spacer(Modifier.height(spacing.sm))
                MiuixText(text = "子目录策略", style = MiuixTheme.typography.labelLarge)
                MiuixSegmentedButton(
                    options = ArtifactStore.Layout.entries.toList(),
                    selected = draft.layout,
                    onSelect = { draft = draft.copy(layout = it) },
                    label = { it.label },
                )
                Spacer(Modifier.height(spacing.sm))
                MiuixTextField(
                    value = template,
                    onValueChange = { template = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "命名模板：{tool}_{timestamp}_{seq}",
                    supportingText = "可用占位符：{tool} {timestamp} {seq} {server}",
                )
                Spacer(Modifier.height(spacing.sm))
                MiuixText(text = "配额上限：${draft.quotaMb} MB", style = MiuixTheme.typography.labelLarge)
                MiuixSlider(
                    value = draft.quotaMb.toFloat(),
                    onValueChange = { draft = draft.copy(quotaMb = it.toInt()) },
                    valueRange = 64f..4096f,
                    steps = 62,
                    showBubble = true,
                    valueLabel = { "${it.toInt()} MB" },
                )
                MiuixText(
                    text = "保留天数：${draft.retentionDays} 天（超期按最旧会话淘汰）",
                    style = MiuixTheme.typography.labelLarge,
                )
                MiuixSlider(
                    value = draft.retentionDays.toFloat(),
                    onValueChange = { draft = draft.copy(retentionDays = it.toInt()) },
                    valueRange = 1f..90f,
                    steps = 88,
                    showBubble = true,
                    valueLabel = { "${it.toInt()} 天" },
                )
                MiuixSuperSwitch(
                    title = "敏感字段脱敏",
                    subtitle = "token / password / secret 落盘前替换为 ***",
                    checked = draft.maskSecrets,
                    onCheckedChange = { draft = draft.copy(maskSecrets = it) },
                )
                MiuixSuperSwitch(
                    title = "记录工具返回内容",
                    subtitle = "关闭后只记录入参与日志，不落盘结果正文",
                    checked = draft.recordResults,
                    onCheckedChange = { draft = draft.copy(recordResults = it) },
                )
            }
            Spacer(Modifier.height(spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                MiuixButton(
                    text = "保存",
                    onClick = { onSave(draft.copy(template = template.ifBlank { "{tool}_{timestamp}_{seq}" })) },
                )
                MiuixButton(
                    text = "打开产物页",
                    onClick = onOpenArtifacts,
                    variant = MiuixButtonVariant.OUTLINED,
                    leadingIcon = Icons.Outlined.Folder,
                )
            }
            Spacer(Modifier.height(spacing.sm))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                MiuixButton(text = "清理失败会话", onClick = { onClear(true) }, variant = MiuixButtonVariant.TEXT)
                MiuixButton(text = "清空全部产物", onClick = { onClear(false) }, variant = MiuixButtonVariant.TEXT)
            }
            Spacer(Modifier.height(spacing.sm))
        }
    }
}

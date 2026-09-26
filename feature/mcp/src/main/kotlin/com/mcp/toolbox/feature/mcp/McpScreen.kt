package com.mcp.toolbox.feature.mcp

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixEmptyState
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixSearchField
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSegmentedButton
import com.mcp.toolbox.core.design.component.MiuixSuperSwitch
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal enum class McpTab(val label: String) {
    SERVERS("服务器"),
    TOOLS("工具"),
    LOGS("日志"),
}

/**
 * MCP 页面（提示词 4 / 5.8）：服务器管理、Schema 驱动工具调用、调用日志，
 * 以及内置 Server 的启停与实时状态。
 */
@Composable
fun McpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onToast: (String) -> Unit = {},
    onOpenArtifacts: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing

    val servers by McpRegistry.servers.collectAsState()
    val states by McpRegistry.states.collectAsState()
    val toolsMap by McpRegistry.tools.collectAsState()
    val calls by McpRegistry.calls.collectAsState()
    val serverRunning by BuiltInMcpServer.running.collectAsState()
    val serverConfig by BuiltInMcpServer.config.collectAsState()
    val serverLogs by BuiltInMcpServer.logs.collectAsState()
    val requestCount by BuiltInMcpServer.requestCount.collectAsState()
    val artifactConfig by ArtifactStore.config.collectAsState()

    var tab by remember { mutableStateOf(McpTab.SERVERS) }
    var menuOpen by remember { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var invoking by remember { mutableStateOf<Pair<McpServerConfig, McpToolInfo>?>(null) }
    var outputSheetOpen by remember { mutableStateOf(false) }
    var artifactRefresh by remember { mutableStateOf(0) }
    val pickArtifactRoot = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            ArtifactStore.setExternalRoot(context, uri)
            artifactRefresh++
            scope.launch {
                val report = runCatching { ArtifactStore.mirror(context, null) }.getOrNull()
                artifactRefresh++
                if (report == null) {
                    onToast("已绑定外置目录，但暂未能访问该目录")
                } else if (report.error.isBlank()) {
                    onToast("已绑定外置目录：将 ${report.sessions} 个会话中的 ${report.files} 个新文件写入外置目录")
                } else {
                    onToast("已绑定外置目录，但同步失败：${report.error}")
                }
            }
        }
    }
    var logQuery by remember { mutableStateOf("") }
    var toolQuery by remember { mutableStateOf("") }
    var logDetail by remember { mutableStateOf<McpCallRecord?>(null) }
    var replayArgs by remember { mutableStateOf<String?>(null) }
    var busyServer by remember { mutableStateOf<String?>(null) }
    var selectedServerId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        BuiltInMcpServer.load(context)
        McpRegistry.load(context)
        ArtifactStore.load(context)
    }

    fun connect(config: McpServerConfig) {
        busyServer = config.id
        scope.launch {
            val state = McpClient.connect(context, config)
            busyServer = null
            selectedServerId = config.id
            onToast(
                if (state.state == McpConnectionState.READY) {
                    "已连接 ${state.serverName} · ${state.toolCount} 个工具 · ${state.latencyMs}ms"
                } else {
                    "连接失败：${state.message}"
                },
            )
        }
    }

    /**
     * 启动内置 Server 后自动连上本机客户端。
     * 服务是异步开始监听的，立刻连必然失败，所以这里轮询重试；
     * 已经连上就不再打扰（例如进程重启后服务本来就在跑）。
     */
    LaunchedEffect(serverRunning) {
        if (!serverRunning) return@LaunchedEffect
        val client = McpRegistry.servers.value.firstOrNull { it.builtIn } ?: return@LaunchedEffect
        if (McpRegistry.state(client.id).state == McpConnectionState.READY) return@LaunchedEffect
        repeat(24) { attempt ->
            delay(if (attempt == 0) 300L else 250L)
            val state = runCatching { McpClient.connect(context, client) }.getOrNull()
            if (state?.state == McpConnectionState.READY) {
                selectedServerId = client.id
                onToast("已自动连接内置 Server · ${state.toolCount} 个工具")
                return@LaunchedEffect
            }
        }
        onToast("内置 Server 已启动，但自动连接超时，请手动点「连接」")
    }

    val exportLog: () -> Unit = {
        scope.launch {
            val dir = java.io.File(ArtifactStore.root(context), "logs").apply { mkdirs() }
            val file = java.io.File(dir, "mcp-call-log-${System.currentTimeMillis()}.json")
            val array = org.json.JSONArray()
            calls.forEach { array.put(it.toJson()) }
            runCatching { file.writeText(array.toString(2)) }
                .onSuccess { onToast("已导出 ${calls.size} 条日志 → ${file.absolutePath}") }
                .onFailure { onToast("导出失败：${it.message}") }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            MiuixTopAppBar(
                title = "MCP",
                navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                onNavigationClick = onBack,
                actions = {
                    Box {
                        MiuixIconButton(Icons.Outlined.MoreHoriz, "更多", onClick = { menuOpen = true })
                        MiuixOverflowMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                            MiuixMenuItem(
                                text = if (serverRunning) "停止内置 Server" else "启动内置 Server",
                                onClick = {
                                    menuOpen = false
                                    if (serverRunning) {
                                        McpServerService.stop(context)
                                        onToast("内置 Server 已停止")
                                    } else {
                                        McpServerService.start(context)
                                        onToast("内置 Server 启动中：${BuiltInMcpServer.httpEndpoint}")
                                    }
                                },
                                icon = if (serverRunning) Icons.Outlined.Stop else Icons.Outlined.PlayArrow,
                            )
                            MiuixMenuItem(
                                text = "连接全部已启用 Server",
                                onClick = {
                                    menuOpen = false
                                    servers.filter { !it.builtIn || serverRunning }.forEach { connect(it) }
                                },
                                icon = Icons.Outlined.Refresh,
                            )
                            MiuixMenuItem(
                                text = "输出文件夹设置",
                                onClick = { menuOpen = false; outputSheetOpen = true },
                                icon = Icons.Outlined.Folder,
                            )
                            MiuixMenuItem(
                                text = "打开 MCP 产物页",
                                onClick = { menuOpen = false; onOpenArtifacts() },
                                icon = Icons.Outlined.Folder,
                            )
                            MiuixMenuItem(
                                text = "导出调用日志",
                                onClick = { menuOpen = false; exportLog() },
                                icon = Icons.Outlined.ContentCopy,
                            )
                            MiuixMenuItem(
                                text = "清空调用日志",
                                onClick = {
                                    menuOpen = false
                                    McpRegistry.clearLog(context)
                                    onToast("调用日志已清空")
                                },
                                icon = Icons.Outlined.Delete,
                                danger = true,
                            )
                        }
                    }
                },
            )

            Column(Modifier.padding(horizontal = spacing.pageHorizontal)) {
                Spacer(Modifier.height(spacing.sm))
                MiuixSegmentedButton(
                    options = McpTab.entries.toList(),
                    selected = tab,
                    onSelect = { tab = it },
                    label = { it.label },
                )
            }
            Spacer(Modifier.height(spacing.sm))
            when (tab) {
                McpTab.SERVERS -> ServersTab(
                    servers = servers,
                    states = states,
                    toolsMap = toolsMap,
                    serverRunning = serverRunning,
                    serverConfig = serverConfig,
                    requestCount = requestCount,
                    serverLogs = serverLogs,
                    busyServer = busyServer,
                    onMessage = onToast,
                    onToggleServer = { enabled ->
                        if (enabled) {
                            McpServerService.start(context)
                            onToast("内置 Server 启动中：${BuiltInMcpServer.httpEndpoint}")
                        } else {
                            McpServerService.stop(context)
                            onToast("内置 Server 已停止")
                        }
                    },
                    onUpdateServerConfig = { next ->
                        BuiltInMcpServer.saveConfig(context, next)
                        onToast("内置 Server 配置已保存")
                    },
                    onConnect = { connect(it) },
                    onEdit = { editing = it },
                    onDelete = { config ->
                        McpRegistry.remove(context, config.id)
                        onToast("已删除 ${config.name}")
                    },
                    onAdd = { addOpen = true },
                    onOpenStatusPage = {
                        val uri = Uri.parse(if (serverConfig.localOnly) BuiltInMcpServer.httpEndpoint.replace("/mcp", "/") else "http://127.0.0.1:${serverConfig.port}/")
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }.onFailure { onToast("没有可用的浏览器处理该地址") }
                    },
                    onCopyToken = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("mcp-token", serverConfig.token),
                        )
                        onToast("token 已复制到剪贴板（${serverConfig.token.length} 位）")
                    },
                    onCopyText = { text ->
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("mcp-endpoint", text))
                        onToast("已复制：$text")
                    },
                    onOpenArtifacts = onOpenArtifacts,
                    artifactEnabled = artifactConfig.enabled,
                )
                McpTab.TOOLS -> ToolsTab(
                    servers = servers,
                    toolsMap = toolsMap,
                    states = states,
                    query = toolQuery,
                    onQueryChange = { toolQuery = it },
                    selectedServerId = selectedServerId,
                    onSelectServer = { selectedServerId = it },
                    onConnect = { connect(it) },
                    onInvoke = { server, tool -> invoking = server to tool },
                )
                McpTab.LOGS -> LogsTab(
                    calls = calls,
                    query = logQuery,
                    onQueryChange = { logQuery = it },
                    onSelect = { logDetail = it },
                )
            }
        }

        if (addOpen) {
            ServerEditSheet(
                initial = null,
                onDismiss = { addOpen = false },
                onSave = { config ->
                    addOpen = false
                    McpRegistry.upsert(context, config)
                    onToast("已添加 ${config.name}")
                },
            )
        }

        editing?.let { current ->
            ServerEditSheet(
                initial = current,
                onDismiss = { editing = null },
                onSave = { config ->
                    editing = null
                    McpRegistry.upsert(context, config)
                    onToast("已保存 ${config.name}")
                    if (config.autoConnect) connect(config)
                },
            )
        }

        invoking?.let { (server, tool) ->
            ToolInvokeSheet(
                server = server,
                tool = tool,
                initialArguments = replayArgs,
                onDismiss = {
                    invoking = null
                    replayArgs = null
                },
                onToast = onToast,
            )
        }

        logDetail?.let { record ->
            LogDetailSheet(
                record = record,
                onDismiss = { logDetail = null },
                onReplay = { replay ->
                    val server = servers.firstOrNull { it.id == replay.serverId }
                    val tool = server?.let { s -> McpRegistry.toolList(s.id).firstOrNull { it.name == replay.toolName } }
                    if (server == null || tool == null) {
                        onToast("原始 Server / 工具已不可用，无法重放")
                    } else {
                        replayArgs = replay.argumentsJson
                        invoking = server to tool
                    }
                    logDetail = null
                },
                onExport = { replay ->
                    scope.launch {
                        val dir = java.io.File(ArtifactStore.root(context), "logs").apply { mkdirs() }
                        val file = java.io.File(dir, "call-${replay.id}.json")
                        runCatching { file.writeText(replay.toJson().toString(2)) }
                            .onSuccess { onToast("已导出 → ${file.absolutePath}") }
                            .onFailure { onToast("导出失败：${it.message}") }
                    }
                },
            )
        }

        if (outputSheetOpen) {
            OutputFolderSheet(
                config = artifactConfig,
                rootPath = ArtifactStore.root(context).absolutePath,
                refreshTick = artifactRefresh,
                onPickRoot = { pickArtifactRoot.launch(null) },
                onUnbindRoot = {
                    ArtifactStore.setExternalRoot(context, null)
                    artifactRefresh++
                    onToast("已解除外置目录绑定（外置目录里已同步的文件保留）")
                },
                onSyncNow = {
                    scope.launch {
                        val report = runCatching { ArtifactStore.mirror(context, null) }.getOrNull()
                        artifactRefresh++
                        if (report == null) {
                            onToast("同步失败：外置目录不可访问")
                        } else if (report.error.isBlank()) {
                            onToast("同步完成：${report.sessions} 个会话，新写入 ${report.files} 个文件")
                        } else {
                            onToast("同步部分失败：${report.error}")
                        }
                    }
                },
                onDismiss = { outputSheetOpen = false },
                onSave = { next ->
                    ArtifactStore.save(context, next)
                    onToast(if (next.enabled) "输出文件夹已开启" else "输出文件夹已关闭")
                },
                onOpenArtifacts = { outputSheetOpen = false; onOpenArtifacts() },
                onClear = { onlyFailed ->
                    ArtifactStore.clear(context, onlyFailed)
                    onToast(if (onlyFailed) "已清理失败会话" else "产物目录已清空")
                },
            )
        }
    }
}

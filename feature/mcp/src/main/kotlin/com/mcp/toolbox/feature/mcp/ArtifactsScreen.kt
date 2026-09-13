package com.mcp.toolbox.feature.mcp

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
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mcp.toolbox.core.design.component.MiuixBottomSheet
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonSize
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixCard
import com.mcp.toolbox.core.design.component.MiuixCodeText
import com.mcp.toolbox.core.design.component.MiuixDialog
import com.mcp.toolbox.core.design.component.MiuixEmptyState
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixSearchField
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSegmentedButton
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch
import java.io.File

private enum class ArtifactFilter(val label: String) {
    ALL("全部"),
    OK("成功"),
    FAILED("失败"),
    WITH_FILES("含文件"),
}

/**
 * MCP 产物页（提示词 5.9）：会话卡片列表 + 详情（manifest / 入参 / 结果 / 日志）。
 */
@Composable
fun ArtifactsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onToast: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val config by ArtifactStore.config.collectAsState()
    val publicRootState by ArtifactStore.publicRoot.collectAsState()
    val usingPublic = publicRootState != null

    var sessions by remember { mutableStateOf<List<ArtifactStore.Session>>(emptyList()) }
    var filter by remember { mutableStateOf(ArtifactFilter.ALL) }
    var query by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<ArtifactStore.Session?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    fun reload() {
        sessions = ArtifactStore.list(context)
    }

    LaunchedEffect(Unit) {
        ArtifactStore.load(context)
        reload()
    }

    val filtered = sessions.filter {
        val matchFilter = when (filter) {
            ArtifactFilter.ALL -> true
            ArtifactFilter.OK -> it.ok
            ArtifactFilter.FAILED -> !it.ok
            ArtifactFilter.WITH_FILES -> it.files > 2
        }
        val matchQuery = query.isBlank() ||
            it.tool.contains(query, ignoreCase = true) ||
            it.server.contains(query, ignoreCase = true) ||
            it.id.contains(query, ignoreCase = true)
        matchFilter && matchQuery
    }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            MiuixTopAppBar(
                title = "MCP 产物",
                navigationIcon = Icons.Outlined.ArrowBack,
                onNavigationClick = onBack,
                actions = {
                    Box {
                        MiuixIconButton(Icons.Outlined.MoreHoriz, "更多", onClick = { menuOpen = true })
                        MiuixOverflowMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                            MiuixMenuItem(
                                text = "刷新列表",
                                onClick = { menuOpen = false; reload() },
                                icon = Icons.Outlined.Refresh,
                            )
                            MiuixMenuItem(
                                text = "执行配额与保留策略",
                                onClick = {
                                    menuOpen = false
                                    ArtifactStore.enforce(context)
                                    reload()
                                    onToast("已按配额 / 保留天数清理")
                                },
                                icon = Icons.Outlined.FolderOpen,
                            )
                            MiuixMenuItem(
                                text = "清空全部产物",
                                onClick = { menuOpen = false; confirmClear = true },
                                icon = Icons.Outlined.Delete,
                                danger = true,
                            )
                        }
                    }
                },
            )
            Column(Modifier.padding(horizontal = spacing.pageHorizontal)) {
                MiuixSectionCard(
                    title = if (config.enabled) "输出文件夹已开启" else "输出文件夹未开启",
                    subtitle = "${ArtifactStore.root(context).absolutePath} · 已用 " +
                        ToolSupport.human(ArtifactStore.totalBytes(context)) + " · 配额 ${config.quotaMb} MB",
                ) {
                    Column(Modifier.padding(spacing.md)) {
                        MiuixText(
                            text = if (config.enabled) {
                                "策略：${config.layout.label} · 模板 ${config.template} · 保留 ${config.retentionDays} 天" +
                                    if (config.maskSecrets) " · 敏感字段脱敏" else ""
                            } else {
                                "开启后每次 tools/call 的入参、结果与报文会落盘；当前调用只保留在内存日志中。"
                            },
                            style = MiuixTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(spacing.sm))
                        MiuixSegmentedButton(
                            options = listOf("应用私有", "公共目录"),
                            selected = if (usingPublic) "公共目录" else "应用私有",
                            onSelect = { picked ->
                                if (picked == "公共目录") {
                                    val ok = ArtifactStore.setPublicRoot(context, ArtifactStore.DEFAULT_PUBLIC_ROOT)
                                    onToast(
                                        if (ok) "产物将直接写入 ${ArtifactStore.DEFAULT_PUBLIC_ROOT}"
                                        else "写入被拒：请先在系统设置里授予「所有文件访问权限」",
                                    )
                                } else {
                                    ArtifactStore.setPublicRoot(context, null)
                                    onToast("产物改为写入应用私有目录")
                                }
                                reload()
                            },
                            label = { it },
                        )
                        Spacer(Modifier.height(spacing.sm))
                        MiuixText(
                            text = "公共目录直写不需要系统文件夹选择器——系统选择器会以隐私为由拒绝根目录下的文件夹，与存储权限无关。" +
                                "「所有文件访问权限」：" +
                                (if (ArtifactStore.canUsePublicRoot()) "已授予" else "未授予") +
                                " · 实际写入位置：" + ArtifactStore.root(context).absolutePath,
                            style = MiuixTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(spacing.sm))
                MiuixSegmentedButton(
                    options = ArtifactFilter.entries.toList(),
                    selected = filter,
                    onSelect = { filter = it },
                    label = { it.label },
                )
                Spacer(Modifier.height(spacing.sm))
                MiuixSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索工具 / Server / 会话名",
                )
                Spacer(Modifier.height(spacing.sm))
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MiuixEmptyState(
                        title = "还没有产物会话",
                        description = if (config.enabled) {
                            "在 MCP 页调用一次工具，会话目录会立刻出现在这里。"
                        } else {
                            "先到 MCP 页的「输出文件夹设置」里开启开关。"
                        },
                        icon = Icons.Outlined.GridView,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = spacing.pageHorizontal,
                        end = spacing.pageHorizontal,
                        bottom = spacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    items(filtered, key = { it.dir }) { session ->
                        MiuixCard(onClick = { detail = session }) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    MiuixTag(
                                        text = if (session.ok) "成功" else "失败",
                                        color = if (session.ok) colors.success else colors.error,
                                    )
                                    Spacer(Modifier.width(spacing.sm))
                                    MiuixText(
                                        text = session.tool,
                                        style = MiuixTheme.typography.titleSmall,
                                    )
                                    Spacer(Modifier.weight(1f))
                                    MiuixText(
                                        text = "${session.elapsedMs}ms",
                                        style = MiuixTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                                MiuixText(
                                    text = "${session.server} · ${formatAt(session.startedAt)} · " +
                                        "${session.files} 文件 · ${ToolSupport.human(session.bytes)}",
                                    style = MiuixTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                )
                                MiuixText(
                                    text = session.dir,
                                    style = MiuixTheme.typography.labelSmall,
                                    color = colors.primary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        detail?.let { session ->
            ArtifactDetailSheet(
                session = session,
                onDismiss = { detail = null },
                onDelete = {
                    ArtifactStore.delete(context, session.dir)
                    detail = null
                    reload()
                    onToast("已删除 ${session.id}")
                },
                onOpenInFiles = {
                    val uri = android.net.Uri.fromFile(File(session.dir))
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                        .setDataAndType(uri, "resource/folder")
                    runCatching { context.startActivity(intent) }
                        .onFailure { onToast("没有可打开文件夹的应用，路径：${session.dir}") }
                },
                onToast = onToast,
            )
        }

        if (confirmClear) {
            MiuixDialog(
                visible = true,
                onDismiss = { confirmClear = false },
                title = "清空全部产物？",
                message = "将删除 ${sessions.size} 个会话目录及其中的入参、结果与日志文件，此操作不可撤销。",
                confirmText = "清空",
                destructive = true,
                onConfirm = {
                    confirmClear = false
                    ArtifactStore.clear(context, false)
                    reload()
                    onToast("产物目录已清空")
                },
            )
        }
    }
}

/** 会话详情：manifest / 入参 / 结果 / 日志 四个分页 + 底部操作栏。 */
@Composable
private fun ArtifactDetailSheet(
    session: ArtifactStore.Session,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onOpenInFiles: () -> Unit,
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    var tab by remember { mutableStateOf("manifest") }
    val files = remember(session.dir) { ArtifactStore.read(context, session.dir) }

    val content = when (tab) {
        "入参" -> files["inputs/arguments.json"]
        "结果" -> files["outputs/result.json"] ?: files["outputs/result.md"]
        "日志" -> files["logs/request.json"]?.let { request ->
            request + "\n\n// response\n" + (files["logs/response.json"] ?: "")
        }
        else -> files["manifest.json"]
    }

    MiuixBottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageHorizontal)
                .navigationBarsPadding(),
        ) {
            MiuixText(text = session.tool, style = MiuixTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiuixTag(text = if (session.ok) "成功" else "失败", color = if (session.ok) colors.success else colors.error)
                Spacer(Modifier.width(spacing.sm))
                MiuixTag(text = "${session.elapsedMs}ms")
                Spacer(Modifier.width(spacing.sm))
                MiuixTag(text = "${session.files} 文件 / ${ToolSupport.human(session.bytes)}")
            }
            MiuixText(
                text = "${session.server} · ${formatAt(session.startedAt)}",
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            if (session.error.isNotBlank()) {
                MiuixText(text = session.error, style = MiuixTheme.typography.bodySmall, color = colors.error)
            }
            Spacer(Modifier.height(spacing.sm))
            MiuixSegmentedButton(
                options = listOf("manifest", "入参", "结果", "日志"),
                selected = tab,
                onSelect = { tab = it },
                label = { it },
            )
            Spacer(Modifier.height(spacing.sm))
            MiuixCodeText(
                code = content ?: "（该分页暂无文件）",
                language = CodeLanguage.JSON,
                showLineNumbers = false,
            )
            Spacer(Modifier.height(spacing.sm))
            MiuixText(
                text = "会话目录：${session.dir}",
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.md))
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                MiuixButton(
                    text = "在文件中打开",
                    onClick = onOpenInFiles,
                    size = MiuixButtonSize.SMALL,
                    variant = MiuixButtonVariant.OUTLINED,
                    leadingIcon = Icons.Outlined.FolderOpen,
                )
                MiuixButton(
                    text = "复制路径",
                    onClick = {
                        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("artifact", session.dir))
                        onToast("路径已复制")
                    },
                    size = MiuixButtonSize.SMALL,
                    variant = MiuixButtonVariant.OUTLINED,
                    leadingIcon = Icons.Outlined.Share,
                )
                MiuixButton(
                    text = "删除",
                    onClick = onDelete,
                    size = MiuixButtonSize.SMALL,
                    variant = MiuixButtonVariant.TEXT,
                    leadingIcon = Icons.Outlined.Delete,
                )
            }
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

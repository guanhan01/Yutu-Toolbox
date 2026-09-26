package com.mcp.toolbox.ui.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Terminal
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixDialog
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.AutoAwesome

/**
 * Linux 工具环境主页。
 *
 * 环境按需下载，不随应用打包；下载源可选、也可自动测速选择，界面不展示具体地址。
 */
@Composable
fun LinuxScreen(
    onOpenChecker: () -> Unit,
    onInstallTool: (LinuxComponent) -> Unit = {},
    onOpenTerminal: () -> Unit = {},
    onOpenFiles: () -> Unit,
    onOpenShared: () -> Unit,
    onOpenWorkspace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 初值取自持久化；离开页面再回来不会重置
    var distro by remember { mutableStateOf(LinuxPrefs.distro(context)) }
    var runtime by remember { mutableStateOf(LinuxPrefs.runtime(context)) }
    var installed by remember { mutableStateOf(false) }
    var sizeText by remember { mutableStateOf("未安装") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<LinuxInstaller.Progress?>(null) }
    var pickDistro by remember { mutableStateOf(false) }
    var pickRuntime by remember { mutableStateOf(false) }
    var components by remember { mutableStateOf<List<ComponentStatus>>(emptyList()) }
    var sourceId by remember { mutableStateOf(LinuxPrefs.source(context, distro)) }
    var pickSource by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<InstallState?>(null) }
    var partialPercent by remember { mutableStateOf(0) }
    var statusLine by remember { mutableStateOf("") }
    // 各工具已下载但未解包的断点大小：> 0 的工具在列表里标「可继续下载」
    var toolPartials by remember { mutableStateOf<Map<LinuxComponent, Long>>(emptyMap()) }

    suspend fun refresh() {
        // sizeOf 会遍历整个 rootfs（几万个文件），放主线程必卡
        val snapshot = withContext(Dispatchers.IO) {
            val ready = LinuxEnvStore.isInstalled(context, distro)
            ready to if (ready) LinuxEnvStore.sizeOf(context, distro) else 0L
        }
        installed = snapshot.first
        sizeText = if (snapshot.first) LinuxChecker.humanSize(snapshot.second) else "未安装"
    }

    /** 读取未完成的安装进度：有中断记录时按钮改成「继续安装」。 */
    suspend fun refreshPending() {
        val state = withContext(Dispatchers.IO) { InstallState.load(context, distro) }
        pending = state
        partialPercent = withContext(Dispatchers.IO) {
            if (state == null || state.total <= 0) return@withContext 0
            val target = File(LinuxEnvStore.downloads(context), state.fileName)
            val part = LinuxInstaller.partFile(target, state.url)
            if (part.isFile) ((part.length() * 100) / state.total).toInt().coerceIn(0, 100) else 0
        }
    }

    /**
     * 扫描「可选用工具」：真实探测各工具版本，并统计下载断点大小。
     *
     * 装机后必须调用一次，否则列表会停在「全部未安装」——用户装完的第一眼
     * 就在看这一栏，不能等他手动进检测页。
     */
    suspend fun rescanComponents() {
        val snapshot = withContext(Dispatchers.IO) {
            if (!LinuxEnvStore.isInstalled(context, distro)) {
                return@withContext emptyList<ComponentStatus>() to emptyMap<LinuxComponent, Long>()
            }
            val statuses = LinuxChecker.check(context, distro)
            val versions = probeVersions(context, distro)
            val enriched = statuses.map { status ->
                val found = versions[status.component]
                // 同 scanEnv：chroot 内 probe 出结果即视为已安装，避免软链接误判
                if (!found.isNullOrBlank()) {
                    status.copy(installed = true, version = found)
                } else {
                    status
                }
            }
            val partials = LinuxComponent.entries
                .associateWith { LinuxToolchain.pendingBytes(context, distro, it) }
                .filterValues { it > 0L }
            enriched to partials
        }
        components = snapshot.first
        toolPartials = snapshot.second
    }

    /** 下载并安装（或继续安装），完成后立刻重扫工具状态。 */
    /** 卸载当前发行版：清 rootfs、下载缓存与续装状态，完成后重扫界面状态。 */
    suspend fun removeAction() {
        busy = true
        message = ""
        statusLine = ""
        val result = LinuxInstaller.uninstall(
            context = context,
            distro = distro,
            onStatus = { statusLine = it },
        )
        busy = false
        statusLine = ""
        result.fold(
            onSuccess = { message = "已卸载 ${distro.displayName} 环境" },
            onFailure = { message = it.message ?: "卸载失败" },
        )
        refresh()
        refreshPending()
        rescanComponents()
    }

    suspend fun installAction() {
        busy = true
        message = ""
        statusLine = ""
        val result = LinuxInstaller.install(
            context = context,
            distro = distro,
            sourceId = sourceId,
            onProgress = { progress = it },
            onStatus = { statusLine = it },
        )
        busy = false
        progress = null
        statusLine = ""
        result.fold(
            onSuccess = { message = "安装完成，已自动完成环境检测" },
            onFailure = { message = it.message ?: "安装失败" },
        )
        refresh()
        refreshPending()
        rescanComponents()
    }

    LaunchedEffect(distro) {
        sourceId = LinuxPrefs.source(context, distro)
        refresh()
        refreshPending()
        rescanComponents()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))

        // 顶部状态卡
        MiuixSectionCard(
            // 标题只反映发行版；运行方式放在副标题，二者互不影响
            title = distro.displayName,
            subtitle = runtime.title,
        ) {
            Column(Modifier.padding(spacing.lg)) {
                MiuixText(
                    text = when {
                        busy && progress != null ->
                            "正在下载 ${progress?.fileName.orEmpty()} ${progress?.percent ?: 0}%"
                        busy -> statusLine.ifBlank { "正在准备…" }
                        pending != null -> "上次安装中断（约 $partialPercent%），点下方继续"
                        installed -> "基础工具已就绪（$sizeText），可按需安装扩展工具"
                        else -> "尚未安装，点击下方按钮下载并安装"
                    },
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                if (message.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    MiuixText(
                        text = message,
                        style = MiuixTheme.typography.bodySmall,
                        color = if (message.startsWith("安装完成")) colors.success else colors.error,
                    )
                }
                Spacer(Modifier.height(spacing.md))
                // 用 FlowRow 而不是 Row：加上「卸载」后四个按钮在窄屏上会超出一行，
                // Row 不换行会直接把最后一个挤出卡片裁掉（实测「卸载」被裁得看不见），
                // FlowRow 放不下时自动换到下一行，任何屏宽都能完整显示。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    MiuixButton(
                        text = when {
                            busy -> "安装中"
                            pending != null -> "继续安装"
                            installed -> "重新安装"
                            else -> "下载并安装"
                        },
                        onClick = {
                            if (busy) return@MiuixButton
                            scope.launch { installAction() }
                        },
                        enabled = !busy,
                        loading = busy,
                    )
                    MiuixButton(
                        text = stringResource(R.string.linux_open_terminal),
                        onClick = onOpenTerminal,
                        enabled = installed,
                        variant = com.mcp.toolbox.core.design.component.MiuixButtonVariant.TONAL,
                    )
                    MiuixButton(
                        text = "环境检测",
                        onClick = onOpenChecker,
                        variant = com.mcp.toolbox.core.design.component.MiuixButtonVariant.TEXT,
                    )
                    // 仅在已安装时给出卸载入口：没装就没有可删的东西
                    if (installed && !busy) {
                        MiuixButton(
                            text = "卸载",
                            onClick = { confirmRemove = true },
                            variant = com.mcp.toolbox.core.design.component.MiuixButtonVariant.TEXT,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(spacing.groupGap))

        // 环境配置
        MiuixSectionCard(title = stringResource(R.string.linux_section_config)) {
            Column {
                SettingsRow(
                    title = stringResource(R.string.linux_distro),
                    value = distro.title,
                    onClick = { pickDistro = true },
                )
                SettingsRow(
                    title = stringResource(R.string.linux_runtime),
                    value = runtime.title,
                    onClick = { pickRuntime = true },
                )
                SettingsRow(
                    title = stringResource(R.string.linux_source),
                    value = LinuxSources.nameOf(distro, sourceId),
                    divider = false,
                    onClick = { pickSource = true },
                )
            }
        }
        Spacer(Modifier.height(spacing.groupGap))

        // 文件与目录
        MiuixSectionCard(title = stringResource(R.string.linux_section_files)) {
            Column {
                MiuixListItem(
                    title = stringResource(R.string.linux_workspace),
                    subtitle = stringResource(R.string.linux_workspace_desc),
                    leadingIcon = Icons.Outlined.Folder,
                    showDivider = true,
                    onClick = onOpenWorkspace,
                )
                MiuixListItem(
                    title = stringResource(R.string.linux_shared),
                    subtitle = stringResource(R.string.linux_shared_desc),
                    leadingIcon = Icons.Outlined.FolderOpen,
                    showDivider = true,
                    onClick = onOpenShared,
                )
                MiuixListItem(
                    title = stringResource(R.string.linux_browse),
                    subtitle = stringResource(R.string.linux_browse_desc),
                    leadingIcon = Icons.Outlined.Description,
                    onClick = onOpenFiles,
                )
            }
        }
        Spacer(Modifier.height(spacing.groupGap))

        // 可选用工具
        MiuixSectionCard(
            title = stringResource(R.string.linux_section_tools),
            subtitle = stringResource(R.string.linux_tools_hint),
        ) {
            Column {
                LinuxComponent.entries.forEachIndexed { index, component ->
                    val status = components.firstOrNull { it.component == component }
                    ComponentRow(
                        component = component,
                        status = status,
                        installed = installed,
                        partialBytes = toolPartials[component] ?: 0L,
                        showDivider = index != LinuxComponent.entries.lastIndex,
                        onInstall = { onInstallTool(component) },
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (confirmRemove) {
        MiuixDialog(
            visible = true,
            onDismiss = { confirmRemove = false },
            title = "卸载 ${distro.displayName} 环境",
            message = "将删除该系统已安装的全部内容，包括已装好的扩展工具（$sizeText）。共享文件夹与工作区文件不受影响，但下次使用需要重新下载安装。",
            confirmText = "卸载",
            onConfirm = {
                confirmRemove = false
                scope.launch { removeAction() }
            },
            destructive = true,
        )
    }
    if (pickDistro) {
        LinuxChoiceSheet(
            title = stringResource(R.string.linux_distro),
            options = LinuxDistro.entries.map { it.title to it.subtitle },
            selectedIndex = LinuxDistro.entries.indexOf(distro),
            onPick = {
                distro = LinuxDistro.entries[it]
                LinuxPrefs.save(context, distro, runtime)
                pending = null
                pickDistro = false
            },
            onDismiss = { pickDistro = false },
        )
    }
    if (pickSource) {
        LinuxChoiceSheet(
            title = stringResource(R.string.linux_source),
            options = LinuxSources.options(distro).map { source ->
                val id = source?.id ?: LinuxSources.AUTO
                LinuxSources.nameOf(distro, id) to LinuxSources.describe(source)
            },
            selectedIndex = LinuxSources.options(distro)
                .indexOfFirst { it?.id == sourceId || (it == null && sourceId == LinuxSources.AUTO) }
                .coerceAtLeast(0),
            onPick = { index ->
                val picked = LinuxSources.options(distro)[index]
                sourceId = picked?.id ?: LinuxSources.AUTO
                LinuxPrefs.saveSource(context, distro, sourceId)
                pickSource = false
            },
            onDismiss = { pickSource = false },
        )
    }
    if (pickRuntime) {
        LinuxChoiceSheet(
            title = stringResource(R.string.linux_runtime),
            options = LinuxRunMode.entries.map { it.title to it.subtitle },
            selectedIndex = LinuxRunMode.entries.indexOf(runtime),
            onPick = {
                runtime = LinuxRunMode.entries[it]
                LinuxPrefs.save(context, distro, runtime)
                pickRuntime = false
            },
            onDismiss = { pickRuntime = false },
        )
    }
}

@Composable
private fun SettingsRow(
    title: String,
    value: String,
    divider: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colors
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixText(text = title, style = MiuixTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            MiuixText(
                text = value,
                style = MiuixTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
        if (divider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(1.dp)
                    .background(colors.outlineVariant),
            )
        }
    }
}

/** 工具行：图标 + 名称说明 + 已安装标签 / 安装按钮。 */
@Composable
private fun ComponentRow(
    component: LinuxComponent,
    status: ComponentStatus?,
    installed: Boolean,
    partialBytes: Long,
    showDivider: Boolean,
    onInstall: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val ready = status?.installed == true
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            MiuixIcon(
                icon = when (component) {
                    LinuxComponent.PYTHON -> Icons.Outlined.Terminal
                    LinuxComponent.NODE -> Icons.Outlined.Memory
                    LinuxComponent.SSH -> Icons.Outlined.Key
                    LinuxComponent.APK -> Icons.Outlined.Android
                    LinuxComponent.GIT -> Icons.Outlined.Code
                    LinuxComponent.CODEX -> Icons.Outlined.SmartToy
                    LinuxComponent.CLAUDE -> Icons.Outlined.AutoAwesome
                },
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                size = 20.dp,
            )
            Column(Modifier.weight(1f)) {
                MiuixText(text = component.title, style = MiuixTheme.typography.bodyLarge)
                MiuixText(
                    text = when {
                        ready && status.version.isNotBlank() -> "${component.subtitle}\n${status.version}"
                        partialBytes > 0L ->
                            "${component.subtitle}\n已下载 ${LinuxChecker.humanSize(partialBytes)}，可继续"
                        else -> component.subtitle
                    },
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            when {
                !installed -> MiuixTag(text = "需先安装环境", color = colors.onSurfaceVariant)
                ready -> MiuixTag(text = stringResource(R.string.linux_installed))
                else -> MiuixButton(
                    // 有残留下载时说明上次中断过，按钮改成「继续下载」更贴合实际
                    text = if (partialBytes > 0L) {
                        "继续下载"
                    } else {
                        stringResource(R.string.linux_install)
                    },
                    onClick = onInstall,
                    variant = com.mcp.toolbox.core.design.component.MiuixButtonVariant.TONAL,
                )
            }
        }
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(1.dp)
                    .background(colors.outlineVariant),
            )
        }
    }
}

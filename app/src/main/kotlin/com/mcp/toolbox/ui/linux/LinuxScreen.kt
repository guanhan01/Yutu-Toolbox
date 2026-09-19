package com.mcp.toolbox.ui.linux

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch

/**
 * Linux 工具环境主页。
 *
 * 环境按需下载，不随应用打包；下载源在代码里配置，界面不展示地址。
 */
@Composable
fun LinuxScreen(
    onOpenChecker: () -> Unit,
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

    var distro by remember { mutableStateOf(LinuxDistro.DEBIAN) }
    var runtime by remember { mutableStateOf(LinuxRunMode.PROOT) }
    var installed by remember { mutableStateOf(false) }
    var sizeText by remember { mutableStateOf("未安装") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf<LinuxInstaller.Progress?>(null) }
    var pickDistro by remember { mutableStateOf(false) }
    var pickRuntime by remember { mutableStateOf(false) }
    var components by remember { mutableStateOf<List<ComponentStatus>>(emptyList()) }

    fun refresh() {
        installed = LinuxEnvStore.isInstalled(context, distro)
        sizeText = if (installed) {
            LinuxChecker.humanSize(LinuxEnvStore.sizeOf(context, distro))
        } else {
            "未安装"
        }
    }

    LaunchedEffect(distro) {
        refresh()
        components = LinuxChecker.check(context, distro)
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
            title = "${distro.title} ${if (runtime == LinuxRunMode.PROOT) "13" else ""}".trim(),
            subtitle = runtime.title,
        ) {
            Column(Modifier.padding(spacing.lg)) {
                MiuixText(
                    text = when {
                        busy && progress != null ->
                            "正在下载 ${progress?.fileName.orEmpty()} ${progress?.percent ?: 0}%"
                        busy -> "正在准备…"
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
                Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                    MiuixButton(
                        text = if (installed) "重新安装" else "下载并安装",
                        onClick = {
                            if (busy) return@MiuixButton
                            busy = true
                            message = ""
                            scope.launch {
                                val result = LinuxInstaller.install(context, distro) { progress = it }
                                busy = false
                                progress = null
                                result.fold(
                                    onSuccess = { message = "安装完成" },
                                    onFailure = { message = it.message ?: "安装失败" },
                                )
                                refresh()
                                components = LinuxChecker.check(context, distro)
                            }
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
                    divider = false,
                    onClick = { pickRuntime = true },
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
                        showDivider = index != LinuxComponent.entries.lastIndex,
                        onInstall = { onOpenChecker() },
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    if (pickDistro) {
        LinuxChoiceSheet(
            title = stringResource(R.string.linux_distro),
            options = LinuxDistro.entries.map { it.title to it.subtitle },
            selectedIndex = LinuxDistro.entries.indexOf(distro),
            onPick = { distro = LinuxDistro.entries[it]; pickDistro = false },
            onDismiss = { pickDistro = false },
        )
    }
    if (pickRuntime) {
        LinuxChoiceSheet(
            title = stringResource(R.string.linux_runtime),
            options = LinuxRunMode.entries.map { it.title to it.subtitle },
            selectedIndex = LinuxRunMode.entries.indexOf(runtime),
            onPick = { runtime = LinuxRunMode.entries[it]; pickRuntime = false },
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
                },
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                size = 20.dp,
            )
            Column(Modifier.weight(1f)) {
                MiuixText(text = component.title, style = MiuixTheme.typography.bodyLarge)
                MiuixText(
                    text = if (ready && status.version.isNotBlank()) {
                        "${component.subtitle}\n${status.version}"
                    } else {
                        component.subtitle
                    },
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            when {
                !installed -> MiuixTag(text = "需先安装环境", color = colors.onSurfaceVariant)
                ready -> MiuixTag(text = stringResource(R.string.linux_installed))
                else -> MiuixButton(
                    text = stringResource(R.string.linux_install),
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

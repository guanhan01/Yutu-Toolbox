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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 二选一弹层：发行版 / 运行方式。 */
@Composable
fun LinuxChoiceSheet(
    title: String,
    options: List<Pair<String, String>>,
    selectedIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MiuixTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .clip(RoundedCornerShape(MiuixTheme.radius.dialog))
                    .background(colors.surface)
                    .padding(vertical = 16.dp),
            ) {
                MiuixText(
                    text = title,
                    style = MiuixTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                options.forEachIndexed { index, (name, desc) ->
                    val active = index == selectedIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp)
                            .clip(RoundedCornerShape(MiuixTheme.radius.field))
                            .background(if (active) colors.primary.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { onPick(index) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            MiuixText(
                                text = name,
                                style = MiuixTheme.typography.bodyLarge,
                                color = if (active) colors.primary else colors.onSurface,
                            )
                            MiuixText(
                                text = desc,
                                style = MiuixTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (active) {
                            MiuixIcon(Icons.Outlined.Check, null, tint = colors.primary, size = 18.dp)
                        }
                    }
                }
            }
        }
    }
}

/**
 * 环境检测页：列出组件、状态与版本。
 *
 * 检测在 rootfs 内查可执行文件，瞬时完成；缺失的组件给出安装入口。
 */
@Composable
fun LinuxCheckScreen(
    distro: LinuxDistro,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    var statuses by remember { mutableStateOf<List<ComponentStatus>>(emptyList()) }
    var scanning by remember { mutableStateOf(true) }

    LaunchedEffect(distro) {
        scanning = true
        statuses = withContext(Dispatchers.IO) { LinuxChecker.check(context, distro) }
        scanning = false
    }

    val ready = statuses.count { it.installed }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))
        MiuixSectionCard(
            title = stringResource(R.string.linux_check_title),
            subtitle = stringResource(R.string.linux_check_hint),
        ) {
            Column(Modifier.padding(spacing.lg)) {
                MiuixText(
                    text = if (scanning) {
                        stringResource(R.string.linux_checking)
                    } else {
                        stringResource(R.string.linux_check_summary, ready, statuses.size)
                    },
                    style = MiuixTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                MiuixText(
                    text = "${distro.title} · ${LinuxChecker.humanSize(LinuxEnvStore.sizeOf(context, distro))}",
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(spacing.groupGap))

        MiuixSectionCard(title = stringResource(R.string.linux_section_tools)) {
            Column {
                statuses.forEachIndexed { index, status ->
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(
                                        if (status.installed) colors.primary else colors.surfaceContainerHighest,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (status.installed) {
                                    MiuixIcon(
                                        Icons.Outlined.Check,
                                        null,
                                        tint = colors.onPrimary,
                                        size = 14.dp,
                                    )
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                MiuixText(
                                    text = status.component.title,
                                    style = MiuixTheme.typography.bodyLarge,
                                )
                                MiuixText(
                                    text = status.component.subtitle,
                                    style = MiuixTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                MiuixTag(
                                    text = if (status.installed) {
                                        stringResource(R.string.linux_status_ready)
                                    } else {
                                        stringResource(R.string.linux_status_missing)
                                    },
                                    color = if (status.installed) colors.success else colors.onSurfaceVariant,
                                )
                                if (status.version.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    MiuixText(
                                        text = status.version,
                                        style = MiuixTheme.typography.labelSmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        if (index != statuses.lastIndex) {
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
                if (statuses.isEmpty() && !scanning) {
                    MiuixText(
                        text = stringResource(R.string.linux_check_empty),
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

package com.mcp.toolbox.feature.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixCard
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixSearchField
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import androidx.compose.foundation.Image
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import com.mcp.toolbox.core.design.component.HomeHeaderBackground
import com.mcp.toolbox.core.design.component.FlowingAuroraBackground
import androidx.compose.ui.draw.blur
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.shape.CircleShape

private data class QuickTool(
    val id: String,
    val label: String,
    val icon: ImageVector,
)

private val quickTools = listOf(
    QuickTool("apps", "应用", Icons.Outlined.Smartphone),
    QuickTool("files", "文件", Icons.Outlined.Folder),
    QuickTool("web", "网页", Icons.Outlined.Language),
    QuickTool("network", "网络", Icons.Outlined.Wifi),
    QuickTool("database", "数据库", Icons.Outlined.Storage),
    QuickTool("capture", "抓包", Icons.Outlined.Bolt),
    QuickTool("decompile", "反编译", Icons.Outlined.Terminal),
    QuickTool("mcp", "MCP", Icons.Outlined.Hub),
)

/**
 * 首页：渐变 Banner + 搜索 + 快捷工具九宫格 + MCP 状态 + 最近任务。
 * 快捷工具后续支持编辑排序（与设置页同源持久化）。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
    onOpenTool: (String) -> Unit = {},
    mcpConnected: Boolean = false,
    mcpServerRunning: Boolean = false,
    recentTasks: List<String> = emptyList(),
    privilegeDetail: String = "等待探测",
    privilegeUsable: Boolean = false,
    privilegeProbing: Boolean = true,
    onOpenPrivilege: () -> Unit = {},
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    var query by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
        verticalArrangement = Arrangement.spacedBy(spacing.groupGap),
    ) {
        Spacer(Modifier.height(spacing.sm))

        val headerContext = LocalContext.current
        val headerBitmap by HomeHeaderBackground.bitmap.collectAsState()
        val headerBlurred by HomeHeaderBackground.blurred.collectAsState()
        val headerAurora by HomeHeaderBackground.aurora.collectAsState()
        LaunchedEffect(Unit) { HomeHeaderBackground.refresh(headerContext) }

        // 大标题 Banner：设了自定义图片就铺图压暗，否则用主题渐变
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(RoundedCornerShape(MiuixTheme.radius.card)),
        ) {
            val bg = headerBitmap
            if (bg != null) {
                Image(
                    bitmap = bg,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .then(
                            // blur 需要 Android 12+，更低版本会自动忽略
                            if (headerBlurred) Modifier.blur(26.dp) else Modifier,
                        ),
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Black.copy(alpha = 0.12f), Color.Black.copy(alpha = 0.58f)),
                            ),
                        ),
                )
            } else if (headerAurora) {
                // 未设置自定义图片时的默认背景：动态流光
                FlowingAuroraBackground(modifier = Modifier.matchParentSize())
            } else {
                // 关掉流光同样用这套配色，只是静止不飘
                FlowingAuroraBackground(modifier = Modifier.matchParentSize(), animated = false)
            }
            Column(modifier = Modifier.align(Alignment.BottomStart).padding(spacing.lg)) {
                MiuixText(
                    text = "Yutu",
                    style = MiuixTheme.typography.displaySmall,
                    color = colors.onPrimaryContainer,
                )
            }
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(spacing.sm)) {
                McpStatusBadge(connected = mcpConnected)
            }
        }

        MiuixSearchField(value = query, onValueChange = { query = it }, placeholder = "搜索工具、文件、请求…")

        Column {
            MiuixText(
                text = "快捷工具",
                style = MiuixTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().height(176.dp + 24.dp * (MiuixTheme.dimens.fontScale - 1f)),
                verticalArrangement = Arrangement.spacedBy(spacing.groupGap),
                horizontalArrangement = Arrangement.spacedBy(spacing.groupGap),
                userScrollEnabled = false,
            ) {
                items(quickTools, key = { it.id }) { tool ->
                    QuickToolTile(tool = tool, onClick = { onOpenTool(tool.id) })
                }
            }
        }

        MiuixCard {
            MiuixListItem(
                title = "内置 MCP Server",
                subtitle = if (mcpServerRunning) "运行中 · 点开查看端点与 token" else "尚未启动，去 MCP 页面开启",
                leadingIcon = Icons.Outlined.Hub,
                trailing = {
                    MiuixTag(
                        text = if (mcpServerRunning) "运行中" else "已停止",
                        color = if (mcpServerRunning) colors.success else colors.onSurfaceVariant,
                    )
                },
                onClick = { onOpenTool("mcp") },
            )
        }

        MiuixCard {
            MiuixListItem(
                title = "权限管理",
                subtitle = privilegeDetail,
                leadingIcon = Icons.Outlined.Security,
                trailing = {
                    MiuixTag(
                        text = when {
                            privilegeProbing -> "检测中"
                            privilegeUsable -> "已就绪"
                            else -> "未就绪"
                        },
                        color = when {
                            privilegeProbing -> colors.onSurfaceVariant
                            privilegeUsable -> colors.success
                            else -> colors.warning
                        },
                    )
                },
                onClick = onOpenPrivilege,
            )
        }

        Column {
            MiuixText(
                text = "最近任务",
                style = MiuixTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
            MiuixCard {
                if (recentTasks.isEmpty()) {
                    Box(Modifier.padding(spacing.lg)) {
                        MiuixText(
                            text = "暂无任务记录",
                            style = MiuixTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                } else {
                    recentTasks.forEachIndexed { index, task ->
                        MiuixListItem(
                            title = task,
                            subtitle = "点击查看产物与日志",
                            leadingIcon = Icons.Outlined.Bolt,
                            showDivider = index != recentTasks.lastIndex,
                            onClick = { onOpenTool("mcp") },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun QuickToolTile(tool: QuickTool, onClick: () -> Unit) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(MiuixTheme.radius.field))
                .background(colors.surfaceContainerHigh)
                .miuixClickable(press, true, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            MiuixIcon(tool.icon, tool.label, tint = colors.primary, size = 22.dp)
        }
        Spacer(Modifier.height(6.dp))
        MiuixText(
            text = tool.label,
            style = MiuixTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
        )
    }
}


/** 大标题卡右上角的 MCP 连接角标：实心高对比，未连接时左侧圆点呼吸提醒。 */
@Composable
private fun McpStatusBadge(connected: Boolean) {
    val colors = MiuixTheme.colors
    // 卡片底色现在是 primaryContainer（浅色 tone90 / 深色 tone30），标签就取
    // onPrimaryContainer 作底、primaryContainer 作字，两者互为对比配对；圆点用
    // 容器色而非 on 色，压在实底上才有足够反差
    val background = colors.onPrimaryContainer
    val foreground = colors.primaryContainer
    val dot = if (connected) colors.successContainer else colors.errorContainer

    val transition = rememberInfiniteTransition(label = "mcp-badge")
    val pulse by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "mcp-badge-pulse",
    )

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(background)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(dot.copy(alpha = if (connected) 1f else pulse)),
        )
        Spacer(Modifier.width(6.dp))
        MiuixText(
            text = if (connected) "MCP 已连接" else "MCP 未连接",
            style = MiuixTheme.typography.labelMedium,
            color = foreground,
            maxLines = 1,
        )
    }
}

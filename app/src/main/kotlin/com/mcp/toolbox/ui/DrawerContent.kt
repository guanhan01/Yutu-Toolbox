package com.mcp.toolbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.R
import com.mcp.toolbox.feature.capture.CaptureStore
import com.mcp.toolbox.core.design.component.MiuixBadge
import com.mcp.toolbox.core.design.component.MiuixDivider
import androidx.compose.material.icons.outlined.Close
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.navigation.Destination
import com.mcp.toolbox.navigation.DrawerFooter
import com.mcp.toolbox.navigation.DrawerNetworkChildren
import com.mcp.toolbox.navigation.DrawerPrimary
import com.mcp.toolbox.navigation.Routes

/**
 * 汉堡抽屉内容：「应用工具」分组 + 可折叠「网络」二级菜单 + 条目 badge。
 * 底部为横向纯图标入口（设置 / 关于）。
 */
@Composable
fun DrawerContent(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    var networkExpanded by remember { mutableStateOf(currentRoute.startsWith("network")) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.surfaceContainerLow),
    ) {
        // 顶部只有一个关闭按钮，不放标题，避免上方留白
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.md, end = spacing.md, top = spacing.sm),
            horizontalArrangement = Arrangement.End,
        ) {
            MiuixIconButton(
                onClick = onClose,
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.app_drawer_close),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = spacing.xs),
        ) {
            // 主列表只保留「首页」，工具统一收进底栏的「应用工具」子页面
            DrawerPrimary.forEach { destination ->
                DrawerItem(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    onClick = {
                        if (destination.route == Routes.NETWORK) {
                            networkExpanded = !networkExpanded
                        }
                        onNavigate(destination.route)
                    },
                    expandable = destination.route == Routes.NETWORK,
                    expanded = networkExpanded,
                )
                if (destination.route == Routes.NETWORK) {
                    AnimatedVisibility(
                        visible = networkExpanded,
                        enter = expandVertically(tween(200)),
                        exit = shrinkVertically(tween(200)),
                    ) {
                        Column {
                            DrawerNetworkChildren.forEach { child ->
                                DrawerItem(
                                    destination = child,
                                    selected = currentRoute == child.route,
                                    onClick = { onNavigate(child.route) },
                                    indented = true,
                                )
                            }
                        }
                    }
                }
            }
        }

        MiuixDivider()
        // 底栏：横向排布，只留图标
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrawerFooter.forEach { destination ->
                DrawerFooterIcon(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    onClick = { onNavigate(destination.route) },
                )
            }
        }
    }

}

/** 抽屉底部的语言入口：显示当前选择，点开三选一。 */
@Composable
private fun DrawerItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
    indented: Boolean = false,
    expandable: Boolean = false,
    expanded: Boolean = false,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val press = rememberMiuixPressState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = 2.dp)
            .clip(RoundedCornerShape(MiuixTheme.radius.inner))
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLow)
            .miuixClickable(press, true, onClick = onClick)
            .padding(start = if (indented) 40.dp else 14.dp, end = 12.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MiuixIcon(
            icon = destination.icon,
            contentDescription = stringResource(destination.labelRes),
            tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            size = 20.dp,
        )
        MiuixText(
            text = stringResource(destination.labelRes),
            style = MiuixTheme.typography.bodyLarge,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        val captureState by CaptureStore.state.collectAsState()
        val badgeCount = if (destination.route == Routes.CAPTURE) {
            if (captureState.running) captureState.records.size else 0
        } else {
            destination.badge
        }
        if (badgeCount > 0) {
            MiuixBadge(count = badgeCount)
        }
        if (expandable) {
            MiuixIcon(
                icon = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                size = 18.dp,
            )
        }
    }
}

/** 抽屉底栏入口：纯图标，无文字。 */
@Composable
private fun DrawerFooterIcon(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
            .miuixClickable(press, true, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MiuixIcon(
            destination.icon,
            stringResource(destination.labelRes),
            tint = if (selected) colors.primary else colors.onSurfaceVariant,
            size = 24.dp,
        )
    }
}

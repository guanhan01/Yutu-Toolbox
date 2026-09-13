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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.R
import com.mcp.toolbox.feature.capture.CaptureStore
import com.mcp.toolbox.core.design.component.MiuixBadge
import com.mcp.toolbox.core.design.component.MiuixDivider
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
 * 汉堡抽屉内容：渐变头图 + 可折叠「网络」二级菜单 + 条目 badge + 语言入口 + 底部固定项。
 * 对齐设计稿图 2 的深浅双区结构。
 */
@Composable
fun DrawerContent(
    currentRoute: String,
    onNavigate: (String) -> Unit,
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
        // 渐变头图区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .background(
                    Brush.linearGradient(listOf(colors.primary, colors.tertiary, colors.primaryContainer)),
                ),
            contentAlignment = Alignment.BottomStart,
        ) {
            Row(
                modifier = Modifier.padding(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(colors.surface.copy(alpha = 0.9f)),
                    contentAlignment = Alignment.Center,
                ) {
                    MiuixIcon(Icons.Outlined.Person, null, tint = colors.primary, size = 26.dp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    MiuixText(
                        text = stringResource(R.string.app_name),
                        style = MiuixTheme.typography.titleLarge,
                        color = colors.onPrimary,
                    )
                    MiuixText(
                        text = stringResource(R.string.app_drawer_subtitle),
                        style = MiuixTheme.typography.labelMedium,
                        color = colors.onPrimary.copy(alpha = 0.85f),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = spacing.sm),
        ) {
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
        Column(modifier = Modifier.padding(vertical = spacing.sm)) {
            DrawerFooter.forEach { destination ->
                DrawerItem(
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

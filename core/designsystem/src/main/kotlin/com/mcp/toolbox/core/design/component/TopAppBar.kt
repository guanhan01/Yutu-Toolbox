package com.mcp.toolbox.core.design.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.anim.SinOutEasing
import top.yukonga.miuix.kmp.basic.SmallTopAppBar as OfficialSmallTopAppBar
import top.yukonga.miuix.kmp.basic.TopAppBar as OfficialTopAppBar

/**
 * 标准顶栏。
 *
 * 实现走官方 [OfficialSmallTopAppBar] / [OfficialTopAppBar]：
 * 高度、标题内边距、窗口内边距与滚动表现全部取自官方，
 * 因此**不要**在调用方再叠 `statusBarsPadding()`（会双重留白）。
 *
 * @param centeredTitle true 时用官方 [OfficialTopAppBar]（标题居中），false 用 [OfficialSmallTopAppBar]（标题左对齐）。
 * @param scrolled 滚动后底色由透明渐显为 surface，走官方 [SinOutEasing] 曲线。
 */
@Composable
fun MiuixTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    scrolled: Boolean = false,
    centeredTitle: Boolean = false,
    subtitle: String = "",
    /**
     * 是否绘制标题文字。
     *
     * 传 false 时标题槽留空（导航图标仍在左、动作区仍在右），[title] 仅用于
     * 导航按钮的无障碍描述 —— 对话页顶栏就是这种形态：标题内容较长且与消息区
     * 重复，留着只会挤占空间。
     *
     * 官方 TopAppBar 本身没有这个开关（它的 title 是必填 String），
     * 所以在这一层用空串实现。
     */
    showTitle: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = MiuixTheme.colors
    val background by animateColorAsState(
        targetValue = if (scrolled) colors.surface else Color.Transparent,
        animationSpec = tween(MiuixTheme.motion.fast, easing = SinOutEasing),
        label = "topbar-bg",
    )

    val nav: @Composable () -> Unit = {
        if (navigationIcon != null) {
            MiuixIconButton(
                icon = navigationIcon,
                contentDescription = title,
                onClick = onNavigationClick ?: {},
                buttonSize = 44.dp,
                iconSize = 22.dp,
            )
        }
    }

    val shownTitle = if (showTitle) title else ""

    if (centeredTitle) {
        OfficialTopAppBar(
            title = shownTitle,
            modifier = modifier,
            color = background,
            titleColor = colors.onSurface,
            subtitle = subtitle,
            subtitleColor = colors.onSurfaceVariant,
            navigationIcon = nav,
            actions = { actions?.invoke(this) },
        )
    } else {
        OfficialSmallTopAppBar(
            title = shownTitle,
            modifier = modifier,
            color = background,
            titleColor = colors.onSurface,
            subtitle = subtitle,
            subtitleColor = colors.onSurfaceVariant,
            navigationIcon = nav,
            actions = { actions?.invoke(this) },
        )
    }
}

/** 顶栏动作区容器：官方 actions 槽位要求 RowScope，无动作时传空即可。 */
@Composable
fun MiuixTopAppBarActions(content: @Composable RowScope.() -> Unit) {
    Row { content() }
}

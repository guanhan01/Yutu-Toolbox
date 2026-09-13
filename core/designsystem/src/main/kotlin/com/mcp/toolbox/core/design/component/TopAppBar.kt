package com.mcp.toolbox.core.design.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** 标准顶栏：56dp 高，滚动后背景渐显（Haze 模糊由调用方按需叠加）。 */
@Composable
fun MiuixTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    scrolled: Boolean = false,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = MiuixTheme.colors
    val background by animateColorAsState(
        targetValue = if (scrolled) colors.surface else Color.Transparent,
        animationSpec = tween(MiuixTheme.motion.fast),
        label = "topbar-bg",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(background)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (navigationIcon != null) {
            MiuixIconButton(
                icon = navigationIcon,
                contentDescription = title,
                onClick = onNavigationClick ?: {},
                buttonSize = 44.dp,
                iconSize = 22.dp,
            )
        } else {
            Spacer(Modifier.width(8.dp))
        }
        MiuixText(
            text = title,
            style = MiuixTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 8.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        if (actions != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                content = actions,
            )
        }
    }
}

/**
 * 视差折叠大标题：collapsedFraction 0 -> 1 时大字收小、顶栏背景渐显。
 * 由页面把 scrollState 归一化后传入，避免依赖具体版本的滚动行为 API。
 */
@Composable
fun MiuixCollapsingTopAppBar(
    title: String,
    collapsedFraction: Float,
    modifier: Modifier = Modifier,
    navigationIcon: ImageVector? = null,
    onNavigationClick: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = MiuixTheme.colors
    val progress = collapsedFraction.coerceIn(0f, 1f)
    val background by animateColorAsState(
        targetValue = if (progress > 0.05f) colors.surface else Color.Transparent,
        animationSpec = tween(MiuixTheme.motion.fast),
        label = "collapsing-bg",
    )
    val titleSizeScale = 1f - 0.35f * progress

    Column(modifier = modifier.fillMaxWidth().background(background)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) {
                MiuixIconButton(
                    icon = navigationIcon,
                    contentDescription = title,
                    onClick = onNavigationClick ?: {},
                    buttonSize = 44.dp,
                    iconSize = 22.dp,
                )
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Box(modifier = Modifier.weight(1f).padding(start = 8.dp)) {
                MiuixText(
                    text = title,
                    style = MiuixTheme.typography.titleLarge,
                    modifier = Modifier.graphicsLayer {
                        scaleX = titleSizeScale
                        scaleY = titleSizeScale
                        alpha = progress
                    },
                    maxLines = 1,
                )
            }
            if (actions != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    content = actions,
                )
            }
        }
        Box(
            modifier = Modifier
                .padding(start = 20.dp, bottom = 8.dp)
                .graphicsLayer {
                    scaleX = titleSizeScale
                    scaleY = titleSizeScale
                    alpha = 1f - progress
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                },
        ) {
            MiuixText(text = title, style = MiuixTheme.typography.displaySmall)
        }
    }
}

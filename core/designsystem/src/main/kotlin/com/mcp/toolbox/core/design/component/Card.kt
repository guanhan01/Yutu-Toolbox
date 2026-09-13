package com.mcp.toolbox.core.design.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** 卡片三种形态：filled / elevated / outlined（对应设计稿 Cards section）。 */
enum class MiuixCardVariant { FILLED, ELEVATED, OUTLINED }

@Composable
fun MiuixCard(
    modifier: Modifier = Modifier,
    variant: MiuixCardVariant = MiuixCardVariant.FILLED,
    onClick: (() -> Unit)? = null,
    shape: Shape = RoundedCornerShape(MiuixTheme.radius.card),
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colors
    val elevation = MiuixTheme.dimens.elevation
    val press = rememberMiuixPressState(onClick != null)

    val container = when (variant) {
        MiuixCardVariant.FILLED -> colors.surfaceContainer
        MiuixCardVariant.ELEVATED -> colors.surfaceContainerLow
        MiuixCardVariant.OUTLINED -> colors.surface
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .then(
                when (variant) {
                    MiuixCardVariant.OUTLINED -> Modifier.border(BorderStroke(1.dp, colors.outlineVariant), shape)
                    MiuixCardVariant.ELEVATED -> Modifier.shadow(elevation.level2, shape)
                    MiuixCardVariant.FILLED -> Modifier
                }
            )
            .then(if (onClick != null) Modifier.miuixClickable(press, true, onClick = onClick) else Modifier),
    ) {
        if (press.pressed) Box(Modifier.matchParentSize().background(colors.pressedOverlay))
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

/** 分组卡片：小标题 + 副标题 + 圆角容器（设置页与文件页的标准分组形态）。 */
@Composable
fun MiuixSectionCard(
    title: String? = null,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            MiuixText(
                text = title,
                style = MiuixTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 4.dp, bottom = if (subtitle == null) 8.dp else 2.dp),
            )
        }
        if (subtitle != null) {
            MiuixText(
                text = subtitle,
                style = MiuixTheme.typography.labelMedium,
                color = MiuixTheme.colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
            )
        }
        MiuixCard(contentPadding = contentPadding, content = content)
    }
}

/** 危险操作区：红色描边容器（删除 / 清空类操作）。 */
@Composable
fun MiuixDangerCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = MiuixTheme.colors
    val shape = RoundedCornerShape(MiuixTheme.radius.card)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(BorderStroke(1.dp, colors.error.copy(alpha = 0.5f)), shape),
        content = content,
    )
}

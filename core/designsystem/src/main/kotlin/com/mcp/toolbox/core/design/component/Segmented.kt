package com.mcp.toolbox.core.design.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** 分段按钮：Tab 行的标准替代品（反编译页 Java/Smali、数据库页 结构/数据/SQL）。 */
@Composable
fun <T> MiuixSegmentedButton(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
) {
    val colors = MiuixTheme.colors
    val motion = MiuixTheme.motion
    val outerShape = RoundedCornerShape(percent = 50)

    Row(
        modifier = modifier
            .height(36.dp)
            .clip(outerShape)
            .background(colors.surfaceContainerHighest)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val press = rememberMiuixPressState()
            val container by animateColorAsState(
                targetValue = if (isSelected) colors.surface else colors.surfaceContainerHighest,
                animationSpec = tween(motion.fast),
                label = "segment-bg",
            )
            val contentColor by animateColorAsState(
                targetValue = if (isSelected) colors.onSurface else colors.onSurfaceVariant,
                animationSpec = tween(motion.fast),
                label = "segment-fg",
            )
            val weight by animateFloatAsState(if (isSelected) 1f else 1f, label = "segment-weight")

            Box(
                modifier = Modifier
                    .weight(weight)
                    .fillMaxHeight()
                    .clip(outerShape)
                    .background(container)
                    .miuixClickable(press, true) { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                MiuixText(
                    text = label(option),
                    style = MiuixTheme.typography.labelMedium,
                    color = contentColor,
                    maxLines = 1,
                )
            }
        }
    }
}

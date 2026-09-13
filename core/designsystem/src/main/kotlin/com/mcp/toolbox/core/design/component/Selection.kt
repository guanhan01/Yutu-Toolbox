package com.mcp.toolbox.core.design.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** Miuix 开关：46x28 胶囊轨道 + 22dp 圆形 thumb，弹簧位移。 */
@Composable
fun MiuixSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colors
    val motion = MiuixTheme.motion
    val press = rememberMiuixPressState(enabled)
    val trackWidth = 46.dp
    val trackHeight = 28.dp
    val thumbSize = 22.dp

    val thumbOffset by animateDpAsState(
        targetValue = if (checked) trackWidth - thumbSize - 3.dp else 3.dp,
        animationSpec = motion.gentle(),
        label = "miuix-switch-thumb",
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.primary else colors.surfaceContainerHighest,
        animationSpec = tween(motion.fast),
        label = "miuix-switch-track",
    )

    Box(
        modifier = modifier
            .size(trackWidth, trackHeight)
            .clip(RoundedCornerShape(percent = 50))
            .background(trackColor)
            .miuixClickable(press, enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(thumbSize)
                .clip(CircleShape)
                .background(if (checked) colors.onPrimary else colors.outline),
        )
    }
}

/** 复选框：20dp 方块，选中为主色底 + 对勾。 */
@Composable
fun MiuixCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState(enabled)
    val shape = RoundedCornerShape(6.dp)
    val container by animateColorAsState(
        targetValue = if (checked) colors.primary else Color.Transparent,
        animationSpec = tween(MiuixTheme.motion.fast),
        label = "miuix-checkbox",
    )
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(shape)
            .background(container)
            .miuixClickable(press, enabled) { onCheckedChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            MiuixIcon(Icons.Filled.Check, null, tint = colors.onPrimary, size = 14.dp)
        } else {
            Box(Modifier.size(20.dp).clip(shape).background(colors.outline.copy(alpha = 0.55f)))
            Box(Modifier.size(17.dp).clip(shape).background(colors.surface))
        }
    }
}

/** 单选：外圈 20dp + 内点 10dp。 */
@Composable
fun MiuixRadioButton(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState(enabled)
    val ring by animateColorAsState(
        targetValue = if (selected) colors.primary else colors.outline,
        animationSpec = tween(MiuixTheme.motion.fast),
        label = "miuix-radio",
    )
    Box(
        modifier = modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(ring)
            .miuixClickable(press, enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(16.dp).clip(CircleShape).background(colors.surface))
        if (selected) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(colors.primary))
        }
    }
}

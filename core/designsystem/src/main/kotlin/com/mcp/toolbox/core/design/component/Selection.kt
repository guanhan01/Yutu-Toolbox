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
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Checkbox as OfficialCheckbox
import top.yukonga.miuix.kmp.basic.Switch as OfficialSwitch

/** Miuix 开关。内部用官方 [OfficialSwitch]：官方 thumb 弹簧、拖动与触感反馈。 */
@Composable
fun MiuixSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OfficialSwitch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
    )
}

/** 复选框。内部用官方 [OfficialCheckbox]：官方打勾缩放动画与触感反馈。 */
@Composable
fun MiuixCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OfficialCheckbox(
        state = if (checked) ToggleableState.On else ToggleableState.Off,
        onClick = { onCheckedChange(!checked) },
        modifier = modifier,
        enabled = enabled,
    )
}

/** 单选按钮：圆环 + 内点。 */
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
    val dotSize by animateDpAsState(
        targetValue = if (selected) 10.dp else 0.dp,
        animationSpec = MiuixTheme.motion.gentle(),
        label = "miuix-radio-dot",
    )
    Box(
        modifier = modifier
            .size(20.dp)
            .miuixClickable(press, enabled, onClick = onClick)
            .clip(CircleShape)
            .background(ring)
            .padding(2.dp)
            .clip(CircleShape)
            .background(colors.surface),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(dotSize)
                .clip(CircleShape)
                .background(colors.primary),
        )
    }
}

package com.mcp.toolbox.core.design.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** DESIGN.md 4.4：所有可点击控件的触控目标不小于 48dp。 */
val MinimumTouchTarget: Dp = 48.dp

/**
 * Miuix 按压态：缩放 0.98 + 表面轻微变暗，**不使用 Material 水波纹**。
 * 所有可点击组件都通过这个状态对象统一交互反馈。
 */
@Immutable
class MiuixPressState internal constructor(
    val interactionSource: MutableInteractionSource,
    val pressed: Boolean,
    val scale: Float,
)

@Composable
fun rememberMiuixPressState(enabled: Boolean = true): MiuixPressState {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressed = isPressed && enabled
    val motion = MiuixTheme.motion
    val scale by animateFloatAsState(
        targetValue = if (pressed) motion.pressScale else 1f,
        animationSpec = motion.gentle(),
        label = "miuix-press-scale",
    )
    return MiuixPressState(interactionSource, pressed, scale)
}

fun Modifier.miuixPressEffect(state: MiuixPressState): Modifier = scale(state.scale)

/**
 * 统一的 Miuix 点击修饰符。[onLongClick] 为 null 时等价于普通点击；
 * 非 null 时用 combinedClickable 承载长按（例如文件列表长按进入多选）。
 * 注意 onClick 必须保持在最后一个参数位，既有调用大量使用尾随 lambda。
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.miuixClickable(
    state: MiuixPressState,
    enabled: Boolean = true,
    role: Role? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier = this
    .scale(state.scale)
    .combinedClickable(
        enabled = enabled,
        role = role,
        indication = null,
        interactionSource = state.interactionSource,
        onLongClick = onLongClick,
        onClick = onClick,
    )

/**
 * 把可点击控件的最小触控区撑到 [minSize]，视觉内容仍按自身尺寸居中绘制、不会被放大。
 * 父级约束更小时服从父级，避免测出的尺寸越界。
 */
fun Modifier.miuixTouchTarget(minSize: Dp = MinimumTouchTarget): Modifier =
    layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val minPx = minSize.roundToPx()
        val width = touchTargetSize(placeable.width, minPx, constraints.hasBoundedWidth, constraints.maxWidth)
        val height = touchTargetSize(placeable.height, minPx, constraints.hasBoundedHeight, constraints.maxHeight)
        layout(width, height) {
            placeable.place((width - placeable.width) / 2, (height - placeable.height) / 2)
        }
    }

private fun touchTargetSize(size: Int, min: Int, bounded: Boolean, max: Int): Int {
    val target = maxOf(size, min)
    return if (bounded) target.coerceAtMost(max) else target
}

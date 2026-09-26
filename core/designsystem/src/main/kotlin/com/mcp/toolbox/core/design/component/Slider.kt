package com.mcp.toolbox.core.design.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Slider as OfficialSlider
import kotlin.math.roundToInt

/**
 * Miuix 滑块。实现为官方 Miuix 组件。
 * 两套 API 一致，调用点无需感知。
 */
@Composable
fun MiuixSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    showBubble: Boolean = false,
    enabled: Boolean = true,
    valueLabel: (Float) -> String = { if (steps == 0) "%.2f".format(it) else it.roundToInt().toString() },
) {
    MiuixSliderOfficial(value, onValueChange, modifier, valueRange, steps, showBubble, enabled, valueLabel)
}

/** Miuix 风格实现：官方 [OfficialSlider]：官方 thumb 缩放动画、拖动 spring 手感、
 * 关键点与触感反馈都由官方负责；外层 API 与气泡展示保持兼容。
 */
@Composable
private fun MiuixSliderOfficial(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    showBubble: Boolean = false,
    enabled: Boolean = true,
    valueLabel: (Float) -> String = { if (steps == 0) "%.2f".format(it) else it.roundToInt().toString() },
) {
    val colors = MiuixTheme.colors
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }

    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val travel = with(density) { 260.dp }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.CenterStart,
    ) {
        OfficialSlider(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            valueRange = valueRange,
            steps = steps,
            onValueChangeFinished = null,
        )

        if (showBubble && dragging) {
            Box(
                modifier = Modifier
                    .offset(x = travel * fraction, y = (-40).dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(colors.inverseSurface)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                MiuixText(
                    text = valueLabel(value),
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.inverseOnSurface,
                )
            }
        }
    }
}

/**
 * Miuix 滑块：轨道全圆角、thumb 按下放大，可选数值气泡与离散刻度。
 * 区间选择见 [MiuixRangeSlider]。
 */

package com.mcp.toolbox.core.design.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme

/**
 * 区间双滑块：两枚 thumb 共用一条全圆角轨道，拖动时自动吸附最近的一端。
 */
@Composable
fun MiuixRangeSlider(
    range: ClosedFloatingPointRange<Float>,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colors
    val density = LocalDensity.current
    var widthPx by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val startFraction = ((range.start - valueRange.start) / span).coerceIn(0f, 1f)
    val endFraction = ((range.endInclusive - valueRange.start) / span).coerceIn(0f, 1f)

    fun snap(raw: Float): Float = if (steps > 0) {
        val stepCount = steps + 1
        val stepValue = span / stepCount
        valueRange.start + (raw * stepCount).toInt() * stepValue
    } else {
        valueRange.start + raw * span
    }

    fun commit(x: Float) {
        val width = widthPx.coerceAtLeast(1).toFloat()
        val raw = (x / width).coerceIn(0f, 1f)
        val value = snap(raw).coerceIn(valueRange.start, valueRange.endInclusive)
        val mid = (startFraction + endFraction) / 2f
        onRangeChange(
            if (raw < mid) {
                value..maxOf(value, range.endInclusive)
            } else {
                minOf(value, range.start)..value
            },
        )
    }

    val thumbSize: Dp = if (dragging) 24.dp else 20.dp
    val widthDp = with(density) { widthPx.toDp() }
    val travel = (widthDp - thumbSize).coerceAtLeast(0.dp)
    val startCenterPx = with(density) { (travel * startFraction + thumbSize / 2).toPx() }
    val endCenterPx = with(density) { (travel * endFraction + thumbSize / 2).toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(enabled, valueRange, steps) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        commit(offset.x)
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    commit(change.position.x)
                    change.consume()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(6.dp)) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(color = colors.surfaceContainerHighest, cornerRadius = radius, size = size)
            drawRoundRect(
                color = if (enabled) colors.primaryContainer else colors.outline,
                cornerRadius = radius,
                size = Size((endCenterPx - startCenterPx).coerceAtLeast(0f), size.height),
                topLeft = Offset(startCenterPx.coerceIn(0f, size.width), 0f),
            )
        }
        RangeThumb(
            modifier = Modifier.align(Alignment.CenterStart).offset(x = travel * startFraction),
            thumbSize = thumbSize,
            active = enabled,
        )
        RangeThumb(
            modifier = Modifier.align(Alignment.CenterStart).offset(x = travel * endFraction),
            thumbSize = thumbSize,
            active = enabled,
        )
    }
}

@Composable
private fun RangeThumb(modifier: Modifier, thumbSize: Dp, active: Boolean) {
    val colors = MiuixTheme.colors
    Box(
        modifier = modifier
            .size(thumbSize)
            .clip(CircleShape)
            .background(colors.surface)
            .border(2.5.dp, if (active) colors.primary else colors.outline, CircleShape),
    )
}

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
import kotlin.math.roundToInt

/**
 * Miuix 滑块：轨道全圆角、thumb 按下放大，可选数值气泡与离散刻度。
 * 区间选择见 [MiuixRangeSlider]。
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
    val colors = MiuixTheme.colors
    val motion = MiuixTheme.motion
    val density = LocalDensity.current
    var widthPx by remember { mutableStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    // 拖动期间用本地值驱动 thumb 与轨道，松手后回落到外部 value。
    // 这样滑杆的跟手完全不依赖「外部状态更新的速度」。
    var dragValue by remember { mutableStateOf<Float?>(null) }
    val shown = dragValue ?: value
    val fraction = ((shown - valueRange.start) / span).coerceIn(0f, 1f)

    val trackHeight = 6.dp
    val thumbIdle = 20.dp
    val thumbSize by animateDpAsState(if (dragging) 24.dp else thumbIdle, motion.gentle(), label = "slider-thumb")
    val widthDp = with(density) { widthPx.toDp() }
    // 行程用「静止尺寸」当基准：按下放大 thumb 的动画不该改变可移动距离，
    // 否则按下瞬间行程收缩、thumb 会被整体往回拽一截 —— 那正是手感上的回弹。
    val travel = (widthDp - thumbIdle).coerceAtLeast(0.dp)
    val travelPx = with(density) { travel.toPx() }.coerceAtLeast(1f)
    val halfThumbPx = with(density) { thumbIdle.toPx() } / 2f
    val thumbCenterPx = with(density) { (travel * fraction + thumbIdle / 2).toPx() }

    // 对外通知的节流时间戳（数组当可变容器，避免为一个时间戳引入额外重组）。
    val lastPush = remember { longArrayOf(0L) }

    fun commit(x: Float, force: Boolean = false) {
        // 先把指针位置换算成 thumb 中心位置再取比例：否则手指在两端时会差半个拇指宽。
        val raw = ((x - halfThumbPx) / travelPx).coerceIn(0f, 1f)
        val snapped = if (steps > 0) {
            val stepCount = steps + 1
            val stepValue = span / stepCount
            valueRange.start + (raw * stepCount).roundToInt() * stepValue
        } else {
            valueRange.start + raw * span
        }
        val next = snapped.coerceIn(valueRange.start, valueRange.endInclusive)
        dragValue = next
        // 拖动中对外通知做节流：滑杆自身 120Hz 跟手，但「全局主题/配置」这类昂贵状态
        // 不必 120Hz 重算（改圆角会牵动整棵 UI 树重组）。松手时再补一次精确值。
        if (force) {
            lastPush[0] = System.currentTimeMillis()
            onValueChange(next)
        } else {
            val t = System.currentTimeMillis()
            if (t - lastPush[0] >= 33L) {
                lastPush[0] = t
                onValueChange(next)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(enabled, valueRange, steps) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset -> commit(offset.x, force = true) }
            }
            .pointerInput(enabled, valueRange, steps) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset -> dragging = true; commit(offset.x, force = true) },
                    onDragEnd = {
                        dragging = false
                        dragValue?.let { onValueChange(it) }
                        dragValue = null
                    },
                    onDragCancel = {
                        dragging = false
                        dragValue?.let { onValueChange(it) }
                        dragValue = null
                    },
                ) { change, _ ->
                    commit(change.position.x)
                    change.consume()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(trackHeight)) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            drawRoundRect(color = colors.surfaceContainerHighest, cornerRadius = radius, size = size)
            drawRoundRect(
                color = if (enabled) colors.primary else colors.outline,
                cornerRadius = radius,
                size = Size(thumbCenterPx.coerceIn(0f, size.width), size.height),
            )
            if (steps > 0) {
                val stepCount = steps + 1
                for (i in 0..stepCount) {
                    val x = size.width * (i.toFloat() / stepCount)
                    drawCircle(color = colors.surface, radius = 2.5f, center = Offset(x, size.height / 2f))
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .offset(x = travel * fraction)
                .size(thumbSize)
                .clip(CircleShape)
                .background(colors.surface)
                .border(2.5.dp, if (enabled) colors.primary else colors.outline, CircleShape),
        )

        if (showBubble && dragging) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = travel * fraction, y = (-32).dp)
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

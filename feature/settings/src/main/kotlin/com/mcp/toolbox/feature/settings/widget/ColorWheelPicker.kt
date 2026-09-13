package com.mcp.toolbox.feature.settings.widget

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** 圆盘位图分辨率。整块一次性渲染，拖动时只移动手柄，不再重算画笔。 */
private const val WHEEL_RES = 512

/** 半径比例低于该值的区域一律吸附为纯白，保证纯白一定点得到。 */
private const val WHITE_SNAP = 0.08f

/**
 * HSV 取色圆盘：角度 = 色相，半径 = 饱和度，圆心吸附纯白。
 *
 * 盘面来自一张逐像素预渲染的位图，因此色相按角度连续变化、饱和度沿半径线性变化，
 * 不会出现 sweepGradient / radialGradient 在 RGB 空间插值造成的色带与断层。
 *
 * 明度不在这块盘里（盘面固定 V=1）。
 *
 * [onColorChange] 只在松手或点击落定时触发一次；拖动中的中间色不回调，避免每帧重建
 * 整套主题配色。拖动中的视觉反馈完全由组件内部状态驱动。
 */
@Composable
fun ColorWheelPicker(
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
) {
    val colors = MiuixTheme.colors
    val wheel = remember { renderWheel(WHEEL_RES) }
    val currentColor by rememberUpdatedState(color)

    val hueState = remember { mutableFloatStateOf(0f) }
    val satState = remember { mutableFloatStateOf(0f) }
    val radiusState = remember { mutableFloatStateOf(0f) }
    val lastTouch = remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(color) {
        val hsv = color.toHsvArray()
        hueState.floatValue = hsv[0]
        satState.floatValue = toRadius(hsv[1])
    }

    fun pick(offset: Offset) {
        val r = radiusState.floatValue
        if (r <= 0f) return
        val dx = offset.x - r
        val dy = offset.y - r
        val dist = (hypot(dx, dy) / r).coerceIn(0f, 1f)
        hueState.floatValue =
            (Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 360f) % 360f
        satState.floatValue = if (dist < WHITE_SNAP) 0f else dist
    }

    fun commit() {
        onColorChange(Color(hsvToRgb(hueState.floatValue, toSaturation(satState.floatValue), 1f)))
    }

    Box(
        modifier = modifier
            .size(size)
            .onSizeChanged { radiusState.floatValue = it.width / 2f }
            .pointerInput(Unit) {
                val touchSlop = viewConfiguration.touchSlop
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        lastTouch.value = down.position
                        var dragging = false
                        drag(down.id) { change ->
                            lastTouch.value = change.position
                            if (!dragging &&
                                (change.position - down.position).getDistance() > touchSlop
                            ) {
                                dragging = true
                            }
                            if (dragging) {
                                pick(change.position)
                                change.consume()
                            }
                        }
                        if (!dragging &&
                            (lastTouch.value - down.position).getDistance() <= touchSlop
                        ) {
                            pick(down.position)
                        }
                        commit()
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            drawImage(
                image = wheel,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(this.size.width.toInt(), this.size.height.toInt()),
            )
            val angle = Math.toRadians(hueState.floatValue.toDouble())
            val handleRadius = r * 0.07f
            val dot = Offset(
                r + (cos(angle) * satState.floatValue * r).toFloat(),
                r + (sin(angle) * satState.floatValue * r).toFloat(),
            )
            drawCircle(Color.White, radius = handleRadius, center = dot)
            drawCircle(colors.outline, radius = handleRadius, center = dot, style = Stroke(2f))
        }
    }
}

/** 逐像素渲染 HSV 圆盘。角度 → 色相，半径 → 饱和度，中心区吸附纯白。 */
private fun renderWheel(res: Int): ImageBitmap {
    val pixels = IntArray(res * res)
    val half = res / 2f
    for (y in 0 until res) {
        val dy = (y + 0.5f - half) / half
        val row = y * res
        for (x in 0 until res) {
            val dx = (x + 0.5f - half) / half
            val distance = sqrt(dx * dx + dy * dy)
            pixels[row + x] = if (distance > 1f) {
                0
            } else {
                val hue = ((atan2(dy, dx).toDouble() * 180.0 / PI).toFloat() + 360f) % 360f
                hsvToRgb(hue, toSaturation(distance), 1f)
            }
        }
    }
    val bitmap = Bitmap.createBitmap(res, res, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, res, 0, 0, res, res)
    return bitmap.asImageBitmap()
}

/** 半径比例 → 饱和度，吸附区内恒为 0。 */
private fun toSaturation(radius: Float): Float =
    if (radius < WHITE_SNAP) 0f else ((radius - WHITE_SNAP) / (1f - WHITE_SNAP)).coerceIn(0f, 1f)

/** 饱和度 → 半径比例，[toSaturation] 的逆运算，用于摆放手柄。 */
private fun toRadius(saturation: Float): Float =
    if (saturation <= 0f) 0f else WHITE_SNAP + saturation.coerceIn(0f, 1f) * (1f - WHITE_SNAP)

private fun Color.toHsvArray(): FloatArray =
    FloatArray(3).also { android.graphics.Color.colorToHSV(toArgb(), it) }

/** HSV → ARGB，避免逐像素调用 HSVToColor 的开销。 */
private fun hsvToRgb(hue: Float, saturation: Float, value: Float): Int {
    val chroma = saturation * value
    val hp = hue / 60f
    val x = chroma * (1f - abs(hp % 2f - 1f))
    var r = 0f
    var g = 0f
    var b = 0f
    when {
        hp < 1f -> { r = chroma; g = x }
        hp < 2f -> { r = x; g = chroma }
        hp < 3f -> { g = chroma; b = x }
        hp < 4f -> { g = x; b = chroma }
        hp < 5f -> { r = x; b = chroma }
        else -> { r = chroma; b = x }
    }
    val m = value - chroma
    return (0xFF shl 24) or
        ((((r + m) * 255f).toInt().coerceIn(0, 255)) shl 16) or
        ((((g + m) * 255f).toInt().coerceIn(0, 255)) shl 8) or
        (((b + m) * 255f).toInt().coerceIn(0, 255))
}

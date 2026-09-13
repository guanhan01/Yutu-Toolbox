package com.mcp.toolbox.core.design.component

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private const val TAU = 6.2831855f

/** 线性相位在 [TIME_BASE] 秒内正好跑满一圈；下表周期都整除它，循环接缝不跳变。 */
private const val TIME_BASE = 1800f

/** 云图位图边长。 */
private const val CLOUD_SIZE = 256

/** 每次取样的正方形边长；平移量限制在 0..(CLOUD_SIZE - SAMPLE_SIZE)，保证取样永不越界。 */
private const val SAMPLE_SIZE = 128

/** 关闭流光时的固定相位：取非 0 也非 0.25，云影分布最不对称。 */
private const val STILL_PHASE = 0.37f

private val X_PERIODS = floatArrayOf(20f, 30f)
private val Y_PERIODS = floatArrayOf(30f, 20f)
private val TINT_MIX = floatArrayOf(0.46f, 0.40f)

private fun sway(phase: Float, periodSeconds: Float, seed: Float): Float =
    TAU * (phase * (TIME_BASE / periodSeconds) + seed)

/**
 * 用四组低频正弦叠加出一张云图。
 *
 * 四个频率分量在 u、v 方向上都取整数周期，所以这张图左右、上下都能无缝对接，
 * 平移取样时不会出现接缝。更关键的是：正弦叠加的结果没有中心、没有边界、
 * 也没有周期性的形状结构，放大铺设后看不出任何一个「东西」在动——这正是
 * 用 radialGradient 永远做不到的（只要中心不透明，它必然是个发光圆）。
 *
 * 噪声值写进 alpha 通道，RGB 统一用给定颜色，叠加时由 alpha 自行混合。
 */
private fun buildCloud(size: Int, random: Random, color: Color): ImageBitmap {
    val phases = FloatArray(4) { random.nextFloat() * TAU }
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(size * size)
    val rgb = color.toArgb() and 0x00FFFFFF
    for (y in 0 until size) {
        val v = y.toFloat() / size
        for (x in 0 until size) {
            val u = x.toFloat() / size
            var s = 0f
            s += sin(TAU * (u + v) + phases[0]) * 0.34f
            s += sin(TAU * (2f * u - v) + phases[1]) * 0.26f
            s += sin(TAU * (u + 2f * v) + phases[2]) * 0.22f
            s += sin(TAU * (3f * u + 2f * v) + phases[3]) * 0.14f
            // 幅度上限合计 0.96；先归一化到 0..1，再平方让色块成团、留出大片底色
            val t = ((s + 0.96f) / 1.92f).coerceIn(0f, 1f)
            val a = (t * t * 255f).toInt().coerceIn(0, 255)
            pixels[y * size + x] = (a shl 24) or rgb
        }
    }
    bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
    return bitmap.asImageBitmap()
}

/**
 * 首页大标题卡的动态云影背景。
 *
 * 底色用 [MiuixTheme] 的 primaryContainer——浅色模式下它是 tone90 的浅色，卡片本身
 * 是亮的，不会在浅色页面里看着像深色主题；深色模式下自动变 tone30。文字一律用
 * onPrimaryContainer，两者是标准对比配对，底色怎么流都不会压到可读性。
 *
 * 流动由两层云图缓慢平移实现：各层的横竖取样用互不相等的周期（20s / 30s）摆动，
 * 色块因此持续飘动，又不会整齐划一地一起走。全程没有任何径向渐变，所以看不到
 * 圆心、圆边，也数不出「几个圆在动」。
 */
@Composable
fun FlowingAuroraBackground(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
) {
    val colors = MiuixTheme.colors

    // 关掉流光时固定在一个不对称的相位上：云影仍是那套无形状的色块，只是不再飘。
    // 静止帧和动画帧共用同一套配色，页面就不会因为关掉动效而掉回深色。
    val phase = if (animated) {
        val value by rememberInfiniteTransition(label = "aurora").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween((TIME_BASE * 1000f).toInt(), easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "aurora-phase",
        )
        value
    } else {
        STILL_PHASE
    }

    val base = colors.primaryContainer
    val clouds = remember(colors) {
        val random = Random(20240914)
        listOf(
            buildCloud(CLOUD_SIZE, random, lerp(base, colors.primary, TINT_MIX[0])),
            buildCloud(CLOUD_SIZE, random, lerp(base, colors.tertiary, TINT_MIX[1])),
        )
    }
    val seeds = remember { List(4) { Random.nextFloat() } }

    Canvas(modifier = modifier) {
        // 底色铺满：云图自带 alpha，叠在纯色底上明暗才有参照
        drawRect(color = base)

        clouds.forEachIndexed { i, cloud ->
            val range = (CLOUD_SIZE - SAMPLE_SIZE).toFloat()
            val ox = ((0.5f + 0.5f * cos(sway(phase, X_PERIODS[i], seeds[i]))) * range).toInt()
            val oy = ((0.5f + 0.5f * sin(sway(phase, Y_PERIODS[i], seeds[i + 2]))) * range).toInt()
            drawImage(
                image = cloud,
                srcOffset = IntOffset(ox, oy),
                srcSize = IntSize(SAMPLE_SIZE, SAMPLE_SIZE),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

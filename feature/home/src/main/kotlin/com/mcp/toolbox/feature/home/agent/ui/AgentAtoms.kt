package com.mcp.toolbox.feature.home.agent.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.shape.SquircleRadii
import com.mcp.toolbox.core.design.shape.sqClip
import com.mcp.toolbox.core.design.shape.sqSurface
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.core.design.token.rememberAgentMotion

/**
 * 流式光标（Spec 第 5.4 节）。
 *
 * - 2dp 宽、行高约 0.9 倍的竖线
 * - `alpha 1→0` 呼吸，530ms 周期，**仅在流式时播放**
 * - 收到新 token 时 [pulseKey] 变化 → 立即重置为全亮（视觉上像心跳）
 *
 * 刻意做成**独立 Composable**：光标闪动只重组它自己，不会带着整段正文一起重组。
 * 这是 Spec 第 5.4 节末明确点名的要求。
 */
@Composable
fun StreamCursor(
    /** 正文每有增量就把它 +1，用来把光标重置为全亮。 */
    pulseKey: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    height: Dp = 18.dp,
) {
    val resolved = if (color == Color.Unspecified) MiuixTheme.colors.primary else color
    val alpha = remember { Animatable(1f) }
    // 时长是主题驱动的，必须在组合期读出来；写进动画 lambda 里会报
    // 「@Composable invocations can only happen from the context of a @Composable function」
    val blinkMs = rememberAgentMotion().cursorBlink()

    // 新 token 到达 → 立刻回满，再开始淡出
    LaunchedEffect(pulseKey, blinkMs) {
        alpha.snapTo(1f)
        alpha.animateTo(
            targetValue = 0.15f,
            animationSpec = tween(durationMillis = blinkMs, easing = LinearEasing),
        )
    }

    Box(
        modifier = modifier
            .width(2.dp)
            .height(height)
            .alpha(alpha.value)
            .sqSurface(resolved, 1.dp),
    )
}

/**
 * 微光扫过覆盖层（Spec 第 6.1 节：每 2.4s 一道 12% 白色高光横向扫过一次）。
 *
 * **只在运行中出现，完成后停止**：它是 `if (running)` 才进入组合的，
 * 一旦结束整棵子树连同 infinite transition 一起被回收，不需要手动 cancel。
 *
 * 覆盖层本身透明，只有那道渐变高光可见；调用方把它叠在内容之上即可。
 */
@Composable
fun ShimmerOverlay(running: Boolean, modifier: Modifier = Modifier) {
    if (!running) return

    val periodMs = rememberAgentMotion().shimmerPeriod()
    val transition = rememberInfiniteTransition(label = "shimmer")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = periodMs, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer-phase",
    )

    // 一趟扫过占周期的 1/4，其余时间高光在画面之外
    val sweep = 0.25f
    val progress = (phase / sweep).coerceAtMost(1f)
    val center = -0.4f + 1.8f * progress

    Box(
        modifier = modifier
            .sqClip(SquircleRadii.card)
            .background(
                Brush.horizontalGradient(
                    0.0f to Color.Transparent,
                    (center - 0.18f).coerceIn(0f, 1f) to Color.Transparent,
                    center.coerceIn(0f, 1f) to Color.White.copy(alpha = 0.12f),
                    (center + 0.18f).coerceIn(0f, 1f) to Color.Transparent,
                    1.0f to Color.Transparent,
                ),
            ),
    )
}

/**
 * 骨架行（Spec 第 7.4 节：网页搜索 3 条骨架，宽度 90% / 70% / 80%，shimmer 1.2s 循环）。
 */
@Composable
fun SkeletonLine(
    widthFraction: Float,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    val base = MiuixTheme.colors.surfaceContainerHigh
    val highlight = MiuixTheme.colors.surfaceContainerHighest

    val transition = rememberInfiniteTransition(label = "skeleton")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Restart,
        ),
        label = "skeleton-phase",
    )

    val startX = -300f + 600f * phase
    val brush = Brush.horizontalGradient(
        colors = listOf(base, highlight, base),
        startX = startX,
        endX = startX + 300f,
    )

    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .sqSurface(base, SquircleRadii.inner)
            .background(brush),
    )
}

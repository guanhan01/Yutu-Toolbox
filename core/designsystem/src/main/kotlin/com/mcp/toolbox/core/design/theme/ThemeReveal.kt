package com.mcp.toolbox.core.design.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.tween
import com.mcp.toolbox.core.design.token.MotionTokens
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import kotlin.math.hypot

/**
 * 主题切换的圆形揭示（circular reveal）状态。
 *
 * 用法：在主题按钮点击处取到按钮中心坐标 → 调用 [LocalThemeRevealTrigger]，
 * 宿主会以该点为圆心用「切换后的背景色」扩散一个圆形覆盖层，动画结束后自然露出新主题。
 */
@Stable
class ThemeRevealState {
    internal val animation = Animatable(0f)
    var origin by mutableStateOf(Offset.Zero)
        internal set
    var revealColor by mutableStateOf(Color.Transparent)
        internal set
}

/** 触发圆形揭示：(圆心屏幕坐标) -> Unit。 */
val LocalThemeRevealTrigger = staticCompositionLocalOf<((Offset) -> Unit)?> { null }

@Composable
fun rememberThemeRevealState(): ThemeRevealState = remember { ThemeRevealState() }

/** 覆盖层宿主：放在内容之上即可。 */
@Composable
fun ThemeRevealHost(state: ThemeRevealState, modifier: Modifier = Modifier) {
    val progress = state.animation.value
    if (progress > 0f) {
        Canvas(modifier = modifier.fillMaxSize()) {
            val maxRadius = hypot(size.width.toDouble(), size.height.toDouble()).toFloat()
            drawCircle(
                color = state.revealColor,
                radius = maxRadius * progress,
                center = state.origin,
            )
        }
    }
}

/** 播放一次揭示动画。 */
suspend fun ThemeRevealState.play(
    origin: Offset,
    color: Color,
    spec: AnimationSpec<Float> = tween(durationMillis = MotionTokens().reveal, easing = FastOutSlowInEasing),
) {
    this.origin = origin
    this.revealColor = color
    animation.snapTo(0f)
    animation.animateTo(
        targetValue = 1f,
        animationSpec = spec,
    )
    animation.snapTo(0f)
}

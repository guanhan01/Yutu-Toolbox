package com.mcp.toolbox.core.design.token

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Immutable

/**
 * 动效 token：统一 spring 曲线与时长倍率，禁止组件里散落 duration 字面量。
 * 规范：spring(StiffnessMediumLow, dampingRatio = 0.9)，页面进入淡入上移 12dp。
 */
@Immutable
data class MotionTokens(
    val scale: Float = 1f,
    val pressScale: Float = 0.98f,
    val pressDarken: Float = 0.08f,
    val enterOffsetDp: Int = 12,
) {
    fun duration(baseMillis: Int): Int = (baseMillis * scale).toInt().coerceIn(0, 2_000)

    val fast: Int get() = duration(150)
    val medium: Int get() = duration(300)
    val slow: Int get() = duration(450)
    val reveal: Int get() = duration(400)

    /** Indeterminate loop duration (spinner cycle). */
    val loop: Int get() = duration(1100)

    /** Skeleton breath half-cycle. */
    val shimmer: Int get() = duration(900)

    fun <T> gentle(): SpringSpec<T> = spring(
        stiffness = Spring.StiffnessMediumLow,
        dampingRatio = 0.9f,
    )

    fun <T> bouncy(): SpringSpec<T> = spring(
        stiffness = Spring.StiffnessMedium,
        dampingRatio = 0.7f,
    )
}

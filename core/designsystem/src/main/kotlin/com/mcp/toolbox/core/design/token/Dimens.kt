package com.mcp.toolbox.core.design.token

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 圆角 token：全部由全局“圆角尺度”派生，禁止在组件里硬编码 dp。
 * 基准 20dp 时 -> sm 10 / md 20 / lg 28 / field 12 / 卡片内圆角 12（外圆角 - 8）。
 */
@Immutable
data class RadiusTokens(val base: Dp = DefaultBase) {
    val sm: Dp = base * 0.5f
    val md: Dp = base
    val lg: Dp = base * 1.4f
    val field: Dp = base * 0.6f
    val card: Dp = base
    val dialog: Dp = base * 1.4f
    val sheet: Dp = base * 1.4f
    val completionCard: Dp = base * 0.8f

    /** 输入栏底板：比卡片更圆，避免高容器看着像矩形。 */
    val composer: Dp = base * 1.4f

    /** 溢出菜单：官方角半径体系里菜单是独立一档（16dp）。 */
    val menu: Dp = base * 0.8f
    val inner: Dp = (base - InnerDelta).coerceAtLeast(0.dp)

    companion object {
        val DefaultBase = 20.dp
        val InnerDelta = 8.dp
    }
}

/** 间距 token：4dp 网格。 */
@Immutable
data class SpacingTokens(
    val grid: Dp = 4.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val pageHorizontal: Dp = 16.dp,
    val groupGap: Dp = 12.dp,
    val rowMinHeight: Dp = 56.dp,
    val rowLargeHeight: Dp = 64.dp,
    val touchTarget: Dp = 48.dp,
)

/** 阴影/描边 token：深色模式用 1dp 高光描边替代阴影。 */
@Immutable
data class ElevationTokens(
    val level0: Dp = 0.dp,
    val level1: Dp = 1.dp,
    val level2: Dp = 2.dp,
    val level3: Dp = 3.dp,
    val darkOutline: Dp = 1.dp,
)

/** 尺寸聚合：随主题（圆角尺度、字号缩放、行高倍率）实时派生。 */
@Immutable
data class MiuixDimens(
    val spacing: SpacingTokens = SpacingTokens(),
    val elevation: ElevationTokens = ElevationTokens(),
    val fontScale: Float = 1f,
    val lineHeightScale: Float = 1f,
)

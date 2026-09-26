package com.mcp.toolbox.core.design.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator as OfficialCircular
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator as OfficialInfinite
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator as OfficialLinear
import top.yukonga.miuix.kmp.basic.ProgressIndicatorColors as OfficialProgressColors

/**
 * 反馈类组件。
 *
 * 全部走官方实现：
 * - 环形 [OfficialCircular] / 线性 [OfficialLinear] 的进度动画
 * - 不确定态用官方 [OfficialInfinite]（轨道 + 环绕圆点），比自绘旋转弧更贴近 Miuix 观感
 * 颜色接项目配色，保证与自绘部分同屏一致。
 */
@Composable
fun MiuixCircularProgress(
    size: Dp = 24.dp,
    color: Color = Color.Unspecified,
    strokeWidth: Dp = 3.dp,
    trackColor: Color = Color.Unspecified,
) {
    val colors = MiuixTheme.colors
    OfficialCircular(
        progress = null,
        colors = OfficialProgressColors(
            foregroundColor = if (color == Color.Unspecified) colors.primary else color,
            disabledForegroundColor = colors.outline,
            backgroundColor = if (trackColor == Color.Unspecified) Color.Transparent else trackColor,
        ),
        strokeWidth = strokeWidth,
        size = size,
    )
}

/** 线性进度：官方轨道 + 填充，高度可调。 */
@Composable
fun MiuixLinearProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
    color: Color = Color.Unspecified,
    trackColor: Color = Color.Unspecified,
) {
    val colors = MiuixTheme.colors
    OfficialLinear(
        modifier = modifier,
        progress = progress.coerceIn(0f, 1f),
        colors = OfficialProgressColors(
            foregroundColor = if (color == Color.Unspecified) colors.primary else color,
            disabledForegroundColor = colors.outline,
            backgroundColor = if (trackColor == Color.Unspecified) colors.surfaceContainerHighest else trackColor,
        ),
        height = height,
    )
}

/** 不确定进度：官方「轨道 + 环绕圆点」动画。 */
@Composable
fun MiuixInfiniteProgress(
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    size: Dp = 20.dp,
    strokeWidth: Dp = 2.dp,
) {
    val colors = MiuixTheme.colors
    OfficialInfinite(
        modifier = modifier,
        color = if (color == Color.Unspecified) colors.primary else color,
        size = size,
        strokeWidth = strokeWidth,
    )
}

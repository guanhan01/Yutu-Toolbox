package com.mcp.toolbox.core.design.component

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** 内容色随容器下发的轻量机制（用于 Filled 按钮内部的 onPrimary 等）。 */
val LocalMiuixContentColor = staticCompositionLocalOf { Color.Unspecified }

@Composable
fun ProvideMiuixContentColor(color: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalMiuixContentColor provides color, content = content)
}

@Composable
fun MiuixText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MiuixTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Ellipsis,
) {
    val local = LocalMiuixContentColor.current
    val resolved = when {
        color != Color.Unspecified -> color
        local != Color.Unspecified -> local
        else -> MiuixTheme.colors.onSurface
    }
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(color = resolved),
        maxLines = maxLines,
        overflow = overflow,
    )
}

/** 当前内容色（供自定义绘制使用）。 */
object MiuixContentColor {
    val current: Color
        @Composable @ReadOnlyComposable get() = LocalMiuixContentColor.current
}

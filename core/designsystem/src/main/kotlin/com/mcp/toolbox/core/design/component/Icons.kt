package com.mcp.toolbox.core.design.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme

/**
 * 统一图标出口：统一尺寸与 tint 解析，一律细线风格（Material Symbols Outlined）。
 */
@Composable
fun MiuixIcon(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 20.dp,
) {
    val resolvedTint = if (tint == Color.Unspecified) MiuixTheme.colors.onSurface else tint
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = resolvedTint,
    )
}

package com.mcp.toolbox.core.design.component

import com.mcp.toolbox.core.design.component.MiuixIconRes
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

/**
 * 矢量资源版图标出口（AI Agent 图标集，364 枚）。
 *
 * 与 [MiuixIcon] 的分工：那个接 `ImageVector`（Material 风格细线图标的既有调用点），
 * 这个接 `@DrawableRes`。新代码一律用这个——图标集是单色的，颜色靠 tint 表达，
 * 并且不占 Dex（矢量 XML 由 AAPT2 编译）。
 */
@Composable
fun MiuixIconRes(
    @androidx.annotation.DrawableRes iconRes: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 20.dp,
) {
    val resolvedTint = if (tint == Color.Unspecified) MiuixTheme.colors.onSurface else tint
    Icon(
        painter = androidx.compose.ui.res.painterResource(iconRes),
        contentDescription = contentDescription,
        modifier = modifier.size(size),
        tint = resolvedTint,
    )
}

/**
 * 资源重载：**调用点不想写参数名时的兜底**。
 *
 * [MiuixIconRes] 与它的差别只有参数名（`iconRes` vs `icon`）。
 * 位置参数调用（`MiuixIcon(AgentIcons.ic_trash, null)`）会命中这里，
 * 所以迁移过的老代码不写参数名也能编译。
 */
@Composable
fun MiuixIcon(
    @androidx.annotation.DrawableRes iconRes: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    size: Dp = 20.dp,
) {
    MiuixIconRes(iconRes, contentDescription, modifier, tint, size)
}

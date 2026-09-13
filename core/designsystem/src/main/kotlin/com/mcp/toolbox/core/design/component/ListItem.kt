package com.mcp.toolbox.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** 1dp 分隔线，默认从文字起始处缩进（列表内分割线规范）。 */
@Composable
fun MiuixDivider(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    Box(
        modifier
            .fillMaxWidth()
            .padding(start = startIndent)
            .height(1.dp)
            .background(MiuixTheme.colors.outlineVariant.copy(alpha = 0.5f)),
    )
}

/**
 * 通用列表项：前导图标 + 标题/副标题 + 尾部控件。
 * 行高 56dp（带副标题 64dp），触控目标 ≥48dp。
 */
@Composable
fun MiuixListItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    showDivider: Boolean = false,
    danger: Boolean = false,
) {
    val colors = MiuixTheme.colors
    val typography = MiuixTheme.typography
    val spacing = MiuixTheme.dimens.spacing
    val press = rememberMiuixPressState(onClick != null)
    val titleColor = if (danger) colors.error else colors.onSurface

    Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = if (subtitle == null) spacing.rowMinHeight else spacing.rowLargeHeight)
                .then(if (onClick != null) Modifier.miuixClickable(press, true, onClick = onClick) else Modifier)
                .padding(horizontal = spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                MiuixIcon(
                    icon = leadingIcon,
                    contentDescription = null,
                    tint = if (danger) colors.error else colors.onSurfaceVariant,
                    size = 22.dp,
                )
                Spacer(Modifier.width(spacing.md))
            }
            if (leading != null) {
                leading()
                Spacer(Modifier.width(spacing.md))
            }
            Column(modifier = Modifier.weight(1f)) {
                MiuixText(text = title, style = typography.bodyLarge, color = titleColor, maxLines = 1)
                if (subtitle != null) {
                    MiuixText(
                        text = subtitle,
                        style = typography.labelMedium,
                        color = if (danger) colors.error.copy(alpha = 0.8f) else colors.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(spacing.md))
                trailing()
            }
        }
        if (showDivider) {
            MiuixDivider(startIndent = if (leadingIcon != null || leading != null) 54.dp else spacing.lg)
        }
    }
}

/** Miuix SuperArrow：设置项标准形态（前导图标 + 标题 + 副标题 + 右侧值 + 箭头）。 */
@Composable
fun MiuixSuperArrow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    valueText: String? = null,
    valueColor: Color = Color.Unspecified,
    showDivider: Boolean = false,
) {
    MiuixListItem(
        title = title,
        subtitle = subtitle,
        leadingIcon = leadingIcon,
        modifier = modifier,
        onClick = onClick,
        showDivider = showDivider,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (valueText != null) {
                    MiuixText(
                        text = valueText,
                        style = MiuixTheme.typography.bodyMedium,
                        color = if (valueColor == Color.Unspecified) MiuixTheme.colors.onSurfaceVariant else valueColor,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                MiuixIcon(
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MiuixTheme.colors.onSurfaceVariant,
                    size = 20.dp,
                )
            }
        },
    )
}

/** Miuix SuperSwitch：整行可点，尾部为开关。 */
@Composable
fun MiuixSuperSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true,
    showDivider: Boolean = false,
) {
    MiuixListItem(
        title = title,
        subtitle = subtitle,
        leadingIcon = leadingIcon,
        modifier = modifier,
        onClick = if (enabled) ({ onCheckedChange(!checked) }) else null,
        showDivider = showDivider,
        trailing = { MiuixSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
    )
}

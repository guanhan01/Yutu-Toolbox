package com.mcp.toolbox.core.design.component

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
import top.yukonga.miuix.kmp.basic.BasicComponent as OfficialBasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults as OfficialBasicComponentDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider as OfficialHorizontalDivider
import top.yukonga.miuix.kmp.basic.SmallTitle as OfficialSmallTitle

/** 1dp 分隔线，默认从文字起始处缩进（列表内分割线规范）。绘制走官方 [OfficialHorizontalDivider]。 */
@Composable
fun MiuixDivider(modifier: Modifier = Modifier, startIndent: Dp = 0.dp) {
    OfficialHorizontalDivider(
        modifier = modifier.padding(start = startIndent),
        thickness = 1.dp,
        color = MiuixTheme.colors.outlineVariant.copy(alpha = 0.5f),
    )
}

/** 分组小标题，走官方 [OfficialSmallTitle]。 */
@Composable
fun MiuixSmallTitle(text: String, modifier: Modifier = Modifier) {
    OfficialSmallTitle(
        text = text,
        modifier = modifier,
        textColor = MiuixTheme.colors.onSurfaceVariant,
    )
}

/**
 * 通用列表项：前导图标 + 标题/副标题 + 尾部控件。
 *
 * 实现走官方 [OfficialBasicComponent] —— 行高、内边距、按压反馈与禁用态判定全部取自
 * [OfficialBasicComponentDefaults]，不再自行规定 56/64dp。
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
    enabled: Boolean = true,
) {
    val colors = MiuixTheme.colors
    val titleColor = if (danger) colors.error else colors.onBackground
    val summaryColor = if (danger) colors.error.copy(alpha = 0.8f) else colors.onSurfaceVariant
    val iconTint = if (danger) colors.error else colors.onSurfaceVariant

    Column {
        OfficialBasicComponent(
            modifier = modifier,
            title = title,
            titleColor = OfficialBasicComponentDefaults.titleColor(color = titleColor),
            summary = subtitle,
            summaryColor = OfficialBasicComponentDefaults.summaryColor(color = summaryColor),
            startAction = when {
                leadingIcon != null -> {
                    {
                        MiuixIcon(
                            icon = leadingIcon,
                            contentDescription = null,
                            tint = iconTint,
                            size = 22.dp,
                        )
                    }
                }
                leading != null -> leading
                else -> null
            },
            endActions = trailing?.let { { it() } },
            onClick = if (enabled) onClick else null,
            enabled = enabled,
        )
        if (showDivider) {
            MiuixDivider(startIndent = if (leadingIcon != null || leading != null) 54.dp else 16.dp)
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
        enabled = enabled,
        trailing = { MiuixSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled) },
    )
}

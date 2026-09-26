package com.mcp.toolbox.core.design.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TextField as OfficialTextField
import top.yukonga.miuix.kmp.basic.TextFieldColors as OfficialTextFieldColors

/**
 * 文本输入。
 *
 * 实现走官方 [OfficialTextField]：官方「聚焦时上浮 label + 描边」动画、官方按压与光标行为。
 * 圆角接项目 token（跟随主题里的圆角滑杆），不写死官方默认的 16dp。
 * prefix/suffix/清除按钮通过官方 leadingIcon / trailingIcon 槽位承载。
 */
@Composable
fun MiuixTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    prefix: String? = null,
    suffix: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxCharCount: Int? = null,
    showClear: Boolean = true,
    /** 输入内容变换；传 [PasswordVisualTransformation] 即为密码框。 */
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val colors = MiuixTheme.colors
    val typography = MiuixTheme.typography
    val radius = MiuixTheme.radius

    val fieldColors = OfficialTextFieldColors(
        backgroundColor = colors.surfaceContainerHigh,
        labelColor = if (isError) colors.error else colors.onSurfaceVariant,
        borderColor = if (isError) colors.error else colors.primary,
    )

    // leading：图标 + 前缀文字，按顺序排（官方只提供一个槽位）
    val leading: (@Composable () -> Unit)? = when {
        leadingIcon != null -> {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiuixIcon(leadingIcon, null, tint = colors.onSurfaceVariant, size = 18.dp)
                    if (prefix != null) {
                        Spacer(Modifier.width(6.dp))
                        MiuixText(prefix, style = typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
        }
        prefix != null -> {
            { MiuixText(prefix, style = typography.bodyMedium, color = colors.onSurfaceVariant) }
        }
        else -> null
    }

    // trailing：后缀文字 + 一键清空
    val trailing: (@Composable () -> Unit)? = when {
        suffix != null || (showClear && value.isNotEmpty() && enabled) -> {
            {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (suffix != null) {
                        MiuixText(suffix, style = typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    if (showClear && value.isNotEmpty() && enabled) {
                        if (suffix != null) Spacer(Modifier.width(6.dp))
                        MiuixIconButton(
                            icon = Icons.Outlined.Close,
                            contentDescription = "清除",
                            onClick = { onValueChange("") },
                            buttonSize = 28.dp,
                            iconSize = 16.dp,
                        )
                    }
                }
            }
        }
        else -> null
    }

    Column(modifier = modifier) {
        OfficialTextField(
            value = value,
            onValueChange = { next ->
                if (maxCharCount == null || next.length <= maxCharCount) onValueChange(next)
            },
            modifier = Modifier.fillMaxWidth(),
            // 官方默认 insideMargin 是 16dp×16dp，单行表单项会偏高；这里收窄到 14×10，
            // 与项目既有行高（48dp 级）对齐。圆角走 token，跟随主题圆角滑杆。
            insideMargin = androidx.compose.ui.unit.DpSize(14.dp, 10.dp),
            colors = fieldColors,
            cornerRadius = radius.field,
            label = placeholder ?: "",
            useLabelAsPlaceholder = true,
            enabled = enabled,
            textStyle = typography.bodyLarge,
            leadingIcon = leading,
            trailingIcon = trailing,
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = visualTransformation,
            cursorBrush = SolidColor(colors.primary),
        )

        if (supportingText != null) {
            MiuixText(
                text = supportingText,
                style = typography.labelSmall,
                color = if (isError) colors.error else colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}

/** 搜索框：官方 docked 形态，带搜索图标与一键清空。 */
@Composable
fun MiuixSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "搜索",
    enabled: Boolean = true,
) {
    MiuixTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        placeholder = placeholder,
        leadingIcon = Icons.Outlined.Search,
        enabled = enabled,
    )
}

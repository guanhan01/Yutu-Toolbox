package com.mcp.toolbox.core.design.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme

/**
 * 文本输入：TextField 12dp 圆角、可选前缀/后缀、清除按钮、错误态与字数计数。
 * 用于 MCP Schema 表单（string 字段）与各模块的搜索/参数输入。
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
) {
    val colors = MiuixTheme.colors
    val typography = MiuixTheme.typography
    val radius = MiuixTheme.radius
    val shape = RoundedCornerShape(radius.field)
    val borderColor = when {
        isError -> colors.error
        else -> colors.outlineVariant
    }

    Column(modifier = modifier) {
        BasicTextField(
            value = value,
            onValueChange = { next ->
                if (maxCharCount == null || next.length <= maxCharCount) onValueChange(next)
            },
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = typography.bodyLarge.copy(color = colors.onSurface),
            cursorBrush = SolidColor(colors.primary),
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.surfaceContainerHigh)
                .defaultMinSize(minHeight = if (singleLine) 48.dp else 96.dp)
                .padding(horizontal = 14.dp, vertical = if (singleLine) 0.dp else 12.dp),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (leadingIcon != null) {
                        MiuixIcon(leadingIcon, null, tint = colors.onSurfaceVariant, size = 18.dp)
                        Box(Modifier.width(8.dp))
                    }
                    if (prefix != null) {
                        MiuixText(
                            text = prefix,
                            style = typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Box(Modifier.width(4.dp))
                    }
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            MiuixText(
                                text = placeholder,
                                style = typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        inner()
                    }
                    if (suffix != null) {
                        Box(Modifier.width(4.dp))
                        MiuixText(
                            text = suffix,
                            style = typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    if (showClear && value.isNotEmpty() && enabled) {
                        Box(Modifier.width(6.dp))
                        MiuixIconButton(
                            icon = Icons.Outlined.Close,
                            contentDescription = "清除",
                            onClick = { onValueChange("") },
                            buttonSize = 28.dp,
                            iconSize = 16.dp,
                        )
                    }
                }
            },
        )

        val errorColor = colors.error
        val help = supportingText
        val counter = maxCharCount?.let { "${value.length}/$it" }
        if (help != null || counter != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, top = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MiuixText(
                    text = help ?: "",
                    style = typography.labelSmall,
                    color = if (isError) errorColor else colors.onSurfaceVariant,
                )
                if (counter != null) {
                    MiuixText(text = counter, style = typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
    }
}

/** 搜索框：docked 胶囊形态，带搜索图标与一键清空。 */
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

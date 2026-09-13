package com.mcp.toolbox.core.design.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme

private val ChipShape = RoundedCornerShape(percent = 50)

/** 筛选 Chip：选中态为主色容器，未选中为描边（文件页顶部 Chip 行）。 */
@Composable
fun MiuixFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    val container = if (selected) colors.primaryContainer else Color.Transparent
    val content = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant

    Box(
        modifier = modifier
            .height(32.dp)
            .clip(ChipShape)
            .background(container)
            .then(if (selected) Modifier else Modifier.border(BorderStroke(1.dp, colors.outlineVariant), ChipShape))
            .miuixClickable(press, true, onClick = onClick)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (leadingIcon != null) MiuixIcon(leadingIcon, null, tint = content, size = 16.dp)
            MiuixText(text = label, style = MiuixTheme.typography.labelLarge, color = content, maxLines = 1)
        }
    }
}

/** 只读态胶囊标签：青绿描边（"只读"、"已解密"、"200 OK" 等状态标签）。 */
@Composable
fun MiuixTag(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    filled: Boolean = false,
) {
    val colors = MiuixTheme.colors
    val tint = if (color == Color.Unspecified) colors.success else color
    Box(
        modifier = modifier
            .height(22.dp)
            .clip(ChipShape)
            .background(if (filled) tint.copy(alpha = 0.16f) else Color.Transparent)
            .then(if (filled) Modifier else Modifier.border(BorderStroke(1.dp, tint.copy(alpha = 0.7f)), ChipShape))
            .padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        MiuixText(text = text, style = MiuixTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/** 建议 Chip：用于搜索建议与 Schema 枚举快捷输入。 */
@Composable
fun MiuixSuggestionChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    Box(
        modifier = modifier
            .height(30.dp)
            .clip(ChipShape)
            .background(colors.surfaceContainerHighest)
            .miuixClickable(press, true, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        MiuixText(text = label, style = MiuixTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1)
    }
}

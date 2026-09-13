package com.mcp.toolbox.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixColors
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** HTTP 方法语义色，抓包 / 网络 / 历史记录共用同一套映射。 */
fun MiuixColors.methodColor(method: String): Color = when (method.uppercase()) {
    "GET" -> success
    "POST" -> primary
    "PUT", "PATCH" -> tertiary
    "DELETE" -> error
    "HEAD", "OPTIONS" -> outline
    else -> secondary
}

/** 状态码语义色：2xx 成功、3xx 提示、4xx 警告、5xx 错误。 */
fun MiuixColors.statusColor(code: Int): Color = when {
    code in 200..299 -> success
    code in 300..399 -> tertiary
    code in 400..499 -> warning
    code in 500..599 -> error
    else -> onSurfaceVariant
}

/** 等宽加粗变体，用于方法名、状态码等需要对齐的数字文本。 */
fun TextStyle.monoBold(): TextStyle =
    copy(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)

/** 请求方法徽标：实色圆角小块 + 白字等宽。 */
@Composable
fun MethodBadge(method: String, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colors
    Box(
        modifier = modifier
            .background(colors.methodColor(method), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        MiuixText(
            text = method.uppercase(),
            style = MiuixTheme.typography.labelSmall.monoBold(),
            color = Color.White,
        )
    }
}

/** 状态码文字：只染色不填充，匹配列表右侧的紧凑排版。 */
@Composable
fun StatusCodeText(code: Int, modifier: Modifier = Modifier) {
    MiuixText(
        text = code.toString(),
        modifier = modifier,
        style = MiuixTheme.typography.labelMedium.monoBold(),
        color = MiuixTheme.colors.statusColor(code),
    )
}

/** 文件与条目的类型色块：彩色圆角方块内嵌图标。 */
@Composable
fun TypeColorTile(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(color, RoundedCornerShape(size * 0.28f)),
        contentAlignment = Alignment.Center,
    ) {
        MiuixIcon(icon = icon, contentDescription = null, tint = Color.White, size = iconSize)
    }
}

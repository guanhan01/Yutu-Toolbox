package com.mcp.toolbox.core.design.token

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * 字体 token。优先 MiSans / HarmonyOS Sans，设备缺失时回退系统默认字体。
 * 字号缩放 0.85-1.3、行高倍率均由主题参数驱动。
 */
@Immutable
data class MiuixTypography(
    val scale: Float = 1f,
    val lineHeightScale: Float = 1f,
    val fontFamily: FontFamily = FontFamily.Default,
    val codeFontFamily: FontFamily = FontFamily.Monospace,
) {
    private fun size(base: Float): TextUnit = (base * scale).sp
    private fun height(base: Float): TextUnit = (base * scale * lineHeightScale).sp

    val displaySmall: TextStyle get() = style(32f, 40f, FontWeight.SemiBold)
    val headlineSmall: TextStyle get() = style(24f, 32f, FontWeight.SemiBold)
    val titleLarge: TextStyle get() = style(20f, 28f, FontWeight.SemiBold)
    val titleMedium: TextStyle get() = style(17f, 24f, FontWeight.Medium)
    val titleSmall: TextStyle get() = style(15f, 20f, FontWeight.Medium)
    val bodyLarge: TextStyle get() = style(15f, 22f, FontWeight.Normal)
    val bodyMedium: TextStyle get() = style(14f, 20f, FontWeight.Normal)
    val bodySmall: TextStyle get() = style(12f, 16f, FontWeight.Normal)
    val labelLarge: TextStyle get() = style(14f, 20f, FontWeight.Medium)
    val labelMedium: TextStyle get() = style(12f, 16f, FontWeight.Medium)
    val labelSmall: TextStyle get() = style(11f, 14f, FontWeight.Medium)
    val code: TextStyle get() = style(13f, 18f, FontWeight.Normal).copy(fontFamily = codeFontFamily)

    private fun style(baseSize: Float, baseHeight: Float, weight: FontWeight): TextStyle =
        TextStyle(
            fontFamily = fontFamily,
            fontWeight = weight,
            fontSize = size(baseSize),
            lineHeight = height(baseHeight),
        )
}

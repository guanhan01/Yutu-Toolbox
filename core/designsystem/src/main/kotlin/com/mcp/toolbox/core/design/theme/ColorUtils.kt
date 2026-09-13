package com.mcp.toolbox.core.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/** HEX <-> Color 互转，主题页 HEX 输入框与取色盘共用。 */
fun Color.toHexString(includeAlpha: Boolean = false): String =
    if (includeAlpha) {
        "#%08X".format(toArgb().toLong() and 0xFFFFFFFFL)
    } else {
        "#%06X".format(toArgb().toLong() and 0xFFFFFFL)
    }

fun String.toColorOrNull(): Color? {
    val raw = trim().removePrefix("#")
    val value = when (raw.length) {
        6 -> raw.toLongOrNull(16)?.or(0xFF000000L)
        8 -> raw.toLongOrNull(16)
        else -> null
    } ?: return null
    return Color(value)
}

/** 调整 HSV 饱和度，用于「色彩活力」临时预览。 */
fun Color.withSaturation(factor: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(toArgb(), hsv)
    hsv[1] = (hsv[1] * factor).coerceIn(0f, 1f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

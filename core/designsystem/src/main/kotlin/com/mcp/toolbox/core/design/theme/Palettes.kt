package com.mcp.toolbox.core.design.theme

import androidx.compose.ui.graphics.Color

/** 预设调色盘包：每套由 Seed 色 + 色彩活力风格生成 light/dark 双配色。 */
data class PalettePreset(
    val id: String,
    val labelZh: String,
    val labelEn: String,
    val seed: Color,
    val style: PaletteStyleSetting = PaletteStyleSetting.TONAL,
)

val PalettePresets: List<PalettePreset> = listOf(
    PalettePreset("miuix_blue", "Miuix 蓝", "Miuix Blue", Color(0xFF3D63F5), PaletteStyleSetting.VIBRANT),
    PalettePreset("monet_purple", "莫奈紫", "Monet Purple", Color(0xFF6750A4)),
    PalettePreset("matcha", "抹茶", "Matcha", Color(0xFF6C9B4F)),
    PalettePreset("coral", "珊瑚", "Coral", Color(0xFFE8645A)),
    PalettePreset("graphite", "石墨", "Graphite", Color(0xFF5B6572), PaletteStyleSetting.NEUTRAL),
    PalettePreset("mint", "薄荷", "Mint", Color(0xFF2FB79A)),
    PalettePreset("sunset", "日落", "Sunset", Color(0xFFF0853C), PaletteStyleSetting.EXPRESSIVE),
    PalettePreset("polar_night", "极夜", "Polar Night", Color(0xFF3A4C7A), PaletteStyleSetting.MUTED),
)

fun presetById(id: String): PalettePreset = PalettePresets.firstOrNull { it.id == id } ?: PalettePresets.first()

/** 取色圆盘下方的候选色块（图 3 主题页标准形态）。 */
val SwatchCandidates: List<Color> = listOf(
    Color(0xFF6750A4),
    Color(0xFF3D63F5),
    Color(0xFF2FB79A),
    Color(0xFF6C9B4F),
    Color(0xFFE8645A),
    Color(0xFF5B6572),
    Color(0xFFF0853C),
)

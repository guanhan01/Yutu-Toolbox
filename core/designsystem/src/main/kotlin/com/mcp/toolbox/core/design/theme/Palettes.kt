package com.mcp.toolbox.core.design.theme

import androidx.compose.ui.graphics.Color

/** 预设主色：主题页右侧的候选色块，点一下即作为 Seed。 */
data class SeedSwatch(
    val id: String,
    val labelZh: String,
    val labelEn: String,
    val color: Color,
    /** 纯白：不是一颗 Seed，而是把 surface 系压白的开关。 */
    val pureWhite: Boolean = false,
)

/**
 * 七个候选：白、蓝、灰、黄、绿、紫（前六颗 Seed）+ 纯白（压白开关）。
 * 「白」给一颗极浅的中性 Seed，用于接近无彩色的浅色主题。
 */
val SeedSwatches: List<SeedSwatch> = listOf(
    SeedSwatch("white", "白", "White", Color(0xFFF2F2F5)),
    SeedSwatch("blue", "蓝", "Blue", Color(0xFF3D63F5)),
    SeedSwatch("gray", "灰", "Gray", Color(0xFF5B6572)),
    SeedSwatch("yellow", "黄", "Yellow", Color(0xFFE8A33D)),
    SeedSwatch("green", "绿", "Green", Color(0xFF3FA46A)),
    SeedSwatch("purple", "紫", "Purple", Color(0xFF6750A4)),
    SeedSwatch("pure_white", "纯白", "Pure white", Color(0xFFFFFFFF), pureWhite = true),
)

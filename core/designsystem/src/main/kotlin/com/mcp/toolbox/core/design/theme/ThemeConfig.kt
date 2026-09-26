package com.mcp.toolbox.core.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** 深浅模式；PER_MODULE 时按 perModuleDark 集合强制部分模块深色。 */
enum class DarkModeSetting { FOLLOW_SYSTEM, LIGHT, DARK, PER_MODULE }

/**
 * 色彩风格：保留官方 [top.yukonga.miuix.kmp.theme.ThemePaletteStyle] 的完整枚举，
 * 但界面不再暴露选择 —— 全站固定 [FIXED_PALETTE_STYLE]（活力）。
 */
enum class PaletteStyleSetting(val labelZh: String, val labelEn: String) {
    TONAL("标准", "Tonal"),
    VIBRANT("活力", "Vibrant"),
    EXPRESSIVE("表现力", "Expressive"),
    NEUTRAL("中性", "Neutral"),
    MONOCHROME("单色", "Monochrome"),
    FIDELITY("还原", "Fidelity"),
    CONTENT("内容", "Content"),
    RAINBOW("彩虹", "Rainbow"),
    FRUIT_SALAD("果缤纷", "Fruit Salad"),
}

/** 全站固定的色彩风格：活力。 */
val FIXED_PALETTE_STYLE: PaletteStyleSetting = PaletteStyleSetting.VIBRANT

/**
 * 主题配置：全部字段持久化在 DataStore，改动即时生效、无需重启。
 *
 * 已收敛的档位（不再由用户设置，避免同一颗 Seed 出多种观感）：
 * - 主题来源：只认 [customSeedArgb]（[dynamicEnabled] 打开时优先跟随系统取色）
 * - 色彩风格：固定活力
 * - 对比度：固定标准（0.0）
 * - 行高倍率：固定 1.0×
 * - 界面风格：只有 Miuix 官方组件一套实现
 */
@Immutable
data class ThemeConfig(
    val darkMode: DarkModeSetting = DarkModeSetting.FOLLOW_SYSTEM,
    val perModuleDark: Set<String> = emptySet(),
    val customSeedArgb: Long = BrandSeedArgb,
    /** 是否跟随系统动态取色（Monet）。关掉则用 [customSeedArgb]。 */
    val dynamicEnabled: Boolean = true,
    val amoled: Boolean = false,
    /** 纯白模式：surface 系压白，主色保持鲜艳。 */
    val pureWhite: Boolean = true,
    /** 全局圆角尺度 0-32dp：卡片=该值，Dialog/Sheet/输入栏=×1.4，输入框=×0.6。 */
    val radiusScaleDp: Float = 20f,
    /** 色彩活力微调 0.5-1.5，乘到调色板 chroma 上。 */
    val saturation: Float = 1f,
    val fontScale: Float = 1f,
    /** 动效时长倍率 0.5-1.5。 */
    val motionScale: Float = 1f,
) {
    companion object {
        const val BrandSeedArgb: Long = 0xFF6750A4
        val BrandSeed: Color get() = Color(BrandSeedArgb)
        const val MinRadius = 0f
        const val MaxRadius = 32f
        const val MinFontScale = 0.85f
        const val MaxFontScale = 1.3f
        const val MinMotionScale = 0.5f
        const val MaxMotionScale = 1.5f
        const val MinSaturation = 0.5f
        const val MaxSaturation = 1.5f
        /** 固定的对比度档位（HCT contrastLevel）。 */
        const val FixedContrastLevel = 0.0
    }
}

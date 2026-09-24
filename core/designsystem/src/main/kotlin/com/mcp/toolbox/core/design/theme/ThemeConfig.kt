package com.mcp.toolbox.core.design.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** 主题来源：默认品牌色 / 莫奈动态取色 / 自定义 Seed / 预设调色盘包 / 纯黑。 */
enum class ThemeSource { BRAND, DYNAMIC, CUSTOM_SEED, PRESET }

/** 深浅模式；PER_MODULE 时按 perModuleDark 集合强制部分模块深色。 */
enum class DarkModeSetting { FOLLOW_SYSTEM, LIGHT, DARK, PER_MODULE }

/** 对比度档位（对应 HCT contrastLevel）。 */
enum class ContrastSetting(val value: Double) {
    STANDARD(0.0), MEDIUM(0.5), HIGH(1.0), EXTRA_HIGH(1.33),
}

/**
 * 色彩风格：与官方 Miuix `ThemePaletteStyle` 的 9 种风格一一对应，
 * 决定同一颗 Seed 派生出的整套配色倾向。
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

/**
 * 主题配置：全部字段持久化在 DataStore，改动即时生效、无需重启。
 */
@Immutable
data class ThemeConfig(
    val source: ThemeSource = ThemeSource.BRAND,
    val darkMode: DarkModeSetting = DarkModeSetting.FOLLOW_SYSTEM,
    val perModuleDark: Set<String> = emptySet(),
    val customSeedArgb: Long = BrandSeedArgb,
    val presetId: String = "miuix_blue",
    val amoled: Boolean = false,
    val dynamicEnabled: Boolean = true,
    val contrast: ContrastSetting = ContrastSetting.STANDARD,
    val paletteStyle: PaletteStyleSetting = PaletteStyleSetting.TONAL,
    /** 全局圆角尺度 0-32dp，卡片=该值，Dialog/Sheet=×1.4，输入框=×0.6。 */
    val radiusScaleDp: Float = 20f,
    /** 色彩活力微调 0.5-1.5，乘到调色板 chroma 上。 */
    val saturation: Float = 1f,
    val fontScale: Float = 1f,
    val lineHeightScale: Float = 1f,
    /** 动效时长倍率 0.5-1.5。 */
    val motionScale: Float = 1f,
    /** 界面风格：经典（自绘）或 Miuix（官方库）。 */
    val uiStyle: UiStyle = UiStyle.MIUIX,
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
    }
}

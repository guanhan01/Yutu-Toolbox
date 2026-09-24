package com.mcp.toolbox.core.design.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.dynamiccolor.DynamicColor
import com.materialkolor.dynamiccolor.MaterialDynamicColors
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette
import com.materialkolor.scheme.DynamicScheme
import com.materialkolor.scheme.SchemeContent
import com.materialkolor.scheme.SchemeExpressive
import com.materialkolor.scheme.SchemeFidelity
import com.materialkolor.scheme.SchemeFruitSalad
import com.materialkolor.scheme.SchemeMonochrome
import com.materialkolor.scheme.SchemeNeutral
import com.materialkolor.scheme.SchemeRainbow
import com.materialkolor.scheme.SchemeTonalSpot
import com.materialkolor.scheme.SchemeVibrant

/**
 * 由 Seed 色生成完整配色方案的核心引擎。
 * 基于 material-color-utilities 的 HCT 空间与 DynamicScheme，
 * 因此「饱和度 / 色彩活力 / 对比度」三档调节都有真实算法依据。
 */
object MiuixColorSchemeFactory {

    /** 成功 / 警告是语义色，M3 方案里没有，用固定色相派生。 */
    private const val SuccessHue = 145.0
    private const val SuccessChroma = 40.0
    private const val WarningHue = 75.0
    private const val WarningChroma = 62.0

    fun dynamicSchemeFor(
        seedArgb: Int,
        isDark: Boolean,
        style: PaletteStyleSetting,
        contrastLevel: Double,
        saturation: Float,
    ): DynamicScheme {
        val seed = Hct.fromInt(seedArgb)
        val chroma = (seed.chroma * saturation).coerceIn(0.0, 120.0)
        val hct = Hct.from(seed.hue, chroma, seed.tone)
        return when (style) {
            PaletteStyleSetting.VIBRANT -> SchemeVibrant(hct, isDark, contrastLevel)
            PaletteStyleSetting.TONAL -> SchemeTonalSpot(hct, isDark, contrastLevel)
            PaletteStyleSetting.EXPRESSIVE -> SchemeExpressive(hct, isDark, contrastLevel)
            PaletteStyleSetting.NEUTRAL -> SchemeNeutral(hct, isDark, contrastLevel)
            PaletteStyleSetting.MONOCHROME -> SchemeMonochrome(hct, isDark, contrastLevel)
            PaletteStyleSetting.FIDELITY -> SchemeFidelity(hct, isDark, contrastLevel)
            PaletteStyleSetting.CONTENT -> SchemeContent(hct, isDark, contrastLevel)
            PaletteStyleSetting.RAINBOW -> SchemeRainbow(hct, isDark, contrastLevel)
            PaletteStyleSetting.FRUIT_SALAD -> SchemeFruitSalad(hct, isDark, contrastLevel)
        }
    }

    fun fromSeed(
        seedArgb: Int,
        isDark: Boolean,
        style: PaletteStyleSetting,
        contrastLevel: Double,
        saturation: Float,
        amoled: Boolean = false,
    ): MiuixColors {
        val scheme = dynamicSchemeFor(seedArgb, isDark, style, contrastLevel, saturation)
        val mdc = MaterialDynamicColors()
        fun c(color: DynamicColor): Color = Color(color.getArgb(scheme))

        val success = TonalPalette.fromHueAndChroma(SuccessHue, SuccessChroma)
        val warning = TonalPalette.fromHueAndChroma(WarningHue, WarningChroma)
        val light = !isDark

        return MiuixColors(
            isDark = isDark,
            amoled = amoled,
            primary = c(mdc.primary()),
            onPrimary = c(mdc.onPrimary()),
            primaryContainer = c(mdc.primaryContainer()),
            onPrimaryContainer = c(mdc.onPrimaryContainer()),
            primaryFixed = c(mdc.primaryFixed()),
            primaryFixedDim = c(mdc.primaryFixedDim()),
            onPrimaryFixed = c(mdc.onPrimaryFixed()),
            onPrimaryFixedVariant = c(mdc.onPrimaryFixedVariant()),
            secondary = c(mdc.secondary()),
            onSecondary = c(mdc.onSecondary()),
            secondaryContainer = c(mdc.secondaryContainer()),
            onSecondaryContainer = c(mdc.onSecondaryContainer()),
            tertiary = c(mdc.tertiary()),
            onTertiary = c(mdc.onTertiary()),
            tertiaryContainer = c(mdc.tertiaryContainer()),
            onTertiaryContainer = c(mdc.onTertiaryContainer()),
            background = c(mdc.background()),
            onBackground = c(mdc.onBackground()),
            surface = c(mdc.surface()),
            onSurface = c(mdc.onSurface()),
            surfaceDim = c(mdc.surfaceDim()),
            surfaceBright = c(mdc.surfaceBright()),
            surfaceContainerLowest = c(mdc.surfaceContainerLowest()),
            surfaceContainerLow = c(mdc.surfaceContainerLow()),
            surfaceContainer = c(mdc.surfaceContainer()),
            surfaceContainerHigh = c(mdc.surfaceContainerHigh()),
            surfaceContainerHighest = c(mdc.surfaceContainerHighest()),
            surfaceVariant = c(mdc.surfaceVariant()),
            onSurfaceVariant = c(mdc.onSurfaceVariant()),
            inverseSurface = c(mdc.inverseSurface()),
            inverseOnSurface = c(mdc.inverseOnSurface()),
            inversePrimary = c(mdc.inversePrimary()),
            outline = c(mdc.outline()),
            outlineVariant = c(mdc.outlineVariant()),
            scrim = c(mdc.scrim()),
            error = c(mdc.error()),
            onError = c(mdc.onError()),
            errorContainer = c(mdc.errorContainer()),
            onErrorContainer = c(mdc.onErrorContainer()),
            success = Color(success.tone(if (light) 40 else 80)),
            onSuccess = Color(success.tone(if (light) 100 else 20)),
            successContainer = Color(success.tone(if (light) 90 else 30)),
            onSuccessContainer = Color(success.tone(if (light) 10 else 90)),
            warning = Color(warning.tone(if (light) 40 else 80)),
            onWarning = Color(warning.tone(if (light) 100 else 20)),
            warningContainer = Color(warning.tone(if (light) 90 else 30)),
            onWarningContainer = Color(warning.tone(if (light) 10 else 90)),
            codeBackground = if (isDark) Color(0xFF121212) else Color(0xFFF4F2F8),
            codeKeyword = if (isDark) Color(0xFFF0A6DC) else Color(0xFF9A2B96),
            codeString = if (isDark) Color(0xFF7BD9B4) else Color(0xFF1B7F6B),
            codeType = if (isDark) Color(0xFF9EC4FF) else Color(0xFF2A5FA8),
            codeComment = if (isDark) Color(0xFF9A94A5) else Color(0xFF7A7583),
        ).let { if (amoled) it.toAmoled() else it }
    }
}

/** AMOLED 纯黑：把 surface/background 压到 #000000，容器整体下压。 */
fun MiuixColors.toAmoled(): MiuixColors = copy(
    isDark = true,
    amoled = true,
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF08080A),
    surfaceContainer = Color(0xFF101013),
    surfaceContainerHigh = Color(0xFF16161A),
    surfaceContainerHighest = Color(0xFF1D1D22),
    codeBackground = Color(0xFF0A0A0C),
)

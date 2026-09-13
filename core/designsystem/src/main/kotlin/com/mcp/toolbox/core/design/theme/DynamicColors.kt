package com.mcp.toolbox.core.design.theme

import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct
import com.materialkolor.palettes.TonalPalette

/**
 * 莫奈动态取色：Android 12+ 读系统壁纸派生的配色方案，
 * 再映射进本工程的 MiuixColors，保证与 Seed 方案同一套 API。
 */
object MiuixDynamicColors {

    private val successPalette = TonalPalette.fromHueAndChroma(145.0, 40.0)
    private val warningPalette = TonalPalette.fromHueAndChroma(75.0, 62.0)

    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun fromSystem(context: Context, isDark: Boolean, amoled: Boolean): MiuixColors? {
        if (!isSupported) return null
        @Suppress("NewApi")
        val m3 = if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        return fromMaterialScheme(m3, isDark, amoled)
    }

    /** 以系统方案的主色为 Seed 生成底稿，再逐项覆盖 M3 已提供的角色。 */
    fun fromMaterialScheme(scheme: ColorScheme, isDark: Boolean, amoled: Boolean): MiuixColors {
        val light = !isDark
        val seedArgb = scheme.primary.toArgb()
        val base = MiuixColorSchemeFactory.fromSeed(
            seedArgb, isDark, PaletteStyleSetting.TONAL, 0.0, 1f, false,
        )
        val seedHct = Hct.fromInt(seedArgb)
        val fixedPalette = TonalPalette.fromHueAndChroma(seedHct.hue, 36.0)

        val colors = base.copy(
            primary = scheme.primary,
            onPrimary = scheme.onPrimary,
            primaryContainer = scheme.primaryContainer,
            onPrimaryContainer = scheme.onPrimaryContainer,
            primaryFixed = Color(fixedPalette.tone(90)),
            primaryFixedDim = Color(fixedPalette.tone(80)),
            onPrimaryFixed = Color(fixedPalette.tone(10)),
            onPrimaryFixedVariant = Color(fixedPalette.tone(30)),
            secondary = scheme.secondary,
            onSecondary = scheme.onSecondary,
            secondaryContainer = scheme.secondaryContainer,
            onSecondaryContainer = scheme.onSecondaryContainer,
            tertiary = scheme.tertiary,
            onTertiary = scheme.onTertiary,
            tertiaryContainer = scheme.tertiaryContainer,
            onTertiaryContainer = scheme.onTertiaryContainer,
            background = scheme.background,
            onBackground = scheme.onBackground,
            surface = scheme.surface,
            onSurface = scheme.onSurface,
            surfaceDim = scheme.surfaceDim,
            surfaceBright = scheme.surfaceBright,
            surfaceContainerLowest = scheme.surfaceContainerLowest,
            surfaceContainerLow = scheme.surfaceContainerLow,
            surfaceContainer = scheme.surfaceContainer,
            surfaceContainerHigh = scheme.surfaceContainerHigh,
            surfaceContainerHighest = scheme.surfaceContainerHighest,
            surfaceVariant = scheme.surfaceVariant,
            onSurfaceVariant = scheme.onSurfaceVariant,
            inverseSurface = scheme.inverseSurface,
            inverseOnSurface = scheme.inverseOnSurface,
            inversePrimary = scheme.inversePrimary,
            outline = scheme.outline,
            outlineVariant = scheme.outlineVariant,
            scrim = scheme.scrim,
            error = scheme.error,
            onError = scheme.onError,
            errorContainer = scheme.errorContainer,
            onErrorContainer = scheme.onErrorContainer,
            success = Color(successPalette.tone(if (light) 40 else 80)),
            onSuccess = Color(successPalette.tone(if (light) 100 else 20)),
            successContainer = Color(successPalette.tone(if (light) 90 else 30)),
            onSuccessContainer = Color(successPalette.tone(if (light) 10 else 90)),
            warning = Color(warningPalette.tone(if (light) 40 else 80)),
            onWarning = Color(warningPalette.tone(if (light) 100 else 20)),
            warningContainer = Color(warningPalette.tone(if (light) 90 else 30)),
            onWarningContainer = Color(warningPalette.tone(if (light) 10 else 90)),
            amoled = false,
        )
        return if (amoled) colors.toAmoled() else colors
    }
}

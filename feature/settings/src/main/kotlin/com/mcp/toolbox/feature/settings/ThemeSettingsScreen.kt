package com.mcp.toolbox.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.mcp.toolbox.feature.settings.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonSize
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixCheckbox
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixFilterChip
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSegmentedButton
import com.mcp.toolbox.core.design.component.MiuixSlider
import com.mcp.toolbox.core.design.component.MiuixSuperArrow
import com.mcp.toolbox.core.design.component.MiuixSuperSwitch
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.MiuixToastState
import com.mcp.toolbox.core.design.component.MiuixToastTone
import com.mcp.toolbox.core.design.theme.ContrastSetting
import com.mcp.toolbox.core.design.theme.DarkModeSetting
import com.mcp.toolbox.core.design.theme.LocalThemeRevealTrigger
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.core.design.theme.PalettePresets
import com.mcp.toolbox.core.design.theme.PaletteStyleSetting
import com.mcp.toolbox.core.design.theme.ThemeConfig
import com.mcp.toolbox.core.design.theme.ThemeSource
import com.mcp.toolbox.core.design.theme.presetById
import com.mcp.toolbox.core.design.theme.toColorOrNull
import com.mcp.toolbox.core.design.theme.toHexString
import com.mcp.toolbox.feature.settings.widget.ColorWheelPicker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.collectAsState

/**
 * 「主题与色彩」页（Theme Studio）：上半屏实时预览，下半屏调色盘与参数滑杆。
 * 所有改动通过 [onConfigChange] 回写 ThemeController，App 全局立即生效、无需重启。
 */
@Composable
fun ThemeSettingsScreen(
    config: ThemeConfig,
    onConfigChange: ((ThemeConfig) -> ThemeConfig) -> Unit,
    modifier: Modifier = Modifier,
    toastState: MiuixToastState? = null,
) {
    val blurContext = LocalContext.current
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val currentSeed = Color(config.customSeedArgb.toInt())
    var hexInput by remember(config.customSeedArgb) { mutableStateOf(currentSeed.toHexString()) }

    // 数据层名称（调色盘风格 / 预设包）自带中英双语，按当前语言取用。
    val isZh = LocalConfiguration.current.locales[0].language == "zh"
    val styleLabels = PaletteStyleSetting.entries.map { if (isZh) it.labelZh else it.labelEn }

    // SegmentedButton 的 label 是非 @Composable lambda，字符串需在此提前解析。
    val sourceLabels = listOf(
        stringResource(R.string.settings_source_default),
        stringResource(R.string.settings_source_monet),
        stringResource(R.string.settings_source_custom),
        stringResource(R.string.settings_source_preset),
    )
    val contrastLabels = listOf(
        stringResource(R.string.settings_contrast_standard),
        stringResource(R.string.settings_contrast_medium),
        stringResource(R.string.settings_contrast_high),
        stringResource(R.string.settings_contrast_very_high),
    )

    // 用 LazyColumn 而不是 Column + verticalScroll：主题页每张卡片都读圆角与配色，
    // 拖动滑杆时若全部留在组合树里会一起重组；LazyColumn 只组合视口内的卡片。
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
        contentPadding = PaddingValues(
            start = spacing.pageHorizontal,
            end = spacing.pageHorizontal,
            top = spacing.sm,
            bottom = 32.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(spacing.groupGap),
    ) {

    item {
            MiuixSectionCard(title = stringResource(R.string.settings_palette_title), subtitle = stringResource(R.string.settings_palette_desc)) {
                Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ColorWheelPicker(
                            color = currentSeed,
                            onColorChange = { picked ->
                                hexInput = picked.toHexString()
                                onConfigChange {
                                    it.copy(source = ThemeSource.CUSTOM_SEED, customSeedArgb = picked.toArgbLong())
                                }
                            },
                            size = 180.dp,
                        )
                    }
                    MiuixTextField(
                        value = hexInput,
                        onValueChange = { raw ->
                            hexInput = raw
                            raw.toColorOrNull()?.let { parsed ->
                                onConfigChange {
                                    it.copy(
                                        source = ThemeSource.CUSTOM_SEED,
                                        customSeedArgb = parsed.toArgbLong(),
                                    )
                                }
                            }
                        },
                        placeholder = "#6750A4",
                        prefix = "HEX",
                        supportingText = stringResource(R.string.settings_hex_hint),
                    )
                }
            }
    }

    item {
            MiuixSectionCard(title = stringResource(R.string.settings_theme_source)) {
                Column(Modifier.padding(spacing.lg), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
                    MiuixSegmentedButton(
                        options = ThemeSource.entries.toList(),
                        selected = config.source,
                        onSelect = { source -> onConfigChange { it.copy(source = source) } },
                        label = { sourceLabels[it.ordinal] },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MiuixSuperArrow(
                        title = stringResource(R.string.settings_preset_packs),
                        subtitle = presetById(config.presetId).let { if (isZh) it.labelZh else it.labelEn },
                        leadingIcon = Icons.Outlined.Palette,
                        valueText = stringResource(R.string.settings_preset_packs_count, PalettePresets.size),
                        onClick = {},
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        PalettePresets.forEach { preset ->
                            MiuixFilterChip(
                                label = if (isZh) preset.labelZh else preset.labelEn,
                                selected = preset.id == config.presetId,
                                onClick = {
                                    onConfigChange { it.copy(presetId = preset.id, source = ThemeSource.PRESET) }
                                },
                            )
                        }
                    }
                }
            }
    }

    item {
            MiuixSectionCard(title = stringResource(R.string.settings_appearance)) {
                Column {
                    // 圆形揭示：以「深浅模式」这一行为圆心扩散新主题背景
                    val revealTrigger = LocalThemeRevealTrigger.current
                    var darkToggleCenter by remember { mutableStateOf(Offset.Zero) }
                    MiuixSuperArrow(
                        title = stringResource(R.string.settings_dark_mode),
                        modifier = Modifier.onGloballyPositioned { coords ->
                            darkToggleCenter = coords.boundsInWindow().center
                        },
                        subtitle = when (config.darkMode) {
                            DarkModeSetting.FOLLOW_SYSTEM -> stringResource(R.string.settings_mode_system)
                            DarkModeSetting.LIGHT -> stringResource(R.string.settings_mode_light)
                            DarkModeSetting.DARK -> stringResource(R.string.settings_mode_dark)
                            DarkModeSetting.PER_MODULE -> stringResource(R.string.settings_mode_forced)
                        },
                        leadingIcon = Icons.Outlined.DarkMode,
                        valueText = stringResource(R.string.settings_mode_desc),
                        onClick = {
                            val next = when (config.darkMode) {
                                DarkModeSetting.FOLLOW_SYSTEM -> DarkModeSetting.LIGHT
                                DarkModeSetting.LIGHT -> DarkModeSetting.DARK
                                DarkModeSetting.DARK -> DarkModeSetting.PER_MODULE
                                DarkModeSetting.PER_MODULE -> DarkModeSetting.FOLLOW_SYSTEM
                            }
                            onConfigChange { it.copy(darkMode = next) }
                            revealTrigger?.invoke(darkToggleCenter)
                        },
                        showDivider = true,
                    )
                    MiuixSuperSwitch(
                        title = stringResource(R.string.settings_dynamic_color),
                        subtitle = stringResource(R.string.settings_dynamic_color_desc),
                        leadingIcon = Icons.Outlined.AutoAwesome,
                        checked = config.dynamicEnabled,
                        onCheckedChange = { value -> onConfigChange { it.copy(dynamicEnabled = value) } },
                        showDivider = true,
                    )
                    MiuixSuperSwitch(
                        title = stringResource(R.string.settings_amoled),
                        subtitle = stringResource(R.string.settings_amoled_desc),
                        leadingIcon = Icons.Outlined.DarkMode,
                        checked = config.amoled,
                        onCheckedChange = { value -> onConfigChange { it.copy(amoled = value) } },
                        showDivider = true,
                    )
                    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
                        MiuixText(
                            text = stringResource(R.string.settings_radius_scale, config.radiusScaleDp.toInt()),
                            style = MiuixTheme.typography.bodyMedium,
                        )
                        MiuixSlider(
                            value = config.radiusScaleDp,
                            onValueChange = { value -> onConfigChange { it.copy(radiusScaleDp = value) } },
                            valueRange = ThemeConfig.MinRadius..ThemeConfig.MaxRadius,
                            showBubble = true,
                        )
                    }
                    MiuixDivider(startIndent = spacing.lg)
                    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
                        MiuixText(text = stringResource(R.string.settings_contrast), style = MiuixTheme.typography.bodyMedium)
                        Spacer(Modifier.height(spacing.sm))
                        MiuixSegmentedButton(
                            options = ContrastSetting.entries.toList(),
                            selected = config.contrast,
                            onSelect = { level -> onConfigChange { it.copy(contrast = level) } },
                            label = { contrastLabels[it.ordinal] },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    MiuixDivider(startIndent = spacing.lg)
                    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
                        MiuixText(text = stringResource(R.string.settings_vibrancy), style = MiuixTheme.typography.bodyMedium)
                        Spacer(Modifier.height(spacing.sm))
                        // 9 种风格横排会挤，改成可横向滚动的 chips
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            PaletteStyleSetting.entries.forEach { style ->
                                MiuixFilterChip(
                                    label = if (isZh) style.labelZh else style.labelEn,
                                    selected = config.paletteStyle == style,
                                    onClick = { onConfigChange { it.copy(paletteStyle = style) } },
                                )
                            }
                        }
                    }
                    MiuixDivider(startIndent = spacing.lg)
                    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
                        MiuixText(
                            text = stringResource(R.string.settings_motion_scale, "%.2f".format(config.motionScale)),
                            style = MiuixTheme.typography.bodyMedium,
                        )
                        MiuixSlider(
                            value = config.motionScale,
                            onValueChange = { value -> onConfigChange { it.copy(motionScale = value) } },
                            valueRange = ThemeConfig.MinMotionScale..ThemeConfig.MaxMotionScale,
                            showBubble = true,
                            valueLabel = { "%.2f×".format(it) },
                        )
                    }
                    MiuixDivider(startIndent = spacing.lg)
                    Column(Modifier.padding(horizontal = spacing.lg, vertical = spacing.md)) {
                        MiuixText(
                            text = stringResource(R.string.settings_font_scale, "%.2f".format(config.fontScale)),
                            style = MiuixTheme.typography.bodyMedium,
                        )
                        MiuixSlider(
                            value = config.fontScale,
                            onValueChange = { value -> onConfigChange { it.copy(fontScale = value) } },
                            valueRange = ThemeConfig.MinFontScale..ThemeConfig.MaxFontScale,
                            showBubble = true,
                            valueLabel = { "%.2f×".format(it) },
                        )
                        MiuixText(
                            text = stringResource(R.string.settings_line_height, "%.2f".format(config.lineHeightScale)),
                            style = MiuixTheme.typography.bodyMedium,
                        )
                        MiuixSlider(
                            value = config.lineHeightScale,
                            onValueChange = { value -> onConfigChange { it.copy(lineHeightScale = value) } },
                            valueRange = 0.9f..1.4f,
                            showBubble = true,
                            valueLabel = { "%.2f×".format(it) },
                        )
                    }
                }
            }
    }

    item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val resetDoneMessage = stringResource(R.string.settings_reset_done)
                MiuixButton(
                    text = stringResource(R.string.settings_reset),
                    onClick = {
                        onConfigChange { ThemeConfig() }
                        toastState?.show(resetDoneMessage, MiuixToastTone.SUCCESS)
                    },
                    variant = MiuixButtonVariant.OUTLINED,
                    leadingIcon = Icons.Outlined.Refresh,
                )
                MiuixIconButton(
                    icon = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.settings_copy_accent),
                    onClick = { toastState?.show("HEX ${currentSeed.toHexString()}", MiuixToastTone.NEUTRAL) },
                )
            }
    }

    }
}

internal fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

package com.mcp.toolbox.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSlider
import com.mcp.toolbox.core.design.component.MiuixSuperArrow
import com.mcp.toolbox.core.design.component.MiuixSuperSwitch
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.MiuixToastState
import com.mcp.toolbox.core.design.component.MiuixToastTone
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.DarkModeSetting
import com.mcp.toolbox.core.design.theme.LocalThemeRevealTrigger
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.core.design.theme.SeedSwatches
import com.mcp.toolbox.core.design.theme.ThemeConfig
import com.mcp.toolbox.core.design.theme.toColorOrNull
import com.mcp.toolbox.core.design.theme.toHexString
import com.mcp.toolbox.feature.settings.widget.ColorWheelPicker

/**
 * 「主题与色彩」页（Theme Studio）。
 *
 * 取舍：主题来源与预设调色盘包已下线，只留一枚 Seed（挑色或动态取色）；
 * 色彩风格固定活力、对比度固定标准、行高倍率固定 1.0×、界面风格只有官方组件一套。
 *
 * 主色区布局：色盘靠左、候选色靠右纵排（白/蓝/灰/黄/绿/紫 + 纯白）。
 * 纯白不是 Seed，而是压白开关，用它时整个页面进入纯白、滑杆与按钮保持鲜艳。
 */
@Composable
fun ThemeSettingsScreen(
    config: ThemeConfig,
    onConfigChange: ((ThemeConfig) -> ThemeConfig) -> Unit,
    modifier: Modifier = Modifier,
    toastState: MiuixToastState? = null,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val currentSeed = Color(config.customSeedArgb.toInt())
    var hexInput by remember(config.customSeedArgb) { mutableStateOf(currentSeed.toHexString()) }
    // 点色块后 HEX 会被同步回填；此时不要再把选中态算回「自定义」
    var seedLocked by remember { mutableStateOf(false) }

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
            MiuixSectionCard(title = stringResource(R.string.settings_seed_title)) {
                Row(
                    modifier = Modifier.padding(spacing.lg),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 色盘靠左
                    ColorWheelPicker(
                        color = currentSeed,
                        onColorChange = { picked ->
                            hexInput = picked.toHexString()
                            onConfigChange { it.copy(customSeedArgb = picked.toArgbLong(), pureWhite = false) }
                        },
                        size = 168.dp,
                    )
                    Spacer(Modifier.width(spacing.lg))
                    // 候选色靠右纵排：白、蓝、灰、黄、绿、紫、纯白
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // 纯白按钮居中：6 个颜色分列两侧。
                        // 深色模式下不显示纯白 —— 纯白强制浅色底，两者同屏会互相否定。
                        SeedSwatches
                            .filter { !(it.pureWhite && config.darkMode == DarkModeSetting.DARK) }
                            .sortedBy { OrderOfSwatch.getValue(it.id) }
                            .forEach { swatch ->
                            SeedSwatchRow(
                                color = swatch.color,
                                label = swatch.labelZh,
                                selected = if (swatch.pureWhite) config.pureWhite
                                else seedLocked && !config.pureWhite &&
                                    swatch.color.toArgb() == currentSeed.toArgb(),
                                onClick = {
                                    seedLocked = true
                                    if (swatch.pureWhite) {
                                        onConfigChange { it.copy(pureWhite = true) }
                                    } else {
                                        onConfigChange {
                                            it.copy(pureWhite = false, customSeedArgb = swatch.color.toArgbLong())
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                MiuixDivider(startIndent = spacing.lg)
                Column(
                    Modifier.padding(
                        start = spacing.lg + 24.dp,
                        end = spacing.lg,
                        top = spacing.md,
                        bottom = spacing.md,
                    ),
                ) {
                    MiuixTextField(
                        value = hexInput,
                        onValueChange = { raw ->
                            hexInput = raw
                            seedLocked = false
                            raw.toColorOrNull()?.let { parsed ->
                                onConfigChange {
                                    it.copy(customSeedArgb = parsed.toArgbLong(), pureWhite = false)
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
                        subtitle = if (config.pureWhite) {
                            // 纯白模式强制浅色，此时再显示「始终深色」会前后矛盾
                            stringResource(R.string.settings_mode_pure_white)
                        } else {
                            when (config.darkMode) {
                                DarkModeSetting.FOLLOW_SYSTEM -> stringResource(R.string.settings_mode_system)
                                DarkModeSetting.LIGHT -> stringResource(R.string.settings_mode_light)
                                DarkModeSetting.DARK -> stringResource(R.string.settings_mode_dark)
                                DarkModeSetting.PER_MODULE -> stringResource(R.string.settings_mode_forced)
                            }
                        },
                        leadingIcon = Icons.Outlined.DarkMode,
                        valueText = stringResource(R.string.settings_mode_desc),
                        onClick = {
                            // 纯白模式下这一行代表「退出纯白」，回到原来的深浅档位
                            if (config.pureWhite) {
                                onConfigChange { it.copy(pureWhite = false) }
                                return@MiuixSuperArrow
                            }
                            val next = when (config.darkMode) {
                                DarkModeSetting.FOLLOW_SYSTEM -> DarkModeSetting.LIGHT
                                DarkModeSetting.LIGHT -> DarkModeSetting.DARK
                                DarkModeSetting.DARK -> DarkModeSetting.PER_MODULE
                                DarkModeSetting.PER_MODULE -> DarkModeSetting.FOLLOW_SYSTEM
                            }
                            // 纯白与深色互斥：切到深色时一并关掉纯白，
                            // 否则纯白会一直把页面压成浅色，深色档位形同虚设。
                            onConfigChange {
                                it.copy(
                                    darkMode = next,
                                    pureWhite = if (next == DarkModeSetting.DARK) false else it.pureWhite,
                                )
                            }
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

/** 单个候选色：左侧圆点色块 + 名称，选中态显示对勾。 */
@Composable
private fun SeedSwatchRow(
    color: Color,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .miuixClickable(press, true, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(color)
                // 白/纯白色块在白底上要能看见边界
                .border(1.dp, colors.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                MiuixIcon(
                    icon = Icons.Filled.Check,
                    contentDescription = null,
                    tint = if (color.luminance() > 0.6f) Color(0xFF1A1A1C) else Color.White,
                    size = 16.dp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        MiuixText(
            text = label,
            style = MiuixTheme.typography.bodyMedium,
            color = if (selected) colors.primary else colors.onSurface,
            maxLines = 1,
        )
    }
}

/** 展示顺序：白 蓝 灰 纯白 黄 绿 紫 —— 纯白落在正中。 */
private val OrderOfSwatch = mapOf(
    "white" to 0, "blue" to 1, "gray" to 2,
    "pure_white" to 3,
    "yellow" to 4, "green" to 5, "purple" to 6,
)

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

internal fun Color.toArgbLong(): Long = toArgb().toLong() and 0xFFFFFFFFL

package com.mcp.toolbox.core.design.component

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.RangeSlider as OfficialRangeSlider
import top.yukonga.miuix.kmp.basic.Slider as OfficialSlider

/**
 * 区间双滑块。
 *
 * 实现走官方 [OfficialRangeSlider]：官方双 thumb 弹簧、磁吸与触感反馈，
 * 不再自绘轨道与 thumb。
 */
@Composable
fun MiuixRangeSlider(
    range: ClosedFloatingPointRange<Float>,
    onRangeChange: (ClosedFloatingPointRange<Float>) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
) {
    OfficialRangeSlider(
        value = range,
        onValueChange = onRangeChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        height = MiuixTheme.dimens.spacing.touchTarget,
    )
}

package com.mcp.toolbox.core.design.component

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.TabRow as OfficialTabRow

/**
 * 分段选择。
 *
 * 实现走官方 [OfficialTabRow]（官方「轨道 + 浮起胶囊」形态与滑动动画），
 * 不再自绘分段轨道。圆角接项目 token，跟随主题圆角滑杆。
 */
@Composable
fun <T> MiuixSegmentedButton(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: (T) -> String = { it.toString() },
) {
    if (options.isEmpty()) return
    // 官方 TabRow 只接受字符串标签，这里把「下标 ↔ 选项」映射在调用侧完成。
    val labels = remember(options, selected) { options.map(label) }
    val index = options.indexOf(selected).coerceAtLeast(0)

    OfficialTabRow(
        tabs = labels,
        selectedTabIndex = index,
        onTabSelected = { i -> options.getOrNull(i)?.let(onSelect) },
        modifier = modifier,
        cornerRadius = MiuixTheme.radius.field,
    )
}

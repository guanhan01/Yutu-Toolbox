package com.mcp.toolbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.navigation.ToolEntries

/**
 * 「应用工具」子页面：集中全部工具入口。
 *
 * 侧边栏底栏的「工具」图标进入这里；首页九宫格仍可直接跳转到具体工具。
 */
@Composable
fun ToolsScreen(
    onOpenTool: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))
        MiuixSectionCard(title = stringResource(R.string.app_drawer_group_tools)) {
            Column {
                ToolEntries.forEachIndexed { index, entry ->
                    MiuixListItem(
                        title = stringResource(entry.labelRes),
                        leadingIcon = entry.icon,
                        showDivider = index != ToolEntries.lastIndex,
                        onClick = { onOpenTool(entry.route) },
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

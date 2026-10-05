package com.mcp.toolbox.feature.home.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixIconRes
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.icon.AgentIcons
import com.mcp.toolbox.core.design.shape.SquircleRadii
import com.mcp.toolbox.core.design.shape.sqSurface
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** 空态首屏：**从不做纯文字空态**（Spec 第 9.7 节），用线性单色插画 + 一行说明。 */
@Composable
fun ChatEmptyState(modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colors
    Box(
        modifier = modifier.fillMaxWidth().padding(vertical = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.layout.Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .sqSurface(colors.primary.copy(alpha = 0.08f), SquircleRadii.panel),
                contentAlignment = Alignment.Center,
            ) {
                MiuixIconRes(
                    iconRes = AgentIcons.ic_ai_assistant,
                    contentDescription = null,
                    tint = colors.primary,
                    size = 36.dp,
                )
            }
            MiuixText(
                text = "开始一段对话",
                style = MiuixTheme.typography.titleMedium,
                color = colors.onSurface,
                modifier = Modifier.padding(top = 16.dp),
            )
            MiuixText(
                text = "可以让它帮你查设备信息、抓包分析、反编译 APK",
                style = MiuixTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .widthIn(max = 280.dp),
            )
        }
    }
}

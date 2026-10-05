package com.mcp.toolbox.feature.network

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.icon.AgentIcons
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSuperArrow
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** 网络模块：三个子工具的入口页。 */
@Composable
fun NetworkHubScreen(
    onBack: () -> Unit,
    onOpenRoute: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        MiuixTopAppBar(
            title = "网络",
            navigationIconRes = AgentIcons.ic_chevron_left,
            onNavigationClick = onBack,
        )
        Column(Modifier.padding(horizontal = spacing.pageHorizontal)) {
            Spacer(Modifier.height(spacing.sm))
            MiuixSectionCard(title = "请求构造", subtitle = "真实发送，不依赖外部抓包工具") {
                MiuixSuperArrow(
                    title = "HTTP 构造器",
                    subtitle = "GET / POST / PUT / DELETE · 响应头与原始报文",
                    leadingIconRes = AgentIcons.ic_globe,
                    onClick = { onOpenRoute("network/http") },
                    showDivider = true,
                )
            }
            Spacer(Modifier.height(spacing.md))
            MiuixSectionCard(title = "连通性诊断", subtitle = "调用系统命令与解析器") {
                MiuixSuperArrow(
                    title = "Ping / Traceroute",
                    subtitle = "逐行实时回显",
                    leadingIconRes = AgentIcons.ic_svc_load_balancer,
                    onClick = { onOpenRoute("network/ping") },
                    showDivider = true,
                )
                MiuixSuperArrow(
                    title = "DNS 查询",
                    subtitle = "正向解析、反向解析与可达性",
                    leadingIconRes = AgentIcons.ic_svc_dns,
                    onClick = { onOpenRoute("network/dns") },
                    showDivider = true,
                )
                MiuixSuperArrow(
                    title = "端口扫描",
                    subtitle = "TCP connect 并发扫描，仅限自有或已授权主机",
                    leadingIconRes = AgentIcons.ic_svc_load_balancer,
                    onClick = { onOpenRoute("network/port-scan") },
                    showDivider = true,
                )
                MiuixSuperArrow(
                    title = "Whois / RDAP",
                    subtitle = "域名与 IP 的注册信息（rdap.org）",
                    leadingIconRes = AgentIcons.ic_globe,
                    onClick = { onOpenRoute("network/whois") },
                    showDivider = true,
                )
                MiuixSuperArrow(
                    title = "网络环境",
                    subtitle = "链路详情、DNS、网卡与带宽估计",
                    leadingIconRes = AgentIcons.ic_orchestration,
                    onClick = { onOpenRoute("network/env") },
                )
            }
            Spacer(Modifier.height(spacing.md))
            MiuixText(
                text = "所有请求与解析结果只在本机处理，不上传任何数据。",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

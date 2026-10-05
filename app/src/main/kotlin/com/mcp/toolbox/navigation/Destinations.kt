package com.mcp.toolbox.navigation

import com.mcp.toolbox.core.design.icon.AgentIcons
import androidx.annotation.StringRes
import com.mcp.toolbox.R

data class Destination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: Int,
    val badge: Int = 0,
    val implStage: String = "P0",
)

object Routes {
    const val HOME = "home"
    const val MEMORY = "memory"
    const val TOOLS = "tools"
    const val APPS = "apps"
    const val WEB = "web"
    const val NETWORK = "network"
    const val NETWORK_HTTP = "network/http"
    const val NETWORK_PING = "network/ping"
    const val NETWORK_DNS = "network/dns"
    const val NETWORK_PORT_SCAN = "network/port-scan"
    const val NETWORK_WHOIS = "network/whois"
    const val NETWORK_ENV = "network/env"
    const val DATABASE = "database"
    const val CAPTURE = "capture"
    const val DECOMPILE = "decompile"
    const val MCP = "mcp"
    const val ARTIFACTS = "artifacts"
    const val SKILLS = "skills"
    const val SETTINGS = "settings"
    const val AI_SETTINGS = "settings/ai"
    const val AI_PROVIDERS = "settings/ai/providers"
    const val AI_PROVIDER_DETAIL = "settings/ai/provider"
    const val AI_MODELS = "settings/ai/models"
    const val LINUX = "settings/ai/linux"
    const val LINUX_CHECK = "settings/ai/linux/check"
    const val LINUX_TERMINAL = "settings/ai/linux/terminal"
    const val LINUX_FILES = "settings/ai/linux/files"
    const val LINUX_SHARED = "settings/ai/linux/shared"
    const val SETTINGS_THEME = "settings/theme"
    const val SETTINGS_PRIVILEGE = "settings/privilege"
    const val ABOUT = "about"
}

/** 抽屉一级入口：只保留首页，工具统一收进「应用工具」子页面。 */
val DrawerPrimary: List<Destination> = listOf(
    Destination(Routes.HOME, R.string.app_nav_home, AgentIcons.ic_globe),
)

/** 「应用工具」子页面里的工具入口。 */
/**
 * 「应用工具」页面的入口。
 *
 * 这里只列**面向用户的功能页**。以下页面已从入口移除，但路由与实现保留：
 *  - 网络 / 数据库：都是开发者向的工具，且 AI 侧已有等价能力
 *    （http.request、net.info、db.* / sql.query），不再单独占用入口。
 *  - 反编译：面向用户的编辑器入口去掉；反编译能力本身仍完整保留给 AI
 *    （apk.* / dex.* / build.* 共约二十个工具），不影响对话里使用。
 *  - MCP 产物：属于 MCP 服务的附属目录，入口集中到 MCP 页面内部。
 */
val ToolEntries: List<Destination> = listOf(
    Destination(Routes.APPS, R.string.app_nav_apps, AgentIcons.ic_phone_device, implStage = "P2"),
    Destination(Routes.WEB, R.string.app_nav_web, AgentIcons.ic_globe, implStage = "P3"),
    Destination(Routes.CAPTURE, R.string.app_nav_capture, AgentIcons.ic_speed, implStage = "P5"),
    Destination(Routes.MCP, R.string.app_nav_mcp, AgentIcons.ic_orchestration, implStage = "P7"),
)

/** 「网络」二级可折叠菜单。 */
val DrawerNetworkChildren: List<Destination> = listOf(
    Destination(Routes.NETWORK_HTTP, R.string.app_nav_network_http, AgentIcons.ic_globe, implStage = "P3"),
    Destination(Routes.NETWORK_PING, R.string.app_nav_network_ping, AgentIcons.ic_svc_load_balancer, implStage = "P3"),
    Destination(Routes.NETWORK_DNS, R.string.app_nav_network_dns, AgentIcons.ic_svc_dns, implStage = "P3"),
    Destination(Routes.NETWORK_PORT_SCAN, R.string.app_nav_network_port_scan, AgentIcons.ic_svc_load_balancer, implStage = "P3"),
    Destination(Routes.NETWORK_WHOIS, R.string.app_nav_network_whois, AgentIcons.ic_globe, implStage = "P3"),
    Destination(Routes.NETWORK_ENV, R.string.app_nav_network_env, AgentIcons.ic_orchestration, implStage = "P3"),
)

/** 抽屉底部固定项。 */
val DrawerFooter: List<Destination> = listOf(
    Destination(Routes.ABOUT, R.string.app_nav_about, AgentIcons.ic_info),
    Destination(Routes.TOOLS, R.string.app_drawer_group_tools, AgentIcons.ic_svc_project_board),
    Destination(Routes.SKILLS, R.string.app_nav_skills, AgentIcons.ic_plugin),
    Destination(Routes.AI_SETTINGS, R.string.app_nav_ai, AgentIcons.ic_ai_sparkle),
    Destination(Routes.SETTINGS, R.string.app_nav_settings, AgentIcons.ic_settings),
)


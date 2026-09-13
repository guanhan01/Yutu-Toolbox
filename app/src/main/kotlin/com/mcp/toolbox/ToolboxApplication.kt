package com.mcp.toolbox

import android.app.Application
import com.mcp.toolbox.core.design.theme.ThemeController
import com.mcp.toolbox.feature.mcp.BuiltInMcpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * 应用级容器。
 * IMPL-NOTE: P0 阶段用最小手写容器（仅持有 ThemeController）；
 * P1 阶段替换为 Koin 模块化装配，保持 ThemeController 单例语义不变。
 */
class ToolboxApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val themeController: ThemeController by lazy {
        ThemeController.create(this, appScope)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // IMPL-NOTE: 尽早载入内置 Server 配置。token 的「固定文件」在 app 私有目录之外，
        // 清理数据 / 卸载重装后必须在这里恢复；不能等用户打开 MCP 页面（Server 服务可能自启）。
        runCatching { BuiltInMcpServer.load(this) }
    }

    companion object {
        lateinit var instance: ToolboxApplication
            private set
    }
}

package com.mcp.toolbox

import android.app.Application
import com.mcp.toolbox.core.design.theme.ThemeController
import com.mcp.toolbox.feature.mcp.BuiltInMcpServer
import com.mcp.toolbox.feature.mcp.SkillStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.mcp.toolbox.feature.mcp.BuiltInToolSet
import com.mcp.toolbox.feature.home.ChatMemoryStore
import com.mcp.toolbox.feature.home.PlanStore
import com.mcp.toolbox.ui.ai.AgentStateTools
import com.mcp.toolbox.ui.linux.LinuxMcpTools

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
        // 把 Linux 环境的能力接进 MCP 工具集。LinuxRuntime 在 app 模块，
        // 只能从这里注入，feature:mcp 不反向依赖。
        runCatching { BuiltInToolSet.registerExtra { ctx -> LinuxMcpTools.all(ctx) } }
        // 计划模式与记忆：这两个工具要读写会话数据层（feature:home），
        // 同样只能从 app 模块注入。
        BuiltInToolSet.registerExtra { ctx -> AgentStateTools.all(ctx) }
        // load 是挂起函数，这里不能阻塞主线程：注册是同步的，数据在用户发出
        // 第一条消息前就绪即可（工具只有在请求发生后才被调用）。
        appScope.launch {
            runCatching { ChatMemoryStore.load(this@ToolboxApplication) }
            runCatching { PlanStore.load(this@ToolboxApplication) }
            // Skill 列表与启用状态：AI 系统提示要用它，必须在第一条消息之前就绪
            runCatching { SkillStore.refresh(this@ToolboxApplication) }
        }
    }

    companion object {
        lateinit var instance: ToolboxApplication
            private set
    }
}

package com.mcp.toolbox.ui.ai

/** 在服务商列表与配置页之间传递选中项，避免为单个参数引入带参路由。 */
object AiHub {
    var pendingProvider: String? = null
}

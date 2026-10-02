package com.mcp.toolbox.ui.ai

/** 在服务商列表与配置页之间传递选中项，避免为单个参数引入带参路由。 */
object AiHub {
    var pendingProvider: String? = null

    /**
     * 正在编辑的自定义供应商 id。
     *
     * 为 null 且 [pendingProvider] 是 CUSTOM 时表示「新增一条」；
     * 非 null 表示编辑已存在的那条。用同一个配置页承载两种意图。
     */
    var pendingCustomId: String? = null
}

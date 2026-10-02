package com.mcp.toolbox.feature.mcp

/**
 * MCP 协议侧常量。
 *
 * 原先这两个值挂在 `BuiltInMcpServer` 上——客户端（对外发起连接的一方）与
 * 服务端共用了同一份定义。内置 Server 移除后，客户端仍在用，因此独立出来。
 */
object McpProtocol {
    /** 客户端在与服务端 initialize 握手时声明的协议版本。 */
    const val PROTOCOL_VERSION = "2025-06-18"

    /** 客户端自身的实现版本，写进 clientInfo。 */
    const val CLIENT_VERSION = "0.1.0"

    /** HTTP 请求的 User-Agent 前缀。 */
    const val USER_AGENT = "MCPToolbox"
}

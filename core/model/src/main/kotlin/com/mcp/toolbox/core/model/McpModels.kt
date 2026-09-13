package com.mcp.toolbox.core.model

/** MCP 传输方式：stdio 需要 Shizuku/root/Termux 桥接，HTTP/SSE 纯网络即可。 */
enum class McpTransport { STDIO, HTTP, SSE }

/** 一个外部 MCP Server 的连接配置。 */
data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: McpTransport,
    val endpoint: String = "",
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    val autoConnect: Boolean = false,
    val healthCheckIntervalSeconds: Int = 30,
    val lastConnectedAt: Long? = null,
    val lastError: String? = null,
)

enum class McpConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, FAILED }

/** 危险等级：DANGEROUS 的工具调用前必须二次确认。 */
enum class ToolDangerLevel { SAFE, CAUTION, DANGEROUS }

/** tools/list 结果；schema 保留原始 JSON 字符串，交给表单生成器解析。 */
data class McpToolDescriptor(
    val serverId: String,
    val name: String,
    val title: String = "",
    val description: String = "",
    val inputSchemaJson: String = "{}",
    val dangerLevel: ToolDangerLevel = ToolDangerLevel.SAFE,
)

data class McpResourceDescriptor(
    val serverId: String,
    val uri: String,
    val name: String = "",
    val mimeType: String = "text/plain",
)

data class McpPromptArgument(
    val name: String,
    val description: String = "",
    val required: Boolean = false,
)

data class McpPromptDescriptor(
    val serverId: String,
    val name: String,
    val description: String = "",
    val arguments: List<McpPromptArgument> = emptyList(),
)

enum class ToolCallStatus { RUNNING, SUCCESS, FAILED, TIMEOUT, CANCELLED }

/** 一次工具调用的完整记录，可导出、可回放到 MCP 产物目录。 */
data class ToolCallRecord(
    val id: String,
    val serverId: String,
    val serverName: String,
    val toolName: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val durationMillis: Long = 0L,
    val status: ToolCallStatus = ToolCallStatus.RUNNING,
    val argumentsJson: String = "{}",
    val resultJson: String? = null,
    val errorMessage: String? = null,
    val artifactSessionId: String? = null,
)

/** JSON Schema 字段类型，用于 Schema -> 表单控件映射。 */
enum class SchemaFieldType { STRING, NUMBER, INTEGER, BOOLEAN, ENUM, ARRAY, OBJECT, UNKNOWN }

package com.mcp.toolbox.core.model

/** MCP 输出文件夹（Artifacts 工作区）的命名与分目录策略。 */
enum class ArtifactLayout { FLAT, SERVER_TOOL, DATE_TOOL }

data class ArtifactSettings(
    val enabled: Boolean = false,
    val rootPath: String = "",
    val layout: ArtifactLayout = ArtifactLayout.SERVER_TOOL,
    val nameTemplate: String = "{tool}_{timestamp}_{seq}",
    val maxTotalBytes: Long = 1024L * 1024 * 1024,
    val retentionDays: Int = 7,
    val redactSensitiveFields: Boolean = true,
    val writeThumbnails: Boolean = true,
)

enum class ArtifactSessionStatus { SUCCESS, FAILED, PARTIAL }

/** 一次 MCP 会话（= 一次工具调用）的落盘目录索引。 */
data class ArtifactSession(
    val id: String,
    val serverName: String,
    val toolName: String,
    val startedAt: Long,
    val status: ArtifactSessionStatus,
    val durationMillis: Long,
    val outputCount: Int,
    val totalBytes: Long,
    val directory: String,
)

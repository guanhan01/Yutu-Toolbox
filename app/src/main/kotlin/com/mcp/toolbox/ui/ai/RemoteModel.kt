package com.mcp.toolbox.ui.ai

/**
 * 从模型列表接口里解析出的模型信息。
 *
 * 上下文上限一律取**服务端返回的真实值**，不做猜测：拿不到就留空，
 * 由用户在模型编辑里手填（[ModelEntry.contextWindow]）。
 */
data class RemoteModel(
    val id: String,
    val displayName: String = "",
    val contextWindow: Int? = null,
    val supportsReasoning: Boolean = false,
)

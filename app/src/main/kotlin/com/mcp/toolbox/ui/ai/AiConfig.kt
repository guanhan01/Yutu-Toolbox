package com.mcp.toolbox.ui.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 支持的 AI 服务商。 */
enum class AiProvider(
    val title: String,
    val badge: String,
    val tintArgb: Long,
    val baseUrl: String,
    val defaultModel: String,
    val docsHint: String,
    @androidx.annotation.DrawableRes val iconRes: Int = 0,
) {
    OPENAI("OpenAI", "AI", 0xFF10A37F, "https://api.openai.com/v1", "gpt-4o", "platform.openai.com", com.mcp.toolbox.R.drawable.ic_brand_openai),
    ANTHROPIC("Anthropic Claude", "C", 0xFFD97757, "https://api.anthropic.com", "claude-sonnet-4-20250514", "console.anthropic.com", com.mcp.toolbox.R.drawable.ic_brand_anthropic),
    GEMINI("Google Gemini", "G", 0xFF4285F4, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash", "aistudio.google.com", com.mcp.toolbox.R.drawable.ic_brand_gemini),
    DEEPSEEK("DeepSeek", "D", 0xFF4D6BFE, "https://api.deepseek.com/v1", "deepseek-chat", "platform.deepseek.com", com.mcp.toolbox.R.drawable.ic_brand_deepseek),
    MOONSHOT("月之暗面 Kimi", "K", 0xFF1F1F1F, "https://api.moonshot.cn/v1", "moonshot-v1-8k", "platform.moonshot.cn", com.mcp.toolbox.R.drawable.ic_brand_kimi),
    ZHIPU("智谱 GLM", "Z", 0xFF3859FF, "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus", "open.bigmodel.cn", com.mcp.toolbox.R.drawable.ic_brand_zhipu),
    DASHSCOPE("通义千问", "Q", 0xFF615CED, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "bailian.console.aliyun.com", com.mcp.toolbox.R.drawable.ic_brand_qwen),
    BAIDU("百度文心", "W", 0xFF2932E1, "https://qianfan.baidubce.com/v2", "ernie-4.0-8k", "console.bce.baidu.com", com.mcp.toolbox.R.drawable.ic_brand_yiyan),
    HUNYUAN("腾讯混元", "H", 0xFF0052D9, "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbo", "console.cloud.tencent.com", com.mcp.toolbox.R.drawable.ic_brand_hunyuan),
    SPARK("讯飞星火", "S", 0xFF1E63FF, "https://spark-api-open.xf-yun.com/v1", "generalv3.5", "console.xfyun.cn", 0),
    XAI("xAI Grok", "X", 0xFF1D1D1F, "https://api.x.ai/v1", "grok-2-latest", "console.x.ai", com.mcp.toolbox.R.drawable.ic_brand_xai),
    MISTRAL("Mistral", "M", 0xFFFF7000, "https://api.mistral.ai/v1", "mistral-large-latest", "console.mistral.ai", com.mcp.toolbox.R.drawable.ic_brand_mistral),
    CUSTOM("自定义（OpenAI 兼容）", "+", 0xFF6B7280, "", "", ""),
    ;

    val isCustom: Boolean get() = this == CUSTOM
}

/** 思考档位。 */
enum class ReasoningEffort(val label: String, val apiValue: String?) {
    OFF("Off", "none"),
    DEFAULT("Default", null),
    MINIMAL("Minimal", "minimal"),
    LOW("Low", "low"),
    MEDIUM("Medium", "medium"),
    HIGH("High", "high"),
    XHIGH("XHigh", "xhigh"),
    MAX("Max", "max"),
}

/** 一个模型。 */
data class ModelEntry(
    val id: String,
    val displayName: String = "",
    val contextWindow: Int? = null,
    val supportsReasoning: Boolean = false,
) {
    val label: String get() = displayName.ifBlank { id }
}

/**
 * 单个服务商的配置。
 *
 * 每个服务商各存一份：密钥、地址、模型、请求头、系统提示都互不影响，
 * 切换服务商不会串用上一个的凭据。
 */
data class ProviderConfig(
    val provider: AiProvider,
    val baseUrl: String = "",
    val apiKey: String = "",
    val models: List<ModelEntry> = emptyList(),
    val selectedModel: String = "",
    val reasoning: ReasoningEffort = ReasoningEffort.DEFAULT,
    val temperature: Float = 0.7f,
    val customHeaders: Map<String, String> = emptyMap(),
    val systemPrompt: String = "",
    val enabled: Boolean = true,
) {
    val ready: Boolean get() = baseUrl.isNotBlank() && selectedModel.isNotBlank() && apiKey.isNotBlank()
}

/** 全局配置：当前服务商 + 各服务商各自的配置。 */
data class AiConfig(
    val current: AiProvider = AiProvider.OPENAI,
    val perProvider: Map<AiProvider, ProviderConfig> = emptyMap(),
) {
    val active: ProviderConfig
        get() = perProvider[current] ?: ProviderConfig(
            provider = current,
            baseUrl = current.baseUrl,
            selectedModel = current.defaultModel,
        )

    val ready: Boolean get() = active.ready
    val baseUrl: String get() = active.baseUrl
    val apiKey: String get() = active.apiKey
    val model: String get() = active.selectedModel
    val reasoning: ReasoningEffort get() = active.reasoning
    val temperature: Float get() = active.temperature
    val customHeaders: Map<String, String> get() = active.customHeaders
    val systemPrompt: String get() = active.systemPrompt
    val enabled: Boolean get() = active.enabled
    val cachedModels: List<String> get() = active.models.map { it.id }

    /** 某个服务商已填写的密钥（列表里用于显示已配置状态）。 */
    fun apiKeyOf(provider: AiProvider): String =
        perProvider[provider]?.apiKey.orEmpty()
}

object AiConfigStore {

    private const val PREFS = "ai-config-v2"
    private const val KEY_PAYLOAD = "payload"

    private val _config = MutableStateFlow(AiConfig())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PAYLOAD, null)
        _config.value = if (raw.isNullOrBlank()) {
            AiConfig()
        } else {
            runCatching { ConfigCodec.decode(context, raw) }.getOrElse { AiConfig() }
        }
        loaded = true
    }

    fun selectProvider(context: Context, provider: AiProvider) {
        update(context) { it.copy(current = provider) }
    }

    fun updateActive(context: Context, change: (ProviderConfig) -> ProviderConfig) {
        update(context) { cfg ->
            cfg.copy(perProvider = cfg.perProvider + (cfg.current to change(cfg.active)))
        }
    }

    fun update(context: Context, change: (AiConfig) -> AiConfig) {
        _config.value = change(_config.value)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAYLOAD, ConfigCodec.encode(context, _config.value))
            .apply()
    }

    fun selectModel(context: Context, model: String) =
        updateActive(context) { it.copy(selectedModel = model) }

    fun selectReasoning(context: Context, effort: ReasoningEffort) =
        updateActive(context) { it.copy(reasoning = effort) }
}

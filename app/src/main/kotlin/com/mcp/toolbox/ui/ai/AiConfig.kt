package com.mcp.toolbox.ui.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 支持的 AI 服务商。
 *
 * `baseUrl` 均为各家的 OpenAI 兼容端点（Anthropic / Gemini 另有原生协议，
 * 这里先按兼容层给出，接入时再按需扩展），`custom` 允许用户自行填写地址与模型。
 */
enum class AiProvider(
    val title: String,
    val baseUrl: String,
    val defaultModel: String,
    val docsHint: String,
) {
    OPENAI("OpenAI", "https://api.openai.com/v1", "gpt-4o", "platform.openai.com"),
    ANTHROPIC("Anthropic Claude", "https://api.anthropic.com/v1", "claude-sonnet-4-20250514", "console.anthropic.com"),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash", "aistudio.google.com"),
    DEEPSEEK("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat", "platform.deepseek.com"),
    MOONSHOT("月之暗面 Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k", "platform.moonshot.cn"),
    ZHIPU("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus", "open.bigmodel.cn"),
    DASHSCOPE("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "bailian.console.aliyun.com"),
    BAIDU("百度文心", "https://qianfan.baidubce.com/v2", "ernie-4.0-8k", "console.bce.baidu.com"),
    HUNYUAN("腾讯混元", "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbo", "console.cloud.tencent.com"),
    SPARK("讯飞星火", "https://spark-api-open.xf-yun.com/v1", "generalv3.5", "console.xfyun.cn"),
    XAI("xAI Grok", "https://api.x.ai/v1", "grok-2-latest", "console.x.ai"),
    MISTRAL("Mistral", "https://api.mistral.ai/v1", "mistral-large-latest", "console.mistral.ai"),
    CUSTOM("自定义（OpenAI 兼容）", "", "", ""),
    ;

    val isCustom: Boolean get() = this == CUSTOM
}

/** AI 接入配置。密钥只存在本机 SharedPreferences 中，不上传。 */
data class AiConfig(
    val provider: AiProvider = AiProvider.OPENAI,
    val baseUrl: String = AiProvider.OPENAI.baseUrl,
    val apiKey: String = "",
    val model: String = AiProvider.OPENAI.defaultModel,
    val temperature: Float = 0.7f,
) {
    /** 是否已具备发起请求的最小条件。 */
    val ready: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
}

object AiConfigStore {

    private const val PREFS = "ai-config"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_BASE_URL = "baseUrl"
    private const val KEY_API_KEY = "apiKey"
    private const val KEY_MODEL = "model"
    private const val KEY_TEMP = "temperature"

    private val _config = MutableStateFlow(AiConfig())
    val config: StateFlow<AiConfig> = _config.asStateFlow()

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val provider = runCatching {
            AiProvider.valueOf(p.getString(KEY_PROVIDER, null) ?: AiProvider.OPENAI.name)
        }.getOrDefault(AiProvider.OPENAI)
        _config.value = AiConfig(
            provider = provider,
            baseUrl = p.getString(KEY_BASE_URL, null) ?: provider.baseUrl,
            apiKey = p.getString(KEY_API_KEY, null).orEmpty(),
            model = p.getString(KEY_MODEL, null) ?: provider.defaultModel,
            temperature = p.getFloat(KEY_TEMP, 0.7f),
        )
        loaded = true
    }

    /** 切换服务商：地址与模型随之切到该服务的默认值。 */
    fun selectProvider(context: Context, provider: AiProvider) {
        val current = _config.value
        save(
            context,
            current.copy(
                provider = provider,
                baseUrl = if (provider.isCustom) current.baseUrl else provider.baseUrl,
                model = if (provider.isCustom) current.model else provider.defaultModel,
            ),
        )
    }

    fun save(context: Context, config: AiConfig) {
        _config.value = config
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDER, config.provider.name)
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_MODEL, config.model)
            .putFloat(KEY_TEMP, config.temperature)
            .apply()
    }
}

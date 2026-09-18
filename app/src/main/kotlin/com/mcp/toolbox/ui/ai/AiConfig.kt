package com.mcp.toolbox.ui.ai

import android.content.Context
import com.mcp.toolbox.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 支持的 AI 服务商。
 *
 * `baseUrl` 均为各家的 OpenAI 兼容端点；`badge` 用于列表里的字母徽标，`tint` 为其品牌色。
 */
enum class AiProvider(
    val title: String,
    val badge: String,
    val tintArgb: Long,
    val baseUrl: String,
    val defaultModel: String,
    val docsHint: String,
    /** 品牌图形资源；为 0 表示回退到字母徽标。 */
    @androidx.annotation.DrawableRes val iconRes: Int = 0,
) {
    OPENAI("OpenAI", "AI", 0xFF10A37F, "https://api.openai.com/v1", "gpt-4o", "platform.openai.com", R.drawable.ic_ai_openai),
    ANTHROPIC("Anthropic Claude", "C", 0xFFD97757, "https://api.anthropic.com/v1", "claude-sonnet-4-20250514", "console.anthropic.com", R.drawable.ic_ai_anthropic),
    GEMINI("Google Gemini", "G", 0xFF4285F4, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.0-flash", "aistudio.google.com", R.drawable.ic_ai_gemini),
    DEEPSEEK("DeepSeek", "D", 0xFF4D6BFE, "https://api.deepseek.com/v1", "deepseek-chat", "platform.deepseek.com", R.drawable.ic_ai_deepseek),
    MOONSHOT("月之暗面 Kimi", "K", 0xFF1F1F1F, "https://api.moonshot.cn/v1", "moonshot-v1-8k", "platform.moonshot.cn", R.drawable.ic_ai_kimi),
    ZHIPU("智谱 GLM", "Z", 0xFF3859FF, "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus", "open.bigmodel.cn", 0),
    DASHSCOPE("通义千问", "Q", 0xFF615CED, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "bailian.console.aliyun.com", 0),
    BAIDU("百度文心", "W", 0xFF2932E1, "https://qianfan.baidubce.com/v2", "ernie-4.0-8k", "console.bce.baidu.com", 0),
    HUNYUAN("腾讯混元", "H", 0xFF0052D9, "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbo", "console.cloud.tencent.com", 0),
    SPARK("讯飞星火", "S", 0xFF1E63FF, "https://spark-api-open.xf-yun.com/v1", "generalv3.5", "console.xfyun.cn", 0),
    XAI("xAI Grok", "X", 0xFF1D1D1F, "https://api.x.ai/v1", "grok-2-latest", "console.x.ai", 0),
    MISTRAL("Mistral", "M", 0xFFFF7000, "https://api.mistral.ai/v1", "mistral-large-latest", "console.mistral.ai", R.drawable.ic_ai_mistral),
    CUSTOM("自定义（OpenAI 兼容）", "+", 0xFF6B7280, "", "", "", 0),
    ;

    val isCustom: Boolean get() = this == CUSTOM
}

/** 思考（推理）档位。不支持的档位由服务商忽略该字段。 */
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

/** AI 接入配置。密钥只存在本机 SharedPreferences 中，不上传。 */
data class AiConfig(
    val provider: AiProvider = AiProvider.OPENAI,
    val baseUrl: String = AiProvider.OPENAI.baseUrl,
    val apiKey: String = "",
    val model: String = AiProvider.OPENAI.defaultModel,
    val temperature: Float = 0.7f,
    val reasoning: ReasoningEffort = ReasoningEffort.DEFAULT,
    /** 从服务商拉取到的模型列表缓存，仅本机保存。 */
    val cachedModels: List<String> = emptyList(),
) {
    /** 是否已具备发起请求的最小条件。 */
    val ready: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()
}

object AiConfigStore {

    private const val PREFS = "ai-config"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_BASE_URL = "baseUrl"
    private const val KEY_API_KEY = "apiKey"
    private const val KEY_API_KEY_ENC = "apiKeyEnc"
    private const val KEY_MODEL = "model"
    private const val KEY_TEMP = "temperature"
    private const val KEY_REASONING = "reasoning"
    private const val KEY_MODELS = "cachedModels"

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
            apiKey = p.getString(KEY_API_KEY_ENC, null)
                ?.let { SecureStore.decrypt(context, it) }
                // 兼容早期写入的明文，读取后会在下次保存时转为密文
                ?: p.getString(KEY_API_KEY, null).orEmpty(),
            model = p.getString(KEY_MODEL, null) ?: provider.defaultModel,
            temperature = p.getFloat(KEY_TEMP, 0.7f),
            reasoning = runCatching {
                ReasoningEffort.valueOf(
                    p.getString(KEY_REASONING, null) ?: ReasoningEffort.DEFAULT.name,
                )
            }.getOrDefault(ReasoningEffort.DEFAULT),
            cachedModels = p.getString(KEY_MODELS, null)
                ?.split('\n')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
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

    /** 聊天页快速切换模型。 */
    fun selectModel(context: Context, model: String) {
        save(context, _config.value.copy(model = model))
    }

    /** 聊天页快速切换思考档位。 */
    fun selectReasoning(context: Context, effort: ReasoningEffort) {
        save(context, _config.value.copy(reasoning = effort))
    }

    fun setCachedModels(context: Context, models: List<String>) {
        save(context, _config.value.copy(cachedModels = models))
    }

    fun save(context: Context, config: AiConfig) {
        _config.value = config
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PROVIDER, config.provider.name)
            .putString(KEY_BASE_URL, config.baseUrl)
            .remove(KEY_API_KEY)
            .putString(KEY_API_KEY_ENC, SecureStore.encrypt(context, config.apiKey))
            .putString(KEY_MODEL, config.model)
            .putFloat(KEY_TEMP, config.temperature)
            .putString(KEY_REASONING, config.reasoning.name)
            .putString(KEY_MODELS, config.cachedModels.joinToString("\n"))
            .apply()
    }
}

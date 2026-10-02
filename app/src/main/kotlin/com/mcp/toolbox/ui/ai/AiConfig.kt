package com.mcp.toolbox.ui.ai

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 内置服务商，分两类。
 *
 * - **通用兼容**（[generic] = true）：OpenAI / OpenAI Responses / Gemini / Anthropic
 *   四套协议的通用适配器，不绑定厂商，地址与模型由用户填写。
 * - **定制供应商**：具体厂商的预设，带默认地址、品牌图标与推荐模型。
 *
 * 分界是「有没有厂商身份」，不是「用哪套协议」——DeepSeek、Kimi 走 OpenAI 兼容
 * 协议，但它们是厂商，归第二组。
 */
enum class AiProvider(
    val title: String,
    val badge: String,
    val tintArgb: Long,
    val baseUrl: String,
    val defaultModel: String,
    val docsHint: String,
    @androidx.annotation.DrawableRes val iconRes: Int = 0,
    /**
     * 通用兼容型：按协议适配，不绑定具体厂商。
     *
     * 地址与模型全部由用户填写。与「是不是 OpenAI 兼容协议」无关——DeepSeek、
     * Kimi 同样说 OpenAI 兼容协议，但它们是有名字的厂商，归定制供应商。
     */
    val generic: Boolean = false,
) {
    // ---------- 通用兼容：按协议，地址与模型自填 ----------
    OPENAI_GENERIC("OpenAI 通用", "AI", 0xFF10A37F, "", "", "", com.mcp.toolbox.R.drawable.ic_brand_openai, generic = true),
    RESPONSES_GENERIC("OpenAI Responses 通用", "AI", 0xFF10A37F, "", "", "", com.mcp.toolbox.R.drawable.ic_brand_openai, generic = true),
    GEMINI_GENERIC("Gemini 通用", "G", 0xFF4285F4, "", "", "", com.mcp.toolbox.R.drawable.ic_brand_gemini, generic = true),
    ANTHROPIC_GENERIC("Anthropic 通用", "C", 0xFFD97757, "", "", "", com.mcp.toolbox.R.drawable.ic_brand_anthropic, generic = true),

    // ---------- 定制供应商：带默认地址与图标的厂商预设 ----------
    OPENAI("OpenAI", "AI", 0xFF10A37F, "https://api.openai.com/v1", "gpt-4o", "platform.openai.com", com.mcp.toolbox.R.drawable.ic_brand_openai),
    ANTHROPIC("Anthropic Claude", "C", 0xFFD97757, "https://api.anthropic.com", "claude-sonnet-4-20250514", "console.anthropic.com", com.mcp.toolbox.R.drawable.ic_brand_anthropic),
    GEMINI("Google Gemini", "G", 0xFF4285F4, "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash", "aistudio.google.com", com.mcp.toolbox.R.drawable.ic_brand_gemini),
    DEEPSEEK("DeepSeek", "D", 0xFF4D6BFE, "https://api.deepseek.com/v1", "deepseek-chat", "platform.deepseek.com", com.mcp.toolbox.R.drawable.ic_brand_deepseek),
    MOONSHOT("月之暗面 Kimi", "K", 0xFF1F1F1F, "https://api.moonshot.cn/v1", "moonshot-v1-8k", "platform.moonshot.cn", com.mcp.toolbox.R.drawable.ic_brand_kimi),
    ZHIPU("智谱 GLM", "Z", 0xFF3859FF, "https://open.bigmodel.cn/api/paas/v4", "glm-4-plus", "open.bigmodel.cn", com.mcp.toolbox.R.drawable.ic_brand_zhipu),
    DASHSCOPE("通义千问", "Q", 0xFF615CED, "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus", "bailian.console.aliyun.com", com.mcp.toolbox.R.drawable.ic_brand_tongyi),
    HUNYUAN("腾讯混元", "H", 0xFF0052D9, "https://api.hunyuan.cloud.tencent.com/v1", "hunyuan-turbo", "console.cloud.tencent.com", com.mcp.toolbox.R.drawable.ic_brand_hunyuan),
    SILICONFLOW("硅基流动", "Si", 0xFF6E29F6, "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3", "cloud.siliconflow.cn", com.mcp.toolbox.R.drawable.ic_brand_siliconflow),
    XAI("xAI Grok", "X", 0xFF1D1D1F, "https://api.x.ai/v1", "grok-2-latest", "console.x.ai", com.mcp.toolbox.R.drawable.ic_brand_xai),
    MISTRAL("Mistral", "M", 0xFFFF7000, "https://api.mistral.ai/v1", "mistral-large-latest", "console.mistral.ai", com.mcp.toolbox.R.drawable.ic_brand_mistral),
    CUSTOM("自定义（OpenAI 兼容）", "+", 0xFF6B7280, "", "", ""),
    ;

    val isCustom: Boolean get() = this == CUSTOM

    /**
     * 这家厂商用哪套协议说话。
     *
     * 与 [AiConfig.activeProtocol] 不同：那里还考虑「当前选中的是不是自定义供应商」，
     * 这里只回答「内置的这家是哪一类」，供服务商列表分组使用。
     */
    fun protocol(): AiProtocol = when (this) {
        ANTHROPIC, ANTHROPIC_GENERIC -> AiProtocol.ANTHROPIC
        GEMINI, GEMINI_GENERIC -> AiProtocol.GEMINI
        RESPONSES_GENERIC -> AiProtocol.RESPONSES
        else -> AiProtocol.OPENAI
    }
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
 * 自定义供应商可选的请求协议。
 *
 * 与 [AiProvider] 的区别：[AiProvider] 是「哪一家厂商」（决定默认地址、图标、
 * 模型清单），这里只是「用哪套报文格式说话」。用户自建的反代或聚合网关
 * 经常是 OpenAI 兼容的，但也有直接暴露 Anthropic / Gemini 原生接口的。
 */
enum class AiProtocol(val label: String) {
    OPENAI("OpenAI 兼容"),
    RESPONSES("OpenAI Responses"),
    ANTHROPIC("Anthropic"),
    GEMINI("Gemini"),
}

/**
 * 单个服务商的配置。
 *
 * 每个服务商各存一份：密钥、地址、模型、请求头、系统提示都互不影响，
 * 切换服务商不会串用上一个的凭据。
 */
data class ProviderConfig(
    val provider: AiProvider,
    /**
     * 请求走哪套协议。
     *
     * 对内置服务商没有意义（由 [provider] 唯一决定），只有自定义供应商需要选：
     * 同样是「自己填地址」，对端可能是 OpenAI 兼容网关，也可能是 Anthropic 或
     * Gemini 的原生接口，三者的路径、鉴权头、报文结构都不同。
     */
    val protocol: AiProtocol = AiProtocol.OPENAI,
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

    /**
     * 当前模型的上下文窗口，**只返回服务端给出的真实值**。
     *
     * 拿不到就是 null：用量比例与「还剩多少」都依赖这个分母，用一个猜测值
     * 会算出一个看起来精确、实际错误的百分比。未知时界面只显示已用量。
     */
    fun contextWindowOrNull(): Int? =
        models.firstOrNull { it.id == selectedModel }?.contextWindow?.takeIf { it > 0 }
}

/**
 * 一个自定义供应商。
 *
 * 独立于 [AiProvider] 枚举存在，因为**它可以有任意多个**：用户想接三套不同的
 * 自建网关，就该有三条配置。枚举做 key 的 Map 只能留一条，后保存的会覆盖前一条。
 *
 * [id] 创建后不再变化，即使改名也稳定——它同时是「当前选中项」的引用依据。
 */
data class CustomProvider(
    val id: String,
    /** 展示名。允许重复，用户想叫两个「测试环境」也随他。 */
    val name: String,
    /**
     * 自定义头像的绝对路径。
     *
     * 存的是应用私有目录里的一份**副本**，不是相册那条 URI：SAF 授权是临时的，
     * 换设备或清缓存后原图可能读不到；复制进来才能保证头像一直显示。
     */
    val iconPath: String? = null,
    val protocol: AiProtocol = AiProtocol.OPENAI,
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
    fun toProviderConfig(): ProviderConfig = ProviderConfig(
        provider = AiProvider.CUSTOM,
        protocol = protocol,
        baseUrl = baseUrl,
        apiKey = apiKey,
        models = models,
        selectedModel = selectedModel,
        reasoning = reasoning,
        temperature = temperature,
        customHeaders = customHeaders,
        systemPrompt = systemPrompt,
        enabled = enabled,
    )

    /** 把一次 [ProviderConfig] 级别的改动写回本条目（id 与 name 不受影响）。 */
    fun applyConfig(changed: ProviderConfig): CustomProvider = copy(
        protocol = changed.protocol,
        baseUrl = changed.baseUrl,
        apiKey = changed.apiKey,
        models = changed.models,
        selectedModel = changed.selectedModel,
        reasoning = changed.reasoning,
        temperature = changed.temperature,
        customHeaders = changed.customHeaders,
        systemPrompt = changed.systemPrompt,
        enabled = changed.enabled,
    )

    /** 只换头像，其余不动。 */
    fun withIcon(path: String?): CustomProvider = copy(iconPath = path)

    companion object {
        fun fromProviderConfig(id: String, name: String, cfg: ProviderConfig): CustomProvider =
            CustomProvider(
                id = id,
                name = name,
                protocol = cfg.protocol,
                baseUrl = cfg.baseUrl,
                apiKey = cfg.apiKey,
                models = cfg.models,
                selectedModel = cfg.selectedModel,
                reasoning = cfg.reasoning,
                temperature = cfg.temperature,
                customHeaders = cfg.customHeaders,
                systemPrompt = cfg.systemPrompt,
                enabled = cfg.enabled,
            )
    }
}

/** 全局配置：当前选中项 + 内置服务商各自的配置 + 自定义供应商列表。 */
data class AiConfig(
    val current: AiProvider = AiProvider.OPENAI,
    /**
     * 当前选中的自定义供应商 id。
     *
     * 非空时优先于 [current]：切换自定义供应商不必去改枚举，也不会在枚举里留下
     * 一个「其实指的是某个自定义」的假状态。
     */
    val currentCustomId: String? = null,
    val perProvider: Map<AiProvider, ProviderConfig> = emptyMap(),
    /** 自定义供应商，按添加顺序排列。 */
    val customProviders: List<CustomProvider> = emptyList(),
) {
    val currentCustom: CustomProvider?
        get() = currentCustomId?.let { id -> customProviders.firstOrNull { it.id == id } }

    val active: ProviderConfig
        get() = currentCustom?.toProviderConfig()
            ?: perProvider[current]
            ?: ProviderConfig(
                provider = current,
                // 缺省配置也要带上协议，否则「Gemini 通用」这类条目会按 OpenAI 兼容发请求
                protocol = current.protocol(),
                baseUrl = current.baseUrl,
                selectedModel = current.defaultModel,
            )

    /** 实际生效的协议：内置服务商由枚举决定，自定义看它自己选的。 */
    val activeProtocol: AiProtocol
        get() = currentCustom?.protocol ?: current.protocol()

    /** 当前选中项的展示名（自定义时是用户自己起的名字）。 */
    val currentTitle: String get() = currentCustom?.name ?: current.title

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

    /** 某个内置服务商已填写的密钥（列表里用于显示已配置状态）。 */
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

    /** 选中一个内置服务商；同时清掉「当前自定义」，两者只能有一个生效。 */
    fun selectProvider(context: Context, provider: AiProvider) {
        update(context) { it.copy(current = provider, currentCustomId = null) }
    }

    /** 选中一个已存在的自定义供应商。 */
    fun selectCustom(context: Context, id: String) {
        update(context) { it.copy(currentCustomId = id) }
    }

    /**
     * 新增一个自定义供应商。
     *
     * 每次调用都新建一条——**名称与地址完全相同也新建**。用户要的就是这个语义：
     * 想改已有那条走编辑，新建代表「我就是要多一条」。返回新条目的 id。
     */
    fun addCustom(context: Context, name: String, cfg: ProviderConfig): String {
        val id = "custom-" + java.util.UUID.randomUUID().toString().take(8)
        update(context) { c ->
            c.copy(
                customProviders = c.customProviders + CustomProvider.fromProviderConfig(id, name, cfg),
                currentCustomId = id,
            )
        }
        return id
    }

    /** 覆盖一条自定义供应商（id 不变）。 */
    fun updateCustom(context: Context, id: String, change: (CustomProvider) -> CustomProvider) {
        update(context) { c ->
            c.copy(customProviders = c.customProviders.map { if (it.id == id) change(it) else it })
        }
    }

    /** 删除一条自定义供应商；删的若是当前选中项，回落到内置默认。 */
    fun removeCustom(context: Context, id: String) {
        update(context) { c ->
            c.copy(
                customProviders = c.customProviders.filterNot { it.id == id },
                currentCustomId = c.currentCustomId.takeIf { it != id },
            )
        }
    }

    /**
     * 改「当前生效的那一份」配置。
     *
     * `selectModel` / `selectReasoning` 会走这里。当前是自定义供应商时改那条自定义，
     * 否则改对应的内置条目——两者各存各的，不能因为切换过就串到一起。
     */
    fun updateActive(context: Context, change: (ProviderConfig) -> ProviderConfig) {
        update(context) { cfg ->
            val id = cfg.currentCustomId
            if (id == null) {
                cfg.copy(perProvider = cfg.perProvider + (cfg.current to change(cfg.active)))
            } else {
                cfg.copy(
                    customProviders = cfg.customProviders.map { cp ->
                        if (cp.id == id) cp.applyConfig(change(cp.toProviderConfig())) else cp
                    },
                )
            }
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

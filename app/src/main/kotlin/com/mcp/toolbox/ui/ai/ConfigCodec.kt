package com.mcp.toolbox.ui.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 配置的序列化。
 *
 * 每个服务商一条记录，密钥单独用 [SecureStore] 加密后再写盘；
 * 解码失败时逐项降级，不让一条坏记录毁掉整份配置。
 */
internal object ConfigCodec {

    private const val VERSION = 3

    fun encode(context: Context, config: AiConfig): String {
        val providers = JSONArray()
        config.perProvider.forEach { (provider, cfg) ->
            providers.put(
                encodeConfig(context, cfg).put("provider", provider.name),
            )
        }

        val customs = JSONArray()
        config.customProviders.forEach { cp ->
            customs.put(
                encodeConfig(context, cp.toProviderConfig())
                    .put("id", cp.id)
                    .put("name", cp.name)
                    .put("iconPath", cp.iconPath ?: JSONObject.NULL),
            )
        }

        return JSONObject()
            .put("version", VERSION)
            .put("current", config.current.name)
            // 非空表示当前生效的是自定义供应商；缺省即内置服务商
            .put("currentCustomId", config.currentCustomId ?: JSONObject.NULL)
            .put("providers", providers)
            .put("customProviders", customs)
            .toString()
    }

    /** 单条配置的公共编码。内置与自定义共用，避免两边字段漂移。 */
    private fun encodeConfig(context: Context, cfg: ProviderConfig): JSONObject {
        val headers = JSONObject()
        cfg.customHeaders.forEach { (k, v) -> headers.put(k, v) }

        val models = JSONArray()
        cfg.models.forEach { m ->
            models.put(
                JSONObject()
                    .put("id", m.id)
                    .put("name", m.displayName)
                    .put("ctx", m.contextWindow ?: JSONObject.NULL)
                    .put("reason", m.supportsReasoning),
            )
        }

        return JSONObject()
            .put("protocol", cfg.protocol.name)
            .put("baseUrl", cfg.baseUrl)
            .put("apiKey", SecureStore.encrypt(context, cfg.apiKey).orEmpty())
            .put("selectedModel", cfg.selectedModel)
            .put("reasoning", cfg.reasoning.name)
            .put("temperature", cfg.temperature.toDouble())
            .put("headers", headers)
            .put("systemPrompt", cfg.systemPrompt)
            .put("enabled", cfg.enabled)
            .put("models", models)
    }

    fun decode(context: Context, text: String): AiConfig {
        val root = JSONObject(text)
        val current = runCatching {
            AiProvider.valueOf(root.optString("current"))
        }.getOrDefault(AiProvider.OPENAI)

        // v2 及更早没有 customProviders / protocol 字段，缺省即空列表与 OpenAI 兼容，
        // 老配置能原样读进来，不需要迁移脚本。
        val arr = root.optJSONArray("providers") ?: JSONArray()
        val map = mutableMapOf<AiProvider, ProviderConfig>()
        (0 until arr.length()).forEach { i ->
            val o = arr.optJSONObject(i) ?: return@forEach
            val provider = runCatching {
                AiProvider.valueOf(o.optString("provider"))
            }.getOrNull() ?: return@forEach
            // CUSTOM 这一槽位是旧版遗留：那时自定义供应商只能存一条，挤在枚举里。
            // 现在改由 customProviders 承载，这里丢弃它——否则它会变成「新增」表单的
            // 默认值，新建时莫名其妙带出上一条的地址与密钥。
            if (provider == AiProvider.CUSTOM) return@forEach
            map[provider] = decodeConfig(context, provider, o)
        }

        val customs = mutableListOf<CustomProvider>()
        root.optJSONArray("customProviders")?.let { carr ->
            (0 until carr.length()).forEach { i ->
                val o = carr.optJSONObject(i) ?: return@forEach
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@forEach
                val cfg = decodeConfig(context, AiProvider.CUSTOM, o)
                customs += CustomProvider.fromProviderConfig(
                    id = id,
                    // 名字丢了就退回地址，避免列表里出现空标题
                    name = o.optString("name").takeIf { it.isNotBlank() }
                        ?: cfg.baseUrl.ifBlank { "自定义" },
                    cfg = cfg,
                ).withIcon(
                    o.optString("iconPath").takeIf { it.isNotBlank() && !o.isNull("iconPath") },
                )
            }
        }

        // 指向已不存在的自定义 id 时视为未选中，避免 active 落空
        val currentCustomId = root.optString("currentCustomId")
            .takeIf { it.isNotBlank() && customs.any { cp -> cp.id == it } }

        return AiConfig(
            current = current,
            currentCustomId = currentCustomId,
            perProvider = map,
            customProviders = customs,
        )
    }

    /** 单条配置的公共解码。内置与自定义共用。 */
    private fun decodeConfig(context: Context, provider: AiProvider, o: JSONObject): ProviderConfig {
        val headers = mutableMapOf<String, String>()
        o.optJSONObject("headers")?.let { h ->
            h.keys().forEach { k -> h.optString(k).takeIf { it.isNotBlank() }?.let { headers[k] = it } }
        }

        val models = mutableListOf<ModelEntry>()
        o.optJSONArray("models")?.let { ms ->
            (0 until ms.length()).forEach { j ->
                val m = ms.optJSONObject(j) ?: return@forEach
                val id = m.optString("id").takeIf { it.isNotBlank() } ?: return@forEach
                models += ModelEntry(
                    id = id,
                    displayName = m.optString("name"),
                    contextWindow = if (m.isNull("ctx")) null else m.optInt("ctx"),
                    supportsReasoning = m.optBoolean("reason"),
                )
            }
        }

        return ProviderConfig(
            provider = provider,
            protocol = runCatching { AiProtocol.valueOf(o.optString("protocol")) }
                .getOrDefault(AiProtocol.OPENAI),
            baseUrl = o.optString("baseUrl"),
            apiKey = SecureStore.decrypt(context, o.optString("apiKey")).orEmpty(),
            models = models,
            selectedModel = o.optString("selectedModel"),
            reasoning = runCatching {
                ReasoningEffort.valueOf(o.optString("reasoning"))
            }.getOrDefault(ReasoningEffort.DEFAULT),
            temperature = o.optDouble("temperature", 0.7).toFloat(),
            customHeaders = headers,
            systemPrompt = o.optString("systemPrompt"),
            enabled = o.optBoolean("enabled", true),
        )
    }
}

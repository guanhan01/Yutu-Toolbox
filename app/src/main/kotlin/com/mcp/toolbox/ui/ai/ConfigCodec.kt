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

    private const val VERSION = 2

    fun encode(context: Context, config: AiConfig): String {
        val providers = JSONArray()
        config.perProvider.forEach { (provider, cfg) ->
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

            providers.put(
                JSONObject()
                    .put("provider", provider.name)
                    .put("baseUrl", cfg.baseUrl)
                    .put("apiKey", SecureStore.encrypt(context, cfg.apiKey).orEmpty())
                    .put("selectedModel", cfg.selectedModel)
                    .put("reasoning", cfg.reasoning.name)
                    .put("temperature", cfg.temperature.toDouble())
                    .put("headers", headers)
                    .put("systemPrompt", cfg.systemPrompt)
                    .put("enabled", cfg.enabled)
                    .put("models", models),
            )
        }
        return JSONObject()
            .put("version", VERSION)
            .put("current", config.current.name)
            .put("providers", providers)
            .toString()
    }

    fun decode(context: Context, text: String): AiConfig {
        val root = JSONObject(text)
        val current = runCatching {
            AiProvider.valueOf(root.optString("current"))
        }.getOrDefault(AiProvider.OPENAI)

        val arr = root.optJSONArray("providers") ?: JSONArray()
        val map = mutableMapOf<AiProvider, ProviderConfig>()

        (0 until arr.length()).forEach { i ->
            val o = arr.optJSONObject(i) ?: return@forEach
            val provider = runCatching {
                AiProvider.valueOf(o.optString("provider"))
            }.getOrNull() ?: return@forEach

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

            map[provider] = ProviderConfig(
                provider = provider,
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
        return AiConfig(current = current, perProvider = map)
    }
}

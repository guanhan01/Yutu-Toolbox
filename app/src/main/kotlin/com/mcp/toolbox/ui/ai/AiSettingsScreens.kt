package com.mcp.toolbox.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSuperArrow
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** AI 设置主页：当前服务概览 + 进入服务商选择。 */
@Composable
fun AiSettingsScreen(
    onOpenProviders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val config by AiConfigStore.config.collectAsState()

    remember(config) { AiConfigStore.load(context); config }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))
        MiuixSectionCard(title = stringResource(R.string.ai_section_current)) {
            Column {
                MiuixSuperArrow(
                    title = stringResource(R.string.ai_provider_title),
                    subtitle = config.provider.title,
                    leadingIcon = Icons.Outlined.SmartToy,
                    valueText = if (config.ready) {
                        stringResource(R.string.ai_ready)
                    } else {
                        stringResource(R.string.ai_not_ready)
                    },
                    onClick = onOpenProviders,
                )
                MiuixListItem(
                    title = stringResource(R.string.ai_current_model),
                    subtitle = config.model.ifBlank { "-" },
                    leadingIcon = Icons.Outlined.Tune,
                    showDivider = true,
                )
                MiuixListItem(
                    title = stringResource(R.string.ai_api_key),
                    subtitle = if (config.apiKey.isBlank()) {
                        stringResource(R.string.ai_api_key_empty)
                    } else {
                        maskKey(config.apiKey)
                    },
                    leadingIcon = Icons.Outlined.Key,
                )
            }
        }
        Spacer(Modifier.height(spacing.groupGap))
        MiuixText(
            text = stringResource(R.string.ai_local_note),
            style = MiuixTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(32.dp))
    }
}

/** 二级：服务商列表。点击其中一项进入该服务商的配置界面。 */
@Composable
fun AiProviderListScreen(
    onBack: () -> Unit,
    onOpenProvider: (AiProvider) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val config by AiConfigStore.config.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))
        MiuixSectionCard(title = stringResource(R.string.ai_provider_pick)) {
            Column {
                AiProvider.entries.forEachIndexed { index, provider ->
                    MiuixListItem(
                        title = provider.title,
                        subtitle = provider.baseUrl.ifBlank {
                            stringResource(R.string.ai_custom_hint)
                        },
                        leadingIcon = if (provider == config.provider) Icons.Outlined.Check else null,
                        trailing = if (provider == config.provider) {
                            { MiuixTag(text = stringResource(R.string.ai_in_use)) }
                        } else null,
                        showDivider = index != AiProvider.entries.lastIndex,
                        onClick = {
                            AiConfigStore.selectProvider(context, provider)
                            onOpenProvider(provider)
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** 三级：单个服务商的地址 / 密钥 / 模型配置。 */
@Composable
fun AiProviderDetailScreen(
    providerName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val saved by AiConfigStore.config.collectAsState()
    val provider = remember(providerName) {
        runCatching { AiProvider.valueOf(providerName) }.getOrDefault(AiProvider.OPENAI)
    }

    var baseUrl by remember(provider) { mutableStateOf(saved.baseUrl) }
    var apiKey by remember(provider) { mutableStateOf(saved.apiKey) }
    var model by remember(provider) { mutableStateOf(saved.model) }
    var tip by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))
        MiuixSectionCard(
            title = provider.title,
            subtitle = if (provider.isCustom) {
                stringResource(R.string.ai_custom_hint)
            } else {
                provider.docsHint
            },
        ) {
            Column(
                modifier = Modifier.padding(spacing.lg),
                verticalArrangement = Arrangement.spacedBy(spacing.md),
            ) {
                MiuixTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    placeholder = stringResource(R.string.ai_field_base_url),
                    singleLine = true,
                )
                MiuixTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = stringResource(R.string.ai_field_api_key),
                    singleLine = true,
                )
                MiuixTextField(
                    value = model,
                    onValueChange = { model = it },
                    placeholder = stringResource(R.string.ai_field_model),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    MiuixButton(
                        text = stringResource(R.string.ai_save),
                        onClick = {
                            AiConfigStore.save(
                                context,
                                saved.copy(
                                    provider = provider,
                                    baseUrl = baseUrl.trim(),
                                    apiKey = apiKey.trim(),
                                    model = model.trim(),
                                ),
                            )
                            tip = "已保存"
                        },
                        enabled = baseUrl.isNotBlank() && model.isNotBlank(),
                    )
                    tip?.let {
                        MiuixText(
                            text = it,
                            style = MiuixTheme.typography.bodySmall,
                            color = colors.success,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(spacing.groupGap))
        MiuixText(
            text = stringResource(R.string.ai_key_note),
            style = MiuixTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(32.dp))
    }
}

/** 只显示首尾，中间打码。 */
private fun maskKey(key: String): String =
    if (key.length <= 8) "••••" else "${key.take(4)}••••${key.takeLast(4)}"

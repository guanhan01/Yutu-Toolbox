package com.mcp.toolbox.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixIcon
import androidx.compose.material.icons.outlined.Visibility
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch

/** 服务商徽标：有品牌图形的用图形，其余回退到字母。 */
@Composable
fun ProviderBadge(provider: AiProvider, size: Dp = 34.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(provider.tintArgb)),
        contentAlignment = Alignment.Center,
    ) {
        if (provider.iconRes != 0) {
            Image(
                painter = painterResource(provider.iconRes),
                contentDescription = null,
                modifier = Modifier.size(size * 0.58f),
            )
        } else {
            MiuixText(
                text = provider.badge,
                style = MiuixTheme.typography.labelLarge,
                color = Color.White,
            )
        }
    }
}

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
                MiuixListItem(
                    title = stringResource(R.string.ai_provider_title),
                    subtitle = config.provider.title,
                    leading = { ProviderBadge(config.provider) },
                    trailing = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            MiuixText(
                                text = if (config.ready) {
                                    stringResource(R.string.ai_ready)
                                } else {
                                    stringResource(R.string.ai_not_ready)
                                },
                                style = MiuixTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            MiuixIcon(
                                Icons.Outlined.ChevronRight,
                                null,
                                tint = colors.onSurfaceVariant,
                                size = 18.dp,
                            )
                        }
                    },
                    showDivider = true,
                    onClick = onOpenProviders,
                )
                MiuixListItem(
                    title = stringResource(R.string.ai_current_model),
                    subtitle = config.model.ifBlank { "-" },
                    leadingIcon = Icons.Outlined.Tune,
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

/** 单选圆圈：只负责勾选，不触发跳转。 */
@Composable
private fun SelectionCircle(selected: Boolean, onClick: () -> Unit) {
    val colors = MiuixTheme.colors
    Box(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .size(26.dp)
            .clip(CircleShape)
            .background(
                if (selected) colors.primary else colors.surfaceContainerHighest,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            MiuixIcon(
                Icons.Outlined.Check,
                null,
                tint = colors.onPrimary,
                size = 16.dp,
            )
        }
    }
}

/** 二级：服务商列表。点圆圈勾选，点其余区域进入该服务商的配置界面。 */
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
                    // 不用 MiuixListItem：它的整行 onClick 会吞掉 trailing 的点击，
                    // 导致点圆圈也跳转。这里自己排两段可点区域，互不干扰。
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        AiConfigStore.selectProvider(context, provider)
                                        onOpenProvider(provider)
                                    }
                                    .padding(start = 20.dp, top = 14.dp, bottom = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                ProviderBadge(provider)
                                Column {
                                    MiuixText(
                                        text = provider.title,
                                        style = MiuixTheme.typography.bodyLarge,
                                    )
                                    MiuixText(
                                        text = provider.baseUrl.ifBlank {
                                            stringResource(R.string.ai_custom_hint)
                                        },
                                        style = MiuixTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                            SelectionCircle(
                                selected = provider == config.provider,
                                onClick = {
                                    AiConfigStore.selectProvider(context, provider)
                                },
                            )
                        }
                        if (index != AiProvider.entries.lastIndex) {
                            MiuixDivider()
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** 密码样式的输入框：默认遮蔽，右侧可切换明文。 */
@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val colors = MiuixTheme.colors
    var visible by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.weight(1f)) {
            MiuixTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                singleLine = true,
                visualTransformation = if (visible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                },
            )
        }
        MiuixIconButton(
            icon = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = placeholder,
            onClick = { visible = !visible },
            buttonSize = 40.dp,
            iconSize = 20.dp,
            tint = colors.onSurfaceVariant,
        )
    }
}

/** 三级：服务商配置。含连接参数、拉取模型、思考档位。 */
@Composable
fun AiProviderDetailScreen(
    providerName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val saved by AiConfigStore.config.collectAsState()
    val provider = remember(providerName) {
        runCatching { AiProvider.valueOf(providerName) }.getOrDefault(AiProvider.OPENAI)
    }

    var baseUrl by remember(provider) { mutableStateOf(saved.baseUrl) }
    var apiKey by remember(provider) { mutableStateOf(saved.apiKey) }
    var model by remember(provider) { mutableStateOf(saved.model) }
    var tip by remember { mutableStateOf<String?>(null) }
    var pulling by remember { mutableStateOf(false) }
    var models by remember(provider) { mutableStateOf(saved.cachedModels) }

    fun persist() {
        AiConfigStore.save(
            context,
            saved.copy(
                provider = provider,
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                model = model.trim(),
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))

        // 连接参数
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.md),
                ) {
                    ProviderBadge(provider, size = 44.dp)
                    MiuixText(
                        text = provider.title,
                        style = MiuixTheme.typography.titleMedium,
                    )
                }
                MiuixTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    placeholder = stringResource(R.string.ai_field_base_url),
                    singleLine = true,
                )
                // 密钥按密码样式输入，避免旁观者直接看到
                PasswordField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    placeholder = stringResource(R.string.ai_field_api_key),
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
                        onClick = { persist(); tip = "已保存" },
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

        // 模型：拉取 + 选择
        MiuixSectionCard(
            title = stringResource(R.string.ai_models),
            subtitle = stringResource(R.string.ai_models_hint),
        ) {
            Column {
                MiuixListItem(
                    title = stringResource(R.string.ai_pull_models),
                    subtitle = if (pulling) {
                        stringResource(R.string.ai_pulling)
                    } else {
                        stringResource(R.string.ai_pull_desc, models.size)
                    },
                    leadingIcon = Icons.Outlined.Refresh,
                    onClick = {
                        if (pulling) return@MiuixListItem
                        pulling = true
                        scope.launch {
                            persist()
                            val cfg = AiConfigStore.config.value
                            val result = AiChatClient.listModels(cfg)
                            pulling = false
                            result
                                .onSuccess {
                                    models = it
                                    AiConfigStore.setCachedModels(context, it)
                                    tip = null
                                }
                                .onFailure { tip = it.message }
                        }
                    },
                )
                models.forEach { name ->
                    MiuixListItem(
                        title = name,
                        leadingIcon = if (name == model) Icons.Outlined.Check else null,
                        trailing = if (name == model) {
                            { MiuixTag(text = stringResource(R.string.ai_in_use)) }
                        } else null,
                        onClick = { model = name; persist() },
                    )
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


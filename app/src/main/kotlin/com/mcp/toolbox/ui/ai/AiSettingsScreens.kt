package com.mcp.toolbox.ui.ai

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch

/** 服务商徽标：有品牌图形的用图形，其余回退字母。 */
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
                modifier = Modifier.size(size * 0.62f),
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

/** 单选圆圈：只负责勾选。 */
@Composable
private fun SelectionCircle(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    Box(
        modifier = modifier
            .padding(horizontal = 20.dp)
            .size(26.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary else colors.surfaceContainerHighest)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            MiuixIcon(Icons.Outlined.Check, null, tint = colors.onPrimary, size = 16.dp)
        }
    }
}

/** 密码框：眼睛图标内嵌在输入框右侧。 */
@Composable
private fun SecretField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
) {
    val colors = MiuixTheme.colors
    val typography = MiuixTheme.typography
    var visible by remember { mutableStateOf(false) }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None
        else PasswordVisualTransformation(),
        textStyle = typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(MiuixTheme.radius.field))
                    .background(colors.surfaceContainerHigh)
                    .padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        MiuixText(
                            text = placeholder,
                            style = typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    inner()
                }
                MiuixIconButton(
                    icon = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                    contentDescription = placeholder,
                    onClick = { visible = !visible },
                    buttonSize = 40.dp,
                    iconSize = 18.dp,
                    tint = colors.onSurfaceVariant,
                )
            }
        },
    )
}

/** 普通输入框，关闭右侧清除按钮。 */
@Composable
private fun PlainField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
) {
    com.mcp.toolbox.core.design.component.MiuixTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = placeholder,
        singleLine = singleLine,
        showClear = false,
    )
}

/** AI 设置主页。 */
@Composable
fun AiSettingsScreen(
    onOpenProviders: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenLinux: () -> Unit = {},
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
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenProviders)
                        .padding(start = 20.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ProviderBadge(config.current)
                    Column(Modifier.weight(1f)) {
                        MiuixText(config.current.title, style = MiuixTheme.typography.bodyLarge)
                        MiuixText(
                            text = if (config.ready) stringResource(R.string.ai_ready)
                            else stringResource(R.string.ai_not_ready),
                            style = MiuixTheme.typography.bodySmall,
                            color = if (config.ready) colors.success else colors.onSurfaceVariant,
                        )
                    }
                }
                MiuixDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenModels)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    MiuixIcon(Icons.Outlined.Tune, null, tint = colors.onSurfaceVariant, size = 20.dp)
                    Column(Modifier.weight(1f)) {
                        MiuixText(stringResource(R.string.ai_models), style = MiuixTheme.typography.bodyLarge)
                        MiuixText(
                            text = config.model.ifBlank { "-" },
                            style = MiuixTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(spacing.groupGap))

        // Linux 工具环境：点击进入二级页面
        MiuixSectionCard(title = stringResource(R.string.linux_section_title)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenLinux)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MiuixIcon(
                    Icons.Outlined.Terminal,
                    null,
                    tint = colors.onSurfaceVariant,
                    size = 20.dp,
                )
                Column(Modifier.weight(1f)) {
                    MiuixText(
                        text = stringResource(R.string.app_nav_linux),
                        style = MiuixTheme.typography.bodyLarge,
                    )
                    MiuixText(
                        text = stringResource(R.string.linux_entry_desc),
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
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

/** 二级：服务商列表。点圆圈勾选，点其余区域进入该服务商的配置界面。 */
@Composable
fun AiProviderListScreen(
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
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { onOpenProvider(provider) }
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
                                selected = provider == config.current,
                                onClick = { AiConfigStore.selectProvider(context, provider) },
                            )
                        }
                        if (index != AiProvider.entries.lastIndex) MiuixDivider()
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** 三级：服务商配置。 */
@Composable
fun AiProviderDetailScreen(
    providerName: String,
    onOpenModels: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by AiConfigStore.config.collectAsState()

    val provider = remember(providerName) {
        runCatching { AiProvider.valueOf(providerName) }.getOrDefault(AiProvider.OPENAI)
    }
    val cfg = remember(config, provider) {
        config.perProvider[provider] ?: ProviderConfig(
            provider = provider,
            baseUrl = provider.baseUrl,
            selectedModel = provider.defaultModel,
        )
    }

    var baseUrl by remember(provider, cfg.baseUrl) { mutableStateOf(cfg.baseUrl) }
    var apiKey by remember(provider, cfg.apiKey) { mutableStateOf(cfg.apiKey) }
    var sysPrompt by remember(provider, cfg.systemPrompt) { mutableStateOf(cfg.systemPrompt) }
    var headerKey by remember { mutableStateOf("") }
    var headerValue by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    fun persist(change: (ProviderConfig) -> ProviderConfig = { it }) {
        AiConfigStore.update(context) { c ->
            val cur = c.perProvider[provider] ?: ProviderConfig(
                provider = provider,
                baseUrl = provider.baseUrl,
                selectedModel = provider.defaultModel,
            )
            c.copy(
                perProvider = c.perProvider + (
                    provider to change(
                        cur.copy(
                            baseUrl = baseUrl.trim(),
                            apiKey = apiKey.trim(),
                            systemPrompt = sysPrompt,
                        ),
                    )
                    ),
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))

        // 连接
        MiuixSectionCard(
            title = provider.title,
            subtitle = provider.docsHint.ifBlank { stringResource(R.string.ai_custom_hint) },
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
                    MiuixText(provider.title, style = MiuixTheme.typography.titleMedium)
                }
                PlainField(baseUrl, { baseUrl = it }, stringResource(R.string.ai_field_base_url))
                SecretField(apiKey, { apiKey = it }, stringResource(R.string.ai_field_api_key))
                PlainField(sysPrompt, { sysPrompt = it }, stringResource(R.string.ai_field_system), singleLine = false)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    MiuixButton(
                        text = stringResource(R.string.ai_save),
                        onClick = { persist(); saved = true },
                        enabled = baseUrl.isNotBlank(),
                    )
                    MiuixButton(
                        text = if (testing) stringResource(R.string.ai_testing)
                        else stringResource(R.string.ai_test),
                        onClick = {
                            testing = true
                            testResult = null
                            scope.launch {
                                persist()
                                testResult = AiChatClient.listModelsFor(
                                    provider = provider,
                                    baseUrl = baseUrl.trim(),
                                    apiKey = apiKey.trim(),
                                )
                                    .fold(
                                        onSuccess = { "连接正常，可用模型 ${it.size} 个" },
                                        onFailure = { "连接失败：${it.message}" },
                                    )
                                testing = false
                            }
                        },
                        enabled = baseUrl.isNotBlank() && !testing,
                        loading = testing,
                    )
                    if (saved && testResult == null) {
                        MiuixText(
                            text = "已保存",
                            style = MiuixTheme.typography.bodySmall,
                            color = colors.success,
                        )
                    }
                }
                testResult?.let {
                    MiuixText(
                        text = it,
                        style = MiuixTheme.typography.bodySmall,
                        color = if (it.startsWith("连接正常")) colors.success else colors.error,
                    )
                }
            }
        }
        Spacer(Modifier.height(spacing.groupGap))

        // 自定义请求头
        MiuixSectionCard(title = stringResource(R.string.ai_headers)) {
            Column {
                cfg.customHeaders.forEach { (k, v) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            MiuixText(k, style = MiuixTheme.typography.bodyMedium)
                            MiuixText(
                                text = v,
                                style = MiuixTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        MiuixIconButton(
                            icon = Icons.Outlined.Delete,
                            contentDescription = null,
                            onClick = {
                                persist { it.copy(customHeaders = it.customHeaders - k) }
                            },
                            buttonSize = 40.dp,
                            iconSize = 18.dp,
                            tint = colors.onSurfaceVariant,
                        )
                    }
                    MiuixDivider()
                }
                Column(
                    modifier = Modifier.padding(spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    PlainField(headerKey, { headerKey = it }, stringResource(R.string.ai_header_key))
                    PlainField(headerValue, { headerValue = it }, stringResource(R.string.ai_header_value))
                    MiuixButton(
                        text = stringResource(R.string.ai_header_add),
                        onClick = {
                            val k = headerKey.trim()
                            if (k.isNotBlank()) {
                                persist { it.copy(customHeaders = it.customHeaders + (k to headerValue.trim())) }
                                headerKey = ""
                                headerValue = ""
                            }
                        },
                        enabled = headerKey.isNotBlank(),
                        leadingIcon = Icons.Outlined.Add,
                    )
                }
            }
        }
        Spacer(Modifier.height(spacing.groupGap))

        // 模型管理入口
        MiuixSectionCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenModels)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                MiuixIcon(Icons.Outlined.Tune, null, tint = colors.onSurfaceVariant, size = 20.dp)
                Column(Modifier.weight(1f)) {
                    MiuixText(stringResource(R.string.ai_models), style = MiuixTheme.typography.bodyLarge)
                    MiuixText(
                        text = stringResource(R.string.ai_models_count, cfg.models.size),
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
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

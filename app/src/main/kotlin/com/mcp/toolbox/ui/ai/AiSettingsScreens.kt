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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import com.mcp.toolbox.core.design.component.MiuixDialog
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
import com.mcp.toolbox.feature.mcp.WriteGuard
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSuperSwitch
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch

/** 服务商徽标：有品牌图形的用图形，其余回退字母。 */
/** 自定义供应商头像：有图显示图，没图显示「+」，点一下换图。 */
@Composable
private fun CustomAvatar(
    bitmap: ImageBitmap?,
    size: Dp,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colors
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (bitmap == null) colors.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        } else {
            MiuixIcon(Icons.Outlined.Add, null, tint = colors.onSurfaceVariant, size = size * 0.5f)
        }
    }
}

@Composable
fun ProviderBadge(
    provider: AiProvider,
    size: Dp = 34.dp,
    /** 自定义供应商的用户头像；为 null 时退回默认徽标。 */
    customIcon: ImageBitmap? = null,
) {
    if (customIcon != null) {
        Image(
            bitmap = customIcon,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
        return
    }
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
    val allowWrite by WriteGuard.allowed.collectAsState()

    remember(config) { AiConfigStore.load(context); config }
    remember { WriteGuard.load(context) }

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

        // 写类工具的授权开关。原来挂在「内置 MCP Server」里，Server 移除后
        // 放进 AI 设置：写能力现在只由 AI 侧使用，授权就应当在这里给。
        MiuixSectionCard(title = stringResource(R.string.ai_section_tools)) {
            Column {
                MiuixSuperSwitch(
                    title = stringResource(R.string.ai_allow_write),
                    subtitle = stringResource(R.string.ai_allow_write_desc),
                    checked = allowWrite,
                    onCheckedChange = { WriteGuard.set(context, it) },
                )
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

        // 分组一：通用兼容 —— 按**协议**适配，不绑定厂商，地址与模型自填。
        //
        // 判据是 AiProvider.generic，不是「用哪套报文」：DeepSeek、Kimi 同样说
        // OpenAI 兼容协议，但它们是有名字的厂商，归第二组。这里放的是
        // OpenAI / OpenAI Responses / Gemini / Anthropic 四套协议的通用入口。
        val generic = AiProvider.entries.filter { it.generic }
        MiuixSectionCard(title = stringResource(R.string.ai_group_generic)) {
            Column {
                generic.forEachIndexed { index, provider ->
                    ProviderRow(
                        provider = provider,
                        selected = config.currentCustomId == null && provider == config.current,
                        subtitle = provider.protocol().label,
                        onClick = { onOpenProvider(provider) },
                        onSelect = { AiConfigStore.selectProvider(context, provider) },
                        showDivider = index != generic.lastIndex,
                    )
                }
            }
        }

        Spacer(Modifier.height(spacing.groupGap))

        // 分组二：定制供应商 —— 具体厂商的预设（带默认地址与图标），
        // 以及**用户自己添加的自定义供应商**。
        val builtInVendors = AiProvider.entries.filter {
            it !== AiProvider.CUSTOM && !it.generic
        }
        MiuixSectionCard(title = stringResource(R.string.ai_group_vendors)) {
            Column {
                builtInVendors.forEach { provider ->
                    ProviderRow(
                        provider = provider,
                        selected = config.currentCustomId == null && provider == config.current,
                        onClick = { onOpenProvider(provider) },
                        onSelect = { AiConfigStore.selectProvider(context, provider) },
                        showDivider = true,
                    )
                }

                // 用户已添加的自定义供应商
                config.customProviders.forEach { cp ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    AiHub.pendingCustomId = cp.id
                                    onOpenProvider(AiProvider.CUSTOM)
                                }
                                .padding(start = 20.dp, top = 14.dp, bottom = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            ProviderBadge(AiProvider.CUSTOM)
                            Column {
                                MiuixText(cp.name, style = MiuixTheme.typography.bodyLarge)
                                MiuixText(
                                    text = cp.baseUrl.ifBlank { cp.protocol.label },
                                    style = MiuixTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                        SelectionCircle(
                            selected = config.currentCustomId == cp.id,
                            onClick = { AiConfigStore.selectCustom(context, cp.id) },
                        )
                    }
                    MiuixDivider()
                }

                // 新增：走同一个配置页，但保存时是「追加一条」而不是覆盖
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            AiHub.pendingCustomId = null
                            onOpenProvider(AiProvider.CUSTOM)
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    MiuixIcon(Icons.Outlined.Add, null, tint = colors.primary, size = 20.dp)
                    MiuixText(
                        text = stringResource(R.string.ai_custom_add),
                        style = MiuixTheme.typography.bodyLarge,
                        color = colors.primary,
                    )
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

/** 服务商列表里的一行：图标 + 名称 + 地址 + 右侧选中圈。 */
@Composable
private fun ProviderRow(
    provider: AiProvider,
    selected: Boolean,
    onClick: () -> Unit,
    onSelect: () -> Unit,
    showDivider: Boolean,
    /** 覆盖默认副标题。通用兼容项没有默认地址，用协议名代替。 */
    subtitle: String? = null,
) {
    val colors = MiuixTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
                .padding(start = 20.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProviderBadge(provider)
            Column {
                MiuixText(provider.title, style = MiuixTheme.typography.bodyLarge)
                MiuixText(
                    text = subtitle ?: provider.baseUrl.ifBlank { provider.docsHint },
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        SelectionCircle(selected = selected, onClick = onSelect)
    }
    if (showDivider) MiuixDivider()
}

/** 三级：服务商配置。 */
@Composable
fun AiProviderDetailScreen(
    providerName: String,
    onOpenModels: () -> Unit,
    onBack: () -> Unit = {},
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
    // 自定义供应商：pendingCustomId 为 null 表示新建，非 null 表示编辑那一条。
    //
    // 这里**不能用 remember 缓存**：缓存的 key 很容易漏掉 pendingCustomId，一旦漏了，
    // 先点过自定义条目 A、再点「新增」时 key 没变化，就会沿用缓存里的 A——
    // 表现是「新建表单带出上一条的配置」。直接算，代价只是一次列表查找。
    val editingCustom = if (provider != AiProvider.CUSTOM) {
        null
    } else {
        AiHub.pendingCustomId?.let { id -> config.customProviders.firstOrNull { it.id == id } }
    }
    val isNewCustom = provider == AiProvider.CUSTOM && editingCustom == null

    val cfg = editingCustom?.toProviderConfig()
        ?: config.perProvider[provider]
        ?: ProviderConfig(
            provider = provider,
            protocol = provider.protocol(),
            baseUrl = provider.baseUrl,
            selectedModel = provider.defaultModel,
        )

    // key 用 id 而不是整个对象：对象每次重组都是新实例，会让状态被反复重置
    val editingKey = editingCustom?.id
    var customName by remember(provider, editingKey) {
        mutableStateOf(editingCustom?.name.orEmpty())
    }
    var protocol by remember(provider, editingKey) { mutableStateOf(cfg.protocol) }
    var baseUrl by remember(provider, editingKey) { mutableStateOf(cfg.baseUrl) }
    var apiKey by remember(provider, editingKey) { mutableStateOf(cfg.apiKey) }
    var sysPrompt by remember(provider, editingKey) { mutableStateOf(cfg.systemPrompt) }
    var iconPath by remember(provider, editingKey) { mutableStateOf(editingCustom?.iconPath) }
    var headerKey by remember { mutableStateOf("") }
    var headerValue by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var iconBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    // 新建阶段还没 id，没法立刻落盘，先把选中的 URI 存着，等保存拿到 id 再复制
    var pendingIconUri by remember(provider, editingKey) { mutableStateOf<android.net.Uri?>(null) }

    LaunchedEffect(iconPath) { iconBitmap = ProviderIconStore.load(iconPath) }

    val iconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val existing = editingCustom
        scope.launch {
            if (existing == null) {
                // 还没保存：先记下来，同时本地预览
                pendingIconUri = uri
                iconBitmap = ProviderIconStore.loadPreview(context, uri)
            } else {
                ProviderIconStore.import(context, existing.id, uri)?.let { path ->
                    iconPath = path
                    AiConfigStore.updateCustom(context, existing.id) { it.withIcon(path) }
                }
            }
        }
    }

    /** 当前输入框里的值，固化成一次「配置改动」。 */
    fun draft(change: (ProviderConfig) -> ProviderConfig = { it }): ProviderConfig =
        change(
            cfg.copy(
                protocol = protocol,
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                systemPrompt = sysPrompt,
            ),
        )

    fun persist(change: (ProviderConfig) -> ProviderConfig = { it }) {
        val next = draft(change)
        if (provider == AiProvider.CUSTOM) {
            val target = editingCustom
            if (target == null) {
                // 新增：每次都追加一条新条目（同名也新建），随后自动选中它
                val newId = AiConfigStore.addCustom(
                    context,
                    customName.trim().ifBlank { next.baseUrl },
                    next,
                )
                // 新建时选的头像此刻才有 id 可以落盘
                val uri = pendingIconUri
                pendingIconUri = null
                if (uri != null) {
                    scope.launch {
                        ProviderIconStore.import(context, newId, uri)?.let { path ->
                            AiConfigStore.updateCustom(context, newId) { it.withIcon(path) }
                        }
                    }
                }
            } else {
                AiConfigStore.updateCustom(context, target.id) { it.applyConfig(next) }
            }
            return
        }
        AiConfigStore.update(context) { c ->
            val cur = c.perProvider[provider] ?: ProviderConfig(
                provider = provider,
                protocol = provider.protocol(),
                baseUrl = provider.baseUrl,
                selectedModel = provider.defaultModel,
            )
            c.copy(perProvider = c.perProvider + (provider to change(cur.copy(
                baseUrl = baseUrl.trim(),
                apiKey = apiKey.trim(),
                systemPrompt = sysPrompt,
            ))))
        }
    }

    // 删除确认：不可撤销，走二次确认（与设计规范里危险操作的要求一致）
    if (confirmingDelete) {
        val target = editingCustom
        MiuixDialog(
            visible = true,
            onDismiss = { confirmingDelete = false },
            title = "删除「${target?.name.orEmpty()}」",
            message = "只删这条自定义供应商的配置与头像，不影响内置服务商。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                confirmingDelete = false
                target?.let { cp ->
                    scope.launch { ProviderIconStore.remove(cp.id, cp.iconPath) }
                    AiConfigStore.removeCustom(context, cp.id)
                }
                AiHub.pendingCustomId = null
                onBack()
            },
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

        // 连接
        MiuixSectionCard(
            title = if (provider == AiProvider.CUSTOM) {
                if (isNewCustom) stringResource(R.string.ai_custom_add) else editingCustom?.name.orEmpty()
            } else {
                provider.title
            },
            subtitle = when {
                // 通用兼容项没有厂商文档可指，说明它的用法即可
                provider.generic -> stringResource(R.string.ai_generic_hint)
                else -> provider.docsHint.ifBlank { stringResource(R.string.ai_custom_hint) }
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
                    if (provider == AiProvider.CUSTOM) {
                        CustomAvatar(
                            bitmap = iconBitmap,
                            size = 44.dp,
                            onClick = { iconPicker.launch("image/*") },
                        )
                    } else {
                        ProviderBadge(provider, size = 44.dp)
                    }
                    MiuixText(
                        text = if (provider == AiProvider.CUSTOM) {
                            customName.ifBlank {
                                if (isNewCustom) stringResource(R.string.ai_custom_add)
                                else editingCustom?.name.orEmpty()
                            }
                        } else {
                            provider.title
                        },
                        style = MiuixTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // 删除只对「已存在的」自定义供应商开放：
                    // 内置服务商删不掉，新建的那条也还没东西可删。
                    if (editingCustom != null) {
                        MiuixText(
                            text = stringResource(R.string.ai_delete),
                            style = MiuixTheme.typography.labelLarge,
                            color = colors.error,
                            modifier = Modifier
                                .clickable { confirmingDelete = true }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }

                // 名字与协议只有自定义供应商需要：内置那 11 家两者的答案都是确定的
                if (provider == AiProvider.CUSTOM) {
                    PlainField(
                        customName,
                        { customName = it },
                        stringResource(R.string.ai_custom_name),
                    )
                    MiuixSectionCard(title = stringResource(R.string.ai_custom_protocol)) {
                        Column {
                            AiProtocol.entries.forEachIndexed { index, item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { protocol = item }
                                        .padding(horizontal = 20.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    MiuixText(
                                        text = item.label,
                                        style = MiuixTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                    )
                                    SelectionCircle(
                                        selected = protocol == item,
                                        onClick = { protocol = item },
                                    )
                                }
                                if (index != AiProtocol.entries.lastIndex) MiuixDivider()
                            }
                        }
                    }
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
                        text = if (isNewCustom) stringResource(R.string.ai_save)
                        else stringResource(R.string.ai_save),
                        onClick = {
                            persist()
                            saved = true
                            // 存完就变成「编辑已存在那条」，否则连点会不断堆新条目——
                            // 用户要的「每次保存都新建」指的是主动新建，不是手滑重复点
                            if (isNewCustom) AiHub.pendingCustomId = null
                        },
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
                                    protocol = protocol,
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

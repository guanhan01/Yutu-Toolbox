package com.mcp.toolbox.ui.ai

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch

/** 模型管理：从远端拉取 / 添加自定义 / 搜索 / 编辑。 */
@Composable
fun AiModelScreen(
    providerName: String,
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
    val cfg = config.perProvider[provider] ?: ProviderConfig(
        provider = provider,
        baseUrl = provider.baseUrl,
        selectedModel = provider.defaultModel,
    )

    var query by remember { mutableStateOf("") }
    var pulling by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<ModelEntry?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun updateModels(change: (List<ModelEntry>) -> List<ModelEntry>) {
        AiConfigStore.update(context) { c ->
            val cur = c.perProvider[provider] ?: ProviderConfig(
                provider = provider,
                baseUrl = provider.baseUrl,
                selectedModel = provider.defaultModel,
            )
            c.copy(perProvider = c.perProvider + (provider to cur.copy(models = change(cur.models))))
        }
    }

    val shown = cfg.models.filter {
        query.isBlank() || it.id.contains(query, true) || it.label.contains(query, true)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))

        MiuixSectionCard(title = stringResource(R.string.ai_model_manage)) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (!pulling) {
                                pulling = true
                                message = null
                                scope.launch {
                                    AiChatClient.listModelsFor(
                                        provider = provider,
                                        baseUrl = provider.baseUrl.ifBlank { cfg.baseUrl },
                                        apiKey = cfg.apiKey,
                                    ).fold(
                                        onSuccess = { ids ->
                                            val existing = cfg.models.associateBy { it.id }
                                            updateModels {
                                                ids.map { id -> existing[id] ?: ModelEntry(id) }
                                            }
                                            message = "已拉取 ${ids.size} 个模型"
                                        },
                                        onFailure = { message = "拉取失败：${it.message}" },
                                    )
                                    pulling = false
                                }
                            }
                        }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    MiuixIcon(Icons.Outlined.Refresh, null, tint = colors.primary, size = 20.dp)
                    Column(Modifier.weight(1f)) {
                        MiuixText(
                            text = stringResource(R.string.ai_pull_models),
                            style = MiuixTheme.typography.bodyLarge,
                        )
                        MiuixText(
                            text = if (pulling) stringResource(R.string.ai_pulling)
                            else stringResource(R.string.ai_pull_from_remote),
                            style = MiuixTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                MiuixDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { adding = true }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    MiuixIcon(Icons.Outlined.Add, null, tint = colors.primary, size = 20.dp)
                    MiuixText(
                        text = stringResource(R.string.ai_add_model),
                        style = MiuixTheme.typography.bodyLarge,
                    )
                }
            }
        }
        message?.let {
            Spacer(Modifier.height(spacing.sm))
            MiuixText(
                text = it,
                style = MiuixTheme.typography.bodySmall,
                color = if (it.startsWith("已拉取")) colors.success else colors.error,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Spacer(Modifier.height(spacing.groupGap))

        MiuixSectionCard(
            title = stringResource(R.string.ai_models),
            subtitle = stringResource(R.string.ai_models_total, cfg.models.size),
        ) {
            Column {
                Box(Modifier.padding(horizontal = spacing.md, vertical = spacing.sm)) {
                    MiuixTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = stringResource(R.string.ai_search_model),
                        leadingIcon = Icons.Outlined.Search,
                        singleLine = true,
                    )
                }
                if (shown.isEmpty()) {
                    MiuixText(
                        text = stringResource(R.string.ai_models_empty),
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
                shown.forEach { entry ->
                    val selected = entry.id == cfg.selectedModel
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { AiConfigStore.selectModel(context, entry.id) }
                                    .padding(start = 20.dp, top = 12.dp, bottom = 12.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    MiuixText(
                                        text = entry.label,
                                        style = MiuixTheme.typography.bodyLarge,
                                    )
                                    if (selected) {
                                        MiuixTag(text = stringResource(R.string.ai_current))
                                    }
                                }
                                MiuixText(
                                    text = entry.id,
                                    style = MiuixTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    entry.contextWindow?.let {
                                        MiuixTag(
                                            text = formatContext(it),
                                            color = colors.secondary,
                                        )
                                    }
                                    if (entry.supportsReasoning) {
                                        MiuixTag(
                                            text = stringResource(R.string.ai_supports_reasoning),
                                            color = colors.primary,
                                        )
                                    }
                                }
                            }
                            MiuixIconButton(
                                icon = Icons.Outlined.Edit,
                                contentDescription = null,
                                onClick = { editing = entry },
                                buttonSize = 40.dp,
                                iconSize = 18.dp,
                                tint = colors.onSurfaceVariant,
                            )
                            Box(
                                modifier = Modifier
                                    .padding(horizontal = 16.dp)
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (selected) colors.primary else colors.surfaceContainerHighest,
                                    )
                                    .clickable { AiConfigStore.selectModel(context, entry.id) },
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
                        MiuixDivider()
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    val editTarget = editing
    if (editTarget != null) {
        ModelEditDialog(
            entry = editTarget,
            onDismiss = { editing = null },
            onSave = { next ->
                updateModels { list -> list.map { if (it.id == editTarget.id) next else it } }
                if (editTarget.id != next.id && cfg.selectedModel == editTarget.id) {
                    AiConfigStore.selectModel(context, next.id)
                }
                editing = null
            },
            onDelete = {
                updateModels { list -> list.filterNot { it.id == editTarget.id } }
                if (cfg.selectedModel == editTarget.id) {
                    AiConfigStore.selectModel(context, "")
                }
                editing = null
            },
        )
    }

    if (adding) {
        ModelEditDialog(
            entry = ModelEntry(id = ""),
            isNew = true,
            onDismiss = { adding = false },
            onSave = { next ->
                updateModels { list -> list.filterNot { it.id == next.id } + next }
                AiConfigStore.selectModel(context, next.id)
                adding = false
            },
            onDelete = null,
        )
    }
}

/** 模型编辑弹窗。 */
@Composable
private fun ModelEditDialog(
    entry: ModelEntry,
    isNew: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (ModelEntry) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing

    var name by remember { mutableStateOf(entry.label) }
    var id by remember { mutableStateOf(entry.id) }
    var ctx by remember { mutableStateOf(entry.contextWindow?.toString().orEmpty()) }
    var supportsReasoning by remember { mutableStateOf(entry.supportsReasoning) }
    var effort by remember { mutableStateOf(ReasoningEffort.DEFAULT) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MiuixTheme.radius.dialog))
                .background(colors.surface)
                .padding(spacing.lg),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            MiuixText(
                text = stringResource(
                    if (isNew) R.string.ai_add_model else R.string.ai_edit_model,
                ),
                style = MiuixTheme.typography.titleMedium,
            )

            Column(
                modifier = Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                MiuixTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = stringResource(R.string.ai_model_name),
                    singleLine = true,
                    showClear = false,
                )
                MiuixTextField(
                    value = id,
                    onValueChange = { id = it },
                    placeholder = stringResource(R.string.ai_model_id),
                    singleLine = true,
                    showClear = false,
                )
                MiuixTextField(
                    value = ctx,
                    onValueChange = { ctx = it.filter { ch -> ch.isDigit() } },
                    placeholder = stringResource(R.string.ai_model_context),
                    singleLine = true,
                    showClear = false,
                )

                // 支持思考 + 档位
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    MiuixText(
                        text = stringResource(R.string.ai_supports_reasoning),
                        style = MiuixTheme.typography.bodyMedium,
                    )
                    MiuixIconButton(
                        icon = if (supportsReasoning) Icons.Outlined.Check else Icons.Outlined.Add,
                        contentDescription = null,
                        onClick = { supportsReasoning = !supportsReasoning },
                        filled = supportsReasoning,
                        buttonSize = 36.dp,
                        iconSize = 16.dp,
                    )
                }
                if (supportsReasoning) {
                    ReasoningEffort.entries.forEach { item ->
                        val active = item == effort
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(MiuixTheme.radius.field))
                                .background(
                                    if (active) colors.primary.copy(alpha = 0.10f)
                                    else androidx.compose.ui.graphics.Color.Transparent,
                                )
                                .clickable { effort = item }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (active) {
                                MiuixIcon(
                                    Icons.Outlined.Check,
                                    null,
                                    tint = colors.primary,
                                    size = 16.dp,
                                )
                            }
                            MiuixText(
                                text = item.label,
                                style = MiuixTheme.typography.bodyMedium,
                                color = if (active) colors.primary else colors.onSurface,
                            )
                        }
                    }
                }

                if (!isNew && onDelete != null) {
                    MiuixButton(
                        text = stringResource(R.string.ai_model_delete),
                        onClick = onDelete,
                        variant = com.mcp.toolbox.core.design.component.MiuixButtonVariant.TONAL,
                        leadingIcon = Icons.Outlined.Delete,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                MiuixButton(
                    text = stringResource(R.string.ai_model_cancel),
                    onClick = onDismiss,
                    variant = com.mcp.toolbox.core.design.component.MiuixButtonVariant.TONAL,
                )
                Spacer(Modifier.size(8.dp))
                MiuixButton(
                    text = stringResource(R.string.ai_save),
                    onClick = {
                        onSave(
                            ModelEntry(
                                id = id.trim(),
                                displayName = name.trim().takeIf { it != id.trim() }.orEmpty(),
                                contextWindow = ctx.toIntOrNull(),
                                supportsReasoning = supportsReasoning,
                            ),
                        )
                    },
                    enabled = id.isNotBlank(),
                )
            }
        }
    }
}

/** 1024000 -> 1M。 */
private fun formatContext(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M 上下文"
    tokens >= 1000 -> "${tokens / 1000}K 上下文"
    else -> "$tokens 上下文"
}

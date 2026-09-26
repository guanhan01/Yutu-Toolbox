package com.mcp.toolbox.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSwitch
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.feature.home.ChatMemoryStore
import com.mcp.toolbox.feature.home.MemoryEntry
import kotlinx.coroutines.launch

/**
 * 记忆管理。
 *
 * 分两块：核心记忆（用户与 AI 都能写，永远注入）与子记忆（AI 创建为主，
 * 可逐条开关是否给 AI 用）。
 *
 * 编辑都是**就地**的：核心记忆直接是一个多行输入框，改完点保存即可，
 * 不跳页、不弹二次确认；子记忆的正文也是多行框（短输入框放不下一条记忆）。
 *
 * 注意 [MiuixSectionCard] 的 contentPadding 默认是 0，卡片内的内容必须自带
 * 内边距，否则文字会顶到圆角上。这里统一用 [CARD_PADDING]。
 */
@Composable
fun AiMemoryScreen(modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val core by ChatMemoryStore.core.collectAsState()
    val entries by ChatMemoryStore.entries.collectAsState()
    val settings by ChatMemoryStore.settings.collectAsState()
    val modelWindow by ChatMemoryStore.modelWindow.collectAsState()

    val limit = ChatMemoryStore.effectiveLimitTokens()
    val used = ChatMemoryStore.usedTokens()

    // 核心记忆的就地编辑草稿：与 store 解耦，未点保存前不影响注入
    var coreDraft by remember(core) { mutableStateOf(core) }
    var editingEntry by remember { mutableStateOf<MemoryEntry?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var entryTitle by remember { mutableStateOf("") }
    var entryBody by remember { mutableStateOf("") }

    LaunchedEffect(Unit) { ChatMemoryStore.load(context) }

    fun beginEntry(entry: MemoryEntry?) {
        editingEntry = entry
        entryTitle = entry?.title.orEmpty()
        entryBody = entry?.content.orEmpty()
        showEditor = true
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState()),
    ) {
        Spacer(Modifier.height(spacing.sm))

        // ---------- 用量 ----------
        MiuixSectionCard(
            title = stringResource(R.string.memory_usage_title),
            subtitle = if (modelWindow > 0) {
                stringResource(R.string.memory_usage_hint, (modelWindow * 0.05f).toInt())
            } else {
                // 窗口未知时说「0 token」会让人以为记忆被禁用了，改用明确说明
                stringResource(R.string.memory_usage_hint_unknown)
            },
            modifier = Modifier.padding(horizontal = spacing.pageHorizontal),
            contentPadding = CARD_PADDING,
        ) {
            MiuixText(
                text = stringResource(R.string.memory_usage_detail, used, limit),
                style = MiuixTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            // 用量条：一眼看出还剩多少额度
            UsageBar(used = used, limit = limit)
            Spacer(Modifier.height(spacing.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiuixText(
                    text = stringResource(R.string.memory_enabled),
                    style = MiuixTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                MiuixSwitch(
                    checked = settings.enabled,
                    onCheckedChange = { scope.launch { ChatMemoryStore.setEnabled(context, it) } },
                )
            }
        }

        Spacer(Modifier.height(spacing.groupGap))

        // ---------- 核心记忆（就地编辑） ----------
        MiuixSectionCard(
            title = stringResource(R.string.memory_core_title),
            subtitle = stringResource(R.string.memory_core_desc),
            modifier = Modifier.padding(horizontal = spacing.pageHorizontal),
            contentPadding = CARD_PADDING,
        ) {
            MiuixTextField(
                value = coreDraft,
                onValueChange = { coreDraft = it },
                placeholder = stringResource(R.string.memory_core_empty),
                // 核心记忆是一整段文字，必须多行；单行框连一句话都看不全
                singleLine = false,
                minLines = 4,
                showClear = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.sm))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                MiuixButton(
                    text = stringResource(R.string.memory_save),
                    onClick = {
                        scope.launch { ChatMemoryStore.saveCore(context, coreDraft.trim()) }
                    },
                    enabled = coreDraft != core,
                )
            }
        }

        Spacer(Modifier.height(spacing.groupGap))

        // ---------- 子记忆 ----------
        MiuixSectionCard(
            title = stringResource(R.string.memory_entries_title),
            subtitle = stringResource(R.string.memory_entries_desc),
            modifier = Modifier.padding(horizontal = spacing.pageHorizontal),
            contentPadding = CARD_PADDING,
        ) {
            if (entries.isEmpty()) {
                MiuixText(
                    text = stringResource(R.string.memory_entries_empty),
                    style = MiuixTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.sm))
            }
            entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(MiuixTheme.radius.sm))
                        .clickable { beginEntry(entry) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiuixText(
                                text = entry.title,
                                style = MiuixTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (entry.fromAi) {
                                Spacer(Modifier.width(6.dp))
                                MiuixTag(text = stringResource(R.string.memory_from_ai))
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        MiuixText(
                            text = entry.content,
                            style = MiuixTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    MiuixSwitch(
                        checked = entry.enabled,
                        onCheckedChange = { on ->
                            scope.launch { ChatMemoryStore.setEntryEnabled(context, entry.id, on) }
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    MiuixIconButton(
                        icon = Icons.Outlined.Delete,
                        contentDescription = stringResource(R.string.memory_delete),
                        onClick = {
                            scope.launch { ChatMemoryStore.deleteEntry(context, entry.id) }
                        },
                        buttonSize = 36.dp,
                        iconSize = 18.dp,
                    )
                }
            }
            Spacer(Modifier.height(spacing.sm))
            MiuixButton(
                text = stringResource(R.string.memory_new),
                onClick = { beginEntry(null) },
            )
        }

        Spacer(Modifier.height(spacing.xxl))
    }

    if (showEditor) {
        Dialog(
            onDismissRequest = {
                showEditor = false
                editingEntry = null
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(MiuixTheme.radius.dialog))
                    .background(colors.surface)
                    .padding(spacing.lg),
            ) {
                MiuixText(
                    text = stringResource(
                        if (editingEntry == null) R.string.memory_new else R.string.memory_edit,
                    ),
                    style = MiuixTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(spacing.md))
                MiuixTextField(
                    value = entryTitle,
                    onValueChange = { entryTitle = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = stringResource(R.string.memory_title_hint),
                    singleLine = true,
                    showClear = false,
                )
                Spacer(Modifier.height(spacing.sm))
                // 正文给足高度：一条记忆通常好几行，小框里没法改
                MiuixTextField(
                    value = entryBody,
                    onValueChange = { entryBody = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 140.dp),
                    singleLine = false,
                    minLines = 6,
                    showClear = false,
                )
                Spacer(Modifier.height(spacing.lg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    MiuixButton(
                        text = stringResource(R.string.memory_cancel),
                        onClick = {
                            showEditor = false
                            editingEntry = null
                        },
                    )
                    Spacer(Modifier.width(spacing.sm))
                    MiuixButton(
                        text = stringResource(R.string.memory_save),
                        enabled = entryTitle.isNotBlank() && entryBody.isNotBlank(),
                        onClick = {
                            val title = entryTitle.trim()
                            val body = entryBody.trim()
                            val target = editingEntry
                            scope.launch {
                                if (target == null) {
                                    ChatMemoryStore.upsert(context, title, body, fromAi = false)
                                } else {
                                    ChatMemoryStore.updateEntry(
                                        context,
                                        target.copy(title = title, content = body),
                                    )
                                }
                            }
                            showEditor = false
                            editingEntry = null
                        },
                    )
                }
            }
        }
    }
}

/** 卡片内边距：SectionCard 默认是 0，内容必须自带。 */
private val CARD_PADDING = PaddingValues(horizontal = 16.dp, vertical = 14.dp)

/** 记忆用量条。 */
@Composable
private fun UsageBar(used: Int, limit: Int) {
    val colors = MiuixTheme.colors
    val ratio = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
    Column {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.surfaceContainerHigh),
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth(ratio)
                    .height(4.dp)
                    .background(
                        if (ratio >= 0.9f) colors.error else colors.primary,
                        RoundedCornerShape(2.dp),
                    ),
            )
        }
    }
}

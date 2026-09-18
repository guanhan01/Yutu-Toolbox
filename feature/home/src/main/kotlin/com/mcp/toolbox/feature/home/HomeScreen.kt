package com.mcp.toolbox.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Wifi
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch

/**
 * 首页：AI Agent 对话界面。
 *
 * 会话与消息由 [ChatStore] 本地保管；真正的模型调用通过 [onSend] 注入，
 * 由宿主（app 模块）决定用哪个服务商与密钥，本模块不依赖具体实现。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
    onSend: suspend (List<ChatMessage>) -> Result<String> = { Result.failure(IllegalStateException("no sender")) },
    currentModel: String = "",
    currentReasoning: String = "",
    availableModels: List<String> = emptyList(),
    availableReasoning: List<String> = emptyList(),
    onSelectModel: (String) -> Unit = {},
    onSelectReasoning: (String) -> Unit = {},
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sessions by ChatStore.sessions.collectAsState()
    val currentId by ChatStore.currentId.collectAsState()
    val session = sessions.firstOrNull { it.id == currentId }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<PickerKind?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { ChatStore.load(context) }

    val messageCount = session?.messages?.size ?: 0
    LaunchedEffect(messageCount) {
        if (messageCount > 0) listState.animateScrollToItem(messageCount - 1)
    }

    val newChatTitle = stringResource(R.string.chat_title)
    val errNetwork = stringResource(R.string.chat_err_network)

    fun submit(text: String) {
        val content = text.trim()
        if (content.isEmpty() || sending) return
        input = ""
        sending = true
        scope.launch {
            try {
                val target = session ?: ChatStore.newSession(context, content.take(24))
                val userMessage = ChatMessage(role = ChatMessage.Role.USER, content = content)
                ChatStore.append(context, target.id, userMessage)

                val history = (target.messages + userMessage)
                    .filter { it.role != ChatMessage.Role.SYSTEM || it.content.isNotBlank() }
                val result = onSend(history)
                val reply = result.getOrElse { errNetwork.format(it.message ?: "") }
                ChatStore.append(
                    context, target.id,
                    ChatMessage(role = ChatMessage.Role.ASSISTANT, content = reply),
                )
            } finally {
                sending = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        MiuixTopAppBar(
            title = session?.displayTitle ?: stringResource(R.string.chat_title),
            navigationIcon = Icons.Outlined.Menu,
            onNavigationClick = onOpenDrawer,
            actions = {
                MiuixIconButton(
                    icon = Icons.Outlined.Add,
                    contentDescription = stringResource(R.string.chat_new),
                    onClick = {
                        if (!sending) scope.launch { ChatStore.newSession(context, newChatTitle) }
                    },
                )
            },
        )

        Box(Modifier.weight(1f)) {
            if (messageCount == 0) {
                ChatSuggestions(onPick = { submit(it) })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = spacing.pageHorizontal,
                        end = spacing.pageHorizontal,
                        top = spacing.sm,
                        bottom = spacing.md,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(session!!.messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                    if (sending) {
                        item(key = "pending") { PendingBubble() }
                    }
                }
            }
        }

        // 输入栏上方的模型与思考档位入口
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageHorizontal, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            if (currentModel.isNotBlank()) {
                SelectorChip(
                    text = currentModel,
                    onClick = { picker = PickerKind.MODEL },
                )
            }
            if (currentReasoning.isNotBlank()) {
                SelectorChip(
                    text = currentReasoning,
                    onClick = { picker = PickerKind.REASONING },
                )
            }
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            sending = sending,
            onSend = { submit(input) },
            onStop = { /* 目前为非流式请求，暂不支持中断 */ },
        )
    }

    picker?.let { kind ->
        PickerSheet(
            kind = kind,
            options = if (kind == PickerKind.MODEL) availableModels else availableReasoning,
            selected = if (kind == PickerKind.MODEL) currentModel else currentReasoning,
            onPick = { value ->
                if (kind == PickerKind.MODEL) onSelectModel(value) else onSelectReasoning(value)
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
}

/** 可选的两类弹层。 */
private enum class PickerKind { MODEL, REASONING }

/** 小圆角选择入口。 */
@Composable
private fun SelectorChip(text: String, onClick: () -> Unit) {
    val colors = MiuixTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MiuixText(
            text = text,
            style = MiuixTheme.typography.labelMedium,
            color = colors.onSurface,
            maxLines = 1,
        )
        MiuixIcon(
            Icons.Outlined.ExpandMore,
            null,
            tint = colors.onSurfaceVariant,
            size = 16.dp,
        )
    }
}

/** 模型 / 档位选择弹层。 */
@Composable
private fun PickerSheet(
    kind: PickerKind,
    options: List<String>,
    selected: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MiuixTheme.colors
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MiuixTheme.radius.dialog))
                .background(colors.surface)
                .padding(vertical = 16.dp),
        ) {
            MiuixText(
                text = stringResource(
                    if (kind == PickerKind.MODEL) R.string.chat_pick_model
                    else R.string.chat_pick_reasoning,
                ),
                style = MiuixTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (options.isEmpty()) {
                    MiuixText(
                        text = stringResource(R.string.chat_pick_empty),
                        style = MiuixTheme.typography.bodySmall,
                        color = MiuixTheme.colors.onSurfaceVariant,
                    )
                }
                options.forEach { option ->
                    val active = option == selected
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MiuixTheme.radius.field))
                            .background(
                                if (active) MiuixTheme.colors.primary.copy(alpha = 0.10f)
                                else Color.Transparent,
                            )
                            .clickable { onPick(option) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MiuixText(
                            text = option,
                            style = MiuixTheme.typography.bodyMedium,
                            color = if (active) MiuixTheme.colors.primary
                            else MiuixTheme.colors.onSurface,
                        )
                    }
                }
            }
        }
    }
}

/** 输入栏：发送按钮内嵌在气泡里；有内容时气泡变白。 */
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val active = value.isNotBlank()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(
                start = spacing.pageHorizontal,
                end = spacing.pageHorizontal,
                top = spacing.sm,
                bottom = spacing.sm,
            ),
        verticalAlignment = Alignment.Bottom,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(MiuixTheme.radius.lg))
                .background(if (active || sending) colors.surface else colors.surfaceContainerLow)
                .padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box(
                modifier = Modifier.weight(1f).padding(bottom = 6.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    MiuixText(
                        text = stringResource(R.string.chat_input_hint),
                        style = MiuixTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MiuixTheme.typography.bodyMedium.copy(color = colors.onSurface),
                    cursorBrush = SolidColor(colors.primary),
                    maxLines = 6,
                )
            }
            MiuixIconButton(
                icon = if (sending) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward,
                contentDescription = stringResource(R.string.chat_send),
                onClick = { if (sending) onStop() else onSend() },
                filled = active || sending,
                enabled = active || sending,
                buttonSize = 36.dp,
                iconSize = 18.dp,
            )
        }
    }
}

/** 空态：标题 + 常用能力卡片。点击直接发起一次对话。 */
@Composable
private fun ChatSuggestions(onPick: (String) -> Unit) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing

    val items = listOf(
        Icons.Outlined.Terminal to stringResource(R.string.chat_sug_apk),
        Icons.Outlined.Smartphone to stringResource(R.string.chat_sug_screen),
        Icons.Outlined.Wifi to stringResource(R.string.chat_sug_network),
        Icons.Outlined.Storage to stringResource(R.string.chat_sug_db),
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.pageHorizontal),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MiuixIcon(
            Icons.Outlined.SmartToy,
            null,
            tint = colors.onSurfaceVariant,
            size = 36.dp,
        )
        Spacer(Modifier.height(spacing.md))
        MiuixText(
            text = stringResource(R.string.chat_hero_title),
            style = MiuixTheme.typography.titleLarge,
        )
        Spacer(Modifier.height(spacing.xl))
        items.chunked(2).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                rowItems.forEach { (icon, label) ->
                    SuggestionCard(
                        icon = icon,
                        label = label,
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(label) },
                    )
                }
            }
            Spacer(Modifier.height(spacing.sm))
        }
    }
}

@Composable
private fun SuggestionCard(
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(MiuixTheme.radius.md))
            .background(colors.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MiuixIcon(icon, null, tint = colors.primary, size = 20.dp)
        MiuixText(
            text = label,
            style = MiuixTheme.typography.bodyMedium,
            maxLines = 2,
        )
    }
}

/** 等待回复时的占位气泡。 */
@Composable
private fun PendingBubble() {
    val colors = MiuixTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(MiuixTheme.radius.md))
                .background(colors.surfaceContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            MiuixText(
                text = stringResource(R.string.chat_thinking),
                style = MiuixTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** 单条消息气泡：用户靠右、AI 靠左、系统提示居中浅色。 */
@Composable
private fun MessageBubble(message: ChatMessage) {
    val colors = MiuixTheme.colors
    val radius = RoundedCornerShape(MiuixTheme.radius.md)
    val isUser = message.role == ChatMessage.Role.USER
    val isSystem = message.role == ChatMessage.Role.SYSTEM

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = when {
            isSystem -> Arrangement.Center
            isUser -> Arrangement.End
            else -> Arrangement.Start
        },
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(radius)
                .background(
                    when {
                        isUser -> colors.primary
                        isSystem -> colors.surfaceContainerHigh
                        else -> colors.surfaceContainer
                    },
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            MiuixText(
                text = message.content,
                style = MiuixTheme.typography.bodyMedium,
                color = when {
                    isUser -> colors.onPrimary
                    isSystem -> colors.onSurfaceVariant
                    else -> colors.onSurface
                },
            )
        }
    }
}

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.SmartToy
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch

/**
 * 首页：AI Agent 对话界面。
 *
 * 消息全部保存在本地会话中（[ChatStore]），侧边栏的「对话」可展开查看历史会话。
 * 目前尚未接入具体模型：发送的内容会入档，并以一条系统消息提示需要配置模型。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sessions by ChatStore.sessions.collectAsState()
    val currentId by ChatStore.currentId.collectAsState()
    val session = sessions.firstOrNull { it.id == currentId }
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { ChatStore.load(context) }

    val messageCount = session?.messages?.size ?: 0
    LaunchedEffect(messageCount) {
        if (messageCount > 0) listState.animateScrollToItem(messageCount - 1)
    }

    val promptNoModel = stringResource(R.string.chat_no_model)
    val newChatTitle = stringResource(R.string.chat_title)

    fun submit(text: String) {
        val content = text.trim()
        if (content.isEmpty()) return
        scope.launch {
            val target = session ?: ChatStore.newSession(context, content.take(24))
            ChatStore.append(
                context, target.id,
                ChatMessage(role = ChatMessage.Role.USER, content = content),
            )
            ChatStore.append(
                context, target.id,
                ChatMessage(role = ChatMessage.Role.SYSTEM, content = promptNoModel),
            )
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
                        scope.launch { ChatStore.newSession(context, newChatTitle) }
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
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .navigationBarsPadding()
                .imePadding()
                .padding(
                    start = spacing.pageHorizontal,
                    end = spacing.pageHorizontal,
                    top = spacing.sm,
                    bottom = spacing.sm,
                ),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        ) {
            Box(Modifier.weight(1f)) {
                MiuixTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = stringResource(R.string.chat_input_hint),
                    singleLine = false,
                    minLines = 1,
                )
            }
            MiuixIconButton(
                icon = Icons.Outlined.Send,
                contentDescription = stringResource(R.string.chat_send),
                onClick = { submit(input); input = "" },
                filled = true,
                enabled = input.isNotBlank(),
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

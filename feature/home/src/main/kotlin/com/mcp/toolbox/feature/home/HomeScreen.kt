package com.mcp.toolbox.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalClipboardManager
import com.mcp.toolbox.core.design.component.MiuixBottomSheet
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixMenuDivider
import com.mcp.toolbox.core.design.component.MiuixMenuGroupLabel
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
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
    /**
     * 启动一次请求。实现方应在应用级作用域里执行，
     * 这样离开页面或进后台都不会中断。
     */
    onStart: (sessionId: String, history: List<ChatMessage>) -> Unit = { _, _ -> },
    /** 当前正在流式接收的文本；未进行时为 null。 */
    runningText: String? = null,
    /** 本轮已发生的工具调用（实时更新，结束时才落盘成正式消息）。 */
    runningTools: List<RunningTool> = emptyList(),
    /** 正在进行的深度思考文本。 */
    runningReasoning: String? = null,
    /** 是否已有请求在执行（可能刚发出、还没有增量）。 */
    running: Boolean = false,
    onStop: () -> Unit = {},
    currentModel: String = "",
    currentReasoning: String = "",
    /** 当前服务商的品牌图标，用作模型选择入口的图标。 */
    @androidx.annotation.DrawableRes providerIconRes: Int = 0,
    availableModels: List<String> = emptyList(),
    availableReasoning: List<String> = emptyList(),
    /** 跨服务商的可选模型，仅含已拉取过列表的服务商。 */
    modelOptions: List<ModelOption> = emptyList(),
    onSelectModel: (String) -> Unit = {},
    onSelectReasoning: (String) -> Unit = {},
    /** 选中跨厂模型的某个模型：需要同时切换服务商与模型。 */
    onSelectModelOption: (ModelOption) -> Unit = {},
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val sessions by ChatStore.sessions.collectAsState()
    val currentId by ChatStore.currentId.collectAsState()
    val session = sessions.firstOrNull { it.id == currentId }
    var input by remember { mutableStateOf("") }
    val sending = running
    var picker by remember { mutableStateOf<PickerKind?>(null) }
    var showOverflow by remember { mutableStateOf(false) }
    // 加号按钮在窗口中的位置，用作溢出菜单的锚点
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<ChatAttachment>() }
    val clipboard = LocalClipboardManager.current
    /** 长按弹出的消息操作菜单目标；编辑弹层复用同一个目标。 */
    var actionTarget by remember { mutableStateOf<ChatMessage?>(null) }
    /** 正在编辑的用户消息：非空时输入栏处于编辑态。 */
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }

    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        uri?.let {
            attachments += ChatAttachment.Image(
                uri = it.toString(),
                label = ChatAttachment.displayName(context, it),
            )
        }
    }
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            attachments += ChatAttachment.File(
                uri = it.toString(),
                label = ChatAttachment.displayName(context, it),
            )
        }
    }
    val pickFolder = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        uri?.let {
            attachments += ChatAttachment.Folder(
                uri = it.toString(),
                label = it.lastPathSegment?.substringAfterLast(':') ?: "文件夹",
            )
        }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(Unit) { ChatStore.load(context) }

    val messageCount = session?.messages?.size ?: 0
    LaunchedEffect(messageCount) {
        if (messageCount > 0) listState.animateScrollToItem(messageCount - 1)
    }

    val newChatTitle = stringResource(R.string.chat_title)

    val attachContext = stringResource(R.string.chat_attach_context)
    val toolRunning = stringResource(R.string.chat_tool_running)

    fun submit(text: String) {
        val content = text.trim()
        if ((content.isEmpty() && attachments.isEmpty()) || sending) return
        val attached = attachments.toList()
        input = ""
        attachments.clear()
        scope.launch {
            val title = content.ifBlank { attached.firstOrNull()?.label ?: "附件" }
            val target = session ?: ChatStore.newSession(context, title.take(24))

            // 图片走多模态，其余附件转成文本上下文
            val imageUris = attached.filterIsInstance<ChatAttachment.Image>().map { it.uri }
            val textAttachments = attached.filterNot { it is ChatAttachment.Image }

            val userMessage = ChatMessage(
                role = ChatMessage.Role.USER,
                content = content,
                imageUris = imageUris,
            )
            // 落盘只留文本，避免临时 URI 进会话记录
            ChatStore.append(context, target.id, userMessage.copy(imageUris = emptyList()))

            val withAttachments = if (textAttachments.isEmpty()) {
                listOf(userMessage)
            } else {
                listOf(
                    userMessage,
                    ChatMessage(
                        role = ChatMessage.Role.USER,
                        content = attachContext + "\n" +
                            textAttachments.joinToString("\n\n") { it.toContext(context) },
                    ),
                )
            }
            val history = (target.messages + withAttachments)
                .filter { it.role != ChatMessage.Role.SYSTEM || it.content.isNotBlank() }
            // 交给外部任务持有者执行，离开页面也不会中断
            onStart(target.id, history)
        }
    }

    /** 从某条用户消息重新生成：截断它之后的全部消息，把含它的历史交回模型。 */
    fun regenerateFrom(userMessage: ChatMessage) {
        val current = session ?: return
        if (userMessage.role != ChatMessage.Role.USER) return
        scope.launch {
            val kept = ChatStore.truncateAfter(context, current.id, userMessage.id)
            val history = (kept + userMessage)
                .filter { it.role != ChatMessage.Role.SYSTEM || it.content.isNotBlank() }
            if (history.isNotEmpty()) onStart(current.id, history)
        }
    }

    /** 应用用户编辑：更新消息文本与图片，并从这条消息重新生成回复。 */
    fun applyEdit(message: ChatMessage, newText: String, newImages: List<String>) {
        val current = session ?: return
        scope.launch {
            val kept = ChatStore.truncateAfter(context, current.id, message.id)
            ChatStore.updateMessage(
                context, current.id, message.id,
                content = newText,
                imageUris = newImages,
            )
            val updated = kept + message.copy(
                content = newText,
                imageUris = newImages,
                edited = true,
            )
            val history = updated
                .filter { it.role != ChatMessage.Role.SYSTEM || it.content.isNotBlank() }
            if (history.isNotEmpty()) onStart(current.id, history)
        }
    }

    /** 进入编辑态：内容预填进输入栏，附件可经加号菜单追加，点发送确认。 */
    fun beginEdit(message: ChatMessage) {
        editingMessage = message
        input = message.content
        attachments.clear()
    }

    /** 确认编辑：输入栏内容 +（可选）附件写回消息并重新生成。 */
    fun confirmEdit() {
        val message = editingMessage ?: return
        val content = input.trim()
        if (content.isEmpty() || sending) return
        val attached = attachments.toList()
        input = ""
        attachments.clear()
        editingMessage = null
        val imageUris = attached.filterIsInstance<ChatAttachment.Image>().map { it.uri }
        val textAttachments = attached.filterNot { it is ChatAttachment.Image }
        val finalContent = if (textAttachments.isEmpty()) content
        else content + "\n\n" + attachContext + "\n" +
            textAttachments.joinToString("\n\n") { it.toContext(context) }
        scope.launch { applyEdit(message, finalContent, imageUris) }
    }

    fun cancelEdit() {
        editingMessage = null
        input = ""
        attachments.clear()
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
                // 新建对话
                MiuixIconButton(
                    icon = Icons.Outlined.AddComment,
                    contentDescription = stringResource(R.string.chat_new),
                    onClick = {
                        if (!sending) scope.launch { ChatStore.newSession(context, newChatTitle) }
                    },
                    buttonSize = 40.dp,
                    iconSize = 20.dp,
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
                        when (message.role) {
                            ChatMessage.Role.REASONING -> ReasoningBlock(message.content)
                            ChatMessage.Role.TOOL -> ToolStepCard(
                                name = message.toolName,
                                arguments = message.toolArguments,
                                result = message.content,
                            )

                            else -> MessageBubble(
                                message = message,
                                onCopy = {
                                    clipboard.setText(
                                        androidx.compose.ui.text.AnnotatedString(message.content),
                                    )
                                },
                                onResend = {
                                    if (!sending) scope.launch { regenerateFrom(message) }
                                },
                                onDelete = {
                                    if (!sending) scope.launch {
                                        ChatStore.deleteMessage(context, session!!.id, message.id)
                                    }
                                },
                                onEdit = { if (!sending) beginEdit(message) },
                            )
                        }
                    }
                    if (sending) {
                        // 思考、工具调用、流式文本会同时存在，必须各自成条按顺序渲染。
                        // 之前是一个互斥的 when，思考一有内容就把工具卡片整个挡掉了，
                        // 于是「调用 xxx」只能等思考结束、消息落盘后才出现。
                        if (!runningReasoning.isNullOrEmpty()) {
                            item(key = "pending-reasoning") {
                                ReasoningBlock(text = runningReasoning, live = true)
                            }
                        }
                        runningTools.forEachIndexed { index, step ->
                            item(key = "pending-tool-$index") {
                                ToolStepCard(
                                    name = step.name,
                                    arguments = step.arguments,
                                    result = step.result,
                                    running = step.result.isEmpty(),
                                )
                            }
                        }
                        if (!runningText.isNullOrEmpty()) {
                            item(key = "pending-text") {
                                MessageBubble(
                                    ChatMessage(
                                        role = ChatMessage.Role.ASSISTANT,
                                        content = runningText,
                                    ),
                                )
                            }
                        }
                        if (runningReasoning.isNullOrEmpty() &&
                            runningTools.isEmpty() &&
                            runningText.isNullOrEmpty()
                        ) {
                            item(key = "pending") { PendingBubble() }
                        }
                    }
                }
            }
        }

        // 待发送的附件
        if (attachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageHorizontal, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            ) {
                attachments.forEachIndexed { index, item ->
                    AttachmentChip(
                        label = item.label,
                        onRemove = { attachments.removeAt(index) },
                    )
                }
            }
        }

        InputBar(
            value = input,
            onValueChange = { input = it },
            sending = sending,
            editing = editingMessage != null,
            onCancelEdit = { cancelEdit() },
            onSend = { if (editingMessage != null) confirmEdit() else submit(input) },
            onStop = onStop,
            onAttach = { showOverflow = true },
            onPickModel = { picker = PickerKind.MODEL },
            onPickReasoning = { picker = PickerKind.REASONING },
            providerIconRes = providerIconRes,
            providerIconLabel = stringResource(R.string.chat_pick_model),
            reasoningActive = currentReasoning != "Default",
        )
    }

    // 复用项目统一的锚定式溢出菜单，锚点取自输入栏加号按钮
    MiuixOverflowMenu(
        expanded = showOverflow,
        onDismiss = { showOverflow = false },
        // 加号在输入栏左侧、屏幕底部：贴底 + 左对齐
        alignStart = true,
        stickToBottom = true,
    ) {
        MiuixMenuItem(
            text = stringResource(R.string.chat_pick_model),
            icon = Icons.Outlined.SwapHoriz,
            onClick = { showOverflow = false; picker = PickerKind.MODEL },
        )
        MiuixMenuItem(
            text = stringResource(R.string.chat_pick_reasoning),
            icon = Icons.Outlined.Psychology,
            onClick = { showOverflow = false; picker = PickerKind.REASONING },
        )
        MiuixMenuDivider()
        MiuixMenuItem(
            text = stringResource(R.string.chat_attach_image),
            icon = Icons.Outlined.Image,
            onClick = {
                showOverflow = false
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
        MiuixMenuItem(
            text = stringResource(R.string.chat_attach_file),
            icon = Icons.Outlined.Description,
            onClick = { showOverflow = false; pickFile.launch(arrayOf("*/*")) },
        )
        MiuixMenuItem(
            text = stringResource(R.string.chat_attach_folder),
            icon = Icons.Outlined.FolderOpen,
            onClick = { showOverflow = false; pickFolder.launch(null) },
        )
        MiuixMenuItem(
            text = stringResource(R.string.chat_attach_path),
            icon = Icons.Outlined.Edit,
            onClick = { showOverflow = false; showPathDialog = true },
        )
    }


    if (showPathDialog) {
        PathDialog(
            value = pathInput,
            onValueChange = { pathInput = it },
            onConfirm = {
                attachments += ChatAttachment.Path(pathInput.trim())
                pathInput = ""
                showPathDialog = false
            },
            onDismiss = { showPathDialog = false },
        )
    }

    // 模型：锚定到右下角的模型按钮，右对齐展开
    MiuixOverflowMenu(
        expanded = picker == PickerKind.MODEL,
        onDismiss = { picker = null },
        stickToBottom = true,
    ) {
        if (modelOptions.isEmpty() && availableModels.isEmpty()) {
            MiuixMenuItem(
                text = stringResource(R.string.chat_pick_empty),
                enabled = false,
                onClick = {},
            )
        }
        if (modelOptions.isEmpty()) {
            availableModels.forEach { id ->
                MiuixMenuItem(
                    text = id,
                    checked = id == currentModel,
                    onClick = { onSelectModel(id); picker = null },
                )
            }
        } else {
            var lastProvider = ""
            modelOptions.forEach { option ->
                if (option.providerName != lastProvider) {
                    if (lastProvider.isNotEmpty()) MiuixMenuDivider()
                    MiuixMenuGroupLabel(text = option.providerTitle)
                    lastProvider = option.providerName
                }
                MiuixMenuItem(
                    text = option.modelId,
                    checked = option.isCurrent,
                    onClick = { onSelectModelOption(option); picker = null },
                )
            }
        }
    }

    // 思考档位：锚定到左下角的思考按钮，左对齐展开
    MiuixOverflowMenu(
        expanded = picker == PickerKind.REASONING,
        onDismiss = { picker = null },
        alignStart = true,
        stickToBottom = true,
    ) {
        availableReasoning.forEach { label ->
            MiuixMenuItem(
                text = label,
                checked = label == currentReasoning,
                onClick = { onSelectReasoning(label); picker = null },
            )
        }
    }
}

/** 已选附件的预览小块，点 × 移除。 */
@Composable
private fun AttachmentChip(label: String, onRemove: () -> Unit) {
    val colors = MiuixTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.surfaceContainerLow)
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MiuixText(
            text = label,
            style = MiuixTheme.typography.labelMedium,
            color = colors.onSurface,
            maxLines = 1,
        )
        MiuixIconButton(
            icon = Icons.Outlined.Close,
            contentDescription = stringResource(R.string.chat_attach),
            onClick = onRemove,
            buttonSize = 26.dp,
            iconSize = 14.dp,
        )
    }
}




/** 手动输入文件路径。 */
@Composable
private fun PathDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
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
                text = stringResource(R.string.chat_attach_path),
                style = MiuixTheme.typography.titleMedium,
            )
            MiuixTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = stringResource(R.string.chat_attach_path_hint),
                singleLine = true,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                MiuixButton(
                    text = stringResource(R.string.chat_attach_path_confirm),
                    onClick = onConfirm,
                    enabled = value.isNotBlank(),
                )
            }
        }
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




/** 输入栏：发送按钮内嵌在气泡里；有内容时气泡变白。 */
@Composable
private fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    sending: Boolean,
    editing: Boolean = false,
    onCancelEdit: () -> Unit = {},
    onSend: () -> Unit,
    onStop: () -> Unit,
    onAttach: () -> Unit,
    onPickModel: () -> Unit,
    onPickReasoning: () -> Unit,
    @androidx.annotation.DrawableRes providerIconRes: Int,
    providerIconLabel: String,
    reasoningActive: Boolean,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val active = value.isNotBlank()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(
                start = spacing.pageHorizontal,
                end = spacing.pageHorizontal,
                top = spacing.sm,
                bottom = spacing.sm,
            )
            .clip(RoundedCornerShape(MiuixTheme.radius.lg))
            .background(colors.surface)
            .padding(start = 14.dp, end = 10.dp, top = 12.dp, bottom = 8.dp),
    ) {
        // 编辑态横条：提示正在编辑，可取消
        if (editing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiuixIcon(
                    Icons.Outlined.Edit,
                    null,
                    tint = colors.primary,
                    size = 16.dp,
                )
                Spacer(Modifier.width(6.dp))
                MiuixText(
                    text = stringResource(R.string.chat_editing_hint),
                    style = MiuixTheme.typography.labelMedium,
                    color = colors.primary,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onCancelEdit),
                    contentAlignment = Alignment.Center,
                ) {
                    MiuixIcon(
                        Icons.Outlined.Close,
                        stringResource(R.string.chat_cancel),
                        tint = colors.onSurfaceVariant,
                        size = 16.dp,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        // 第一行：输入
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 28.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                MiuixText(
                    text = stringResource(if (editing) R.string.chat_editing_hint else R.string.chat_input_hint),
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

        Spacer(Modifier.height(6.dp))

        // 第二行：左（附件 / 思考）  右（模型 / 发送）
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 自己包一层：MiuixIconButton 的 modifier 不落到实际节点上，
            // onGloballyPositioned 不会触发，anchor 就永远是 null。
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onAttach),
                contentAlignment = Alignment.Center,
            ) {
                MiuixIcon(
                    Icons.Outlined.Add,
                    stringResource(R.string.chat_attach),
                    tint = colors.onSurfaceVariant,
                    size = 20.dp,
                )
            }
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onPickReasoning),
                contentAlignment = Alignment.Center,
            ) {
                MiuixIcon(
                    Icons.Outlined.Psychology,
                    stringResource(R.string.chat_pick_reasoning),
                    tint = if (reasoningActive) colors.primary else colors.onSurfaceVariant,
                    size = 20.dp,
                )
            }

            Spacer(Modifier.weight(1f))

            // 模型：品牌色圆底 + 白色图形，放在发送键左侧
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onPickModel),
                contentAlignment = Alignment.Center,
            ) {
                if (providerIconRes != 0) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(colors.onSurface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(providerIconRes),
                            contentDescription = providerIconLabel,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else {
                    MiuixIcon(
                        Icons.Outlined.SwapHoriz,
                        null,
                        tint = colors.onSurfaceVariant,
                        size = 20.dp,
                    )
                }
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

/**
 * 深度思考折叠块。
 *
 * 推理文本与期间的工具调用合并展示在同一块里，默认收起；
 * [live] 为 true 时表示仍在推理，标题会动态提示。
 */
@Composable
private fun ReasoningBlock(text: String, live: Boolean = false) {
    val colors = MiuixTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiuixTheme.radius.md))
            .background(colors.surfaceContainerLow)
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            MiuixIcon(
                Icons.Outlined.Psychology,
                null,
                tint = colors.primary,
                size = 16.dp,
            )
            MiuixText(
                text = if (live) stringResource(R.string.chat_reasoning_live)
                else stringResource(R.string.chat_reasoning_done),
                style = MiuixTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier.weight(1f),
            )
            MiuixIcon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                null,
                tint = colors.onSurfaceVariant,
                size = 16.dp,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            MiuixText(
                text = text,
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * 工具调用步骤卡片。
 *
 * 与图二的形态一致：一行标题（扳手图标 + 工具名 + 状态），展开后是参数与结果。
 */
@Composable
private fun ToolStepCard(
    name: String,
    arguments: String,
    result: String,
    running: Boolean = false,
) {
    val colors = MiuixTheme.colors
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiuixTheme.radius.md))
            .background(colors.surfaceContainerLow)
            .clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MiuixIcon(
                Icons.Outlined.Build,
                null,
                tint = colors.onSurfaceVariant,
                size = 16.dp,
            )
            MiuixText(
                text = if (running) {
                    stringResource(R.string.chat_tool_running_short, name)
                } else {
                    stringResource(R.string.chat_tool_done, name)
                },
                style = MiuixTheme.typography.labelMedium,
                color = colors.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (!running) {
                MiuixIcon(
                    Icons.Outlined.Check,
                    null,
                    tint = colors.success,
                    size = 14.dp,
                )
            }
            MiuixIcon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                null,
                tint = colors.onSurfaceVariant,
                size = 16.dp,
            )
        }

        if (expanded) {
            Spacer(Modifier.height(6.dp))
            if (arguments.isNotBlank() && arguments != "{}") {
                MiuixText(
                    text = stringResource(R.string.chat_tool_args),
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                MiuixText(
                    text = arguments,
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurface,
                )
                Spacer(Modifier.height(6.dp))
            }
            MiuixText(
                text = if (result.isBlank()) stringResource(R.string.chat_tool_pending)
                else result,
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}




/** 等待回复时的占位气泡。 */
@Composable
private fun PendingBubble(text: String? = null) {
    val colors = MiuixTheme.colors
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(MiuixTheme.radius.md))
                .background(colors.surfaceContainer)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            MiuixText(
                text = text ?: stringResource(R.string.chat_thinking),
                style = MiuixTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/**
 * 单条消息气泡：用户靠右、AI 靠左、系统提示居中浅色。
 *
 * AI 消息完成后在气泡下方显示 复制/重说/删除；用户消息长按弹出 复制/编辑/删除，
 * 编辑时支持追加图片与文件附件，编辑后的消息带「已编辑」角标。
 */
@Composable
private fun MessageBubble(
    message: ChatMessage,
    onCopy: () -> Unit = {},
    onResend: () -> Unit = {},
    onDelete: () -> Unit = {},
    onEdit: () -> Unit = {},
) {
    val colors = MiuixTheme.colors
    val radius = RoundedCornerShape(MiuixTheme.radius.md)
    val isUser = message.role == ChatMessage.Role.USER
    val isSystem = message.role == ChatMessage.Role.SYSTEM
    var showActions by remember(message.id) { mutableStateOf(false) }
    // 气泡在窗口中的位置，用作长按菜单的锚点
    val bubbleAnchor = remember(message.id) { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = when {
            isSystem -> Alignment.CenterHorizontally
            isUser -> Alignment.End
            else -> Alignment.Start
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
                .combinedClickable(
                    onLongClick = if (isUser) ({ showActions = true }) else null,
                    onClick = {},
                )
                .onGloballyPositioned { bubbleAnchor.value = it.boundsInWindow() }
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
        if (isUser && message.edited) {
            Spacer(Modifier.height(2.dp))
            MiuixText(
                text = stringResource(R.string.chat_msg_edited),
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        // AI 完成的回答：气泡下方一排操作图标
        if (message.role == ChatMessage.Role.ASSISTANT && message.content.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                MessageActionIcon(Icons.Outlined.ContentCopy, stringResource(R.string.chat_msg_copy), onCopy)
                MessageActionIcon(Icons.Outlined.Refresh, stringResource(R.string.chat_msg_resend), onResend)
                MessageActionIcon(Icons.Outlined.DeleteOutline, stringResource(R.string.chat_msg_delete), onDelete)
            }
        }
    }

    // 用户消息长按菜单：复制 / 编辑 / 删除；锚在气泡右缘、往右错开一点
    if (showActions && isUser) {
        MiuixOverflowMenu(
            expanded = showActions,
            onDismiss = { showActions = false },
            anchor = bubbleAnchor.value?.let {
                androidx.compose.ui.geometry.Rect(
                    it.left + 24f, it.top, it.right + 24f, it.bottom,
                )
            },
            alignStart = false,
        ) {
            MiuixMenuItem(
                text = stringResource(R.string.chat_msg_copy),
                icon = Icons.Outlined.ContentCopy,
                onClick = { showActions = false; onCopy() },
            )
            MiuixMenuItem(
                text = stringResource(R.string.chat_msg_edit),
                icon = Icons.Outlined.Edit,
                onClick = { showActions = false; onEdit() },
            )
            MiuixMenuItem(
                text = stringResource(R.string.chat_msg_delete),
                icon = Icons.Outlined.DeleteOutline,
                danger = true,
                onClick = { showActions = false; onDelete() },
            )
        }
    }

}

/** 消息气泡下的小操作图标（复制 / 重说 / 删除）。 */
@Composable
private fun MessageActionIcon(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colors
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MiuixIcon(icon, label, tint = colors.onSurfaceVariant, size = 16.dp)
    }
}

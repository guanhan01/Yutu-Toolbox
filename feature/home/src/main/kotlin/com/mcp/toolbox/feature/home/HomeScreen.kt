package com.mcp.toolbox.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.produceState
import android.net.Uri
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Cable
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FileCopy
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.FindInPage
import androidx.compose.material.icons.outlined.FindReplace
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Http
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PieChart
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Swipe
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
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
import com.mcp.toolbox.core.design.component.MiuixSlider
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.component.MarkdownText
import com.mcp.toolbox.core.design.component.MiuixSwitch
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.math.roundToInt
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity

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
    /** 打开终端。由宿主导航，本模块不依赖具体路由。 */
    onOpenTerminal: () -> Unit = {},
    /**
     * 启动一次请求。实现方应在应用级作用域里执行，
     * 这样离开页面或进后台都不会中断。
     */
    onStart: (sessionId: String, history: List<ChatMessage>) -> Unit = { _, _ -> },
    /** 当前正在流式接收的文本；未进行时为 null。 */
    runningText: String? = null,
    /**
     * 本轮回复的时间线（实时更新，结束时才落盘成正式消息）。
     *
     * 思考与工具调用按发生顺序混排，因此「工具之后继续思考」会自然排在工具下面。
     */
    runningTimeline: List<RunningStep> = emptyList(),
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
    /**
     * 当前模型的真实上下文窗口；null 表示服务端未提供。
     *
     * 不给默认值：用量百分比与「剩余多少」都依赖这个分母，用猜测值会算出
     * 一个看着精确、其实错误的比例。
     */
    contextWindow: Int? = null,
    /** 计划模式开关状态。 */
    planEnabled: Boolean = false,
    onTogglePlan: (Boolean) -> Unit = {},
    /** 压缩当前会话的较早历史（由宿主调模型执行）。 */
    onCompress: (() -> Unit)? = null,
    /** 压缩进行中。 */
    compressing: Boolean = false,
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
    var showPlan by remember { mutableStateOf(false) }
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
    /**
     * 过程块的展开状态，key = 该段过程在本会话里的序号（"p0"、"p1"…）。
     *
     * 必须放会话级：放在 ProcessBlock 内部时，LazyColumn 滑出屏幕回收 item 就会
     * 连同状态一起丢掉，表现出来就是「手动展开后一滚出屏幕就自己缩回去」。
     * 用序号当 key 还顺手解决第二个坑：运行中那段和落盘后那段是同一个序号，
     * 所以回复生成完、实时块换成历史块时**展开状态不会重置**。
     */
    val processExpanded = remember(session?.id) { mutableStateMapOf<String, Boolean>() }
    // 用户往上翻时不再强制滚到底，改为露出「回到最新」
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || last.index >= listState.layoutInfo.totalItemsCount - 1
        }
    }

    LaunchedEffect(Unit) { ChatStore.load(context) }

    val messageCount = session?.messages?.size ?: 0
    LaunchedEffect(messageCount) {
        // 只在用户本来就贴着底部时跟随；翻看历史时不打断
        if (messageCount > 0 && atBottom) listState.animateScrollToItem(messageCount - 1)
    }

    // 上下文用量与压缩记录都来自 store；后者决定「哪几条历史已被摘要替换」
    val usageMap by ChatUsageStore.usage.collectAsState()
    val compressStates by CompressionStore.states.collectAsState()
    val compression = session?.id?.let { compressStates[it] }
    val used = session?.id?.let { id ->
        usageMap[id]?.promptTokens?.takeIf { it > 0 }
    } ?: ChatUsageStore.estimate(context, session, null)
    val hasRealUsage = session?.id?.let { ChatUsageStore.hasRealValue(it) } == true

    // 超过阈值自动压缩一次，同一会话只自动触发一次，避免连环压
    var autoCompressed by remember(session?.id) { mutableStateOf(false) }
    val planMap by PlanStore.plans.collectAsState()
    val plan = session?.id?.let { planMap[it] }?.takeIf { !it.isEmpty }

    // 每段压缩的「第一条仍在历史里的消息」id -> 该段记录。
    // 段首被删掉时摘要块顺延到下一条，不会整段消失。
    val summaryAnchors = remember(session?.messages, compression) {
        val messages = session?.messages.orEmpty()
        compression?.ranges.orEmpty().mapNotNull { range ->
            messages.firstOrNull { it.id in range.messageIds }?.let { it.id to range }
        }.toMap()
    }

    /**
     * 消息区真正要渲染的项。
     *
     * 不能直接按 messages 逐条渲染：一个过程块会吃掉七八条消息，逐条渲染时被跳过的
     * 那些仍然占着一个 item（内容为空，但照样参与 Arrangement.spacedBy 的间距），
     * 于是「步骤块」和「回答」之间凭空多出几十 dp 的空隙。先在数据层把过程段折叠成
     * 一项，渲染层就没有空 item 了。
     */
    val chatItems = remember(session?.messages, compression) {
        val msgs = session?.messages.orEmpty()
        val out = mutableListOf<ChatItem>()
        var i = 0
        var ordinal = 0
        while (i < msgs.size) {
            val msg = msgs[i]
            val range = compression?.rangeOf(msg.id)
            if (range != null) {
                summaryAnchors[msg.id]?.let { anchored ->
                    out += ChatItem.Summary(
                        key = "s-$" + "{anchored.id}",
                        range = anchored,
                        original = msgs.filter { it.id in anchored.messageIds },
                        position = compression?.ranges?.indexOf(anchored)?.takeIf { it >= 0 } ?: 0,
                    )
                }
                i++
                continue
            }
            if (msg.isProcessMessage()) {
                val start = i
                while (i < msgs.size && msgs[i].isProcessMessage() &&
                    compression?.rangeOf(msgs[i].id) == null
                ) {
                    i++
                }
                out += ChatItem.Process(
                    key = "p$ordinal",
                    steps = msgs.subList(start, i).map { it.toProcessStep(context) },
                )
                ordinal++
            } else {
                out += ChatItem.Single(key = msg.id, message = msg)
                i++
            }
        }
        out
    }

    /** 运行中那一段的序号 = 已有段数，于是它落盘后正好接管同一个 key。 */
    val liveProcessKey = "p" + chatItems.count { it is ChatItem.Process }

    // 压缩后不能再自动触发：阈值会被压下去，否则会立刻又压一次
    val overThreshold = contextWindow != null && contextWindow > 0 &&
        used.toFloat() / contextWindow >= ChatUsageStore.COMPRESS_THRESHOLD
    LaunchedEffect(session?.id, overThreshold, sending) {
        if (overThreshold && !autoCompressed && !sending && onCompress != null && !compressing) {
            autoCompressed = true
            onCompress()
        }
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
            val kept = ChatStore.truncateFrom(context, current.id, userMessage.id)
            val history = (kept + userMessage)
                .filter { it.role != ChatMessage.Role.SYSTEM || it.content.isNotBlank() }
            if (history.isNotEmpty()) onStart(current.id, history)
        }
    }

    /** 应用用户编辑：更新消息文本与图片，并从这条消息重新生成回复。 */
    fun applyEdit(message: ChatMessage, newText: String, newImages: List<String>) {
        val current = session ?: return
        scope.launch {
            // 先删掉这条之后的回复（保留它自己），再按 id 就地更新，最后重发
            val kept = ChatStore.truncateAfter(context, current.id, message.id)
            ChatStore.updateMessage(
                context, current.id, message.id,
                content = newText,
                imageUris = newImages,
            )
            val updated = kept.map { m ->
                if (m.id == message.id) {
                    m.copy(content = newText, imageUris = newImages, edited = true)
                } else {
                    m
                }
            }
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
            // 标题只用于无障碍描述，不再绘制：对话页的标题与消息内容重复，
            // 摆在中间既占地方又容易被长标题截断成「检查所有工…」。
            title = session?.displayTitle ?: stringResource(R.string.chat_title),
            showTitle = false,
            navigationIcon = Icons.Outlined.Menu,
            onNavigationClick = onOpenDrawer,
            actions = {
                // 终端：放在「新建对话」左侧，两个按钮各自 40dp，触控区不会互相挤占
                MiuixIconButton(
                    icon = Icons.Outlined.Terminal,
                    contentDescription = stringResource(R.string.chat_terminal),
                    onClick = onOpenTerminal,
                    buttonSize = 40.dp,
                    iconSize = 20.dp,
                )
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

        // 计划条：只占一行；完整计划在弹层里看
        if (planEnabled && plan != null) {
            Row(modifier = Modifier.padding(horizontal = spacing.pageHorizontal, vertical = 2.dp)) {
                PlanBar(plan = plan, onClick = { showPlan = true })
            }
        }

        // 输入栏要「浮」在内容之上，而不是与内容平级。
        //
        // 原来是 Column 里两个兄弟：列表底边 = 输入栏顶边，形成一条硬裁切线，
        // 文字滚到那里直接消失（看起来像输入栏外面套了个矩形）。
        // 现在两者放进同一个 Box：列表铺满整个内容区、文字从输入栏下面滑过，
        // 输入栏贴在 Box 底部。与此同时给列表留出「输入栏高度」的底部内边距，
        // 保证最后一条消息滚到底时不会被输入栏永久遮住。
        var inputBarHeightPx by remember { mutableIntStateOf(0) }

        Box(Modifier.weight(1f)) {
            // 「回到最新」浮标：用户往上翻时出现，点一下回到底部
            if (sending && !atBottom) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp)
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceContainerHigh)
                        .clickable {
                            scope.launch {
                                listState.animateScrollToItem(session!!.messages.size + 8)
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    MiuixIcon(
                        Icons.Outlined.ArrowDownward,
                        stringResource(R.string.chat_jump_latest),
                        tint = colors.onSurfaceVariant,
                        size = 20.dp,
                    )
                }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = spacing.pageHorizontal,
                    end = spacing.pageHorizontal,
                    top = spacing.sm,
                    // 底部预留输入栏高度：列表铺满内容区后，末条消息仍能滚到输入栏上方
                    bottom = spacing.md + with(LocalDensity.current) { inputBarHeightPx.toDp() },
                ),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(items = chatItems, key = { it.key }) { item ->
                    when (item) {
                        is ChatItem.Summary -> CompressionBlock(
                            range = item.range,
                            original = item.original,
                            step = item.position + 1,
                            total = compression?.ranges?.size ?: 1,
                            onRestore = {
                                scope.launch {
                                    CompressionStore.remove(context, session!!.id, item.range.id)
                                }
                            },
                        )

                        is ChatItem.Process -> ProcessBlock(
                            steps = item.steps,
                            expandedKey = item.key,
                            expandedMap = processExpanded,
                        )

                        is ChatItem.Single -> MessageBubble(
                            message = item.message,
                            onCopy = {
                                clipboard.setText(
                                    androidx.compose.ui.text.AnnotatedString(item.message.content),
                                )
                            },
                            onResend = {
                                if (!sending) scope.launch { regenerateFrom(item.message) }
                            },
                            onDelete = {
                                if (!sending) scope.launch {
                                    ChatStore.deleteMessage(context, session!!.id, item.message.id)
                                }
                            },
                            onEdit = { if (!sending) beginEdit(item.message) },
                        )
                    }
                }
                if (sending) {
                    // 思考与工具调用按发生顺序收进同一个过程框：谁先发生谁在前。
                    // 中间旁白（模型带工具那轮的正文）仍按原位置单独铺开，
                    // 于是「工具之后继续思考」自然落在工具下面。
                    val processSteps = mutableListOf<ProcessStep>()
                    fun flushProcess() {
                        if (processSteps.isNotEmpty()) {
                            val snapshot = processSteps.toList()
                            item(key = liveProcessKey) {
                                ProcessBlock(
                                    steps = snapshot,
                                    expandedKey = liveProcessKey,
                                    expandedMap = processExpanded,
                                    autoOpen = true,
                                )
                            }
                            processSteps.clear()
                        }
                    }
                    runningTimeline.forEach { step ->
                        when (step) {
                            is RunningStep.Thinking -> processSteps += ProcessStep.Thinking(
                                text = step.text,
                                live = step.live,
                                elapsedMs = step.elapsedMs,
                            )
                            is RunningStep.Tool -> processSteps += ProcessStep.Tool(
                                name = step.name,
                                title = toolStepTitle(context, step.name, step.arguments),
                                arguments = step.arguments,
                                result = step.result,
                                running = step.result.isEmpty(),
                                elapsedMs = step.elapsedMs,
                            )
                            // 中间旁白也进同一个过程块：它和同轮的思考、工具是一体的
                            is RunningStep.Narration -> processSteps += ProcessStep.Narration(
                                text = step.text,
                            )
                        }
                    }
                    flushProcess()
                    if (!runningText.isNullOrEmpty()) {
                        item(key = "pending-text") { StreamingAnswer(runningText!!) }
                    }
                    if (runningTimeline.isEmpty() && runningText.isNullOrEmpty()) {
                        item(key = "pending") { PendingBubble() }
                    }
                }
            }
            InputBar(
            modifier = Modifier.align(Alignment.BottomCenter),
            onHeightMeasured = { inputBarHeightPx = it },
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
            // 上下文细线与附件并入底板：它们和输入区属于同一条「输入栏」，
            // 分开铺在底板外面会各自成块，看起来像好几个独立矩形。
            usage = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End,
                ) {
                    UsageIndicator(
                        used = used,
                        window = contextWindow,
                        estimated = !hasRealUsage,
                        compressing = compressing,
                        onCompress = onCompress,
                    )
                }
            },
            attachmentRow = {
                if (attachments.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                    ) {
                        attachments.forEachIndexed { index, item ->
                            if (item is ChatAttachment.Image) {
                                ImageAttachmentChip(
                                    uri = item.uri,
                                    label = item.label,
                                    onRemove = { attachments.removeAt(index) },
                                )
                            } else {
                                AttachmentChip(
                                    label = item.label,
                                    onRemove = { attachments.removeAt(index) },
                                )
                            }
                        }
                    }
                }
            },
        )
    }

    // 复用项目统一的锚定式溢出菜单，锚点取自输入栏加号按钮
    MiuixOverflowMenu(
        expanded = showOverflow,
        onDismiss = { showOverflow = false },
        // 加号在输入栏左侧、屏幕底部：贴底 + 左对齐
        alignStart = true,
        stickToBottom = true,
        // 非焦点模式：不抢输入框焦点，输入法保持弹出
        focusable = false,
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
        focusable = false,
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

    // 完整计划的底部弹层
    MiuixBottomSheet(
        visible = showPlan && plan != null,
        onDismiss = { showPlan = false },
    ) {
        plan?.let { current ->
            PlanSheetContent(
                plan = current,
                onToggle = { taskId, status ->
                    scope.launch {
                        PlanStore.setTaskStatus(context, session!!.id, taskId, status)
                    }
                },
            )
        }
    }

    // 思考档位：档位有强弱顺序，滑杆比列表更贴合「程度」语义。
    // 拖动即时生效（顶部标签实时显示当前档位），点外部收起。
    MiuixBottomSheet(
        visible = picker == PickerKind.REASONING,
        onDismiss = { picker = null },
    ) {
        ReasoningSheet(
            labels = availableReasoning,
            current = currentReasoning,
            onSelect = onSelectReasoning,
            planEnabled = planEnabled,
            onTogglePlan = onTogglePlan,
        )
    }
}
}

/**
 * 思考档位滑杆。
 *
 * 档位离散且有序（Off < Default < … < Max），所以滑杆取值直接就是档位下标：
 * 0..lastIndex 取整到档，两端正好落在首尾档，不会停在半个档位上。
 */
@Composable
private fun ReasoningSheet(
    labels: List<String>,
    current: String,
    onSelect: (String) -> Unit,
    planEnabled: Boolean = false,
    onTogglePlan: (Boolean) -> Unit = {},
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    if (labels.isEmpty()) return

    val lastIndex = labels.lastIndex
    // 当前档位下标；查不到（档位表变动过）就退回 Default，再退回第一档
    val currentIndex = labels.indexOf(current)
        .takeIf { it >= 0 }
        ?: labels.indexOf("Default").coerceAtLeast(0)
    // 拖动期间用本地下标驱动滑杆：拇指跟手不必等配置回写完成
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    val shownIndex = dragIndex ?: currentIndex

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.pageHorizontal, vertical = spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixText(
                text = stringResource(R.string.chat_pick_reasoning),
                style = MiuixTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            MiuixText(
                text = labels[shownIndex],
                style = MiuixTheme.typography.labelLarge,
                color = colors.primary,
            )
        }
        MiuixSlider(
            value = shownIndex.toFloat(),
            onValueChange = { raw ->
                val next = raw.roundToInt().coerceIn(0, lastIndex)
                // 只在跨档时写配置：一次拖动最多写「档位数」次，不按帧刷
                if (next != (dragIndex ?: currentIndex)) {
                    dragIndex = next
                    onSelect(labels[next])
                }
            },
            valueRange = 0f..lastIndex.toFloat(),
            steps = (lastIndex - 1).coerceAtLeast(0),
            valueLabel = { labels[it.roundToInt().coerceIn(0, lastIndex)] },
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            MiuixText(
                text = labels.first(),
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            MiuixText(
                text = labels.last(),
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(spacing.md))

        // 计划模式开关：紧跟在思考能力滑杆下方
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                MiuixText(
                    text = stringResource(R.string.chat_plan_title),
                    style = MiuixTheme.typography.bodyMedium,
                )
                MiuixText(
                    text = stringResource(R.string.chat_plan_desc),
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(spacing.sm))
            MiuixSwitch(
                checked = planEnabled,
                onCheckedChange = onTogglePlan,
            )
        }
    }

    // 配置跟上后丢掉本地拖动值，滑杆回到受控状态
    LaunchedEffect(current) { dragIndex = null }
}

/**
 * 图片附件：显示真实缩略图，点右上角 × 移除。
 *
 * 解码放在 IO 线程并按目标边长降采样：相册里动辄四五千像素的原图直接读进来，
 * 一张就够几十 MB 的堆，几张就能把输入栏拖卡。
 */
@Composable
private fun ImageAttachmentChip(
    uri: String,
    label: String,
    onRemove: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val context = LocalContext.current
    // 只依赖 uri：换图时重新解码，不会沿用上一张的位图
    val thumb by produceState<android.graphics.Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val resolver = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= THUMB_EDGE_PX &&
                    bounds.outHeight / (sample * 2) >= THUMB_EDGE_PX
                ) {
                    sample *= 2
                }
                resolver.openInputStream(Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(
                        it,
                        null,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }
            }.getOrNull()
        }
    }

    Box(
        modifier = Modifier
            .size(THUMB_SIZE)
            .clip(RoundedCornerShape(MiuixTheme.radius.md))
            .background(colors.surfaceContainerLow),
    ) {
        val bitmap = thumb
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // 解码中 / 解码失败：给个占位，避免附件区高度跳一下
            MiuixIcon(
                Icons.Outlined.Image,
                label,
                tint = colors.onSurfaceVariant,
                size = 20.dp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // 移除按钮压在右上角：缩略图上没有放文字标签的位置，
        // 加一层半透明底，保证在浅色图片上也看得见
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            MiuixIcon(
                Icons.Outlined.Close,
                stringResource(R.string.chat_attach),
                tint = Color.White,
                size = 12.dp,
            )
        }
    }
}

/** 缩略图边长，以及解码目标边长（按 3x 屏估算：够清晰，又不至于占内存）。 */
private val THUMB_SIZE = 56.dp
private const val THUMB_EDGE_PX = 168

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
    modifier: Modifier = Modifier,
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
    usage: @Composable () -> Unit = {},
    attachmentRow: @Composable () -> Unit = {},
    /** 把输入栏实测高度报出去，供外层给内容列表留出等高的底部内边距。 */
    onHeightMeasured: (Int) -> Unit = {},
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val active = value.isNotBlank()
    // 悬浮式输入栏：不复用「整条贴边底板」。
    // 贴边全宽的容器会和页面留白连成一片，看起来就是一块把输入框包住的矩形；
    // 现在改成左右留出页边距的圆角悬浮面板 + 投影，靠高度而非贴边来分层。
    val panelShape = RoundedCornerShape(MiuixTheme.radius.composer)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightMeasured(it.height) }
            // 刻意不铺任何不透明底色：铺了就会得到一条全宽的不透明带，
            // 它的上边缘正是「内容被盖住」的那条直线 —— 看起来就是输入栏坐在一个矩形上。
            // 悬浮的正确画法是只有卡片本身不透明，内容从卡片下面经过、被卡片（含圆角）遮住。
            .navigationBarsPadding()
            .imePadding()
            .padding(
                start = spacing.pageHorizontal + 4.dp,
                end = spacing.pageHorizontal + 4.dp,
                top = spacing.sm,
                bottom = spacing.md,
            )
            .shadow(
                elevation = MiuixTheme.dimens.elevation.level2,
                shape = panelShape,
                clip = false,
            )
            .clip(panelShape)
            .background(colors.surfaceContainerLow)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
    ) {
        // 上下文细线：并入底板顶部，不再单独占一行浮在框外
        usage()
        attachmentRow()
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

/**
 * 消息区的一个渲染项。
 *
 * 过程消息（深度思考 / 工具调用 / 中间旁白）会被折叠成一项 [Process]，
 * 其余消息各自一项 [Single]，被压缩的历史段落占一项 [Summary]。
 */
private sealed interface ChatItem {
    val key: String

    data class Summary(
        override val key: String,
        val range: CompressedRange,
        val original: List<ChatMessage>,
        val position: Int,
    ) : ChatItem

    data class Process(
        override val key: String,
        val steps: List<ProcessStep>,
    ) : ChatItem

    data class Single(
        override val key: String,
        val message: ChatMessage,
    ) : ChatItem
}

/**
 * 过程内容的一步。
 *
 * [label] 是「终端 · 单次执行」这类标题：同一次回复里往往连续调用七八次工具，
 * 只显示工具名（`shell_exec`）看不出在干什么，所以标题要带上工具的中文名与动作。
 */
private sealed interface ProcessStep {
    val elapsedMs: Long

    data class Thinking(
        val text: String,
        val live: Boolean,
        override val elapsedMs: Long = 0,
    ) : ProcessStep

    data class Tool(
        val name: String,
        val title: String,
        val arguments: String,
        val result: String,
        val running: Boolean,
        override val elapsedMs: Long = 0,
    ) : ProcessStep

    data class Narration(val text: String) : ProcessStep {
        override val elapsedMs: Long get() = 0
    }
}

/** 把毫秒格式化成人读的时长。 */
private fun formatElapsed(ms: Long): String = when {
    ms < 1000 -> "$ms 毫秒"
    ms < 60_000 -> "%.1f 秒".format(java.util.Locale.US, ms / 1000.0).removeSuffix(".0")
    else -> "${ms / 60_000} 分 ${(ms % 60_000) / 1000} 秒"
}

/** 单个步骤的图标：工具按名字分类给图形，思考用灯泡。 */
private fun stepIcon(name: String): ImageVector {
    val n = name.lowercase()
    return when {
        "shell" in n || "linux_exec" in n || "terminal" in n -> Icons.Outlined.Terminal
        "file_write" in n || "file_copy" in n || "file_move" in n ||
            "file_rename" in n || "file_delete" in n || "file_mkdir" in n ||
            "file_zip" in n || "file_unzip" in n -> Icons.Outlined.EditNote
        "file_read" in n || "file_list" in n || "file_search" in n ||
            "file_info" in n || "file_tree" in n -> Icons.Outlined.Description
        "db_" in n || "sql" in n -> Icons.Outlined.Storage
        "http" in n || "network" in n -> Icons.Outlined.Language
        "apk" in n -> Icons.Outlined.Android
        "dex" in n || "smali" in n || "decompile" in n -> Icons.Outlined.Code
        "ui_" in n -> Icons.Outlined.Smartphone
        "settings" in n -> Icons.Outlined.Settings
        "clipboard" in n -> Icons.Outlined.ContentCopy
        "plan" in n -> Icons.Outlined.Checklist
        "memory" in n -> Icons.Outlined.Psychology
        "app_" in n -> Icons.Outlined.Apps
        "device" in n -> Icons.Outlined.Memory
        "artifact" in n -> Icons.Outlined.Inventory2
        else -> Icons.Outlined.Build
    }
}

/**
 * 一个「步骤」的外观：左侧一条竖向轨道 + 图标 + 标题行。
 *
 * 整块过程用同一套轨道串起来（圆点/图标对齐在同一条竖线上），
 * 而不是每个步骤各画一张卡片——后者在七八步时看着像一堆散落的方块。
 */
@Composable
private fun StepRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    trailing: String? = null,
    running: Boolean = false,
    expandable: Boolean = true,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = MiuixTheme.colors
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "step-arrow",
    )

    // animateContentSize 必须在**根**节点上：放在内部那个 Column 上时，它自己
    // 是随出现一起创建的，没有「从 0 到有」的过程，看不到动画。
    Column(modifier = Modifier.animateContentSize(tween(220))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MiuixTheme.radius.sm))
                .clickable(enabled = expandable, onClick = onToggle)
                .padding(vertical = 6.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixIcon(icon, null, tint = iconTint, size = 17.dp)
            Spacer(Modifier.width(8.dp))
            MiuixText(
                text = title,
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (running) {
                Spacer(Modifier.width(6.dp))
                StepSpinner()
            } else {
                // 成功勾：与标题同排在右侧，一眼能扫完「哪些跑完了」
                MiuixIcon(Icons.Outlined.Check, null, tint = colors.success, size = 14.dp)
            }
            if (trailing != null) {
                Spacer(Modifier.width(6.dp))
                MiuixText(
                    text = trailing,
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
            if (expandable) {
                Spacer(Modifier.width(4.dp))
                MiuixIcon(
                    Icons.Outlined.ExpandMore,
                    null,
                    tint = colors.onSurfaceVariant,
                    size = 15.dp,
                    modifier = Modifier.rotate(rotation),
                )
            }
        }

        // 内容整体缩进，与标题文字左对齐，形成「标题 → 明细」的层次
        if (expanded) {
            Column(
                modifier = Modifier
                    .padding(start = 25.dp)
                    .animateContentSize(tween(220)),
            ) {
                content()
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

/** 运行中的小转圈。 */
@Composable
private fun StepSpinner() {
    val colors = MiuixTheme.colors
    val transition = rememberInfiniteTransition(label = "step-spinner")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
        label = "step-spinner-angle",
    )
    Box(
        modifier = Modifier
            .size(13.dp)
            .graphicsLayer { rotationZ = angle },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(13.dp),
            strokeWidth = 1.5.dp,
            color = colors.primary,
        )
    }
}

/**
 * 深度思考与工具调用的统一过程区。
 *
 * 每一步都是独立的行（思考 / 工具各一行，各带自己的图标与用时），
 * 整体按发生顺序串在一起。这样长回复里能一眼扫出「做了哪几步、各花多久」，
 * 而不是把十几段推理和调用揉成一整块文字。
 */
@Composable
private fun ProcessBlock(
    steps: List<ProcessStep>,
    expandedKey: String,
    expandedMap: MutableMap<String, Boolean>,
    autoOpen: Boolean = false,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val lastStep = steps.lastOrNull()
    val working = when (lastStep) {
        is ProcessStep.Thinking -> lastStep.live
        is ProcessStep.Tool -> lastStep.running
        else -> false
    }

    // 默认展开：思考与工具是用户要看的过程，默认收起等于每次都被挡在标题外。
    // 手动折叠过就听用户的；状态存会话级 map，LazyColumn 回收不会把它一起收走。
    val expanded = expandedMap[expandedKey] ?: true
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "process-arrow",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiuixTheme.radius.md))
            .background(colors.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(MiuixTheme.radius.sm))
                .clickable { expandedMap[expandedKey] = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixIcon(
                Icons.Outlined.Psychology,
                null,
                tint = colors.primary,
                size = 16.dp,
            )
            Spacer(Modifier.width(6.dp))
            MiuixText(
                text = stringResource(
                    if (working) R.string.chat_steps_running
                    else R.string.chat_steps_done,
                    steps.count { it !is ProcessStep.Narration },
                ),
                style = MiuixTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier.weight(1f),
            )
            MiuixIcon(
                Icons.Outlined.ExpandMore,
                null,
                tint = colors.onSurfaceVariant,
                size = 16.dp,
                modifier = Modifier.rotate(rotation),
            )
        }

        Column(modifier = Modifier.animateContentSize(tween(220))) {
            if (expanded) {
                Spacer(Modifier.height(spacing.xs))
                steps.forEach { step ->
                    when (step) {
                        is ProcessStep.Thinking -> ThinkingStep(step)
                        is ProcessStep.Tool -> ToolStep(step)
                        is ProcessStep.Narration -> MarkdownText(
                            text = step.text,
                            compact = true,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** 一步深度思考。 */
@Composable
private fun ThinkingStep(step: ProcessStep.Thinking) {
    val colors = MiuixTheme.colors
    // 思考默认展开：这是用户明确要看的推理过程，默认收起等于每次都要多点一下。
    // 手动折叠过就听用户的。
    var manual by remember(step.text) { mutableStateOf<Boolean?>(null) }
    val expanded = manual ?: true

    StepRow(
        icon = Icons.Outlined.Lightbulb,
        iconTint = colors.primary,
        title = stringResource(
            if (step.live) R.string.chat_step_thinking
            else R.string.chat_step_thought,
        ),
        trailing = if (step.elapsedMs > 0) formatElapsed(step.elapsedMs) else null,
        running = step.live,
        expandable = true,
        expanded = expanded,
        onToggle = { manual = !expanded },
    ) {
        // 思考内容全展示：不再限高、不再内部滚动——内部滚动条会把推理切成
        // 一截一截，用户要的是从头看到尾；字号也用正文级，不再用 compact 小字。
        MarkdownText(text = step.text, color = colors.onSurfaceVariant)
    }
}

/** 一次工具调用。 */
@Composable
private fun ToolStep(step: ProcessStep.Tool) {
    val colors = MiuixTheme.colors
    var expanded by remember { mutableStateOf(false) }

    StepRow(
        icon = stepIcon(step.name),
        iconTint = colors.onSurfaceVariant,
        title = step.title.ifBlank { step.name },
        trailing = if (step.elapsedMs > 0) formatElapsed(step.elapsedMs) else null,
        running = step.running,
        expanded = expanded,
        onToggle = { expanded = !expanded },
    ) {
        Column {
            // 非终端类工具只展示结果，不占位显示空参数
            if (step.arguments.isNotBlank() && step.arguments != "{}") {
                DetailBlock(
                    label = stringResource(R.string.chat_tool_args),
                    body = step.arguments,
                    mono = true,
                )
                Spacer(Modifier.height(6.dp))
            }
            DetailBlock(
                label = stringResource(R.string.chat_tool_result),
                body = step.result.ifBlank { stringResource(R.string.chat_tool_pending) },
                mono = false,
            )
        }
    }
}

/** 工具参数 / 结果的展示块：浅底 + 可横向滚动，长 JSON 不会被折断。 */
@Composable
private fun DetailBlock(label: String, body: String, mono: Boolean) {
    val colors = MiuixTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiuixTheme.radius.sm))
            .background(colors.surfaceContainer)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        MiuixText(
            text = label,
            style = MiuixTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        // 用换行而不是横向滚动：窄屏上斜着看一长条 JSON 很别扭，
        // 路径之类的长串让它自然折行更好读。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            MiuixText(
                text = body,
                style = if (mono) {
                    MiuixTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                } else {
                    MiuixTheme.typography.bodySmall
                },
                color = colors.onSurface,
            )
        }
    }
}

/** 思考正文折叠前显示的行数。 */

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

/** 正在流式生成的最终答复：与已完成答复同款的无框排版，不显示操作按钮。 */
@Composable
private fun StreamingAnswer(text: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        MarkdownText(text = text, color = MiuixTheme.colors.onSurface)
    }
}

/**
 * 单条消息。
 *
 * 用户消息保持气泡（便于区分输入与输出）；AI 的最终答复**不加背景框**，
 * 直接按 Markdown 铺在页面上，与主流 AI 应用的阅读形态一致。
 * 长按用户气泡仍是 复制 / 编辑 / 删除；AI 答复下方是 复制 / 重说 / 删除。
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
    val bubbleShape = RoundedCornerShape(MiuixTheme.radius.field)
    val isUser = message.role == ChatMessage.Role.USER
    val isSystem = message.role == ChatMessage.Role.SYSTEM
    val isAssistant = message.role == ChatMessage.Role.ASSISTANT
    var showActions by remember(message.id) { mutableStateOf(false) }
    // 气泡在窗口中的位置，用作长按菜单的锚点
    val bubbleAnchor = remember(message.id) { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }

    if (isAssistant) {
        // 无框答复：不加背景、不加内边距，仅在内容下方留一排操作图标。
        // 中间旁白只铺文字——它是过程的碎片，不该让用户以为可以「重说」它。
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.Start,
        ) {
            MarkdownText(
                text = message.content,
                color = if (message.intermediate) colors.onSurfaceVariant else colors.onSurface,
            )
            if (message.content.isNotBlank() && !message.intermediate) {
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    MessageActionIcon(
                        Icons.Outlined.ContentCopy,
                        stringResource(R.string.chat_msg_copy),
                        onCopy,
                    )
                    MessageActionIcon(
                        Icons.Outlined.Refresh,
                        stringResource(R.string.chat_msg_resend),
                        onResend,
                    )
                    MessageActionIcon(
                        Icons.Outlined.DeleteOutline,
                        stringResource(R.string.chat_msg_delete),
                        onDelete,
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isSystem) Alignment.CenterHorizontally else Alignment.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                // 用户气泡沿用原来的 20dp 会显得「太圆」，收到 field（12dp）
                .clip(bubbleShape)
                // 纯白模式下气泡走灰白（主色只留给滑杆/按钮），其余模式保持主色气泡
                .background(
                    when {
                        isSystem -> colors.surfaceContainerHigh
                        colors.pureWhite -> colors.surfaceContainerHigh
                        else -> colors.primary
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
                    isSystem -> colors.onSurfaceVariant
                    colors.pureWhite -> colors.onSurface
                    else -> colors.onPrimary
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

/**
 * 是否属于「过程」而非最终答复。
 *
 * 除了深度思考与工具调用，带工具那一轮的中间旁白也是过程的一部分——
 * 它在界面上应该和同轮的思考、工具排在一起，而不是飘在过程块外面。
 */
private fun ChatMessage.isProcessMessage(): Boolean = when (role) {
    ChatMessage.Role.REASONING, ChatMessage.Role.TOOL -> true
    // 中间旁白由落盘时的 intermediate 标记（或迁移规则）识别
    ChatMessage.Role.ASSISTANT -> intermediate
    else -> false
}

/** 把一条历史消息还原成过程框里的一步。 */
private fun ChatMessage.toProcessStep(context: android.content.Context): ProcessStep = when (role) {
    ChatMessage.Role.REASONING -> ProcessStep.Thinking(
        text = content,
        live = false,
        elapsedMs = elapsedMs,
    )
    // 中间旁白是模型带工具那轮的正文，在步骤块里就是一段普通文字。
    // 早期实现把它落到 else 分支 → 渲染成标题空白的「工具」行（只剩扳手图标）。
    ChatMessage.Role.ASSISTANT -> ProcessStep.Narration(text = content)
    else -> ProcessStep.Tool(
        name = toolName,
        title = toolStepTitle(context, toolName, toolArguments),
        arguments = toolArguments,
        result = content,
        running = false,
        elapsedMs = elapsedMs,
    )
}

/**
 * 工具的展示标题。
 *
 * 内置工具都注册了中文 `title`（如「终端 · 单次执行」），直接取来用；
 * 取不到时退回工具名，至少不会显示成空白。
 */
private fun toolStepTitle(context: android.content.Context, name: String, arguments: String): String {
    // name 是模型侧的函数名（点号已换成下划线），必须按同一套映射反查
    val def = runCatching {
        val dots = if ('_' in name && '.' !in name) {
            com.mcp.toolbox.feature.mcp.BuiltInToolSet.all(context)
                .firstOrNull { it.name.replace('.', '_') == name }
                ?.name
        } else {
            name
        }
        com.mcp.toolbox.feature.mcp.BuiltInToolSet.all(context)
            .firstOrNull { it.name == dots }
    }.getOrNull()
    val base = def?.title?.takeIf { it.isNotBlank() } ?: name
    val action = commandHint(arguments)
    return if (action.isBlank()) base else "$base · $action"
}

/** 从参数里挑一个能说明「在做什么」的短提示。 */
private fun commandHint(arguments: String): String {
    val json = runCatching { org.json.JSONObject(arguments) }.getOrNull() ?: return ""
    val raw = json.optString("command").takeIf { it.isNotBlank() }
        ?: json.optString("path").takeIf { it.isNotBlank() }
        ?: json.optString("url").takeIf { it.isNotBlank() }
        ?: json.optString("query").takeIf { it.isNotBlank() }
        ?: return ""
    return raw.replace("\n", " ").take(48)
}

/**
 * 被压缩的一段历史。
 *
 * 摘要块只占一条消息的位置，展开后能看到**原文全文**（原文一直在
 * `session.messages` 里，压缩只改了发给模型的内容）。
 */
@Composable
private fun CompressionBlock(
    range: CompressedRange,
    original: List<ChatMessage>,
    step: Int,
    total: Int,
    onRestore: () -> Unit,
) {
    val colors = MiuixTheme.colors
    var expanded by remember(range.id) { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiuixTheme.radius.md))
            .background(colors.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixIcon(
                Icons.Outlined.Compress,
                null,
                tint = colors.onSurfaceVariant,
                size = 15.dp,
            )
            Spacer(Modifier.width(6.dp))
            MiuixText(
                text = stringResource(R.string.chat_summary_title, range.messageCount),
                style = MiuixTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            MiuixText(
                text = "$step/$total",
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        MarkdownText(text = range.summary, compact = true, color = colors.onSurface)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixText(
                text = stringResource(R.string.chat_summary_expand),
                style = MiuixTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier.clickable { expanded = !expanded },
            )
            Spacer(Modifier.width(12.dp))
            MiuixText(
                text = stringResource(R.string.chat_summary_restore),
                style = MiuixTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier.clickable(onClick = onRestore),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                original.forEach { m ->
                    val label = when (m.role) {
                        ChatMessage.Role.USER -> "用户"
                        ChatMessage.Role.ASSISTANT -> "助手"
                        ChatMessage.Role.REASONING -> "深度思考"
                        ChatMessage.Role.TOOL -> "工具调用 ${m.toolName}"
                        ChatMessage.Role.SYSTEM -> "系统"
                    }
                    MarkdownText(
                        text = "【$label】\n${m.content}",
                        compact = true,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * 计划条：常驻在消息区上方的**单行**入口。
 *
 * 完整计划放进底部弹层，而不是在页面顶部摊开——长计划一摊就是十几行，
 * 直接把消息区顶掉大半屏。这里只留「模式 / 进度 / 目标首行」，点开才看全部。
 */
@Composable
private fun PlanBar(plan: PlanState, onClick: () -> Unit) {
    val colors = MiuixTheme.colors

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(MiuixTheme.radius.sm))
            .background(colors.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiuixIcon(Icons.Outlined.Checklist, null, tint = colors.primary, size = 15.dp)
        Spacer(Modifier.width(6.dp))
        MiuixText(
            text = stringResource(R.string.chat_plan_title),
            style = MiuixTheme.typography.labelMedium,
            color = colors.primary,
        )
        if (plan.tasks.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            MiuixText(
                text = stringResource(R.string.chat_plan_progress, plan.doneCount, plan.tasks.size),
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        // 目标只露一行，让收起状态也能看出这是哪份计划
        plan.goal.takeIf { it.isNotBlank() }?.let { goal ->
            MiuixText(
                text = goal,
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
        }
        MiuixIcon(
            Icons.Outlined.ExpandMore,
            null,
            tint = colors.onSurfaceVariant,
            size = 15.dp,
        )
    }
}

/**
 * 完整计划的底部弹层。
 *
 * 子计划可点勾切换状态——这一步不经过模型，直接写 [PlanStore]，
 * 所以手动调整不必等一轮请求。
 */
@Composable
private fun PlanSheetContent(
    plan: PlanState,
    onToggle: (String, String) -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.pageHorizontal, vertical = spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixIcon(Icons.Outlined.Checklist, null, tint = colors.primary, size = 18.dp)
            Spacer(Modifier.width(6.dp))
            MiuixText(
                text = stringResource(R.string.chat_plan_title),
                style = MiuixTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (plan.tasks.isNotEmpty()) {
                MiuixText(
                    text = stringResource(R.string.chat_plan_progress, plan.doneCount, plan.tasks.size),
                    style = MiuixTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }

        plan.goal.takeIf { it.isNotBlank() }?.let { goal ->
            Spacer(Modifier.height(spacing.sm))
            MarkdownText(text = goal, compact = true, color = colors.onSurface)
        }

        Spacer(Modifier.height(spacing.md))

        if (plan.tasks.isEmpty()) {
            MiuixText(
                text = stringResource(R.string.chat_plan_empty),
                style = MiuixTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                plan.tasks.forEach { task ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(MiuixTheme.radius.sm))
                            .clickable {
                                val next = if (task.status == PlanTask.STATUS_DONE) {
                                    PlanTask.STATUS_PENDING
                                } else {
                                    PlanTask.STATUS_DONE
                                }
                                onToggle(task.id, next)
                            }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MiuixIcon(
                            icon = when (task.status) {
                                PlanTask.STATUS_DONE -> Icons.Outlined.CheckCircle
                                PlanTask.STATUS_DOING -> Icons.Outlined.PlayCircleOutline
                                PlanTask.STATUS_SKIPPED -> Icons.Outlined.RemoveCircleOutline
                                else -> Icons.Outlined.RadioButtonUnchecked
                            },
                            contentDescription = task.status,
                            tint = when (task.status) {
                                PlanTask.STATUS_DONE -> colors.success
                                PlanTask.STATUS_DOING -> colors.primary
                                else -> colors.onSurfaceVariant
                            },
                            size = 18.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        MiuixText(
                            text = task.title,
                            style = MiuixTheme.typography.bodyMedium,
                            color = if (task.status == PlanTask.STATUS_DONE) {
                                colors.onSurfaceVariant
                            } else {
                                colors.onSurface
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(spacing.sm))
    }
}

/**
 * 输入框右上角的用量指示。
 *
 * 细进度条 + 数字，点开是溢出菜单：已用 / 剩余 / 窗口大小 / 压缩入口。
 * 进度条刻意做得很短（56dp），它只是一眼可见的「水位」，数字看菜单。
 */
@Composable
private fun UsageIndicator(
    used: Int,
    window: Int?,
    estimated: Boolean,
    compressing: Boolean,
    onCompress: (() -> Unit)?,
) {
    val colors = MiuixTheme.colors
    var showMenu by remember { mutableStateOf(false) }
    val anchor = remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    // 窗口未知时 ratio 为 null：不画进度条，只显示已用量
    val ratio = window?.takeIf { it > 0 }?.let { (used.toFloat() / it).coerceIn(0f, 1f) }
    val danger = ratio != null && ratio >= ChatUsageStore.COMPRESS_THRESHOLD
    val barColor = when {
        ratio == null -> colors.primary
        ratio >= 0.9f -> colors.error
        danger -> colors.warning
        else -> colors.primary
    }

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(MiuixTheme.radius.sm))
            .clickable { showMenu = true }
            .onGloballyPositioned { anchor.value = it.boundsInWindow() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 没有真实窗口就不画进度条：一条永远填不满（或永远填满）的条
        // 比不画更容易误导
        if (ratio != null) {
            Box(
                modifier = Modifier
                    .width(56.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.surfaceContainerHigh),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(ratio)
                        .background(barColor, RoundedCornerShape(2.dp)),
                )
            }
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.width(4.dp))
        MiuixText(
            text = formatTokens(used),
            style = MiuixTheme.typography.labelSmall,
            color = if (danger) barColor else colors.onSurfaceVariant,
        )
    }

    MiuixOverflowMenu(
        expanded = showMenu,
        onDismiss = { showMenu = false },
        anchor = anchor.value,
        alignStart = false,
        stickToBottom = false,
    ) {
        // 菜单只展示数据与一个动作，不用可选条目
        MiuixMenuItem(
            text = stringResource(R.string.chat_usage_used, formatTokens(used)),
            enabled = false,
            onClick = {},
        )
        if (window != null) {
            MiuixMenuItem(
                text = stringResource(
                    R.string.chat_usage_left,
                    formatTokens((window - used).coerceAtLeast(0)),
                ),
                enabled = false,
                onClick = {},
            )
            MiuixMenuItem(
                text = stringResource(R.string.chat_usage_window, formatTokens(window)),
                enabled = false,
                onClick = {},
            )
        } else {
            MiuixMenuItem(
                text = stringResource(R.string.chat_usage_window_unknown),
                enabled = false,
                onClick = {},
            )
        }
        if (estimated) {
            MiuixMenuDivider()
            MiuixMenuItem(
                text = stringResource(R.string.chat_usage_estimated),
                enabled = false,
                onClick = {},
            )
        }
        if (onCompress != null) {
            MiuixMenuDivider()
            MiuixMenuItem(
                text = stringResource(
                    if (compressing) R.string.chat_usage_compressing
                    else R.string.chat_usage_compress,
                ),
                icon = Icons.Outlined.Compress,
                enabled = !compressing,
                onClick = { showMenu = false; onCompress() },
            )
        }
    }
}

package com.mcp.toolbox.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import com.mcp.toolbox.feature.home.agent.FeedbackComposer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.mcp.toolbox.feature.home.agent.AgentSegmentCodec
import com.mcp.toolbox.feature.home.agent.DemoRunner
import com.mcp.toolbox.feature.home.agent.rememberAgentChatState
import com.mcp.toolbox.feature.home.agent.ui.AgentMessageList
import com.mcp.toolbox.feature.home.agent.ui.rememberClipboardWriter
import com.mcp.toolbox.core.design.icon.AgentIcons
import com.mcp.toolbox.core.design.component.MiuixBottomSheet
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixMenuDivider
import com.mcp.toolbox.core.design.component.MiuixMenuGroupLabel
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixSlider
import com.mcp.toolbox.core.design.component.MiuixSwitch
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.shape.sqSurface
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.theme.MiuixTheme
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.produceState
import android.net.Uri
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 首页：AI 对话界面。
 *
 * 会话与消息由 [ChatStore] 本地保管；真正的模型调用通过 [onStart] 注入，
 * 由宿主（app 模块）决定用哪个服务商与密钥，本模块不依赖具体实现。
 *
 * 注意：AI 回答区（回答正文 / 思考步骤 / 工具调用卡片 / 压缩摘要 / 计划面板）正在重构，
 * 相关 UI 已全部移除，消息区暂时是空占位。输入栏、顶栏与侧边栏对话记录不受影响。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
    /** 打开终端。由宿主导航，本模块不依赖具体路由。 */
    onOpenTerminal: () -> Unit = {},
    /**
     * 用**内置浏览器**打开一个链接（AI 回答里的链接走这里）。
     *
     * 宿主决定用哪个页面承载；本模块只把地址交出去，
     * 所以不依赖 `:feature:web`，也不假设一定有浏览器。
     */
    onOpenWeb: (String) -> Unit = {},
    /**
     * 启动一次请求。实现方应在应用级作用域里执行，
     * 这样离开页面或进后台都不会中断。
     */
    onStart: (sessionId: String, assistantMessageId: String, history: List<ChatMessage>) -> Unit =
        { _, _, _ -> },
    /**
     * 单独重跑一个工具（工具卡的「重试」）。
     *
     * 必须与 [onStart] 分开：后者走 [startNewTurn]，会清空整条时间线，
     * 于是「重试一个工具」变成了「把前面的过程全删掉重跑一轮」（用户实测反馈）。
     * 返回 null 表示已启动重试；非 null 是要展示给用户的原因（例如这条消息
     * 不是当前轮、或已有请求在跑）—— 明确告知，而不是静默什么都不做。
     */
    onRetryTool: (
        messageId: String,
        round: Int,
        seq: Int,
        toolName: String,
        argsJson: String?,
    ) -> String? = { _, _, _, _, _ -> null },
    /** 是否已有请求在执行。 */
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
    /**
     * 输入栏里「模型」按钮的窗口矩形。
     *
     * 菜单定位需要它：不传 anchor 时菜单会退回用 Popup 的父布局矩形，而父布局
     * 约等于整条输入栏，右对齐就落到屏幕最右侧，与那颗按钮的实际位置对不上。
     */
    var modelAnchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var showOverflow by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    var pathInput by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<ChatAttachment>() }

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
                // 树 URI 能解析出目录路径就显示路径；解析不出（如存储根）退回 provider 的显示名
                label = ChatAttachment.folderLabel(it)
                    ?: ChatAttachment.displayName(context, it),
            )
        }
    }

    // 必须先完成加载：`load()` 是无条件 `_sessions.value = parsed`，
    // 两个独立的 LaunchedEffect 谁先跑不确定，演示若先建会话就会被它覆盖掉。
    var storeLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ChatStore.load(context)
        storeLoaded = true
    }

    // 输入栏里那个上下文用量条用的数据（旧回答 UI 的用量统计不受影响）
    val usageMap by ChatUsageStore.usage.collectAsState()
    val used = session?.id?.let { id ->
        usageMap[id]?.promptTokens?.takeIf { it > 0 }
    } ?: ChatUsageStore.estimate(context, session, null)
    val hasRealUsage = session?.id?.let { ChatUsageStore.hasRealValue(it) } == true

    val newChatTitle = stringResource(R.string.chat_title)
    val attachContext = stringResource(R.string.chat_attach_context)

    // ── 回答区状态 ──
    // 放在页面这一层而不是 LazyColumn 里：切页 / 进后台时时间线不丢，
    // 与 ChatRunner 运行在应用级作用域是同一个理由。
    val agent = rememberAgentChatState()

    /**
     * 正在编辑的消息 id。非空即进入编辑态。
     *
     * 编辑语义（与 `chat_editing_hint` 的文案一致）：
     * 原提问回到输入框 → 用户改（还可以通过 + 追加附件）→ 点发送生效。
     * 生效时把原提问**之后**的消息全部作废，再用新内容重跑一轮。
     */
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    /** 编辑态下点发送会走「应用编辑」而不是「新提问」。 */
    val editing = editingMessageId != null
    /** 进入编辑态后请求一次输入框焦点；消费掉即复位，避免反复抢焦点。 */
    var focusInput by remember { mutableStateOf(false) }
    val inputFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    // 进入编辑态后把焦点交给输入框（消费掉标记，避免每次重组都抢焦点）
    LaunchedEffect(focusInput) {
        if (focusInput) {
            inputFocus.requestFocus()
            focusInput = false
        }
    }

    // 回答区的落盘出口：把「本轮时间线 + 正文 + 终态」写回会话。
    // 用回调注入而不是让 state 直接依赖 ChatStore，状态层因此保持可单测。
    LaunchedEffect(agent) {
        agent.persist = { segments, answer, turnState ->
            val sid = agent.sessionId
            val mid = agent.messageId
            // 没绑定轮次（还没发送 / 已切会话）就没什么可写的
            if (sid != null && mid != null) {
                scope.launch {
                    ChatStore.upsertAssistant(
                        context = context,
                        sessionId = sid,
                        messageId = mid,
                        content = answer,
                        segmentsJson = AgentSegmentCodec.encode(segments),
                        turnState = turnState,
                    )
                }
            }
        }
    }

    // 注意：这里**不要**在 currentId 变化时自动 detachTurn()。
    // 发送时先落占位消息（会让 currentId 变化）、再 bindTurn，而 LaunchedEffect
    // 的执行时机与 bindTurn 谁先谁后不确定；一旦它跑在后面，就会把刚绑上的轮次
    // 解绑掉，进度再也写不回那条消息（实测：新建会话的第一条回答过程必丢）。
    // 不同会话的过程本来就是各写各的消息 id，不存在互相污染。

    // 进后台强制落盘一次：进程可能随时被杀
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) agent.onBackground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val writeClipboard = rememberClipboardWriter()
    /** 轻量提示（复制成功、分享失败等）。 */
    val toast = com.mcp.toolbox.core.design.component.rememberMiuixToastState()
    /** 反馈草稿；非空即弹出确认层。**先让用户过目再发**，不静默上报。 */
    var feedbackDraft by remember { mutableStateOf<String?>(null) }

    // debug 专用：若启动 Intent 带了演示开关就播一遍（见 DemoRunner 注释）。
    // 不占任何界面元素，release 里 DemoRunner.enabled 恒为 false。
    LaunchedEffect(storeLoaded) {
        if (storeLoaded) DemoRunner.runPending(context, agent)
    }


    /**
     * 开一轮新的 AI 回答（重新生成 / 继续生成 / 重试都用它）。
     *
     * 先落一条 `Running` 占位并绑定，这样过程与中断都有落点；
     * 再把已有的会话历史交给 `ChatRunner` 重跑。
     */
    /**
     * 重跑一轮回答。
     *
     * - [reuseMessageId] 非空：**原地重来**——把这条 AI 消息的内容与时间线清空，
     *   过程重新写进同一条（「重新生成」和「继续生成 / 重试」都是这个语义）
     * - [reuseMessageId] 为空：新建一条 AI 消息
     */
    suspend fun startNewTurn(
        sessionId: String,
        agent: com.mcp.toolbox.feature.home.agent.AgentChatState,
        context: android.content.Context,
        reuseMessageId: String? = null,
    ) {
        val session = ChatStore.sessions.value.firstOrNull { it.id == sessionId }
        val history = session?.messages.orEmpty()
        val assistantId = reuseMessageId ?: java.util.UUID.randomUUID().toString()
        // 原地重来要**清空**旧内容与旧时间线。早先这里把旧的 segmentsJson 原样带回去，
        // 于是「重新生成」被解读成「在刚刚的回答下面继续思考」。
        ChatStore.upsertAssistant(
            context = context,
            sessionId = sessionId,
            messageId = assistantId,
            content = "",
            segmentsJson = null,
            turnState = TurnState.Running,
        )
        agent.bindTurn(sessionId, assistantId)
        onStart(sessionId, assistantId, history.forModel())
    }

    /**
     * 进入编辑态：把这条提问的原文放回输入框。
     *
     * 只做「回到输入框」这一件事——真正的生效发生在用户点发送时（[applyEdit]），
     * 这样用户可以在里面继续追加附件、改措辞，中途反悔还能取消。
     */
    fun beginEdit(message: ChatMessage) {
        if (sending) return
        editingMessageId = message.id
        input = message.content
        // 原消息带的附件也一并回到待发送清单，编辑才是「所见即所得」
        attachments.clear()
        attachments += message.attachments.map { ref ->
            when (ref.kind) {
                AttachmentRef.Kind.FILE -> ChatAttachment.File("", ref.label)
                AttachmentRef.Kind.FOLDER -> ChatAttachment.Folder("", ref.label)
                AttachmentRef.Kind.PATH -> ChatAttachment.Path(ref.label)
                AttachmentRef.Kind.IMAGE -> ChatAttachment.Image("", ref.label)
            }
        }
        focusInput = true
    }

    /**
     * 应用编辑：改写原提问，作废它之后的一切，然后用新内容重跑。
     *
     * 「作废它之后的」是必须的：原来的回答是针对旧措辞生成的，
     * 留着会与新提问对不上（用户实测反馈的连带关系）。
     */
    suspend fun applyEdit(
        messageId: String,
        newContent: String,
        attached: List<ChatAttachment>,
    ) {
        val sid = session?.id ?: return
        val imageUris = attached.filterIsInstance<ChatAttachment.Image>().map { it.uri }
        val textAttachments = attached.filterNot { it is ChatAttachment.Image }
        val modelContext = textAttachments
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n\n") { it.toContext(context) }
            ?.let { attachContext + "\n" + it }

        // 1) 改写原提问
        ChatStore.updateMessage(
            context = context,
            sessionId = sid,
            messageId = messageId,
            content = newContent,
            imageUris = imageUris,
        )
        // 2) 补充 modelContext 与附件展示信息（updateMessage 不管这两项）
        ChatStore.patchUserExtras(
            context = context,
            sessionId = sid,
            messageId = messageId,
            attachments = attached.map { it.toRef() },
            modelContext = modelContext,
        )
        // 3) 作废它之后的消息，拿到可用的历史
        val kept = ChatStore.truncateAfter(context, sid, messageId)
        // 4) 新开一轮（复用 truncate 后的历史）
        val assistantId = java.util.UUID.randomUUID().toString()
        ChatStore.upsertAssistant(
            context = context,
            sessionId = sid,
            messageId = assistantId,
            content = "",
            segmentsJson = null,
            turnState = TurnState.Running,
        )
        agent.bindTurn(sid, assistantId)
        onStart(sid, assistantId, kept.forModel())
    }

    fun submit(text: String) {
        val content = text.trim()
        if ((content.isEmpty() && attachments.isEmpty()) || sending) return
        val attached = attachments.toList()
        val editId = editingMessageId
        input = ""
        attachments.clear()
        editingMessageId = null

        // ── 编辑态：作废原提问之后的消息，再用新内容重跑 ──
        if (editId != null) {
            scope.launch { applyEdit(editId, content, attached) }
            return
        }

        scope.launch {
            val title = content.ifBlank { attached.firstOrNull()?.label ?: "附件" }
            val target = session ?: ChatStore.newSession(context, title.take(24))

            // 图片走多模态，其余附件转成文本上下文
            val imageUris = attached.filterIsInstance<ChatAttachment.Image>().map { it.uri }
            val textAttachments = attached.filterNot { it is ChatAttachment.Image }

            // ── 一条用户消息承载全部输入 ──
            // 早先附件上下文被当成第二条 `role = USER` 的消息塞进会话，
            // 界面上于是出现两个用户气泡，第二个还是一大段原始上下文。
            // 现在附件只作为展示信息 + 一条仅发给模型的 modelContext。
            val modelContext = textAttachments
                .takeIf { it.isNotEmpty() }
                ?.joinToString("\n\n") { it.toContext(context) }
                ?.let { attachContext + "\n" + it }

            val userMessage = ChatMessage(
                role = ChatMessage.Role.USER,
                content = content,
                imageUris = imageUris,
                attachments = attached.map { it.toRef() },
                modelContext = modelContext,
            )
            ChatStore.append(context, target.id, userMessage)

            // ── 本轮 AI 消息先落一条 Running 占位 ──
            // 这样「切后台被回收 → 再进来」能知道上次没跑完，过程也不会丢。
            val assistantId = java.util.UUID.randomUUID().toString()
            ChatStore.upsertAssistant(
                context = context,
                sessionId = target.id,
                messageId = assistantId,
                content = "",
                segmentsJson = null,
                turnState = TurnState.Running,
            )
            // 回答区把过程写给这条消息
            agent.bindTurn(target.id, assistantId)

            // 交给外部任务持有者执行，离开页面也不会中断。
            // 历史用 forModel() 把 modelContext 拼进正文，界面与落盘都不受影响。
            onStart(target.id, assistantId, (target.messages + userMessage).forModel())
        }
    }

    /**
     * 链接点击的落点。
     *
     * 只有 http/https 走内置浏览器；`mailto:`、`tel:` 交给系统更合适
     * （内置 WebView 打不开它们）。判断放在这里而不是渲染层：
     * 「什么链接去哪」是宿主的决定，Markdown 只负责把 URL 递出来。
     */
    val linkOpener: (String) -> Unit = remember {
        { url ->
            if (url.startsWith("http://") || url.startsWith("https://")) {
                onOpenWeb(url)
            } else {
                runCatching {
                    context.startActivity(
                        android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(url),
                        ),
                    )
                }.onFailure { toast.show("无法打开链接") }
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.mcp.toolbox.core.design.component.LocalLinkOpener provides linkOpener,
    ) {
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
            navigationIconRes = AgentIcons.ic_menu,
            onNavigationClick = onOpenDrawer,
            actions = {
                // 终端：放在「新建对话」左侧，两个按钮各自 40dp，触控区不会互相挤占
                MiuixIconButton(
                    iconRes = AgentIcons.ic_terminal,
                    contentDescription = stringResource(R.string.chat_terminal),
                    onClick = onOpenTerminal,
                    buttonSize = 40.dp,
                    iconSize = 20.dp,
                )
                // 新建对话
                MiuixIconButton(
                    iconRes = AgentIcons.ic_chat,
                    contentDescription = stringResource(R.string.chat_new),
                    onClick = {
                        if (!sending) scope.launch { ChatStore.newSession(context, newChatTitle) }
                    },
                    buttonSize = 40.dp,
                    iconSize = 20.dp,
                )
            },
        )

        // 输入栏要「浮」在内容之上，而不是与内容平级。
        //
        // 原来是 Column 里两个兄弟：列表底边 = 输入栏顶边，形成一条硬裁切线，
        // 文字滚到那里直接消失（看起来像输入栏外面套了个矩形）。
        // 现在两者放进同一个 Box：列表铺满整个内容区、文字从输入栏下面滑过，
        // 输入栏贴在 Box 底部。与此同时给列表留出「输入栏高度」的底部内边距，
        // 保证最后一条消息滚到底时不会被输入栏永久遮住。
        var inputBarHeightPx by remember { mutableIntStateOf(0) }

        Box(Modifier.weight(1f)) {
            // ── AI 回答区 ──
            // 用户气泡 + AI 回答区（思考块 / 工具卡 / 正文 / 系统卡）由 AgentMessageList 渲染。
            // 顶栏与输入栏结构未动，只在此处接上流式联动。
            AgentMessageList(
                state = agent,
                messages = session?.messages.orEmpty(),
                bottomPadding = with(LocalDensity.current) { inputBarHeightPx.toDp() } + spacing.md,
                // 重新生成 / 继续生成 / 重试：都用当前会话已有的历史再跑一轮。
                // 截断由 ChatStore 负责（见其 truncateFrom / truncateAfter），
                // 这里不复制一套历史改写逻辑。
                onRegenerate = {
                    val sid = session?.id ?: return@AgentMessageList
                    // 重新生成：作废最后一条 AI 回答（清空内容与过程），原地重来
                    val lastAssistant = session?.messages
                        ?.lastOrNull { it.role == ChatMessage.Role.ASSISTANT }?.id
                    scope.launch { startNewTurn(sid, agent, context, lastAssistant) }
                },
                onDeleteAnswer = { id ->
                    val sid = session?.id ?: return@AgentMessageList
                    scope.launch { ChatStore.deleteMessage(context, sid, id) }
                },
                // 删除一「轮」：这条用户提问 + 它后面的过程与回答一起删。
                //
                // 为什么必须连带：只删用户那一条会把回答变成「无主的孤儿」——
                // 它还挂在列表里，但已经不知道是谁问的（用户实测反馈
                // 「发出去的问题不能删除」时提到的连带关系）。
                onDeleteTurn = { userMessageId ->
                    val sid = session?.id ?: return@AgentMessageList
                    scope.launch {
                        val msgs = ChatStore.sessions.value
                            .firstOrNull { it.id == sid }?.messages.orEmpty()
                        val start = msgs.indexOfFirst { it.id == userMessageId }
                        if (start >= 0) {
                            // 从这条提问开始，删到**下一条用户消息之前**为止
                            val nextUser = msgs.drop(start + 1)
                                .indexOfFirst { it.role == ChatMessage.Role.USER }
                            val endExclusive = if (nextUser < 0) msgs.size else start + 1 + nextUser
                            msgs.subList(start, endExclusive)
                                .forEach { ChatStore.deleteMessage(context, sid, it.id) }
                        }
                    }
                },
                onContinue = {
                    val sid = session?.id ?: return@AgentMessageList
                    // 复用最后一条 AI 消息（就是被中断的那条），过程原地续写
                    val lastAssistant = session?.messages
                        ?.lastOrNull { it.role == ChatMessage.Role.ASSISTANT }?.id
                    scope.launch { startNewTurn(sid, agent, context, lastAssistant) }
                },
                onRetry = {
                    val sid = session?.id ?: return@AgentMessageList
                    // 复用最后一条 AI 消息（就是被中断的那条），过程原地续写
                    val lastAssistant = session?.messages
                        ?.lastOrNull { it.role == ChatMessage.Role.ASSISTANT }?.id
                    scope.launch { startNewTurn(sid, agent, context, lastAssistant) }
                },
                // 工具重试：只重跑那一个工具，前面的过程原样保留。
                // 若这条消息不是当前轮，宿主改不到它，就明确告知而不是假装成功。
                onRetryTool = { messageId, round, seq, toolName, argsJson ->
                    // null = 已启动；非 null 是要告诉用户的原因
                    onRetryTool(messageId, round, seq, toolName, argsJson)?.let { toast.show(it) }
                    null
                },
                onOpenAuth = {},
                onApproveTool = { round, seq -> agent.resolveApproval(round, seq, allowed = true) },
                onDenyTool = { round, seq -> agent.resolveApproval(round, seq, allowed = false) },
                onEditMessage = { message -> beginEdit(message) },
                onCopied = { writeClipboard(agent.answerText()) },
                // 「朗读 / 分享为图片 / 反馈」三项尚未实现，菜单项会置灰。
                // 这里留空实现是**刻意的**：宁可让用户看到「暂不可用」，
                // 也不要弹一个假装成功、实际什么都没发生的提示。
                onSpeak = {},
                // 分享为图片：把捕获到的图层写成 PNG，再交给系统分享面板。
                // 失败时明确告知，**不假装成功**。
                onShareImage = { layer ->
                    scope.launch {
                        val ok = com.mcp.toolbox.feature.home.agent.AnswerImageShare.share(context, layer)
                        if (!ok) toast.show("生成图片失败")
                    }
                },
                // 打开来源链接。
                //
                // 这里走系统浏览器而不是应用内的 Web 模块：`WebScreen` 目前
                // 不接受「初始 URL」，要支持得先给 web 模块加一个入口参数。
                // 那是另一个模块的改动，不应该混在这次里一并塞进来——
                // 用系统浏览器先让它真实可用。
                // 来源面板里的「打开」也走内置浏览器。
                //
                // 早先这里直接 `startActivity(ACTION_VIEW)`，结果同一个界面里
                // 「正文里的链接」走内置浏览器、「来源卡的打开」却跳到系统浏览器
                // （实测反馈）。同一种操作不该有两套行为。
                onOpenSource = linkOpener,
                onFeedback = { excerpt ->
                    feedbackDraft = FeedbackComposer.compose(
                        context = context,
                        model = currentModel,
                        answerExcerpt = excerpt,
                    )
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = spacing.pageHorizontal),
            )
            InputBar(
                modifier = Modifier.align(Alignment.BottomCenter),
                onHeightMeasured = { inputBarHeightPx = it },
                // 把「模型」按钮的窗口矩形收上来：菜单要用它定位。
                // 按钮在 InputBar 内部，菜单在外层，只能这样传。
                onModelAnchorChange = { modelAnchor = it },
                value = input,
                onValueChange = { input = it },
                sending = sending,
                editing = editing,
                onCancelEdit = {
                    editingMessageId = null
                    input = ""
                },
                onSend = { submit(input) },
                focusRequester = inputFocus,
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
                        )
                    }
                },
                attachmentRow = {
                    // 图片留在面板内：缩略图本身就是「所见即所得」的输入内容。
                    val images = attachments.withIndex().filter { it.value is ChatAttachment.Image }
                    if (images.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            images.forEach { (index, item) ->
                                if (item is ChatAttachment.Image) {
                                    ImageAttachmentCard(
                                        uri = item.uri,
                                        label = item.label,
                                        onRemove = { attachments.removeAt(index) },
                                    )
                                }
                            }
                        }
                    }
                },
                fileRow = {
                    // 文件 / 文件夹 / 路径挪到面板之外：它们是一份待发送清单，
                    // 和输入框不是同一层，压在框里只会让输入区变高变乱。
                    val files = attachments.withIndex().filter { it.value !is ChatAttachment.Image }
                    if (files.isNotEmpty()) {
                        FlowRow(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(spacing.sm),
                        ) {
                            files.forEach { (index, item) ->
                                FileChip(
                                    visual = fileVisual(item),
                                    onRemove = { attachments.removeAt(index) },
                                )
                            }
                        }
                    }
                },
            )
        }
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
            iconRes = AgentIcons.ic_svc_pipeline_app,
            onClick = { showOverflow = false; picker = PickerKind.MODEL },
        )
        MiuixMenuItem(
            text = stringResource(R.string.chat_pick_reasoning),
            iconRes = AgentIcons.ic_brain,
            onClick = { showOverflow = false; picker = PickerKind.REASONING },
        )
        MiuixMenuDivider()
        MiuixMenuItem(
            text = stringResource(R.string.chat_attach_image),
            iconRes = AgentIcons.ic_image,
            onClick = {
                showOverflow = false
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
        MiuixMenuItem(
            text = stringResource(R.string.chat_attach_file),
            iconRes = AgentIcons.ic_file_text,
            onClick = { showOverflow = false; pickFile.launch(arrayOf("*/*")) },
        )
        MiuixMenuItem(
            text = stringResource(R.string.chat_attach_folder),
            iconRes = AgentIcons.ic_folder,
            onClick = { showOverflow = false; pickFolder.launch(null) },
        )
        MiuixMenuItem(
            text = stringResource(R.string.chat_attach_path),
            iconRes = AgentIcons.ic_edit,
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

    // 模型：锚定到模型按钮本身，右对齐展开
    MiuixOverflowMenu(
        expanded = picker == PickerKind.MODEL,
        onDismiss = { picker = null },
        anchor = modelAnchor,
        stickToBottom = true,
        // focusable=true：才拦得住返回手势/返回键，并让「点外部收回」成立。
        // 这里不再需要「保持输入法」——连点菜单时输入框并非必然处于编辑焦点。
        focusable = true,
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
        )
    }

    // ── 反馈确认层 ──
    // 先让用户看到将要发出去的内容，再选「去提 issue」或「发邮件」。
    // 刻意不做静默上报：应用全部数据都在本机，突然往外发东西与这个定位矛盾。
    val draft = feedbackDraft
    if (draft != null) {
        FeedbackSheet(
            draft = draft,
            onDismiss = { feedbackDraft = null },
            onOpenIssue = {
                val ok = FeedbackComposer.openIssue(context, "反馈：", draft)
                if (!ok) toast.show("无法打开浏览器")
                feedbackDraft = null
            },
            onOpenMail = {
                val ok = FeedbackComposer.openMail(context, "Yutu Agt 反馈", draft)
                if (!ok) toast.show("没有可用的邮件应用")
                feedbackDraft = null
            },
            onCopy = {
                writeClipboard(draft)
                toast.show("已复制到剪贴板")
                feedbackDraft = null
            },
        )
    }

    // 轻量提示：直接铺在根 Column 里。
    //
    // 不用 `MiuixToastHost` + `Modifier.align`：根是 `Column` 而不是 `Box`，
    // `align` 在这里不合法；而为了放一个提示条去包一层 Box、把所有内容缩进一级，
    // 改动面太大。这里就是一行贴顶的胶囊，够用且无副作用。
    }  // CompositionLocalProvider

    toast.message?.let { msg ->
        LaunchedEffect(msg.id) {
            delay(2200)
            // 只在「还是这条提示」时关闭：期间来了新提示就不该被旧的计时器关掉
            if (toast.message?.id == msg.id) toast.dismiss()
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = spacing.sm, bottom = spacing.xs),
            contentAlignment = Alignment.Center,
        ) {
            MiuixText(
                text = msg.text,
                style = MiuixTheme.typography.labelMedium,
                color = colors.onSurface,
                modifier = Modifier
                    .sqSurface(colors.surfaceContainerHigh, MiuixTheme.radius.md)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 反馈确认弹层。
 *
 * 三件事：**看到内容** → 选择渠道 → 走人。
 * 「复制」是给那些想自己挑渠道（论坛、聊天工具）的用户留的后路。
 */
@Composable
private fun FeedbackSheet(
    draft: String,
    onDismiss: () -> Unit,
    onOpenIssue: () -> Unit,
    onOpenMail: () -> Unit,
    onCopy: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    MiuixBottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.pageHorizontal, vertical = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.md),
        ) {
            MiuixText(
                text = "反馈",
                style = MiuixTheme.typography.titleMedium,
            )
            MiuixText(
                text = "下面是将要发送的内容。它只包含版本、机型和这段回答，不含其它会话记录。",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            // 内容用等宽小字铺开并限高：要能看全，又不能把整屏占满
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState())
                    .sqSurface(colors.surfaceContainerHigh, MiuixTheme.radius.md)
                    .padding(10.dp),
            ) {
                MiuixText(
                    text = draft,
                    style = MiuixTheme.typography.code,
                    color = colors.onSurface,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                MiuixButton(text = "提 issue", onClick = onOpenIssue, modifier = Modifier.weight(1f))
                MiuixButton(
                    text = "发邮件",
                    onClick = onOpenMail,
                    modifier = Modifier.weight(1f),
                    variant = MiuixButtonVariant.OUTLINED,
                )
                MiuixIconButton(
                    iconRes = AgentIcons.ic_copy,
                    contentDescription = "复制反馈内容",
                    onClick = onCopy,
                )
            }
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
private fun ImageAttachmentCard(
    uri: String,
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    val shape = RoundedCornerShape(MiuixTheme.radius.md)
    val colors = MiuixTheme.colors
    Box(
        modifier = modifier
            .size(ATTACH_THUMB_SIZE)
            .clip(shape)
            .background(colors.surfaceContainerHighest),
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
            // 解码未完成时先摆一枚淡图标，占位与成品同尺寸，不跳版
            MiuixIcon(
                AgentIcons.ic_image,
                label,
                tint = colors.onSurfaceVariant,
                size = 20.dp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // 移除按钮压在右上角：黑底半透明白叉，浅图深图都看得清
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            MiuixIcon(
                AgentIcons.ic_close,
                stringResource(R.string.chat_attach_remove),
                tint = Color.White,
                size = 12.dp,
            )
        }
    }
}

/** 文件类附件的外观：类型图标、图标配色、展示文案。 */
private data class FileVisual(val icon: Int, val tint: Color, val text: String)

/**
 * 算出文件 / 文件夹 / 路径附件的展示外观。
 *
 * 文件夹（含指向目录的手输路径）是「文件夹图标 + 路径」，单个文件是「类型图标 + 文件名」；
 * 手输路径两个分支都用压缩后的路径——只显示末段文件名，就看不出它到底来自哪里。
 */
@Composable
private fun fileVisual(item: ChatAttachment): FileVisual {
    val colors = MiuixTheme.colors
    return when (item) {
        is ChatAttachment.Folder ->
            FileVisual(AgentIcons.ic_folder, colors.primary, compactPath(item.label))

        is ChatAttachment.Path -> {
            val path = compactPath(item.path)
            val (icon, tint) = if (item.isDirectory) {
                AgentIcons.ic_folder to colors.primary
            } else {
                fileTypeVisual(item.path)
            }
            FileVisual(icon, tint, path)
        }

        is ChatAttachment.File -> {
            val (icon, tint) = fileTypeVisual(item.label)
            FileVisual(icon, tint, item.label)
        }

        // 图片走 [ImageAttachmentCard]，这里只兜底，避免 when 不穷尽
        is ChatAttachment.Image -> FileVisual(AgentIcons.ic_image, colors.primary, item.label)
    }
}

/** 按扩展名挑图标与配色。 */
@Composable
private fun fileTypeVisual(name: String): Pair<Int, Color> {
    val colors = MiuixTheme.colors
    return when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> AgentIcons.ic_file_pdf to colors.error
        "ppt", "pptx", "keynote" -> AgentIcons.ic_presentation to colors.warning
        "zip", "rar", "7z", "tar", "gz", "bz2" -> AgentIcons.ic_file_zip to colors.warning
        "ttf", "otf", "woff", "woff2" -> AgentIcons.ic_font_size to colors.primary
        "csv", "xls", "xlsx" -> AgentIcons.ic_table to colors.success
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg" ->
            AgentIcons.ic_image to colors.primary
        "mp4", "mkv", "mov", "avi", "webm", "flv" -> AgentIcons.ic_video to colors.tertiary
        "mp3", "wav", "flac", "m4a", "ogg", "aac" -> AgentIcons.ic_music to colors.tertiary
        "apk", "apks", "xapk" -> AgentIcons.ic_phone_device to colors.success
        "db", "sqlite", "sqlite3", "sql" -> AgentIcons.ic_json_data to colors.success
        "pem", "der", "crt", "cer", "p12", "jks", "keystore" -> AgentIcons.ic_key to colors.warning
        "html", "htm", "js", "ts", "jsx", "tsx", "kt", "kts", "java", "py", "sh",
        "c", "cpp", "h", "rs", "go", "rb", "php", "xml", "json", "yml", "yaml",
        -> AgentIcons.ic_code to colors.tertiary
        "txt", "md", "log", "ini", "conf", "properties", "toml" ->
            AgentIcons.ic_file_text to colors.onSurfaceVariant
        else -> AgentIcons.ic_file to colors.onSurfaceVariant
    }
}

/**
 * 路径压缩：只保留末 [PATH_SEGMENTS] 段，砍掉的部分用省略号代替。
 *
 * 完整路径动辄五六段，铺出来 chip 会横着占满一行；只留文件名又丢掉了目录信息。
 */
private fun compactPath(path: String): String {
    val parts = path.trim().trimEnd('/').split('/').filter { it.isNotBlank() }
    val visible = parts.takeLast(PATH_SEGMENTS)
    val text = visible.joinToString("/")
    return if (parts.size > visible.size) "…/$text" else text.ifBlank { path }
}

/**
 * 文件 / 文件夹 / 路径附件：浮在输入框面板外的胶囊。
 *
 * 类型图标垫一层同色淡染的圆角底：纯描边图标在大屏上太单薄，整块实心色又太重，
 * 渐变染色正好落在「简洁，但有细节」的位置。
 */
@Composable
private fun FileChip(visual: FileVisual, onRemove: () -> Unit) {
    val colors = MiuixTheme.colors
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .shadow(MiuixTheme.dimens.elevation.level1, shape, clip = false)
            .clip(shape)
            .background(colors.surface)
            .border(BorderStroke(1.dp, colors.outlineVariant), shape)
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(MiuixTheme.radius.sm))
                .background(
                    Brush.linearGradient(
                        listOf(
                            visual.tint.copy(alpha = 0.30f),
                            visual.tint.copy(alpha = 0.10f),
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            MiuixIcon(visual.icon, null, tint = visual.tint, size = 14.dp)
        }
        Spacer(Modifier.width(4.dp))
        MiuixText(
            text = visual.text,
            modifier = Modifier.widthIn(max = 112.dp),
            style = MiuixTheme.typography.labelMedium,
            color = colors.onSurface,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            MiuixIcon(
                AgentIcons.ic_close,
                stringResource(R.string.chat_attach_remove),
                tint = colors.onSurfaceVariant,
                size = 13.dp,
            )
        }
    }
}

/** 图片缩略图边长（正方形）。 */
private val ATTACH_THUMB_SIZE = 64.dp
/** 路径展示保留的末段数：够看出在哪个目录，又不至于把 chip 撑满一行。 */
private const val PATH_SEGMENTS = 3
/** 解码目标边长（按 3x 屏估算：够清晰，又不至于占内存）。 */
private const val THUMB_EDGE_PX = 168

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

private enum class PickerKind { MODEL, REASONING }

/** 输入栏：发送按钮内嵌在气泡里；有内容时气泡变白。 */
@Composable
private fun InputBar(
    onModelAnchorChange: (androidx.compose.ui.geometry.Rect) -> Unit = {},
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
    /** 图片附件：留在输入框面板内。 */
    attachmentRow: @Composable () -> Unit = {},
    /** 文件 / 文件夹 / 路径附件：铺在输入框面板外的上方。 */
    fileRow: @Composable () -> Unit = {},
    /** 把输入栏实测高度报出去，供外层给内容列表留出等高的底部内边距。 */
    onHeightMeasured: (Int) -> Unit = {},
    /** 外部请求把焦点给到输入框（进入编辑态时用）。 */
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
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
    ) {
        // 文件 / 文件夹 / 路径附件浮在面板之外。
        // 面板只有自己那圈圆角不透明，上方这行 chip 才真的落在框外。
        fileRow()
        Column(
            modifier = Modifier
                .fillMaxWidth()
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
                        AgentIcons.ic_edit,
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
                            AgentIcons.ic_close,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (focusRequester != null) {
                                Modifier.focusRequester(focusRequester)
                            } else {
                                Modifier
                            },
                        ),
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
                        AgentIcons.ic_plus,
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
                        // 与「记忆」入口分开：原来两处都是 ic_brain，看不出区别。
                        // 芯片＝算力＝推理档位，且这个图标全仓库未被他处占用。
                        AgentIcons.ic_ai_chip,
                        stringResource(R.string.chat_pick_reasoning),
                        tint = if (reasoningActive) colors.primary else colors.onSurfaceVariant,
                        size = 20.dp,
                    )
                }

                Spacer(Modifier.weight(1f))

                // 模型：品牌色圆底 + 白色图形，放在发送键左侧
                //
                // 记下自身矩形传给菜单。**不传 anchor 时菜单会退回用 Popup 的父布局
                // 矩形定位**，而父布局约等于整条输入栏，右对齐就落到屏幕最右侧 ——
                // 与这颗按钮的实际位置对不上（用户实测反馈「按按钮位置算不对」）。
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .onGloballyPositioned { onModelAnchorChange(it.boundsInWindow()) }
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
                            AgentIcons.ic_svc_pipeline_app,
                            null,
                            tint = colors.onSurfaceVariant,
                            size = 20.dp,
                        )
                    }
                }

                MiuixIconButton(
                    iconRes = if (sending) AgentIcons.ic_stop else AgentIcons.ic_arrow_up,
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
}

/**

 */
@Composable
private fun UsageIndicator(
    used: Int,
    window: Int?,
    estimated: Boolean,
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
        // 轨道常显：它就是「上下文上限」那条线，窗口未知时也不该整条消失；
        // 只是没有真实窗口就不填充（用猜测的分母画长度会误导）
        Box(
            modifier = Modifier
                .width(56.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.surfaceContainerHigh),
        ) {
            if (ratio != null) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(ratio)
                        .background(barColor, RoundedCornerShape(2.dp)),
                )
            }
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
    }
}

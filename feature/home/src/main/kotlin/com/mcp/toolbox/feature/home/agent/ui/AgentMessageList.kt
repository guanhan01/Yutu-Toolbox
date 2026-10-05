package com.mcp.toolbox.feature.home.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixIconRes
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.icon.AgentIcons
import com.mcp.toolbox.core.design.shape.SquircleRadii
import com.mcp.toolbox.core.design.shape.sqBorder
import com.mcp.toolbox.core.design.shape.sqSurface
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.feature.home.AttachmentRef
import com.mcp.toolbox.feature.home.ChatMessage
import com.mcp.toolbox.feature.home.TurnState
import com.mcp.toolbox.feature.home.agent.AgentChatState
import com.mcp.toolbox.feature.home.agent.stream.AutoScrollState
import com.mcp.toolbox.feature.home.agent.stream.AutoScrollTracker
import com.mcp.toolbox.feature.home.agent.AnswerSegment
import com.mcp.toolbox.feature.home.agent.NarrationSegment
import com.mcp.toolbox.feature.home.agent.Segment
import com.mcp.toolbox.feature.home.agent.SystemSegment
import com.mcp.toolbox.feature.home.agent.ThinkingSegment
import com.mcp.toolbox.feature.home.agent.ToolCallSegment
import kotlinx.coroutines.launch

/**
 * 消息区。
 *
 * ## 一条消息 = 一条 LazyColumn item
 *
 * 关键点：**AI 的过程（思考 / 工具 / 正文）与这条消息是同一条 item**，
 * 而不是「历史消息」和「当前时间线」两组分开渲染。
 *
 * 早先分成两组、且当前时间线是个永不 commit 的缓冲，于是：
 * 上一轮的过程块留在列表尾、新问题被塞到它下面（顺序倒置），
 * 第二轮的工具事件又按 `(round, seq)` 命中上一轮的卡片而不新建。
 * 现在每轮结束就把过程写进对应消息，列表里就是纯粹的「按时间排列的消息」。
 */
@Composable
fun AgentMessageList(
    state: AgentChatState,
    messages: List<ChatMessage>,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onRegenerate: () -> Unit,
    onDeleteAnswer: (String) -> Unit,
    /**
     * 删除一整轮：从这条用户消息起、到它后面的回答为止全部删掉。
     *
     * 只删用户那一条会把过程与回答变成「无主的孤儿」（还挂在列表里，
     * 但已经不知道是谁问的），所以删除必须以「轮」为单位。
     */
    onDeleteTurn: (messageId: String) -> Unit = {},
    /**
     * 「编辑」——真正的编辑：内容回到输入框，改完再发出去。
     *
     * 早先这里接的是 `onRegenerate()`（直接重跑一遍模型），
     * 所以用户改不了自己的提问（用户实测反馈「编辑是真编辑，即内容回到输入框进行编辑」）。
     * 传整条消息而不是 id：输入框需要原文，重新生成也需要它当上下文。
     */
    onEditMessage: (ChatMessage) -> Unit = {},
    /** 「朗读」/「分享为图片」/「反馈」；未接入时对应菜单项置灰。 */
    onSpeak: () -> Unit = {},
    onShareImage: (androidx.compose.ui.graphics.layer.GraphicsLayer) -> Unit = {},
    onFeedback: (String) -> Unit = {},
    /** 打开一个来源链接（宿主有内嵌浏览器，本模块不直接持有它）。 */
    onOpenSource: (url: String) -> Unit = {},
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    /**
     * 工具卡上的「重试」：只重跑那一个工具，前面的过程原样保留。
     *
     * 返回 null 表示已启动重试（界面会自己刷新）；非 null 是要展示给用户的原因
     * （这条消息不是当前轮、或已有请求在跑）—— 宿主据此提示，而不是静默忽略。
     */
    onRetryTool: (messageId: String, round: Int, seq: Int, toolName: String, argsJson: String?) -> String? = { _, _, _, _, _ -> null },
    onOpenAuth: () -> Unit,
    onApproveTool: (round: Int, seq: Int) -> Unit,
    onDenyTool: (round: Int, seq: Int) -> Unit,
    onCopied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    /**
     * 输入栏占用的底部高度（像素）。
     *
     * 「到底了没有」的判据必须扣掉它：输入栏是浮在列表**之上**的，
     * 末项底边贴在视口底边时其实还被输入栏盖着。
     */
    val bottomInsetPx = with(density) { bottomPadding.roundToPx() }

    val itemCount = messages.size
    /** 按「轮」分组的结果：在组合期算好，LazyColumn 的 item lambda 里不能调 @Composable。 */
    val turns = remember(messages) { groupIntoTurns(messages) }

    /**
     * 距底距离。
     *
     * 判据是「最后一个 item 的底边距列表底边多远」——用不到 `.firstVisibleItemIndex`
     * 反推总高度，`layoutInfo` 里直接就有这两项。
     */
    /**
     * 距底距离。
     *
     * ## 两个必须做对的地方
     *
     * 1. `visibleItemsInfo` 为空时**保持上一次的值**，不能报 0。
     *    列表在布局瞬态里会短暂没有可见项，而 0 的语义是「正好在底部」——
     *    那会让状态机把 Drifted 判回 Anchored、把看历史的用户拽回底部。
     *
     * 2. **最后一项比视口还高时，不能用 `viewportEnd - offset - size`**。
     *    那条式子算的是「该项底边在视口下方多少个像素」，
     *    而底边远超视口时它是**负数**，一 clamp 成 0 就被读成「已在底部」——
     *    「回到底部」按钮因此永远补不齐最后那一段（实测：点完只滚到长回答的开头）。
     *
     *    正解是「该项还有多少**像素**没露出来」：
     *    `size - 已露出部分`，其中已露出部分被视口裁切。
     */
    // ⚠️ 不要在 `derivedStateOf` **内部写状态**。
    //
    // 早先写成 `if (last == null) lastDistance else { lastDistance = d; d }`——
    // 派生值里做副作用会让它的缓存失效判断错乱，滚动手势与跟随效果都跟着抖
    //（实测：点「回到底部」只前进几段就停住）。改成单向数据流：
    // 布局信息变化 → 写状态 → 派生/直接读。
    var distanceFromBottom by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo }.collect { info ->
            val last = info.visibleItemsInfo.lastOrNull()
            // `visibleItemsInfo` 为空（布局瞬态）时保持上一次的值：
            // 报 0 的语义是「正好在底部」，会把正在看历史的用户拽回底部。
            if (last != null) {
                // 目标线要扣掉输入栏高度：它是浮在列表之上的，
                // 「能看见结尾」= 末项底边停在输入栏之上，而不是停在视口底边。
                distanceFromBottom = overshootBelow(
                    target = info.viewportEndOffset - bottomInsetPx,
                    itemOffset = last.offset,
                    itemSize = last.size,
                )
            }
        }
    }

    /**
     * 用户是否**正在主动操作**滚动。
     *
     * 这是修「生成中无法上下移动」的关键：只靠 `isScrollInProgress` 是不够的，
     * 因为自动跟随本身也会把它置 true，于是「用户滚动」与「程序滚动」分不开，
     * 状态机在跟随之后又把状态复位成 Anchored，用户被反复拽回底部。
     */
    // LazyListState 自带 interactionSource，直接用它即可
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val userIsMoving = dragging

    // 用户一上手就立刻判 Drifted（不等滚动结束、不等超过阈值）。
    //
    // 早先的判据是「滚动中 && 距底 > 300px」——流式期间自动跟随每隔几十毫秒
    // 就把内容拉回底部，用户永远攒不够 300px，于是**永远进不了 Drifted**，
    // 表现就是「无论如何上下移都会回到原位」。
    LaunchedEffect(userIsMoving, distanceFromBottom) {
        state.autoScroll.onDistanceFromBottom(
            distancePx = distanceFromBottom,
            userIsScrolling = userIsMoving,
            thresholdPx = if (userIsMoving) {
                AutoScrollTracker.DRAGGING_THRESHOLD_PX
            } else {
                AutoScrollTracker.ANCHOR_THRESHOLD_PX
            },
        )
    }

    /**
     * 跟随新内容：**按增量滚动底边**，而不是 `animateScrollToItem(lastIndex)`。
     *
     * `animateScrollToItem` 的语义是「把目标 item 拉进视口」——只要它还没完全可见，
     * 就一定会继续滚。流式期间每个 token 提交都调一次，等于持续把用户往底部拽。
     * 用 `scrollBy` 只补「本次内容增长了多少」，用户只要不动就能平滑跟随，
     * 一旦上手立刻被 [userIsMoving] 拦下。
     *
     * 触发源只取「消息条数」与「尾部长」——不要拿整个 `state.segments` 当 key：
     * 它每 500ms 因思考计时换一次实例，会导致计时一跳滚一次。
     */
    val tailLen = state.segments.lastOrNull()
        ?.let { seg ->
            when (seg) {
                is AnswerSegment -> seg.markdown.length
                is ThinkingSegment -> seg.text.length
                else -> 0
            }
        } ?: 0
    var followedFor by remember { mutableStateOf("") }
    val followKey = "$itemCount:$tailLen"
    LaunchedEffect(followKey, userIsMoving, state.autoScroll.state) {
        if (userIsMoving) {
            // 用户正在操作：这一帧不跟随，等他自己停手
            return@LaunchedEffect
        }
        if (state.autoScroll.state != AutoScrollState.Anchored) return@LaunchedEffect
        if (followedFor == followKey) return@LaunchedEffect
        followedFor = followKey
        // 把末项底边拉到「视口底边 − 输入栏」这条线上。
        //
        // ⚠️ 这里的差值必须用 `overshootBelow` 并扣掉输入栏高度。
        // 之前写成 `viewportEnd - offset - size`：最后一条长回答**高于视口**时
        // 这个值恒为负、clamp 成 0，于是 `scrollBy(0)` —— 流式期间根本不跟随，
        // 内容全堆在视口下方（「回到底部」也是同一个错算的受害者）。
        //
        // 另外**不要**给 `missing` 设上限（早先有个 FOLLOW_MAX_PX = 240）。
        // 那个上限本意是「离底太远说明用户在看历史」，但这件事已经由状态机管了：
        // 用户一上手就是 Drifted，这里根本不会执行。留着上限的唯一效果是
        // 让内容可以藏到输入栏底下 240px 处 —— 输入栏有 499px 高，
        // 结果就是「跟随了，但结尾仍被输入栏盖住」。
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return@LaunchedEffect
        val missing = overshootBelow(
            target = info.viewportEndOffset - bottomInsetPx,
            itemOffset = last.offset,
            itemSize = last.size,
        )
        if (missing >= 1) {
            listState.scrollBy(missing.toFloat())
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (itemCount == 0) {
            ChatEmptyState()
            return@Box
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomPadding + 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 按「轮」渲染：一轮 = 一条用户提问 + 紧随其后的 AI 回答。
            //
            // 为什么要成组而不是逐条：用户要求「分享为图片要包含问题」，
            // 而一张图必须同时覆盖提问气泡与回答，否则分享出去对方看不到问了什么。
            // 分组后把 graphicsLayer 挂在轮上，一次捕获就是完整一轮。
            items(turns, key = { it.id }) { turn ->
                TurnRow(
                    turn = turn,
                    isLatestTurn = turn.id == turns.lastOrNull()?.id,
                    // ⚠️ 判据必须是「state **确实绑定了这条消息**」，不能只是「它是最后一条」。
                    // 冷启动时 state 还没绑定任何消息，若按「最后一条」判定，就会拿一个
                    // 空的时间线去渲染，把磁盘上恢复出来的过程整个盖掉（实测踩到：
                    // AI 气泡变成一片空白）。
                    live = state.segments.takeIf { state.messageId == turn.answer?.id },
                    state = state,
                    onRegenerate = onRegenerate,
                    onDeleteAnswer = onDeleteAnswer,
                    onDeleteTurn = onDeleteTurn,
                    onEditMessage = onEditMessage,
                    onSpeak = onSpeak,
                    onShareImage = onShareImage,
                    onFeedback = onFeedback,
                    onOpenSource = onOpenSource,
                    onContinue = onContinue,
                    onRetry = onRetry,
                    onRetryTool = onRetryTool,
                    onOpenAuth = onOpenAuth,
                    onApproveTool = onApproveTool,
                    onDenyTool = onDenyTool,
                    onCopied = onCopied,
                )
            }
        }

        AnimatedVisibility(
            visible = state.autoScroll.showJumpPill,
            enter = fadeIn(tween(280)),
            exit = fadeOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = bottomPadding + 12.dp),
        ) {
            JumpToBottomPill(
                hasNewContent = state.streaming,
                onClick = {
                    state.autoScroll.onJumpRequested()
                    scope.launch {
                        scrollToBottom(
                            listState = listState,
                            itemCount = itemCount,
                            // 输入栏高度：列表要为空出它而多滚一段
                            bottomInsetPx = with(density) { bottomPadding.roundToPx() },
                        )
                        state.autoScroll.onJumpFinished()
                    }
                },
            )
        }
    }
}

/**
 * 真正滚到底部。
 *
 * ## 为什么不能只调 `animateScrollToItem(lastIndex)`
 *
 * 它的语义是「把该项的**顶部**对齐到视口顶部」。当最后一项（一条长回答）
 * 本身高于视口时，滚完只是停在它的开头，离底部还差一整屏——用户点了
 * 「回到底部」却发现没到底（实测反馈）。
 *
 * 所以分两步：先把末项滚进视口，再按「还剩多少像素在视口下方」补齐。
 * 循环两轮是因为补齐过程可能触发 LazyColumn 组合新的 item，高度会再变一次。
 */
private suspend fun scrollToBottom(
    listState: androidx.compose.foundation.lazy.LazyListState,
    itemCount: Int,
    /**
     * 底部被浮层遮住的高度（这里就是输入栏）。
     *
     * ⚠️ 必须扣掉它。输入栏是 `Box` 里 `align(BottomCenter)` 浮在列表**之上**的，
     * 而最后一条回答常常比视口还高——此时「末项底边贴住视口底边」并不等于
     * 「用户能看见结尾」，正文与操作条会被输入栏盖住（实测截图：复制全文那一行
     * 压在输入栏下面）。正确目标是「末项底边 = 视口底边 − 输入栏高度」。
     */
    bottomInsetPx: Int,
) {
    if (itemCount <= 0) return
    listState.animateScrollToItem(itemCount - 1)
    // 补齐余量：反复用**无动画**的 scrollBy 补，直到末项底边停在输入栏之上。
    //
    // 实测日志（长回答 2971px、视口 2210px）：
    //   off=0    隐藏 761px  → 补 761
    //   off=-761 隐藏 0px    → 到底
    // 过程中试过的 `scrollToItem(index, 大偏移)` 只前进几项就停，所以最终选闭环——
    // 它不依赖任何高度估算，输入栏高度变了也照样收敛。
    //
    // 为什么不用 `scrollToItem(index, 大偏移)` 一步到位：实测它只前进几项就停住
    // （原因未定），而「读布局 → 补差值 → 再读」这种闭环不依赖任何高度估算，
    // 高度怎么变都能收敛。上限 40 次是防呆，避免布局抖动时死循环。
    repeat(40) {
        val info = listState.layoutInfo
        val last = info.visibleItemsInfo.lastOrNull() ?: return
        // 目标底边 = 视口底边 − 输入栏高度（输入栏浮在列表之上，会盖住内容）
        val missing = overshootBelow(
            target = info.viewportEndOffset - bottomInsetPx,
            itemOffset = last.offset,
            itemSize = last.size,
        )
        if (missing <= 1) return
        listState.scrollBy(missing.toFloat())
    }
}


/**
 * item 的底边比目标线低出多少像素。
 *
 * ## 这里错过两次，值得写清楚
 *
 * `itemOffset` 是相对视口顶端的偏移（可以为负），`size` 是项的完整高度，
 * 所以 **项底边 = itemOffset + itemSize**，与目标线的距离就是两者之差。
 *
 * 我先后写错的两版：
 * 1. `viewportEnd - itemOffset - itemSize` —— 算的是「底边相对视口底边」，
 *    目标是「视口底边 − 输入栏高度」时就会差一整个输入栏；
 * 2. `itemSize - (viewportEnd - itemOffset).coerceIn(0, itemSize)` ——
 *    对**高于视口的项**恒等于 0（clamp 把「底边在哪」的信息抹掉了），
 *    于是永远判成「已到底」。
 *
 * 正解就是最朴素的减法，只在「底边已高于目标线」时收敛到 0。
 */
private fun overshootBelow(target: Int, itemOffset: Int, itemSize: Int): Int =
    (itemOffset + itemSize - target).coerceAtLeast(0)

/** 用户气泡最大宽度：屏宽减去两侧安全边距后的合理上限。 */
private val BUBBLE_MAX_WIDTH = 300.dp

private fun distanceFromBottom(listState: androidx.compose.foundation.lazy.LazyListState, count: Int): Int {
    val info = listState.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0
    return (count - 1 - last.index) * (last.size + 8)
}

/** 一轮：一条用户提问 + 紧随其后的 AI 回答（可能为空，例如刚发出还没回）。 */
private data class Turn(val question: ChatMessage?, val answer: ChatMessage?) {
    val id: String get() = question?.id ?: answer?.id.orEmpty()
}

/**
 * 把扁平的 message 列表切成「轮」。
 *
 * 规则：一条 USER 消息开启一轮，其后直到下一条 USER 之前的所有消息都归这一轮
 * （正常情况下只有一条 ASSISTANT；万一有多条也不会丢）。
 */
private fun groupIntoTurns(messages: List<ChatMessage>): List<Turn> {
    val out = mutableListOf<Turn>()
    var q: ChatMessage? = null
    var answers = mutableListOf<ChatMessage>()
    fun flush() {
        if (q != null || answers.isNotEmpty()) {
            // 一轮里最多展示一条回答；多出来的（异常数据）合并显示
            out += Turn(q, answers.firstOrNull())
        }
        q = null
        answers = mutableListOf()
    }
    messages.forEach { m ->
        when (m.role) {
            ChatMessage.Role.USER -> {
                flush()
                q = m
            }

            ChatMessage.Role.ASSISTANT -> answers += m
            ChatMessage.Role.SYSTEM -> Unit
        }
    }
    flush()
    return out
}

/**
 * 一轮的渲染单元。
 *
 * ## 为什么捕获层挂在「轮」上而不是回答上
 *
 * 「分享为图片」要包含用户的提问（用户要求），所以图层必须同时覆盖气泡与回答。
 * 挂在一条消息上做不到这件事。
 *
 * 录制的范围**不含底部操作条**：那是界面控件，分享出去会让对方以为也能点。
 */
@Composable
private fun TurnRow(
    turn: Turn,
    live: List<Segment>?,
    state: AgentChatState,
    isLatestTurn: Boolean,
    onRegenerate: () -> Unit,
    onDeleteAnswer: (String) -> Unit,
    onDeleteTurn: (String) -> Unit,
    onEditMessage: (ChatMessage) -> Unit,
    onSpeak: () -> Unit,
    onShareImage: (androidx.compose.ui.graphics.layer.GraphicsLayer) -> Unit,
    onFeedback: (String) -> Unit,
    onOpenSource: (String) -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onRetryTool: (messageId: String, round: Int, seq: Int, toolName: String, argsJson: String?) -> String?,
    onOpenAuth: () -> Unit,
    onApproveTool: (round: Int, seq: Int) -> Unit,
    onDenyTool: (round: Int, seq: Int) -> Unit,
    onCopied: () -> Unit,
) {
    val shareLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()

    /**
     * 原始输出 / 来源清单面板的状态。
     *
     * 放在「轮」这一层而不是每张工具卡里：面板是全屏浮层，
     * 一个轮次同时只可能开一个；挂在卡片上会导致多张卡片各持一份状态，
     * 也更容易随卡片折叠而意外消失。
     */
    var rawFor by remember { mutableStateOf<ToolCallSegment?>(null) }
    var sourcesFor by remember { mutableStateOf<ToolCallSegment?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 每帧把「提问 + 回答」重绘进图层，分享时无需额外触发一次绘制
            .drawWithContent {
                shareLayer.record { this@drawWithContent.drawContent() }
                drawLayer(shareLayer)
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        turn.question?.let { q ->
            QuestionRow(
                message = q,
                isLatestTurn = isLatestTurn,
                onCopied = onCopied,
                onDeleteTurn = onDeleteTurn,
                onEditMessage = onEditMessage,
            )
        }
        turn.answer?.let { a ->
            AnswerRow(
                message = a,
                live = live,
                state = state,
                isLatestTurn = isLatestTurn,
                shareLayer = shareLayer,
                onRegenerate = onRegenerate,
                onDeleteAnswer = onDeleteAnswer,
                onContinue = onContinue,
                onRetry = onRetry,
                onRetryTool = onRetryTool,
                onOpenAuth = onOpenAuth,
                onApproveTool = onApproveTool,
                onDenyTool = onDenyTool,
                onCopied = onCopied,
                onSpeak = onSpeak,
                onShareImage = onShareImage,
                onFeedback = onFeedback,
                onOpenRaw = { rawFor = it },
                onOpenSources = { sourcesFor = it },
            )
        }
    }

    // ── 面板宿主：与「轮」同层，关掉即释放 ──
    rawFor?.let { seg ->
        RawOutputSheet(
            title = seg.displayName,
            raw = seg.result?.rawText.orEmpty(),
            onDismiss = { rawFor = null },
        )
    }
    sourcesFor?.let { seg ->
        SourcesSheet(
            sources = seg.result?.sources.orEmpty(),
            onOpen = onOpenSource,
            onDismiss = { sourcesFor = null },
        )
    }
}

@Composable
private fun QuestionRow(
    message: ChatMessage,
    isLatestTurn: Boolean,
    onCopied: () -> Unit,
    onDeleteTurn: (String) -> Unit,
    onEditMessage: (ChatMessage) -> Unit,
) {
    UserBubble(
        text = message.content,
        imageUris = message.imageUris,
        attachments = message.attachments,
        onCopy = { onCopied() },
        onDeleteTurn = { onDeleteTurn(message.id) },
        onEdit = { onEditMessage(message) },
        enableEdit = isLatestTurn,
    )
}

@Composable
private fun AnswerRow(
    message: ChatMessage,
    live: List<Segment>?,
    state: AgentChatState,
    isLatestTurn: Boolean,
    shareLayer: androidx.compose.ui.graphics.layer.GraphicsLayer,
    onOpenRaw: (ToolCallSegment) -> Unit,
    onOpenSources: (ToolCallSegment) -> Unit,
    onRegenerate: () -> Unit,
    onDeleteAnswer: (String) -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onRetryTool: (messageId: String, round: Int, seq: Int, toolName: String, argsJson: String?) -> String?,
    onOpenAuth: () -> Unit,
    onApproveTool: (round: Int, seq: Int) -> Unit,
    onDenyTool: (round: Int, seq: Int) -> Unit,
    onCopied: () -> Unit,
    onSpeak: () -> Unit,
    onShareImage: (androidx.compose.ui.graphics.layer.GraphicsLayer) -> Unit,
    onFeedback: (String) -> Unit,
) {
    // 正在流的这一轮用内存时间线（每帧在变），已完成的轮次用落盘快照（稳定）
    val segments = live ?: remember(message.segmentsJson) {
        AgentSegmentHolder.decode(message.segmentsJson)
    }

    // 请求中途死掉、什么也没产出的轮次：只留一张恢复卡，不要一个空气泡
    val hasAnything = message.content.isNotBlank() ||
        segments.any { seg ->
            when (seg) {
                is ThinkingSegment -> seg.text.isNotBlank()
                is AnswerSegment -> seg.markdown.isNotBlank()
                is ToolCallSegment -> true
                is NarrationSegment -> seg.text.isNotBlank()
                is SystemSegment -> true
            }
        }
    if (!hasAnything && live == null) {
        SystemCard(
            segment = com.mcp.toolbox.feature.home.agent.SystemSegment(
                id = "checkpoint-${message.id}",
                createdAt = message.time,
                kind = com.mcp.toolbox.feature.home.agent.SystemKind.Checkpoint,
                preservedChars = 0,
            ),
            onRetry = onRetry,
            onContinue = onContinue,
            onRegenerate = onRegenerate,
            onDelete = { onDeleteAnswer(message.id) },
            onOpenAuth = onOpenAuth,
        )
        return
    }

    // 工具重试：把「哪条消息、哪个工具」绑好交给宿主。
    // 同一张卡要同时服务「当前轮」与「历史轮」，判断留给宿主（它知道当前轮是谁）。
    val retryTool: (ToolCallSegment) -> Unit = { seg ->
        onRetryTool(message.id, seg.round, seg.seq, seg.toolName, seg.argsJson)
    }

    val rows = remember(segments) { groupSegments(segments) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            when (row) {
                is RowItem.Single -> SegmentView(
                    segment = row.segment,
                    live = live != null,
                    isLatestTurn = isLatestTurn,
                    state = state,
                    onRegenerate = onRegenerate,
                    onDeleteAnswer = onDeleteAnswer,
                    onContinue = onContinue,
                    onRetry = onRetry,
                    onRetryTool = retryTool,
                    onOpenAuth = onOpenAuth,
                    onApproveTool = onApproveTool,
                    onDenyTool = onDenyTool,
                    onCopied = onCopied,
                    onSpeak = onSpeak,
                    onShareImage = onShareImage,
                    onFeedback = onFeedback,
                    shareLayer = shareLayer,
                    onOpenRaw = onOpenRaw,
                    onOpenSources = onOpenSources,
                )

                is RowItem.Group -> {
                    val groupExpanded = state.isGroupExpanded(row.id, row.autoExpanded)
                    ProcessGroupCard(
                        groupId = row.id,
                        children = row.children,
                        expanded = groupExpanded,
                        onToggle = { state.toggleGroup(row.id, groupExpanded) },
                        renderChild = { child ->
                            SegmentView(
                                segment = child,
                                live = live != null,
                                isLatestTurn = isLatestTurn,
                                state = state,
                                onRegenerate = onRegenerate,
                                onDeleteAnswer = onDeleteAnswer,
                                onContinue = onContinue,
                                onRetry = onRetry,
                                onRetryTool = retryTool,
                                onOpenAuth = onOpenAuth,
                                onApproveTool = onApproveTool,
                                onDenyTool = onDenyTool,
                                onCopied = onCopied,
                                onSpeak = onSpeak,
                                onShareImage = onShareImage,
                                onFeedback = onFeedback,
                                shareLayer = shareLayer,
                                onOpenRaw = onOpenRaw,
                                onOpenSources = onOpenSources,
                                insideGroup = true,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SegmentView(
    segment: Segment,
    live: Boolean,
    state: AgentChatState,
    onRegenerate: () -> Unit,
    onDeleteAnswer: (String) -> Unit,
    onContinue: () -> Unit,
    onRetry: () -> Unit,
    onOpenAuth: () -> Unit,
    onApproveTool: (round: Int, seq: Int) -> Unit,
    onDenyTool: (round: Int, seq: Int) -> Unit,
    onCopied: () -> Unit,
    /** 工具卡的「重试」：只重跑该工具。 */
    onRetryTool: (ToolCallSegment) -> Unit = {},
    isLatestTurn: Boolean = true,
    onSpeak: () -> Unit = {},
    onShareImage: (androidx.compose.ui.graphics.layer.GraphicsLayer) -> Unit = {},
    onFeedback: (String) -> Unit = {},
    /** 本轮（提问+回答）的捕获图层，供「分享为图片」。 */
    shareLayer: androidx.compose.ui.graphics.layer.GraphicsLayer? = null,
    /** 请求查看某个工具的完整原始输出。 */
    onOpenRaw: (ToolCallSegment) -> Unit = {},
    /** 请求查看某个工具的来源清单。 */
    onOpenSources: (ToolCallSegment) -> Unit = {},
    /** 位于过程组内部：思考块只渲染正文摘要，折叠交给组头。 */
    insideGroup: Boolean = false,
) {
    when (segment) {
        is ThinkingSegment -> if (insideGroup) {
            ThinkingInline(
                segment = segment.copy(
                    isExpanded = state.isBlockExpanded(segment.id, segment.isExpanded),
                ),
                onToggle = {
                    state.toggleBlockExpanded(
                        segmentId = segment.id,
                        currentlyExpanded = state.isBlockExpanded(segment.id, segment.isExpanded),
                    )
                },
            )
        } else {
            ThinkingBlock(
                segment = segment.copy(
                    isExpanded = state.isBlockExpanded(segment.id, segment.isExpanded),
                ),
                onToggle = {
                    state.toggleBlockExpanded(
                        segmentId = segment.id,
                        currentlyExpanded = state.isBlockExpanded(segment.id, segment.isExpanded),
                    )
                },
            )
        }

        is ToolCallSegment -> ToolCard(
            segment = segment.copy(
                isDetailExpanded = state.isBlockExpanded(segment.id, segment.isDetailExpanded),
            ),
            onOpenRaw = { onOpenRaw(segment) },
            onOpenSources = { onOpenSources(segment) },
            onToggleDetail = {
                state.toggleBlockExpanded(
                    segmentId = segment.id,
                    currentlyExpanded = state.isBlockExpanded(segment.id, segment.isDetailExpanded),
                )
            },
            onApprove = { onApproveTool(segment.round, segment.seq) },
            onDeny = { onDenyTool(segment.round, segment.seq) },
            // 只重跑这一个工具。此前接的是 onRetry（整轮重跑），
            // 点一下就把前面的工具卡全清掉、再从头答一遍（用户实测反馈）。
            onRetry = { onRetryTool(segment) },
        )

        is NarrationSegment -> NarrationLine(text = segment.text)

        is AnswerSegment -> {
            // 只有「当前被 chunker 跟踪的那一段」能用增量缓存；
            // 其它正文段（工具打断后续写、历史轮次）自己全量解析。
            val usesCache = live && state.streamingAnswerId == segment.id
            val blocks = if (usesCache) {
                // 已闭合块（永不重排）+ 尾块（每次重解析）。
                // 两者内容**不重叠**：chunker 的闭合点就是已解析区与尾块的分界。
                remember(state.closedBlocks, state.streamTail) {
                    state.closedBlocks +
                        com.mcp.toolbox.core.design.component.parseMarkdown(state.streamTail)
                }
            } else {
                remember(segment.markdown) {
                    com.mcp.toolbox.core.design.component.parseMarkdown(segment.markdown)
                }
            }
            AnswerBlock(
                blocks = blocks,
                streaming = segment.isStreaming,
                text = segment.markdown,
                pulseKey = segment.markdown.length,
                onCopy = onCopied,
                onRegenerate = onRegenerate,
                onDelete = { onDeleteAnswer(segment.id) },
                // 「重新生成」是否露出，见 AnswerBlock 的 isLatestTurn 参数
                isLatestTurn = isLatestTurn,
                onSpeak = onSpeak,
                onShareImage = onShareImage,
                onFeedback = onFeedback,
                shareLayer = shareLayer,
            )
        }

        is SystemSegment -> SystemCard(
            segment = segment,
            onRetry = onRetry,
            onContinue = onContinue,
            onRegenerate = onRegenerate,
            onDelete = { onDeleteAnswer(segment.id) },
            onOpenAuth = onOpenAuth,
        )
    }
}

/** 落盘时间线的解码缓存：按 JSON 文本记忆，避免每次重组都解析一遍。 */
private object AgentSegmentHolder {
    fun decode(json: String?): List<Segment> =
        com.mcp.toolbox.feature.home.agent.AgentSegmentCodec.decode(json)
}

// ---------------------------------------------------------------------------
// 用户气泡：文字 + 图片 + 附件
// ---------------------------------------------------------------------------

/**
 * 用户消息气泡。
 *
 * - Squircle 20dp，**右下角 6dp 收口**，与 AI 侧形成方向差异
 * - 多行（> 5 行）切成「宽卡片」：左侧不再留呼吸位、四角回正，避免细长条
 * - 图片画真实缩略图；文件 / 文件夹 / 路径画成 chip
 */
@Composable
fun UserBubble(
    text: String,
    imageUris: List<String> = emptyList(),
    attachments: List<AttachmentRef> = emptyList(),
    onCopy: () -> Unit = {},
    onDeleteTurn: () -> Unit = {},
    onEdit: () -> Unit = {},
    /** 只有最新一轮允许编辑/删除这一轮（否则会作废后续话题）。 */
    enableEdit: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    /** 长按菜单的开合；同时用它当锚点，让菜单贴在这条气泡上。 */
    var menuOpen by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val press = com.mcp.toolbox.core.design.component.rememberMiuixPressState()
    val multiline = text.lineSequence().count() > 5
    /**
     * 右侧呼吸位。
     *
     * 早先留 56dp（照搬「不放头像、留白呼吸」的稿子），在手机宽度下把气泡
     * 整体推得太靠左，读起来像贴在屏幕左半边（用户实测反馈）。
     * 现在只留一小段，让气泡真正靠在右侧、只与屏幕边缘保持安全距离；
     * 多行时连这一小段也省掉，因为此时气泡本身就是「宽卡片」形态。
     */
    val reservedRight = if (multiline) 0.dp else 12.dp
    val tail = if (multiline) SquircleRadii.bubble else SquircleRadii.bubbleTail

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier
                // 长按弹菜单；用 onGloballyPositioned 记下自身矩形，菜单才会贴在这条气泡上
                .onGloballyPositioned { anchor = it.boundsInWindow() }
                .miuixClickable(press, true, onLongClick = { menuOpen = true }, onClick = {})
                .widthIn(min = 64.dp, max = BUBBLE_MAX_WIDTH)
                .sqSurface(
                    color = colors.primaryContainer,
                    topStart = SquircleRadii.bubble,
                    topEnd = SquircleRadii.bubble,
                    bottomEnd = tail,
                    bottomStart = SquircleRadii.bubble,
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (text.isNotBlank()) {
                MiuixText(
                    text = text,
                    style = MiuixTheme.typography.bodyLarge,
                    /**
                     * 用 `onPrimary` 而不是 `onPrimaryContainer`。
                     *
                     * 气泡底色是 `primaryContainer`，名义上的配对前景色是
                     * `onPrimaryContainer`；但在本工程的 Monet 派生方案里，
                     * 浅色模式下它是「深蓝底 + 深蓝字」这一对，
                     * 明度差只有 20 上下，字看起来发灰、读起来费力
                     * （用户实测反馈「问题的字有点不白」）。
                     * `onPrimary` 是同一色系里明度拉开最大的一档，在
                     * `primaryContainer` 底上对比度反而足够。
                     */
                    color = colors.onPrimary,
                )
            }
            if (imageUris.isNotEmpty()) {
                UserImageRow(imageUris)
            }
            if (attachments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    attachments.forEach { UserAttachmentChip(it) }
                }
            }
        }
        if (reservedRight > 0.dp) Spacer(Modifier.width(reservedRight))
    }

    // 长按菜单：复制 / 编辑并重发 / 删除这一轮
    com.mcp.toolbox.core.design.component.MiuixOverflowMenu(
        expanded = menuOpen,
        onDismiss = { menuOpen = false },
        anchor = anchor,
        focusable = false,
    ) {
        com.mcp.toolbox.core.design.component.MiuixMenuItem(
            text = "复制",
            iconRes = AgentIcons.ic_copy,
            onClick = { menuOpen = false; onCopy() },
        )
        com.mcp.toolbox.core.design.component.MiuixMenuItem(
            text = "编辑",
            iconRes = AgentIcons.ic_edit,
            /**
             * 只有最新一轮可编辑。
             *
             * 「编辑」的语义是「改完重发」，而重发会作废这条提问之后的所有消息。
             * 对历史轮次这么做，等于把后面聊过的内容一起抹掉——那不是用户
             * 点「编辑」时的预期，所以这里置灰。
             *
             * 删除**不置灰**：删中间一轮确实是用户会主动做的事
             * （比如某一轮不想要了），限制它反而是越权替他做决定。
             */
            enabled = enableEdit,
            onClick = { menuOpen = false; onEdit() },
        )
        com.mcp.toolbox.core.design.component.MiuixMenuDivider()
        com.mcp.toolbox.core.design.component.MiuixMenuItem(
            text = "删除这一轮",
            iconRes = AgentIcons.ic_trash,
            danger = true,
            onClick = { menuOpen = false; confirmDelete = true },
        )
    }

    // 删除是破坏性操作，且会连带过程与回答，必须二次确认
    com.mcp.toolbox.core.design.component.MiuixDialog(
        visible = confirmDelete,
        onDismiss = { confirmDelete = false },
        title = "删除这一轮对话？",
        message = "这条提问以及它下面的思考过程、工具调用和回答都会一起删除，无法撤销。",
        confirmText = "删除",
        destructive = true,
        onConfirm = { confirmDelete = false; onDeleteTurn() },
    )
}

/**
 * 图片缩略图行。
 *
 * 解码走 `BitmapFactory` 按目标边长降采样：相册原图动辄几千像素，
 * 直接读进来一张就够几十 MB 堆。
 */
@Composable
private fun UserImageRow(imageUris: List<String>) {
    val context = LocalContext.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        imageUris.forEach { uri ->
            val bitmap by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(
                initialValue = null,
                uri,
            ) {
                value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching {
                        val parsed = android.net.Uri.parse(uri)
                        val bounds = android.graphics.BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        context.contentResolver.openInputStream(parsed)?.use {
                            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                        }
                        var sample = 1
                        while (bounds.outWidth / (sample * 2) >= THUMB_PX &&
                            bounds.outHeight / (sample * 2) >= THUMB_PX
                        ) {
                            sample *= 2
                        }
                        context.contentResolver.openInputStream(parsed)?.use {
                            android.graphics.BitmapFactory.decodeStream(
                                it,
                                null,
                                android.graphics.BitmapFactory.Options().apply { inSampleSize = sample },
                            )
                        }
                    }.getOrNull()
                }
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .sqSurface(MiuixTheme.colors.onPrimaryContainer.copy(alpha = 0.08f), SquircleRadii.thumbnail),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(
                            androidx.compose.foundation.shape.RoundedCornerShape(SquircleRadii.thumbnail),
                        ),
                    )
                } else {
                    // URI 可能已失效（进程死亡后 content URI 会过期）：退回一枚占位图标，
                    // 而不是留个空洞
                    MiuixIconRes(
                        iconRes = AgentIcons.ic_image,
                        contentDescription = null,
                        tint = MiuixTheme.colors.onPrimaryContainer.copy(alpha = 0.5f),
                        size = 24.dp,
                    )
                }
            }
        }
    }
}

private const val THUMB_PX = 144

@Composable
private fun UserAttachmentChip(attachment: AttachmentRef) {
    val colors = MiuixTheme.colors
    Row(
        modifier = Modifier
            .widthIn(max = 280.dp)
            .sqSurface(colors.onPrimaryContainer.copy(alpha = 0.08f), SquircleRadii.bubbleTail)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MiuixIconRes(
            iconRes = when (attachment.kind) {
                AttachmentRef.Kind.FILE -> AgentIcons.ic_file
                AttachmentRef.Kind.FOLDER -> AgentIcons.ic_folder
                AttachmentRef.Kind.PATH -> AgentIcons.ic_link
                AttachmentRef.Kind.IMAGE -> AgentIcons.ic_image
            },
            contentDescription = null,
            tint = colors.onPrimaryContainer.copy(alpha = 0.8f),
            size = 14.dp,
        )
        MiuixText(
            text = attachment.label,
            style = MiuixTheme.typography.labelMedium,
            color = colors.onPrimaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 工具调用那一轮里模型的过渡旁白。视觉上比正文弱一档。 */
@Composable
fun NarrationLine(text: String, modifier: Modifier = Modifier) {
    MiuixText(
        text = text,
        style = MiuixTheme.typography.bodyMedium,
        color = MiuixTheme.colors.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}

/** 「回到底部」胶囊。 */
@Composable
fun JumpToBottomPill(
    hasNewContent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val press = com.mcp.toolbox.core.design.component.rememberMiuixPressState()
    Row(
        modifier = modifier
            .height(36.dp)
            .sqSurface(colors.surfaceContainerHighest, SquircleRadii.card)
            .sqBorder(1.dp, colors.outlineVariant, SquircleRadii.card)
            .miuixClickable(press, true, onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        MiuixIconRes(AgentIcons.ic_arrow_down, null, tint = colors.primary, size = 16.dp)
        MiuixText(
            text = if (hasNewContent) "有新内容" else "回到底部",
            style = MiuixTheme.typography.labelMedium,
            color = colors.primary,
        )
    }
}

// ---------------------------------------------------------------------------
// 过程分组
// ---------------------------------------------------------------------------

/** 渲染行：要么是单个块，要么是一个过程组。 */
private sealed interface RowItem {
    val id: String

    data class Single(val segment: Segment) : RowItem {
        override val id: String get() = segment.id
    }

    data class Group(
        override val id: String,
        val children: List<Segment>,
        /**
         * 自动展开判据：只要组内**还有在跑的块**就展开。
         *
         * 这不是持久状态——用户一旦手动点过，就以用户的选择为准，
         * 所以它只作为「用户没表过态时」的默认值传给 `isGroupExpanded`。
         */
        val autoExpanded: Boolean,
    ) : RowItem
}

/**
 * 把时间线切成「单块」与「过程组」。
 *
 * 连续的 [ThinkingSegment] / [ToolCallSegment] 合并成一个 [RowItem.Group]；
 * 旁白、正文、系统卡各自成为 [RowItem.Single]——它们是「说给用户听的话」，
 * 不属于「做了什么」，混进组里会让组头的计数失去意义。
 *
 * 组 id 取首尾两块的 id：同一段过程在重组之间保持稳定，
 * 组头只在内容真的变了时才重算。
 */
private fun groupSegments(segments: List<Segment>): List<RowItem> {
    val out = mutableListOf<RowItem>()
    var buffer = mutableListOf<Segment>()

    fun flush() {
        if (buffer.isEmpty()) return
        val children = buffer.toList()
        out += RowItem.Group(
            id = "grp-${children.first().id}-${children.last().id}",
            children = children,
            autoExpanded = children.any { seg ->
                (seg is ThinkingSegment && seg.isRunning) ||
                    (seg is ToolCallSegment && seg.isRunning)
            },
        )
        buffer = mutableListOf()
    }

    segments.forEach { seg ->
        when (seg) {
            is ThinkingSegment, is ToolCallSegment -> buffer += seg
            else -> {
                flush()
                out += RowItem.Single(seg)
            }
        }
    }
    flush()
    return out
}

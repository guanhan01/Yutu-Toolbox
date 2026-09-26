package com.mcp.toolbox.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import com.mcp.toolbox.R
import com.mcp.toolbox.feature.capture.CaptureStore
import com.mcp.toolbox.feature.home.ChatSession
import com.mcp.toolbox.feature.home.ChatStore
import com.mcp.toolbox.core.design.component.MiuixBadge
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixDialog
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.MiuixDivider
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.navigation.Destination
import com.mcp.toolbox.navigation.DrawerFooter
import com.mcp.toolbox.navigation.DrawerPrimary
import com.mcp.toolbox.navigation.Routes
import kotlinx.coroutines.launch

/**
 * 汉堡抽屉内容：「应用工具」分组 + 可折叠「网络」二级菜单 + 条目 badge。
 * 底部为横向纯图标入口（设置 / 关于）。
 */
@Composable
fun DrawerContent(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    var chatExpanded by remember { mutableStateOf(currentRoute == Routes.HOME) }
    val allSessions by ChatStore.sessions.collectAsState()
    // 只列有消息的会话：空白会话没有内容，也就没有可展示的标题，
    // 露在列表里只会变成一行行同名空壳（新建后看着像没生效）。
    val chatSessions = remember(allSessions) { allSessions.filter { it.messages.isNotEmpty() } }
    val currentChatId by ChatStore.currentId.collectAsState()
    val scope = rememberCoroutineScope()
    // 正在重命名的会话；非空即弹对话框
    var renamingSession by remember { mutableStateOf<ChatSession?>(null) }
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxSize()
            // 抽屉靠左，只圆右侧两角：既成「卡片式抽屉」，也不会在屏幕边缘留缺口
            .clip(RoundedCornerShape(topEnd = MiuixTheme.radius.dialog, bottomEnd = MiuixTheme.radius.dialog))
            .background(colors.surfaceContainerLow)
            .statusBarsPadding(),
    ) {
        // 顶部：左侧 Yutu 标题，右侧关闭按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.lg, end = spacing.md, top = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixText(
                text = "Yutu",
                style = MiuixTheme.typography.titleMedium,
            )
            Spacer(Modifier.weight(1f))
            MiuixIconButton(
                onClick = onClose,
                icon = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.app_drawer_close),
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(vertical = spacing.xs),
        ) {
            // 记忆入口固定在「对话」上方：它是对话的长期配套，不属于工具集
            DrawerItem(
                destination = Destination(
                    route = Routes.MEMORY,
                    labelRes = R.string.app_nav_memory,
                    icon = Icons.Outlined.Psychology,
                ),
                selected = currentRoute == Routes.MEMORY,
                onClick = { onNavigate(Routes.MEMORY) },
            )

            // 主列表只保留「首页」，工具统一收进底栏的「应用工具」子页面
            DrawerPrimary.forEach { destination ->
                val isChat = destination.route == Routes.HOME
                DrawerItem(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    onClick = {
                        // 点行本身：进入该页面（抽屉会收起，这是预期行为）。
                        //
                        // 不能再在这里翻转展开状态：那样「点行」与「点箭头」都会切换，
                        // 用户点箭头时会被这里再翻一次，看起来像没反应。
                        onNavigate(destination.route)
                    },
                    expandable = isChat,
                    expanded = chatExpanded,
                    // 只切换展开，不导航、不关抽屉
                    onToggleExpand = { if (isChat) chatExpanded = !chatExpanded },
                )
                if (isChat) {
                    AnimatedVisibility(
                        visible = chatExpanded,
                        enter = expandVertically(tween(200)),
                        exit = shrinkVertically(tween(200)),
                    ) {
                        Column {
                            chatSessions.forEach { item ->
                                val context = LocalContext.current
                                ChatHistoryRow(
                                    title = item.displayTitle,
                                    selected = item.id == currentChatId,
                                    onClick = {
                                        scope.launch { ChatStore.select(item.id) }
                                        // 刻意不调 onClose()：切对话属于「浏览」，抽屉留着
                                        // 才能连着看好几条；收起交给点遮罩或点开关闭按钮。
                                    },
                                    onRename = { renamingSession = item },
                                    onDelete = {
                                        scope.launch { ChatStore.delete(context, item.id) }
                                    },
                                )
                            }
                        }
                    }
                }

            }
        }

        MiuixDivider()
        // 底栏：横向排布，只留图标
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DrawerFooter.forEach { destination ->
                DrawerFooterIcon(
                    destination = destination,
                    selected = currentRoute == destination.route,
                    onClick = { onNavigate(destination.route) },
                )
            }
        }
    }

    // 重命名对话框
    renamingSession?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.displayTitle) }
        MiuixDialog(
            visible = true,
            onDismiss = { renamingSession = null },
            title = stringResource(R.string.chat_history_rename),
            confirmText = stringResource(R.string.app_action_save),
            onConfirm = {
                val name = text
                scope.launch { ChatStore.rename(context, target.id, name) }
            },
        ) {
            MiuixTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = stringResource(R.string.chat_history_rename_hint),
                singleLine = true,
            )
        }
    }
}

/** 抽屉底部的语言入口：显示当前选择，点开三选一。 */
@Composable
private fun DrawerItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
    indented: Boolean = false,
    expandable: Boolean = false,
    expanded: Boolean = false,
    /**
     * 「展开 / 收起」与「进入该页面」是两个不同的动作。
     *
     * 之前箭头就是整行的一部分，点它等于点整行，于是展开子列表的同时把抽屉也关了。
     * 现在把箭头单独接出来：点箭头只切换展开，点行的其它地方才导航。
     */
    onToggleExpand: (() -> Unit)? = null,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val press = rememberMiuixPressState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.md, vertical = 2.dp)
            .clip(RoundedCornerShape(MiuixTheme.radius.inner))
            .background(if (selected) colors.secondaryContainer else colors.surfaceContainerLow)
            .miuixClickable(press, true, onClick = onClick)
            .padding(start = if (indented) 40.dp else 14.dp, end = 12.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        MiuixIcon(
            icon = destination.icon,
            contentDescription = stringResource(destination.labelRes),
            tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
            size = 20.dp,
        )
        MiuixText(
            text = stringResource(destination.labelRes),
            style = MiuixTheme.typography.bodyLarge,
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
        )
        val captureState by CaptureStore.state.collectAsState()
        val badgeCount = if (destination.route == Routes.CAPTURE) {
            if (captureState.running) captureState.records.size else 0
        } else {
            destination.badge
        }
        if (badgeCount > 0) {
            MiuixBadge(count = badgeCount)
        }
        if (expandable) {
            val arrowPress = rememberMiuixPressState()
            val arrowDesc = stringResource(
                if (expanded) R.string.app_drawer_collapse else R.string.app_drawer_expand,
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(MiuixTheme.radius.inner))
                    .miuixClickable(
                        arrowPress,
                        true,
                        onClick = { onToggleExpand?.invoke() },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                MiuixIcon(
                    icon = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = arrowDesc,
                    tint = colors.onSurfaceVariant,
                    size = 18.dp,
                )
            }
        }
    }
}

/** 抽屉底栏入口：纯图标，无文字。 */
@Composable
private fun DrawerFooterIcon(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (selected) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
            .miuixClickable(press, true, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        MiuixIcon(
            destination.icon,
            stringResource(destination.labelRes),
            tint = if (selected) colors.primary else colors.onSurfaceVariant,
            size = 24.dp,
        )
    }
}

/**
 * 抽屉里的一条历史对话。
 *
 * 编辑与删除放在**长按弹出的溢出菜单**里：这两个动作低频且不可逆，
 * 常驻行尾会让一整列条目看着都是按钮，也容易误触删除。
 *
 * 点击整行 = 切换到该对话（由调用方决定是否收起抽屉）。
 */
@Composable
private fun ChatHistoryRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit = {},
    onDelete: () -> Unit = {},
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val press = rememberMiuixPressState()
    var showMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.xxl, end = spacing.md, top = 1.dp, bottom = 1.dp)
                .clip(RoundedCornerShape(MiuixTheme.radius.inner))
                .background(if (selected) colors.primary.copy(alpha = 0.12f) else Color.Transparent)
                .miuixClickable(press, true, onLongClick = { showMenu = true }, onClick = onClick)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixText(
                text = title,
                style = MiuixTheme.typography.bodyMedium,
                color = if (selected) colors.primary else colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showMenu) {
            MiuixOverflowMenu(
                expanded = showMenu,
                onDismiss = { showMenu = false },
                alignStart = true,
            ) {
                MiuixMenuItem(
                    text = stringResource(R.string.chat_history_rename),
                    icon = Icons.Outlined.DriveFileRenameOutline,
                    onClick = { showMenu = false; onRename() },
                )
                MiuixMenuItem(
                    text = stringResource(R.string.chat_history_delete),
                    icon = Icons.Outlined.DeleteOutline,
                    danger = true,
                    onClick = { showMenu = false; onDelete() },
                )
            }
        }
    }
}

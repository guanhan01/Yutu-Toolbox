package com.mcp.toolbox.ui.linux.term

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.icon.AgentIcons
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.MiuixTheme

/**
 * 终端窗口切换条。
 *
 * 为什么不做成「每个窗口一个导航页面」：窗口切换要能看到**其它窗口还活着**
 * （进程在跑、`top` 还在画），导航栈来回 pop/push 会连带销毁面板，而面板一销毁，
 * xterm.js 的屏幕缓冲就没了。这里只切换「显示哪个会话」，进程始终不动，
 * 内容靠 [TerminalSession.readSince] 重放补齐。
 *
 * 未读小圆点表示该窗口有新输出——跑 `apt upgrade` 这类长任务时，
 * 用户切到别的窗口也能看见它还在干活。
 *
 * 关闭入口是每个窗口右侧的 ×，由调用方弹确认框：× 就在标题旁边，
 * 误触代价是「一个正在跑编译的窗口连同它的进程一起没了」，
 * 所以不能点一下就关。
 */
@Composable
fun TerminalTabStrip(
    tabs: List<TermSessionStore.Tab>,
    currentId: String?,
    onSelect: (TermSessionStore.Tab) -> Unit,
    onRequestClose: (TermSessionStore.Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    var lastCount by remember { mutableIntStateOf(tabs.size) }

    // 新窗口追加在末尾，把它滚进视野，否则「点了加号好像没反应」
    LaunchedEffect(tabs.size) {
        if (tabs.size > lastCount) scroll.animateScrollTo(scroll.maxValue)
        lastCount = tabs.size
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = MiuixTheme.dimens.spacing.pageHorizontal)
            .padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            TabChip(
                tab = tab,
                selected = tab.id == currentId,
                onSelect = { onSelect(tab) },
                onClose = { onRequestClose(tab) },
            )
        }
    }
}

@Composable
private fun TabChip(
    tab: TermSessionStore.Tab,
    selected: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val press = rememberMiuixPressState()
    val title by tab.title
    val unseen by tab.unseen

    Box(
        modifier = Modifier
            .heightIn(min = 30.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(if (selected) colors.primaryContainer else colors.surfaceContainerHigh)
            .miuixClickable(press, true, onClick = onSelect)
            .padding(start = 12.dp, end = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MiuixText(
                text = title,
                style = MiuixTheme.typography.labelLarge,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                maxLines = 1,
            )
            if (unseen) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(colors.primary),
                )
            }
            // × 的触摸区比图标大一圈（22dp 对 14dp），手指点得中；
            // 它自带 clickable，点击就地消费，不会触发外层的「切换窗口」。
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                MiuixIcon(
                    AgentIcons.ic_close,
                    stringResource(R.string.linux_term_close_window),
                    tint = if (selected) {
                        colors.onPrimaryContainer.copy(alpha = 0.75f)
                    } else {
                        colors.onSurfaceVariant.copy(alpha = 0.75f)
                    },
                    size = 14.dp,
                )
            }
        }
    }
}

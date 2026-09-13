package com.mcp.toolbox.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.navigation.BottomDestinations
import com.mcp.toolbox.navigation.Destination

/** 底部导航：4 个入口，选中态用主色胶囊底 + 图标放大。 */
@Composable
fun MiuixBottomBar(
    currentRoute: String,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceContainer)
            .navigationBarsPadding()
            .height(64.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        BottomDestinations.forEach { destination ->
            BottomBarItem(
                destination = destination,
                selected = currentRoute == destination.route,
                onClick = { onNavigate(destination.route) },
            )
        }
    }
}

@Composable
private fun BottomBarItem(
    destination: Destination,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val motion = MiuixTheme.motion
    val press = rememberMiuixPressState()
    val container by animateColorAsState(
        targetValue = if (selected) colors.secondaryContainer else Color.Transparent,
        animationSpec = tween(motion.fast),
        label = "bottom-item-bg",
    )
    val tint by animateColorAsState(
        targetValue = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant,
        animationSpec = tween(motion.fast),
        label = "bottom-item-fg",
    )
    val scale by animateFloatAsState(if (selected) 1f else 0.96f, motion.gentle(), label = "bottom-item-scale")

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(MiuixTheme.radius.inner))
            .background(container)
            .miuixClickable(press, true, onClick = onClick)
            .scale(scale)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MiuixIcon(destination.icon, stringResource(destination.labelRes), tint = tint, size = 20.dp)
        Spacer(Modifier.height(2.dp))
        MiuixText(text = stringResource(destination.labelRes), style = MiuixTheme.typography.labelSmall, color = tint)
    }
}

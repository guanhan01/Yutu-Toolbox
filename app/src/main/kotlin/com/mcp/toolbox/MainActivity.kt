package com.mcp.toolbox

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mcp.toolbox.core.design.component.MiuixDialog
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixToastState
import com.mcp.toolbox.core.design.component.rememberMiuixToastState
import com.mcp.toolbox.core.design.theme.LocalThemeRevealTrigger
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.core.design.theme.ThemeRevealHost
import com.mcp.toolbox.core.design.theme.play
import com.mcp.toolbox.core.design.theme.rememberThemeRevealState
import com.mcp.toolbox.ui.AppShell

class MainActivity : ComponentActivity() {

    /** 应用内语言在这里生效：早于 onCreate，整棵资源树都会拿到新 locale。 */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val controller = (application as ToolboxApplication).themeController
        // 状态栏与导航栏图标默认按「浅色底」走深色图标。
        // 之前没设过 SystemBarStyle，系统一直用浅色（白）图标，
        // 于是白底页面上时间、电量几乎看不见（实测状态栏区域全白）。
        // 真实明暗在下面的 Compose 里按主题重新校准。
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
        )
        setContent {
            val config by controller.config.collectAsState()
            MiuixTheme(config = config) {
                // 状态栏图标必须与页面底色相反：浅底用深色图标，深底用浅色图标。
                // 这里以主题背景色的相对亮度为准（纯白模式与浅色调都是高亮度）。
                val bgLuminance = MiuixTheme.colors.background.luminance()
                val dark = bgLuminance < 0.5f
                SideEffect {
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) {
                            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                            )
                        },
                        navigationBarStyle = if (dark) {
                            SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        } else {
                            SystemBarStyle.light(
                                android.graphics.Color.TRANSPARENT,
                                android.graphics.Color.TRANSPARENT,
                            )
                        },
                    )
                }
                val toastState: MiuixToastState = rememberMiuixToastState()
                val revealState = rememberThemeRevealState()
                val background = MiuixTheme.colors.background
                var pendingReveal by remember { mutableStateOf<Offset?>(null) }

                // 主题切换后播放圆形揭示：扩散圆使用「新主题」的背景色，动画结束后自然露出新配色
                LaunchedEffect(pendingReveal, config) {
                    pendingReveal?.let { origin ->
                        revealState.play(origin, background)
                        pendingReveal = null
                    }
                }

                CompositionLocalProvider(
                    LocalThemeRevealTrigger provides { origin -> pendingReveal = origin },
                ) {
                    Box(Modifier.fillMaxSize()) {
                        ComplianceGate()
                        AppShell(
                            config = config,
                            onConfigChange = { transform -> controller.update(transform) },
                            toastState = toastState,
                        )
                        ThemeRevealHost(revealState)
                    }
                }
            }
        }
    }
}

private const val CompliancePrefs = "compliance"
private const val ComplianceKey = "accepted_v1"

/**
 * 首启合规弹窗（安全与合规章节要求）：抓包与反编译仅限自有或已授权应用的学习调试。
 * 首次确认后写入 SharedPreferences，不再打扰。
 */
@Composable
private fun ComplianceGate() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(CompliancePrefs, Context.MODE_PRIVATE) }
    var visible by remember { mutableStateOf(!prefs.getBoolean(ComplianceKey, false)) }

    MiuixDialog(
        visible = visible,
        onDismiss = {
            prefs.edit().putBoolean(ComplianceKey, true).apply()
            visible = false
        },
        title = stringResource(R.string.app_compliance_title),
        confirmText = stringResource(R.string.app_compliance_confirm),
        dismissText = null,
        message = stringResource(R.string.app_compliance_message),
    ) {
        MiuixText(text = stringResource(R.string.app_compliance_footer))
    }
}

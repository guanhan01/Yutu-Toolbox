package com.mcp.toolbox.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.mcp.toolbox.AppLanguage
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.component.MiuixDialog
import com.mcp.toolbox.core.design.component.MiuixMenuDivider
import com.mcp.toolbox.core.design.component.MiuixMenuGroupLabel
import com.mcp.toolbox.core.design.component.MiuixMenuItem

/** 从 Compose 的 Context 里解包出宿主 Activity，用于切换语言后重建界面。 */
private fun Context.findActivity(): Activity? {
    var cursor: Context = this
    while (cursor is ContextWrapper) {
        if (cursor is Activity) return cursor
        cursor = cursor.baseContext
    }
    return null
}

/** 语言名在中英界面里都用各自的原生写法，避免切错以后看不懂菜单。 */
@Composable
fun languageDisplayName(tag: String): String = when (tag) {
    AppLanguage.ZH -> stringResource(R.string.app_language_zh)
    AppLanguage.EN -> stringResource(R.string.app_language_en)
    else -> stringResource(R.string.app_language_system)
}

/** 写入选择并重建 Activity；重建时 attachBaseContext 会用新语言包一遍 Configuration。 */
@Composable
fun rememberLanguageSwitcher(): (String) -> Unit {
    val context = LocalContext.current
    return remember(context) {
        { tag ->
            AppLanguage.apply(context, tag)
            context.findActivity()?.recreate()
        }
    }
}

/** 溢出菜单里的语言分组：三选一，当前项打勾。 */
@Composable
fun LanguageMenuItems(onSelect: (String) -> Unit) {
    val context = LocalContext.current
    val current = remember(context) { AppLanguage.current(context) }
    MiuixMenuGroupLabel(text = stringResource(R.string.app_language))
    AppLanguage.options.forEach { tag ->
        MiuixMenuItem(
            text = languageDisplayName(tag),
            checked = tag == current,
            onClick = { onSelect(tag) },
        )
    }
}

/** 抽屉入口用的语言选择对话框：三选一，选中即切换。 */
@Composable
fun LanguageDialog(visible: Boolean, onDismiss: () -> Unit) {
    val switchTo = rememberLanguageSwitcher()
    MiuixDialog(
        visible = visible,
        onDismiss = onDismiss,
        title = stringResource(R.string.app_language),
        message = stringResource(R.string.app_language_recreate_hint),
        confirmText = stringResource(R.string.app_action_close),
        dismissText = null,
    ) {
        Column {
            val context = LocalContext.current
            val current = remember(context) { AppLanguage.current(context) }
            AppLanguage.options.forEach { tag ->
                MiuixMenuItem(
                    text = languageDisplayName(tag),
                    checked = tag == current,
                    onClick = { switchTo(tag) },
                )
            }
        }
    }
}

/** 菜单内分组之间的标准分隔线，供调用方少写一行 import。 */
@Composable
fun LanguageMenuTail() {
    MiuixMenuDivider()
}

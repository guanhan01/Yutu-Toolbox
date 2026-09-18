package com.mcp.toolbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.AppLanguage
import com.mcp.toolbox.R
import com.mcp.toolbox.core.design.component.MiuixCard
import com.mcp.toolbox.core.design.component.MiuixEmptyState
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSuperArrow
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.navigation.Destination
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import com.mcp.toolbox.BuildConfig
import com.mcp.toolbox.core.design.component.MiuixButton
import kotlinx.coroutines.launch

/** 未实现模块的统一占位页：明确写出所属阶段与降级说明，避免“看不懂的空页面”。 */
@Composable
fun FeaturePlaceholderScreen(destination: Destination, modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.lg))
        MiuixEmptyState(
            icon = Icons.Outlined.Construction,
            title = stringResource(R.string.app_placeholder_title, stringResource(destination.labelRes)),
            description = stringResource(R.string.app_placeholder_desc, destination.implStage),
        )
    }
}

/** 设置页总览：分组入口（主题与色彩、语言已实现，其余为后续阶段）。 */
@Composable
fun SettingsOverview(
    onOpenTheme: () -> Unit,
    onOpenPrivilege: () -> Unit,
    privilegeSummary: String,
    privilegeUsable: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    var languageDialog by remember { mutableStateOf(false) }
    val currentLanguage = AppLanguage.current(LocalContext.current)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))
        MiuixSectionCard(title = stringResource(R.string.app_settings_section_appearance)) {
            Column {
                MiuixSuperArrow(
                    title = stringResource(R.string.app_screen_theme),
                    subtitle = stringResource(R.string.app_settings_theme_subtitle),
                    leadingIcon = Icons.Outlined.Palette,
                    valueText = stringResource(R.string.app_settings_theme_value),
                    onClick = onOpenTheme,
                )
                MiuixSuperArrow(
                    title = stringResource(R.string.app_language),
                    subtitle = stringResource(R.string.app_settings_language_subtitle),
                    leadingIcon = Icons.Outlined.Language,
                    valueText = languageDisplayName(currentLanguage),
                    onClick = { languageDialog = true },
                )
            }
        }
        Spacer(Modifier.height(spacing.groupGap))
        MiuixSectionCard(title = stringResource(R.string.app_settings_section_capabilities)) {
            Column {
                MiuixSuperArrow(
                    title = stringResource(R.string.app_settings_root_title),
                    subtitle = privilegeSummary,
                    leadingIcon = Icons.Outlined.Security,
                    valueText = if (privilegeUsable) "已就绪" else "未就绪",
                    onClick = onOpenPrivilege,
                )
            }
        }
        Spacer(Modifier.height(spacing.groupGap))
        Spacer(Modifier.height(32.dp))
    }

    LanguageDialog(visible = languageDialog, onDismiss = { languageDialog = false })
}

/** 关于页：版本与更新检查、开源项目、合规声明。 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val currentVersion = remember { BuildConfig.VERSION_NAME }
    var updateState by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }

    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
    }

    fun checkUpdate() {
        scope.launch {
            updateState = UpdateState.Checking
            updateState = UpdateChecker.check(currentVersion)
        }
    }

    // 内测包默认关闭，不发起任何请求
    LaunchedEffect(Unit) {
        if (UpdateChecker.ENABLED) checkUpdate()
    }

    val state = updateState
    val statusText = when {
        !UpdateChecker.ENABLED ->
            stringResource(R.string.app_about_update_disabled)
        state is UpdateState.Idle || state is UpdateState.Checking ->
            stringResource(R.string.app_about_update_checking)
        state is UpdateState.UpToDate ->
            stringResource(R.string.app_about_update_uptodate)
        state is UpdateState.Available ->
            stringResource(R.string.app_about_update_available, state.latest)
        else ->
            stringResource(R.string.app_about_update_failed)
    }
    val available = state as? UpdateState.Available

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))
        MiuixSectionCard(
            title = stringResource(R.string.app_name),
            subtitle = stringResource(R.string.app_about_version, currentVersion),
        ) {
            Column(Modifier.padding(spacing.lg)) {
                MiuixText(
                    text = stringResource(R.string.app_about_desc),
                    style = MiuixTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                MiuixText(
                    text = stringResource(R.string.app_about_theme),
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(spacing.groupGap))

        // 版本更新：进页自动检查，点该行可手动重查；有新版本时给出下载入口
        MiuixSectionCard(title = stringResource(R.string.app_about_section_update)) {
            Column {
                MiuixListItem(
                    title = stringResource(R.string.app_about_update_check),
                    subtitle = statusText,
                    leadingIcon = Icons.Outlined.SystemUpdate,
                    onClick = if (UpdateChecker.ENABLED) ({ checkUpdate() }) else null,
                    showDivider = available != null,
                )
                if (available != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = spacing.lg, vertical = spacing.md),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        MiuixButton(
                            text = stringResource(R.string.app_about_update_go),
                            onClick = { openUrl(available.releaseUrl) },
                            leadingIcon = Icons.Outlined.Download,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(spacing.groupGap))

        // 开源项目
        MiuixSectionCard(title = stringResource(R.string.app_about_section_project)) {
            Column {
                MiuixListItem(
                    title = stringResource(R.string.app_about_project_title),
                    subtitle = UpdateChecker.PROJECT_URL.removePrefix("https://"),
                    leadingIcon = Icons.Outlined.Language,
                    onClick = { openUrl(UpdateChecker.PROJECT_URL) },
                    showDivider = true,
                )
                MiuixListItem(
                    title = stringResource(R.string.app_about_license_title),
                    subtitle = stringResource(R.string.app_about_license_subtitle),
                    leadingIcon = Icons.Outlined.Verified,
                    onClick = { openUrl(UpdateChecker.PROJECT_URL + "/blob/main/LICENSE") },
                )
            }
        }
        Spacer(Modifier.height(spacing.groupGap))

        MiuixSectionCard(title = stringResource(R.string.app_about_section_compliance)) {
            Column {
                MiuixListItem(
                    title = stringResource(R.string.app_about_compliance1_title),
                    subtitle = stringResource(R.string.app_about_compliance1_subtitle),
                    leadingIcon = Icons.Outlined.Security,
                    showDivider = true,
                )
                MiuixListItem(
                    title = stringResource(R.string.app_about_compliance2_title),
                    subtitle = stringResource(R.string.app_about_compliance2_subtitle),
                    leadingIcon = Icons.Outlined.Info,
                    showDivider = true,
                )
                MiuixListItem(
                    title = stringResource(R.string.app_about_compliance3_title),
                    subtitle = stringResource(R.string.app_about_compliance3_subtitle),
                    leadingIcon = Icons.Outlined.Security,
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

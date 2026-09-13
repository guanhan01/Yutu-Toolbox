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

/** 关于页：版本、开源组件与合规声明。 */
@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
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
            subtitle = stringResource(R.string.app_about_version),
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

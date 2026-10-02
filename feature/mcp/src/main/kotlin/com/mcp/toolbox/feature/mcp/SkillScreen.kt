package com.mcp.toolbox.feature.mcp

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixBottomSheet
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixCard
import com.mcp.toolbox.core.design.component.MiuixDialog
import com.mcp.toolbox.core.design.component.MiuixEmptyState
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixSegmentedButton
import com.mcp.toolbox.core.design.component.MiuixSwitch
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MarkdownText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.common.Formatters
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.launch

/**
 * Skill 工具箱。
 *
 * 空列表是正常初始状态——应用不内置任何 Skill，也不预置示例。
 *
 * @param onBack 二级页返回。
 * @param onToast 轻提示。
 * @param showTopBar 由宿主决定是否绘制顶栏；接在自绘外壳里时为 false。
 */
@Composable
fun SkillScreen(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
    showTopBar: Boolean = true,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var skills by remember { mutableStateOf(SkillStore.skills.value) }
    var showImport by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<Skill?>(null) }
    var renaming by remember { mutableStateOf<Skill?>(null) }
    var deleting by remember { mutableStateOf<Skill?>(null) }
    var exportTarget by remember { mutableStateOf<Skill?>(null) }

    // 长按唤出的溢出菜单：记住是哪一行、以及那一行在窗口里的位置。
    // 菜单要用窗口坐标定位，所以必须把行自身的矩形带上来，不能只记 skill。
    var menuSkill by remember { mutableStateOf<Skill?>(null) }
    var menuAnchor by remember { mutableStateOf<Rect?>(null) }
    // menuSkill 收起后仍留在原位，只由 menuOpen 控制显隐——这样组件不会被立刻
    // 移出组合，离场动画才有地方播。
    var menuOpen by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { skills = SkillStore.refresh(context) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching { SkillStore.export(context, target.id, uri) }
                .onSuccess { onToast("已导出 $it") }
                .onFailure { onToast("导出失败：${it.message ?: "未知错误"}") }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            if (showTopBar) {
                SkillTopBar(title = "Skill 工具箱", onBack = onBack)
            }
            if (skills.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MiuixEmptyState(
                        title = "还没有 Skill",
                        description = "点右下角「+」导入。支持文件、ZIP、文件夹、URL、JSON 与剪贴板。",
                        icon = Icons.Outlined.Extension,
                        action = {
                            MiuixButton(
                                text = "导入",
                                onClick = { showImport = true },
                                variant = MiuixButtonVariant.TONAL,
                                leadingIcon = Icons.Outlined.Add,
                            )
                        },
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = spacing.pageHorizontal,
                        end = spacing.pageHorizontal,
                        top = spacing.sm,
                        // 留出右下角 FAB 的高度，否则最后一项会被它压住
                        bottom = 120.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(spacing.groupGap),
                ) {
                    items(skills, key = { it.id }) { skill ->
                        SkillRow(
                            skill = skill,
                            onClick = { detail = skill },
                            onLongPress = { bounds ->
                                menuAnchor = bounds
                                menuSkill = skill
                                menuOpen = true
                            },
                            onToggle = { checked ->
                                scope.launch {
                                    SkillStore.setEnabled(context, skill.id, checked)
                                    skills = SkillStore.skills.value
                                }
                            },
                        )
                    }
                }
            }
        }

        // 导入入口：右下角圆形加号（自绘）。
        //
        // 刻意不使用 MiuixFab：那是个「Box + clip + background + 自定义点击」的组合，
        // 颜色还要经过主题插值链路，前几轮反复出现「圆看不见」却查不出所以然。
        // 这里把尺寸、形状、颜色、点击全部摊平在一个 Box 上，行为完全可预期：
        //   CircleShape 明确指定圆形（不用 percent=50 的近似写法）
        //   颜色取 colors.primary（实测 #904A49，与背景 #FFF8F7 对比充分）
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = spacing.pageHorizontal, bottom = 88.dp)
                .size(56.dp)
                .clip(CircleShape)
                .background(colors.primary)
                .clickable(onClick = { showImport = true })
                .semantics { contentDescription = "导入 Skill" },
            contentAlignment = Alignment.Center,
        ) {
            MiuixIcon(Icons.Outlined.Add, null, tint = colors.onPrimary, size = 26.dp)
        }

        if (busy) {
            Box(
                Modifier.fillMaxSize().background(colors.background.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                com.mcp.toolbox.core.design.component.MiuixInfiniteProgress()
            }
        }
    }

    // 长按某一行的溢出菜单。菜单挂在页面顶层并按行的矩形定位，
    // 不使用 offset/父布局定位——PopScope 是全屏的，那样算出来的位置是错的。
    menuSkill?.let { current ->
        MiuixOverflowMenu(
            expanded = menuOpen,
            onDismiss = { menuOpen = false },
            anchor = menuAnchor,
        ) {
            // 文案直接说「点了会发生什么」，比用对勾表达当前状态更不容易误读
            MiuixMenuItem(
                text = if (current.enabled) "停用" else "启用",
                onClick = {
                    menuSkill = null
                    scope.launch {
                        SkillStore.setEnabled(context, current.id, !current.enabled)
                        skills = SkillStore.skills.value
                        onToast(if (current.enabled) "已停用 ${current.name}" else "已启用 ${current.name}")
                    }
                },
            )
            MiuixMenuItem(
                text = "查看详情",
                onClick = {
                    detail = current
                    menuSkill = null
                },
                icon = Icons.Outlined.Info,
            )
            MiuixMenuItem(
                text = "重命名",
                onClick = {
                    renaming = current
                    menuSkill = null
                },
                icon = Icons.Outlined.DriveFileRenameOutline,
            )
            MiuixMenuItem(
                text = "导出为 ZIP",
                onClick = {
                    exportTarget = current
                    menuSkill = null
                    exportLauncher.launch(null)
                },
                icon = Icons.Outlined.Upload,
            )
            MiuixMenuItem(
                text = "删除",
                onClick = {
                    deleting = current
                    menuSkill = null
                },
                icon = Icons.Outlined.DeleteOutline,
                danger = true,
            )
        }
    }

    if (showImport) {
        SkillImportSheet(
            onDismiss = { showImport = false },
            onBusy = { busy = it },
            onToast = onToast,
            onDone = {
                scope.launch { skills = SkillStore.refresh(context) }
            },
        )
    }

    detail?.let { skill ->
        SkillDetailSheet(
            skill = skill,
            onDismiss = { detail = null },
            onRename = {
                detail = null
                renaming = skill
            },
            onExport = {
                detail = null
                exportTarget = skill
                exportLauncher.launch(null)
            },
            onExportJson = {
                scope.launch {
                    runCatching { SkillStore.exportJson(context, skill.id) }
                        .onSuccess { json ->
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(android.content.ClipData.newPlainText("skill", json))
                            onToast("已复制 JSON 到剪贴板")
                            detail = null
                        }
                        .onFailure { onToast("导出失败：${it.message ?: "未知错误"}") }
                }
            },
            onDelete = {
                detail = null
                deleting = skill
            },
        )
    }

    renaming?.let { skill ->
        var text by remember(skill.id) { mutableStateOf(skill.name) }
        MiuixDialog(
            visible = true,
            onDismiss = { renaming = null },
            title = "重命名",
            confirmText = "保存",
            onConfirm = {
                scope.launch {
                    runCatching { SkillStore.rename(context, skill.id, text) }
                        .onSuccess {
                            onToast("已重命名")
                            skills = SkillStore.skills.value
                        }
                        .onFailure { onToast(it.message ?: "重命名失败") }
                }
            },
        ) {
            MiuixTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Skill 名称",
            )
        }
    }

    deleting?.let { skill ->
        MiuixDialog(
            visible = true,
            onDismiss = { deleting = null },
            title = "删除「${skill.name}」",
            message = "会连同它的脚本与参考文档一起删除，无法恢复。",
            confirmText = "删除",
            destructive = true,
            onConfirm = {
                scope.launch {
                    SkillStore.delete(context, skill.id)
                    skills = SkillStore.skills.value
                    onToast("已删除")
                }
            },
        )
    }
}

/** 二级页顶栏：返回 + 标题。导入入口在右下角的圆形按钮上。 */
@Composable
private fun SkillTopBar(
    title: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val backPress = rememberMiuixPressState()
        Box(
            modifier = Modifier.size(44.dp)
                .miuixClickable(backPress, true, onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            MiuixIcon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                "返回",
                tint = MiuixTheme.colors.onSurface,
                size = 22.dp,
            )
        }
        MiuixText(
            text = title,
            style = MiuixTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 8.dp).weight(1f),
            maxLines = 1,
        )
    }
}

/** 一行 Skill：图标 + 名称/描述 + 启用开关。长按与点击都进详情。 */
@Composable
private fun SkillRow(
    skill: Skill,
    onClick: () -> Unit,
    /** 长按回调带上本行的窗口矩形，供溢出菜单定位。 */
    onLongPress: (Rect) -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val press = rememberMiuixPressState()
    // 卡片自身的窗口坐标。菜单是顶层 Popup，拿不到行布局，只能在这里量好带上去。
    var bounds by remember { mutableStateOf(Rect.Zero) }
    MiuixCard(
        modifier = Modifier
            .onGloballyPositioned { bounds = it.boundsInWindow() }
            .miuixClickable(press, true, onLongClick = { onLongPress(bounds) }, onClick = onClick),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(40.dp)
                    .background(colors.primary.copy(alpha = 0.12f), RoundedCornerShape(MiuixTheme.radius.field)),
                contentAlignment = Alignment.Center,
            ) {
                MiuixIcon(
                    Icons.Outlined.Extension,
                    null,
                    tint = colors.primary,
                    size = 22.dp,
                )
            }
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                MiuixText(
                    text = skill.name,
                    style = MiuixTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                MiuixText(
                    text = introOf(skill),
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(spacing.sm))
            MiuixSwitch(checked = skill.enabled, onCheckedChange = onToggle)
        }
    }
}

/** 导入分类。顶栏分段控件负责切换，下面的内容随之变化。 */
private enum class ImportTab(val label: String) {
    FILE("文件"),
    ZIP("ZIP"),
    FOLDER("文件夹"),
    URL("URL"),
    JSON("JSON"),
    CLIPBOARD("剪贴板"),
}

/**
 * 导入弹窗。
 *
 * 顶栏的分段控件即分类：切到哪一类，下面就是那一类需要的输入与操作，
 * 而不是把所有入口平铺在一屏里。
 */
@Composable
private fun SkillImportSheet(
    onDismiss: () -> Unit,
    onBusy: (Boolean) -> Unit,
    onToast: (String) -> Unit,
    onDone: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var tab by remember { mutableStateOf(ImportTab.FILE) }
    var urlText by remember { mutableStateOf("") }
    var jsonText by remember { mutableStateOf("") }

    fun run(block: suspend () -> List<String>) {
        scope.launch {
            onBusy(true)
            runCatching { block() }
                .onSuccess { names ->
                    onDone()
                    if (names.isNotEmpty()) onToast("已导入 ${names.size} 个：${names.joinToString("、")}")
                    else onToast("已导入")
                    onDismiss()
                }
                .onFailure { onToast("导入失败：${it.message ?: "未知错误"}") }
            onBusy(false)
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run {
            listOf(SkillStore.importFromMarkdown(context, uri, "文件").name)
        }
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run { listOf(SkillStore.importFromZip(context, uri, "ZIP").name) }
    }
    val jsonPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run { SkillStore.importFromJson(context, uri, "JSON").map { it.name } }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        run { listOf(SkillStore.importFromTree(context, uri, "文件夹").name) }
    }

    MiuixBottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.pageHorizontal),
        ) {
            MiuixText(
                text = "导入 Skill",
                style = MiuixTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 4.dp, bottom = spacing.sm),
            )
            MiuixSegmentedButton(
                options = ImportTab.entries.toList(),
                selected = tab,
                onSelect = { tab = it },
                label = { it.label },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(spacing.lg))

            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
            ) {
                when (tab) {
                    ImportTab.FILE -> ImportEntry(
                        icon = Icons.Outlined.Description,
                        title = "选择 Markdown 文件",
                        subtitle = "导入一个 .md；带 frontmatter 时读取 name 与 description",
                        action = "选择文件",
                        onAction = { filePicker.launch(arrayOf("text/*", "application/octet-stream")) },
                    )

                    ImportTab.ZIP -> ImportEntry(
                        icon = Icons.Outlined.Upload,
                        title = "选择 ZIP 压缩包",
                        subtitle = "包内需有 SKILL.md，可带 scripts / references / assets",
                        action = "选择 ZIP",
                        onAction = { zipPicker.launch(arrayOf("application/zip", "*/*")) },
                    )

                    ImportTab.FOLDER -> ImportEntry(
                        icon = Icons.Outlined.FolderOpen,
                        title = "选择文件夹",
                        subtitle = "整个目录复制进来；含多个子目录时逐个识别为 Skill",
                        action = "选择文件夹",
                        onAction = { folderPicker.launch(null) },
                    )

                    ImportTab.URL -> Column {
                        MiuixTextField(
                            value = urlText,
                            onValueChange = { urlText = it },
                            placeholder = "https://…/SKILL.md 或 .zip / .json",
                        )
                        Spacer(Modifier.height(spacing.md))
                        MiuixButton(
                            text = "从 URL 导入",
                            onClick = {
                                val url = urlText.trim()
                                if (url.isBlank()) {
                                    onToast("请先填写地址")
                                } else {
                                    run { SkillStore.importFromUrl(context, url, "URL").map { it.name } }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = Icons.Outlined.Link,
                        )
                    }

                    ImportTab.JSON -> Column {
                        MiuixTextField(
                            value = jsonText,
                            onValueChange = { jsonText = it },
                            placeholder = "粘贴 JSON 文本",
                            singleLine = false,
                            minLines = 4,
                        )
                        Spacer(Modifier.height(spacing.md))
                        MiuixButton(
                            text = "从 JSON 文本导入",
                            onClick = {
                                val text = jsonText.trim()
                                if (text.isBlank()) {
                                    onToast("请先粘贴 JSON")
                                } else {
                                    run { SkillStore.importFromJsonText(context, text, "JSON").map { it.name } }
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = Icons.Outlined.DataObject,
                        )
                        Spacer(Modifier.height(spacing.sm))
                        MiuixButton(
                            text = "从 JSON 文件导入",
                            onClick = { jsonPicker.launch(arrayOf("application/json", "text/*", "*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            variant = MiuixButtonVariant.TONAL,
                        )
                    }

                    ImportTab.CLIPBOARD -> ImportEntry(
                        icon = Icons.Outlined.ContentCopy,
                        title = "从剪贴板导入",
                        subtitle = "读出剪贴板里的 JSON 文本并导入",
                        action = "读取剪贴板",
                        onAction = {
                            val text = clipboard.getText()?.text?.trim().orEmpty()
                            if (text.isBlank()) {
                                onToast("剪贴板里没有文本")
                            } else {
                                run { SkillStore.importFromJsonText(context, text, "剪贴板").map { it.name } }
                            }
                        },
                    )
                }
                Spacer(Modifier.height(spacing.xl))
            }
        }
    }
}

/** 导入分类里的一屏：图标 + 说明 + 一个主操作按钮。 */
@Composable
private fun ImportEntry(
    icon: ImageVector,
    title: String,
    subtitle: String,
    action: String,
    onAction: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    MiuixCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.lg)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixIcon(icon, null, tint = colors.primary, size = 24.dp)
            Spacer(Modifier.width(spacing.md))
            MiuixText(text = title, style = MiuixTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(spacing.sm))
        MiuixText(
            text = subtitle,
            style = MiuixTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(spacing.lg))
        MiuixButton(
            text = action,
            onClick = onAction,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 一句话介绍。
 *
 * Skill 的 description 是写给模型看的：触发条件、禁用场景、关键词都堆在同一段里，
 * 直接铺在界面上没人读得下去。这里取第一句，列表与详情共用。
 */
private fun introOf(skill: Skill): String =
    firstSentence(skill.description).ifBlank { skill.summary }

/** 详情页的完整介绍：description 原样保留换行，没有描述时退回来源与大小。 */
private fun fullIntro(skill: Skill): String =
    skill.description.trim().ifBlank { skill.summary }

/** 取第一句并压掉换行；过长时截断，避免一句话占满整屏。 */
private fun firstSentence(raw: String): String {
    val text = raw.trim().replace(Regex("\\s+"), " ")
    if (text.isEmpty()) return ""
    val stop = text.indexOfFirst { it in "。！？!?." }
    val cut = if (stop >= 0) text.substring(0, stop + 1) else text
    return if (cut.length > 140) cut.take(139).trimEnd() + "…" else cut
}

/**
 * 正文里的二三级标题，作为「这份 Skill 讲了什么」的目录。
 *
 * 跳过代码围栏，否则脚本示例里的注释会被误当成标题；最多取 [limit] 条。
 */
private fun outlineOf(body: String, limit: Int = 8): List<String> {
    val out = mutableListOf<String>()
    var inFence = false
    body.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            inFence = !inFence
            return@forEach
        }
        if (inFence || out.size >= limit) return@forEach
        if (trimmed.startsWith("## ") || trimmed.startsWith("### ")) {
            val title = trimmed.removePrefix("### ").removePrefix("## ").trim()
            if (title.isNotEmpty()) out += title
        }
    }
    return out
}

/** 长按（或点击）进入的详情面板：正文、附带文件与各项操作。 */
@Composable
private fun SkillDetailSheet(
    skill: Skill,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onExportJson: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    var body by remember(skill.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(skill.id) { body = SkillStore.body(context, skill.id) }

    MiuixBottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.pageHorizontal),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(40.dp)
                        .background(colors.primary.copy(alpha = 0.12f), RoundedCornerShape(MiuixTheme.radius.field)),
                    contentAlignment = Alignment.Center,
                ) {
                    MiuixIcon(Icons.Outlined.Extension, null, tint = colors.primary, size = 22.dp)
                }
                Spacer(Modifier.width(spacing.md))
                Column(Modifier.weight(1f)) {
                    MiuixText(
                        text = skill.name,
                        style = MiuixTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MiuixText(
                        text = buildString {
                            append(skill.id)
                            if (skill.version.isNotBlank()) append(" · v").append(skill.version)
                            append(" · ").append(Skill.formatBytes(skill.bytes))
                        },
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(spacing.md))
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
            ) {
                MiuixCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.lg)) {
                    MiuixText(
                        text = "介绍",
                        style = MiuixTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(spacing.sm))
                    MiuixText(
                        text = fullIntro(skill),
                        style = MiuixTheme.typography.bodyMedium,
                    )
                    val outline = outlineOf(body.orEmpty())
                    if (outline.isNotEmpty()) {
                        Spacer(Modifier.height(spacing.md))
                        MiuixText(
                            text = "内容目录",
                            style = MiuixTheme.typography.labelMedium,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(spacing.xs))
                        outline.forEach { item ->
                            MiuixText(
                                text = "· $item",
                                style = MiuixTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (skill.source.isNotBlank()) {
                        Spacer(Modifier.height(spacing.sm))
                        MiuixText(
                            text = buildString {
                                append("来自 ").append(skill.source)
                                if (skill.importedAt > 0) {
                                    append(" · 导入于 ").append(Formatters.relativeTime(skill.importedAt))
                                }
                            },
                            style = MiuixTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(spacing.md))
                MiuixCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.lg)) {
                    MiuixText(
                        text = "正文",
                        style = MiuixTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(spacing.sm))
                    val bodyText = body.orEmpty()
                    if (bodyText.isBlank()) {
                        MiuixText(
                            text = "（正文为空）",
                            style = MiuixTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    } else {
                        MarkdownText(text = bodyText)
                    }
                }
                if (skill.files.size > 1) {
                    Spacer(Modifier.height(spacing.md))
                    MiuixCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(spacing.lg)) {
                        MiuixText(
                            text = "附带文件（${skill.files.size - 1}）",
                            style = MiuixTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(spacing.sm))
                        skill.files.filter { it != "SKILL.md" }.take(40).forEach { f ->
                            MiuixText(
                                text = "· $f",
                                style = MiuixTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(spacing.md))
                MiuixCard(contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    MiuixListItem(
                        title = "重命名",
                        leadingIcon = Icons.Outlined.DriveFileRenameOutline,
                        onClick = onRename,
                        showDivider = true,
                    )
                    MiuixListItem(
                        title = "导出为 ZIP",
                        leadingIcon = Icons.Outlined.Upload,
                        onClick = onExport,
                        showDivider = true,
                    )
                    MiuixListItem(
                        title = "复制 JSON",
                        leadingIcon = Icons.Outlined.DataObject,
                        onClick = onExportJson,
                        showDivider = true,
                    )
                    MiuixListItem(
                        title = "删除",
                        leadingIcon = Icons.Outlined.DeleteOutline,
                        onClick = onDelete,
                        danger = true,
                    )
                }
                Spacer(Modifier.height(spacing.lg))
            }
        }
    }
}

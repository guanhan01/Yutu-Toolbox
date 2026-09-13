package com.mcp.toolbox.feature.decompile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material.icons.outlined.ZoomOut
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import androidx.core.content.ContextCompat
import com.mcp.toolbox.core.design.component.CodeLanguage
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixBottomSheet
import com.mcp.toolbox.core.design.component.CodeEditor
import com.mcp.toolbox.core.design.component.MiuixCard
import com.mcp.toolbox.core.design.component.MiuixCodeText
import com.mcp.toolbox.core.design.component.MiuixDialog
import com.mcp.toolbox.core.design.component.MiuixEmptyState
import com.mcp.toolbox.core.design.component.MiuixFilterChip
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixLinearProgress
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixSearchField
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSegmentedButton
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.feature.decompile.engine.ApkSource
import com.mcp.toolbox.feature.decompile.engine.DecompileEngine
import com.mcp.toolbox.feature.decompile.engine.DecompileHub
import com.mcp.toolbox.feature.decompile.engine.DecompileTaskService
import com.mcp.toolbox.feature.decompile.engine.InstalledApks
import com.mcp.toolbox.feature.decompile.engine.JadxEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 反编译工作台：真实 APK 分析（AXML / ARSC / dexlib2）+ smali / jadx 双引擎 + 任务中心。
 */
/**
 * 反编译工作台。主界面是「目录树 + 内容」的双栏结构：
 *
 * - 左栏是按包名前缀折出来的文件树，目录可展开折叠，只渲染可见行；
 * - 右栏是内容区，显示当前选中文件的代码，可切换成编辑态直接改并写回磁盘；
 * - 来源选择与原摘要卡收进「来源与概览」底部抽屉，需要时再拉起来，不占常驻空间。
 *
 * 清单与资源两个分段保留原有的全宽呈现，它们没有「一个文件」的概念。
 */
@Composable
fun DecompileScreen(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    onToast: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing

    val session by DecompileHub.session.collectAsState()
    val tasks by DecompileHub.tasks.collectAsState()
    val running by DecompileHub.running.collectAsState()
    val smaliFiles by DecompileHub.smaliFiles.collectAsState()

    var view by remember { mutableStateOf(DecompileView.JAVA) }
    var source by remember { mutableStateOf<ApkSource>(ApkSource.SelfApp) }
    var menuOpen by remember { mutableStateOf(false) }
    var taskCenterOpen by remember { mutableStateOf(false) }
    var overviewOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var appPickerOpen by remember { mutableStateOf(false) }
    var engineNote by remember { mutableStateOf<String?>(null) }
    var treeQuery by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var fileCode by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var editCaret by remember { mutableIntStateOf(-1) }
    // 侧边栏：收起 / 展开 + 用户拖出来的宽度比例。
    var treeOpen by remember { mutableStateOf(true) }
    var treeWidthFraction by remember { mutableFloatStateOf(0.4f) }
    var codeZoom by remember { mutableFloatStateOf(1f) }
    var searchQuery by remember { mutableStateOf("") }
    var detailText by remember { mutableStateOf<String?>(null) }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val launchTask: (String, ApkSource?) -> Unit = { action, explicit ->
        val target = explicit ?: source
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        DecompileTaskService.launch(context, action, target)
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "picked.apk"
            val picked = ApkSource.PickedFile(uri, name)
            source = picked
            launchTask(DecompileTaskService.ACTION_ANALYZE, picked)
            onToast("已选择 $name，开始分析")
        }
    }

    LaunchedEffect(Unit) {
        DecompileHub.loadHistory(context)
        engineNote = JadxEngine.availability()
        // 从文件页 / 内置阅读器点「反编译」进来：自动选中该文件并直接开始分析。
        DecompileHub.pendingPath.value?.let { path ->
            DecompileHub.pendingPath.value = null
            val target = ApkSource.LocalPath(path)
            source = target
            launchTask(DecompileTaskService.ACTION_ANALYZE, target)
            onToast("已选中 ${target.label}，开始分析")
        }
    }

    // 进程重启后 session 会丢，但产物目录和任务历史都还在。
    // 从最近一次成功任务里把目录捡回来，免得出现「任务成功、左栏却空」的假象。
    val restoredJavaDir = remember(tasks) {
        tasks.firstOrNull {
            it.state == TaskState.DONE && it.engine == DecompileTaskService.actionLabel(DecompileTaskService.ACTION_JAVA) &&
                !it.outputDir.isNullOrBlank()
        }?.outputDir
    }
    val restoredSmaliDir = remember(tasks) {
        tasks.firstOrNull {
            it.state == TaskState.DONE && it.engine == DecompileTaskService.actionLabel(DecompileTaskService.ACTION_SMALI) &&
                !it.outputDir.isNullOrBlank()
        }?.outputDir
    }
    val restoredDir = if (view == DecompileView.JAVA) restoredJavaDir else restoredSmaliDir
    val dirOf: (DecompileSession?) -> String? = { s ->
        if (view == DecompileView.JAVA) s?.javaDir ?: restoredJavaDir else s?.smaliDir ?: restoredSmaliDir
    }
    val fileList: List<String> = remember(session, view, smaliFiles, restoredDir) {
        val root = dirOf(session) ?: return@remember emptyList()
        run {
            val rootFile = File(root)
            if (!rootFile.isDirectory) {
                emptyList()
            } else {
                rootFile.walkTopDown()
                    .filter { it.isFile && (it.extension == "java" || it.extension == "smali") }
                    .map { it.relativeTo(rootFile).path.replace('\\', '/') }
                    .sorted()
                    .take(60000)
                    .toList()
            }
        }
    }

    // 目录树只在 Java / Smali 视图下有意义：它们的产物本来就是一层层包名目录。
    val tree = remember(fileList) { buildFileTree(fileList) }
    val rows: List<TreeRow> = remember(tree, expanded, treeQuery) {
        if (treeQuery.isBlank()) {
            flattenFileTree(tree, expanded)
        } else {
            // 搜索时整棵树展开，只留命中的文件；路径保留完整包名，便于确认同名类。
            val keyword = treeQuery
            flattenFileTree(tree, allDirectoryPaths(tree))
                .filter { row -> !row.node.isDirectory && row.node.path.contains(keyword, ignoreCase = true) }
        }
    }

    // 首次拿到文件列表时展开最前面几个目录，免得一进来只有一排折叠项。
    LaunchedEffect(tree) {
        if (expanded.isEmpty()) {
            expanded = tree.filter { it.isDirectory }.take(3).map { it.path }.toSet()
        }
    }

    LaunchedEffect(selectedFile, session, view, restoredDir) {
        val root = dirOf(session) ?: return@LaunchedEffect
        val path = selectedFile ?: return@LaunchedEffect
        fileCode = withContext(Dispatchers.IO) {
            runCatching { File(root, path).readText().take(400000) }.getOrDefault("// 读取失败")
        }
    }

    val saveCode: () -> Unit = {
        val root = dirOf(session)
        val path = selectedFile
        if (root == null || path == null) {
            onToast("没有可保存的文件")
        } else {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching { File(root, path).writeText(draft) }.isSuccess
                }
                if (ok) {
                    fileCode = draft
                    editing = false
                    onToast("已保存 $path")
                } else {
                    onToast("保存失败：$path")
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            MiuixTopAppBar(
                title = "反编译",
                navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                onNavigationClick = onOpenDrawer,
                actions = {
                    Box {
                        MiuixIconButton(Icons.Outlined.MoreHoriz, "更多", onClick = { menuOpen = true })
                        MiuixOverflowMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                            MiuixMenuItem(
                                "来源与概览",
                                {
                                    menuOpen = false
                                    overviewOpen = true
                                },
                                icon = Icons.Outlined.Widgets,
                            )
                            MiuixMenuItem(
                                "全局搜索",
                                {
                                    menuOpen = false
                                    searchOpen = true
                                },
                                icon = Icons.Outlined.Search,
                            )
                            MiuixMenuItem(
                                "重新分析当前 APK",
                                {
                                    menuOpen = false
                                    launchTask(DecompileTaskService.ACTION_ANALYZE, null)
                                },
                                icon = Icons.Outlined.PlayArrow,
                            )
                            MiuixMenuItem(
                                "生成 smali（baksmali）",
                                {
                                    menuOpen = false
                                    view = DecompileView.SMALI
                                    launchTask(DecompileTaskService.ACTION_SMALI, null)
                                },
                                icon = Icons.Outlined.Terminal,
                            )
                            MiuixMenuItem(
                                "生成 Java 源码（jadx）",
                                {
                                    menuOpen = false
                                    view = DecompileView.JAVA
                                    launchTask(DecompileTaskService.ACTION_JAVA, null)
                                },
                                icon = Icons.Outlined.Code,
                            )
                            MiuixMenuItem(
                                "导出清单 / 类表 / 字符串",
                                {
                                    menuOpen = false
                                    val s = session
                                    if (s == null) {
                                        onToast("请先完成一次分析")
                                    } else {
                                        scope.launch {
                                            val dir = File(
                                                DecompileEngine.sessionDir(context, File(s.summary.path), s.summary.displayName),
                                                "export",
                                            )
                                            val files = withContext(Dispatchers.IO) {
                                                DecompileEngine.exportTexts(s, dir)
                                            }
                                            onToast("已导出 ${files.size} 个文件到 ${dir.absolutePath}")
                                        }
                                    }
                                },
                                icon = Icons.Outlined.Download,
                            )
                            MiuixMenuItem(
                                "引擎状态",
                                {
                                    menuOpen = false
                                    val jadx = JadxEngine.availability()
                                    detailText = buildString {
                                        append("baksmali / dexlib2：可用（已随 APK 打包）\n")
                                        append("jadx-core：")
                                        append(jadx ?: "可用")
                                        append("\n\n输出目录：")
                                        append(DecompileEngine.workDir(context).absolutePath)
                                    }
                                },
                                icon = Icons.Outlined.Memory,
                            )
                            MiuixMenuItem(
                                "清理任务中心",
                                {
                                    menuOpen = false
                                    DecompileHub.clearTasks(context)
                                    onToast("任务中心已清空")
                                },
                                icon = Icons.Outlined.Delete,
                            )
                            MiuixMenuItem(
                                "清空产物目录",
                                {
                                    menuOpen = false
                                    scope.launch {
                                        val dir = DecompileEngine.workDir(context)
                                        val removed = withContext(Dispatchers.IO) {
                                            dir.deleteRecursively()
                                        }
                                        DecompileHub.javaCache.clear()
                                        DecompileHub.smaliFiles.value = emptyList()
                                        onToast(if (removed) "产物目录已清空" else "清理失败")
                                    }
                                },
                                icon = Icons.Outlined.Delete,
                                danger = true,
                            )
                        }
                    }
                },
            )

            MiuixSegmentedButton(
                options = DecompileView.entries,
                selected = view,
                onSelect = {
                    view = it
                    selectedFile = null
                    editing = false
                },
                label = { it.label },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageHorizontal),
            )
            Spacer(Modifier.height(spacing.sm))

            // 运行中的任务在这里给一条真实进度：之前只有通知栏，界面上看不出反应。
            running?.let { task ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.pageHorizontal),
                ) {
                    MiuixText(
                        text = "${task.engine} · ${(task.progress * 100).toInt()}% · ${task.message}",
                        style = MiuixTheme.typography.labelMedium,
                        color = colors.primary,
                        maxLines = 1,
                    )
                    // 手画一条细进度条：这个模块没有 material3 依赖。
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(3.dp)
                            .background(colors.outlineVariant),
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth(task.progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(colors.primary),
                        )
                    }
                }
                Spacer(Modifier.height(spacing.sm))
            }

            val active = session
            when {
                // 清单与资源必须有分析结果才谈得上；
                // 其余视图只要能找到产物目录，就照常进双栏。
                active == null &&
                    (restoredDir == null || view == DecompileView.MANIFEST || view == DecompileView.RESOURCES) -> Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = spacing.pageHorizontal),
                ) {
                    engineNote?.let { note ->
                        MiuixCard(contentPadding = PaddingValues(spacing.md)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MiuixIcon(Icons.Outlined.ErrorOutline, null, tint = colors.warning, size = 18.dp)
                                Spacer(Modifier.width(spacing.sm))
                                MiuixText(note, style = MiuixTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(spacing.sm))
                    }
                    MiuixEmptyState(
                        title = "还没有分析结果",
                        description = "选好来源跑一次分析，结果会在这里按包名展开成可折叠的目录树。",
                        icon = Icons.Outlined.Widgets,
                        action = {
                            MiuixButton(
                                "选择来源",
                                { overviewOpen = true },
                                leadingIcon = Icons.Outlined.FolderOpen,
                            )
                        },
                    )
                }

                view == DecompileView.MANIFEST -> Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = spacing.pageHorizontal),
                ) {
                    ManifestPane(active!!)
                }

                view == DecompileView.RESOURCES -> Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = spacing.pageHorizontal),
                ) {
                    ResourcePane(active!!) { detailText = it }
                }

                active != null && active.javaDir == null && active.smaliDir == null && restoredDir == null -> Column(
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = spacing.pageHorizontal),
                ) {
                    engineNote?.let { note ->
                        MiuixCard(contentPadding = PaddingValues(spacing.md)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                MiuixIcon(Icons.Outlined.ErrorOutline, null, tint = colors.warning, size = 18.dp)
                                Spacer(Modifier.width(spacing.sm))
                                MiuixText(note, style = MiuixTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(spacing.sm))
                    }
                    MiuixEmptyState(
                        title = if (view == DecompileView.JAVA) "尚未生成 Java 源码" else "尚未生成 smali",
                        description = "点下面的按钮在后台任务里生成，进度会显示在通知栏与任务中心。",
                        icon = Icons.Outlined.Code,
                        action = {
                            MiuixButton(
                                if (view == DecompileView.JAVA) "生成 Java 源码（jadx）" else "生成 smali（baksmali）",
                                {
                                    launchTask(
                                        if (view == DecompileView.JAVA) DecompileTaskService.ACTION_JAVA else DecompileTaskService.ACTION_SMALI,
                                        null,
                                    )
                                },
                                leadingIcon = Icons.Outlined.PlayArrow,
                            )
                        },
                    )
                }

                else -> BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val fullWidth = maxWidth
                    // 拖动把手的那几十毫秒里要跟手，所以把宽度动画切成 snap；
                    // 松手后恢复补间，收起与展开才是滑出去的。
                    var draggingTree by remember { mutableStateOf(false) }
                    val treeFraction by animateFloatAsState(
                        targetValue = if (treeOpen) treeWidthFraction else 0f,
                        animationSpec = if (draggingTree) snap() else tween(260),
                        label = "treeWidth",
                    )
                    Row(Modifier.fillMaxSize()) {
                        Box(
                            Modifier
                                .width(fullWidth * treeFraction)
                                .fillMaxHeight()
                                .clipToBounds(),
                        ) {
                            FileTreePanel(
                                rows = rows,
                                expanded = expanded,
                                query = treeQuery,
                                onQuery = { treeQuery = it },
                                selected = selectedFile,
                                onToggle = { path ->
                                    expanded = if (path in expanded) expanded - path else expanded + path
                                },
                                onSelect = { path ->
                                    selectedFile = path
                                    editing = false
                                    editCaret = -1
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        TreeHandle(
                            open = treeOpen,
                            onToggle = { treeOpen = !treeOpen },
                            onResize = { delta ->
                                // 收起状态下拖动，就从零开始长出来，手感更像「拉出抽屉」。
                                if (!treeOpen) {
                                    treeOpen = true
                                    treeWidthFraction = 0f
                                }
                                treeWidthFraction = (treeWidthFraction + delta).coerceIn(0.2f, 0.58f)
                            },
                            onDragging = { draggingTree = it },
                            totalWidth = fullWidth,
                        )
                        CodeDetailPanel(
                            file = selectedFile,
                            code = fileCode,
                            editing = editing,
                            draft = draft,
                            editCaret = editCaret,
                            zoom = codeZoom,
                            onZoom = { next ->
                                codeZoom = next.coerceIn(MIN_CODE_ZOOM, MAX_CODE_ZOOM)
                            },
                            onDraft = { draft = it },
                            onStartEdit = { caret ->
                                draft = fileCode
                                editCaret = caret
                                editing = true
                            },
                            onCancelEdit = { editing = false },
                            onSave = saveCode,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                        )
                    }
                }
            }

            TaskCenter(
                running = running,
                tasks = tasks,
                expanded = taskCenterOpen,
                onToggle = { taskCenterOpen = !taskCenterOpen },
                modifier = Modifier
                    .padding(horizontal = spacing.pageHorizontal, vertical = spacing.sm)
                    .navigationBarsPadding(),
            )
        }

        detailText?.let { text ->
            MiuixDialog(
                visible = true,
                onDismiss = { detailText = null },
                title = "详情",
                message = text,
                confirmText = "复制",
                onConfirm = {
                    detailText = null
                    onToast("已复制到详情弹窗缓存")
                },
                dismissText = "关闭",
            )
        }

        if (appPickerOpen) {
            InstalledAppSheet(
                context = context,
                onDismiss = { appPickerOpen = false },
                onPick = { entry ->
                    appPickerOpen = false
                    val picked = ApkSource.InstalledApp(entry.packageName, entry.appLabel)
                    source = picked
                    launchTask(DecompileTaskService.ACTION_ANALYZE, picked)
                },
            )
        }
    }

    // 子界面：原来的来源选择 + 摘要卡，收进同一个窗口里的底部抽屉。
    MiuixBottomSheet(visible = overviewOpen, onDismiss = { overviewOpen = false }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageHorizontal),
        ) {
            MiuixText("来源与概览", style = MiuixTheme.typography.titleMedium)
            Spacer(Modifier.height(spacing.sm))
            SourcePicker(
                source = source,
                running = running,
                onSelf = {
                    source = ApkSource.SelfApp
                    launchTask(DecompileTaskService.ACTION_ANALYZE, ApkSource.SelfApp)
                },
                onInstalled = { appPickerOpen = true },
                onPickFile = { picker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "*/*")) },
                onAnalyze = {
                    // 点完立刻收起抽屉：不然按钮没变化，看着像没点到。
                    overviewOpen = false
                    launchTask(DecompileTaskService.ACTION_ANALYZE, null)
                },
            )
            session?.let { current ->
                Spacer(Modifier.height(spacing.md))
                SummaryCard(current)
            }
            Spacer(Modifier.height(spacing.lg))
        }
    }

    // 子界面：全局搜索也放在抽屉里，不打断双栏浏览。
    MiuixBottomSheet(visible = searchOpen, onDismiss = { searchOpen = false }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.pageHorizontal),
        ) {
            session?.let { current ->
                SearchPane(current, searchQuery, { searchQuery = it }) { hit ->
                    detailText = "${hit.kind} · ${hit.owner}\n${hit.text}\n${hit.detail}"
                }
            } ?: MiuixText(
                "先完成一次分析，再按类名、方法签名或字符串常量搜索。",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.lg))
        }
    }
}

/**
 * 右栏内容区：显示选中文件的代码，并提供编辑与写回。
 *
 * 编辑入口有两个：直接点正文，或者点右上角的编辑按钮。
 * 保存写回产物目录里的真实文件，这样手改过的 smali / Java 也能立刻用于后续步骤。
 * 标题栏还有一对字号按钮，配合双指捏合可以把代码缩放到舒服的大小。
 */
@Composable
private fun CodeDetailPanel(
    file: String?,
    code: String,
    editing: Boolean,
    draft: String,
    editCaret: Int,
    zoom: Float,
    onZoom: (Float) -> Unit,
    onDraft: (String) -> Unit,
    onStartEdit: (Int) -> Unit,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    // 捏合直接落成真实字号：预览走惰性列表，重排只涉及可见行，不需要再做视觉缩放。
    val latestZoom by rememberUpdatedState(zoom)
    val latestOnZoom by rememberUpdatedState(onZoom)
    val codeLanguage = if (file?.endsWith(".java") == true) CodeLanguage.JAVA else CodeLanguage.SMALI
    Column(modifier) {
        // 路径独占一行：侧边栏拉宽时，按钮再多也挤不掉它。
        MiuixText(
            text = file ?: "未选择文件",
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = spacing.sm, end = spacing.sm, top = spacing.xs),
            style = MiuixTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = if (file == null) colors.onSurfaceVariant else colors.primary,
            maxLines = 1,
        )
        if (file != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = spacing.xs, bottom = spacing.xs),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiuixIconButton(Icons.Outlined.ZoomOut, "缩小字号", onClick = { onZoom(quantizeZoom(zoom / 1.15f)) }, buttonSize = 34.dp, iconSize = 17.dp)
                MiuixIconButton(Icons.Outlined.ZoomIn, "放大字号", onClick = { onZoom(quantizeZoom(zoom * 1.15f)) }, buttonSize = 34.dp, iconSize = 17.dp)
                if (editing) {
                    MiuixIconButton(Icons.Outlined.Close, "放弃修改", onClick = onCancelEdit, buttonSize = 34.dp, iconSize = 17.dp)
                    MiuixIconButton(Icons.Outlined.Save, "保存", onClick = onSave, buttonSize = 34.dp, iconSize = 17.dp)
                } else {
                    MiuixIconButton(Icons.Outlined.Edit, "编辑", onClick = { onStartEdit(-1) }, buttonSize = 34.dp, iconSize = 17.dp)
                }
            }
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                file == null -> Box(Modifier.fillMaxSize().padding(spacing.md)) {
                    MiuixText(
                        text = "点左侧文件查看内容",
                        style = MiuixTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }

                editing -> CodeEditor(
                    value = draft,
                    onValueChange = onDraft,
                    autoFocus = true,
                    initialSelection = editCaret,
                    language = codeLanguage,
                    fontSize = (BASE_CODE_FONT_SP * zoom).sp,
                    lineHeight = (BASE_CODE_LINE_SP * zoom).sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .codeZoomGesture(
                            onLive = { factor -> latestOnZoom(quantizeZoom(latestZoom * factor)) },
                            onCommit = {},
                        ),
                    minHeight = 0.dp,
                )

                else -> LazyCodeView(
                    code = code,
                    language = codeLanguage,
                    fontSize = (BASE_CODE_FONT_SP * zoom).sp,
                    lineHeight = (BASE_CODE_LINE_SP * zoom).sp,
                    // 点正文直接进编辑态，光标落在手指按下的那个字符上。
                    onTapOffset = onStartEdit,
                    modifier = Modifier
                        .fillMaxSize()
                        .codeZoomGesture(
                            onLive = { factor ->
                                latestOnZoom((latestZoom * factor).coerceIn(MIN_CODE_ZOOM, MAX_CODE_ZOOM))
                            },
                            onCommit = {},
                        ),
                )
            }
        }
    }
}

/**
 * 把缩放量化到 0.05 的台阶。
 *
 * 捏合时每一帧都改字号，加载的列表每帧都要重排可见行，几千行的 smali 直接卡爆。
 * 量化之后只有跨过台阶才重组，手感依旧连续，重排次数少一个数量级。
 */
private fun quantizeZoom(value: Float): Float =
    (value.coerceIn(MIN_CODE_ZOOM, MAX_CODE_ZOOM) * 20f).roundToInt() / 20f

/** 代码字号缩放的上下限与基准值，按钮和捏合手势共用。 */
private const val MIN_CODE_ZOOM = 0.6f
private const val MAX_CODE_ZOOM = 3f
private const val BASE_CODE_FONT_SP = 11f
private const val BASE_CODE_LINE_SP = 18f

/**
 * 双指捏合缩放代码字号。
 *
 * 只在两根手指同时按下时才消费事件，单指仍留给垂直与水平滚动，
 * 否则想把代码滚下去就会变成改字号。回调传的是倍率而不是新值，
 * 这样手势进行中即使缩放状态在变，也不会读到过期的闭包值。
 */
private fun Modifier.codeZoomGesture(
    onLive: (Float) -> Unit,
    onCommit: () -> Unit,
): Modifier =
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    val factor = event.calculateZoom()
                    if (factor != 1f) {
                        event.changes.forEach { it.consume() }
                        // 手势期间只累积视觉倍率，不碰字号：否则每帧都要重排整篇代码。
                        onLive(factor)
                    }
                }
                if (event.changes.none { it.pressed }) break
            }
            onCommit()
        }
    }

/**
 * 两栏之间的把手：点一下收起或展开侧边栏，横向拖动可以自由改宽度。
 *
 * 一次手势只做一件事：横向位移超过触摸阈值才算拖动，否则一律算点击。
 * 否则想收起侧边栏时会被判成拖动，顺手把宽度也改掉。
 * 宽度与动画由调用方持有；这里只在真正拖动期间告诉它「正在拖」，
 * 好让它把宽度动画切成 snap，跟手不迟滞。
 */
@Composable
private fun TreeHandle(
    open: Boolean,
    onToggle: () -> Unit,
    onResize: (Float) -> Unit,
    onDragging: (Boolean) -> Unit,
    totalWidth: Dp,
) {
    val colors = MiuixTheme.colors
    val gestureWidth = with(LocalDensity.current) { totalWidth.toPx() }.coerceAtLeast(1f)
    val touchSlop = LocalViewConfiguration.current.touchSlop
    Box(
        modifier = Modifier
            .width(20.dp)
            .fillMaxHeight()
            .background(colors.surfaceContainerLow)
            .pointerInput(gestureWidth, touchSlop) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var travelled = 0f
                    var isDrag = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val step = change.position.x - change.previousPosition.x
                        travelled += step
                        if (!isDrag && abs(travelled) > touchSlop) {
                            isDrag = true
                            onDragging(true)
                        }
                        if (isDrag) {
                            change.consume()
                            onResize(step / gestureWidth)
                        }
                    }
                    if (isDrag) onDragging(false) else onToggle()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(1.dp)
                .fillMaxHeight()
                .background(colors.outlineVariant),
        )
        MiuixIcon(
            icon = if (open) Icons.AutoMirrored.Outlined.KeyboardArrowLeft else Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = if (open) "收起侧边栏" else "展开侧边栏",
            tint = colors.onSurfaceVariant,
            size = 17.dp,
        )
    }
}

@Composable
private fun SourcePicker(
    source: ApkSource,
    running: TaskRecord?,
    onSelf: () -> Unit,
    onInstalled: () -> Unit,
    onPickFile: () -> Unit,
    onAnalyze: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    MiuixCard(contentPadding = PaddingValues(spacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MiuixIcon(Icons.Outlined.FolderOpen, null, tint = colors.primary, size = 20.dp)
            Spacer(Modifier.width(spacing.sm))
            Column(Modifier.weight(1f)) {
                MiuixText("目标 APK", style = MiuixTheme.typography.titleSmall)
                MiuixText(
                    text = source.label,
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            MiuixTag(text = "本地解析", color = colors.success)
        }
        Spacer(Modifier.height(spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
            MiuixButton("本应用", onSelf, variant = MiuixButtonVariant.TONAL, size = com.mcp.toolbox.core.design.component.MiuixButtonSize.SMALL)
            MiuixButton("已安装应用", onInstalled, variant = MiuixButtonVariant.TONAL, size = com.mcp.toolbox.core.design.component.MiuixButtonSize.SMALL)
            MiuixButton("选文件", onPickFile, variant = MiuixButtonVariant.TONAL, size = com.mcp.toolbox.core.design.component.MiuixButtonSize.SMALL)
        }
        Spacer(Modifier.height(spacing.sm))
        MiuixButton(
            text = if (running != null) "分析中 ${(running.progress * 100).toInt()}%" else "开始分析",
            onClick = { if (running == null) onAnalyze() },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = Icons.Outlined.PlayArrow,
        )
    }
}

@Composable
private fun SummaryCard(session: DecompileSession) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val summary = session.summary
    MiuixSectionCard(title = summary.displayName, subtitle = summary.path) {
        Column(Modifier.padding(spacing.md)) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                MiuixTag("类 ${session.classes.size}", color = colors.primary)
                MiuixTag("方法 ${session.methodCount}", color = colors.primary)
                MiuixTag("字段 ${session.fieldCount}", color = colors.primary)
                MiuixTag("字符串 ${session.stringPool.size}", color = colors.success)
            }
            Spacer(Modifier.height(spacing.sm))
            KeyValue("大小", "%.2f MB".format(summary.sizeBytes / 1024.0 / 1024.0))
            KeyValue("条目", "${summary.entryCount} 个（dex ${summary.dexEntries.size} 个 · so ${summary.nativeLibs.size} 个）")
            KeyValue("MD5", summary.md5)
            KeyValue("SHA-256", summary.sha256)
            summary.certificate?.let { cert ->
                KeyValue("签名主体", cert.subject)
                KeyValue("证书指纹", cert.sha256)
                KeyValue("有效期", "${cert.notBefore} → ${cert.notAfter}")
            } ?: KeyValue("签名", "未找到证书（v1/v2 均未解析到）")
            session.manifest?.let { manifest ->
                KeyValue("包名", manifest.packageName)
                KeyValue("版本", "${manifest.versionName ?: "?"} (${manifest.versionCode ?: "?"})")
                KeyValue("SDK", "min ${manifest.minSdk ?: "?"} · target ${manifest.targetSdk ?: "?"}")
                KeyValue("权限", "${manifest.permissions.size} 个 · 组件 ${manifest.groups.sumOf { it.items.size }} 个")
            }
            session.resources?.let { resources ->
                KeyValue("资源", "${resources.entries.size} 项 · 类型 ${resources.typeCounts.size} 种 · 配置块 ${resources.configCount}")
            }
            session.smaliDir?.let { KeyValue("smali", it) }
            session.javaDir?.let { KeyValue("Java", it) }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    val colors = MiuixTheme.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        MiuixText(
            text = key,
            modifier = Modifier.width(84.dp),
            style = MiuixTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        MiuixText(
            text = value,
            modifier = Modifier.weight(1f),
            style = MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
private fun ManifestPane(session: DecompileSession) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val manifest = session.manifest
    if (manifest == null) {
        MiuixEmptyState(
            title = "清单解析失败",
            description = "该 APK 没有 AndroidManifest.xml，或 AXML 结构不受支持。",
            icon = Icons.Outlined.ErrorOutline,
        )
        return
    }
    Column {
        MiuixSectionCard(title = "权限（${manifest.permissions.size}）") {
            Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                manifest.permissions.take(40).forEach { permission ->
                    MiuixText(
                        text = permission,
                        style = MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                }
                if (manifest.permissions.size > 40) {
                    MiuixText("… 其余 ${manifest.permissions.size - 40} 个", style = MiuixTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(spacing.sm))
        manifest.groups.forEach { group ->
            MiuixSectionCard(title = "${group.kind}（${group.items.size}）") {
                Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                    group.items.take(25).forEach { item ->
                        MiuixText(
                            text = item,
                            style = MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                    if (group.items.size > 25) {
                        MiuixText("… 其余 ${group.items.size - 25} 个", style = MiuixTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(spacing.sm))
        }
        MiuixSectionCard(title = "解码后的 XML") {
            Column(Modifier.padding(spacing.sm)) {
                MiuixCodeText(code = manifest.xml, language = CodeLanguage.TEXT, fontSize = 11.sp, lineHeight = 18.sp)
            }
        }
    }
}

@Composable
private fun ResourcePane(session: DecompileSession, onShow: (String) -> Unit) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val resources = session.resources
    if (resources == null) {
        MiuixEmptyState(
            title = "没有资源表",
            description = "该 APK 没有 resources.arsc（可能是纯 dex 或 split APK）。",
            icon = Icons.Outlined.ErrorOutline,
        )
        return
    }
    Column {
        MiuixSectionCard(title = "资源类型", subtitle = "共 ${resources.entries.size} 项资源") {
            Column(Modifier.padding(spacing.md)) {
                resources.typeCounts.take(24).forEach { (type, count) ->
                    MiuixListItem(
                        title = type,
                        subtitle = "$count 项",
                        leadingIcon = Icons.AutoMirrored.Outlined.Article,
                        onClick = { onShow("资源类型 $type 共 $count 项") },
                    )
                }
            }
        }
        Spacer(Modifier.height(spacing.sm))
        MiuixSectionCard(title = "资源条目（前 60 项）") {
            Column(Modifier.padding(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
                resources.entries.take(60).forEach { entry ->
                    MiuixText(
                        text = "@${entry.type}/${entry.name} = ${entry.value}",
                        style = MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (entry.complex) colors.onSurfaceVariant else colors.onSurface,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchPane(
    session: DecompileSession,
    query: String,
    onQuery: (String) -> Unit,
    onShow: (SearchHit) -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val index = remember(session) {
        com.mcp.toolbox.feature.decompile.engine.DexIndex(
            classes = session.classes,
            stringConstants = session.stringPool,
            dexEntries = session.summary.dexEntries,
        )
    }
    val hits = remember(index, query) { if (query.isBlank()) emptyList() else index.search(query, 60) }
    Column {
        MiuixSectionCard(title = "全局搜索", subtitle = "类名 / 方法签名 / 字段 / 字符串常量（正则可用）") {
            Column(Modifier.padding(spacing.md)) {
                MiuixSearchField(
                    value = query,
                    onValueChange = onQuery,
                    placeholder = "例如 Activity、https:// 、Regex: ^get.*Name$",
                )
                Spacer(Modifier.height(spacing.sm))
                MiuixText(
                    text = if (query.isBlank()) "输入关键词开始搜索" else "命中 ${hits.size} 条（最多 60）",
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(spacing.sm))
                hits.take(30).forEach { hit ->
                    MiuixListItem(
                        title = hit.text.take(120),
                        subtitle = "${hit.kind} · ${hit.owner} · ${hit.detail}",
                        leadingIcon = Icons.Outlined.Search,
                        onClick = { onShow(hit) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskCenter(
    running: TaskRecord?,
    tasks: List<TaskRecord>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh, RoundedCornerShape(MiuixTheme.radius.lg))
            .padding(vertical = spacing.md),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = spacing.md), verticalAlignment = Alignment.CenterVertically) {
            MiuixIcon(Icons.Outlined.Terminal, null, tint = colors.primary, size = 20.dp)
            Spacer(Modifier.width(spacing.md))
            Column(Modifier.weight(1f)) {
                MiuixText("任务中心", style = MiuixTheme.typography.titleSmall)
                MiuixText(
                    text = running?.let { "${it.engine} · ${it.message}" } ?: "空闲 · 历史 ${tasks.size} 条",
                    style = MiuixTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            MiuixIconButton(
                icon = if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                contentDescription = "展开或收起",
                onClick = onToggle,
            )
        }
        running?.let { task ->
            Spacer(Modifier.height(spacing.sm))
            MiuixLinearProgress(
                progress = task.progress,
                modifier = Modifier.padding(horizontal = spacing.md).fillMaxWidth(),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn(tween(MiuixTheme.motion.fast)) + expandVertically(tween(MiuixTheme.motion.fast)),
            exit = fadeOut(tween(MiuixTheme.motion.fast)) + shrinkVertically(tween(MiuixTheme.motion.fast)),
        ) {
            Column(Modifier.padding(horizontal = spacing.md)) {
                Spacer(Modifier.height(spacing.sm))
                if (tasks.isEmpty()) {
                    MiuixText("暂无任务", style = MiuixTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                tasks.take(6).forEach { task ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        MiuixIcon(
                            icon = when (task.state) {
                                TaskState.DONE -> Icons.Outlined.CheckCircle
                                TaskState.FAILED -> Icons.Outlined.ErrorOutline
                                else -> Icons.Outlined.Terminal
                            },
                            contentDescription = null,
                            tint = when (task.state) {
                                TaskState.DONE -> colors.success
                                TaskState.FAILED -> colors.error
                                else -> colors.primary
                            },
                            size = 14.dp,
                        )
                        Spacer(Modifier.width(spacing.sm))
                        MiuixText(
                            text = formatter.format(Date(task.startedAt)) + " · " + DecompileEngineHistoryLine(task),
                            modifier = Modifier.weight(1f),
                            style = MiuixTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}

private fun DecompileEngineHistoryLine(record: TaskRecord): String = buildString {
    append("${record.engine} · ${record.apkName} · ")
    when (record.state) {
        TaskState.DONE -> {
            append("完成")
            if (record.classCount > 0) append(" · 类 ${record.classCount}/方法 ${record.methodCount}")
            if (record.elapsedMs > 0) append(" · ${record.elapsedMs}ms")
        }
        TaskState.FAILED -> append("失败 · ${record.error ?: "未知错误"}")
        TaskState.RUNNING -> append("进行中 · ${record.message}")
        TaskState.QUEUED -> append("排队中")
        TaskState.CANCELLED -> append("已取消")
    }
}

@Composable
private fun InstalledAppSheet(
    context: Context,
    onDismiss: () -> Unit,
    onPick: (InstalledApks.Entry) -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<InstalledApks.Entry>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { InstalledApks.list(context) }
    }
    val filtered = remember(apps, query) {
        if (query.isBlank()) apps.take(80)
        else apps.filter { it.appLabel.contains(query, true) || it.packageName.contains(query, true) }.take(80)
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .background(colors.surfaceContainerHigh, RoundedCornerShape(MiuixTheme.radius.dialog))
                .padding(spacing.md)
                .fillMaxWidth()
                .fillMaxHeight(0.75f),
        ) {
            MiuixText("选择已安装应用", style = MiuixTheme.typography.titleMedium)
            Spacer(Modifier.height(spacing.sm))
            MiuixSearchField(value = query, onValueChange = { query = it }, placeholder = "搜索应用名或包名")
            Spacer(Modifier.height(spacing.sm))
            LazyColumn(Modifier.weight(1f)) {
                items(filtered) { entry ->
                    MiuixListItem(
                        title = entry.appLabel + if (entry.isSelf) "（本应用）" else "",
                        subtitle = entry.packageName,
                        leadingIcon = Icons.Outlined.Widgets,
                        onClick = { onPick(entry) },
                        showDivider = true,
                    )
                }
            }
            Spacer(Modifier.height(spacing.sm))
            MiuixText(
                text = "已列出 ${filtered.size} / ${apps.size} 个应用（仅三方应用，含本应用）",
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

package com.mcp.toolbox.feature.files

import android.os.Environment
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixFab
import com.mcp.toolbox.core.design.component.MiuixFilterChip
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixSearchField
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.component.TypeColorTile
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mcp.toolbox.core.common.FileOps
import com.mcp.toolbox.core.common.FileOpResult
import com.mcp.toolbox.core.design.component.MiuixBottomSheet
import androidx.core.content.FileProvider
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.foundation.text.BasicTextField
import android.os.Looper
import android.os.Handler
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.ui.platform.LocalContext
import com.mcp.toolbox.core.common.PrivilegeManager
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonVariant

/** 文件类型：决定列表左侧色块的颜色与图标。 */
enum class FileKind { FOLDER, DOC, IMAGE, ARCHIVE, PACKAGE, CODE, VIDEO, AUDIO, UNKNOWN }

data class FileEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val kind: FileKind,
)

private val DOC_EXT = setOf("txt", "md", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv")
private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "svg", "avif")
private val VIDEO_EXT = setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp", "m4v", "ts")
private val AUDIO_EXT = setOf("mp3", "flac", "wav", "aac", "ogg", "m4a", "opus", "amr", "mid")
private val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "bz2", "xz", "jar", "aar", "tgz", "zst", "iso", "tbz", "tbz2", "txz", "lzma")

/** 安装包单独成类：它们本质也是 zip，但用户要的是「能装的应用」而不是「压缩包」。 */
private val PACKAGE_EXT = setOf("apk", "apks", "xapk", "apkm", "aab")
private val CODE_EXT = setOf("kt", "java", "smali", "dex", "json", "xml", "js", "ts", "py", "sh", "sql", "yml", "yaml", "toml", "gradle", "pro", "html", "css")

private fun extensionOf(name: String): String =
    name.substringAfterLast('.', "").lowercase(Locale.ROOT)

internal fun kindOf(name: String, isDir: Boolean): FileKind = when {
    isDir -> FileKind.FOLDER
    extensionOf(name) in PACKAGE_EXT -> FileKind.PACKAGE
    extensionOf(name) in IMAGE_EXT -> FileKind.IMAGE
    extensionOf(name) in VIDEO_EXT -> FileKind.VIDEO
    extensionOf(name) in AUDIO_EXT -> FileKind.AUDIO
    extensionOf(name) in ARCHIVE_EXT -> FileKind.ARCHIVE
    extensionOf(name) in DOC_EXT -> FileKind.DOC
    extensionOf(name) in CODE_EXT -> FileKind.CODE
    else -> FileKind.UNKNOWN
}
@Composable
private fun kindColor(kind: FileKind): Color = when (kind) {
    FileKind.FOLDER -> MiuixTheme.colors.primary
    FileKind.DOC -> MiuixTheme.colors.success
    FileKind.IMAGE -> MiuixTheme.colors.tertiary
    FileKind.ARCHIVE -> MiuixTheme.colors.warning
    FileKind.PACKAGE -> MiuixTheme.colors.primary
    FileKind.CODE -> MiuixTheme.colors.secondary
    FileKind.VIDEO -> MiuixTheme.colors.tertiary
    FileKind.AUDIO -> MiuixTheme.colors.secondary
    FileKind.UNKNOWN -> MiuixTheme.colors.outline
}

private fun kindIcon(kind: FileKind): ImageVector = when (kind) {
    FileKind.FOLDER -> Icons.Outlined.Folder
    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.ARCHIVE -> Icons.Outlined.Archive
    FileKind.PACKAGE -> Icons.Outlined.Android
    FileKind.DOC -> Icons.Outlined.Description
    FileKind.VIDEO -> Icons.Outlined.Movie
    FileKind.AUDIO -> Icons.Outlined.MusicNote
    FileKind.CODE, FileKind.UNKNOWN -> Icons.Outlined.InsertDriveFile
}

internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

internal fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

/** 演示数据：未授予存储权限时展示，界面上会显式标注。 */
private fun demoEntries(root: String): List<FileEntry> {
    val now = System.currentTimeMillis()
    fun entry(name: String, dir: Boolean, size: Long, hoursAgo: Long) = FileEntry(
        name = name,
        path = "$root/$name",
        isDir = dir,
        size = size,
        modified = now - hoursAgo * 3600_000L,
        kind = kindOf(name, dir),
    )
    return listOf(
        entry("Download", true, 0, 2),
        entry("Documents", true, 0, 26),
        entry("Pictures", true, 0, 30),
        entry("base.apk", false, 18_240_000, 5),
        entry("capture_20241106.har", false, 2_411_000, 9),
        entry("screenshot.png", false, 862_000, 12),
        entry("crash.log", false, 41_000, 20),
        entry("project.tar.gz", false, 9_120_000, 44),
    )
}

/** 目录来源：决定是否需要提示用户授权。 */
private enum class FileSource { DIRECT, ROOT, DEMO }

private data class DirListing(val entries: List<FileEntry>, val source: FileSource)

/** Scoped Storage 下无权限时 listFiles() 会返回空数组而不是 null，所以要先 canRead() 判断。 */
private fun listViaFileApi(path: String): List<FileEntry>? {
    val dir = File(path)
    if (!dir.canRead()) return null
    val children = dir.listFiles() ?: return null
    return children.map { file ->
        FileEntry(
            name = file.name,
            path = file.absolutePath,
            isDir = file.isDirectory,
            size = if (file.isDirectory) 0L else file.length(),
            modified = file.lastModified(),
            kind = kindOf(file.name, file.isDirectory),
        )
    }.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase(Locale.ROOT) })
}

/** 单引号安全包装，避免路径里的引号截断 shell 命令。 */
private fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

/**
 * 无「所有文件访问」权限时的兜底：用 root 列目录。
 * 取「路径|字节|秒级时间|类型」四段，避免解析 ls 的对齐、中文列宽和空格文件名。
 *
 * 注意：这里用 shell glob 而不是 find。toybox 的 find 不支持 -maxdepth，
 * 会让 -exec 一次都不触发并静默返回空输出，看起来像「目录是空的」。
 */
private fun listViaRoot(path: String): List<FileEntry>? {
    val result = PrivilegeManager.exec(
        command = "for f in " + shellQuote(path) + "/* " + shellQuote(path) + "/.[!.]*; " +
            "do [ -e \"\$f\" ] && stat -c '%n|%s|%Y|%F' \"\$f\"; done 2>/dev/null",
        timeoutMs = 15_000L,
    )
    // 读不到就返回 null 交给上层继续回退，不要假装成「空目录」。
    if (!result.ok) return null
    val out = result.stdout.trim()
    if (out.isEmpty()) return null
    return out.lines().mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size < 4) return@mapNotNull null
        val full = parts[0]
        val name = full.trimEnd('/').substringAfterLast('/')
        if (name.isEmpty()) return@mapNotNull null
        val isDir = parts[3].contains("directory")
        FileEntry(
            name = name,
            path = full,
            isDir = isDir,
            size = if (isDir) 0L else parts[1].toLongOrNull() ?: 0L,
            modified = (parts[2].toLongOrNull() ?: 0L) * 1000L,
            kind = kindOf(name, isDir),
        )
    }.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase(Locale.ROOT) })
}

/**
 * 三级回退：直接读 → root 读 → 演示数据。
 * 直接读返回空也要继续试 root：Scoped Storage 下 canRead() 可能为 true，
 * 但 listFiles() 只给出可见的那一部分（根目录尤其明显）。
 */
private fun listDirectory(path: String): DirListing {
    val direct = listViaFileApi(path)
    // 直接读能拿到文件才算可信；有些 ROM 的 FUSE 只肯给出目录，
    // 这时「文档 / 图片」筛选会永远为空，必须让 root 兜底。
    if (direct != null && direct.any { !it.isDir }) return DirListing(direct, FileSource.DIRECT)
    listViaRoot(path)?.let { return DirListing(it, FileSource.ROOT) }
    if (direct != null && direct.isNotEmpty()) return DirListing(direct, FileSource.DIRECT)
    return DirListing(demoEntries(path), FileSource.DEMO)
}

/** 引导到系统「所有文件访问」页；R 以下退化为应用详情页。 */
internal fun openStorageSettings(context: Context, onToast: (String) -> Unit) {
    val uri = Uri.parse("package:${context.packageName}")
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri)
    } else {
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri)
    }
    runCatching { context.startActivity(intent) }.onFailure { onToast("无法打开系统授权页") }
}

/**
 * 文件管理器：读取外部存储真实目录；无权限时降级为演示数据并明确提示。
 * 目录进入、面包屑返回、类型筛选、多选批量操作均已接通。
 */
@Composable
fun FilesScreen(
    onOpenDrawer: () -> Unit,
    modifier: Modifier = Modifier,
    onToast: (String) -> Unit = {},
    onOpenViewer: (String) -> Unit = {},
    onDecompile: (String) -> Unit = {},
    onInstallApk: (String) -> Unit = {},
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val root: String = remember { Environment.getExternalStorageDirectory().absolutePath }
    val context = LocalContext.current

    var currentPath by remember { mutableStateOf(root) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var source by remember { mutableStateOf(FileSource.DIRECT) }
    var reloadKey by remember { mutableStateOf(0) }
    var tab by remember { mutableStateOf("全部") }
    var query by remember { mutableStateOf("") }
    val haptics = LocalHapticFeedback.current
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var menuOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FileEntry?>(null) }
    var transferMode by remember { mutableStateOf<String?>(null) }
    var newFolderOpen by remember { mutableStateOf(false) }
    var moreSheetOpen by remember { mutableStateOf(false) }
    var apkTarget by remember { mutableStateOf<FileEntry?>(null) }
    var detailsTarget by remember { mutableStateOf<FileEntry?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var firstResume by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(currentPath, reloadKey) {
        val listing = withContext(Dispatchers.IO) { listDirectory(currentPath) }
        source = listing.source
        entries = listing.entries
        selected = emptySet()
    }

    // 从系统「所有文件访问」页返回时自动重读：授权后立刻接管真实目录，不必手动刷新。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (firstResume) firstResume = false else reloadKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 侧滑 / 返回键先回上一级目录，而不是直接退回首页。
    BackHandler(enabled = selecting || currentPath != root) {
        if (selecting) {
            selecting = false
            selected = emptySet()
        } else {
            currentPath = parentPathOf(currentPath, root)
        }
    }

    /** 执行一次真实写操作：完成后收起选择态并刷新列表。 */
    fun runOp(label: String, block: () -> FileOpResult) {
        if (busy != null) return
        busy = label
        scope.launch {
            val result = withContext(Dispatchers.IO) { block() }
            busy = null
            onToast(result.message)
            if (result.ok) {
                selecting = false
                selected = emptySet()
                reloadKey++
            }
        }
    }
    val tabs = listOf("全部", "文档", "图片", "安装包", "压缩包", "最近")
    val visible = entries.filter { entry ->
        val matchesTab = when (tab) {
            "文档" -> entry.kind == FileKind.DOC
            "图片" -> entry.kind == FileKind.IMAGE
            "安装包" -> entry.kind == FileKind.PACKAGE
            "压缩包" -> entry.kind == FileKind.ARCHIVE
            "最近" -> System.currentTimeMillis() - entry.modified < 48 * 3600_000L
            else -> true
        }
        val matchesQuery = query.isBlank() || entry.name.contains(query, ignoreCase = true)
        matchesTab && matchesQuery
    }
    val selectedEntries = entries.filter { selected.contains(it.path) }

    fun open(entry: FileEntry) {
        when {
            entry.isDir -> currentPath = entry.path
            entry.kind == FileKind.PACKAGE -> apkTarget = entry
            entry.kind == FileKind.DOC || entry.kind == FileKind.IMAGE || entry.kind == FileKind.CODE ||
                entry.kind == FileKind.VIDEO || entry.kind == FileKind.AUDIO || entry.kind == FileKind.ARCHIVE ->
                onOpenViewer(entry.path)
            else -> onToast("${entry.name} · ${formatSize(entry.size)}（暂不支持预览）")
        }
    }

    Box(modifier = modifier.fillMaxSize().background(colors.background)) {
        Column(Modifier.fillMaxSize()) {
            MiuixTopAppBar(
                title = if (selecting) "已选 ${selected.size} 项" else "文件",
                navigationIcon = Icons.Outlined.Folder,
                onNavigationClick = onOpenDrawer,
                actions = {
                    MiuixIconButton(Icons.Outlined.Search, "搜索", onClick = {})
                    Box {
                        MiuixIconButton(Icons.Outlined.MoreHoriz, "更多", onClick = { menuOpen = true })
                        MiuixOverflowMenu(expanded = menuOpen, onDismiss = { menuOpen = false }) {
                            MiuixMenuItem("新建文件夹", { menuOpen = false; newFolderOpen = true }, icon = Icons.Outlined.CreateNewFolder)
                            MiuixMenuItem("申请所有文件访问权限", { menuOpen = false; openStorageSettings(context, onToast) }, icon = Icons.Outlined.Android)
                            MiuixMenuItem("按名称排序", { menuOpen = false }, icon = Icons.Outlined.Sort)
                            MiuixMenuItem("全选", {
                                menuOpen = false
                                selecting = true
                                selected = visible.map { it.path }.toSet()
                            }, icon = Icons.Outlined.SelectAll)
                        }
                    }
                },
            )

            Column(Modifier.padding(horizontal = spacing.pageHorizontal)) {
                MiuixSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "搜索当前目录",
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(spacing.md))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(spacing.sm),
                ) {
                    tabs.forEach { item ->
                        MiuixFilterChip(
                            label = item,
                            selected = tab == item,
                            onClick = { tab = item },
                        )
                    }
                }
                Spacer(Modifier.height(spacing.sm))
            }

            // 面包屑：内部存储 / Download / project
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = spacing.pageHorizontal, vertical = spacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val crumbs = remember(currentPath) {
                    val relative = currentPath.removePrefix(root).trim('/')
                    listOf("内部存储") + relative.split('/').filter { it.isNotEmpty() }
                }
                crumbs.forEachIndexed { index, label ->
                    val press = rememberMiuixPressState()
                    MiuixText(
                        text = if (index == crumbs.lastIndex) label else "$label /",
                        modifier = Modifier
                            .miuixClickable(press, index != crumbs.lastIndex) {
                                val segments = crumbs.drop(1).take(index)
                                currentPath = (listOf(root) + segments).joinToString("/")
                            }
                            .padding(vertical = 4.dp, horizontal = 2.dp),
                        style = MiuixTheme.typography.labelMedium,
                        color = if (index == crumbs.lastIndex) colors.onSurfaceVariant else colors.primary,
                    )
                }
            }

            if (source == FileSource.ROOT) {
                val grantPress = rememberMiuixPressState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.pageHorizontal, vertical = spacing.xs)
                        .background(colors.surfaceContainerHigh, RoundedCornerShape(MiuixTheme.radius.sm))
                        .miuixClickable(grantPress, true, onClick = { openStorageSettings(context, onToast) })
                        .padding(horizontal = spacing.md, vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiuixText(
                        text = "未授予「所有文件访问」权限，当前通过 root 读取真实目录",
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    MiuixText(
                        text = "去授权",
                        style = MiuixTheme.typography.labelLarge,
                        color = colors.primary,
                    )
                }
            }
            if (source == FileSource.DEMO) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = spacing.pageHorizontal, vertical = spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MiuixText(
                        text = "未获得存储访问权限，当前展示演示数据",
                        modifier = Modifier.weight(1f),
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.warning,
                    )
                    MiuixButton(
                        text = "去授权",
                        onClick = { openStorageSettings(context, onToast) },
                        variant = MiuixButtonVariant.OUTLINED,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = if (selecting) 132.dp else 96.dp,
                    top = spacing.xs,
                ),
            ) {
                items(visible, key = { it.path }) { entry ->
                    val isSelected = selected.contains(entry.path)
                    FileRow(
                        entry = entry,
                        selected = isSelected,
                        selecting = selecting,
                        onClick = {
                            when {
                                selecting -> selected = if (isSelected) selected - entry.path else selected + entry.path
                                else -> open(entry)
                            }
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            selecting = true
                            selected = selected + entry.path
                        },
                        onToggleSelect = {
                            selecting = true
                            selected = if (isSelected) selected - entry.path else selected + entry.path
                        },
                        color = kindColor(entry.kind),
                    )
                }
                item {
                    if (visible.isEmpty()) {
                        MiuixText(
                            text = "此目录没有匹配的内容",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(spacing.xl),
                            style = MiuixTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (selecting) {
            SelectionActionBar(
                count = selected.size,
                onClose = { selecting = false; selected = emptySet() },
                onAction = { name ->
                    when (name) {
                        "分享" -> sharePaths(context, selectedEntries.map { it.path }, onToast)
                        "移动", "复制" -> transferMode = name
                        "删除" -> pendingDelete = true
                        "重命名" -> {
                            val only = selectedEntries.singleOrNull()
                            if (only == null) onToast("重命名一次只支持一项") else renameTarget = only
                        }
                        "详情" -> detailsTarget = selectedEntries.firstOrNull()
                        "安装" -> selectedEntries.firstOrNull { it.kind == FileKind.PACKAGE }
                            ?.let { onInstallApk(it.path) } ?: onToast("选中的项里没有安装包")
                        "反编译" -> selectedEntries.firstOrNull { it.kind == FileKind.PACKAGE }
                            ?.let { onDecompile(it.path) } ?: onToast("选中的项里没有安装包")
                        "更多" -> moreSheetOpen = true
                    }
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            MiuixFab(
                icon = Icons.Outlined.Add,
                contentDescription = "新建",
                onClick = { onToast("新建菜单：文件夹 / 文本 / 导入") },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = spacing.pageHorizontal, bottom = 88.dp),
            )
        }

        if (busy != null) {
            MiuixText(
                text = busy.orEmpty(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = spacing.xl),
                style = MiuixTheme.typography.labelLarge,
                color = colors.primary,
            )
        }

        if (pendingDelete) {
            FileDialog(
                title = "删除 ${selected.size} 项？",
                message = "此操作不可撤销。",
                confirmText = "删除",
                danger = true,
                onDismiss = { pendingDelete = false },
                onConfirm = {
                    pendingDelete = false
                    val paths = selectedEntries.map { it.path }
                    runOp("正在删除…") { FileOps.delete(paths) }
                },
            )
        }

        renameTarget?.let { target ->
            TextInputDialog(
                title = "重命名",
                label = "新名称",
                initial = target.name,
                onDismiss = { renameTarget = null },
                onConfirm = { value ->
                    renameTarget = null
                    runOp("正在重命名…") { FileOps.rename(target.path, value) }
                },
            )
        }

        transferMode?.let { mode ->
            TextInputDialog(
                title = "$mode ${selected.size} 项到",
                label = "目标目录",
                initial = currentPath,
                onDismiss = { transferMode = null },
                onConfirm = { value ->
                    transferMode = null
                    val paths = selectedEntries.map { it.path }
                    runOp("正在$mode…") {
                        if (mode == "复制") FileOps.copy(paths, value) else FileOps.move(paths, value)
                    }
                },
            )
        }

        if (newFolderOpen) {
            TextInputDialog(
                title = "新建文件夹",
                label = "文件夹名称",
                initial = "",
                onDismiss = { newFolderOpen = false },
                onConfirm = { value ->
                    newFolderOpen = false
                    runOp("正在创建…") { FileOps.createFolder(currentPath, value) }
                },
            )
        }

        if (moreSheetOpen) {
            MiuixBottomSheet(visible = true, onDismiss = { moreSheetOpen = false }) {
                Column(Modifier.fillMaxWidth().padding(spacing.md)) {
                    listOf("复制", "解压", "详情", "安装", "反编译").forEach { item ->
                        MiuixMenuItem(item, {
                            moreSheetOpen = false
                            when (item) {
                                "复制" -> transferMode = "复制"
                                "解压" -> {
                                    val only = selectedEntries.singleOrNull()
                                    when {
                                        only == null -> onToast("解压一次只支持一个压缩包")
                                        only.kind != FileKind.ARCHIVE -> onToast("选中的不是压缩包")
                                        else -> extractArchiveFromPath(context, only.path, onToast)
                                    }
                                }
                                "详情" -> detailsTarget = selectedEntries.firstOrNull()
                                "安装" -> selectedEntries.firstOrNull { it.kind == FileKind.PACKAGE }
                                    ?.let { onInstallApk(it.path) } ?: onToast("选中的项里没有安装包")
                                "反编译" -> selectedEntries.firstOrNull { it.kind == FileKind.PACKAGE }
                                    ?.let { onDecompile(it.path) } ?: onToast("选中的项里没有安装包")
                            }
                        })
                    }
                }
            }
        }

        apkTarget?.let { target ->
            MiuixBottomSheet(visible = true, onDismiss = { apkTarget = null }) {
                Column(Modifier.fillMaxWidth().padding(spacing.md)) {
                    MiuixText(
                        text = target.name,
                        style = MiuixTheme.typography.labelLarge,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(spacing.sm))
                    listOf("安装", "反编译", "详情", "分享").forEach { item ->
                        MiuixMenuItem(item, {
                            apkTarget = null
                            when (item) {
                                "安装" -> onInstallApk(target.path)
                                "反编译" -> onDecompile(target.path)
                                "详情" -> detailsTarget = target
                                "分享" -> sharePaths(context, listOf(target.path), onToast)
                            }
                        })
                    }
                }
            }
        }

        detailsTarget?.let { target ->
            MiuixBottomSheet(visible = true, onDismiss = { detailsTarget = null }) {
                Column(Modifier.fillMaxWidth().padding(spacing.md)) {
                    MiuixText(
                        text = target.name,
                        style = MiuixTheme.typography.labelLarge,
                        color = colors.onSurface,
                    )
                    Spacer(Modifier.height(spacing.sm))
                    MiuixText(
                        text = "路径：" + target.path + "\n大小：" + formatSize(target.size) +
                            "\n修改时间：" + formatTime(target.modified),
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun FileRow(
    entry: FileEntry,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelect: () -> Unit,
    color: Color,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val press = rememberMiuixPressState()
    val background = if (selected) colors.primaryContainer.copy(alpha = 0.55f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .miuixClickable(press, true, onLongClick = onLongClick, onClick = onClick)
            .padding(horizontal = spacing.pageHorizontal, vertical = spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            TypeColorTile(
                icon = kindIcon(entry.kind),
                color = if (selected) colors.primary else color,
            )
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(colors.primary.copy(alpha = 0.72f), RoundedCornerShape(44.dp * 0.28f)),
                    contentAlignment = Alignment.Center,
                ) {
                    MiuixIcon(Icons.Outlined.SelectAll, "已选择", tint = Color.White, size = 20.dp)
                }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = spacing.md),
        ) {
            MiuixText(
                text = entry.name,
                style = MiuixTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                ),
                maxLines = 1,
            )
            MiuixText(
                text = if (entry.isDir) "文件夹 · ${formatTime(entry.modified)}"
                else "${formatSize(entry.size)} · ${formatTime(entry.modified)}",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (selecting) {
            MiuixIcon(
                icon = if (entry.isDir) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                size = 18.dp,
                modifier = Modifier.miuixClickable(rememberMiuixPressState(), true, onClick = onToggleSelect),
            )
        } else {
            MiuixIcon(Icons.Outlined.ChevronRight, null, tint = colors.onSurfaceVariant, size = 18.dp)
        }
    }
    MiuixDivider(startIndent = 76.dp)
}

@Composable
private fun SelectionActionBar(
    count: Int,
    onClose: () -> Unit,
    onAction: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = spacing.pageHorizontal)
            .navigationBarsPadding()
            .padding(bottom = spacing.md)
            .background(colors.surfaceContainerHigh, RoundedCornerShape(MiuixTheme.radius.lg))
            .padding(vertical = spacing.md),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                Triple("分享", Icons.Outlined.Share, false),
                Triple("移动", Icons.Outlined.DriveFileMove, false),
                Triple("删除", Icons.Outlined.Delete, true),
                Triple("重命名", Icons.Outlined.DriveFileRenameOutline, false),
                Triple("更多", Icons.Outlined.MoreHoriz, false),
            ).forEach { (label, icon, danger) ->
                SelectionAction(
                    label = label,
                    icon = icon,
                    danger = danger,
                    enabled = count > 0,
                    onClick = { onAction(label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(spacing.sm))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MiuixText(
                text = "已选 $count 项",
                style = MiuixTheme.typography.labelLarge,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            val press = rememberMiuixPressState()
            Box(
                modifier = Modifier
                    .background(colors.primary, RoundedCornerShape(MiuixTheme.radius.sm))
                    .miuixClickable(press, true, onClick = onClose)
                    .padding(horizontal = spacing.lg, vertical = spacing.sm),
            ) {
                MiuixText(
                    text = "完成",
                    style = MiuixTheme.typography.labelLarge,
                    color = colors.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun SelectionAction(
    label: String,
    icon: ImageVector,
    danger: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val tint = when {
        danger -> colors.error
        else -> colors.onSurface
    }
    val press = rememberMiuixPressState(enabled)
    Column(
        modifier = modifier.miuixClickable(press, enabled, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MiuixIcon(icon, label, tint = if (enabled) tint else colors.onSurfaceVariant, size = 22.dp)
        Spacer(Modifier.height(spacing_4))
        MiuixText(
            text = label,
            style = MiuixTheme.typography.labelSmall,
            color = if (enabled) tint else colors.onSurfaceVariant,
        )
    }
}

private val spacing_4 = 6.dp

/** 上一级目录；已经在根目录时留在根，交给系统退回首页。 */
internal fun parentPathOf(path: String, root: String): String {
    val trimmed = path.trimEnd('/')
    val rootTrimmed = root.trimEnd('/')
    if (trimmed == rootTrimmed) return root
    val parent = trimmed.substringBeforeLast('/', "")
    return if (parent.isEmpty() || parent.length < rootTrimmed.length) root else parent
}

/**
 * 分享选中的文件。
 * 源文件多半只有 root 读得到，所以先复制到应用缓存再交给系统，
 * 这也是 FileProvider 唯一能对外授权的路径。
 */
internal fun sharePaths(context: Context, paths: List<String>, onToast: (String) -> Unit) {
    if (paths.isEmpty()) return
    val main = Handler(Looper.getMainLooper())
    // 只有需要 root 搬运的路径才提示；本地可直接读的走原路径，瞬间完成。
    if (paths.any { !FileOps.canReadDirectly(it) }) {
        main.post { onToast("正在准备分享文件…") }
    }
    Thread {
        var stagedFailure = 0
        val uris = paths.mapNotNull { path ->
            val staged = FileOps.stageForSharing(context, path)
            if (staged == null) {
                stagedFailure++
                return@mapNotNull null
            }
            runCatching {
                FileProvider.getUriForFile(context, context.packageName + ".fileprovider", staged)
            }.getOrNull()
        }
        if (uris.isEmpty()) {
            val hint = if (stagedFailure > 0) {
                "无法准备分享文件：需要 root 或「所有文件访问」权限"
            } else {
                "无法准备分享文件：路径不在可授权范围内"
            }
            main.post { onToast(hint) }
            return@Thread
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = "*/*"
                putExtra(Intent.EXTRA_STREAM, uris.first())
                // clipData 是很多接收方（微信、QQ 等）能拿到读权限的前提
                clipData = ClipData.newUri(context.contentResolver, "shared", uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                val clip = ClipData.newUri(context.contentResolver, "shared", uris.first())
                uris.drop(1).forEach { extra -> clip.addItem(ClipData.Item(extra)) }
                clipData = clip
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, "分享 ${uris.size} 个文件")
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // 必须在主线程启动，且不能加 FLAG_ACTIVITY_NEW_TASK：
        // 带 NEW_TASK 时系统不会把 content:// 的临时读权限传给 chooser 与最终接收方。
        main.post {
            runCatching { context.startActivity(chooser) }
                .onFailure { onToast("没有可用的分享目标") }
        }
    }.start()
}

/** 轻量确认弹窗；项目里的 Miuix 弹窗组件不稳定，这里用原生 Dialog 自绘。 */
@Composable
private fun FileDialog(
    title: String,
    message: String,
    confirmText: String,
    danger: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceContainerHigh, RoundedCornerShape(MiuixTheme.radius.lg))
                .padding(spacing.lg),
        ) {
            MiuixText(
                text = title,
                style = MiuixTheme.typography.labelLarge,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(spacing.sm))
            MiuixText(
                text = message,
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.lg))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                MiuixButton(
                    text = "取消",
                    onClick = onDismiss,
                    variant = MiuixButtonVariant.OUTLINED,
                )
                Spacer(Modifier.width(spacing.sm))
                MiuixButton(text = confirmText, onClick = onConfirm)
            }
        }
    }
}

/** 单行输入弹窗：重命名、新建文件夹、移动 / 复制的目标目录都用它。 */
@Composable
private fun TextInputDialog(
    title: String,
    label: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    var value by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surfaceContainerHigh, RoundedCornerShape(MiuixTheme.radius.lg))
                .padding(spacing.lg),
        ) {
            MiuixText(
                text = title,
                style = MiuixTheme.typography.labelLarge,
                color = colors.onSurface,
            )
            Spacer(Modifier.height(spacing.sm))
            MiuixText(
                text = label,
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(spacing.xs))
            BasicTextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                textStyle = MiuixTheme.typography.bodyMedium.copy(color = colors.onSurface),
                cursorBrush = SolidColor(colors.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background, RoundedCornerShape(MiuixTheme.radius.sm))
                    .padding(horizontal = spacing.md, vertical = spacing.sm),
            )
            Spacer(Modifier.height(spacing.lg))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                MiuixButton(
                    text = "取消",
                    onClick = onDismiss,
                    variant = MiuixButtonVariant.OUTLINED,
                )
                Spacer(Modifier.width(spacing.sm))
                MiuixButton(text = "确定", onClick = { onConfirm(value) })
            }
        }
    }
}

/**
 * 安装 APK：先把文件落到应用缓存拿到 content:// URI，再交给系统安装器。
 * 设备无「所有文件访问」权限时 [FileOps.stageForSharing] 会自动走 root 复制。
 */
fun installApk(context: Context, path: String, onToast: (String) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    Thread {
        val staged = FileOps.stageForSharing(context, path)
        if (staged == null) {
            main.post { onToast("无法准备安装包（需要 root 复制到缓存）") }
            return@Thread
        }
        val uri = runCatching {
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", staged)
        }.getOrNull()
        if (uri == null) {
            main.post { onToast("无法准备安装 URI") }
            return@Thread
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newRawUri("apk", uri)
        }
        // 同样在主线程启动且不加 NEW_TASK，否则安装器读不到安装包
        main.post {
            runCatching { context.startActivity(intent) }
                .onFailure { onToast("没有可用的安装器（设备可能禁止未知来源）") }
        }
    }.start()
}

package com.mcp.toolbox.feature.files

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.common.FileOps
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.theme.MiuixTheme
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicText
import com.mcp.toolbox.core.design.component.CodeLanguage
import com.mcp.toolbox.core.design.component.highlightCode
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.graphics.SolidColor
import com.mcp.toolbox.core.design.component.highlightCodeLine

/** 文本预览上限：再大就不是「看一眼」而是编辑场景了。 */
private const val MAX_TEXT_BYTES = 400 * 1024

/** PDF 一次最多渲染多少页，避免大文档吃光内存。 */
private const val MAX_PDF_PAGES = 40

/** PDF 渲染倍数：2 倍在清晰度与内存之间比较平衡。 */
private const val PDF_SCALE = 2

private val TEXT_EXT = setOf(
    "txt", "md", "markdown", "json", "xml", "yml", "yaml", "toml", "ini", "conf", "cfg",
    "log", "csv", "tsv", "kt", "java", "smali", "js", "ts", "py", "sh", "bash", "c", "h",
    "cpp", "hpp", "cs", "go", "rs", "rb", "php", "sql", "gradle", "pro", "properties",
    "html", "htm", "css",
)
private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "avif")
private val APK_EXT = setOf("apk", "apks", "xapk", "apkm")

private sealed interface ViewerState {
    data object Loading : ViewerState
    data class Text(val content: String, val truncated: Boolean) : ViewerState
    data class Pages(val bitmaps: List<Bitmap>) : ViewerState
    data class Single(val bitmap: Bitmap) : ViewerState
    data class Apk(val size: Long) : ViewerState
    data class Failed(val reason: String) : ViewerState
}

/** 按扩展名推断着色语言；未知类型交给 TEXT，不做多余处理。 */
private fun languageOf(path: String): CodeLanguage = when (path.substringAfterLast('.', "").lowercase()) {
    "json" -> CodeLanguage.JSON
    "sql", "db" -> CodeLanguage.SQL
    "java" -> CodeLanguage.JAVA
    "kt", "kts" -> CodeLanguage.KOTLIN
    "smali" -> CodeLanguage.SMALI
    "http" -> CodeLanguage.HTTP
    else -> CodeLanguage.TEXT
}

private fun sizeOf(path: String): Long =
    runCatching { java.io.File(path).length() }.getOrDefault(0L)

private fun renderPdf(context: Context, path: String): ViewerState {
    val file = FileOps.toLocalFile(context, path)
        ?: return ViewerState.Failed("取不到文件内容：需要 root 或「所有文件访问」权限")
    return runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                val count = minOf(renderer.pageCount, MAX_PDF_PAGES)
                val pages = ArrayList<Bitmap>(count)
                for (index in 0 until count) {
                    renderer.openPage(index).use { page ->
                        val bitmap = Bitmap.createBitmap(
                            page.width * PDF_SCALE,
                            page.height * PDF_SCALE,
                            Bitmap.Config.ARGB_8888,
                        )
                        bitmap.eraseColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        pages.add(bitmap)
                    }
                }
                ViewerState.Pages(pages)
            }
        }
    }.getOrElse { ViewerState.Failed("PDF 解析失败：${it.message ?: it::class.simpleName}") }
}

private fun decodeImage(context: Context, path: String): ViewerState {
    val file = FileOps.toLocalFile(context, path)
        ?: return ViewerState.Failed("取不到文件内容：需要 root 或「所有文件访问」权限")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
    val bitmap = BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply { inSampleSize = sample },
    )
    return if (bitmap != null) ViewerState.Single(bitmap) else ViewerState.Failed("图片解码失败")
}

private fun load(context: Context, path: String, ext: String): ViewerState = when {
    ext in APK_EXT -> ViewerState.Apk(sizeOf(path))
    ext == "pdf" -> renderPdf(context, path)
    ext in IMAGE_EXT -> decodeImage(context, path)
    else -> {
        val text = FileOps.readText(path, MAX_TEXT_BYTES)
        if (text == null) ViewerState.Failed("取不到文件内容：需要 root 或「所有文件访问」权限")
        else ViewerState.Text(text, text.toByteArray().size >= MAX_TEXT_BYTES)
    }
}

/** 扩展名表集中在这里，三种查看器共用。 */
internal val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "webm", "avi", "mov", "flv", "wmv", "3gp", "m4v", "ts", "m2ts", "mpg", "mpeg", "rmvb",
)
internal val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "wav", "aac", "m4a", "ogg", "opus", "wma", "amr", "ape", "mid", "midi",
)
internal val ARCHIVE_EXTENSIONS = setOf(
    "zip", "jar", "war", "ear", "aar", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst", "iso",
    "tbz", "tbz2", "txz", "lzma",
)

/**
 * 按类型分发：视频 / 音频进内置播放器，压缩包进内容浏览，
 * 其余仍走原来的阅读器。调用方只需要给一个路径。
 */
@Composable
fun FileViewerOverlay(
    path: String,
    onClose: () -> Unit,
    onToast: (String) -> Unit = {},
    onInstallApk: (String) -> Unit = {},
    onDecompile: (String) -> Unit = {},
) {
    val name = remember(path) { path.trimEnd('/').substringAfterLast('/') }
    val ext = remember(path) { name.substringAfterLast('.', "").lowercase() }
    when {
        ext in VIDEO_EXTENSIONS || ext in AUDIO_EXTENSIONS ->
            MediaViewerOverlay(path, name, onClose, onToast)
        ext in ARCHIVE_EXTENSIONS ->
            ArchiveViewerOverlay(path, name, onClose, onToast)
        else ->
            FileViewerOverlayInner(path, onClose, onToast, onInstallApk, onDecompile)
    }
}

/**
 * 内置文件阅读器：文本 / PDF / 图片直接看，安装包给安装与反编译入口。
 * 正文取不到时统一走 [FileOps.toLocalFile] / [FileOps.readText] 的 root 兜底。
 */
@Composable
private fun FileViewerOverlayInner(
    path: String,
    onClose: () -> Unit,
    onToast: (String) -> Unit = {},
    onInstallApk: (String) -> Unit = {},
    onDecompile: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colors
    val name = remember(path) { path.trimEnd('/').substringAfterLast('/') }
    val ext = remember(path) { name.substringAfterLast('.', "").lowercase() }
    val isApk = ext in APK_EXT
    var state by remember(path) { mutableStateOf<ViewerState>(ViewerState.Loading) }
    var editing by remember(path) { mutableStateOf(false) }
    var draft by remember(path) { mutableStateOf("") }
    var saving by remember(path) { mutableStateOf(false) }
    var editValue by remember(path) { mutableStateOf(TextFieldValue("")) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    // 保存草稿：FileOps.writeText 内部先直写、失败再走 root 兜底。
    val saveDraft: () -> Unit = {
        if (!saving) {
            saving = true
            scope.launch {
                val result = withContext(Dispatchers.IO) { FileOps.writeText(context, path, draft) }
                saving = false
                onToast(result.message)
                if (result.ok) {
                    editing = false
                    state = withContext(Dispatchers.IO) { load(context, path, ext) }
                }
            }
        }
    }

    LaunchedEffect(path) {
        state = ViewerState.Loading
        state = withContext(Dispatchers.IO) { load(context, path, ext) }
    }

    // 编辑中先退出编辑，再退一次才关页面，避免误丢改动。
    BackHandler(enabled = true) { if (editing) editing = false else onClose() }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        MiuixTopAppBar(
            title = name,
            navigationIcon = Icons.Outlined.Close,
            onNavigationClick = { if (editing) editing = false else onClose() },
            actions = {
                if (isApk) {
                    MiuixIconButton(Icons.Outlined.Android, "安装", onClick = { onInstallApk(path) })
                    MiuixIconButton(Icons.Outlined.Code, "反编译", onClick = { onDecompile(path) })
                }
                val textState = state as? ViewerState.Text
                if (editing) {
                    MiuixIconButton(
                        Icons.Outlined.Check,
                        if (saving) "保存中" else "保存",
                        onClick = { saveDraft() },
                        enabled = !saving,
                    )
                }
                if (textState != null && !editing) {
                    MiuixIconButton(Icons.Outlined.ContentCopy, "复制全文", onClick = {
                        clipboard.setText(AnnotatedString(textState.content))
                        onToast("已复制全文")
                    })
                    MiuixIconButton(Icons.Outlined.Edit, "编辑", onClick = {
                        draft = textState.content
                        editing = true
                    })
                }
                MiuixIconButton(
                    Icons.Outlined.Share,
                    "分享",
                    onClick = { sharePaths(context, listOf(path), onToast) },
                )
            },
        )

        when (val current = state) {
            ViewerState.Loading -> CenterHint("正在读取…")

            is ViewerState.Failed -> CenterHint(current.reason)

            is ViewerState.Text -> if (editing) {
                // Android 15+ 的 edge-to-edge 会让 adjustResize 失效，用 imePadding 把按钮顶到键盘之上。
                Column(Modifier.fillMaxSize().imePadding()) {
                    BasicTextField(
                        value = editValue,
                        onValueChange = { editValue = it; draft = it.text },
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp),
                        textStyle = MiuixTheme.typography.bodyMedium
                            .copy(fontFamily = FontFamily.Monospace, color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MiuixButton(
                            text = "取消",
                            variant = MiuixButtonVariant.TEXT,
                            onClick = { editing = false },
                            modifier = Modifier.weight(1f),
                        )
                        MiuixButton(
                            text = "保存",
                            loading = saving,
                            enabled = !saving,
                            onClick = { saveDraft() },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                        // 点正文任意位置即进入编辑态，等价于顶栏「编辑」。
                        .clickable { draft = current.content; editValue = TextFieldValue(current.content, TextRange.Zero); editing = true },
                ) {
                    if (current.truncated) {
                        MiuixText(
                            text = "内容较大，仅显示前 ${MAX_TEXT_BYTES / 1024} KB",
                            style = MiuixTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    // 按扩展名做语法着色；顶栏仍保留一键「复制全文」。
                    remember(current.content) { current.content.split("\n") }.forEachIndexed { index, line ->
                        BasicText(
                            text = remember(line, path, colors) {
                                highlightCodeLine(line, languageOf(path), colors)
                            },
                            style = MiuixTheme.typography.bodyMedium
                                .copy(fontFamily = FontFamily.Monospace, color = colors.onSurface),
                            modifier = Modifier.fillMaxWidth().clickable {
                                var off = 0
                                val all = current.content.split("\n")
                                for (i in 0 until index) off += all[i].length + 1
                                draft = current.content
                                editValue = TextFieldValue(
                                    current.content,
                                    TextRange(off.coerceIn(0, current.content.length)),
                                )
                                editing = true
                            },
                        )
                    }
                }
            }

            is ViewerState.Pages -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(current.bitmaps) { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White, RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.FillWidth,
                    )
                }
            }

            is ViewerState.Single -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
            ) {
                Image(
                    bitmap = current.bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.White, RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.FillWidth,
                )
            }

            is ViewerState.Apk -> Column(
                Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MiuixText(
                    text = "安装包",
                    style = MiuixTheme.typography.titleMedium,
                    color = colors.onSurface,
                )
                MiuixText(
                    text = "$name · ${formatSize(current.size)}",
                    style = MiuixTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                MiuixText(
                    text = "安装会交给系统安装器确认；想先看看里面有什么，可以直接反编译。",
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiuixButton(text = "安装", onClick = { onInstallApk(path) })
                    MiuixButton(text = "反编译", onClick = { onDecompile(path) })
                }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String) {
    val colors = MiuixTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        MiuixText(
            text = text,
            style = MiuixTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(32.dp),
        )
    }
}

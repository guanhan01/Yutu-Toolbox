package com.mcp.toolbox.feature.files

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.common.FileOps
import com.mcp.toolbox.core.common.PrivilegeManager
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.InsertDriveFile

/** 压缩包总大小上限：超过就不再尝试落到本地缓存。 */
private const val MAX_ARCHIVE_BYTES = 4L * 1024 * 1024 * 1024

/** 压缩包内的一个文件条目。 */
internal data class ArchiveEntry(
    val name: String,
    val size: Long,
    val compressed: Long,
)

internal fun shellQuoted(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private fun iconFor(name: String) = when (name.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic", "svg" -> Icons.Outlined.Image
    "mp3", "wav", "flac", "aac", "ogg", "m4a", "amr" -> Icons.Outlined.MusicNote
    "mp4", "mkv", "avi", "mov", "webm", "3gp", "flv" -> Icons.Outlined.Movie
    "java", "kt", "kts", "smali", "c", "cpp", "h", "py", "js", "ts", "go", "rs", "sh", "xml", "json", "yml", "yaml", "html", "css" -> Icons.Outlined.Code
    "txt", "md", "log", "ini", "conf", "properties", "csv" -> Icons.Outlined.Description
    "apk", "jar", "dex", "so", "zip", "tar", "gz", "7z", "rar" -> Icons.Outlined.Android
    else -> Icons.Outlined.InsertDriveFile
}

private fun humanSize(bytes: Long): String = when {
    bytes < 0 -> "—"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    else -> "%.2f GB".format(bytes / 1024.0 / 1024.0 / 1024.0)
}

/**
 * tar 家族对应的 busybox tar 压缩标志；不是 tar 容器则返回 null。
 * 只认真正的 tar，纯 .gz / .xz 单文件不在此列。
 */
private fun tarFlagOf(fileName: String): String? {
    val lower = fileName.lowercase()
    return when {
        lower.endsWith(".tar") -> ""
        lower.endsWith(".tgz") || lower.endsWith(".tar.gz") -> "z"
        lower.endsWith(".tbz") || lower.endsWith(".tbz2") || lower.endsWith(".tar.bz2") -> "j"
        lower.endsWith(".txz") || lower.endsWith(".tar.xz") -> "J"
        else -> null
    }
}

/** 读出压缩包条目；格式不支持时返回 null。按容器类型分派。 */
private fun listArchive(local: File): List<ArchiveEntry>? {
    val flag = tarFlagOf(local.name)
    return if (flag != null) listTarArchive(local, flag) else listZipArchive(local)
}

private fun listZipArchive(local: File): List<ArchiveEntry>? = runCatching {
    ZipFile(local).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory }
            .map { ArchiveEntry(it.name, it.size, it.compressedSize) }
            .sortedBy { it.name }
            .toList()
    }
}.getOrNull()

/**
 * 用系统 tar 列条目。设备上的 /system/bin/tar 是 BusyBox，带 z/j/J/lzma，
 * 因此 tar、tgz、tar.bz2、tar.xz 都能读；zip 家族仍走 java.util.zip。
 */
private fun listTarArchive(local: File, flag: String): List<ArchiveEntry>? {
    val result = PrivilegeManager.exec(
        "/system/bin/tar -tv" + flag + "f " + shellQuoted(local.absolutePath),
        timeoutMs = 120_000L,
    )
    if (!result.ok) return null
    val regex = Regex("""^(\S+)\s+\S+\s+(\d+)\s+\S+\s+\S+\s+(.*)$""")
    val listed = result.stdout.lineSequence()
        .mapNotNull { line ->
            val match = regex.find(line) ?: return@mapNotNull null
            val name = match.groupValues[3].trim()
            if (match.groupValues[1].startsWith("d") || name.endsWith("/")) return@mapNotNull null
            ArchiveEntry(name, match.groupValues[2].toLongOrNull() ?: -1L, -1L)
        }
        .sortedBy { it.name }
        .toList()
    return listed.ifEmpty { null }
}

/**
 * 解压到 [destDir]：先在应用缓存里解开，再用特权把结果搬到目标目录。
 * 直接写目标目录会受 SAF 与 shell 权限限制，走缓存中转最稳。
 */
internal fun extractArchiveTo(context: Context, local: File, destDir: String): Boolean = runCatching {
    val staging = File(context.cacheDir, "extract/" + System.currentTimeMillis())
    staging.deleteRecursively()
    if (!staging.mkdirs()) return false
    val base = staging.canonicalPath
    val tarFlag = tarFlagOf(local.name)
    // 需要 zip slip 校验的只有 zip 分支；tar 交给系统 tar 自己解。
    val written = if (tarFlag != null) {
        val result = PrivilegeManager.exec(
            "/system/bin/tar -x" + tarFlag + "f " + shellQuoted(local.absolutePath) +
                " -C " + shellQuoted(staging.absolutePath),
            timeoutMs = 300_000L,
        )
        if (result.ok) staging.walkTopDown().count { it.isFile } else 0
    } else {
        var count = 0
        ZipFile(local).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                val out = File(staging, entry.name)
                if (!out.canonicalPath.startsWith(base + File.separator)) continue
                out.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                count++
            }
        }
        count
    }
    if (written == 0) {
        staging.deleteRecursively()
        return false
    }
    val command = "mkdir -p " + shellQuoted(destDir) +
        " && cp -rf " + shellQuoted(staging.absolutePath + "/.") + " " + shellQuoted(destDir) + "/" +
        " && chmod -R 755 " + shellQuoted(destDir)
    val result = PrivilegeManager.exec(command, timeoutMs = 300_000L)
    staging.deleteRecursively()
    result.ok
}.getOrDefault(false)

/** 把压缩包里的单个条目解到缓存，供内置阅读器打开。 */
private fun extractOne(context: Context, local: File, entry: ArchiveEntry): File? = runCatching {
    val out = File(File(context.cacheDir, "unzip"), entry.name.substringAfterLast('/'))
    out.parentFile?.mkdirs()
    val tarFlag = tarFlagOf(local.name)
    if (tarFlag != null) {
        // -O 把单个条目写到 stdout，这里重定向落盘：
        // 二进制内容不能走 exec 的字符串返回，会被截断/损坏。
        PrivilegeManager.exec(
            "/system/bin/tar -x" + tarFlag + "Of " + shellQuoted(local.absolutePath) +
                " " + shellQuoted(entry.name) + " > " + shellQuoted(out.absolutePath),
            timeoutMs = 120_000L,
        )
    } else {
        ZipFile(local).use { zip ->
            val target = zip.getEntry(entry.name)
            if (target == null) return null
            zip.getInputStream(target).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }
    if (out.exists() && out.length() > 0L) out else null
}.getOrNull()

private fun extractEntryTo(context: Context, local: File, entryName: String, destDir: String): Boolean = runCatching {
    val staging = File(context.cacheDir, "extract/" + System.currentTimeMillis())
    staging.deleteRecursively()
    if (!staging.mkdirs()) return false
    val out = File(staging, entryName.substringAfterLast('/'))
    val tarFlag = tarFlagOf(local.name)
    if (tarFlag != null) {
        PrivilegeManager.exec("/system/bin/tar -x" + tarFlag + "Of " + shellQuoted(local.absolutePath) + " " + shellQuoted(entryName) + " > " + shellQuoted(out.absolutePath), timeoutMs = 120_000L)
    } else {
        ZipFile(local).use { zip ->
            val target = zip.getEntry(entryName) ?: return false
            zip.getInputStream(target).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
    }
    if (!out.exists() || out.length() == 0L) { staging.deleteRecursively(); return false }
    val cmd = "mkdir -p " + shellQuoted(destDir) + " && cp -rf " + shellQuoted(out.absolutePath) + " " + shellQuoted(destDir) + "/ && chmod -R 755 " + shellQuoted(destDir)
    val r = PrivilegeManager.exec(cmd, timeoutMs = 120_000L)
    staging.deleteRecursively()
    r.ok
}.getOrDefault(false)

internal fun extractEntryFromPath(context: Context, path: String, entryName: String, onToast: (String) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    Thread {
        val nm = path.trimEnd('/').substringAfterLast('/')
        val destDir = path.substringBeforeLast('/') + "/" + nm.substringBeforeLast('.', nm)
        val local = FileOps.toLocalFile(context, path, namespace = "archive", maxBytes = MAX_ARCHIVE_BYTES)
        if (local == null) {
            main.post { onToast("打不开压缩包：需要 root 或「所有文件访问」权限") }
            return@Thread
        }
        val ok = extractEntryTo(context, local, entryName, destDir)
        main.post {
            val short = entryName.substringAfterLast('/')
            onToast(if (ok) "已解出 " + short + " 到 " + destDir.substringAfterLast('/') + "/" else "解压失败：目标目录不可写")
        }
    }.start()
}

private val PREVIEWABLE = setOf(
    "txt", "md", "json", "xml", "log", "kt", "java", "js", "ts", "py", "sh", "yml", "yaml", "csv", "html",
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "pdf",
)

/**
 * 压缩包内容浏览：列条目、点开单个文件预览、一键解压到同级同名目录。
 * 目前支持 zip 家族（zip / jar / aar / war），rar、7z 等会明确提示不支持。
 */
@Composable
fun ArchiveViewerOverlay(
    path: String,
    name: String,
    onClose: () -> Unit,
    onToast: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val scope = rememberCoroutineScope()

    var entries by remember(path) { mutableStateOf<List<ArchiveEntry>?>(null) }
    var localFile by remember(path) { mutableStateOf<File?>(null) }
    var problem by remember(path) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            val local = FileOps.toLocalFile(context, path, namespace = "archive", maxBytes = MAX_ARCHIVE_BYTES)
            if (local == null) {
                problem = "打不开压缩包：需要 root 或「所有文件访问」权限"
                return@withContext
            }
            localFile = local
            val listed = listArchive(local)
            when {
                listed == null -> problem = "暂不支持这种压缩格式（支持 zip 家族与 tar / tgz / tar.bz2 / tar.xz）"
                listed.isEmpty() -> problem = "压缩包里没有文件"
                else -> entries = listed
            }
        }
    }

    val destDir = remember(path, name) { path.substringBeforeLast('/') + "/" + name.substringBeforeLast('.', name) }
    val extractAll: () -> Unit = {
        val local = localFile
        if (local == null) {
            onToast("压缩包还没准备好")
        } else {
            busy = "正在解压…"
            scope.launch {
                val ok = withContext(Dispatchers.IO) { extractArchiveTo(context, local, destDir) }
                busy = null
                onToast(if (ok) "已解压到 " + destDir.substringAfterLast('/') + "/" else "解压失败：目标目录不可写")
            }
        }
    }

    // 预览单个条目时叠一层内置阅读器。
    val staged = preview
    if (staged != null) {
        FileViewerOverlay(path = staged, onClose = { preview = null }, onToast = onToast)
        return
    }

    BackHandler(enabled = true) { onClose() }

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
            onNavigationClick = onClose,
            actions = {
                if (entries != null) {
                    MiuixIconButton(Icons.Outlined.Unarchive, "解压到同级目录", onClick = extractAll)
                }
            },
        )
        busy?.let { label ->
            MiuixText(
                label,
                style = MiuixTheme.typography.labelMedium,
                color = colors.primary,
                modifier = Modifier.padding(horizontal = spacing.pageHorizontal, vertical = spacing.xs),
            )
        }
        val list = entries
        if (list == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MiuixText(
                    problem ?: "正在读取压缩包…",
                    style = MiuixTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 4,
                    modifier = Modifier.padding(horizontal = spacing.xl),
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.pageHorizontal, vertical = spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiuixText(
                    list.size.toString() + " 个文件 · 解压后 " + humanSize(list.sumOf { it.size }),
                    style = MiuixTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                MiuixButton(text = "解压全部", onClick = extractAll)
            }
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = spacing.pageHorizontal,
                    end = spacing.pageHorizontal,
                    bottom = spacing.xxl,
                ),
                verticalArrangement = Arrangement.spacedBy(spacing.xs),
            ) {
                items(list, key = { it.name }) { item ->
                    ArchiveRow(
                        item = item,
                        onExtract = { extractEntryFromPath(context, path, item.name, onToast) },
                        onOpen = {
                            val ext = item.name.substringAfterLast('.', "").lowercase()
                            val local = localFile
                            if (ext !in PREVIEWABLE) {
                                onToast("这类文件不支持预览，可先解压再用其他应用打开")
                            } else if (local == null) {
                                onToast("压缩包还没准备好")
                            } else {
                                busy = "正在解出…"
                                scope.launch {
                                    val out = withContext(Dispatchers.IO) { extractOne(context, local, item) }
                                    busy = null
                                    if (out == null) onToast("解出失败") else preview = out.absolutePath
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ArchiveRow(item: ArchiveEntry, onOpen: () -> Unit, onExtract: () -> Unit) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiuixIconButton(iconFor(item.name), item.name, onClick = onOpen)
        Spacer(Modifier.width(spacing.sm))
        Column(Modifier.weight(1f)) {
            MiuixText(item.name, style = MiuixTheme.typography.bodyMedium, color = colors.onSurface, maxLines = 1)
            MiuixText(
                humanSize(item.size) + " · 压缩后 " + humanSize(item.compressed),
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(spacing.sm))
        MiuixIconButton(Icons.Outlined.Unarchive, "解出这个文件到同级目录", onClick = onExtract)
    }
}

/** 从原始路径直接解压，供列表页「解压」菜单调用；线程切换与权限兜底都在内部。 */
internal fun extractArchiveFromPath(context: Context, path: String, onToast: (String) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    Thread {
        val name = path.trimEnd('/').substringAfterLast('/')
        val destDir = path.substringBeforeLast('/') + "/" + name.substringBeforeLast('.', name)
        val local = FileOps.toLocalFile(context, path, namespace = "archive", maxBytes = MAX_ARCHIVE_BYTES)
        if (local == null) {
            main.post { onToast("打不开压缩包：需要 root 或「所有文件访问」权限") }
            return@Thread
        }
        val ok = extractArchiveTo(context, local, destDir)
        main.post {
            onToast(if (ok) "已解压到 " + destDir.substringAfterLast('/') + "/" else "解压失败：目标目录不可写")
        }
    }.start()
}

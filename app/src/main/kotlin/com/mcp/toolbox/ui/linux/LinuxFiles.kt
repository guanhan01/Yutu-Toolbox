package com.mcp.toolbox.ui.linux

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
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
import androidx.compose.ui.window.Dialog
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 打开文件浏览时希望停在哪个目录（相对 rootfs，空表示根）。 */
object LinuxBrowseTarget {
    var initialPath: String = ""
}

/**
 * 这些是 bind mount 进来的宿主目录。
 *
 * 进去等于遍历整个 Android 系统（/proc 有几十万节点），必须挡住。
 * 注意 `sdcard` 不在此列：那是用户自己的存储，浏览它是刚需。
 */
private val BLOCKED_DIRS = setOf("proc", "sys", "dev")

private data class FsEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val readable: Boolean,
)

/** 列目录。全部在 IO 线程调用，别放渲染路径里。 */
private fun readDir(rootfs: File, rel: String): Pair<List<FsEntry>, String> {
    val dir = if (rel.isEmpty()) rootfs else File(rootfs, rel)
    if (!dir.isDirectory) return emptyList<FsEntry>() to "目录不存在，可能环境尚未安装"
    if (!dir.canRead()) return emptyList<FsEntry>() to "没有读取权限"
    val list = dir.listFiles() ?: return emptyList<FsEntry>() to "没有读取权限"
    val entries = list.mapNotNull { file ->
        runCatching {
            FsEntry(
                name = file.name,
                path = if (rel.isEmpty()) file.name else "$rel/${file.name}",
                isDir = file.isDirectory,
                size = if (file.isDirectory) 0L else file.length(),
                modified = file.lastModified(),
                readable = file.canRead(),
            )
        }.getOrNull()
    }.sortedWith(compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() })
    return entries to ""
}

/** 读文本预览。二进制与大文件都只给一句说明。 */
private fun previewOf(file: File): String {
    if (file.length() > 256 * 1024) {
        return "文件较大（${LinuxChecker.humanSize(file.length())}），暂不预览"
    }
    return runCatching {
        val bytes = file.readBytes()
        if (bytes.any { it == 0.toByte() }) "二进制文件，暂不预览" else String(bytes, Charsets.UTF_8)
    }.getOrElse { "无法读取：${it.message ?: "权限不足"}" }
}

/** 浏览 Linux 环境中的文件（只读）。 */
@Composable
fun LinuxFilesScreen(
    distro: LinuxDistro,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootfs = remember(distro) { LinuxEnvStore.rootfs(context, distro) }

    var rel by remember { mutableStateOf(LinuxBrowseTarget.initialPath.trim('/')) }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(rel) {
        loading = true
        val result = withContext(Dispatchers.IO) { readDir(rootfs, rel) }
        entries = result.first
        note = result.second
        loading = false
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))
        MiuixSectionCard(title = "当前位置") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    MiuixText(
                        text = "/" + rel,
                        style = MiuixTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(2.dp))
                    MiuixText(
                        text = if (note.isNotBlank()) {
                            note
                        } else {
                            "${entries.size} 项 · 只读"
                        },
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (rel.isNotEmpty()) {
                    MiuixButton(
                        text = "上级",
                        onClick = { rel = rel.substringBeforeLast('/', "") },
                    )
                }
            }
        }
        Spacer(Modifier.height(spacing.groupGap))

        if (loading) {
            MiuixText(
                text = "读取中…",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        MiuixSectionCard(title = "文件") {
            if (entries.isEmpty() && !loading) {
                MiuixText(
                    text = "这个目录是空的",
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                )
            }
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                items(entries) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.isDir) {
                                    if (entry.name in BLOCKED_DIRS) {
                                        note = "${entry.name} 是宿主的系统目录，已跳过"
                                    } else {
                                        rel = entry.path
                                    }
                                } else {
                                    scope.launch {
                                        val text = withContext(Dispatchers.IO) {
                                            previewOf(File(rootfs, entry.path))
                                        }
                                        preview = entry.name to text
                                    }
                                }
                            }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        MiuixIcon(
                            icon = if (entry.isDir) Icons.Outlined.Folder else Icons.Outlined.Description,
                            contentDescription = null,
                            tint = colors.onSurfaceVariant,
                            size = 20.dp,
                        )
                        Column(Modifier.weight(1f)) {
                            MiuixText(
                                text = entry.name,
                                style = MiuixTheme.typography.bodyMedium,
                            )
                            MiuixText(
                                text = if (entry.isDir) {
                                    "目录"
                                } else {
                                    LinuxChecker.humanSize(entry.size)
                                },
                                style = MiuixTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (!entry.readable) {
                            MiuixTag(text = "无权限", color = colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(32.dp))
    }

    preview?.let { (name, text) ->
        Dialog(onDismissRequest = { preview = null }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                MiuixText(text = name, style = MiuixTheme.typography.bodyLarge)
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    MiuixText(
                        text = text,
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(14.dp))
                MiuixButton(text = "关闭", onClick = { preview = null })
            }
        }
    }
}

/** 共享文件夹：把 Android 目录挂进 Linux 环境。 */
@Composable
fun LinuxSharedScreen(
    distro: LinuxDistro,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val context = LocalContext.current
    val rootfs = remember(distro) { LinuxEnvStore.rootfs(context, distro) }
    var mounts by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var copied by remember { mutableStateOf("") }

    LaunchedEffect(distro) {
        mounts = withContext(Dispatchers.IO) {
            runCatching {
                File("/proc/self/mounts").readLines().mapNotNull { line ->
                    val parts = line.split(" ")
                    if (parts.size < 2) return@mapNotNull null
                    val point = parts[1]
                    if (!point.startsWith(rootfs.absolutePath)) return@mapNotNull null
                    val inner = point.removePrefix(rootfs.absolutePath).trim('/')
                    if (inner.isEmpty()) null else inner to parts[0]
                }.distinct()
            }.getOrDefault(emptyList())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.pageHorizontal),
    ) {
        Spacer(Modifier.height(spacing.sm))
        MiuixSectionCard(
            title = "已挂载的 Android 目录",
            subtitle = "在 Linux 里按左侧路径访问，读写都是双向的",
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                if (mounts.isEmpty()) {
                    MiuixText(
                        text = "还没有挂载。先打开一次终端或环境检测，挂载会自动建立。",
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
                mounts.forEach { (inner, source) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            MiuixText(
                                text = "/$inner",
                                style = MiuixTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(2.dp))
                            MiuixText(
                                text = source,
                                style = MiuixTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        MiuixTag(text = "双向", color = colors.success)
                    }
                }
            }
        }
        Spacer(Modifier.height(spacing.groupGap))

        MiuixSectionCard(title = "怎么用") {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                MiuixText(
                    text = "在 Linux 环境的终端里执行：",
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                MiuixText(
                    text = "ls /sdcard\ncp /sdcard/Download/a.apk /root/",
                    style = MiuixTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                MiuixButton(
                    text = if (copied.isBlank()) "复制 Android 存储路径" else "已复制 $copied",
                    onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("path", "/storage/emulated/0"))
                        copied = "/storage/emulated/0"
                    },
                )
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.text.TextStyle

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
)

private fun shellQuoted(text: String): String = "'" + text.replace("'", "'\\''") + "'"

/**
 * 列目录。必须在 IO 线程调用，别放渲染路径里。
 *
 * 走 root shell 而不是 Java 侧 [File]：应用进程解析 rootfs 里的软链接会被 SELinux
 * 拒绝（`avc: denied { read } ... tclass=lnk_file`，域是 untrusted_app）。Debian 自
 * bookworm 起默认 merged-usr，`/bin`、`/lib`、`/sbin` 都是指向 `usr/bin` 一类的软链接，
 * 全部命中该规则：`File.isDirectory` 恒为 false，于是被当成 0 字节文件，尺寸落进
 * [LinuxChecker.humanSize] 的 0 值分支，显示成「未安装」。
 *
 * 这条规则连 QQ 缓存目录的 `cache` 软链都会拒，属于平台行为，应用侧绕不过去；
 * 而列目录本来就需要 root，交给 root shell 一次问清楚最省事。
 *
 * 每条记录占两行：第一行 `<类型> <字节数> <修改时间>`，第二行文件名。文件名单独
 * 占一行，不必纠结分隔符与文件名冲突（含换行的文件名仍不支持）。
 */
private suspend fun readDir(
    context: Context,
    distro: LinuxDistro,
    rel: String,
): Pair<List<FsEntry>, String> {
    val target = if (rel.isEmpty()) "/" else "/$rel"
    val script = """
        cd ${shellQuoted(target)} 2>/dev/null || exit 3
        ls -1A | while IFS= read -r n; do
          i=${'$'}(stat -L -c '%F %s %Y' "${'$'}n" 2>/dev/null) || i="link 0 ${'$'}(stat -c '%Y' "${'$'}n" 2>/dev/null || echo 0)"
          printf '%s\n%s\n' "${'$'}i" "${'$'}n"
        done
    """.trimIndent()
    val out = LinuxRuntime.exec(context, distro, script, timeoutMs = 30_000)
    if (out.exitCode == 3) {
        return emptyList<FsEntry>() to "目录不存在，可能环境尚未安装"
    }
    if (out.exitCode != 0) {
        return emptyList<FsEntry>() to out.combined.ifBlank { "列目录失败" }
    }

    val lines = out.stdout.lines()
    val entries = mutableListOf<FsEntry>()
    var i = 0
    while (i + 1 < lines.size) {
        val info = lines[i].trim()
        val name = lines[i + 1]
        i += 2
        if (name.isBlank()) continue
        // 类型名可能带空格（`regular file`），所以大小与时间从尾部取
        val fields = info.split(' ')
        val mtime = fields.lastOrNull()?.toLongOrNull() ?: 0L
        val size = fields.getOrNull(fields.size - 2)?.toLongOrNull() ?: 0L
        val isDir = info.startsWith("directory")
        entries += FsEntry(
            name = name,
            path = if (rel.isEmpty()) name else "$rel/$name",
            isDir = isDir,
            size = if (isDir) 0L else size,
            modified = mtime,
        )
    }
    return entries.sortedWith(
        compareByDescending<FsEntry> { it.isDir }.thenBy { it.name.lowercase() },
    ) to ""
}

/**
 * 读文本预览。二进制与大文件都只给一句说明。
 *
 * 与 [readDir] 同理走 root shell：应用进程自己读普通文件没问题，但只要路径里有一段
 * 是软链接，解析就会被 SELinux 拒掉。
 */
private suspend fun previewOf(
    context: Context,
    distro: LinuxDistro,
    path: String,
): String {
    val file = shellQuoted("/$path")
    val script = """
        f=$file
        [ -e "${'$'}f" ] || exit 3
        [ -d "${'$'}f" ] && exit 6
        s=${'$'}(wc -c < "${'$'}f" 2>/dev/null || echo 0)
        if [ "${'$'}s" -gt 262144 ]; then printf 'BIG %s\n' "${'$'}s"; exit 0; fi
        printf 'TXT %s\n' "${'$'}s"
        cat "${'$'}f"
    """.trimIndent()
    val out = LinuxRuntime.exec(context, distro, script, timeoutMs = 30_000)
    val head = out.stdout.lineSequence().firstOrNull().orEmpty()
    val kind = head.substringBefore(' ')
    val size = head.substringAfter(' ', "").trim().toLongOrNull() ?: 0L
    return when {
        out.exitCode == 3 -> "文件不存在或已被删除"
        out.exitCode == 6 -> "这是一个目录"
        kind == "BIG" -> "文件较大（${LinuxChecker.humanSize(size)}），暂不预览"
        kind == "TXT" -> {
            val text = out.stdout.substringAfter('\n', "")
            when {
                text.isEmpty() -> "（空文件）"
                text.contains('\u0000') -> "二进制文件，暂不预览"
                else -> text
            }
        }
        else -> "无法读取：" + out.combined.ifBlank { "未知错误" }
    }
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
    var rel by remember { mutableStateOf(LinuxBrowseTarget.initialPath.trim('/')) }
    var entries by remember { mutableStateOf<List<FsEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(rel) {
        loading = true
        // LinuxRuntime.exec 内部已切到 IO 线程，这里不再套一层 withContext
        val result = readDir(context, distro, rel)
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
                                        preview = entry.name to previewOf(context, distro, entry.path)
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

/** 读当前已生效的挂载。务必在 IO 线程调用。 */
internal fun readMounts(rootfs: File): List<Pair<String, String>> = runCatching {
    File("/proc/self/mounts").readLines().mapNotNull { line ->
        val parts = line.split(" ")
        if (parts.size < 2) return@mapNotNull null
        val point = parts[1]
        // /proc/mounts 记的是解析后的真实路径（/data/data/...），与
        // rootfs.absolutePath（/data/user/0/...）前缀对不上，所以按特征匹配
        if (!point.contains("files/linux-env")) return@mapNotNull null
        val inner = point.substringAfter("/rootfs", "").trim('/')
        if (inner.isEmpty()) null else inner to parts[0]
    }.distinct()
}.getOrDefault(emptyList())

/** 一行输入框。 */
@Composable
private fun MountInput(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit,
) {
    val colors = MiuixTheme.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = TextStyle(
            color = colors.onSurface,
            fontSize = MiuixTheme.typography.bodySmall.fontSize,
        ),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceContainerHighest, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                if (value.isEmpty()) {
                    MiuixText(
                        text = placeholder,
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    inner()
                }
            }
        },
    )
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
    var custom by remember { mutableStateOf(LinuxPrefs.customMounts(context)) }
    var adding by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf("") }

    LaunchedEffect(distro) {
        mounts = withContext(Dispatchers.IO) { readMounts(rootfs) }
        custom = LinuxPrefs.customMounts(context)
    }

    fun save(next: List<Pair<String, String>>) {
        LinuxPrefs.saveCustomMounts(context, next)
        custom = next
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
                mounts.forEach { (inner, _) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            MiuixText(text = "/$inner", style = MiuixTheme.typography.bodyMedium)
                            Spacer(Modifier.height(2.dp))
                            // /proc/mounts 给的是设备名（/dev/block/...），对用户没意义，
                            // 这里换成实际的 Android 来源路径
                            MiuixText(
                                text = when {
                                    inner == "dev" -> "/dev"
                                    inner == "proc" -> "/proc"
                                    inner == "sys" -> "/sys"
                                    inner == "sdcard" -> "/storage/emulated/0"
                                    inner.startsWith("mnt/android/") ->
                                        "/" + inner.removePrefix("mnt/android/")
                                    else -> custom.firstOrNull { it.second == inner }?.first
                                        ?: "自定义目录"
                                },
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

        MiuixSectionCard(
            title = "自定义挂载",
            subtitle = "把任意 Android 目录挂进 Linux 环境",
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                if (custom.isEmpty()) {
                    MiuixText(
                        text = "还没有自定义目录。可以只共享 Download、Pictures 这类特定文件夹。",
                        style = MiuixTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
                custom.forEach { (src, dst) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            MiuixText(text = "/$dst", style = MiuixTheme.typography.bodyMedium)
                            Spacer(Modifier.height(2.dp))
                            MiuixText(
                                text = src,
                                style = MiuixTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        MiuixButton(
                            text = "删除",
                            onClick = { save(custom.filterNot { it.second == dst }) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                ) {
                    MiuixButton(text = "添加目录", onClick = { adding = true })
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

    if (adding) {
        var src by remember { mutableStateOf("") }
        var dst by remember { mutableStateOf("") }
        Dialog(onDismissRequest = { adding = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.background, RoundedCornerShape(16.dp))
                    .padding(16.dp),
            ) {
                MiuixText(text = "添加自定义挂载", style = MiuixTheme.typography.bodyLarge)
                Spacer(Modifier.height(14.dp))
                MiuixText(
                    text = "Android 目录",
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                MountInput(src, "/storage/emulated/0/Download") { src = it }
                Spacer(Modifier.height(12.dp))
                MiuixText(
                    text = "Linux 内的挂载点",
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                MountInput(dst, "mnt/download") { dst = it }
                Spacer(Modifier.height(10.dp))
                MiuixText(
                    text = "保存后打开一次终端或环境检测即可生效。",
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiuixButton(text = "取消", onClick = { adding = false })
                    MiuixButton(
                        text = "保存",
                        onClick = {
                            val s = src.trim()
                            val d = dst.trim().trim('/')
                            if (s.isNotEmpty() && d.isNotEmpty()) {
                                save(custom.filterNot { it.second == d } + (s to d))
                            }
                            adding = false
                        },
                    )
                }
            }
        }
    }
}

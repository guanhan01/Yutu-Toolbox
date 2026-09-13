package com.mcp.toolbox.core.common

import android.content.Context
import java.io.File

/** 一次文件操作的结果。 */
data class FileOpResult(val ok: Boolean, val message: String)

/**
 * 文件读写统一入口。
 *
 * 无「所有文件访问」权限时，app 自己的 File API 对 /storage/emulated/0
 * 的删除、改名、移动会静默失败或抛 SecurityException，
 * 所以这里先试 File API（有权限时零开销），失败再交给 root / Shizuku。
 */
object FileOps {

    /** 单引号安全包装，避免路径里的引号截断 shell 命令。 */
    fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    private fun fileNameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

    private fun parentOf(path: String): String =
        path.trimEnd('/').substringBeforeLast('/', "").ifEmpty { "/" }

    private fun run(command: String, timeoutMs: Long = 30_000L): FileOpResult {
        val result = PrivilegeManager.exec(command, timeoutMs)
        if (result.ok) {
            val tail = result.stdout.trim().lines().lastOrNull { it.isNotBlank() }.orEmpty()
            return FileOpResult(true, tail.ifEmpty { "完成" })
        }
        val reason = result.failure
            ?: result.stderr.trim().lines().lastOrNull { it.isNotBlank() }
            ?: result.stdout.trim().lines().lastOrNull { it.isNotBlank() }
            ?: "操作失败（code=${result.code}）"
        return FileOpResult(false, reason)
    }

    /** 删除文件或目录（递归）。 */
    fun delete(paths: List<String>): FileOpResult {
        if (paths.isEmpty()) return FileOpResult(false, "没有选中文件")
        val direct = paths.all { runCatching { File(it).deleteRecursively() }.getOrDefault(false) }
        if (direct) return FileOpResult(true, "已删除 ${paths.size} 项")
        return run(paths.joinToString(" ") { "rm -rf -- " + shellQuote(it) })
    }

    fun rename(path: String, newName: String): FileOpResult {
        val cleaned = newName.trim()
        if (cleaned.isEmpty()) return FileOpResult(false, "名称不能为空")
        if (cleaned.contains('/')) return FileOpResult(false, "名称不能包含 /")
        val target = parentOf(path) + "/" + cleaned
        if (target == path) return FileOpResult(true, "名称未变化")
        if (File(target).exists()) return FileOpResult(false, "已存在同名项")
        val direct = runCatching { File(path).renameTo(File(target)) }.getOrDefault(false)
        if (direct) return FileOpResult(true, "已重命名为 $cleaned")
        return run("mv -- " + shellQuote(path) + " " + shellQuote(target))
    }

    fun move(paths: List<String>, targetDir: String): FileOpResult {
        if (paths.isEmpty()) return FileOpResult(false, "没有选中文件")
        val dir = targetDir.trim().trimEnd('/').ifEmpty { "/" }
        if (paths.all { parentOf(it) == dir }) return FileOpResult(false, "目标目录和当前目录相同")
        return run(
            "mkdir -p -- " + shellQuote(dir) + " && mv -- " +
                paths.joinToString(" ") { shellQuote(it) } + " " + shellQuote(dir),
        )
    }

    fun copy(paths: List<String>, targetDir: String): FileOpResult {
        if (paths.isEmpty()) return FileOpResult(false, "没有选中文件")
        val dir = targetDir.trim().trimEnd('/').ifEmpty { "/" }
        return run(
            "mkdir -p -- " + shellQuote(dir) + " && cp -r -- " +
                paths.joinToString(" ") { shellQuote(it) } + " " + shellQuote(dir),
        )
    }

    fun createFolder(parent: String, name: String): FileOpResult {
        val cleaned = name.trim()
        if (cleaned.isEmpty()) return FileOpResult(false, "名称不能为空")
        if (cleaned.contains('/')) return FileOpResult(false, "名称不能包含 /")
        val target = parent.trimEnd('/') + "/" + cleaned
        if (File(target).exists()) return FileOpResult(false, "已存在同名项")
        if (runCatching { File(target).mkdirs() }.getOrDefault(false)) {
            return FileOpResult(true, "已创建 $cleaned")
        }
        return run("mkdir -p -- " + shellQuote(target))
    }

    /** 读文本预览，最多 [maxBytes] 字节。 */
    fun readText(path: String, maxBytes: Int = 256 * 1024): String? {
        if (runCatching { File(path).canRead() }.getOrDefault(false)) {
            val text = runCatching { File(path).readText() }.getOrNull()
            if (text != null) return text.take(maxBytes)
        }
        val result = PrivilegeManager.exec(
            "head -c " + maxBytes + " -- " + shellQuote(path),
            timeoutMs = 30_000L,
        )
        return if (result.ok) result.stdout else null
    }

    /**
     * 把文件取到应用私有缓存，供 PdfRenderer / BitmapFactory 这类
     * 只认本地 seekable 路径的 API 使用。已在缓存里的直接复用。
     * 超过 [maxBytes] 视为过大，返回 null。
     */
    fun toLocalFile(
        context: Context,
        path: String,
        namespace: String = "fileview",
        maxBytes: Long = 200L * 1024 * 1024,
    ): File? {
        val src = File(path)
        if (runCatching { src.canRead() && src.isFile }.getOrDefault(false)) return src
        val target = File(File(context.cacheDir, namespace), fileNameOf(path))
        target.parentFile?.mkdirs()
        if (target.exists() && target.length() > 0L) return target
        val result = PrivilegeManager.exec(
            "cp -f -- " + shellQuote(path) + " " + shellQuote(target.absolutePath) +
                " && chmod 644 -- " + shellQuote(target.absolutePath),
            timeoutMs = 120_000L,
        )
        if (!result.ok || !target.exists() || target.length() == 0L) {
            runCatching { target.delete() }
            return null
        }
        if (target.length() > maxBytes) {
            runCatching { target.delete() }
            return null
        }
        return target
    }

    /**
     * 复制到缓存里的分享子目录，返回副本路径；分享 / 安装 APK 用。
     * 上限放宽到 4GB：toLocalFile 默认只有 200MB，会出现
     * 「root 已经复制成功、却因为超限被判为不可分享」的情况。
     */
    fun stageForSharing(context: Context, path: String): File? =
        toLocalFile(context, path, namespace = "share", maxBytes = 4L * 1024 * 1024 * 1024)

    /** 直接判断路径是否可读（不做 root 复制）。 */
    fun canReadDirectly(path: String): Boolean = runCatching {
        val file = File(path)
        file.canRead() && file.isFile
    }.getOrDefault(false)

    /**
     * 写回文本内容。先试 app 自己的 File API（有「所有文件访问」权限时零开销），
     * 失败再把内容落到应用缓存、交给 root 覆盖过去。
     */
    fun writeText(context: Context, path: String, content: String): FileOpResult {
        val target = File(path)
        if (runCatching { target.writeText(content) }.isSuccess) return FileOpResult(true, "已保存")
        val staged = runCatching {
            val dir = File(context.cacheDir, "edit").apply { mkdirs() }
            File(dir, "staged-" + System.currentTimeMillis() + ".tmp").also { it.writeText(content) }
        }.getOrNull() ?: return FileOpResult(false, "保存失败：没有写入权限")
        val result = run(
            "cp -f -- " + shellQuote(staged.absolutePath) + " " + shellQuote(path) +
                " && chmod 644 -- " + shellQuote(path),
        )
        runCatching { staged.delete() }
        return if (result.ok) FileOpResult(true, "已保存") else FileOpResult(false, "保存失败：" + result.message)
    }

}

package com.mcp.toolbox.feature.home

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * 一条待发送的附件。
 *
 * 目前不把二进制内容直接塞进请求，而是在发送时把「路径 + 内容摘要」转成文本上下文，
 * 附带在用户消息之后；这样对各家服务商都通用，也不会一次撑爆请求体。
 */
sealed interface ChatAttachment {
    val label: String

    data class Image(val uri: String, override val label: String) : ChatAttachment
    data class File(val uri: String, override val label: String) : ChatAttachment
    data class Folder(val uri: String, override val label: String) : ChatAttachment
    data class Path(val path: String) : ChatAttachment {
        override val label: String get() = path.substringAfterLast('/').ifBlank { path }

        /**
         * 路径指向目录还是文件。
         *
         * 在构造期判定一次：显示图标要按它分支，写成 getter 会在每次重组时重新 stat 一遍。
         * 刻意不做构造参数——否则它会进入 data class 的 equals/copy，
         * 同一条路径因判定时机不同就可能被当成两个不同的附件。
         */
        val isDirectory: Boolean = runCatching { java.io.File(path).isDirectory }.getOrDefault(false)
    }

    /** 转成给模型看的上下文文本。 */
    fun toContext(context: Context): String = when (this) {
        is Image -> "【图片】$label（路径：$uri）"
        is Folder -> "【文件夹】$label 的内容：\n" + listTree(context, uri).take(2000)
        // 目录路径之前会落进 readPath 的 !isFile 分支，显示成「无法读取」——
        // 明明是个能打开的目录。这里按 isDirectory 分开处理。
        is Path -> if (isDirectory) {
            "【文件夹】$path 的内容：\n" + listDir(java.io.File(path))
        } else {
            readPath(java.io.File(path))?.let { "【文件】$path 的内容：\n$it" }
                ?: "【路径】$path（无法读取）"
        }
        is File -> readUri(context, uri)?.let { "【文件】$label 的内容：\n$it" } ?: "【文件】$label（无法读取）"
    }

    companion object {
        /**
         * 文件夹树 URI → 展示用路径。
         *
         * 取 tree 的 documentId（形如 `primary:Download/sub`），把分隔符还原成 `/`；
         * 存储卷标（`primary`）对用户没有意义，去掉只留真正的目录层级。
         */
        fun folderLabel(uri: Uri): String? {
            val docId = runCatching {
                android.provider.DocumentsContract.getTreeDocumentId(uri)
            }.getOrNull() ?: return null
            val scheme = docId.substringBefore(':', "")
            val rest = docId.substringAfter(':', docId).trimStart('/')
            val path = when (scheme) {
                "", "primary", "raw" -> rest
                // 第三方 provider 的卷标可能带信息，保留
                else -> "$scheme/$rest"
            }
            return path.ifBlank { null }
        }

        /** 从 content URI 取显示名。 */
        fun displayName(context: Context, uri: Uri): String = runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "未命名"

        private fun readUri(context: Context, uri: String): String? = runCatching {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
                val bytes = input.readNBytes(64 * 1024)
                bytes.toString(Charsets.UTF_8)
            }
        }.getOrNull()

        /** 目录内容（最多 200 项），与 [listTree] 的截断口径保持一致。 */
        private fun listDir(dir: java.io.File): String = runCatching {
            dir.listFiles()?.take(200)?.joinToString("\n") { it.name }.orEmpty()
        }.getOrDefault("")

        private fun readPath(file: java.io.File): String? = runCatching {
            if (!file.exists() || !file.isFile) return null
            file.inputStream().use { input ->
                input.readNBytes(64 * 1024).toString(Charsets.UTF_8)
            }
        }.getOrNull()

        private fun listTree(context: Context, treeUri: String): String = runCatching {
            val docId = android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
            val childrenUri = android.provider.DocumentsContract
                .buildChildDocumentsUriUsingTree(Uri.parse(treeUri), docId)
            val names = mutableListOf<String>()
            context.contentResolver.query(childrenUri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext() && names.size < 200) {
                    if (idx >= 0) names += c.getString(idx)
                }
            }
            names.joinToString("\n")
        }.getOrDefault("")
    }
}

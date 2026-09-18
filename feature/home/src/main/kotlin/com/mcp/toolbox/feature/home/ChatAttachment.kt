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
    }

    /** 转成给模型看的上下文文本。 */
    fun toContext(context: Context): String = when (this) {
        is Image -> "【图片】$label（路径：$uri）"
        is Folder -> "【文件夹】$label 的内容：\n" + listTree(context, uri).take(2000)
        is Path -> readPath(java.io.File(path))?.let { "【文件】$path 的内容：\n$it" } ?: "【路径】$path（无法读取）"
        is File -> readUri(context, uri)?.let { "【文件】$label 的内容：\n$it" } ?: "【文件】$label（无法读取）"
    }

    companion object {
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

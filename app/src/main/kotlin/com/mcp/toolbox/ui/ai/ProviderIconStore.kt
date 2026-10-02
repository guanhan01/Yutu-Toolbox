package com.mcp.toolbox.ui.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自定义供应商头像的落盘与读取。
 *
 * 从相册选到的是 SAF URI，授权是临时的：清缓存、换设备、或用户删掉原图后就读不到了。
 * 所以选中时立刻把**压缩后的副本**写进应用私有目录，之后只认这份副本。
 *
 * 顺带把长边压到 [MAX_EDGE]：头像是 40dp 的小圆形，原图动辄 4000px，
 * 直接存下来既占空间，每次解码也会卡顿。
 */
object ProviderIconStore {

    private const val DIR = "provider-icons"
    private const val MAX_EDGE = 256
    private const val JPEG_QUALITY = 88

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { mkdirs() }

    /** 把选中的图片复制进来，返回副本的绝对路径；失败返回 null。 */
    suspend fun import(context: Context, customId: String, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val original = context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)
                } ?: return@runCatching null

                val scaled = scaleDown(original)
                // 文件名带 id：同一供应商换头像直接覆盖，不堆垃圾文件
                val target = File(dir(context), "$customId.jpg")
                target.outputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                }
                if (scaled !== original) original.recycle()
                scaled.recycle()
                target.absolutePath
            }.getOrNull()
        }

    /**
     * 预览一张还没落盘的图（新建供应商时用）。
     *
     * 只解码缩略图，不写文件：此刻还没有 id 作为文件名，写完也没处引用。
     */
    suspend fun loadPreview(context: Context, uri: Uri): ImageBitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()
        }

    /** 读取头像；文件不存在或解不出来时返回 null（调用方退回默认徽标）。 */
    suspend fun load(path: String?): ImageBitmap? = withContext(Dispatchers.IO) {
        if (path.isNullOrBlank()) return@withContext null
        runCatching {
            val file = File(path)
            if (!file.isFile) return@runCatching null
            BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
        }.getOrNull()
    }

    /** 删除某条供应商的头像文件。 */
    suspend fun remove(customId: String, path: String?) = withContext(Dispatchers.IO) {
        runCatching { File(path ?: "").takeIf { it.isFile }?.delete() }
        Unit
    }

    /** 等比缩到长边不超过 [MAX_EDGE]；本来就够小就原样返回。 */
    private fun scaleDown(src: Bitmap): Bitmap {
        val longEdge = maxOf(src.width, src.height)
        if (longEdge <= MAX_EDGE) return src
        val ratio = MAX_EDGE.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            src,
            (src.width * ratio).toInt().coerceAtLeast(1),
            (src.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}

package com.mcp.toolbox.feature.home.agent

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import java.io.File

/**
 * 把一条回答渲染成图片并交给系统分享。
 *
 * ## 为什么用 `GraphicsLayer`
 *
 * Compose 1.7+ 的 `rememberGraphicsLayer()` + `drawLayer()` 能在**不截屏**的前提下
 * 把某个子树的绘制结果抓成 Bitmap：不需要屏幕录制权限、不受其它窗口遮挡影响，
 * 也拿得到比屏幕更宽的画布（长回答可以整段渲染而不是只截可见部分）。
 *
 * 早先「分享为图片」在菜单里是置灰的——那时候我没有可用的捕获路径，
 * 与其做一个「点了没反应」的假按钮，不如让它明确不可用。现在条件具备，补上。
 *
 * ## 为什么必须走 FileProvider
 *
 * `targetSdk >= 24` 之后 `file://` URI 会触发 `FileUriExposedException`。
 * 写进 `cacheDir/share/` 再用 FileProvider 换一个 `content://` 是唯一合规路径，
 * 清单里已登记 `${applicationId}.fileprovider`，`file_paths.xml` 也放开了该目录。
 */
object AnswerImageShare {

    private const val DIR = "share"
    private const val FILE = "answer.png"

    /**
     * 把 [layer] 的内容写成 PNG 并弹出系统分享面板。
     *
     * 任何一步失败都返回 false，由调用方决定怎么提示——**不假装成功**。
     */
    suspend fun share(context: Context, layer: GraphicsLayer, filename: String = FILE): Boolean {
        // `toImageBitmap()` 是 suspend（要等一次 GPU 同步读回），所以这里也得是挂起函数
        val bitmap = runCatching { layer.toImageBitmap().asAndroidBitmap() }.getOrNull() ?: return false
        val file = writeCache(context, bitmap, filename) ?: return false
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(intent, "分享回答").apply {
            // 从非 Activity 上下文启动必须带 NEW_TASK
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    private fun writeCache(context: Context, bitmap: Bitmap, filename: String): File? =
        runCatching {
            val dir = File(context.cacheDir, DIR).apply { mkdirs() }
            val file = File(dir, filename)
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            file
        }.getOrNull()
}

package com.mcp.toolbox.core.design.component

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 首页大标题卡片的背景图。
 *
 * 选中的图片先拷进应用私有目录再解码，避免相册 URI 的临时授权失效后背景变空白；
 * 内存里用 StateFlow 持有解码结果，改完首页立即生效，不必重启。
 */
object HomeHeaderBackground {

    private const val FILE_NAME = "home_header_bg.img"
    private const val MAX_EDGE = 1440
    private const val PREFS = "home_header_bg"
    private const val KEY_BLUR = "blur"
    private const val KEY_AURORA = "aurora"

    private val _bitmap = MutableStateFlow<ImageBitmap?>(null)
    val bitmap: StateFlow<ImageBitmap?> = _bitmap.asStateFlow()

    private val _hasCustom = MutableStateFlow(false)
    val hasCustom: StateFlow<Boolean> = _hasCustom.asStateFlow()

    private val _blurred = MutableStateFlow(false)

    /** 背景图是否做高斯模糊。图片本体不动，只影响首页显示。 */
    val blurred: StateFlow<Boolean> = _blurred.asStateFlow()

    private val _aurora = MutableStateFlow(true)

    /** 首页大标题是否使用动态流光背景（设置了自定义图片时以图片为准）。 */
    val aurora: StateFlow<Boolean> = _aurora.asStateFlow()

    fun setAurora(context: Context, enabled: Boolean) {
        _aurora.value = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AURORA, enabled)
            .apply()
    }

    fun setBlur(context: Context, enabled: Boolean) {
        _blurred.value = enabled
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_BLUR, enabled)
            .apply()
    }

    private fun target(context: Context) = File(context.filesDir, FILE_NAME)

    /** 冷启动时把上次设置的背景读回内存。 */
    fun refresh(context: Context) {
        val file = target(context)
        val available = file.isFile && file.length() > 0L
        val decoded = if (available) runCatching { decode(file) }.getOrNull() else null
        _bitmap.value = decoded?.asImageBitmap()
        _hasCustom.value = decoded != null
        _blurred.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BLUR, false)
        _aurora.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AURORA, true)
    }

    /** 把相册里的图片设为首页背景。 */
    fun apply(context: Context, uri: Uri): Result<Unit> = runCatching {
        val file = target(context)
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output -> input.copyTo(output) }
        } ?: error("无法读取所选图片")
        val decoded = decode(file)
        val scaled = scaleDown(decoded)
        if (scaled !== decoded) {
            file.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        }
        _bitmap.value = scaled.asImageBitmap()
        _hasCustom.value = true
    }

    /** 回到默认的主题渐变背景。 */
    fun clear(context: Context) {
        target(context).delete()
        _bitmap.value = null
        _hasCustom.value = false
    }

    private fun decode(file: File): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options) ?: error("图片解码失败")
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / (sample * 2) >= MAX_EDGE || height / (sample * 2) >= MAX_EDGE) sample *= 2
        return sample
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_EDGE || longest == 0) return bitmap
        val ratio = MAX_EDGE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }
}

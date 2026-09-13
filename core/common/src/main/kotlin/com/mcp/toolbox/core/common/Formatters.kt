package com.mcp.toolbox.core.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** 全局格式化工具：所有模块展示数值都必须走这里，保证单位与精度一致。 */
object Formatters {

    private val byteUnits = arrayOf("B", "KB", "MB", "GB", "TB")

    fun bytes(value: Long, decimals: Int = 1): String {
        if (value < 0) return "-"
        if (value < 1024) return "$value B"
        var v = value.toDouble()
        var unit = 0
        while (v >= 1024 && unit < byteUnits.lastIndex) {
            v /= 1024.0
            unit++
        }
        return "%.${decimals}f %s".format(Locale.US, v, byteUnits[unit])
    }

    fun speed(bytesPerSecond: Long): String = bytes(bytesPerSecond) + "/s"

    /** 毫秒 -> 人类可读耗时：820ms / 2.40s / 1m12s */
    fun duration(millis: Long): String {
        if (millis < 1000) return "${millis}ms"
        if (millis < 60_000) return "%.2fs".format(Locale.US, millis / 1000.0)
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        if (minutes < 60) return "${minutes}m${seconds.toString().padStart(2, '0')}s"
        val hours = minutes / 60
        return "${hours}h${(minutes % 60).toString().padStart(2, '0')}m"
    }

    fun timestamp(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(millis))

    fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - millis
        if (abs(diff) < 60_000) return "刚刚"
        val minutes = diff / 60_000
        if (minutes < 60) return "$minutes 分钟前"
        val hours = minutes / 60
        if (hours < 24) return "$hours 小时前"
        val days = hours / 24
        if (days < 30) return "$days 天前"
        return timestamp(millis).substringBefore(' ')
    }

    fun count(value: Long): String = when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> "%.1fK".format(Locale.US, value / 1000.0)
        else -> "%.1fM".format(Locale.US, value / 1_000_000.0)
    }

    fun percent(value: Float): String = "${(value * 100).roundToLong()}%"

    /** 文件名清洗：去掉路径分隔符与非法字符，用于 MCP 产物落盘。 */
    fun sanitizeFileName(raw: String, fallback: String = "untitled"): String {
        val cleaned = raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('.', '_', ' ')
        return cleaned.ifEmpty { fallback }.take(96)
    }
}

package com.mcp.toolbox.feature.decompile.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 二进制资源值的类型常量与解码（AXML / ARSC 共用）。 */
object ResValue {
    const val TYPE_NULL = 0x00
    const val TYPE_REFERENCE = 0x01
    const val TYPE_ATTRIBUTE = 0x02
    const val TYPE_STRING = 0x03
    const val TYPE_FLOAT = 0x04
    const val TYPE_DIMENSION = 0x05
    const val TYPE_FRACTION = 0x06
    const val TYPE_DYNAMIC_REFERENCE = 0x07
    const val TYPE_INT_DEC = 0x10
    const val TYPE_INT_HEX = 0x11
    const val TYPE_INT_BOOLEAN = 0x12

    fun decode(dataType: Int, data: Int, stringPool: List<String>?): String = when (dataType) {
        TYPE_NULL -> "null"
        TYPE_REFERENCE -> "0x%08x".format(data)
        TYPE_ATTRIBUTE -> "?" + "0x%08x".format(data)
        TYPE_STRING -> stringPool?.getOrNull(data) ?: "@string/$data"
        TYPE_FLOAT -> java.lang.Float.intBitsToFloat(data).toString()
        TYPE_DIMENSION -> "${complexToFloat(data)}${dimensionUnit(data)}"
        TYPE_FRACTION -> "${complexToFloat(data) * 100f}%"
        TYPE_DYNAMIC_REFERENCE -> "@dyn/0x" + Integer.toHexString(data)
        TYPE_INT_DEC -> data.toString()
        TYPE_INT_HEX -> "0x" + Integer.toHexString(data)
        TYPE_INT_BOOLEAN -> if (data != 0) "true" else "false"
        else -> "0x" + Integer.toHexString(data)
    }

    fun isComplex(flags: Int): Boolean = (flags and 0x0001) != 0 || (flags and 0x0002) != 0

    private fun complexToFloat(data: Int): Float {
        val mantissa = (data and 0x00FFFFFF).toFloat()
        val shift = when ((data ushr 4) and 0x03) {
            0 -> 23
            1 -> 16
            2 -> 8
            else -> 0
        }
        val value = mantissa * (1f / (1 shl shift))
        return if (data and 0x80000000.toInt() != 0) -value else value
    }

    private fun dimensionUnit(data: Int): String = when (data and 0x0F) {
        0 -> "px"
        1 -> "dip"
        2 -> "sp"
        3 -> "pt"
        4 -> "in"
        5 -> "mm"
        else -> ""
    }

    fun buffer(bytes: ByteArray): ByteBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
}

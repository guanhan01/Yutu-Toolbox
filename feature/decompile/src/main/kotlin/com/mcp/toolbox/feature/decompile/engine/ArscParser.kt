package com.mcp.toolbox.feature.decompile.engine

import com.mcp.toolbox.feature.decompile.ResourceEntry
import com.mcp.toolbox.feature.decompile.ResourceTableInfo

/**
 * resources.arsc 解析：资源类型 / 名称 / 值，并提供 @ref 反查，
 * 用于 AXML 属性解析与「资源」页浏览。只依赖 java.nio。
 */
class ArscParser(private val bytes: ByteArray) {

    class ArscException(message: String) : Exception(message)

    private val valueStrings = mutableListOf<String>()
    private val typeStrings = mutableListOf<String>()
    private val keyStrings = mutableListOf<String>()
    private val byId = HashMap<Int, String>()
    private val entries = mutableListOf<ResourceEntry>()
    private val packageNames = mutableListOf<String>()
    private var typeChunkCount = 0

    fun parse(): ResourceTableInfo {
        if (bytes.size < 12) throw ArscException("resources.arsc 过小")
        val buf = ResValue.buffer(bytes)
        if ((buf.getShort(0).toInt() and 0xFFFF) != 0x0002) throw ArscException("magic 不是资源表（0x0002）")
        var offset = buf.getShort(2).toInt() and 0xFFFF
        while (offset + 8 <= bytes.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val headerSize = buf.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize <= 0 || offset + chunkSize > bytes.size) break
            when (type) {
                0x0001 -> valueStrings.addAll(readStringPool(buf, offset))
                0x0200 -> readPackage(buf, offset, headerSize, chunkSize)
                else -> Unit
            }
            offset += chunkSize
        }
        val typeCounts = entries.groupingBy { it.type }.eachCount().entries
            .sortedByDescending { it.value }
            .map { it.key to it.value }
        return ResourceTableInfo(
            packages = packageNames.toList(),
            typeCounts = typeCounts,
            entries = entries.toList(),
            configCount = typeChunkCount,
        )
    }

    /** 把 @0x7f0b0001 这类引用还原成可读名称；解析不到返回 null。 */
    fun resolve(id: Int): String? = byId[id]

    private fun readPackage(buf: java.nio.ByteBuffer, offset: Int, headerSize: Int, chunkSize: Int) {
        val id = buf.getInt(offset + 8)
        val name = StringBuilder()
        for (i in 0 until 128) {
            val c = buf.getShort(offset + 12 + i * 2).toInt() and 0xFFFF
            if (c == 0) break
            name.append(c.toChar())
        }
        if (name.isNotEmpty()) packageNames += "$name（0x%02x）".format(id)
        val typeStringsOffset = buf.getInt(offset + 268)
        val keyStringsOffset = buf.getInt(offset + 276)
        if (typeStringsOffset in 8 until chunkSize) typeStrings.addAll(readStringPool(buf, offset + typeStringsOffset))
        if (keyStringsOffset in 8 until chunkSize) keyStrings.addAll(readStringPool(buf, offset + keyStringsOffset))
        var inner = offset + headerSize
        val end = offset + chunkSize
        while (inner + 8 <= end) {
            val type = buf.getShort(inner).toInt() and 0xFFFF
            val innerHeader = buf.getShort(inner + 2).toInt() and 0xFFFF
            val innerSize = buf.getInt(inner + 4)
            if (innerSize <= 0 || inner + innerSize > end) break
            when (type) {
                0x0201 -> {
                    typeChunkCount++
                    readType(buf, id, inner, innerHeader, innerSize)
                }
                else -> Unit
            }
            inner += innerSize
        }
    }

    private fun readType(buf: java.nio.ByteBuffer, packageId: Int, offset: Int, headerSize: Int, chunkSize: Int) {
        val typeId = buf.get(offset + 8).toInt() and 0xFF
        val entryCount = buf.getInt(offset + 12)
        val entriesStart = buf.getInt(offset + 16)
        val typeName = typeStrings.getOrNull(typeId - 1) ?: "type$typeId"
        val offsetsBase = offset + headerSize
        val dataBase = offset + entriesStart
        for (i in 0 until entryCount) {
            val pos = offsetsBase + i * 4
            if (pos + 4 > offset + chunkSize) break
            val entryOffset = buf.getInt(pos)
            if (entryOffset == -1) continue
            val entryAbs = dataBase + entryOffset
            if (entryAbs + 8 > offset + chunkSize) continue
            val entrySize = buf.getShort(entryAbs).toInt() and 0xFFFF
            val flags = buf.getShort(entryAbs + 2).toInt() and 0xFFFF
            val keyIdx = buf.getInt(entryAbs + 4)
            val resName = keyStrings.getOrNull(keyIdx) ?: "entry$i"
            val complex = ResValue.isComplex(flags) || entrySize < 8
            val dataType = if (entrySize >= 8) buf.get(entryAbs + 11).toInt() and 0xFF else ResValue.TYPE_NULL
            val data = if (entrySize >= 8) buf.getInt(entryAbs + 12) else 0
            val resId = (packageId shl 24) or (typeId shl 16) or i
            val value = if (complex) "复杂值（bag / array）" else ResValue.decode(dataType, data, valueStrings)
            entries += ResourceEntry(type = typeName, name = resName, value = value, resId = resId, complex = complex)
            if (resName.isNotEmpty()) byId.putIfAbsent(resId, "$typeName/$resName")
        }
    }

    private fun readStringPool(buf: java.nio.ByteBuffer, offset: Int): List<String> {
        val stringCount = buf.getInt(offset + 8)
        val flags = buf.getInt(offset + 16)
        val stringsStart = buf.getInt(offset + 20)
        val utf8 = (flags and 0x100) != 0
        val offsetsBase = offset + 28
        val dataBase = offset + stringsStart
        val out = ArrayList<String>(stringCount)
        for (i in 0 until stringCount) {
            val pos = dataBase + buf.getInt(offsetsBase + i * 4)
            val text = when {
                pos < 0 || pos >= buf.capacity() -> ""
                utf8 -> readUtf8(buf, pos)
                else -> readUtf16(buf, pos)
            }
            out += text
        }
        return out
    }

    private fun readUtf16(buf: java.nio.ByteBuffer, pos: Int): String {
        var p = pos
        var len = buf.getShort(p).toInt() and 0xFFFF
        p += 2
        if (len and 0x8000 != 0) {
            len = ((len and 0x7FFF) shl 16) or (buf.getShort(p).toInt() and 0xFFFF)
            p += 2
        }
        val chars = CharArray(len)
        for (i in 0 until len) {
            val abs = p + i * 2
            chars[i] = if (abs + 1 < buf.capacity()) buf.getShort(abs).toInt().toChar() else ' '
        }
        return String(chars)
    }

    private fun readUtf8(buf: java.nio.ByteBuffer, pos: Int): String {
        var p = pos
        var a = buf.get(p).toInt() and 0xFF
        p += 1
        if (a and 0x80 != 0) {
            a = ((a and 0x7F) shl 8) or (buf.get(p).toInt() and 0xFF)
            p += 1
        }
        var byteLen = buf.get(p).toInt() and 0xFF
        p += 1
        if (byteLen and 0x80 != 0) {
            byteLen = ((byteLen and 0x7F) shl 8) or (buf.get(p).toInt() and 0xFF)
            p += 1
        }
        val end = (p + byteLen).coerceAtMost(buf.capacity())
        val arr = ByteArray((end - p).coerceAtLeast(0))
        for (i in arr.indices) arr[i] = buf.get(p + i)
        return String(arr, Charsets.UTF_8)
    }
}

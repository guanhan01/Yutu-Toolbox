package com.mcp.toolbox.feature.decompile.engine

import com.mcp.toolbox.feature.decompile.ComponentGroup
import com.mcp.toolbox.feature.decompile.ManifestInfo

/**
 * Android 二进制 XML（AXML）解析器：解析 AndroidManifest.xml 等编译后 XML。
 * 只依赖 java.nio；解析失败抛出 AxmlException，由上层降级为原始字节预览。
 */
object AxmlParser {

    private const val RES_STRING_POOL = 0x0001
    private const val RES_XML = 0x0003
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val RES_XML_END_ELEMENT = 0x0103
    private const val RES_XML_RESOURCE_MAP = 0x0180

    class AxmlException(message: String) : Exception(message)

    class Element(val name: String, val attributes: List<Attribute>) {
        val children: MutableList<Element> = mutableListOf()
        fun attr(name: String): String? = attributes.firstOrNull { it.name == name }?.value
    }

    data class Attribute(
        val namespace: String?,
        val name: String,
        val value: String,
        val rawValue: String?,
        val resId: Int,
        val dataType: Int,
    )

    data class Parsed(val root: Element?, val strings: List<String>, val attributeCount: Int)

    fun parse(bytes: ByteArray, valueResolver: (Int) -> String? = { null }): Parsed {
        if (bytes.size < 8) throw AxmlException("文件过小，不是二进制 XML")
        val buf = ResValue.buffer(bytes)
        val magic = buf.getShort(0).toInt() and 0xFFFF
        if (magic != RES_XML) throw AxmlException("不是二进制 XML（type=0x%04x）".format(magic))
        val headerSize = buf.getShort(2).toInt() and 0xFFFF
        val strings = mutableListOf<String>()
        var resMap = IntArray(0)
        var root: Element? = null
        val stack = ArrayDeque<Element>()
        var attributeCount = 0
        var offset = headerSize.coerceAtLeast(8)
        while (offset + 8 <= bytes.size) {
            val type = buf.getShort(offset).toInt() and 0xFFFF
            val chunkHeaderSize = buf.getShort(offset + 2).toInt() and 0xFFFF
            val chunkSize = buf.getInt(offset + 4)
            if (chunkSize <= 0 || offset + chunkSize > bytes.size) break
            when (type) {
                RES_STRING_POOL -> strings.addAll(readStringPool(buf, offset))
                RES_XML_RESOURCE_MAP -> {
                    val count = ((chunkSize - chunkHeaderSize) / 4).coerceAtLeast(0)
                    resMap = IntArray(count) { buf.getInt(offset + chunkHeaderSize + it * 4) }
                }
                RES_XML_START_ELEMENT -> {
                    val nameIdx = buf.getInt(offset + 20)
                    val attrCount = buf.getShort(offset + 28).toInt() and 0xFFFF
                    val attrs = ArrayList<Attribute>(attrCount)
                    var a = offset + 36
                    for (i in 0 until attrCount) {
                        if (a + 20 > offset + chunkSize) break
                        val nsIdx = buf.getInt(a)
                        val attrNameIdx = buf.getInt(a + 4)
                        val rawIdx = buf.getInt(a + 8)
                        val dataType = buf.get(a + 15).toInt() and 0xFF
                        val data = buf.getInt(a + 16)
                        val attrName = strings.getOrNull(attrNameIdx) ?: "attr$attrNameIdx"
                        val raw = if (rawIdx >= 0) strings.getOrNull(rawIdx) else null
                        val resId = resMap.getOrNull(attrNameIdx) ?: 0
                        val decoded = if (dataType == ResValue.TYPE_REFERENCE) {
                            valueResolver(data) ?: raw ?: ("0x%08x".format(data))
                        } else {
                            raw ?: ResValue.decode(dataType, data, strings)
                        }
                        attrs += Attribute(
                            namespace = strings.getOrNull(nsIdx),
                            name = attrName,
                            value = decoded,
                            rawValue = raw,
                            resId = resId,
                            dataType = dataType,
                        )
                        a += 20
                    }
                    attributeCount += attrs.size
                    val element = Element(strings.getOrNull(nameIdx) ?: "node", attrs)
                    val parent = stack.lastOrNull()
                    if (parent != null) parent.children += element else if (root == null) root = element
                    stack.addLast(element)
                }
                RES_XML_END_ELEMENT -> if (stack.isNotEmpty()) stack.removeLast()
                else -> Unit
            }
            offset += chunkSize
        }
        if (root == null) throw AxmlException("未解析到根节点")
        return Parsed(root, strings, attributeCount)
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

    /** 整理成清单页需要的结构化信息，并生成可读 XML 文本。 */
    fun toManifest(parsed: Parsed): ManifestInfo {
        val root = parsed.root ?: throw AxmlException("未解析到根节点")
        if (root.name != "manifest") throw AxmlException("根节点为 <${root.name}>，不是 AndroidManifest")
        val app = root.children.firstOrNull { it.name == "application" }
        val usesSdk = root.children.firstOrNull { it.name == "uses-sdk" }
        val permissions = root.children
            .filter { it.name == "uses-permission" || it.name == "uses-permission-sdk-23" }
            .mapNotNull { it.attr("name") }
            .distinct()
        val declared = root.children.filter { it.name == "permission" }.mapNotNull { it.attr("name") }
        val features = root.children.filter { it.name == "uses-feature" }.mapNotNull { it.attr("name") }
        val groups = listOf("activity", "activity-alias", "service", "receiver", "provider").mapNotNull { kind ->
            val items = app?.children?.filter { it.name == kind }?.mapNotNull { node -> node.attr("name") }
                ?: emptyList()
            if (items.isEmpty()) null else ComponentGroup(kind, items)
        }
        val sb = StringBuilder()
        appendXml(sb, root, 0)
        return ManifestInfo(
            packageName = root.attr("package") ?: "unknown",
            versionName = root.attr("versionName"),
            versionCode = root.attr("versionCode"),
            minSdk = usesSdk?.attr("minSdkVersion"),
            targetSdk = usesSdk?.attr("targetSdkVersion"),
            permissions = permissions,
            declaredPermissions = declared,
            groups = groups,
            usesFeatures = features,
            xml = sb.toString(),
            attributeCount = parsed.attributeCount,
        )
    }

    private fun appendXml(sb: StringBuilder, element: Element, depth: Int) {
        val indent = "  ".repeat(depth)
        val attrs = element.attributes.joinToString(" ") { attr ->
            val ns = if (attr.namespace != null && attr.namespace.contains("android")) "android:" else ""
            val idText = if (attr.resId != 0) " (0x%08x)".format(attr.resId) else ""
            "$ns${attr.name}=\"${attr.value}\"$idText"
        }
        val open = if (attrs.isEmpty()) "<${element.name}" else "<${element.name} $attrs"
        if (element.children.isEmpty()) {
            sb.append(indent).append(open).append("/>\n")
        } else {
            sb.append(indent).append(open).append(">\n")
            element.children.forEach { appendXml(sb, it, depth + 1) }
            sb.append(indent).append("</").append(element.name).append(">\n")
        }
    }
}

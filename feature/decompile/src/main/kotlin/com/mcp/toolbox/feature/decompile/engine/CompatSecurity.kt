package com.mcp.toolbox.feature.decompile.engine

import jadx.api.security.IJadxSecurity
import org.w3c.dom.Document
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * jadx 默认的 JadxSecurity 会用 Apache Xerces 专有特性（http://apache.org/xml/features/...）
 * 配置 DocumentBuilderFactory，Android 自带 JAXP 不支持，导致 ExceptionInInitializerError
 * → jadx.load() 失败。这里换成只用标准 API 的实现，并显式关闭外部实体（防 XXE）。
 */
object CompatSecurity : IJadxSecurity {

    override fun verifyAppPackage(appPackage: String): String = appPackage

    override fun parseXml(input: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isExpandEntityReferences = false
        factory.isIgnoringComments = false
        runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        val builder = factory.newDocumentBuilder()
        // 显式拒绝一切外部实体解析
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(input)
    }
}

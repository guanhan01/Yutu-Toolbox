package com.mcp.toolbox.feature.mcp

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * 网页读取。
 *
 * 与 `http.request` 的分工：那个返回的是**原始响应**（状态码、响应头、正文片段），
 * 适合调 API；这里返回的是**可读正文**，适合让模型读一篇文章或文档页。
 * 两者都走内置 Server 的同一套 token 与「允许写入」开关。
 *
 * HTML → 文本是本地实现的轻量提取：不引第三方解析器（会显著增大 APK），
 * 只做够用的事——丢掉 script/style/注释，按块级标签换行，解开常见实体。
 */
object BuiltInToolSetWeb {

    private const val MAX_BYTES = 3 * 1024 * 1024
    private const val CONNECT_TIMEOUT = 20_000
    private const val READ_TIMEOUT = 30_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"

    fun all(): List<ToolDef> = listOf(webFetch)

    private val webFetch = ToolDef(
        name = "web.fetch",
        title = "读取网页",
        description = "抓取一个网页并抽取可读正文（去掉脚本、样式与标签），返回标题、正文与长度。" +
            "适合读文档、文章、公告；需要原始响应时用 http.request。",
        schema = Schema.obj(
            listOf(
                "url" to Schema.string("目标网页地址（http/https）", format = "uri"),
                "maxChars" to Schema.integer("正文最多返回字符数", default = 12000, min = 500, max = 60000),
                "keepLinks" to Schema.bool("是否保留链接地址（形如 文本[url]）", default = false),
            ),
            required = listOf("url"),
        ),
        handler = { _, args ->
            val raw = args.getString("url").trim()
            val url = URL(raw)
            if (url.protocol != "http" && url.protocol != "https") {
                error("只支持 http/https：${url.protocol}")
            }
            val maxChars = args.optInt("maxChars", 12000).coerceIn(500, 60000)
            val keepLinks = args.optBoolean("keepLinks", false)

            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.8")
                setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                setRequestProperty("Accept-Encoding", "gzip, deflate")
            }
            try {
                val status = conn.responseCode
                if (status !in 200..299) error("抓取失败：HTTP $status")
                val charset = conn.contentType
                    ?.let { Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                    ?.let { runCatching { java.nio.charset.Charset.forName(it) }.getOrNull() }
                    ?: Charsets.UTF_8
                val rawBytes = conn.inputStream.use { stream ->
                    val decoded = when (conn.contentEncoding?.lowercase()) {
                        "gzip" -> GZIPInputStream(stream)
                        "deflate" -> InflaterInputStream(stream)
                        else -> stream
                    }
                    decoded.readLimited(MAX_BYTES)
                }
                val contentType = conn.contentType.orEmpty()
                val isHtml = contentType.contains("html", true) ||
                    rawBytes.take(256).toString(Charsets.UTF_8).trimStart().startsWith("<")

                if (!isHtml) {
                    val text = rawBytes.toString(charset).trim()
                    val trimmed = text.take(maxChars)
                    val structured = JSONObject()
                        .put("url", url.toString())
                        .put("status", status)
                        .put("contentType", contentType)
                        .put("isHtml", false)
                        .put("length", text.length)
                        .put("truncated", text.length > maxChars)
                        .put("text", trimmed)
                    return@ToolDef ToolResult(structured, trimmed)
                }

                val html = rawBytes.toString(charset)
                val title = extractTitle(html)
                val text = htmlToText(html, keepLinks).trim()
                val truncated = text.length > maxChars
                val body = text.take(maxChars)

                val structured = JSONObject()
                    .put("url", url.toString())
                    .put("status", status)
                    .put("contentType", contentType)
                    .put("isHtml", true)
                    .put("title", title)
                    .put("length", text.length)
                    .put("truncated", truncated)
                    .put("text", body)
                val rendered = buildString {
                    if (title.isNotBlank()) append("# ").append(title).append("\n\n")
                    append(body)
                    if (truncated) append("\n\n（正文过长，已截断；可调大 maxChars 或收敛目标页面）")
                }
                ToolResult(structured, rendered)
            } finally {
                conn.disconnect()
            }
        },
    )

    /** 取 `<title>`；没有就退回 og:title。 */
    private fun extractTitle(html: String): String {
        Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { return decodeEntities(it).trim() }
        Regex("<meta[^>]+property=[\"']og:title[\"'][^>]+content=[\"']([^\"']+)", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { return decodeEntities(it).trim() }
        return ""
    }

    /**
     * HTML 转文本。
     *
     * 顺序很重要：先删掉整段不该出现的内容（脚本、样式、注释、内联 SVG），
     * 再处理链接，最后才剥标签——反过来的话 script 里的 `<` 会污染结构判断。
     */
    internal fun htmlToText(html: String, keepLinks: Boolean): String {
        var s = html

        // 1) 整块丢弃
        s = Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE).replace(s, " ")
        s = Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE).replace(s, " ")
        s = Regex("<noscript[^>]*>[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE).replace(s, " ")
        s = Regex("<svg[^>]*>[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE).replace(s, " ")
        s = Regex("<!--[\\s\\S]*?-->").replace(s, " ")

        // 2) 块级标签换行，避免整页挤成一行
        s = Regex("</(p|div|section|article|header|footer|li|tr|h[1-6]|blockquote|pre)>", RegexOption.IGNORE_CASE)
            .replace(s, "\n")
        s = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE).replace(s, "\n")
        s = Regex("<li[^>]*>", RegexOption.IGNORE_CASE).replace(s, "\n· ")

        // 3) 链接
        s = if (keepLinks) {
            Regex("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE)
                .replace(s) { m ->
                    val href = m.groupValues[1]
                    val label = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                    if (label.isEmpty()) "" else "$label[${absolute(href)}]"
                }
        } else {
            Regex("<a[^>]*>([\\s\\S]*?)</a>", RegexOption.IGNORE_CASE).replace(s) { m -> m.groupValues[1] }
        }

        // 4) 其余标签剥掉
        s = Regex("<[^>]+>").replace(s, " ")

        // 5) 实体与空白收敛
        s = decodeEntities(s)
        s = s.replace('\u00A0', ' ')
        s = Regex("[ \\t\\x0B\\f\\r]+").replace(s, " ")
        s = Regex(" *\\n *").replace(s, "\n")
        s = Regex("\\n{3,}").replace(s, "\n\n")
        return s.trim()
    }

    /** 常见实体。只用命名表 + 数字实体，覆盖网页正文里的绝大多数情况。 */
    internal fun decodeEntities(text: String): String {
        if (!text.contains('&')) return text
        var s = text
        s = Regex("&#x([0-9a-fA-F]+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
        }
        s = Regex("&#(\\d+);").replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
        }
        ENTITIES.forEach { (name, ch) -> s = s.replace("&$name;", ch) }
        return s
    }

    private val ENTITIES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "ensp" to " ", "emsp" to " ", "thinsp" to " ",
        "copy" to "©", "reg" to "®", "trade" to "™", "deg" to "°",
        "hellip" to "…", "mdash" to "—", "ndash" to "–", "middot" to "·",
        "laquo" to "«", "raquo" to "»", "ldquo" to "“", "rdquo" to "”",
        "lsquo" to "‘", "rsquo" to "’", "times" to "×", "divide" to "÷",
        "bull" to "•", "dagger" to "†", "sect" to "§", "para" to "¶",
        "yen" to "¥", "euro" to "€", "pound" to "£", "cent" to "¢",
        "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓",
        "plusmn" to "±", "frac12" to "½", "sup2" to "²", "sup3" to "³",
    )

    /** 相对地址补全，链接模式下才有意义。 */
    private fun absolute(href: String): String = when {
        href.startsWith("http://") || href.startsWith("https://") -> href
        else -> href
    }

    /** 边读边计数，超过上限立即停止，避免被超大响应拖垮内存。 */
    private fun java.io.InputStream.readLimited(limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val n = read(chunk)
            if (n < 0) break
            total += n
            if (total > limit) break
            out.write(chunk, 0, n)
        }
        return out.toByteArray()
    }

    private fun ByteArray.toString(charset: java.nio.charset.Charset): String = String(this, charset)

    private fun ByteArray.take(n: Int): ByteArray = if (size <= n) this else copyOf(n)
}

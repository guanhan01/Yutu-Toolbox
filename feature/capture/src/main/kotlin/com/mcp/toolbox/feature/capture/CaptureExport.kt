package com.mcp.toolbox.feature.capture

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 抓包结果导出：HAR 1.2、原始 JSON、cURL，以及写入系统下载目录。 */
object CaptureExport {
    private val isoFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)

    fun toHar(records: List<CaptureRecord>): String {
        val builder = StringBuilder()
        builder.append("{\n  \"log\": {\n")
        builder.append("    \"version\": \"1.2\",\n")
        builder.append("    \"creator\": { \"name\": \"MCP Toolbox\", \"version\": \"0.5.0\" },\n")
        builder.append("    \"pages\": [],\n")
        builder.append("    \"entries\": [")
        records.forEachIndexed { index, record ->
            if (index > 0) builder.append(',')
            builder.append("\n").append(entryJson(record))
        }
        builder.append("\n    ]\n  }\n}\n")
        return builder.toString()
    }

    fun toJson(records: List<CaptureRecord>): String {
        val builder = StringBuilder("[\n")
        records.forEachIndexed { index, record ->
            if (index > 0) builder.append(",\n")
            builder.append("  {\n")
            builder.append("    \"kind\": \"${record.kind}\",\n")
            builder.append("    \"protocol\": \"${record.protocol}\",\n")
            builder.append("    \"method\": ${quote(record.method ?: "")},\n")
            builder.append("    \"url\": ${quote(record.url ?: "")},\n")
            builder.append("    \"host\": ${quote(record.host ?: "")},\n")
            builder.append("    \"sni\": ${quote(record.sni ?: "")},\n")
            builder.append("    \"status\": ${record.status ?: 0},\n")
            builder.append("    \"src\": \"${record.srcIp}:${record.srcPort}\",\n")
            builder.append("    \"dst\": \"${record.dstIp}:${record.dstPort}\",\n")
            builder.append("    \"app\": ${quote(record.appPackage ?: "")},\n")
            builder.append("    \"requestBytes\": ${record.requestBytes},\n")
            builder.append("    \"responseBytes\": ${record.responseBytes},\n")
            builder.append("    \"durationMs\": ${record.durationMs},\n")
            builder.append("    \"state\": \"${record.state}\",\n")
            builder.append("    \"startedAt\": ${record.startedAt}\n")
            builder.append("  }")
        }
        builder.append("\n]\n")
        return builder.toString()
    }

    fun toCurl(record: CaptureRecord): String {
        val method = record.method ?: "GET"
        val url = record.url ?: return "缺少可重放的 URL（该记录没有解出主机名）"
        val builder = StringBuilder("curl -X $method '${url}'")
        record.requestHeaders.forEach { (name, value) ->
            builder.append(" \\\n  -H '").append(name).append(": ").append(value).append('\'')
        }
        record.requestBody?.let {
            builder.append(" \\\n  --data-raw '").append(it.replace("'", "'\\''")).append('\'')
        }
        return builder.toString()
    }

    private fun entryJson(record: CaptureRecord): String {
        val method = record.method ?: record.protocol
        val url = record.url ?: "://${record.host ?: record.dstIp}"
        val started = isoFormat.format(Date(record.startedAt))
        val ttfb = record.ttfbMs ?: -1
        val bodySent = record.requestBody
        val bodyReceived = record.responseBody
        return buildString {
            append("      {")
            append("\n        \"startedDateTime\": ${quote(started)},")
            append("\n        \"time\": ${record.durationMs},")
            append("\n        \"request\": {")
            append("\n          \"method\": ${quote(method)},")
            append("\n          \"url\": ${quote(url)},")
            append("\n          \"httpVersion\": \"HTTP/1.1\",")
            append("\n          \"headers\": [")
                .append(headerJson(record.requestHeaders))
                .append("],")
            append("\n          \"queryString\": [],")
            append("\n          \"cookies\": [],")
            append("\n          \"headersSize\": -1,")
            append("\n          \"bodySize\": ${record.requestBytes}")
            if (bodySent != null) {
                append(",\n          \"postData\": { \"mimeType\": ")
                append(
                    quote(
                        record.requestHeaders
                            .firstOrNull { it.first.equals("Content-Type", true) }
                            ?.second ?: "text/plain"))
                append(", \"text\": ").append(quote(bodySent)).append(" }")
            }
            append("\n        },")
            append("\n        \"response\": {")
            append("\n          \"status\": ${record.status ?: 0},")
            append("\n          \"statusText\": ${quote(record.reason ?: "")},")
            append("\n          \"httpVersion\": \"HTTP/1.1\",")
            append("\n          \"headers\": [")
                .append(headerJson(record.responseHeaders))
                .append("],")
            append("\n          \"cookies\": [],")
            append("\n          \"content\": { \"size\": ${record.responseBytes}, \"mimeType\": ")
            append(
                quote(
                    record.responseHeaders
                        .firstOrNull { it.first.equals("Content-Type", true) }
                        ?.second ?: "application/octet-stream"))
            if (bodyReceived != null) append(", \"text\": ").append(quote(bodyReceived))
            append(" },")
            append(
                "\n          \"redirectURL\": ${quote(record.responseHeaders.firstOrNull { it.first.equals("Location", true) }?.second ?: "")},")
            append("\n          \"headersSize\": -1,")
            append("\n          \"bodySize\": ${record.responseBytes}")
            append("\n        },")
            append("\n        \"cache\": {},")
            append(
                "\n        \"timings\": { \"blocked\": -1, \"dns\": -1, \"connect\": -1, \"ssl\": ")
            append(record.tlsHandshakeMs ?: -1)
            append(", \"send\": 0, \"wait\": ").append(ttfb).append(", \"receive\": ")
            append((record.durationMs - ttfb).coerceAtLeast(0))
            append(" },")
            append("\n        \"serverIPAddress\": ${quote(record.dstIp)},")
            append(
                "\n        \"_mcpToolbox\": { \"kind\": \"${record.kind}\", \"state\": \"${record.state}\", \"app\": ")
            append(quote(record.appLabel ?: record.appPackage ?: ""))
            append(", \"sni\": ").append(quote(record.sni ?: ""))
            append(", \"tls\": ").append(quote(record.tlsVersion ?: ""))
            append(", \"note\": ")
            append(quote(if (record.kind == CaptureKind.HTTPS) "TLS 未解密，仅握手与流量统计可见" else ""))
            append(" }")
            append("\n      }")
        }
    }

    private fun headerJson(headers: List<Pair<String, String>>): String =
        headers.joinToString(",") {
            " { \"name\": ${quote(it.first)}, \"value\": ${quote(it.second)} } "
        }

    fun quote(value: String): String {
        val builder = StringBuilder("\"")
        value.forEach { ch ->
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                else ->
                    if (ch.code < 0x20) {
                        builder.append(String.format("\\u%04x", ch.code))
                    } else {
                        builder.append(ch)
                    }
            }
        }
        return builder.append('"').toString()
    }

    /** 写文本到系统「下载」根目录，返回可展示的相对路径。 */
    /** 写二进制到系统「下载」根目录（DER 证书等）。 */
    fun saveBytes(context: Context, fileName: String, bytes: ByteArray, mimeType: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            val uri =
                runCatching {
                        context.contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    }
                    .getOrNull() ?: return null
            val written =
                runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                    .isSuccess
            return if (written) Environment.DIRECTORY_DOWNLOADS + "/" + fileName else null
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) return null
        return runCatching {
                File(dir, fileName).writeBytes(bytes)
                Environment.DIRECTORY_DOWNLOADS + "/" + fileName
            }
            .getOrNull()
    }

    fun saveText(context: Context, fileName: String, content: String, mimeType: String): String? {
        val bytes = content.toByteArray()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values =
                ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
            val uri =
                runCatching {
                        context.contentResolver.insert(
                            MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    }
                    .getOrNull() ?: return null
            val written =
                runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    }
                    .isSuccess
            return if (written) "${Environment.DIRECTORY_DOWNLOADS}/$fileName" else null
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists() && !dir.mkdirs()) return null
        val file = File(dir, fileName)
        return runCatching {
                file.writeBytes(bytes)
                file.absolutePath
            }
            .getOrNull()
    }

    fun timestampName(prefix: String, extension: String): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "$prefix-$stamp.$extension"
    }
}

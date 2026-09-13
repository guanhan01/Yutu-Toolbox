package com.mcp.toolbox.feature.capture

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Repeater：把抓到的明文请求（可编辑后）重新发一次，返回真实响应。 */
object HttpRepeater {
    data class Result(
        val ok: Boolean,
        val status: Int,
        val statusText: String,
        val headers: List<Pair<String, String>>,
        val body: String,
        val durationMs: Long,
        val error: String? = null,
    )

    suspend fun send(method: String, url: String, headersText: String, body: String?): Result =
        withContext(Dispatchers.IO) {
            val started = System.currentTimeMillis()
            val connection = runCatching {
                (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method.uppercase().ifBlank { "GET" }
                    connectTimeout = 15_000
                    readTimeout = 20_000
                    instanceFollowRedirects = false
                    doInput = true
                }
            }.getOrNull() ?: return@withContext Result(
                false, 0, "", emptyList(), "", System.currentTimeMillis() - started, "URL 无效：$url",
            )
            try {
                headersText.lines().forEach { line ->
                    if (!line.contains(':')) return@forEach
                    val name = line.substringBefore(':').trim()
                    val value = line.substringAfter(':').trim()
                    if (name.isEmpty() || name.equals("Host", true) || name.equals("Content-Length", true)) {
                        return@forEach
                    }
                    runCatching { connection.setRequestProperty(name, value) }
                }
                if (body != null && connection.requestMethod !in listOf("GET", "HEAD")) {
                    connection.doOutput = true
                    connection.outputStream.use { it.write(body.toByteArray()) }
                }
                val status = connection.responseCode
                val stream = if (status >= 400) connection.errorStream else connection.inputStream
                val text = runCatching { stream?.bufferedReader()?.use { it.readText() } ?: "" }.getOrDefault("")
                val headers = connection.headerFields.entries
                    .filter { it.key != null }
                    .flatMap { entry -> entry.value.map { entry.key to it } }
                Result(
                    ok = true,
                    status = status,
                    statusText = connection.responseMessage ?: "",
                    headers = headers,
                    body = text.take(64 * 1024),
                    durationMs = System.currentTimeMillis() - started,
                )
            } catch (t: Throwable) {
                Result(
                    false, 0, "", emptyList(), "", System.currentTimeMillis() - started,
                    t.message ?: t.javaClass.simpleName,
                )
            } finally {
                runCatching { connection.disconnect() }
            }
        }
}

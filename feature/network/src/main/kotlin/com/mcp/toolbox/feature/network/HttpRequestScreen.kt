package com.mcp.toolbox.feature.network

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcp.toolbox.core.design.component.CodeEditor
import com.mcp.toolbox.core.design.component.CodeLanguage
import com.mcp.toolbox.core.design.component.MethodBadge
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixCard
import com.mcp.toolbox.core.design.component.MiuixCodeText
import com.mcp.toolbox.core.design.component.MiuixDivider
import com.mcp.toolbox.core.design.component.MiuixIcon
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.component.StatusCodeText
import com.mcp.toolbox.core.design.component.methodColor
import com.mcp.toolbox.core.design.component.miuixClickable
import com.mcp.toolbox.core.design.component.rememberMiuixPressState
import com.mcp.toolbox.core.design.component.statusColor
import com.mcp.toolbox.core.design.theme.MiuixTheme
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HttpResult(
    val code: Int = 0,
    val elapsedMs: Long = 0,
    val sizeBytes: Int = 0,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: String = "",
    val error: String? = null,
)

data class HttpHistoryItem(val method: String, val url: String, val at: Long)

/** 真实发起请求：仅使用 JDK 的 HttpURLConnection，不引入额外依赖。 */
suspend fun executeHttp(
    method: String,
    url: String,
    headers: List<Pair<String, String>>,
    body: String,
): HttpResult =
    withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        runCatching {
                connection =
                    (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = method
                        connectTimeout = 15_000
                        readTimeout = 20_000
                        instanceFollowRedirects = true
                        headers
                            .filter { it.first.isNotBlank() }
                            .forEach { (k, v) -> setRequestProperty(k.trim(), v) }
                        if (body.isNotBlank() && method !in listOf("GET", "HEAD")) {
                            doOutput = true
                            setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        }
                    }
                if (body.isNotBlank() && method !in listOf("GET", "HEAD")) {
                    connection!!.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = connection!!.responseCode
                val stream =
                    if (code in 200..299) connection!!.inputStream else connection!!.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val elapsed = System.currentTimeMillis() - started
                HttpResult(
                    code = code,
                    elapsedMs = elapsed,
                    sizeBytes = text.toByteArray(Charsets.UTF_8).size,
                    headers =
                        connection!!
                            .headerFields
                            .entries
                            .filter { it.key != null }
                            .flatMap { (k, values) -> values.map { k to it } },
                    body = text,
                )
            }
            .getOrElse { throwable ->
                HttpResult(error = throwable.message ?: throwable.javaClass.simpleName)
            }
            .also { connection?.disconnect() }
    }

/** HTTP 构造器：方法、URL、参数、请求头、请求体、认证与响应六段式布局， 请求为真实发送（HttpURLConnection），响应展示状态码、耗时、体积与原始头。 */
@Composable
fun HttpRequestScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onToast: (String) -> Unit = {},
) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val scope = rememberCoroutineScope()

    var method by remember { mutableStateOf("POST") }
    var url by remember { mutableStateOf("https://api.example.com/v1/query") }
    var tab by remember { mutableStateOf("请求体") }
    var body by remember {
        mutableStateOf(
            """
            {
              "id": 12345,
              "name": "example",
              "active": true,
              "tags": ["api", "demo"],
              "data": null,
              "metadata": { "version": "1.0.0" },
              "created": "2024-01-01T00:00:00Z"
            }
            """
                .trimIndent(),
        )
    }
    var result by remember { mutableStateOf<HttpResult?>(null) }
    var sending by remember { mutableStateOf(false) }
    var methodMenu by remember { mutableStateOf(false) }

    // 方法菜单和按钮不同层（平级），所以记下按钮矩形给菜单当锚点。
    var methodAnchor by remember { mutableStateOf<Rect?>(null) }
    var moreMenu by remember { mutableStateOf(false) }
    var history by remember {
        mutableStateOf(
            listOf(
                HttpHistoryItem(
                    "GET",
                    "https://api.example.com/v1/query",
                    System.currentTimeMillis() - 3600_000),
                HttpHistoryItem(
                    "POST",
                    "https://api.example.com/v1/create",
                    System.currentTimeMillis() - 7200_000),
            ),
        )
    }

    val tabs = listOf("参数", "请求头", "请求体", "认证", "响应")

    Column(modifier = modifier.fillMaxSize().background(colors.background)) {
        MiuixTopAppBar(
            title = "HTTP 构造器",
            navigationIcon = Icons.Outlined.ArrowBack,
            onNavigationClick = onBack,
            actions = {
                MiuixTag(text = "测试环境 dev", color = colors.tertiary, filled = true)
                Box {
                    MiuixIconButton(Icons.Outlined.MoreHoriz, "更多", onClick = { moreMenu = true })
                    MiuixOverflowMenu(expanded = moreMenu, onDismiss = { moreMenu = false }) {
                        MiuixMenuItem(
                            "复制为 cURL",
                            {
                                moreMenu = false
                                onToast("已复制 cURL（演示）")
                            },
                            icon = Icons.Outlined.ContentCopy)
                        MiuixMenuItem(
                            "格式化 JSON", { moreMenu = false }, icon = Icons.Outlined.DataObject)
                    }
                }
            },
        )

        Column(
            modifier =
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.pageHorizontal),
        ) {
            // 方法 + URL + 发送
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    val press = rememberMiuixPressState()
                    Box(
                        modifier =
                            Modifier.onGloballyPositioned { methodAnchor = it.boundsInWindow() }
                                .background(
                                    colors.methodColor(method),
                                    RoundedCornerShape(MiuixTheme.radius.sm))
                                .miuixClickable(press, true) { methodMenu = true }
                                .padding(horizontal = spacing.md, vertical = spacing.sm),
                    ) {
                        MiuixText(
                            text = method,
                            style =
                                MiuixTheme.typography.labelLarge.copy(
                                    fontFamily = FontFamily.Monospace),
                            color = Color.White,
                        )
                    }
                    MiuixOverflowMenu(
                        expanded = methodMenu,
                        onDismiss = { methodMenu = false },
                        anchor = methodAnchor) {
                            listOf("GET", "POST", "PUT", "PATCH", "DELETE").forEach { item ->
                                MiuixMenuItem(
                                    item,
                                    {
                                        method = item
                                        methodMenu = false
                                    },
                                    checked = method == item)
                            }
                        }
                }
                Spacer(Modifier.width(spacing.sm))
                Box(
                    modifier =
                        Modifier.weight(1f)
                            .background(
                                colors.surfaceContainerHigh,
                                RoundedCornerShape(MiuixTheme.radius.field))
                            .padding(horizontal = spacing.md, vertical = 10.dp),
                ) {
                    BasicTextField(
                        value = url,
                        onValueChange = { url = it },
                        singleLine = true,
                        textStyle =
                            MiuixTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                color = colors.onSurface,
                            ),
                        cursorBrush = SolidColor(colors.primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.width(spacing.sm))
                val sendPress = rememberMiuixPressState(!sending)
                Box(
                    modifier =
                        Modifier.size(44.dp)
                            .background(colors.primary, RoundedCornerShape(14.dp))
                            .miuixClickable(sendPress, !sending) {
                                sending = true
                                scope.launch {
                                    result = executeHttp(method, url, emptyList(), body)
                                    sending = false
                                    history =
                                        listOf(
                                            HttpHistoryItem(
                                                method, url, System.currentTimeMillis())) + history
                                    onToast(result?.error?.let { "请求失败：$it" } ?: "请求完成")
                                }
                            },
                    contentAlignment = Alignment.Center,
                ) {
                    if (sending) {
                        com.mcp.toolbox.core.design.component.MiuixCircularProgress(
                            size = 20.dp, color = colors.onPrimary)
                    } else {
                        MiuixIcon(Icons.Outlined.Send, "发送", tint = colors.onPrimary, size = 20.dp)
                    }
                }
            }

            Spacer(Modifier.height(spacing.md))

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(spacing.lg),
            ) {
                tabs.forEach { item ->
                    val selected = tab == item
                    val press = rememberMiuixPressState()
                    Column(
                        modifier = Modifier.miuixClickable(press, true) { tab = item },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        MiuixText(
                            text = item,
                            style = MiuixTheme.typography.labelLarge,
                            color = if (selected) colors.primary else colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                        Box(
                            modifier =
                                Modifier.height(3.dp)
                                    .width(20.dp)
                                    .background(
                                        if (selected) colors.primary else Color.Transparent,
                                        RoundedCornerShape(50),
                                    ),
                        )
                    }
                }
            }

            Spacer(Modifier.height(spacing.md))

            when (tab) {
                "请求体",
                "参数",
                "请求头" -> {
                    CodeEditor(value = body, onValueChange = { body = it })
                    Spacer(Modifier.height(spacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                        MiuixButton(
                            text = "JSON 语法",
                            onClick = { onToast("已切换 JSON 高亮") },
                            variant = MiuixButtonVariant.TONAL,
                            size = com.mcp.toolbox.core.design.component.MiuixButtonSize.SMALL,
                        )
                        MiuixButton(
                            text = "格式化",
                            onClick = { body = formatJson(body) },
                            variant = MiuixButtonVariant.OUTLINED,
                            size = com.mcp.toolbox.core.design.component.MiuixButtonSize.SMALL,
                        )
                    }
                }
                "认证" -> {
                    MiuixCard {
                        MiuixText("认证方式", style = MiuixTheme.typography.titleSmall)
                        Spacer(Modifier.height(spacing.sm))
                        MiuixText(
                            text = "当前支持 Bearer Token 与 Basic 两种方式，P3 会接入签名助手（HMAC / RSA）。",
                            style = MiuixTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                else -> Unit
            }

            result?.let { res ->
                Spacer(Modifier.height(spacing.lg))
                ResponseCard(res, onCopy = { onToast("响应头已复制") })
            }

            if (result == null && tab != "响应") {
                Spacer(Modifier.height(spacing.lg))
                MiuixCard(
                    variant = com.mcp.toolbox.core.design.component.MiuixCardVariant.OUTLINED) {
                        MiuixText("尚未发送请求", style = MiuixTheme.typography.titleSmall)
                        Spacer(Modifier.height(4.dp))
                        MiuixText(
                            text = "点击右上角发送按钮后，这里会展示状态码、耗时、响应头与响应体。",
                            style = MiuixTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
            }

            Spacer(Modifier.height(spacing.lg))
            MiuixText("历史请求", style = MiuixTheme.typography.titleSmall)
            Spacer(Modifier.height(spacing.sm))
            history.forEach { item ->
                Row(
                    modifier =
                        Modifier.fillMaxWidth()
                            .miuixClickable(rememberMiuixPressState(), true) {
                                url = item.url
                                method = item.method
                            }
                            .padding(vertical = spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MethodBadge(item.method)
                    Spacer(Modifier.width(spacing.sm))
                    MiuixText(
                        text = item.url,
                        modifier = Modifier.weight(1f),
                        style =
                            MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurface,
                        maxLines = 1,
                    )
                    MiuixText(
                        text =
                            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(item.at)),
                        style = MiuixTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                MiuixDivider()
            }
            Spacer(Modifier.height(spacing.xxl))
        }
    }
}

@Composable
private fun ResponseCard(result: HttpResult, onCopy: () -> Unit) {
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    MiuixCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (result.error != null) {
                MiuixTag(text = "请求失败", color = colors.error, filled = true)
                Spacer(Modifier.width(spacing.sm))
                MiuixText(
                    text = result.error,
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.error,
                )
            } else {
                MiuixTag(
                    text = "${result.code} OK",
                    color = colors.statusColor(result.code),
                    filled = true,
                )
                Spacer(Modifier.width(spacing.sm))
                StatusCodeText(result.code)
            }
            Spacer(Modifier.weight(1f))
            MiuixText(
                text = "${result.elapsedMs}ms · ${formatBytes(result.sizeBytes)}",
                style = MiuixTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            MiuixIconButton(
                icon = Icons.Outlined.ContentCopy,
                contentDescription = "复制",
                onClick = onCopy,
            )
        }
        if (result.headers.isNotEmpty()) {
            Spacer(Modifier.height(spacing.sm))
            MiuixText("响应头", style = MiuixTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            result.headers.take(8).forEach { (key, value) ->
                Row(Modifier.fillMaxWidth()) {
                    MiuixText(
                        text = key.lowercase(Locale.ROOT),
                        modifier = Modifier.width(140.dp),
                        style =
                            MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.codeKeyword,
                        maxLines = 1,
                    )
                    MiuixText(
                        text = value,
                        modifier = Modifier.weight(1f),
                        style =
                            MiuixTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = colors.onSurface,
                        maxLines = 1,
                    )
                }
            }
        }
        if (result.body.isNotBlank()) {
            Spacer(Modifier.height(spacing.md))
            MiuixText("响应体", style = MiuixTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            MiuixCodeText(
                code = result.body.take(4000),
                language = CodeLanguage.JSON,
                showLineNumbers = false,
                fontSize = 12.sp,
            )
        }
    }
}

internal fun formatJson(raw: String): String =
    runCatching {
            val trimmed = raw.trim()
            val builder = StringBuilder()
            var indent = 0
            var inString = false
            trimmed.forEachIndexed { index, c ->
                when {
                    c == '"' && (index == 0 || trimmed[index - 1] != '\\') -> {
                        inString = !inString
                        builder.append(c)
                    }
                    inString -> builder.append(c)
                    c == '{' || c == '[' -> {
                        indent++
                        builder.append(c).append('\n').append("  ".repeat(indent))
                    }
                    c == '}' || c == ']' -> {
                        indent = (indent - 1).coerceAtLeast(0)
                        builder.append('\n').append("  ".repeat(indent)).append(c)
                    }
                    c == ',' -> builder.append(c).append('\n').append("  ".repeat(indent))
                    c == ':' -> builder.append(": ")
                    c.isWhitespace() -> Unit
                    else -> builder.append(c)
                }
            }
            builder.toString()
        }
        .getOrDefault(raw)

internal fun formatBytes(bytes: Int): String =
    when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }

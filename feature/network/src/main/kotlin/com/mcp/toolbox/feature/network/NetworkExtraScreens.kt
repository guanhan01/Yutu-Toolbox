package com.mcp.toolbox.feature.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.CodeLanguage
import com.mcp.toolbox.core.design.component.MiuixButton
import com.mcp.toolbox.core.design.component.MiuixButtonSize
import com.mcp.toolbox.core.design.component.MiuixButtonVariant
import com.mcp.toolbox.core.design.component.MiuixCircularProgress
import com.mcp.toolbox.core.design.component.MiuixCodeText
import com.mcp.toolbox.core.design.component.MiuixEmptyState
import com.mcp.toolbox.core.design.component.MiuixFilterChip
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixLinearProgress
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSegmentedButton
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL
import java.net.UnknownHostException

// ---------------------------------------------------------------------------
// Whois / RDAP：走 rdap.org 公开 RDAP 服务，真实查询域名或 IP 的注册信息
// ---------------------------------------------------------------------------

data class WhoisField(val label: String, val value: String)

@Composable
fun WhoisScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val colors = MiuixTheme.colors
    val scope = rememberCoroutineScope()
    val clipboard: ClipboardManager = LocalClipboardManager.current
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var fields by remember { mutableStateOf<List<WhoisField>>(emptyList()) }
    var rawJson by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun lookup() {
        val target = query.trim()
        if (target.isBlank()) {
            onToast("请输入域名或 IP")
            return
        }
        loading = true
        error = null
        fields = emptyList()
        rawJson = ""
        scope.launch {
            val outcome = rdapLookup(target)
            loading = false
            outcome.onSuccess { payload ->
                rawJson = payload.first
                fields = payload.second
                if (fields.isEmpty()) error = "RDAP 未返回可解析字段"
            }.onFailure { throwable ->
                error = throwable.message ?: "查询失败"
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        MiuixTopAppBar(
            title = "Whois / RDAP",
            navigationIcon = Icons.Outlined.ArrowBack,
            onNavigationClick = onBack,
            actions = {
                if (rawJson.isNotBlank()) {
                    com.mcp.toolbox.core.design.component.MiuixIconButton(
                        icon = Icons.Outlined.ContentCopy,
                        contentDescription = "复制原始 JSON",
                        onClick = {
                            clipboard.setText(AnnotatedString(rawJson))
                            onToast("已复制原始 RDAP JSON")
                        },
                    )
                }
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(8.dp))
            MiuixTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "example.com 或 8.8.8.8",
                leadingIcon = Icons.Outlined.Search,
            )
            Spacer(Modifier.height(10.dp))
            MiuixButton(
                text = if (loading) "查询中…" else "查询",
                onClick = { lookup() },
                modifier = Modifier.fillMaxWidth(),
                loading = loading,
                enabled = !loading,
            )
            Spacer(Modifier.height(16.dp))
            if (loading) {
                Box(Modifier.fillMaxWidth(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    MiuixCircularProgress()
                }
            }
            error?.let {
                MiuixSectionCard(title = "查询失败") {
                    Column(Modifier.padding(16.dp)) {
                        MiuixText(
                            text = it,
                            style = MiuixTheme.typography.bodyMedium,
                            color = colors.error,
                        )
                    }
                }
            }
            if (fields.isNotEmpty()) {
                MiuixSectionCard(title = "注册信息") {
                    fields.forEach { field ->
                        MiuixListItem(title = field.label, subtitle = field.value)
                    }
                }
            }
            if (rawJson.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                MiuixText(
                    text = "原始 RDAP 响应",
                    style = MiuixTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                MiuixCodeText(
                    code = rawJson,
                    language = CodeLanguage.JSON,
                    showLineNumbers = false,
                    modifier = Modifier.heightIn(max = 520.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private suspend fun rdapLookup(target: String): Result<Pair<String, List<WhoisField>>> =
    withContext(Dispatchers.IO) {
        runCatching {
            val candidates = if (target.matches(Regex("^[0-9.]+$"))) {
                listOf("https://rdap.org/ip/$target")
            } else {
                listOf("https://rdap.org/domain/$target", "https://rdap.org/ip/$target")
            }
            var lastError: String? = null
            candidates.forEach { url ->
                val body = httpGet(url)
                if (body != null) {
                    val json = JSONObject(body)
                    return@runCatching body to parseRdap(json)
                }
                lastError = "RDAP 未命中：$url"
            }
            throw UnknownHostException(lastError ?: "RDAP 查询失败")
        }
    }

private fun httpGet(url: String): String? {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 8000
        readTimeout = 8000
        setRequestProperty("Accept", "application/rdap+json, application/json")
        setRequestProperty("User-Agent", "MCPToolbox/1.0 (Android)")
    }
    return try {
        if (connection.responseCode !in 200..299) {
            null
        } else {
            connection.inputStream.bufferedReader().use { it.readText() }
        }
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun parseRdap(json: JSONObject): List<WhoisField> {
    val fields = mutableListOf<WhoisField>()
    json.optString("handle").takeIf { it.isNotBlank() }?.let { fields += WhoisField("Handle", it) }
    (json.optString("ldhName").takeIf { it.isNotBlank() } ?: json.optString("name").takeIf { it.isNotBlank() })
        ?.let { fields += WhoisField("名称", it) }
    json.optString("startAddress").takeIf { it.isNotBlank() }?.let {
        val end = json.optString("endAddress")
        fields += WhoisField("地址段", if (end.isBlank()) it else "$it - $end")
    }
    json.optString("country").takeIf { it.isNotBlank() }?.let { fields += WhoisField("国家/地区", it) }
    json.optString("type").takeIf { it.isNotBlank() }?.let { fields += WhoisField("类型", it) }

    json.optJSONArray("status")?.let { statuses ->
        val list = (0 until statuses.length()).map { statuses.optString(it) }.filter { it.isNotBlank() }
        if (list.isNotEmpty()) fields += WhoisField("状态", list.joinToString("\n"))
    }
    json.optJSONArray("events")?.let { events ->
        for (i in 0 until events.length()) {
            val event = events.optJSONObject(i) ?: continue
            val action = event.optString("eventAction")
            val date = event.optString("eventDate")
            if (action.isNotBlank() && date.isNotBlank()) {
                fields += WhoisField(
                    when (action.lowercase()) {
                        "registration" -> "注册时间"
                        "expiration" -> "到期时间"
                        "last changed" -> "最后变更"
                        "last update of rdap database" -> "RDAP 更新"
                        else -> action
                    },
                    date,
                )
            }
        }
    }
    json.optJSONArray("nameservers")?.let { servers ->
        val list = (0 until servers.length()).mapNotNull { servers.optJSONObject(it)?.optString("ldhName") }
            .filter { it.isNotBlank() }
        if (list.isNotEmpty()) fields += WhoisField("Name Servers", list.joinToString("\n"))
    }
    json.optJSONArray("entities")?.let { entities ->
        for (i in 0 until entities.length()) {
            val entity = entities.optJSONObject(i) ?: continue
            val roles = entity.optJSONArray("roles")?.let { array ->
                (0 until array.length()).map { array.optString(it) }
            }.orEmpty()
            if (roles.contains("registrar")) {
                val name = entity.optJSONArray("vcardArray")
                    ?.takeIf { it.length() > 1 }
                    ?.optJSONArray(1)
                    ?.let { vcard ->
                        (0 until vcard.length()).mapNotNull { index ->
                            val row = vcard.optJSONArray(index)
                            if (row != null && row.optString(0) == "fn") row.optString(3) else null
                        }.firstOrNull()
                    }
                fields += WhoisField("注册商", name ?: entity.optString("handle"))
            }
        }
    }
    val notices = json.optJSONArray("notices")?.let { array ->
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("title") }
            .filter { it.isNotBlank() }
    }.orEmpty()
    if (notices.isNotEmpty()) fields += WhoisField("附加说明", notices.joinToString("\n"))

    return fields
}

// ---------------------------------------------------------------------------
// 网络环境：真实读取 ConnectivityManager / LinkProperties / NetworkInterface
// ---------------------------------------------------------------------------

@Composable
fun NetworkEnvScreen(onBack: () -> Unit, onToast: (String) -> Unit) {
    val colors = MiuixTheme.colors
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var sections by remember { mutableStateOf<List<Pair<String, List<WhoisField>>>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        refreshing = true
        sections = readNetworkEnvironment(context)
        refreshing = false
    }

    Column(Modifier.fillMaxSize()) {
        MiuixTopAppBar(
            title = "网络环境",
            navigationIcon = Icons.Outlined.ArrowBack,
            onNavigationClick = onBack,
            actions = {
                com.mcp.toolbox.core.design.component.MiuixIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    onClick = {
                        refreshing = true
                        sections = readNetworkEnvironment(context)
                        refreshing = false
                        onToast("已刷新")
                    },
                )
            },
        )
        if (refreshing && sections.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                MiuixCircularProgress()
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp),
        ) {
            items(sections) { (title, rows) ->
                MiuixSectionCard(title = title) {
                    rows.forEach { row ->
                        MiuixListItem(
                            title = row.label,
                            subtitle = row.value.takeIf { it.isNotBlank() } ?: "-",
                            showDivider = true,
                        )
                    }
                }
            }
            item {
                MiuixButton(
                    text = "复制全部信息",
                    onClick = {
                        val text = sections.joinToString("\n\n") { (title, rows) ->
                            title + "\n" + rows.joinToString("\n") { "${it.label}: ${it.value}" }
                        }
                        clipboard.setText(AnnotatedString(text))
                        onToast("已复制到剪贴板")
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = MiuixButtonVariant.TONAL,
                    size = MiuixButtonSize.MEDIUM,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
        if (sections.isEmpty()) {
            MiuixEmptyState(
                title = "未读取到网络信息",
                description = "请检查是否已连接网络",
                icon = Icons.Outlined.Wifi,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

private fun readNetworkEnvironment(context: Context): List<Pair<String, List<WhoisField>>> {
    val result = mutableListOf<Pair<String, List<WhoisField>>>()
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return result
    val network = manager.activeNetwork
    val capabilities: NetworkCapabilities? = network?.let { manager.getNetworkCapabilities(it) }
    val link: LinkProperties? = network?.let { manager.getLinkProperties(it) }

    val summary = mutableListOf<WhoisField>()
    summary += WhoisField("联网状态", if (network == null) "未连接" else "已连接")
    if (capabilities != null) {
        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("Wi-Fi")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("蜂窝")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("以太网")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("蓝牙")
        }
        summary += WhoisField("传输类型", transports.joinToString(" + ").ifBlank { "未知" })
        summary += WhoisField("已验证联网", if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "是" else "否")
        summary += WhoisField("按流量计费", if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) "否" else "是")
        summary += WhoisField(
            "带宽估计",
            "下行 ${capabilities.linkDownstreamBandwidthKbps} kbps / 上行 ${capabilities.linkUpstreamBandwidthKbps} kbps",
        )
    }
    result += "连接概览" to summary

    if (link != null) {
        val linkRows = mutableListOf<WhoisField>()
        linkRows += WhoisField("接口", link.interfaceName ?: "-")
        // LinkProperties.mtu 在部分 ROM（如 ColorOS）返回 0，回退到网卡真实 MTU
        val interfaceMtu = runCatching { NetworkInterface.getByName(link.interfaceName.orEmpty())?.mtu }
            .getOrNull()
        val mtu = if (link.mtu > 0) link.mtu else (interfaceMtu ?: -1)
        linkRows += WhoisField("MTU", if (mtu > 0) "$mtu" else "-")
        val addresses = link.linkAddresses.mapNotNull { it.address.hostAddress }
        if (addresses.isNotEmpty()) linkRows += WhoisField("本机地址", addresses.joinToString("\n"))
        val dns = link.dnsServers.mapNotNull { it.hostAddress }
        if (dns.isNotEmpty()) linkRows += WhoisField("DNS 服务器", dns.joinToString("\n"))
        linkRows += WhoisField("路由条目", "${link.routes.size}")
        link.domains?.takeIf { it.isNotBlank() }?.let { linkRows += WhoisField("搜索域", it) }
        link.httpProxy?.let { proxy ->
            linkRows += WhoisField("HTTP 代理", "${proxy.host}:${proxy.port}")
        }
        result += "链路详情" to linkRows
    }

    val interfaces = mutableListOf<WhoisField>()
    runCatching {
        val enumeration = NetworkInterface.getNetworkInterfaces() ?: return@runCatching
        while (enumeration.hasMoreElements()) {
            val nif = enumeration.nextElement()
            val address = nif.inetAddresses.toList().joinToString(", ") { it.hostAddress.orEmpty() }
            interfaces += WhoisField(
                label = nif.name + if (nif.isUp) " (UP)" else " (DOWN)",
                value = buildString {
                    append("MTU ${runCatching { nif.mtu }.getOrDefault(-1)}")
                    if (address.isNotBlank()) append("\n$address")
                },
            )
        }
    }
    if (interfaces.isNotEmpty()) result += "网卡列表（${interfaces.size}）" to interfaces

    return result
}

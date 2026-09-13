package com.mcp.toolbox.feature.database

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixBottomSheet
import com.mcp.toolbox.core.design.component.MiuixDialog
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixMenuDivider
import com.mcp.toolbox.core.design.component.MiuixMenuGroupLabel
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixSegmentedButton
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.component.MiuixTextField
import com.mcp.toolbox.core.design.component.MiuixTopAppBar
import com.mcp.toolbox.core.design.theme.MiuixTheme
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment

/** 业务表清单：过滤掉 Android/SQLite 内部表，避免默认落到 android_metadata。 */
private fun userTables(schemas: List<TableSchema>): List<String> =
    schemas
        .filter {
            it.type == "table" && !it.name.startsWith("android_") && !it.name.startsWith("sqlite_")
        }
        .map { it.name }

private enum class DbTab(val label: String) {
    SCHEMA("结构"),
    DATA("数据"),
    SQL("SQL"),
}

/** 数据库浏览器：真实打开磁盘上的 SQLite 文件（沙箱 / SAF 外部文件 / root 只读副本）， 提供结构浏览、分页数据编辑、SQL 执行与导入导出。 */
@Composable
fun DatabaseScreen(
    onBack: () -> Unit,
    onToast: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing

    var handle by remember { mutableStateOf<DbHandle?>(null) }
    var schemas by remember { mutableStateOf(emptyList<TableSchema>()) }
    var loading by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(DbTab.SCHEMA) }
    var selectedObject by remember { mutableStateOf<String?>(null) }
    var dataTable by remember { mutableStateOf<String?>(null) }
    var openMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }

    // 菜单挂在页面顶层（不在按钮旁边的 Box 里），所以自己记下三点按钮的窗口矩形，菜单才能贴着它展开。
    var openAnchor by remember { mutableStateOf<Rect?>(null) }
    var moreAnchor by remember { mutableStateOf<Rect?>(null) }
    var rootSheet by remember { mutableStateOf(false) }
    var treeUri by remember { mutableStateOf<String?>(null) }
    var rootAvailable by remember { mutableStateOf<Boolean?>(null) }
    var pathDialog by remember { mutableStateOf(false) }
    var rootPath by remember { mutableStateOf("/data/adb/modules") }
    var schemaReport by remember { mutableStateOf<List<String>?>(null) }
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    fun refresh(handleValue: DbHandle) {
        loading = true
        scope.launch {
            schemas = SqliteEngine.schemas(handleValue.db)
            loading = false
            selectedObject = schemas.firstOrNull()?.name
        }
    }

    fun openLocal(
        file: File,
        readOnly: Boolean,
        origin: DbOrigin,
        label: String,
        seedSample: Boolean = false
    ) {
        scope.launch {
            if (seedSample) SampleDatabase.ensure(context)
            val db = runCatching { SqliteEngine.open(file.absolutePath, readOnly) }.getOrNull()
            if (db == null) {
                onToast("打开失败：不是有效的 SQLite 文件")
                return@launch
            }
            val opened = SqliteEngine.handle(file.absolutePath, db, readOnly, origin)
            handle?.db?.close()
            handle = opened
            refresh(opened)
            onToast("已打开 $label · ${DbFormat.bytes(opened.sizeBytes)}")
        }
    }

    fun openBytes(bytes: ByteArray, name: String, origin: DbOrigin, readOnly: Boolean) {
        scope.launch {
            val target = File(File(context.cacheDir, "opened").apply { mkdirs() }, name)
            withContext(Dispatchers.IO) { target.writeBytes(bytes) }
            openLocal(target, readOnly, origin, name)
        }
    }

    // SAF 选择数据库文件：复制到缓存后以可写句柄打开，原文保留用于「保存写回」
    val pickDatabase =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            scope.launch {
                val bytes =
                    withContext(Dispatchers.IO) {
                        runCatching {
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }
                            .getOrNull()
                    }
                if (bytes == null) {
                    onToast("无法读取该文件")
                    return@launch
                }
                if (!SqliteEngine.isSqliteFile(bytes.take(16).toByteArray())) {
                    onToast("不是 SQLite 文件（文件头校验失败）")
                    return@launch
                }
                val name = queryDisplayName(context, uri) ?: "external.db"
                openBytes(bytes, name, DbOrigin.Saf(uri.toString(), name), readOnly = false)
            }
        }

    val pickSchema =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            val current = handle ?: return@rememberLauncherForActivityResult
            scope.launch {
                val text =
                    withContext(Dispatchers.IO) {
                        runCatching {
                                context.contentResolver.openInputStream(uri)?.use {
                                    it.readBytes().decodeToString()
                                }
                            }
                            .getOrNull()
                    }
                if (text == null) {
                    onToast("无法读取 schema 文件")
                    return@launch
                }
                val (ok, issues) = SqliteEngine.validateRoomSchema(current.db, text)
                onToast(if (ok) "Room schema 校验通过" else "发现 ${issues.size} 处差异")
                schemaReport = if (ok) listOf("校验通过：schema.json 与当前数据库完全一致") else issues
            }
        }

    val pickTree =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            treeUri = uri.toString()
            onToast("导出目录已设置")
        }

    LaunchedEffect(Unit) {
        rootAvailable = SqliteEngine.rootAvailable()
        openLocal(
            context.getDatabasePath(SampleDatabase.FILE_NAME).also { it.parentFile?.mkdirs() },
            readOnly = false,
            origin = DbOrigin.Demo(),
            label = "示例库",
            seedSample = true,
        )
    }

    fun export(name: String, content: String) {
        scope.launch {
            val (file, message) =
                withContext(Dispatchers.IO) { DbExport.write(context, treeUri, name, content) }
            onToast(message)
        }
    }

    Column(Modifier.fillMaxSize().background(colors.background)) {
        MiuixTopAppBar(
            title = "数据库",
            navigationIcon = Icons.Outlined.ArrowBack,
            onNavigationClick = onBack,
            actions = {
                MiuixIconButton(
                    icon = Icons.Outlined.FolderOpen,
                    contentDescription = "打开数据库",
                    onClick = { openMenu = true },
                    modifier = Modifier.onGloballyPositioned { openAnchor = it.boundsInWindow() },
                )
                MiuixIconButton(
                    icon = Icons.Outlined.MoreHoriz,
                    contentDescription = "更多",
                    onClick = { moreMenu = true },
                    modifier = Modifier.onGloballyPositioned { moreAnchor = it.boundsInWindow() },
                )
            },
        )

        val current = handle
        if (current == null) {
            MiuixSectionCard(
                title = "正在准备数据库",
                subtitle = if (loading) "正在读取示例库…" else "点右上角可打开其它数据库",
                modifier = Modifier.padding(horizontal = spacing.pageHorizontal),
            ) {
                MiuixText(
                    text = "支持应用沙箱文件、SAF 选择的外部 .db 文件、以及经 root 复制为只读副本的应用数据库。",
                    style = MiuixTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(spacing.pageHorizontal),
                )
            }
            return@Column
        }

        MiuixSectionCard(
            title = current.name,
            subtitle = current.origin.label,
            modifier = Modifier.padding(horizontal = spacing.pageHorizontal),
        ) {
            Row(
                modifier =
                    Modifier.horizontalScroll(rememberScrollState())
                        .padding(horizontal = spacing.pageHorizontal, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiuixTag(
                    text = if (current.readOnly) "只读" else "可写",
                    color = if (current.readOnly) colors.success else colors.primary,
                    filled = true,
                )
                Spacer(Modifier.width(8.dp))
                MiuixTag(text = DbFormat.bytes(current.sizeBytes), color = colors.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                MiuixTag(
                    text = "page ${current.pageSize}×${current.pageCount}",
                    color = colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(8.dp))
                MiuixTag(text = "journal ${current.journalMode}", color = colors.onSurfaceVariant)
            }
            MiuixText(
                text =
                    "路径：${current.path}\nuser_version=${current.userVersion} · encoding=${current.encoding} · " +
                        "对象 ${schemas.size} 个（表/视图）",
                style = MiuixTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = spacing.pageHorizontal, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
        }

        Spacer(Modifier.height(spacing.sm))
        Row(Modifier.padding(horizontal = spacing.pageHorizontal)) {
            MiuixSegmentedButton(
                options = DbTab.entries.toList(),
                selected = tab,
                onSelect = { tab = it },
                label = { it.label },
            )
        }
        Spacer(Modifier.height(spacing.sm))

        when (tab) {
            DbTab.SCHEMA ->
                DbSchemaTab(
                    schemas = schemas,
                    selected = selectedObject,
                    onSelect = { selectedObject = it.ifBlank { null } },
                    onOpenData = { table ->
                        dataTable = table
                        tab = DbTab.DATA
                    },
                    modifier = Modifier.weight(1f),
                )

            DbTab.DATA ->
                DbDataTab(
                    handle = current,
                    tables = userTables(schemas),
                    initialTable = dataTable,
                    schemas = schemas,
                    onToast = onToast,
                    onExport = { name, content -> export(name, content) },
                    modifier = Modifier.weight(1f),
                )

            DbTab.SQL ->
                DbSqlTab(
                    handle = current,
                    tables = userTables(schemas),
                    columnsByTable =
                        schemas.associate { it.name to it.columns.map { column -> column.name } },
                    onToast = onToast,
                    onExport = { name, content -> export(name, content) },
                    modifier = Modifier.weight(1f),
                )
        }
    }

    // 文件夹图标：只负责打开 / 切换数据源
    MiuixOverflowMenu(expanded = openMenu, onDismiss = { openMenu = false }, anchor = openAnchor) {
        MiuixMenuGroupLabel(text = "打开数据库")
        MiuixMenuItem(
            text = "示例库（真实 SQLite 文件）",
            icon = Icons.Outlined.Storage,
            onClick = {
                openMenu = false
                openLocal(
                    context.getDatabasePath(SampleDatabase.FILE_NAME),
                    readOnly = false,
                    origin = DbOrigin.Demo(),
                    label = "示例库",
                    seedSample = true,
                )
            })
        MiuixMenuItem(
            text = "打开外部 .db 文件（SAF）",
            icon = Icons.Outlined.FolderOpen,
            onClick = {
                openMenu = false
                pickDatabase.launch(arrayOf("*/*"))
            })
        MiuixMenuItem(
            text = "打开应用数据库（root 只读）",
            icon = Icons.Outlined.PhoneAndroid,
            trailingText =
                when (rootAvailable) {
                    true -> "可用"
                    false -> "不可用"
                    null -> "检测中"
                },
            onClick = {
                openMenu = false
                if (rootAvailable == false) {
                    onToast("未获得 root 权限，可改用 SAF 打开文件")
                } else {
                    rootSheet = true
                }
            },
        )
        MiuixMenuItem(
            text = "自定义 root 路径",
            icon = Icons.Outlined.Terminal,
            onClick = {
                openMenu = false
                pathDialog = true
            })
    }

    // 三点菜单：导出、写回与关闭
    MiuixOverflowMenu(expanded = moreMenu, onDismiss = { moreMenu = false }, anchor = moreAnchor) {
        MiuixMenuGroupLabel(text = "导出与校验")
        MiuixMenuItem(
            text = "导出整库 SQL dump",
            onClick = {
                moreMenu = false
                handle?.let { current ->
                    scope.launch {
                        val sql = withContext(Dispatchers.IO) { SqliteEngine.dumpSql(current.db) }
                        export("${current.name}.dump.sql", sql)
                    }
                }
            })
        MiuixMenuItem(
            text = "校验 Room schema.json",
            onClick = {
                moreMenu = false
                pickSchema.launch(arrayOf("application/json", "*/*"))
            })
        MiuixMenuItem(
            text = "选择导出目录",
            trailingText = if (treeUri == null) "应用私有" else "已授权",
            onClick = {
                moreMenu = false
                pickTree.launch(null)
            },
        )
        MiuixMenuItem(
            text = "保存写回原文件",
            icon = Icons.Outlined.Save,
            enabled = handle?.origin is DbOrigin.Saf && handle?.writable == true,
            onClick = {
                moreMenu = false
                val current = handle ?: return@MiuixMenuItem
                val origin = current.origin
                if (origin !is DbOrigin.Saf) return@MiuixMenuItem
                confirm =
                    "把当前编辑后的数据库写回 ${origin.name}？" to
                    {
                        scope.launch {
                            val (ok, message) =
                                withContext(Dispatchers.IO) {
                                    DbExport.writeBack(context, origin.uri, File(current.path))
                                }
                            onToast(message)
                        }
                    }
            },
        )
        MiuixMenuDivider()
        MiuixMenuItem(
            text = "清空导出目录",
            icon = Icons.Outlined.DeleteSweep,
            onClick = {
                moreMenu = false
                confirm =
                    "删除应用私有导出目录中的全部文件？" to
                    {
                        scope.launch {
                            val removed =
                                withContext(Dispatchers.IO) {
                                    DbExport.defaultDirectory(context).listFiles()?.count {
                                        it.delete()
                                    } ?: 0
                                }
                            onToast("已删除 $removed 个文件")
                        }
                    }
            })
        MiuixMenuItem(
            text = "关闭数据库",
            danger = true,
            onClick = {
                moreMenu = false
                handle?.db?.close()
                handle = null
                schemas = emptyList()
            })
    }

    if (rootSheet) {
        RootDatabaseSheet(
            context = context,
            onDismiss = { rootSheet = false },
            onPick = { entry ->
                rootSheet = false
                scope.launch {
                    onToast("正在经 root 复制 ${entry.name}…")
                    val (file, message) = SqliteEngine.copyFromRoot(context, entry.path)
                    if (file == null || file.length() == 0L) {
                        onToast("复制失败：$message")
                    } else {
                        openLocal(
                            file,
                            readOnly = true,
                            origin = DbOrigin.RootCopy(entry.path),
                            label = entry.name)
                    }
                }
            },
        )
    }

    if (pathDialog) {
        MiuixDialog(
            visible = true,
            onDismiss = { pathDialog = false },
            title = "通过 root 打开数据库",
            message = "填入设备上的绝对路径，App 会用 su 复制为只读副本后再打开。",
            confirmText = "打开",
            onConfirm = {
                val path = rootPath.trim()
                pathDialog = false
                if (path.isBlank()) {
                    onToast("路径不能为空")
                } else {
                    scope.launch {
                        val (file, message) = SqliteEngine.copyFromRoot(context, path)
                        if (file == null || file.length() == 0L) {
                            onToast("打开失败：$message")
                        } else {
                            openLocal(
                                file,
                                readOnly = true,
                                origin = DbOrigin.RootCopy(path),
                                label = path.substringAfterLast('/'),
                            )
                        }
                    }
                }
            },
            content = {
                MiuixTextField(
                    value = rootPath,
                    onValueChange = { rootPath = it },
                    placeholder = "/data/data/<包名>/databases/xxx.db",
                )
            },
        )
    }

    schemaReport?.let { report ->
        MiuixDialog(
            visible = true,
            onDismiss = { schemaReport = null },
            title = "Room schema 校验",
            message = report.take(12).joinToString("\n"),
            confirmText = "知道了",
            dismissText = null,
            onConfirm = { schemaReport = null },
        )
    }

    confirm?.let { (message, action) ->
        MiuixDialog(
            visible = true,
            onDismiss = { confirm = null },
            title = "危险操作确认",
            message = message,
            confirmText = "继续",
            destructive = true,
            onConfirm = {
                confirm = null
                action()
            },
        )
    }
}

/** 应用数据库选择 Sheet：真实列出已安装应用，再经 root 读取其 databases 目录。 */
@Composable
private fun RootDatabaseSheet(
    context: Context,
    onDismiss: () -> Unit,
    onPick: (RootEntry) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var packages by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) }
    var files by remember { mutableStateOf(emptyList<RootEntry>()) }
    var status by remember { mutableStateOf("正在读取应用列表…") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val list =
            withContext(Dispatchers.IO) {
                val fromRoot =
                    SqliteEngine.listRootDirectory("/data/data")
                        .first
                        .filter { it.isDirectory }
                        .map { it.name }
                        .filter { it.contains('.') }
                val names =
                    if (fromRoot.isNotEmpty()) {
                        fromRoot
                    } else {
                        runCatching {
                                context.packageManager.getInstalledPackages(0).map {
                                    it.packageName
                                }
                            }
                            .getOrDefault(emptyList())
                    }
                names
                    .map { packageName ->
                        val label =
                            runCatching {
                                    context.packageManager
                                        .getApplicationInfo(packageName, 0)
                                        .loadLabel(context.packageManager)
                                        .toString()
                                }
                                .getOrNull()
                                ?.takeIf { it.isNotBlank() } ?: packageName
                        label to packageName
                    }
                    .sortedBy { it.first.lowercase() }
            }
        packages = list
        status = "共 ${list.size} 个应用"
    }

    LaunchedEffect(picked) {
        val target = picked ?: return@LaunchedEffect
        status = "正在读取 ${target.second} 的 databases…"
        val (entries, error) = SqliteEngine.listPackageDatabases(target.second)
        files = entries
        status = if (entries.isEmpty()) error.ifBlank { "没有可读的数据库文件" } else "共 ${entries.size} 个文件"
    }

    MiuixBottomSheet(visible = true, onDismiss = onDismiss) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = MiuixTheme.dimens.spacing.pageHorizontal)) {
                MiuixText(text = "打开应用数据库", style = MiuixTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                MiuixText(
                    text = status,
                    style = MiuixTheme.typography.bodySmall,
                    color = MiuixTheme.colors.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (picked == null) {
                    MiuixTextField(
                        value = query, onValueChange = { query = it }, placeholder = "搜索应用名或包名")
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.height(420.dp)) {
                        items(
                            packages.filter {
                                query.isBlank() ||
                                    it.first.contains(query, true) ||
                                    it.second.contains(query, true)
                            }) { (name, packageName) ->
                                MiuixListItem(
                                    title = name,
                                    subtitle = packageName,
                                    showDivider = true,
                                    onClick = { picked = name to packageName },
                                )
                            }
                    }
                } else {
                    MiuixListItem(
                        title = "← 返回应用列表",
                        subtitle = picked?.first,
                        onClick = {
                            picked = null
                            files = emptyList()
                        },
                    )
                    LazyColumn(Modifier.height(420.dp)) {
                        items(files) { entry ->
                            MiuixListItem(
                                title = entry.name,
                                subtitle = "${DbFormat.bytes(entry.sizeBytes)} · ${entry.path}",
                                showDivider = true,
                                onClick = { onPick(entry) },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
    }
}

/** 查询 SAF 文件真实显示名。 */
private fun queryDisplayName(context: Context, uri: Uri): String? =
    runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }
        .getOrNull()

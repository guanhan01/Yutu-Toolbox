package com.mcp.toolbox.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.unit.dp
import com.mcp.toolbox.core.design.component.MiuixListItem
import com.mcp.toolbox.core.design.component.MiuixSectionCard
import com.mcp.toolbox.core.design.component.MiuixTag
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme

/** 一条应用权限的检测结果：[grantable] 为 false 表示安装即授予、无法单独申请。 */
internal data class AppPermission(
    val name: String,
    val desc: String,
    val granted: Boolean,
    val grantable: Boolean,
    val settingsIntent: Intent?,
)

private fun isGranted(context: Context, perm: String): Boolean =
    context.checkPermission(perm, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED

private fun appDetails(context: Context) = Intent(
    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
    Uri.parse("package:" + context.packageName),
)

/** 逐项检测本应用声明的全部权限，返回可直接渲染的列表。 */
internal fun collectAppPermissions(context: Context): List<AppPermission> {
    val out = mutableListOf<AppPermission>()
    fun runtime(name: String, perm: String, desc: String) {
        out += AppPermission(name, desc, isGranted(context, perm), true, appDetails(context))
    }
    runtime("通知", Manifest.permission.POST_NOTIFICATIONS, "后台任务完成与错误提示")
    if (Build.VERSION.SDK_INT >= 33) {
        runtime("图片", Manifest.permission.READ_MEDIA_IMAGES, "读取相册中的图片")
        runtime("视频", Manifest.permission.READ_MEDIA_VIDEO, "读取相册中的视频")
        runtime("音频", Manifest.permission.READ_MEDIA_AUDIO, "读取音乐与录音")
    } else {
        runtime("存储", Manifest.permission.READ_EXTERNAL_STORAGE, "读取共享存储中的文件")
    }

    val allFiles = if (Build.VERSION.SDK_INT >= 30) {
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
    } else true
    out += AppPermission(
        "所有文件访问",
        "解压、反编译、证书安装需要直接读写任意目录",
        allFiles,
        true,
        Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:" + context.packageName),
        ),
    )

    val usage = runCatching {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)
    out += AppPermission(
        "使用情况访问",
        "读取最近使用记录；ColorOS 需到「设置 → 应用 → 特殊应用权限 → 使用情况访问」手动打开",
        usage,
        true,
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
    )

    listOf(
        "网络访问" to "联网请求与网页加载",
        "网络状态" to "判断当前联网方式",
        "前台服务" to "抓包与长任务在后台运行",
        "查询应用列表" to "列出并管理本机应用",
    ).forEach { (n, d) -> out += AppPermission(n, d, true, false, null) }
    return out
}

/** 权限检测与申请区块：每项显示真实状态，可申请的直接跳系统授权页。 */
@Composable
internal fun PermissionSection(onToast: (String) -> Unit = {}) {
    val context = LocalContext.current
    val colors = MiuixTheme.colors
    val owner = LocalLifecycleOwner.current
    var items by remember { mutableStateOf(collectAppPermissions(context)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) items = collectAppPermissions(context)
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    val missing = items.count { it.grantable && !it.granted }

    MiuixSectionCard(title = if (missing == 0) "应用权限 · 全部就绪" else "应用权限 · " + missing + " 项待授权") {
        Column {
            items.forEachIndexed { index, item ->
                MiuixListItem(
                    title = item.name,
                    subtitle = item.desc,
                    leadingIcon = Icons.Outlined.Info,
                    trailing = {
                        if (item.granted) {
                            MiuixTag(text = "已授予", color = colors.primary)
                        } else if (item.grantable) {
                            MiuixTag(text = "去授权", color = colors.error)
                        } else {
                            MiuixTag(text = "安装即授予", color = colors.onSurfaceVariant)
                        }
                    },
                    onClick = {
                        val intent = item.settingsIntent
                        when {
                            item.granted -> onToast(item.name + " 已授予")
                            intent == null -> onToast(item.name + " 由系统在安装时授予，无需申请")
                            else -> runCatching {
                                context.startActivity(intent)
                                onToast("在该页找到「Yutu Toolbox」并打开开关，返回后自动刷新")
                            }.onFailure { onToast("无法打开该页，请到系统设置里手动授权") }
                        }
                    },
                    showDivider = index != items.lastIndex,
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    MiuixText(
        text = "点任意一项跳到系统授权页，在该页找到「Yutu Toolbox」并打开开关；" +
            "返回本页会自动重新检测。标「安装即授予」的是普通权限，系统在安装时已授予。",
        style = MiuixTheme.typography.bodySmall,
        color = colors.onSurfaceVariant,
    )
}

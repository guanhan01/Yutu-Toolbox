package com.mcp.toolbox.feature.capture

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把本地抓包 CA 打包成可直接刷入的 Magisk / KernelSU 模块（zip）。
 *
 * 只做「系统证书区」这一件事：
 * - `system/etc/security/cacerts/<hash>.0` 覆盖 Android 13 及更早（magic mount 生效）；
 * - Android 14 起系统信任目录在 Conscrypt APEX 内，magic mount 覆盖不到，
 *   因此模块自带 `service.sh`，开机后复用 App 内同一套脚本（暂存 + tmpfs 覆盖 APEX + 补铺已运行的 App）。
 */
object CaModuleZip {

    const val MODULE_ID = "mcp-capture-ca"

    fun build(
        pem: String,
        hashFileName: String,
        serviceScript: String,
        displayName: String = "MCP Toolbox 抓包 CA",
    ): ByteArray {
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val versionCode = stamp.toIntOrNull() ?: 1
        val prop = buildString {
            append("id=").append(MODULE_ID).append('\n')
            append("name=").append(displayName).append('\n')
            append("version=1.0 (").append(stamp).append(")\n")
            append("versionCode=").append(versionCode).append('\n')
            append("author=MCP Toolbox\n")
            append("description=把 MCP Toolbox 的抓包 CA 装进系统信任区；Android 14+ 由 service.sh 覆盖 Conscrypt APEX 证书目录。卸载模块或重启即还原。\n")
        }
        val customize = buildString {
            append("SKIPUNZIP=0\n")
            append("ui_print \"- 安装 MCP Toolbox 抓包 CA\"\n")
            append("set_perm_recursive ").append('$').append("MODPATH/system/etc/security/cacerts 0 0 0644 0644\n")
            append("set_perm ").append('$').append("MODPATH/service.sh 0 0 0755\n")
            append("ui_print \"- 重启后生效；已运行的应用请强制停止后重开\"\n")
        }
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            write(zip, "module.prop", prop)
            write(zip, "customize.sh", customize)
            write(zip, "service.sh", serviceScript)
            write(zip, "system/etc/security/cacerts/" + hashFileName, pem.trim() + "\n")
        }
        return out.toByteArray()
    }

    private fun write(zip: ZipOutputStream, name: String, body: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(body.toByteArray())
        zip.closeEntry()
    }
}

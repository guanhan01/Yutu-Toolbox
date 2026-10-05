package com.mcp.toolbox.ui.linux

import android.content.Context
import android.util.Base64
import com.mcp.toolbox.feature.mcp.WriteGuard
import com.mcp.toolbox.feature.mcp.Schema
import com.mcp.toolbox.feature.mcp.ToolDef
import com.mcp.toolbox.feature.mcp.ToolResult
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import com.mcp.toolbox.core.common.PrivilegeManager

/**
 * 把 Linux 环境的能力暴露成 MCP 工具，让 AI 能直接使用。
 *
 * 放在 app 模块而不是 feature:mcp —— LinuxRuntime 属于 app，反过来依赖会成环。
 * 由 ToolboxApplication 在启动时用 BuiltInToolSet.registerExtra 注入。
 */
object LinuxMcpTools {

    /** 这些是 bind mount 进来的宿主目录，AI 也不该去碰。 */
    private val BLOCKED = setOf("proc", "sys", "dev")

    private fun distroOf(context: Context): LinuxDistro = LinuxPrefs.distro(context)

    private fun requireEnv(context: Context, distro: LinuxDistro) {
        if (!LinuxRuntime.isReady(context, distro)) {
            throw IllegalStateException(
                "Linux 环境尚未安装：请先在「开发环境」里下载并安装 ${distro.displayName}",
            )
        }
    }

    /** rootfs 内相对路径 -> File，并做越界与宿主目录校验。 */
    private fun resolve(context: Context, distro: LinuxDistro, raw: String): File {
        val rel = raw.trim().trim('/')
        val first = rel.substringBefore('/')
        if (first.isNotEmpty() && first in BLOCKED) {
            throw IllegalArgumentException("$first 是宿主的系统目录（bind mount），不允许访问")
        }
        val rootfs = LinuxEnvStore.rootfs(context, distro)
        val target = if (rel.isEmpty()) rootfs else File(rootfs, rel)
        val canonical = runCatching { target.canonicalFile }
            .getOrElse { throw IllegalArgumentException("路径无法解析：$raw") }
        val rootCanonical = runCatching { rootfs.canonicalFile }.getOrDefault(rootfs)
        if (canonical != rootCanonical && !canonical.path.startsWith(rootCanonical.path + File.separator)) {
            throw IllegalArgumentException("路径越界被拒绝：$raw")
        }
        return canonical
    }

    fun all(context: Context): List<ToolDef> = listOf(
        envStatus(context),
        execTool(context),
        shellRun(context),
        shellStatus(context),
        shellKill(context),
        fileList(context),
        fileRead(context),
        fileWrite(context),
        mountList(context),
        mountAdd(context),
        mountRemove(context),
        installApk(context),
    )

    private fun envStatus(context: Context) = ToolDef(
        name = "linux.env",
        title = "Linux 环境状态",
        description = "返回 Linux 环境是否已安装、发行版、占用空间，以及各可选工具" +
            "（Python/uv、Node.js、SSH、APK 分析、Git）的安装状态与版本号。",
        schema = Schema.obj(emptyList()),
        readOnly = true,
        handler = { ctx, _ ->
            val distro = distroOf(ctx)
            val ready = LinuxRuntime.isReady(ctx, distro)
            val components = if (ready) LinuxChecker.check(ctx, distro) else emptyList()
            val versions = if (ready) runBlocking { probeVersions(ctx, distro) } else emptyMap()
            val structured = JSONObject().apply {
                put("installed", ready)
                put("distro", distro.displayName)
                put("sizeBytes", if (ready) LinuxEnvStore.sizeOf(ctx, distro) else 0L)
                put("sizeText", if (ready) LinuxChecker.humanSize(LinuxEnvStore.sizeOf(ctx, distro)) else "未安装")
                put(
                    "components",
                    JSONArray().also { array ->
                        components.forEach { status ->
                            array.put(
                                JSONObject().apply {
                                    put("name", status.component.title)
                                    put("installed", status.installed)
                                    put("version", versions[status.component] ?: status.version)
                                },
                            )
                        }
                    },
                )
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun execTool(context: Context) = ToolDef(
        name = "linux.exec",
        title = "在 Linux 环境执行命令",
        description = "在已安装的 Linux 发行版里以 root 身份执行一条 shell 命令（非交互，跑完即退出），" +
            "返回 stdout、stderr 与退出码。工作目录默认 /root。共享的 Android 目录在 /sdcard。",
        schema = Schema.obj(
            listOf(
                "command" to Schema.string("要执行的 shell 命令"),
                "workdir" to Schema.string("工作目录（rootfs 内路径）", default = "/root"),
                "timeoutMs" to Schema.integer("超时毫秒", default = 60000, min = 1000, max = 1800000),
            ),
            required = listOf("command"),
        ),
        readOnly = false,
        dangerous = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            val distro = distroOf(ctx)
            requireEnv(ctx, distro)
            val command = args.getString("command")
            val workdir = args.optString("workdir", "/root").ifBlank { "/root" }
            val timeout = args.optInt("timeoutMs", 60000).toLong().coerceIn(1000L, 1800000L)
            val out = runBlocking { LinuxRuntime.exec(ctx, distro, command, workdir, timeout) }
            val structured = JSONObject().apply {
                put("exitCode", out.exitCode)
                put("stdout", out.stdout)
                put("stderr", out.stderr)
                put("elapsedMs", out.elapsedMs)
                put("distro", distro.displayName)
            }
            ToolResult(structured, out.combined, isError = !out.ok)
        },
    )

    // ---------------- 持久 shell 会话 ----------------
    //
    // 与上面的 linux.exec 的区别：linux.exec 每次 `sh -c` 跑完即退，没有状态；
    // 这里在 rootfs 里维持一个长期存活的 sh，cwd / 环境变量 / 后台任务跨调用保留。
    //
    // 会话放在进程级单例里：AI 对话是一轮轮来的，模型需要「上一步在哪个目录、
    // 装了什么」在下一步仍然成立。应用被杀才会丢（这是有意的——进程没了还留着
    // 一个 root shell 挂着并不安全）。

    private var shell_: AiShellSession? = null

    @Synchronized
    private fun shell(context: Context): AiShellSession {
        val distro = distroOf(context)
        // 切换发行版要换一个会话：新会话指向另一个 rootfs
        val existing = shell_
        if (existing != null && existing.distro == distro) return existing
        runCatching { runBlocking { existing?.kill() } }
        return AiShellSession(context.applicationContext, distro).also { shell_ = it }
    }

    private fun shellRun(context: Context) = ToolDef(
        name = "linux.shell",
        title = "在 Linux 持久会话里执行命令",
        description = "在**长期存活**的 shell 里执行命令，因此 `cd`、`export`、" +
            "后台任务（`cmd &`）都会保留到后续调用，适合分步骤干活：" +
            "先 `cd` 到项目目录，再 `apt install`，再跑构建，最后看日志。" +
            "\n\n" +
            "几个要点：\n" +
            "· 退出码在返回值里，非 0 表示命令失败，`stderr` 已并入输出。\n" +
            "· 命令里**不要写单独的 `exit`**：那是 shell 内建，会杀掉整个持久会话。" +
            "要拿某个退出码用 `(exit N)`。\n" +
            "· 交互式程序（`vim`、`top`、需要输入密码的 `ssh`）不适用：这里没有终端，" +
            "它们会立刻失败或读不到输入。跑完即退的用法请改用 linux.exec，" +
            "它每次都带完整初始化，更干净。\n" +
            "· 长任务请自行放后台（`nohup cmd >log 2>&1 &`）或调大 timeoutMs；" +
            "超时不会杀掉正在跑的任务，会话与任务都保留。",
        schema = Schema.obj(
            listOf(
                "command" to Schema.string("要执行的 shell 命令（可多行）"),
                "workdir" to Schema.string(
                    "先切到该目录再执行（rootfs 内绝对路径）；不传则沿用会话当前目录",
                ),
                "timeoutMs" to Schema.integer(
                    "等待上限毫秒；超时不杀任务",
                    default = 120000,
                    min = 1000,
                    max = 600000,
                ),
            ),
            required = listOf("command"),
        ),
        readOnly = false,
        dangerous = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            WriteGuard.requireWrite()
            requireEnv(ctx, distroOf(ctx))
            val command = args.getString("command")
            if (command.isBlank()) throw IllegalArgumentException("命令不能为空")
            val workdir = args.optString("workdir").takeIf { it.isNotBlank() }
            val timeout = args.optInt("timeoutMs", 120000).toLong()
            val result = runBlocking { shell(ctx).run(command, timeout, workdir) }
            val structured = JSONObject().apply {
                put("command", result.command)
                put("exitCode", result.exitCode)
                put("success", result.exitCode == 0)
                put("output", result.output)
                put("elapsedMs", result.elapsedMs)
                put("sessionAlive", result.sessionAlive)
                result.note?.let { put("note", it) }
            }
            // 失败时置 isError，模型据此判断是否要重试或换做法
            ToolResult(structured, result.output, isError = result.exitCode != 0)
        },
    )

    private fun shellStatus(context: Context) = ToolDef(
        name = "linux.shell_status",
        title = "查看持久会话状态",
        description = "返回持久 shell 是否在运行、发行版、会话内当前目录，" +
            "以及在会话里跑 `ps` 拿到的进程清单——用来确认之前放后台的任务是否还在跑。" +
            "活动进程列表可能较长，默认只看末尾 40 行。",
        schema = Schema.obj(
            listOf(
                "includeProcesses" to Schema.bool("是否附带会话内的进程列表", default = true),
            ),
        ),
        readOnly = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            requireEnv(ctx, distroOf(ctx))
            val session = shell(ctx)
            val alive = session.running
            val structured = JSONObject().apply {
                put("running", alive)
                put("distro", distroOf(ctx).displayName)
            }
            val text = StringBuilder()
            text.append("持久会话：").append(if (alive) "运行中" else "未运行（下次调用会自动建立）")
            if (args.optBoolean("includeProcesses", true) && alive) {
                // 一次往返同时取回 cwd 与进程表：模型据此判断「上一步 cd 到哪了」
                // 以及「之前放后台的任务还在不在」
                val probe = runBlocking {
                    session.run("echo \"CWD=$(pwd)\"; ps 2>/dev/null | tail -40", 20000L)
                }
                structured.put("probe", probe.output)
                val cwd = Regex("""(?:^|\n)CWD=(.*)""").find(probe.output)?.groupValues?.get(1)?.trim()
                if (!cwd.isNullOrBlank()) structured.put("workdir", cwd)
                text.append("\n\n会话内情况：\n").append(probe.output)
            }
            ToolResult(structured, text.toString())
        },
    )

    private fun shellKill(context: Context) = ToolDef(
        name = "linux.shell_kill",
        title = "结束持久会话",
        description = "结束当前持久 shell 会话，其中的后台任务会一并结束。" +
            "之后再次调用 linux.shell 会自动建立全新会话（cwd 回到 /root，环境变量清空）。" +
            "适合「上一步把环境弄脏了，想从头来」。",
        schema = Schema.obj(emptyList()),
        readOnly = false,
        dangerous = true,
        requiresPrivilege = true,
        handler = { ctx, _ ->
            val session = shell_
            val had = session?.running == true
            runBlocking { session?.kill() }
            shell_ = null
            val structured = JSONObject().apply {
                put("killed", had)
                put("distro", distroOf(ctx).displayName)
            }
            ToolResult(
                structured,
                if (had) "已结束持久会话，其中的后台任务一并结束。下次调用 linux.shell 会建立新会话。"
                else "当前没有运行中的持久会话。",
            )
        },
    )

    private fun shellQuote(raw: String): String = "'" + raw.replace("'", "'\\''") + "'"

    private fun checkNotBlocked(rel: String) {
        val first = rel.trim('/').substringBefore('/')
        if (first.isNotEmpty() && first in BLOCKED) {
            throw IllegalArgumentException("$first 是宿主的系统目录（bind mount），不允许访问")
        }
    }

    /**
     * 文件操作一律走 root 在 Linux 内执行。
     *
     * rootfs 里大量目录属主是 root（例如 /root 是 700），应用进程用 File API
     * 连列目录都会被拒，所以这里统一让 root 去读。
     */
    private fun fileList(context: Context) = ToolDef(
        name = "linux.file_list",
        title = "列出 Linux 目录",
        description = "列出 Linux 环境里的目录内容（路径相对 rootfs 根）。" +
            "proc / sys / dev 是宿主的系统目录，会被拒绝。共享目录在 /sdcard。",
        schema = Schema.obj(
            listOf("path" to Schema.string("相对 rootfs 的路径，空表示根目录", default = "")),
        ),
        readOnly = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            val distro = distroOf(ctx)
            requireEnv(ctx, distro)
            val rel = args.optString("path").trim().trim('/')
            checkNotBlocked(rel)
            val inner = if (rel.isEmpty()) "/" else "/$rel"
            val out = runBlocking {
                LinuxRuntime.exec(ctx, distro, "ls -1Ap " + shellQuote(inner), "/", 30000)
            }
            if (!out.ok) {
                throw IllegalStateException(out.combined.ifBlank { "列目录失败：$inner" })
            }
            val array = JSONArray()
            out.stdout.lineSequence().filter { it.isNotBlank() }.forEach { line ->
                array.put(
                    JSONObject().apply {
                        put("name", line.trimEnd('/'))
                        put("dir", line.endsWith("/"))
                    },
                )
            }
            val structured = JSONObject().apply {
                put("path", inner)
                put("count", array.length())
                put("entries", array)
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun fileRead(context: Context) = ToolDef(
        name = "linux.file_read",
        title = "读取 Linux 文件",
        description = "读取 Linux 环境里的文件。文本直接返回；二进制按 base64 返回。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("相对 rootfs 的路径"),
                "encoding" to Schema.string("期望编码", default = "auto", enum = listOf("auto", "text", "base64")),
                "maxBytes" to Schema.integer("最多读取字节", default = 65536, min = 1, max = 1048576),
            ),
            required = listOf("path"),
        ),
        readOnly = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            val distro = distroOf(ctx)
            requireEnv(ctx, distro)
            val rel = args.getString("path").trim().trim('/')
            checkNotBlocked(rel)
            val max = args.optInt("maxBytes", 65536).coerceIn(1, 1048576)
            val out = runBlocking {
                LinuxRuntime.exec(
                    ctx,
                    distro,
                    "head -c $max " + shellQuote("/$rel") + " | base64 -w0",
                    "/",
                    60000,
                )
            }
            if (!out.ok) {
                throw IllegalStateException(out.combined.ifBlank { "读取失败：/$rel" })
            }
            val bytes = runCatching { Base64.decode(out.stdout.trim(), Base64.DEFAULT) }
                .getOrDefault(ByteArray(0))
            val binary = bytes.any { it == 0.toByte() }
            val wanted = args.optString("encoding", "auto")
            val asBase64 = wanted == "base64" || (wanted == "auto" && binary)
            val structured = JSONObject().apply {
                put("path", "/$rel")
                put("bytesReturned", bytes.size)
                put("truncated", bytes.size >= max)
                put("binary", binary)
                put("encoding", if (asBase64) "base64" else "text")
                put(
                    "content",
                    if (asBase64) {
                        Base64.encodeToString(bytes, Base64.NO_WRAP)
                    } else {
                        String(bytes, Charsets.UTF_8)
                    },
                )
            }
            ToolResult(
                structured,
                if (asBase64) "（二进制，已按 base64 返回 ${bytes.size} 字节）" else String(bytes, Charsets.UTF_8),
            )
        },
    )

    private fun fileWrite(context: Context) = ToolDef(
        name = "linux.file_write",
        title = "写入 Linux 文件",
        description = "写入 Linux 环境里的文件，支持追加与 base64。需要在内置 Server 中开启「允许写入」。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("相对 rootfs 的路径"),
                "content" to Schema.string("内容；encoding=base64 时传 base64"),
                "append" to Schema.bool("是否追加", default = false),
                "encoding" to Schema.string("内容编码", default = "utf-8", enum = listOf("utf-8", "base64")),
            ),
            required = listOf("path", "content"),
        ),
        readOnly = false,
        dangerous = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            WriteGuard.requireWrite()
            val distro = distroOf(ctx)
            requireEnv(ctx, distro)
            val rel = args.getString("path").trim().trim('/')
            checkNotBlocked(rel)
            val raw = args.getString("content")
            val bytes = if (args.optString("encoding") == "base64") {
                Base64.decode(raw, Base64.DEFAULT)
            } else {
                raw.toByteArray(Charsets.UTF_8)
            }
            val parent = rel.substringBeforeLast('/', "")
            val redirect = if (args.optBoolean("append")) ">>" else ">"
            val script = buildString {
                append("mkdir -p ").append(shellQuote("/$parent")).append(" 2>/dev/null; ")
                append("printf '%s' ").append(shellQuote(Base64.encodeToString(bytes, Base64.NO_WRAP)))
                append(" | base64 -d ").append(redirect).append(" ").append(shellQuote("/$rel"))
            }
            val out = runBlocking { LinuxRuntime.exec(ctx, distro, script, "/", 60000) }
            if (!out.ok) {
                throw IllegalStateException(out.combined.ifBlank { "写入失败：/$rel" })
            }
            val structured = JSONObject().apply {
                put("path", "/$rel")
                put("writtenBytes", bytes.size)
                put("append", args.optBoolean("append"))
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun mountList(context: Context) = ToolDef(
        name = "linux.mount_list",
        title = "共享文件夹列表",
        description = "列出已经挂进 Linux 环境的 Android 目录（bind mount），以及用户配置的自定义挂载。",
        schema = Schema.obj(emptyList()),
        readOnly = true,
        handler = { ctx, _ ->
            val distro = distroOf(ctx)
            val active = if (LinuxRuntime.isReady(ctx, distro)) {
                runBlocking { readMounts(LinuxEnvStore.rootfs(ctx, distro)) }
            } else {
                emptyList()
            }
            val custom = LinuxPrefs.customMounts(ctx)
            val structured = JSONObject().apply {
                put(
                    "active",
                    JSONArray().also { array ->
                        active.forEach { (inner, _) ->
                            array.put(
                                JSONObject().apply {
                                    put("linuxPath", "/$inner")
                                    put("androidPath", androidPathOf(inner, custom))
                                },
                            )
                        }
                    },
                )
                put(
                    "configured",
                    JSONArray().also { array ->
                        custom.forEach { (src, dst) ->
                            array.put(
                                JSONObject().apply {
                                    put("androidPath", src)
                                    put("linuxPath", "/$dst")
                                },
                            )
                        }
                    },
                )
                put("note", "自定义挂载在下次进入 Linux 环境（执行命令或打开检测页）时生效")
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    private fun androidPathOf(inner: String, custom: List<Pair<String, String>>): String = when {
        inner == "dev" -> "/dev"
        inner == "proc" -> "/proc"
        inner == "sys" -> "/sys"
        inner == "sdcard" -> "/storage/emulated/0"
        inner.startsWith("mnt/android/") -> "/" + inner.removePrefix("mnt/android/")
        else -> custom.firstOrNull { it.second == inner }?.first ?: "自定义目录"
    }

    private fun mountAdd(context: Context) = ToolDef(
        name = "linux.mount_add",
        title = "添加共享文件夹",
        description = "把一个 Android 目录挂进 Linux 环境。下次进入 Linux 环境时生效。",
        schema = Schema.obj(
            listOf(
                "androidPath" to Schema.string("Android 侧绝对路径，如 /storage/emulated/0/Download"),
                "linuxPath" to Schema.string("Linux 内挂载点（相对 rootfs），如 mnt/download"),
            ),
            required = listOf("androidPath", "linuxPath"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            val src = args.getString("androidPath").trim()
            val dst = args.getString("linuxPath").trim().trim('/')
            if (src.isEmpty() || dst.isEmpty()) {
                throw IllegalArgumentException("androidPath 与 linuxPath 都不能为空")
            }
            if (!File(src).isDirectory) {
                throw IllegalArgumentException("Android 路径不存在或不是目录：$src")
            }
            val next = LinuxPrefs.customMounts(ctx).filterNot { it.second == dst } + (src to dst)
            LinuxPrefs.saveCustomMounts(ctx, next)
            val structured = JSONObject().apply {
                put("androidPath", src)
                put("linuxPath", "/$dst")
                put("total", next.size)
            }
            ToolResult(structured, "已添加：$src → /$dst（下次进入 Linux 环境生效）")
        },
    )

    private fun installApk(context: Context) = ToolDef(
        name = "android.install_apk",
        title = "安装 Android 应用",
        description = "把一个 APK 安装到本机。需要在内置 Server 中开启「允许写入」，且需要 root。" +
            "为绕开 SELinux 对共享存储的限制，会先把安装包复制到 /data/local/tmp 再安装。" +
            "返回包名、版本与安装结果。",
        schema = Schema.obj(
            listOf(
                "path" to Schema.string("APK 的绝对路径，如 /storage/emulated/0/Download/a.apk"),
                "reinstall" to Schema.bool("覆盖安装（-r）", default = true),
                "grant" to Schema.bool("安装时授予全部运行时权限（-g）", default = false),
                "downgrade" to Schema.bool("允许版本降级（-d）", default = false),
            ),
            required = listOf("path"),
        ),
        readOnly = false,
        dangerous = true,
        requiresPrivilege = true,
        handler = { ctx, args ->
            WriteGuard.requireWrite()
            val path = args.getString("path").trim()
            val source = File(path)
            if (!source.isFile) throw IllegalArgumentException("APK 不存在：$path")
            val flags = buildString {
                if (args.optBoolean("reinstall", true)) append(" -r")
                if (args.optBoolean("grant")) append(" -g")
                if (args.optBoolean("downgrade")) append(" -d")
            }
            val temp = "/data/local/tmp/eta-install-" + System.currentTimeMillis() + ".apk"
            val command = "cp " + shellQuote(path) + " " + shellQuote(temp) +
                " && pm install" + flags + " " + shellQuote(temp) +
                " ; rm -f " + shellQuote(temp)
            val result = runBlocking { PrivilegeManager.exec(command, 180000L) }
            val output = result.stdout.ifBlank { result.output }
            val ok = result.ok && !output.contains("Failure")
            val structured = JSONObject().apply {
                put("apk", path)
                put("sizeBytes", source.length())
                put("success", ok)
                put("backend", result.backend.name)
                put("output", output.take(2000))
            }
            ToolResult(structured, output.ifBlank { if (ok) "安装完成" else "安装失败" }, isError = !ok)
        },
    )

    private fun mountRemove(context: Context) = ToolDef(
        name = "linux.mount_remove",
        title = "移除共享文件夹",
        description = "移除一条自定义挂载（按 Linux 内挂载点匹配）。",
        schema = Schema.obj(
            listOf("linuxPath" to Schema.string("Linux 内挂载点，如 mnt/download")),
            required = listOf("linuxPath"),
        ),
        readOnly = false,
        handler = { ctx, args ->
            val dst = args.getString("linuxPath").trim().trim('/')
            val before = LinuxPrefs.customMounts(ctx)
            val after = before.filterNot { it.second == dst }
            if (after.size == before.size) throw IllegalArgumentException("没有找到挂载点：/$dst")
            LinuxPrefs.saveCustomMounts(ctx, after)
            val structured = JSONObject().apply {
                put("removed", "/$dst")
                put("total", after.size)
            }
            ToolResult(structured, "已移除 /$dst")
        },
    )
}

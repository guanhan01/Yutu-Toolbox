package com.mcp.toolbox.feature.mcp

import android.content.Context
import com.mcp.toolbox.feature.decompile.engine.ApkSignerEngine
import com.mcp.toolbox.feature.decompile.engine.RebuildApi
import com.mcp.toolbox.feature.decompile.engine.RebuildPipeline
import com.mcp.toolbox.feature.decompile.engine.SignKeys
import com.mcp.toolbox.feature.decompile.engine.SmaliAssembler
import com.mcp.toolbox.feature.decompile.engine.SmaliEngine
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 深度逆向工具集：反汇编落盘 -> 改 -> 回编译 -> 重打包 -> 重新签名 -> 校验。
 *
 * 与 BuiltInToolSetApk/Dex 的区别：那些是只读分析（看），这里是可写闭环（改完能出可安装 APK）。
 * 全部在设备上完成，不依赖 apktool / aapt2 —— 因此修改代码不受外部工具链限制。
 */
object BuiltInToolSetReverse {

    fun all(context: Context): List<ToolDef> = listOf(
        decodeSmali(),
        buildRebuild(),
        buildAssemble(),
        buildSign(),
        buildVerify(),
        keystoreManage(),
    )

    private fun requireWrite() {
        if (!BuiltInMcpServer.config.value.allowWrite) {
            error("内置 Server 未开启写入：请在 MCP 页面打开「允许写入」后重试")
        }
    }

    private fun safe(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(48)

    private fun workRoot(context: Context, kind: String): File {
        val dir = File(ArtifactStore.root(context), kind)
        dir.mkdirs()
        return dir
    }

    // ------------------------------------------------------------------
    // 1. 反汇编落盘
    // ------------------------------------------------------------------
    private fun decodeSmali() = ToolDef(
        name = "decode.smali",
        title = "反汇编 APK 为 smali 工程",
        description = "用自带 baksmali 引擎把 APK 的全部 dex 反汇编成可读可改的 smali 目录，供 build.rebuild 回编译。" +
            "多 dex 的 APK 会按 classes.dex / classes2.dex 分子目录，回编译时逐 dex 汇编，不受单 dex 64K 方法数限制。",
        schema = Schema.obj(
            listOf(
                "apk" to Schema.string("APK 绝对路径（可用 apk.sources 获取）"),
                "output" to Schema.string("输出 smali 目录，可空则写入产物目录"),
            ),
            required = listOf("apk"),
        ),
        handler = { ctx, args ->
            requireWrite()
            val structured = runBlocking {
                val apk = File(args.getString("apk").trim())
                require(apk.isFile) { "APK 不存在：${apk.absolutePath}" }
                val dir = args.optString("output").trim().takeIf { it.isNotEmpty() }?.let { File(it) }
                    ?: File(workRoot(ctx, "decode/${safe(apk.nameWithoutExtension)}"), "smali")
                dir.mkdirs()

                val started = System.currentTimeMillis()
                var lastMessage = ""
                SmaliEngine.disassemble(apk, dir) { _, message -> lastMessage = message }

                val smaliFiles = dir.walkTopDown().count { it.isFile && it.name.endsWith(".smali") }
                val dexDirs = dir.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted().orEmpty()
                val splitByDex = dexDirs.any { it.endsWith(".dex") }

                JSONObject().apply {
                    put("smaliDir", dir.absolutePath)
                    put("smaliFiles", smaliFiles)
                    put("dexDirs", JSONArray(dexDirs))
                    put("layout", if (splitByDex) "按 dex 分目录" else "单一 smali 目录")
                    put("elapsedMs", System.currentTimeMillis() - started)
                    put("lastMessage", lastMessage)
                    put("next", "改完 smali 后调用 build.rebuild，参数 smaliDir=$dir")
                }
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    // ------------------------------------------------------------------
    // 2. 一条龙：smali -> 签名 APK
    // ------------------------------------------------------------------
    private fun buildRebuild() = ToolDef(
        name = "build.rebuild",
        title = "重打包并签名",
        description = "把改过的 smali 汇编成 dex、替换进底包 APK、剥离旧签名，再用内置自签名密钥签 v1+v2+v3，输出可直接安装的 APK。" +
            "注意：换了签名就无法覆盖安装原应用（签名不一致），需要先卸载，或者改包名后作为新应用安装。",
        schema = Schema.obj(
            listOf(
                "apk" to Schema.string("底包 APK 绝对路径（必须与 smali 来源同一个 APK）"),
                "smaliDir" to Schema.string("改过的 smali 目录"),
                "output" to Schema.string("输出 APK 路径，可空则写入 <产物目录>/rebuild/"),
                "apiLevel" to Schema.integer("smali 汇编 API 级别", default = 34, min = 26, max = 40),
                "minSdk" to Schema.integer("签名用 minSdk（影响签名方案选择）", default = 21, min = 21, max = 40),
                "path" to Schema.string("可选的 classes.dex 类条目路径，默认按 smaliDir 布局自动判断"),
            ),
            required = listOf("apk", "smaliDir"),
        ),
        handler = { ctx, args ->
            requireWrite()
            val structured = runBlocking {
                val apk = File(args.getString("apk").trim())
                val smali = File(args.getString("smaliDir").trim())
                require(apk.isFile) { "底包不存在：${apk.absolutePath}" }
                require(smali.isDirectory) { "smali 目录不存在：${smali.absolutePath}" }

                val apiLevel = args.optInt("apiLevel", 34).coerceIn(26, 40)
                val minSdk = args.optInt("minSdk", 21).coerceIn(21, 40)
                val work = workRoot(ctx, "rebuild/${safe(apk.nameWithoutExtension)}")
                val out = args.optString("output").trim().takeIf { it.isNotEmpty() }?.let { File(it) }
                    ?: File(work, "${safe(apk.nameWithoutExtension)}-signed.apk")

                val result = RebuildPipeline.run(
                    context = ctx,
                    baseApk = apk,
                    smaliRoot = smali,
                    workDir = work,
                    outApk = out,
                    apiLevel = apiLevel,
                    minSdk = minSdk,
                )
                val verify = RebuildApi.verify(result.signedApk)

                JSONObject().apply {
                    put("signedApk", result.signedApk.absolutePath)
                    put("sizeBytes", result.signedApk.length())
                    put("sizeHuman", ToolSupport.human(result.signedApk.length()))
                    put("sha256", result.sign.sha256)
                    put("schemes", result.sign.schemes)
                    put("keyAlias", result.sign.alias)
                    put("keySubject", result.sign.subject)
                    put("keyCreatedThisRun", result.keyCreated)
                    put(
                        "dexes",
                        JSONArray(result.dexes.map { dex ->
                            JSONObject().apply {
                                put("entry", dex.entryName)
                                put("bytes", dex.bytes)
                            }
                        }),
                    )
                    put("replacedEntries", JSONArray(result.rebuild.replaced))
                    put("addedEntries", JSONArray(result.rebuild.added))
                    put("strippedSignatures", JSONArray(result.rebuild.removed))
                    put(
                        "verify",
                        JSONObject().apply {
                            put("ok", verify.verified)
                            put("v1", verify.v1)
                            put("v2", verify.v2)
                            put("v3", verify.v3)
                            put("signers", verify.signerCount)
                            put("subject", verify.subject ?: JSONObject.NULL)
                            put("errors", JSONArray(verify.errors))
                        },
                    )
                    put("steps", JSONArray(result.steps))
                    put("installHint", "adb install -r \"${result.signedApk.absolutePath}\"（与原应用签名不同，需先卸载原应用）")
                }
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    // ------------------------------------------------------------------
    // 3. 只汇编 dex，不出 APK
    // ------------------------------------------------------------------
    private fun buildAssemble() = ToolDef(
        name = "build.assemble",
        title = "汇编 smali 为 dex",
        description = "只把 smali 目录汇编成 dex 文件并落盘，用于单独取出 classes.dex 或做后续手工处理。",
        schema = Schema.obj(
            listOf(
                "smaliDir" to Schema.string("smali 目录"),
                "output" to Schema.string("输出目录，可空则写入产物目录"),
                "apiLevel" to Schema.integer("汇编 API 级别", default = 34, min = 26, max = 40),
            ),
            required = listOf("smaliDir"),
        ),
        handler = { ctx, args ->
            requireWrite()
            val structured = runBlocking {
                val smali = File(args.getString("smaliDir").trim())
                require(smali.isDirectory) { "smali 目录不存在：${smali.absolutePath}" }
                val apiLevel = args.optInt("apiLevel", 34).coerceIn(26, 40)
                val outDir = args.optString("output").trim().takeIf { it.isNotEmpty() }?.let { File(it) }
                    ?: File(workRoot(ctx, "dex/${safe(smali.parentFile?.name ?: "smali")}"), "dex")

                val started = System.currentTimeMillis()
                val dexes = SmaliAssembler.assemble(smali, outDir, apiLevel)

                JSONObject().apply {
                    put("outDir", outDir.absolutePath)
                    put(
                        "dexes",
                        JSONArray(dexes.map { dex ->
                            JSONObject().apply {
                                put("entry", dex.entryName)
                                put("path", dex.file.absolutePath)
                                put("bytes", dex.bytes)
                                put("sha256", ApkSignerEngine.sha256(dex.file))
                            }
                        }),
                    )
                    put("elapsedMs", System.currentTimeMillis() - started)
                }
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    // ------------------------------------------------------------------
    // 4. 给任意 APK 重新签名
    // ------------------------------------------------------------------
    private fun buildSign() = ToolDef(
        name = "build.sign",
        title = "重新签名 APK",
        description = "用内置自签名密钥给一个未签名（或已剥离签名）的 APK 签 v1/v2/v3。如果输入是已签名 APK，" +
            "请先用 build.rebuild 剥离，或直接改签名会失败。",
        schema = Schema.obj(
            listOf(
                "apk" to Schema.string("待签名 APK 绝对路径"),
                "output" to Schema.string("输出 APK 路径，可空则写入 <产物目录>/sign/"),
                "minSdk" to Schema.integer("签名用 minSdk", default = 21, min = 21, max = 40),
                "v1" to Schema.bool("启用 v1（JAR）签名", default = true),
                "v2" to Schema.bool("启用 v2 签名（Android 11+ 对 targetSdk>=30 强制）", default = true),
                "v3" to Schema.bool("启用 v3 签名", default = true),
            ),
            required = listOf("apk"),
        ),
        handler = { ctx, args ->
            requireWrite()
            val structured = runBlocking {
                val input = File(args.getString("apk").trim())
                require(input.isFile) { "APK 不存在：${input.absolutePath}" }
                val out = args.optString("output").trim().takeIf { it.isNotEmpty() }?.let { File(it) }
                    ?: File(workRoot(ctx, "sign"), "${safe(input.nameWithoutExtension)}-signed.apk")

                val key = SignKeys.ensure(ctx)
                val report = ApkSignerEngine.sign(
                    input = input,
                    out = out,
                    key = key,
                    minSdk = args.optInt("minSdk", 21).coerceIn(21, 40),
                    v1 = args.optBoolean("v1", true),
                    v2 = args.optBoolean("v2", true),
                    v3 = args.optBoolean("v3", true),
                )
                val verify = RebuildApi.verify(out)

                JSONObject().apply {
                    put("signedApk", report.outApk.absolutePath)
                    put("sizeBytes", report.size)
                    put("sizeHuman", ToolSupport.human(report.size))
                    put("sha256", report.sha256)
                    put("schemes", report.schemes)
                    put("keyAlias", report.alias)
                    put("keyCreatedThisRun", key.fresh)
                    put(
                        "verify",
                        JSONObject().apply {
                            put("ok", verify.verified)
                            put("v1", verify.v1)
                            put("v2", verify.v2)
                            put("v3", verify.v3)
                            put("errors", JSONArray(verify.errors))
                        },
                    )
                }
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    // ------------------------------------------------------------------
    // 5. 校验签名
    // ------------------------------------------------------------------
    private fun buildVerify() = ToolDef(
        name = "build.verify",
        title = "校验 APK 签名",
        description = "用 apksig 校验 APK 的 v1/v2/v3 签名是否有效，返回签名者、证书指纹与错误明细。",
        schema = Schema.obj(
            listOf("apk" to Schema.string("APK 绝对路径")),
            required = listOf("apk"),
        ),
        handler = { _, args ->
            val structured = runBlocking {
                val apk = File(args.getString("apk").trim())
                val report = RebuildApi.verify(apk)
                JSONObject().apply {
                    put("apk", apk.absolutePath)
                    put("verified", report.verified)
                    put("v1", report.v1)
                    put("v2", report.v2)
                    put("v3", report.v3)
                    put("signers", report.signerCount)
                    put("subject", report.subject ?: JSONObject.NULL)
                    put("certSha256", report.sha256 ?: JSONObject.NULL)
                    put("errors", JSONArray(report.errors))
                    put("warnings", JSONArray(report.warnings))
                }
            }
            ToolResult(structured, structured.toString(2))
        },
    )

    // ------------------------------------------------------------------
    // 6. 签名密钥管理
    // ------------------------------------------------------------------
    private fun keystoreManage() = ToolDef(
        name = "keystore.manage",
        title = "重打包签名密钥",
        description = "查看/生成/重置重打包用的自签名密钥。复用同一密钥，重打包产物之间才能互相覆盖安装。",
        schema = Schema.obj(
            listOf(
                "action" to Schema.string("info=查看（默认）｜create=没有就生成｜reset=删除后重新生成", default = "info", enum = listOf("info", "create", "reset")),
            ),
        ),
        handler = { ctx, args ->
            val structured = runBlocking {
                val action = args.optString("action", "info").ifBlank { "info" }
                var created = false
                when (action) {
                    "create" -> {
                        requireWrite()
                        created = SignKeys.ensure(ctx).fresh
                    }
                    "reset" -> {
                        requireWrite()
                        SignKeys.delete(ctx)
                        SignKeys.create(ctx)
                        created = true
                    }
                }
                val summary = RebuildApi.keySummary(ctx)
                JSONObject().apply {
                    put("action", action)
                    put("exists", summary != null)
                    put("created", created)
                    if (summary == null) {
                        put("hint", "还没有密钥，调用 build.rebuild / build.sign 会自动生成，或 action=create")
                    } else {
                        summary.forEach { (k, v) -> put(k, v ?: JSONObject.NULL) }
                    }
                }
            }
            ToolResult(structured, structured.toString(2))
        },
    )
}

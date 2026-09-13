package com.mcp.toolbox.feature.decompile.engine

import java.io.File

/**
 * jadx 引擎：把 dex 反编译为 Java 源码。
 * 通过反射探测可用性；缺依赖 / 内存不足时抛出 JadxUnavailable，由上层降级到 smali。
 */
object JadxEngine {

    class JadxUnavailable(message: String, cause: Throwable? = null) : Exception(message, cause)

    data class Handle(val decompiler: Any, val classes: List<Any>, private val jadxClass: Class<*>, val diagnostics: String = "")

    /** 反射探测 jadx-core 是否真的可用（可选的重量级依赖）。 */
    fun availability(): String? = try {
        Class.forName("jadx.api.JadxDecompiler")
        Class.forName("jadx.api.JadxArgs")
        null
    } catch (error: Throwable) {
        "jadx-core 不可用：${error.javaClass.simpleName} ${error.message ?: ""}"
    }

    fun open(apkFile: File, outDir: File?, singleClass: String? = null, threads: Int = 2): Handle {
        availability()?.let { throw JadxUnavailable(it) }
        installXmlFactories()
        val args = Class.forName("jadx.api.JadxArgs").getDeclaredConstructor().newInstance()
        runCatching {
            args.javaClass
                .getMethod("setSecurity", Class.forName("jadx.api.security.IJadxSecurity"))
                .invoke(args, CompatSecurity)
        }
        args.javaClass.getMethod("setInputFiles", java.util.List::class.java)
            .invoke(args, listOf(apkFile))
        args.javaClass.getMethod("setSkipResources", Boolean::class.javaPrimitiveType).invoke(args, true)
        args.javaClass.getMethod("setThreadsCount", Int::class.javaPrimitiveType).invoke(args, threads)
        args.javaClass.getMethod("setShowInconsistentCode", Boolean::class.javaPrimitiveType).invoke(args, true)
        args.javaClass.getMethod("setDeobfuscationOn", Boolean::class.javaPrimitiveType).invoke(args, false)
        if (outDir != null) {
            outDir.mkdirs()
            args.javaClass.getMethod("setOutDir", File::class.java).invoke(args, outDir)
        }
        val decompiler = newDecompiler(args)
        // jadx 1.5 把 dex/apk 输入拆成了独立插件：插件没被加载时 load() 不会报错，
        // 但一个 dex 类都不进来，最后只产出资源生成的 R.java（曾经的"Java 只有一个文件夹"）。
        val viaServiceLoader = serviceLoaderHasPlugin()
        val explicit = if (viaServiceLoader) false else registerDexPlugin(decompiler)
        load(decompiler)
        val classes = classesOf(decompiler)
        @Suppress("UNUSED_PARAMETER") val unused = singleClass
        // 诊断：jadx 只出一个 R.java 时，这里能看出到底加载了多少类、输入文件是否可读。
        val inputOk = apkFile.exists() && apkFile.canRead()
        val diag = buildString {
            append("输入可读=")
            append(inputOk)
            append("\n输入大小=")
            append(apkFile.length())
            append("\n加载类数=")
            append(classes.size)
            append("\n输入插件=")
            append(
                when {
                    explicit -> "显式注册 DexInputPlugin"
                    viaServiceLoader -> "ServiceLoader"
                    else -> "未找到（没有 dex 输入插件）"
                },
            )
        }
        return Handle(decompiler, classes, decompiler.javaClass, diag)
    }

    /**
     * jadx 内部会用 Apache XML 特性配置 DocumentBuilderFactory，Android 自带 JAXP 会抛
     * ParserConfigurationException。这里把工厂指向随包携带的 Xerces 实现。
     */
    private fun installXmlFactories() {
        if (xercesInstalled) return
        val factories = listOf(
            "javax.xml.parsers.DocumentBuilderFactory" to "org.apache.xerces.jaxp.DocumentBuilderFactoryImpl",
            "javax.xml.parsers.SAXParserFactory" to "org.apache.xerces.jaxp.SAXParserFactoryImpl",
        )
        factories.forEach { (key, impl) ->
            val present = runCatching { Class.forName(impl) }.isSuccess
            if (present && System.getProperty(key) == null) runCatching { System.setProperty(key, impl) }
        }
        xercesInstalled = true
    }

    @Volatile
    private var xercesInstalled = false

    private fun newDecompiler(args: Any): Any = try {
        Class.forName("jadx.api.JadxDecompiler")
            .getConstructor(Class.forName("jadx.api.JadxArgs"))
            .newInstance(args)
    } catch (error: Throwable) {
        throw JadxUnavailable("jadx 初始化失败：${rootMessage(error)}", error)
    }

    private fun load(decompiler: Any) {
        try {
            decompiler.javaClass.getMethod("load").invoke(decompiler)
        } catch (error: Throwable) {
            throw JadxUnavailable("jadx 解析 dex 失败：${rootMessage(error)}", error)
        }
    }

    private fun classesOf(decompiler: Any): List<Any> =
        (runCatching { decompiler.javaClass.getMethod("getClasses").invoke(decompiler) }.getOrNull() as? List<*>)
            ?.filterNotNull()
            ?: emptyList()

    /** 插件能不能被 ServiceLoader 发现；Android 合并 jar 资源时这个注册文件有时会丢。 */
    private fun serviceLoaderHasPlugin(): Boolean = runCatching {
        val type = Class.forName("jadx.api.plugins.JadxPlugin")
        @Suppress("UNCHECKED_CAST")
        java.util.ServiceLoader.load(type as Class<Any>).iterator().hasNext()
    }.getOrDefault(false)

    /** 显式注册 dex 输入插件，绕开 ServiceLoader。 */
    private fun registerDexPlugin(decompiler: Any): Boolean = runCatching {
        val plugin = Class.forName("jadx.plugins.input.dex.DexInputPlugin").getDeclaredConstructor().newInstance()
        val pluginType = Class.forName("jadx.api.plugins.JadxPlugin")
        decompiler.javaClass.getMethod("registerPlugin", pluginType).invoke(decompiler, plugin)
        true
    }.getOrDefault(false)

    fun classNameOf(javaClass: Any): String =
        runCatching { javaClass.javaClass.getMethod("getFullName").invoke(javaClass) as? String }
            .getOrNull() ?: "unknown"

    fun codeOf(javaClass: Any): String =
        runCatching { javaClass.javaClass.getMethod("getCode").invoke(javaClass) as? String }
            .getOrNull() ?: "// 该类反编译结果为空"

    fun findClass(handle: Handle, className: String): Any? = handle.classes.firstOrNull { classNameOf(it) == className }

    fun saveAll(handle: Handle): String? = runCatching {
        handle.decompiler.javaClass.getMethod("save").invoke(handle.decompiler)
        val outDir = handle.decompiler.javaClass.getMethod("getArgs").invoke(handle.decompiler)
        val dir = outDir.javaClass.getMethod("getOutDir").invoke(outDir) as? File
        dir?.absolutePath
    }.getOrNull()

    fun close(handle: Handle) {
        runCatching { handle.decompiler.javaClass.getMethod("close").invoke(handle.decompiler) }
    }

    private fun rootMessage(error: Throwable): String {
        var current: Throwable = error
        while (current.cause != null && current.cause !== current) current = current.cause!!
        return "${current.javaClass.simpleName}: ${current.message ?: "无详细信息"}"
    }
}

package com.mcp.toolbox.feature.decompile

/** 反编译引擎类别：界面右侧代码视图的切换维度。 */
enum class DecompileView(val label: String) {
    JAVA("Java"),
    SMALI("Smali"),
    MANIFEST("清单"),
    RESOURCES("资源"),
}

enum class EngineKind(val label: String, val available: Boolean) {
    SMALI("baksmali 轻量引擎", true),
    JADX("jadx 完整源码", true),
}

data class CertInfo(
    val subject: String,
    val issuer: String,
    val sha256: String,
    val sha1: String,
    val algorithm: String,
    val serial: String,
    val notBefore: String,
    val notAfter: String,
    val fileName: String,
)

data class ApkSummary(
    val displayName: String,
    val path: String,
    val sizeBytes: Long,
    val md5: String,
    val sha256: String,
    val entryCount: Int,
    val dexEntries: List<String>,
    val nativeLibs: List<String>,
    val certificate: CertInfo?,
) {
    val signed: Boolean get() = certificate != null
}

data class MemberInfo(
    val signature: String,
    val name: String,
    val kind: MemberKind,
    val accessText: String,
    val isAbstract: Boolean,
)

enum class MemberKind(val label: String) { METHOD("方法"), FIELD("字段"), CONSTRUCTOR("构造") }

data class ClassInfo(
    val descriptor: String,
    val name: String,
    val packageName: String,
    val simpleName: String,
    val superName: String?,
    val interfaces: List<String>,
    val accessText: String,
    val sourceFile: String?,
    val methods: List<MemberInfo>,
    val fields: List<MemberInfo>,
    val dexEntry: String,
)

data class ComponentGroup(val kind: String, val items: List<String>)

data class ManifestInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: String?,
    val minSdk: String?,
    val targetSdk: String?,
    val permissions: List<String>,
    val declaredPermissions: List<String>,
    val groups: List<ComponentGroup>,
    val usesFeatures: List<String>,
    val xml: String,
    val attributeCount: Int,
)

data class ResourceEntry(
    val type: String,
    val name: String,
    val value: String,
    val resId: Int,
    val complex: Boolean,
)

data class ResourceTableInfo(
    val packages: List<String>,
    val typeCounts: List<Pair<String, Int>>,
    val entries: List<ResourceEntry>,
    val configCount: Int,
)

data class SearchHit(
    val kind: String,
    val owner: String,
    val text: String,
    val detail: String,
)

enum class TaskState { QUEUED, RUNNING, DONE, FAILED, CANCELLED }

data class TaskRecord(
    val id: String,
    val apkName: String,
    val engine: String,
    val state: TaskState,
    val progress: Float,
    val message: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val classCount: Int = 0,
    val methodCount: Int = 0,
    val fieldCount: Int = 0,
    val stringCount: Int = 0,
    val error: String? = null,
    val outputDir: String? = null,
    val elapsedMs: Long = 0,
)

/** 一次分析产物的内存索引，供界面各 Tab 复用。 */
data class DecompileSession(
    val summary: ApkSummary,
    val classes: List<ClassInfo>,
    val manifest: ManifestInfo?,
    val resources: ResourceTableInfo?,
    val stringPool: List<String> = emptyList(),
    val smaliDir: String? = null,
    val javaDir: String? = null,
) {
    val methodCount: Int get() = classes.sumOf { it.methods.size }
    val fieldCount: Int get() = classes.sumOf { it.fields.size }
    val packageCount: Int get() = classes.map { it.packageName }.distinct().size
}

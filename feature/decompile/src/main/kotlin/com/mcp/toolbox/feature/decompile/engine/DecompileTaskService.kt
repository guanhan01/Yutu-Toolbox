package com.mcp.toolbox.feature.decompile.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import com.mcp.toolbox.feature.decompile.TaskRecord
import com.mcp.toolbox.feature.decompile.TaskState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * 反编译前台服务：长任务在服务里跑，通知栏显示实时进度；
 * 结果写入 DecompileHub 供界面订阅，并持久化任务历史。
 */
class DecompileTaskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var notifications: NotificationManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "反编译任务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "反编译 / smali 反汇编任务进度"
                setShowBadge(false)
            }
            notifications.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: ACTION_ANALYZE
        val source = sourceOf(intent)
        if (source == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val record = TaskRecord(
            id = UUID.randomUUID().toString().take(8),
            apkName = source.label,
            engine = actionLabel(action),
            state = TaskState.RUNNING,
            progress = 0f,
            message = "已入队",
            startedAt = System.currentTimeMillis(),
        )
        DecompileHub.startTask(record)
        DecompileHub.activeSource.value = source
        startForeground(NOTIFICATION_ID, buildNotification(record))
        val started = System.currentTimeMillis()
        scope.launch {
            try {
                when (action) {
                    ACTION_ANALYZE -> runAnalyze(source, record)
                    ACTION_SMALI -> runSmali(source, record)
                    ACTION_JAVA -> runJava(source, record)
                    else -> Unit
                }
            } catch (error: Throwable) {
                val message = error.javaClass.simpleName + ": " + (error.message ?: "无详细信息")
                DecompileHub.updateTask(record.id) {
                    it.copy(
                        state = TaskState.FAILED,
                        message = "失败",
                        error = message,
                        finishedAt = System.currentTimeMillis(),
                        elapsedMs = System.currentTimeMillis() - started,
                    )
                }
                post(buildNotification(DecompileHub.tasks.value.first(), failed = true))
            } finally {
                DecompileHub.persist(applicationContext)
                stopForegroundCompat()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun runAnalyze(source: ApkSource, record: TaskRecord) {
        val started = System.currentTimeMillis()
        val session = DecompileEngine.analyze(applicationContext, source) { progress ->
            DecompileHub.updateTask(record.id) {
                it.copy(progress = progress.fraction, message = progress.message)
            }
            post(buildNotification(DecompileHub.tasks.value.first()))
        }
        DecompileHub.session.value = session
        DecompileHub.javaCache.clear()
        DecompileHub.smaliFiles.value = listOf()
        val outputDir = DecompileEngine.exportTexts(
            session,
            File(DecompileEngine.sessionDir(applicationContext, File(session.summary.path), source.label), "export"),
        ).firstOrNull()?.parentFile
        DecompileHub.updateTask(record.id) {
            it.copy(
                state = TaskState.DONE,
                progress = 1f,
                message = "分析完成",
                finishedAt = System.currentTimeMillis(),
                classCount = session.classes.size,
                methodCount = session.methodCount,
                fieldCount = session.fieldCount,
                stringCount = session.stringPool.size,
                elapsedMs = System.currentTimeMillis() - started,
                outputDir = outputDir?.absolutePath,
            )
        }
        post(buildNotification(DecompileHub.tasks.value.first()))
    }

    private fun runSmali(source: ApkSource, record: TaskRecord) {
        val started = System.currentTimeMillis()
        val result = DecompileEngine.disassemble(applicationContext, source) { progress ->
            DecompileHub.updateTask(record.id) { it.copy(progress = progress.fraction, message = progress.message) }
            post(buildNotification(DecompileHub.tasks.value.first()))
        }
        val dir = result.dir
        val files = File(dir).walkTopDown().filter { it.isFile && it.extension == "smali" }
            .map { it.relativeTo(File(dir)).path.replace('\\', '/') }
            .sorted()
            .take(60000)
            .toList()
        DecompileHub.smaliFiles.value = files
        DecompileHub.session.value = DecompileHub.session.value?.copy(smaliDir = dir)
        DecompileHub.updateTask(record.id) {
            it.copy(
                // 一个类都没写出来才算失败；部分成功就如实汇报，不能再假装成功。
                state = if (result.writtenClasses == 0 && result.failedDexes.isNotEmpty()) {
                    TaskState.FAILED
                } else {
                    TaskState.DONE
                },
                progress = 1f,
                message = buildString {
                    append("smali 输出 ${result.writtenClasses} 个类（${result.dexCount} 个 dex）")
                    if (result.skipped > 0) append("，跳过 ${result.skipped} 个无法分析的类型")
                    if (result.failedDexes.isNotEmpty()) append("，${result.failedDexes.size} 个 dex 失败")
                },
                error = result.failedDexes.takeIf { result.writtenClasses == 0 }?.joinToString("；"),
                finishedAt = System.currentTimeMillis(),
                elapsedMs = System.currentTimeMillis() - started,
                outputDir = dir,
            )
        }
        post(buildNotification(DecompileHub.tasks.value.first()))
    }

    private fun runJava(source: ApkSource, record: TaskRecord) {
        val started = System.currentTimeMillis()
        val outcome = DecompileEngine.decompileJava(applicationContext, source) { progress ->
            DecompileHub.updateTask(record.id) { it.copy(progress = progress.fraction, message = progress.message) }
            post(buildNotification(DecompileHub.tasks.value.first()))
        }
        DecompileHub.session.value = DecompileHub.session.value?.copy(javaDir = outcome.dir)
        DecompileHub.updateTask(record.id) {
            it.copy(
                // 一个 .java 都没写出来（= dex 根本没进来或全失败）才算失败，不能再假装成功。
                state = if (outcome.writtenFiles == 0 && outcome.dexCount > 0) TaskState.FAILED else TaskState.DONE,
                progress = 1f,
                message = buildString {
                    append("Java 源码 ${outcome.writtenFiles} 个文件 / ${outcome.loadedClasses} 个类")
                    append("（${outcome.dexCount} 个 dex）")
                    if (outcome.failedDexes.isNotEmpty()) append("，${outcome.failedDexes.size} 个失败")
                },
                error = outcome.failedDexes
                    .takeIf { outcome.writtenFiles == 0 || it.isNotEmpty() }
                    ?.joinToString("、")
                    ?.take(400),
                finishedAt = System.currentTimeMillis(),
                elapsedMs = System.currentTimeMillis() - started,
                outputDir = outcome.dir,
            )
        }
        post(buildNotification(DecompileHub.tasks.value.first()))
    }

    private fun buildNotification(record: TaskRecord, failed: Boolean = false): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("反编译 · ${record.engine}")
            .setContentText("${record.apkName} · ${record.message}")
            .setOngoing(record.state == TaskState.RUNNING)
            .setOnlyAlertOnce(true)
            .setProgress(100, (record.progress * 100).toInt().coerceIn(0, 100), record.state == TaskState.RUNNING)
        launch?.let { builder.setContentIntent(android.app.PendingIntent.getActivity(this, 0, it, PENDING_FLAGS)) }
        if (failed) builder.setContentText("${record.apkName} · ${record.error ?: "失败"}")
        return builder.build()
    }

    private fun post(notification: Notification) {
        runCatching { notifications.notify(NOTIFICATION_ID, notification) }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun sourceOf(intent: Intent?): ApkSource? {
        val type = intent?.getStringExtra(EXTRA_SOURCE_TYPE) ?: return null
        val label = intent.getStringExtra(EXTRA_SOURCE_LABEL) ?: "APK"
        return when (type) {
            "self" -> ApkSource.SelfApp
            "pkg" -> intent.getStringExtra(EXTRA_SOURCE_PKG)?.let { ApkSource.InstalledApp(it, label) }
            "uri" -> intent.getStringExtra(EXTRA_SOURCE_URI)?.let { ApkSource.PickedFile(Uri.parse(it), label) }
            "path" -> intent.getStringExtra(EXTRA_SOURCE_PATH)?.let { ApkSource.LocalPath(it) }
            else -> null
        }
    }

    companion object {
        const val CHANNEL_ID = "decompile-tasks"
        const val NOTIFICATION_ID = 0x2DE1
        const val EXTRA_ACTION = "action"
        const val EXTRA_SOURCE_TYPE = "source_type"
        const val EXTRA_SOURCE_PKG = "source_pkg"
        const val EXTRA_SOURCE_URI = "source_uri"
        const val EXTRA_SOURCE_LABEL = "source_label"
        const val EXTRA_SOURCE_PATH = "source_path"

        const val ACTION_ANALYZE = "analyze"
        const val ACTION_SMALI = "smali"
        const val ACTION_JAVA = "java"

        private const val PENDING_FLAGS = android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE

        fun actionLabel(action: String): String = when (action) {
            ACTION_ANALYZE -> "索引分析"
            ACTION_SMALI -> "smali 反汇编"
            ACTION_JAVA -> "jadx Java 源码"
            else -> action
        }

        fun launch(context: Context, action: String, source: ApkSource) {
            val intent = Intent(context, DecompileTaskService::class.java).apply {
                putExtra(EXTRA_ACTION, action)
                putExtra(EXTRA_SOURCE_LABEL, source.label)
                when (source) {
                    is ApkSource.SelfApp -> putExtra(EXTRA_SOURCE_TYPE, "self")
                    is ApkSource.InstalledApp -> {
                        putExtra(EXTRA_SOURCE_TYPE, "pkg")
                        putExtra(EXTRA_SOURCE_PKG, source.packageName)
                    }
                    is ApkSource.PickedFile -> {
                        putExtra(EXTRA_SOURCE_TYPE, "uri")
                        putExtra(EXTRA_SOURCE_URI, source.uri.toString())
                    }
                    is ApkSource.LocalPath -> {
                        putExtra(EXTRA_SOURCE_TYPE, "path")
                        putExtra(EXTRA_SOURCE_PATH, source.path)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

package com.mcp.toolbox.core.common

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 无障碍服务：让 AI **无需 root / Shizuku** 就能点击、滑动、输入、读界面、截屏。
 *
 * 与 `PrivilegeManager` 的关系：那条路走 shell（`input` / `screencap` / `uiautomator`），
 * 需要 root 或 Shizuku；这条路走系统无障碍 API，只需要用户在设置里手动开启一次。
 * 两者能力有交集，调用方应优先用本服务（免提权），不可用时再回落到 shell。
 *
 * 设计取舍：
 * - 服务本身**不做任何自主操作**，只在工具被调用时执行一条指令，没有轮询、没有自动化脚本。
 * - 事件类型只订阅窗口变化（用于判断界面是否稳定），不订阅按键与触摸，避免干扰用户。
 */
class ToolboxAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 只需要知道「界面变了」，不需要内容；保持空实现，避免无谓开销。
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            lastWindowChangeAt = System.currentTimeMillis()
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun rootOrNull(): AccessibilityNodeInfo? =
        rootInActiveWindow ?: windows.firstOrNull { it.isActive }?.root

    companion object {

        @Volatile
        private var instance: ToolboxAccessibilityService? = null

        @Volatile
        private var lastWindowChangeAt: Long = 0L

        /** 服务是否已连接（即用户已开启无障碍）。 */
        val connected: Boolean get() = instance != null

        /**
         * 是否已在系统设置里启用。
         *
         * 比 [connected] 更早可用：刚开机或进程刚起时服务可能还没连上，
         * 但设置项已经是开启状态，界面应显示「已开启」而不是「未开启」。
         */
        fun enabledInSettings(context: Context): Boolean {
            val expected = ComponentName(context, ToolboxAccessibilityService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':')
            splitter.setString(enabled)
            while (splitter.hasNext()) {
                if (ComponentName.unflattenFromString(splitter.next()) == expected) return true
            }
            return false
        }

        /** 界面距上次变化过了多久；用来等动画结束再点击。 */
        fun quietMillis(): Long = System.currentTimeMillis() - lastWindowChangeAt

        /** 当前窗口信息（前台包名 / Activity 类名）。 */
        data class WindowInfo(val packageName: String, val activity: String, val focus: String)

        /**
         * 读当前活动窗口。
         *
         * 走 windows + rootInActiveWindow，不需要 dumpsys，所以免 root 也能用。
         */
        fun currentWindow(): WindowInfo? {
            val service = instance ?: return null
            val window = service.windows.firstOrNull { it.isActive } ?: return null
            val node = service.rootInActiveWindow
            val pkg = node?.packageName?.toString() ?: window.root?.packageName?.toString() ?: return null
            val activity = window.root?.className?.toString().orEmpty()
            val focus = buildString {
                append(pkg)
                if (activity.isNotBlank()) append('/').append(activity)
            }
            return WindowInfo(pkg, activity, focus)
        }

        /**
         * 点击坐标。返回是否真的派发了手势。
         *
         * 这里不判断「点了有没有效果」——效果由调用方随后读界面确认。
         */
        fun tap(x: Int, y: Int): Result<Unit> = gesture { path ->
            path.moveTo(x.toFloat(), y.toFloat())
        }

        /** 长按：原地停留 [durationMs]。 */
        fun longPress(x: Int, y: Int, durationMs: Long): Result<Unit> = gesture(
            durationMs = durationMs,
        ) { path -> path.moveTo(x.toFloat(), y.toFloat()) }

        /** 滑动 / 拖拽。 */
        fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Result<Unit> = gesture(
            durationMs = durationMs,
        ) { path ->
            path.moveTo(x1.toFloat(), y1.toFloat())
            path.lineTo(x2.toFloat(), y2.toFloat())
        }

        private fun gesture(
            durationMs: Long = 60L,
            build: (Path) -> Unit,
        ): Result<Unit> = runCatching {
            val service = instance ?: error("无障碍服务未开启：请在「权限检测与申请」里开启")
            val path = Path().apply(build)
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(1L))
            val description = GestureDescription.Builder().addStroke(stroke).build()
            val latch = CountDownLatch(1)
            val failure = AtomicReference<String?>(null)
            val dispatched = service.dispatchGesture(
                description,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        latch.countDown()
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        failure.set("手势被系统取消（可能有其他无障碍服务正在操作）")
                        latch.countDown()
                    }
                },
                null,
            )
            if (!dispatched) error("手势派发失败")
            // 动画时长之外再留一点余量，否则紧接着的读界面会取到过渡中的状态
            latch.await(durationMs + 2000L, TimeUnit.MILLISECONDS)
            failure.get()?.let { error(it) }
            Unit
        }

        /**
         * 在焦点输入框写入文本。
         *
         * 优先 ACTION_SET_TEXT（一次写入、支持中文与 emoji，且不经过输入法）；
         * 节点不支持时退回逐个 ACTION_PASTE——由调用方先写剪贴板。
         */
        fun setText(text: String, pasteFromClipboard: Boolean = false): Result<Boolean> = runCatching {
            val root = service()?.rootOrNull() ?: return@runCatching false
            val target = findEditable(root) ?: return@runCatching false
            if (pasteFromClipboard) {
                target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                true
            } else {
                val args = android.os.Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
        }

        /** 按 id / 文本查找节点；两种条件都为空时返回 null。 */
        fun findNode(id: String?, text: String?, exact: Boolean = false): Result<Rect?> = runCatching {
            val root = service()?.rootOrNull() ?: return@runCatching null
            val node = when {
                !id.isNullOrBlank() -> root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
                !text.isNullOrBlank() -> {
                    val hits = root.findAccessibilityNodeInfosByText(text)
                    hits.firstOrNull { candidate ->
                        val label = candidate.text?.toString().orEmpty()
                        when {
                            candidate.text == null && candidate.contentDescription == null -> false
                            exact -> label == text || candidate.contentDescription?.toString() == text
                            else -> label.contains(text) || candidate.contentDescription?.toString()?.contains(text) == true
                        }
                    }
                }

                else -> null
            } ?: return@runCatching null
            boundsOf(node)
        }

        /** 读当前界面为可读文本（供 AI 定位元素）。 */
        fun dumpTree(maxNodes: Int = 400): Result<String> = runCatching {
            val root = service()?.rootOrNull() ?: error("无障碍服务未开启")
            val sb = StringBuilder()
            val counter = intArrayOf(0)
            walk(root, sb, counter, maxNodes, 0)
            sb.toString().trim().ifBlank { "(界面为空)" }
        }

        private fun walk(
            node: AccessibilityNodeInfo,
            sb: StringBuilder,
            counter: IntArray,
            maxNodes: Int,
            depth: Int,
        ) {
            if (counter[0] >= maxNodes) return
            counter[0]++

            val label = node.text?.toString()?.take(80)
                ?: node.contentDescription?.toString()?.take(80)
            val rect = boundsOf(node)
            val kind = when {
                node.isEditable -> "输入框"
                node.isClickable -> "可点"
                else -> null
            }
            if (label != null || kind != null) {
                sb.append("  ".repeat(depth.coerceAtMost(8)))
                if (kind != null) sb.append('[').append(kind).append("] ")
                sb.append(node.className?.toString()?.substringAfterLast('.') ?: "view")
                if (label != null) sb.append(" \"").append(label).append('"')
                if (rect != null) {
                    sb.append(" @").append(rect.centerX()).append(',').append(rect.centerY())
                    sb.append(" [").append(rect.width()).append('x').append(rect.height()).append(']')
                }
                node.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfterLast('/')) }
                sb.append('\n')
            }

            for (i in 0 until node.childCount) {
                val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
                walk(child, sb, counter, maxNodes, depth + 1)
                if (counter[0] >= maxNodes) return
            }
        }

        private fun boundsOf(node: AccessibilityNodeInfo): Rect? {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            return if (rect.isEmpty) null else rect
        }

        private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null && focused.isEditable) return focused
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isEditable) return node
                for (i in 0 until node.childCount) {
                    runCatching { node.getChild(i) }.getOrNull()?.let { queue.add(it) }
                }
            }
            return null
        }

        /** 全局返回 / 主页 / 最近任务（不依赖 shell）。 */
        fun performGlobal(action: Int): Result<Unit> = runCatching {
            val service = service() ?: error("无障碍服务未开启")
            if (!service.performGlobalAction(action)) error("系统拒绝执行该全局动作")
        }

        /**
         * 截屏。
         *
         * 走 Android 11+ 的 [AccessibilityService.takeScreenshot]：无需投屏授权，
         * 也不需要 root。返回 PNG 字节与尺寸。
         */
        fun screenshot(): Result<ByteArray> = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                error("系统版本低于 Android 11，无障碍截屏不可用")
            }
            val service = service() ?: error("无障碍服务未开启")
            val latch = CountDownLatch(1)
            val result = AtomicReference<ByteArray?>(null)
            val failure = AtomicReference<String?>(null)
            service.takeScreenshot(
                android.view.Display.DEFAULT_DISPLAY,
                service.mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        runCatching {
                            val bitmap = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace,
                            )
                            val out = ByteArrayOutputStream()
                            // 转成软件位图再压 PNG：硬件位图直接 compress 在部分机型返回 false
                            bitmap?.copy(Bitmap.Config.ARGB_8888, false)?.compress(
                                Bitmap.CompressFormat.PNG,
                                100,
                                out,
                            )
                            bitmap?.recycle()
                            result.set(out.toByteArray())
                        }.onFailure { failure.set(it.message ?: "截图解码失败") }
                        screenshot.hardwareBuffer.close()
                        latch.countDown()
                    }

                    override fun onFailure(errorCode: Int) {
                        failure.set("系统拒绝截屏（错误码 $errorCode）")
                        latch.countDown()
                    }
                },
            )
            if (!latch.await(10, TimeUnit.SECONDS)) error("截屏超时")
            failure.get()?.let { error(it) }
            result.get() ?: error("截屏返回空数据")
        }

        private fun service(): ToolboxAccessibilityService? = instance
    }
}

package com.mcp.toolbox.ui.linux.term

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import com.mcp.toolbox.ui.linux.LinuxDistro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * 终端窗口的持有者。
 *
 * 为什么是单例而不是 `remember`：一个窗口就是一个真进程（bash 加上用户在里面跑的
 * 东西）。退出终端页再回来若重开一次，正在跑的编译、正在看的 `top` 全没了，还会在
 * 系统里留下孤儿 pty。所以会话放在这里，页面只负责「接上」。
 *
 * **可以同时存在多个窗口**（[Tab]）：每个是一份独立的 shell、独立的 pty、独立的
 * 回放历史。切换窗口不动进程，只换正在显示的 WebView；内容靠
 * [TerminalSession.readSince] 重放恢复（xterm 的屏幕缓冲随 WebView 销毁，
 * 这是「第二次打开终端是白屏」的根因）。
 *
 * 真正的结束只有三种：用户关掉窗口、shell 自己 `exit`、进程被杀。
 */
object TermSessionStore {

    /**
     * 同时最多几个窗口。
     *
     * 每个窗口是一份真 shell + 一个 WebView，都是重量级对象。手机内存有限
     * （见项目里构建被 LMK 杀过的记录），这里给个上限，超了就让用户先关几个。
     */
    const val MAX_TABS = 6

    /** 一个终端窗口。 */
    /**
     * 这个窗口跑在哪个环境里。
     *
     * - [LOCAL]：**宿主 Android 环境**。不需要 rootfs、不需要 Root，
     *   直接跑 Android 自带的 shell，能用的命令就是宿主那套
     *   （`ls`、`ps`、`dumpsys`、`pm`…）。
     * - [LINUX]：已安装的 Linux 环境，需要 Root（见 `LinuxRunMode` 的说明）。
     *
     * 放在 Tab 上而不是全局：两种窗口可以同时开着（一边看 Android 进程、
     * 一边在 Linux 里编译），这是这两个入口同时存在的意义。
     */
    enum class Env { LOCAL, LINUX }

    class Tab internal constructor(
        val id: String,
        val distro: LinuxDistro,
        /** 本窗口的环境类型。 */
        val env: Env,
        internal val session: TerminalSession,
        internal val scope: CoroutineScope,
        initialTitle: String,
    ) {
        /** 顶栏 chip 上显示的名字。 */
        val title = mutableStateOf(initialTitle)

        /** 会话是否已就绪（bash 在跑）。 */
        val running = mutableStateOf(false)

        /** 启动失败原因；null 表示无错误。 */
        val error = mutableStateOf<String?>(null)

        /** 不在前台时来过新输出 → 顶栏 chip 上点一个小圆点。 */
        val unseen = mutableStateOf(false)

        /** 重启次数。面板据此清屏并回到「整屏重放」状态。 */
        val restarts = mutableStateOf(0)
    }

    private val seq = AtomicInteger()

    /** 窗口列表。整体替换而不是 mutableStateList：写入来自后台线程，语义简单不易错。 */
    val tabs = mutableStateOf<List<Tab>>(emptyList())

    /** 当前选中的窗口。 */
    val current = mutableStateOf<Tab?>(null)

    /**
     * 正在启动的窗口 id。
     *
     * 不能拿 `session.running` 当启动锁：它在 `start()` 内部才置位，而从「发起
     * 启动」到那一刻有几百毫秒（挂载 rootfs、forkpty），期间 open() 与面板的
     * LaunchedEffect 都会认为「还没启动」→ 起两份 bash、两份 pty。
     */
    private val starting = HashSet<String>()

    /** 新建窗口并选中它。 */
    @Synchronized
    fun open(
        context: Context,
        distro: LinuxDistro,
        env: Env = Env.LINUX,
    ): Tab {
        val app = context.applicationContext
        val n = seq.incrementAndGet()
        val id = "t$n"
        val session = TerminalSession(app, distro, env)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // 标题直接标明环境：两种窗口混在一起时，光看「终端 3」分不清是哪种
        val tab = Tab(id, distro, env, session, scope, if (env == Env.LOCAL) "Android $n" else "Linux $n")

        // 后台输出时给非当前窗口点个未读标记。用 collect 而不是在 UI 层统计：
        // 未挂载的窗口没有面板，只有这里能听到动静。
        scope.launch {
            session.wake.collect {
                if (current.value?.id != id) tab.unseen.value = true
            }
        }

        tabs.value = tabs.value + tab
        current.value = tab
        tab.unseen.value = false
        ensureStarted(tab)
        return tab
    }

    /** 切到某个窗口。 */
    @Synchronized
    fun select(tab: Tab) {
        current.value = tab
        tab.unseen.value = false
        ensureStarted(tab)
    }

    /**
     * 关闭窗口，返回它是否**真的**被关掉了。
     *
     * 只剩一个时不关（那会留下「一个窗口都没有」的空页面要处理），而是就地重开
     * 一份干净的 shell——这正是菜单里「退出终端」想要的语义，此时返回 false。
     */
    @Synchronized
    fun close(tab: Tab): Boolean {
        val list = tabs.value
        if (list.size <= 1) {
            restart(tab)
            return false
        }
        tabs.value = list.filterNot { it.id == tab.id }
        if (current.value?.id == tab.id) current.value = tabs.value.lastOrNull()

        // kill 要在独立协程里跑：这个窗口的 scope 马上就要被取消
        CoroutineScope(Dispatchers.IO).launch { runCatching { tab.session.kill() } }
        tab.scope.cancel()
        return true
    }

    /**
     * 确保会话在跑；已启动则直接返回，重复进入页面不会重开。
     *
     * 只维护「是否就绪 / 失败原因」两个状态；输出由面板订阅
     * [TerminalSession.wake] 后按游标取。
     */
    @Synchronized
    fun ensureStarted(tab: Tab) {
        if (tab.session.running) return
        if (!starting.add(tab.id)) return
        tab.running.value = false
        tab.error.value = null
        tab.scope.launch {
            val result = runCatching { tab.session.start() }
            synchronized(this@TermSessionStore) { starting.remove(tab.id) }
            if (result.isSuccess) {
                tab.running.value = true
                tab.error.value = null
            } else {
                tab.running.value = false
                tab.error.value = result.exceptionOrNull()?.message ?: "终端启动失败"
            }
        }
    }

    /**
     * 「重新启动」：结束当前 shell，在同一个窗口里开一份新的。
     *
     * kill 必须等完再 start：pty 还是同一个 shell 持有的，没死透就重启会抢到
     * 残留的从端。这两步放在窗口自己的 scope 里串行执行。
     */
    @Synchronized
    fun restart(tab: Tab) {
        // 已在启动/重启中就不再叠一次：错误行上的「重试」按钮可能被连点
        if (!starting.add(tab.id)) return
        tab.scope.launch {
            tab.restarts.value += 1
            runCatching { tab.session.kill() }
            tab.running.value = false
            tab.error.value = null
            val result = runCatching { tab.session.start() }
            synchronized(this@TermSessionStore) { starting.remove(tab.id) }
            if (result.isSuccess) {
                tab.running.value = true
            } else {
                tab.running.value = false
                tab.error.value = result.exceptionOrNull()?.message ?: "终端启动失败"
            }
        }
    }
}

package com.mcp.toolbox.feature.files

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Forward10
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mcp.toolbox.core.common.FileOps
import com.mcp.toolbox.core.design.component.MiuixIconButton
import com.mcp.toolbox.core.design.component.MiuixMenuGroupLabel
import com.mcp.toolbox.core.design.component.MiuixMenuItem
import com.mcp.toolbox.core.design.component.MiuixOverflowMenu
import com.mcp.toolbox.core.design.component.MiuixSlider
import com.mcp.toolbox.core.design.component.MiuixText
import com.mcp.toolbox.core.design.theme.MiuixTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 媒体文件上限：再大就不往应用缓存里搬了。 */
private const val MAX_MEDIA_BYTES = 4L * 1024 * 1024 * 1024

private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

/** 控制层出入场时长：进场稍慢一点显轻，出场快一些不拖沓。 */
private const val CONTROLS_ENTER_MS = 220
private const val CONTROLS_EXIT_MS = 160

/** 自动淡出前的静止时间：控制层本身足够克制，久留只会挡视线。 */
private const val CONTROLS_IDLE_MS = 2400L

/**
 * 画面填充方式。
 *
 * [Fit] 按原始比例如实显示，宽高比与屏幕不一致时四周留黑边；
 * [Crop] 等比放大到铺满整屏，超出的部分裁掉，横竖屏不匹配也不会有黑边。
 */
private enum class VideoScale { Fit, Crop }

/** 往上找到宿主 Activity，用来请求屏幕方向。 */
private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

/**
 * 读片源真实宽高（已把旋转元数据算进去），单位像素。
 *
 * 这个宽高比有两个用途：一是判断横竖屏决定要不要转屏，
 * 二是**直接决定画面在屏幕上的摆放尺寸** ——
 * 播放器的 setVideoScalingMode 在部分设备上会被静默忽略，
 * 靠它保证比例并不可靠，而按真实比例摆放 SurfaceView 则一定有效。
 * 读不出来时返回 null，调用方退回「铺满」。
 */
private fun probeVideoSize(file: String): Pair<Int, Int>? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file)
        val w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        if (w <= 0 || h <= 0) {
            null
        } else {
            // 旋转 90/270 度时，元数据里的宽高是转之前的，要互换才是真正看到的朝向。
            if (rotation == 90 || rotation == 270) h to w else w to h
        }
    } finally {
        runCatching { retriever.release() }
    }
}.getOrNull()

private fun clockOf(ms: Int): String {
    val total = (ms.coerceAtLeast(0) / 1000)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * 内置媒体播放器：视频铺满画面、音频显示封面占位；
 * 两者共用一套进度条、±10 秒与 0.5x～2x 倍速控制。
 *
 * 顶栏与底栏是两块**互相独立**的悬浮层，各自朝屏幕对应的边缘滑入滑出：
 * 顶栏从上边进来，底栏从下边升起来，而不是整体从同一个角展开。
 * 默认显示两秒多后自动淡出，点画面任意处可重新唤出，
 * 暂停 / 拖进度 / 开着倍速菜单时不会消失。
 *
 * 控制层刻意做得轻：顶栏是一层向下渐隐的遮罩而不是实心黑条，
 * 底栏是一张左右留边的圆角浮卡；横屏时进一步收窄、缩小控件，
 * 并把时间压到进度条两端，尽量少压画面。
 *
 * 视频会按片源方向自动转屏：横屏片转成横屏铺满，竖屏片保持竖屏；
 * 顶栏另有手动旋转按钮，退出播放页时恢复系统默认方向。
 */
@Composable
fun MediaViewerOverlay(
    path: String,
    name: String,
    onClose: () -> Unit,
    onToast: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val colors = MiuixTheme.colors
    val spacing = MiuixTheme.dimens.spacing
    val ext = remember(name) { name.substringAfterLast('.', "").lowercase() }
    val isVideo = ext in VIDEO_EXTENSIONS

    // 横屏时画面本身就很扁，控制层必须比竖屏更克制，否则一多半画面都被压住。
    val compact = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val buttonSize: Dp = if (compact) 36.dp else 40.dp
    val buttonIcon: Dp = if (compact) 18.dp else 20.dp

    var ready by remember(path) { mutableStateOf(false) }
    var failure by remember(path) { mutableStateOf<String?>(null) }
    var playing by remember(path) { mutableStateOf(false) }
    var duration by remember(path) { mutableStateOf(0) }
    var position by remember(path) { mutableStateOf(0) }
    var speed by remember(path) { mutableStateOf(1f) }
    var dragging by remember(path) { mutableStateOf(false) }
    var dragValue by remember(path) { mutableStateOf(0f) }
    var speedMenu by remember(path) { mutableStateOf(false) }

    /**
     * 画面填充方式，默认「适应」：始终保持片源原始比例，
     * 屏幕比片源更宽时左右留黑边，而不是把画面横向拉伸铺满。
     * 想消掉黑边可以手动切「填满」。
     */
    var scale by remember(path) { mutableStateOf(VideoScale.Fit) }

    /**
     * 屏幕方向。初值 null 表示「还没判定」，此时不碰系统方向，
     * 免得在本来就横着握持时突然被掰回竖屏。
     */
    var landscape by remember(path) { mutableStateOf<Boolean?>(null) }

    /** 片源真实宽高，用来按原始比例摆放画面；探测失败时为 null。 */
    var videoSize by remember(path) { mutableStateOf<Pair<Int, Int>?>(null) }
    val videoAspect: Float? = videoSize?.let { it.first.toFloat() / it.second.toFloat() }

    val player = remember(path) { MediaPlayer() }
    val surfaceView = remember(path) { SurfaceView(context) }

    /**
     * 切换倍速。setPlaybackParams 会把播放器拉到播放态，
     * 原本处于暂停时要按回去，否则暂停中调倍速会自己播起来。
     */
    val applySpeed: (Float) -> Unit = { value ->
        val wasPlaying = playing
        speed = value
        runCatching { player.playbackParams = player.playbackParams.setSpeed(value) }
        if (!wasPlaying) runCatching { player.pause() }
    }

    /**
     * 绑定显示面，并紧跟一次填充方式设置。
     *
     */
    val bindSurface: () -> Unit = {
        runCatching { player.setDisplay(surfaceView.holder) }
        runCatching {
            // Surface 尺寸已经按片源比例定好了，播放器这边固定用 FIT 即可：
            // 两者比例一致时，FIT 的表现就是「完整填充且不变形」。
            player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
        }
    }

    // 切换填充方式时重设一次；此时 Surface 已经挂好，设置立刻生效。
    LaunchedEffect(scale, ready, isVideo) {
        if (!isVideo || !ready) return@LaunchedEffect
        bindSurface()
    }

    LaunchedEffect(path) {
        val local = withContext(Dispatchers.IO) {
            FileOps.toLocalFile(context, path, namespace = "media", maxBytes = MAX_MEDIA_BYTES)
        }
        if (local == null) {
            failure = "打不开媒体文件：需要 root 或「所有文件访问」权限"
            return@LaunchedEffect
        }
        // 顺手探一次片源真实尺寸：既用于自动转屏，也用于按比例摆放画面。
        if (isVideo) {
            val probed = withContext(Dispatchers.IO) { probeVideoSize(local.absolutePath) }
            if (probed != null) {
                videoSize = probed
                if (landscape == null) landscape = probed.first > probed.second
            }
        }
        runCatching {
            player.setDataSource(local.absolutePath)
            player.setOnPreparedListener {
                duration = runCatching { player.duration }.getOrDefault(0)
                ready = true
                failure = null
                // 画面挂到 SurfaceView；音频不需要 display。
                if (isVideo) bindSurface()
                runCatching { player.playbackParams = player.playbackParams.setSpeed(speed) }
                player.start()
                playing = true
            }
            player.setOnCompletionListener { playing = false }
            player.setOnErrorListener { _, _, _ ->
                failure = "播放失败：编码格式不支持或文件已损坏"
                playing = false
                true
            }
            // 异步准备，避免大文件在 IO 线程外阻塞
            player.prepareAsync()
        }.onFailure { failure = "无法打开播放器：" + it.message }
    }

    // 播放期间轮询进度；拖动进度条时让位给手指。
    LaunchedEffect(ready, playing) {
        while (ready) {
            if (!dragging) {
                position = runCatching { player.currentPosition }.getOrDefault(position)
                val fresh = runCatching { player.duration }.getOrDefault(0)
                if (fresh > 0) duration = fresh
                playing = runCatching { player.isPlaying }.getOrDefault(playing)
            }
            delay(400)
        }
    }

    // 拖动防抖：手指停 280ms 才真正 seek，避免一路拖一路 seek 卡成幻灯片。
    LaunchedEffect(dragValue, dragging) {
        if (dragging) {
            delay(280)
            runCatching { player.seekTo(dragValue.toInt()) }
            position = dragValue.toInt()
            dragging = false
        }
    }

    // 应用屏幕方向。Activity 已声明 configChanges，旋转不会重建界面、播放不中断。
    LaunchedEffect(landscape) {
        val target = landscape ?: return@LaunchedEffect
        activity?.requestedOrientation = if (target) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    DisposableEffect(path) {
        onDispose { runCatching { player.reset(); player.release() } }
    }

    // 退出播放页时把方向控制权还给系统。
    DisposableEffect(path) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    // SurfaceView 的 surface 可能晚于播放器就绪，在 surfaceCreated 里补一次绑定，
    // 否则画面一直全黑。
    DisposableEffect(path) {
        val callback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Surface 重建会一并丢掉缩放设置，这里把显示面和填充方式一起补回来。
                runCatching { player.setDisplay(holder) }
                runCatching {
                    player.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
                }
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                runCatching { player.setDisplay(null) }
            }
        }
        surfaceView.holder.addCallback(callback)
        onDispose { runCatching { surfaceView.holder.removeCallback(callback) } }
    }

    BackHandler(enabled = true) { onClose() }

    // 悬浮控制层：点画面切换显隐，播放中短暂无操作即自动淡出。
    var controlsVisible by remember(path) { mutableStateOf(true) }

    LaunchedEffect(controlsVisible, playing, ready, dragging, speedMenu) {
        // 暂停、拖进度、开着倍速菜单时不隐藏，免得手还没松开就没了。
        if (controlsVisible && playing && ready && !dragging && !speedMenu) {
            delay(CONTROLS_IDLE_MS)
            controlsVisible = false
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            failure != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MiuixText(
                    failure.orEmpty(),
                    style = MiuixTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 4,
                    modifier = Modifier.padding(horizontal = spacing.xl),
                )
            }

            // 画面比例由 SurfaceView 自身的尺寸保证，而不是交给播放器的缩放开关。
            //
            // 原因：MediaPlayer.setVideoScalingMode 在部分设备上会被静默忽略，
            // 一旦忽略，解码帧就按 Surface 尺寸硬拉伸铺满，
            // 16:9 的片源放到比它更宽的屏幕上就会整体横向变扁。
            //
            // 这里改成按片源真实宽高比摆放：
            // Fit 让整幅画面落在屏幕内（片源比屏幕窄时自然露出左右黑边），
            // Crop 等比放大到盖住屏幕，多余部分由外层 clipToBounds 裁掉。
            isVideo -> BoxWithConstraints(Modifier.fillMaxSize()) {
                val aspect = videoAspect
                val layout = when {
                    aspect == null -> Modifier.fillMaxSize()

                    scale == VideoScale.Fit -> Modifier
                        .align(Alignment.Center)
                        .aspectRatio(aspect)

                    else -> {
                        val screenAspect = maxWidth.value / maxHeight.value
                        // requiredSize 会忽略父级约束，这样「放大到超出屏幕」才成立。
                        if (aspect > screenAspect) {
                            Modifier
                                .align(Alignment.Center)
                                .requiredSize(width = maxHeight * aspect, height = maxHeight)
                        } else {
                            Modifier
                                .align(Alignment.Center)
                                .requiredSize(width = maxWidth, height = maxWidth / aspect)
                        }
                    }
                }
                Box(Modifier.fillMaxSize().clipToBounds()) {
                    AndroidView(factory = { surfaceView }, modifier = layout)
                }
            }

            else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    MiuixIconButton(Icons.Outlined.MusicNote, "音频", onClick = {}, tint = Color.White)
                    MiuixText(
                        if (ready) "正在播放音频" else "正在准备…",
                        style = MiuixTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }

        // 点画面切换控制层。这张透明层压在画面之上、控制层之下，
        // 控制层里的按钮与卡片自己消费点击，只有画面空白处的点击会落到这里。
        Box(
            Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { controlsVisible = !controlsVisible },
        )

        // 顶栏：独立一块悬浮层，从屏幕上方滑入、向上滑出。
        // 背景用向下渐隐的遮罩而不是实心黑条，让标题区之外不留下明显色块。
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(CONTROLS_ENTER_MS)) +
                slideInVertically(tween(CONTROLS_ENTER_MS)) { height -> -height },
            exit = fadeOut(tween(CONTROLS_EXIT_MS)) +
                slideOutVertically(tween(CONTROLS_EXIT_MS)) { height -> -height },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                Color.Black.copy(alpha = 0.66f),
                                Color.Black.copy(alpha = 0.22f),
                                Color.Transparent,
                            ),
                        ),
                    )
                    .statusBarsPadding()
                    .padding(
                        start = spacing.xs,
                        end = spacing.md,
                        top = spacing.xs,
                        bottom = if (compact) spacing.sm else spacing.lg,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiuixIconButton(
                    icon = Icons.Outlined.Close,
                    contentDescription = "关闭",
                    onClick = onClose,
                    tint = Color.White,
                    buttonSize = buttonSize,
                    iconSize = buttonIcon,
                )
                MiuixText(
                    name,
                    style = MiuixTheme.typography.titleSmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (isVideo) {
                    MiuixIconButton(
                        icon = Icons.Outlined.ScreenRotation,
                        contentDescription = "旋转屏幕",
                        tint = Color.White,
                        onClick = { landscape = !(landscape ?: false) },
                        buttonSize = buttonSize,
                        iconSize = buttonIcon,
                    )
                    Spacer(Modifier.width(spacing.xs))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable {
                                scale = if (scale == VideoScale.Crop) VideoScale.Fit else VideoScale.Crop
                            }
                            .padding(
                                horizontal = if (compact) 12.dp else 14.dp,
                                vertical = if (compact) 5.dp else 7.dp,
                            ),
                    ) {
                        MiuixText(
                            if (scale == VideoScale.Crop) "填满" else "适应",
                            style = MiuixTheme.typography.labelLarge,
                            color = Color.White,
                        )
                    }
                }
            }
        }

        // 底栏：另一块独立悬浮层，从屏幕下方升起、向下沉回去。
        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(CONTROLS_ENTER_MS)) +
                slideInVertically(tween(CONTROLS_ENTER_MS)) { height -> height },
            exit = fadeOut(tween(CONTROLS_EXIT_MS)) +
                slideOutVertically(tween(CONTROLS_EXIT_MS)) { height -> height },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
                // 左右留边的圆角浮卡：压在画面上时一眼能看出是浮层，
                // 自身消费点击，免得点卡片空白处把整层控制条收起来。
                // 横屏时左右各让出更多，卡片窄一点，画面露出的部分就多一点。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = if (compact) 72.dp else spacing.md)
                        .clip(RoundedCornerShape(if (compact) 18.dp else 22.dp))
                        .background(Color.Black.copy(alpha = if (compact) 0.58f else 0.72f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {}
                        .padding(
                            horizontal = spacing.pageHorizontal,
                            vertical = if (compact) spacing.sm else spacing.md,
                        ),
                ) {
                    if (compact) {
                        // 横屏：时间挪到进度条两端，省掉一整行高度。
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            MiuixText(
                                clockOf(if (dragging) dragValue.toInt() else position),
                                style = MiuixTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                            Spacer(Modifier.width(spacing.sm))
                            MiuixSlider(
                                value = if (dragging) dragValue else position.toFloat(),
                                onValueChange = { value ->
                                    dragging = true
                                    dragValue = value
                                },
                                valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                                enabled = ready,
                                valueLabel = { clockOf(it.toInt()) },
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(spacing.sm))
                            MiuixText(
                                clockOf(duration),
                                style = MiuixTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.85f),
                            )
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            MiuixText(
                                clockOf(if (dragging) dragValue.toInt() else position),
                                style = MiuixTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                            Spacer(Modifier.weight(1f))
                            MiuixText(
                                clockOf(duration),
                                style = MiuixTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.8f),
                            )
                        }
                        MiuixSlider(
                            value = if (dragging) dragValue else position.toFloat(),
                            onValueChange = { value ->
                                dragging = true
                                dragValue = value
                            },
                            valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
                            enabled = ready,
                            valueLabel = { clockOf(it.toInt()) },
                        )
                        Spacer(Modifier.height(spacing.sm))
                    }
                    Box(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MiuixIconButton(
                                icon = Icons.Outlined.Replay10,
                                contentDescription = "后退 10 秒",
                                tint = Color.White,
                                onClick = {
                                    runCatching { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)) }
                                },
                                buttonSize = buttonSize,
                                iconSize = buttonIcon,
                            )
                            Spacer(Modifier.width(spacing.lg))
                            MiuixIconButton(
                                icon = if (playing) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                                contentDescription = if (playing) "暂停" else "播放",
                                tint = Color.White,
                                onClick = {
                                    if (playing) {
                                        runCatching { player.pause() }
                                        playing = false
                                    } else {
                                        runCatching { player.start() }
                                        playing = true
                                    }
                                },
                                buttonSize = buttonSize,
                                iconSize = buttonIcon,
                            )
                            Spacer(Modifier.width(spacing.lg))
                            MiuixIconButton(
                                icon = Icons.Outlined.Forward10,
                                contentDescription = "前进 10 秒",
                                tint = Color.White,
                                onClick = {
                                    runCatching { player.seekTo(player.currentPosition + 10_000) }
                                },
                                buttonSize = buttonSize,
                                iconSize = buttonIcon,
                            )
                        }
                        // 倍速入口固定在右下角：点开就是 0.5x-2x 菜单。
                        Box(Modifier.align(Alignment.CenterEnd)) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(50))
                                    .background(Color.White.copy(alpha = 0.18f))
                                    .clickable { speedMenu = true }
                                    .padding(
                                        horizontal = if (compact) 12.dp else 14.dp,
                                        vertical = if (compact) 5.dp else 7.dp,
                                    ),
                            ) {
                                MiuixText(
                                    speedLabel(speed),
                                    style = MiuixTheme.typography.labelLarge,
                                    color = Color.White,
                                )
                            }
                            MiuixOverflowMenu(expanded = speedMenu, onDismiss = { speedMenu = false }) {
                                MiuixMenuGroupLabel("播放速度")
                                PLAYBACK_SPEEDS.forEach { value ->
                                    MiuixMenuItem(
                                        text = speedLabel(value),
                                        onClick = {
                                            speedMenu = false
                                            applySpeed(value)
                                        },
                                        checked = speed == value,
                                    )
                                }
                            }
                        }
                    }
                }

                // 这个设备的手势条不报告 navigationBars inset（读数为 0），
                // 固定留一段底部空白，避免控制按钮贴着系统手势条。
                Spacer(Modifier.height(if (compact) 14.dp else 28.dp))
            }
        }
    }
}

private fun speedLabel(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() + "x" else value.toString() + "x"

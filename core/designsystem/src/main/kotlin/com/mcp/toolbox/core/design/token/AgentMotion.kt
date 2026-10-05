package com.mcp.toolbox.core.design.token

import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.mcp.toolbox.core.design.theme.MiuixTheme
import top.yukonga.miuix.kmp.anim.folmeSpring

/**
 * 回答区的动效 token（UI/动效 Spec 第 10 节参数表）。
 *
 * ## ⚠️ 参数顺序陷阱（必须知道）
 * 真 Miuix 的 `folmeSpring` 签名是 `folmeSpring(damping, response)`，
 * 而 Spec 全文写作 `spring(response, dampingRatio)`。照抄会把 `spring(0.35, 0.85)`
 * 解释成「阻尼 0.35、响应 0.85s」——动画变成慢慢晃，**而且不会报编译错**。
 * 这里统一按真签名封装，调用点只写语义名，写不错。
 *
 * ## 为什么是「一次取值 + 纯函数」而不是 `@Composable fun`
 * `AnimatedContent(transitionSpec = { ... })`、`AnimatedVisibility(enter = ...)` 这些
 * 位置**不是组合上下文**，在里面调 `@Composable` 函数会直接编译失败。
 * 所以这里做成：组合期用 [rememberAgentMotion] 取一次 [AgentMotion]，
 * 之后所有规格都由它的普通方法产出，lambda 里捕获这个值即可。
 *
 * ## 返回值类型
 * 一律返回 `FiniteAnimationSpec`——`expandVertically` / `fadeIn` 要求的就是它，
 * 返回泛型 `AnimationSpec` 会因类型推断失败而报
 * 「Argument type mismatch: actual type is AnimationSpec<...>」。
 *
 * 原则（Spec 第 10 节末）：位移用弹簧；颜色与透明度用 tween(easeOut)；
 * 循环动画必须能随状态立即停止并回收；任何动画都能打断并从当前值续接。
 */
class AgentMotion internal constructor(
    /** 主题里的「动效强度」倍率。 */
    val scale: Float = 1f,
    /** 无障碍「减少动画」或低电量模式：弹簧退化为短 tween，模糊与 shimmer 关闭。 */
    val reduced: Boolean = false,
) {

    /** 把基准时长按倍率折算。 */
    fun ms(base: Int): Int = (base * scale).toInt().coerceIn(0, 2_000)

    // ---- 弹簧：位移类 ----------------------------------------------------

    /** 用户气泡入场。Spec: 260ms spring(0.30, 0.90)。 */
    fun <T> bubbleEnter(): FiniteAnimationSpec<T> =
        if (reduced) tween(ms(160), easing = EaseOut) else folmeSpring(damping = 0.90f, response = 0.30f)

    /** AI 卡片入场 / 思考胶囊出现 / 工具卡出现 / 展开收起。Spec: spring(0.35, 0.85)。 */
    fun <T> cardEnter(): FiniteAnimationSpec<T> =
        if (reduced) tween(ms(160), easing = EaseOut) else folmeSpring(damping = 0.85f, response = 0.35f)

    /** 思考文案轮播。Spec: 260ms spring(0.28, 0.95)。 */
    fun <T> textSwap(): FiniteAnimationSpec<T> =
        if (reduced) tween(ms(140), easing = EaseOut) else folmeSpring(damping = 0.95f, response = 0.28f)

    /** 思考胶囊宽度变化。Spec: 240ms spring(0.32, 0.90)。 */
    fun <T> capsuleWidth(): FiniteAnimationSpec<T> =
        if (reduced) tween(ms(140), easing = EaseOut) else folmeSpring(damping = 0.90f, response = 0.32f)

    /** 回到底部胶囊 / Snackbar。Spec: 280ms spring(0.32, 0.90)。 */
    fun <T> pillEnter(): FiniteAnimationSpec<T> =
        if (reduced) tween(ms(160), easing = EaseOut) else folmeSpring(damping = 0.90f, response = 0.32f)

    /** 按压按下与回弹。Spec: 120ms 按下 / 240ms 回弹 spring(0.20, 0.95)。 */
    fun <T> press(): FiniteAnimationSpec<T> = folmeSpring(damping = 0.95f, response = 0.20f)

    /** 来源卡片 staggered。Spec: spring(0.30, 0.90)。 */
    fun <T> sourceEnter(): FiniteAnimationSpec<T> =
        if (reduced) tween(ms(140), easing = EaseOut) else folmeSpring(damping = 0.90f, response = 0.30f)

    /** 抽屉打开 / 关闭 / 页面转场。Spec: 320ms spring(0.38, 0.90)。 */
    fun <T> drawer(): FiniteAnimationSpec<T> =
        if (reduced) tween(ms(160), easing = EaseOut) else folmeSpring(damping = 0.90f, response = 0.38f)

    /** 左滑操作露出后吸附。Spec: spring(0.30, 0.95)。 */
    fun <T> swipeSnap(): FiniteAnimationSpec<T> = folmeSpring(damping = 0.95f, response = 0.30f)

    // ---- tween：颜色 / 透明度 / 滚动 -------------------------------------

    /** 颜色 / 透明度过渡：一律 easeOut。Spec 默认 240ms。 */
    fun <T> fade(base: Int = 240): TweenSpec<T> = tween(ms(base), easing = EaseOut)

    /** 微光扫过用 easeInOutSine（Spec 明确指定）。 */
    fun <T> sweep(base: Int = 600): TweenSpec<T> = tween(ms(base), easing = EaseInOutSine)

    /** 滚动用 easeOut。Spec: 120ms。 */
    fun <T> scroll(base: Int = 120): TweenSpec<T> = tween(ms(base), easing = EaseOut)

    // ---- 循环周期（毫秒） -------------------------------------------------

    /** 思考 orb 呼吸半周期。Spec: 1600ms easeOut 循环。 */
    fun orbBreath(): Int = ms(1600)

    /** 微光扫过一趟。Spec: 600ms。 */
    fun shimmerSweep(): Int = ms(600)

    /** 微光扫过的周期。Spec: 2.4s。 */
    fun shimmerPeriod(): Int = ms(2400)

    /** 流式光标呼吸。Spec: 530ms linear 循环（Spec 里唯一允许 linear 的地方）。 */
    fun cursorBlink(): Int = ms(530)

    /** 工具状态切换的底色过渡。Spec: 240ms tween(easeOut)。 */
    fun statusColor(): Int = ms(240)

    /** 自动滚动。Spec: 120ms tween(easeOut)。 */
    fun autoScroll(): Int = ms(120)

    /** 发送键 → 停止键的形状插值。Spec: 180ms tween(easeInOut)。 */
    fun morph(): Int = ms(180)

    /** 完成态操作条进入。Spec: 240ms。 */
    fun actionsEnter(): Int = ms(240)

    /** 无障碍 / 低电量下的统一降级时长。Spec: ≤160ms。 */
    fun reducedDuration(): Int = ms(160)

    /** 来源卡片第 [index] 条的入场延迟。Spec: 每条 40ms，超过 10 条后压缩到 20ms。 */
    fun staggerDelay(index: Int): Int {
        val step = if (index < 10) 40 else 20
        return ms(index * step).coerceAtMost(400)
    }
}

/** 无障碍「减少动画」开关。 */
val LocalReduceMotion = staticCompositionLocalOf { false }

/** 低电量模式开关（关闭模糊与 shimmer）。 */
val LocalPowerSave = staticCompositionLocalOf { false }

/**
 * 取当前生效的动效规格。**每个组件组合期调一次**，之后在动画 lambda 里捕获使用。
 */
@Composable
@ReadOnlyComposable
fun rememberAgentMotion(): AgentMotion {
    val scale = MiuixTheme.motion.scale
    val reduced = LocalReduceMotion.current || LocalPowerSave.current
    return AgentMotion(scale = scale, reduced = reduced)
}

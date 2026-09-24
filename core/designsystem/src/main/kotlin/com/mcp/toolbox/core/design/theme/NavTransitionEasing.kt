package com.mcp.toolbox.core.design.theme

import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 官方 Miuix 页面转场缓动。
 *
 * 移植自 compose-miuix-ui/miuix 的 `androidx.navigation3.animation.NavTransitionEasing`
 * （Apache-2.0）。官方用它给 NavDisplay 的前进/返回转场做 500ms 动画曲线；
 * 该类型在官方库里是 internal，外部无法直接引用，因此按其算法在本工程内等价实现，
 * 保证「Miuix 风格」下页面转场与官方观感一致。
 *
 * 曲线由阻尼比 [damping] 与响应时间 [response] 决定，本质是二阶弹簧的解析解。
 */
@Immutable
class NavTransitionEasing(
    private val response: Float,
    private val damping: Float,
) : Easing {
    private val r: Float
    private val w: Float
    private val c2: Float

    init {
        val omega = 2.0 * PI / response
        val k = omega * omega
        val c = damping * 4.0 * PI / response
        w = (sqrt(4.0 * k - c * c) / 2.0).toFloat()
        r = (-c / 2.0).toFloat()
        c2 = r / w
    }

    override fun transform(fraction: Float): Float {
        val t = fraction.toDouble()
        val decay = exp(r * t)
        return (decay * (-cos(w * t) + c2 * sin(w * t)) + 1.0).toFloat()
    }

    /** 按官方参数：response = 0.8s，damping = 0.95。 */
    companion object {
        val Default = NavTransitionEasing(0.8f, 0.95f)
    }
}

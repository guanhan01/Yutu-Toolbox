package com.mcp.toolbox.feature.home.agent.stream

/**
 * 自动滚动的三态模型（UI/动效 Spec 第 5.5 节）。
 *
 * **底线：不能和用户抢滚动条。** 用户一旦上滑离开底部，就必须完全停止自动滚动，
 * 否则正在读的内容会被不断拽走。
 *
 * ## 用户选中文字时强制 Drifted
 * 选区被滚跑是同类 bug 里最恼人的一个，所以 [onSelectionActive] 直接把状态钉在
 * [Drifted]，直到用户主动点「回到底部」。
 */
enum class AutoScrollState {
    /** 贴底（距底 < [AutoScrollTracker.ANCHOR_THRESHOLD_PX]）。新内容自动平滑滚过去。 */
    Anchored,

    /** 用户上滑离开底部。**完全停止自动滚动**，浮出「回到底部」胶囊。 */
    Drifted,

    /** 刚点了「回到底部」，正在平滑滚回去。滚到位后转 [Anchored]。 */
    JumpedBack,
}

/**
 * 自动滚动的状态机（纯逻辑，可单测）。
 *
 * 输入：列表的距底距离、用户是否在滚动、是否有活动选区。
 * 输出：当前状态 + 「要不要自动滚」。
 */
class AutoScrollTracker {

    var state: AutoScrollState = AutoScrollState.Anchored
        private set

    /** 是否应当自动滚动到最新内容。 */
    val shouldAutoScroll: Boolean
        get() = state == AutoScrollState.Anchored || state == AutoScrollState.JumpedBack

    /** 是否应显示「回到底部」胶囊。 */
    val showJumpPill: Boolean
        get() = state == AutoScrollState.Drifted

    /**
     * 距底距离变化。由 `listState` 的布局信息驱动。
     *
     * ## 关键：用户一旦离开底部，就**粘住** Drifted
     *
     * 早先的实现在「用户停止滚动」后会做一次 `distance <= 阈值 → Anchored` 的复位，
     * 于是用户往上翻、松手，下一次内容增长就把他**又拽回底部**
     * （实测「往下移了，它又移上去」）。
     *
     * 现在的规则：
     * - 用户滚动中且离底 → Drifted（一旦进入就粘住，不因松手而复位）
     * - 用户滚动中且回到底部附近 → Anchored（这是**用户自己**回到底部，允许）
     * - 非用户滚动（内容变长、展开思考块）→ **只更新「贴底程度」，不改变状态**
     */
    fun onDistanceFromBottom(
        distancePx: Int,
        userIsScrolling: Boolean,
        /**
         * 判定「算离开底部」的阈值。
         *
         * 用户**正在拖动**时应当给一个很小的值（几乎一动手就算离开），
         * 否则流式期间自动跟随会把内容不断拉回底部，用户永远攒不够默认的 300px，
         * 于是永远进不了 Drifted——表现就是「无论如何上下移都会回到原位」。
         */
        thresholdPx: Int = ANCHOR_THRESHOLD_PX,
    ): Boolean {
        val before = state
        state = when {
            // 选中文字期间钉住 Drifted，任何滚动都不改变状态
            selectionActive -> AutoScrollState.Drifted

            userIsScrolling ->
                if (distancePx <= thresholdPx) {
                    if (state == AutoScrollState.JumpedBack) AutoScrollState.JumpedBack
                    else AutoScrollState.Anchored
                } else {
                    AutoScrollState.Drifted
                }

            // 非用户滚动：**绝不动状态**。
            //
            // 这一条至关重要。早先写了「非用户滚动 && 距底近 ⇒ Anchored」，
            // 于是列表布局的瞬态（`visibleItemsInfo` 一时为空、距底被算成 0）
            // 就会把 Drifted 判回 Anchored，自动跟随立刻恢复并把用户拽回底部
            // ——表现就是「明明上滑了，几秒后自己又滑下去」。
            //
            // 现在回到 Anchored 只有两个途径：用户自己滚到底部，或者点「回到底部」。
            else -> state
        }
        return state != before
    }

    /** 用户点了「回到底部」。 */
    fun onJumpRequested(): Boolean {
        val before = state
        state = AutoScrollState.JumpedBack
        return state != before
    }

    /** 平滑滚动到位。 */
    fun onJumpFinished(): Boolean {
        val before = state
        state = AutoScrollState.Anchored
        return state != before
    }

    /** 选区出现 / 消失。出现时强制 Drifted 以保护选区。 */
    var selectionActive: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) state = AutoScrollState.Drifted
        }

    /** 切换会话 / 重新生成时重置。 */
    fun reset() {
        state = AutoScrollState.Anchored
        selectionActive = false
    }

    companion object {
        /** 静止时判「贴底」的距离阈值。 */
        const val ANCHOR_THRESHOLD_PX = 300

        /**
         * 用户正在拖动时的阈值。
         *
         * 取一个很小的值：一动手就算「离开底部」，自动跟随立刻让位。
         * 这不是为了灵敏，而是为了**不与用户抢滚动条**。
         */
        const val DRAGGING_THRESHOLD_PX = 8
    }
}

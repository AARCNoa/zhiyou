package com.AARCNoa.zhiyou.features.items.chat

import android.app.Activity
import android.graphics.Outline
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.ViewTreeObserver
import androidx.activity.ComponentActivity
import androidx.compose.material3.ListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.tencent.mm.pluginsdk.ui.chat.ChatFooter
import com.tencent.mm.ui.chatting.ChattingUI
import dev.ujhhgtg.reflekt.reflekt
import com.AARCNoa.zhiyou.dexkit.abc.IResolveDex
import com.AARCNoa.zhiyou.dexkit.dsl.dexMethod
import com.AARCNoa.zhiyou.features.core.ClickableFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.features.items.chat.FloatingChatFooter.bottomGapDp
import com.AARCNoa.zhiyou.preferences.WePrefs.Companion.prefOption
import com.AARCNoa.zhiyou.ui.content.AlertDialogContent
import com.AARCNoa.zhiyou.ui.content.Button
import com.AARCNoa.zhiyou.ui.content.DefaultColumn
import com.AARCNoa.zhiyou.ui.content.TextButton
import com.AARCNoa.zhiyou.ui.utils.showComposeDialog
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.constructor
import kotlin.math.roundToInt

@Feature(
    name = "悬浮输入框",
    categories = ["界面美化"],
    description = "将聊天输入框改为悬浮卡片形式, 带有圆角、阴影和侧边距"
)
object FloatingChatFooter : ClickableFeature(), IResolveDex {

    private const val TAG = "FloatingChatFooter"

    private const val DEFAULT_CORNER_RADIUS = 24
    private const val DEFAULT_SIDE_MARGIN = 12
    private const val DEFAULT_BOTTOM_GAP = 4
    private const val DEFAULT_ELEVATION = 4

    private const val MIN_CORNER_RADIUS = 0
    private const val MAX_CORNER_RADIUS = 32
    private const val MIN_SIDE_MARGIN = 0
    private const val MAX_SIDE_MARGIN = 32
    private const val MIN_BOTTOM_GAP = 0
    private const val MAX_BOTTOM_GAP = 24
    private const val MIN_ELEVATION = 0
    private const val MAX_ELEVATION = 16

    private var cornerRadiusDp by prefOption("floating_chat_footer_corner_radius", DEFAULT_CORNER_RADIUS)
    private var sideMarginDp by prefOption("floating_chat_footer_side_margin", DEFAULT_SIDE_MARGIN)
    private var bottomGapDp by prefOption("floating_chat_footer_bottom_gap", DEFAULT_BOTTOM_GAP)
    private var elevationDp by prefOption("floating_chat_footer_elevation", DEFAULT_ELEVATION)

    /**
     * 我们最后一次写进 `ChatFooter.translationY` 的值（px），NaN 表示还没写过。
     *
     * 这个值用来「认出自己」：ChatFooter 的 translationY 不是模块独占的属性 ——
     * 微信自己也会用它做表情面板展开、键盘避让的位移动画。
     * 只要当前值不等于这里记录的值，就说明「微信刚写了新值」，此时以微信的值为**基准**
     * 再减掉 gap（叠加），而不是直接覆盖 —— 这样微信的整段动画都会被平移 gap，
     * 不会出现「收键盘时先落到底、再被我们顶上来」的跳动。
     */
    @Volatile
    private var appliedShift = Float.NaN

    /** 上次施加的 gap（px），-1 表示还没施加过。参数变了要拿它把基准还原出来。 */
    @Volatile
    private var appliedGapPx = -1

    /** 已经装过监视器的 footer，避免 onResume / 重复 attach 时叠加 listener。 */
    private val watchedFooters: MutableSet<ChatFooter> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    override fun onEnable() {
        val reflekt = ChatFooter::class.reflekt()

        // Outline + elevation can be set immediately after construction (no LayoutParams needed)
        ChatFooter::class.constructor.hookAfter {
            applyDrawingStyle(thisObject as ChatFooter)
        }

        // Side margins must wait until onAttachedToWindow: LayoutParams is set by the parent
        // ViewGroup (ChattingScrollLayout) when it calls addView(), which happens after the
        // constructor returns. It is guaranteed to be non-null by the time the view attaches.
        // 同时在这里装上「底部间距 + 裁剪」的每帧监视（构造期拿不到真实的 ViewTreeObserver）。
        reflekt.firstMethod { name = "onAttachedToWindow" }.hookAfter {
            val footer = thisObject as ChatFooter
            applySideMargins(footer)
            syncBottomGap(footer)
            watchBottomGap(footer)
        }

        // Notification jump (and other non-standard entry paths) can bypass the normal
        // ChatFooter creation lifecycle. Re-check on every ChattingUI.onResume and re-apply
        // the floating style if the footer exists but hasn't been styled yet.
        ChattingUI::class.reflekt().firstMethod { name = "onResume" }.hookAfter {
            val activity = thisObject as? ChattingUI ?: return@hookAfter
            val footer = findChatFooter(activity) ?: return@hookAfter
            applyDrawingStyle(footer)
            applySideMargins(footer)
            syncBottomGap(footer)
        }
    }

    /** Sets the outline provider, corner clipping, and elevation — all drawing properties
     *  that don't require LayoutParams. Safe to call from the constructor hook. */
    private fun applyDrawingStyle(footer: ChatFooter) {
        val density = footer.resources.displayMetrics.density
        // 画一次读取一次 MMKV 不行（getOutline 每帧都调用），这里先把 dp 换算成像素快照。
        val radiusPx = cornerRadiusDp * density

        footer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val parent = view.parent as? View
                // 微信自己的位移（表情面板展开 / 键盘避让）只要出现，就一点都不裁。
                // 面板本来就从 footer 的「屏幕外那段」滑进来，裁一刀正好把它裁没 ——
                // 之前「点 + 号弹不出面板」就是这么来的。
                val weChatShift = -view.translationY - appliedGapPx
                val visibleBottom = if (weChatShift > 1f || parent == null || parent.height <= 0) {
                    view.height.coerceAtLeast(1)
                } else {
                    // 面板收起、只有我们自己的抬升：裁掉屏幕外那段面板区，
                    // 底部缝隙才不会被面板白底填住。用 view.top（不含 translation）算，
                    // 抬升多少就正好露出多少。
                    (parent.height - view.top).coerceIn(1, view.height)
                }
                outline.setRoundRect(0, 0, view.width, visibleBottom, radiusPx)
            }
        }
        footer.clipToOutline = true
        footer.elevation = elevationDp * density
        WeLogger.d(
            TAG,
            "applied drawing style: corner=${cornerRadiusDp}dp elev=${elevationDp}dp bottomGap=${bottomGapDp}dp",
        )
        // 一轮布局之后把真实几何打出来：万一还是"粘连"，这行日志能直接定位是
        // 裁剪没生效（height/top 不对）、还是白色底其实来自别的 View（bg/children 为空）。
        footer.postDelayed({
            val parent = footer.parent as? View
            WeLogger.d(
                TAG,
                "footer geom: h=${footer.height} top=${footer.top} y=${footer.y} " +
                        "transY=${footer.translationY} appliedGap=${appliedGapPx} " +
                        "visible=${if (parent != null) parent.height - footer.top else -1} " +
                        "parent=${parent?.javaClass?.simpleName} parentH=${parent?.height} " +
                        "bg=${footer.background?.javaClass?.simpleName} children=${footer.childCount}",
            )
        }, 500)
    }

    /** 浮点位移比较：RenderNode 往返可能有极小误差，用 0.5px 容差避免每帧反复重写。 */
    private fun sameShift(a: Float, b: Float): Boolean =
        a == b || (a.isFinite() && b.isFinite() && kotlin.math.abs(a - b) < 0.5f)

    /**
     * 底部间距：把整只输入栏抬起来 —— 但**叠加在微信自己的位移之上**，不是覆盖它。
     *
     * `ChatFooter.translationY` 同时被微信用于「表情/功能面板展开」和「键盘避让」的
     * 位移动画。所以不能写死 `translationY = -gap`：
     *  · 面板要往上推 panelH 才能露出来，覆盖掉它 → 点 + 号没反应；
     *  · 键盘收起时微信把它归零，覆盖/等待都会让输入栏先落到屏幕底，
     *    再由我们顶起来 —— 视觉上就是「从底部弹上来」。
     *
     * 正确做法：分辨「这个值是谁写的」。只要当前值 ≠ 我们上次写的值，就认为微信刚写过，
     * 用它当基准再减 gap（每帧跟随），微信的整段动画因此被整体平移 gap，不会有跳变：
     *     base = current（微信写的） 或 current + 上次的 gap（只是参数变了）
     *     translationY = base − gap
     */
    private fun syncBottomGap(footer: ChatFooter) {
        val gapPx = (bottomGapDp * footer.resources.displayMetrics.density).toInt()
        val current = footer.translationY
        val lastApplied = appliedShift

        // 值没被外部动过、参数也没变 → 已经是「基准 − gap」，什么都不用做
        if (sameShift(current, lastApplied) && gapPx == appliedGapPx) return

        val base = if (sameShift(current, lastApplied) && appliedGapPx >= 0) {
            // 只有参数变了：把上次的 gap 加回去，还原微信原本的基准
            current + appliedGapPx
        } else {
            // 微信刚写过（或首次）：当前值就是它的基准
            current
        }

        val target = base - gapPx
        footer.translationY = target
        appliedShift = target
        appliedGapPx = gapPx
        // outline 有缓存，位移变了必须显式失效，否则裁剪线还停在上一帧的位置
        footer.invalidateOutline()
        if (lastApplied.isNaN() || kotlin.math.abs(target - lastApplied) > 1f) {
            WeLogger.d(TAG, "sync bottom gap: base=$base -> $target (gap=${bottomGapDp}dp)")
        }
    }

    /**
     * 每帧盯住 `translationY`：
     *  - 微信把位移归零之后（键盘收起、面板收起）立刻把 gap 补回去；
     *  - 位移一旦变化就让 outline 失效，裁剪线跟着面板重新算，
     *    否则面板展开时会被上一帧的裁剪线切掉。
     *
     * 只读几个字段，开销可以忽略；装在 onAttachedToWindow，避免构造期拿到临时
     * ViewTreeObserver（attach 时会被换掉，listener 随之丢失）。
     */
    private fun watchBottomGap(footer: ChatFooter) {
        if (watchedFooters.contains(footer)) return
        watchedFooters.add(footer)
        footer.addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ ->
            if (view is ChatFooter) {
                syncBottomGap(view)
                // 面板若是通过「重新布局」把自己摆进屏幕（而不是位移动画），
                // 裁剪线也会跟着变 —— 这里必须刷新，否则面板被上一帧的线切掉。
                view.invalidateOutline()
            }
        }
        footer.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            private var lastShift = Float.NaN
            override fun onPreDraw(): Boolean {
                // 只有「微信刚写了新位移」时才需要跟随（含键盘/面板动画的每一帧）。
                // 平时值等于我们自己写的那个，直接跳过，不读 MMKV、不产生 IO。
                if (!sameShift(footer.translationY, appliedShift)) syncBottomGap(footer)
                val shift = footer.translationY
                if (shift != lastShift) {
                    lastShift = shift
                    footer.invalidateOutline()
                }
                return true
            }
        })
    }

    /** 设置页点「确定」后立刻应用到已经存在的输入栏，不用退出聊天页重进。 */
    private fun reapplyToLiveFooters() {
        runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
            val field = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activities = field.get(at) as? Map<*, *> ?: return
            for (record in activities.values) {
                val activity = record?.javaClass?.getDeclaredField("activity")
                    ?.apply { isAccessible = true }
                    ?.get(record) as? Activity ?: continue
                val footer = findChatFooter(activity) ?: continue
                applyDrawingStyle(footer)
                applySideMargins(footer)
                // 参数变了：把我们上次写的值替换成新值（当前值若被微信占用则等它归零后生效）
                syncBottomGap(footer)
                WeLogger.d(TAG, "re-applied floating style to live footer")
            }
        }.onFailure { WeLogger.w(TAG, "reapplyToLiveFooters failed", it) }
    }

    /** Applies left/right margins so the footer appears as a floating card inset from the
     *  screen edges. Called from onAttachedToWindow where LayoutParams is guaranteed non-null. */
    private fun applySideMargins(footer: ChatFooter) {
        val lp = footer.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val sideMarginPx = (sideMarginDp * footer.resources.displayMetrics.density).toInt()
        lp.leftMargin = sideMarginPx
        lp.rightMargin = sideMarginPx
        footer.requestLayout()
        WeLogger.d(TAG, "applied side margins: side=${sideMarginDp}dp")
        // 父容器是 LinearLayout(ChattingScrollLayout)，正常情况下左右 margin 会被采纳；
        // 万一被忽略（自定义 measure 直接给满宽），这里会留下可查的证据。
        footer.post {
            val parentWidth = (footer.parent as? View)?.width ?: 0
            if (parentWidth > 0 && footer.width >= parentWidth) {
                WeLogger.w(
                    TAG,
                    "side margins ignored: footer=${footer.width} parent=$parentWidth",
                )
            }
        }
    }

    /** Walks the view hierarchy of [activity] to find the ChatFooter instance. */
    private fun findChatFooter(activity: Activity): ChatFooter? {
        val root = activity.window?.decorView ?: return null
        return findChatFooterInView(root)
    }

    private fun findChatFooterInView(view: View): ChatFooter? {
        if (view is ChatFooter) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val result = findChatFooterInView(view.getChildAt(i))
                if (result != null) return result
            }
        }
        return null
    }

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            var cornerInput by remember { mutableFloatStateOf(cornerRadiusDp.toFloat()) }
            var sideInput by remember { mutableFloatStateOf(sideMarginDp.toFloat()) }
            var gapInput by remember { mutableFloatStateOf(bottomGapDp.toFloat()) }
            var elevInput by remember { mutableFloatStateOf(elevationDp.toFloat()) }

            AlertDialogContent(
                title = { Text("悬浮输入框") },
                text = {
                    DefaultColumn {
                        ListItem(
                            headlineContent = { Text("圆角半径: ${cornerInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = cornerInput,
                                    onValueChange = { cornerInput = it },
                                    valueRange = MIN_CORNER_RADIUS.toFloat()..MAX_CORNER_RADIUS.toFloat(),
                                    steps = MAX_CORNER_RADIUS - MIN_CORNER_RADIUS - 1
                                )
                            }
                        )
                        ListItem(
                            headlineContent = { Text("侧边距: ${sideInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = sideInput,
                                    onValueChange = { sideInput = it },
                                    valueRange = MIN_SIDE_MARGIN.toFloat()..MAX_SIDE_MARGIN.toFloat(),
                                    steps = MAX_SIDE_MARGIN - MIN_SIDE_MARGIN - 1
                                )
                            }
                        )
                        ListItem(
                            headlineContent = { Text("底部间距: ${gapInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = gapInput,
                                    onValueChange = { gapInput = it },
                                    valueRange = MIN_BOTTOM_GAP.toFloat()..MAX_BOTTOM_GAP.toFloat(),
                                    steps = MAX_BOTTOM_GAP - MIN_BOTTOM_GAP - 1
                                )
                            }
                        )
                        ListItem(
                            headlineContent = { Text("阴影强度: ${elevInput.roundToInt()} dp") },
                            supportingContent = {
                                Slider(
                                    value = elevInput,
                                    onValueChange = { elevInput = it },
                                    valueRange = MIN_ELEVATION.toFloat()..MAX_ELEVATION.toFloat(),
                                    steps = MAX_ELEVATION - MIN_ELEVATION - 1
                                )
                            }
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text("取消") } },
                confirmButton = {
                    Button(onClick = {
                        cornerRadiusDp = cornerInput.roundToInt()
                        sideMarginDp = sideInput.roundToInt()
                        bottomGapDp = gapInput.roundToInt()
                        elevationDp = elevInput.roundToInt()
                        onDismiss()
                        // 立即应用到当前会话页，不必退出去再进来
                        reapplyToLiveFooters()
                    }) { Text("确定") }
                }
            )
        }
    }
}

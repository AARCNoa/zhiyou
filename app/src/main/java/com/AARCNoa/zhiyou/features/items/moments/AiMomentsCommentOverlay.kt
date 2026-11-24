package com.AARCNoa.zhiyou.features.items.moments

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.AARCNoa.zhiyou.ai.AiMomentsCommentEngine
import com.AARCNoa.zhiyou.ai.profileCountsText
import com.AARCNoa.zhiyou.features.items.chat.InlineDots
import com.AARCNoa.zhiyou.features.items.chat.marqueeBrush
import com.AARCNoa.zhiyou.ui.style.WxPalette
import com.AARCNoa.zhiyou.ui.utils.LifecycleOwnerProvider
import com.AARCNoa.zhiyou.ui.utils.dpToPx
import com.AARCNoa.zhiyou.ui.utils.statusBarHeight
import com.AARCNoa.zhiyou.ui.utils.XposedLifecycleOwner
import com.AARCNoa.zhiyou.ui.utils.setLifecycleOwner
import com.AARCNoa.zhiyou.ui.utils.theme.ModuleTheme
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.copyToClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "AiMomentsComment"

/**
 * 朋友圈「帮我评论」浮层。
 *
 * 形态与聊天页双击完全一致：先在标题栏下方出现一枚胶囊（三点动画 + 跑马灯边框），
 * 模型返回后原地展开成卡片，列出三条评论，点一条复制到剪贴板。
 *
 * 为什么不做成 Dialog：评论要粘贴回微信的评论框，模态遮罩会挡住朋友圈内容；
 * 这里只挂一层 `WRAP_CONTENT` 的 ComposeView 在 decorView 上，点别处直接穿透。
 *
 * 位置取「标题栏下方」而不是屏幕顶部：朋友圈详情页的标题栏是自绘的（不是 ActionBar），
 * 按内容列表的上沿定位，比硬编码状态栏高度稳。
 */
object AiMomentsCommentOverlay {

    /** 详情页内容列表：RecyclerView / ListView 的类名不会被混淆，用它取标题栏下沿。 */
    private val LIST_CLASS_HINTS = listOf("RecyclerView", "ListView")

    private val lock = Any()

    private var cardView: ComposeView? = null
    private var host: Activity? = null
    private var observer: LifecycleEventObserver? = null
    private var lifecycleOwner: XposedLifecycleOwner? = null

    /** 每次挂载 / 收起都 +1，用来作废「上一张卡片的延时收起」。 */
    private var viewToken = 0

    fun show(activity: Activity, post: AiMomentsCommentEngine.Post) {
        dismiss()

        val decor = activity.window?.decorView as? ViewGroup ?: return
        // 详情页的 decor 默认没有 ViewTreeLifecycleOwner，先垫一个随 Activity 走的兜底。
        runCatching { decor.setLifecycleOwner(LifecycleOwnerProvider.getOrCreate(activity)) }
        val lifecycle = (activity as? LifecycleOwner)?.lifecycle
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) dismiss()
        }.also { lifecycle?.addObserver(it) }

        // 挂在 decorView 上的 ComposeView 必须自带 owner，否则组合会停在非 RESUMED 状态。
        val owner = XposedLifecycleOwner.create()
        val view = ComposeView(activity).apply {
            isSaveEnabled = false
            setLifecycleOwner(owner)
            setContent {
                ModuleTheme {
                    CommentCard(post = post, onDismiss = { dismiss() })
                }
            }
        }
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP,
        ).apply {
            topMargin = topInset(activity, decor)
        }

        synchronized(lock) {
            viewToken++
            decor.addView(view, params)
            host = activity
            lifecycleOwner = owner
            this.observer = observer
            cardView = view
        }
    }

    fun dismiss() {
        val snapshot = synchronized(lock) {
            viewToken++
            val current = Snapshot(cardView, lifecycleOwner, observer, host)
            cardView = null
            lifecycleOwner = null
            observer = null
            host = null
            current
        }
        val action = Runnable {
            runCatching { snapshot.card?.let { (it.parent as? ViewGroup)?.removeView(it) } }
            runCatching {
                val activity = snapshot.activity as? LifecycleOwner
                if (activity != null && snapshot.observer != null) {
                    activity.lifecycle.removeObserver(snapshot.observer)
                }
            }
            runCatching { snapshot.owner?.onDestroy() }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            Handler(Looper.getMainLooper()).post(action)
        }
    }

    private class Snapshot(
        val card: ComposeView?,
        val owner: XposedLifecycleOwner?,
        val observer: LifecycleEventObserver?,
        val activity: Activity?,
    )

    // ── 定位 ────────────────────────────────────────────────────────────────

    /**
     * 胶囊/卡片的上边距：内容列表的上沿（标题栏下沿）与状态栏高度取较大者，再留 8dp。
     *
     * 找不到列表时退回状态栏高度 —— 卡片会压在标题栏上，但至少不会跑到屏幕外。
     */
    private fun topInset(activity: Activity, decor: ViewGroup): Int {
        val decorLocation = IntArray(2)
        decor.getLocationInWindow(decorLocation)
        var listTop = 0
        fun walk(view: View, depth: Int) {
            if (depth > 24 || view.visibility != View.VISIBLE) return
            if (view.isShown && view.height > 0 && isContentList(view)) {
                val location = IntArray(2)
                view.getLocationInWindow(location)
                val top = location[1] - decorLocation[1]
                if (top > 0 && (listTop == 0 || top < listTop)) listTop = top
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) walk(view.getChildAt(index), depth + 1)
            }
        }
        walk(decor, 0)
        return maxOf(listTop, activity.statusBarHeight(28)) + 8.dpToPx(activity)
    }

    private fun isContentList(view: View): Boolean {
        var clazz: Class<*>? = view.javaClass
        var depth = 0
        while (clazz != null && depth < 6) {
            val name = clazz.name
            if (LIST_CLASS_HINTS.any { name.contains(it) }) return true
            clazz = clazz.superclass
            depth++
        }
        return false
    }

}

/**
 * 胶囊 → 卡片。生成过程放在 Compose 的生命周期里，卡片被关掉就作废这次请求。
 */
@Composable
private fun CommentCard(post: AiMomentsCommentEngine.Post, onDismiss: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<AiMomentsCommentEngine.Comment?>(null) }
    var attempt by remember { mutableStateOf(0) }
    /** 药丸上的阶段文案：只有「要等图片」时才出现，其余时间是空的。 */
    var stage by remember(attempt) { mutableStateOf("") }

    // 每次生成一枚「还需要结果」的标记：卡片被关掉、或点刷新换一批时置 false。
    // 请求本身是阻塞调用，没有这个标记的话关掉后仍会把整次请求跑完。
    val alive = remember(attempt) { AtomicBoolean(true) }
    DisposableEffect(alive) {
        onDispose { alive.set(false) }
    }

    LaunchedEffect(attempt) {
        expanded = false
        val generated = withContext(Dispatchers.IO) {
            AiMomentsCommentEngine.generate(post) { text ->
                withContext(Dispatchers.Main) { stage = text }
            }
        }
        if (!alive.get()) return@LaunchedEffect
        stage = ""
        result = generated
        expanded = true
    }

    val reveal by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(360, easing = FastOutSlowInEasing),
        label = "moments-comment-bloom",
    )
    val corner by animateDpAsState(if (expanded) 16.dp else 22.dp, label = "moments-comment-corner")
    val shape = RoundedCornerShape(corner)
    val border = if (expanded) Modifier else Modifier.border(1.6.dp, marqueeBrush(), shape)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .then(if (expanded) Modifier.fillMaxWidth() else Modifier)
                .graphicsLayer {
                    if (expanded) {
                        alpha = 0.4f + 0.6f * reveal
                        scaleX = 0.96f + 0.04f * reveal
                        scaleY = 0.96f + 0.04f * reveal
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                }
                .then(border)
                .clip(shape)
                .background(WxPalette.cardBackground())
                .then(if (expanded) Modifier else Modifier.clickable { onDismiss() })
                .padding(
                    horizontal = if (expanded) 14.dp else 16.dp,
                    vertical = if (expanded) 12.dp else 10.dp,
                ),
        ) {
            if (expanded) {
                val comment = result
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = comment?.authorName ?: "好友",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = WxPalette.textPrimary(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 120.dp),
                    )
                    Text(
                        text = "· AI 评论",
                        fontSize = 11.sp,
                        color = WxPalette.textTertiary(),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "↻",
                        fontSize = 14.sp,
                        color = WxPalette.textSecondary(),
                        modifier = Modifier
                            .clickable { attempt++ }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(18.dp))
                    Text(
                        text = "✕",
                        fontSize = 13.sp,
                        color = WxPalette.textTertiary(),
                        modifier = Modifier
                            .clickable { onDismiss() }
                            .padding(horizontal = 2.dp, vertical = 2.dp),
                    )
                }
                // 有档案才显示规模（记忆 / 标签条数），与建议卡片同一口径。
                val counts = profileCountsText(
                    comment?.memoryCount ?: 0,
                    comment?.tagCount ?: 0,
                )
                if (counts.isNotEmpty()) {
                    Text(
                        text = counts,
                        fontSize = 10.sp,
                        color = WxPalette.textTertiary(),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    InlineDots()
                    if (stage.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stage,
                            fontSize = 11.sp,
                            color = WxPalette.textTertiary(),
                        )
                    }
                }
            }

            if (expanded) {
                Spacer(Modifier.height(10.dp))
                CommentBody(post = post, comment = result, onRetry = { attempt++ })
            }
        }
    }
}

@Composable
private fun CommentBody(
    post: AiMomentsCommentEngine.Post,
    comment: AiMomentsCommentEngine.Comment?,
    onRetry: () -> Unit,
) {
    val context = LocalContext.current
    var copied by remember(comment) { mutableStateOf(-1) }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.42f).dp
    val preview = post.text
        .replace('\n', ' ')
        .trim()
        .ifBlank { if (post.isVideo) "（视频动态）" else "（图片动态）" }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = preview,
            fontSize = 11.sp,
            color = WxPalette.textTertiary(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        when {
            comment == null -> Text(
                text = "正在写…",
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
            )

            comment.error != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "生成失败：" + comment.error,
                    fontSize = 12.sp,
                    color = WxPalette.Danger,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "重试",
                    fontSize = 12.sp,
                    color = WxPalette.Primary,
                    modifier = Modifier
                        .clickable { onRetry() }
                        .padding(start = 10.dp),
                )
            }

            comment.replies.isEmpty() -> Text(
                text = "没有生成可用评论，换一批试试。",
                fontSize = 12.sp,
                color = WxPalette.textSecondary(),
            )

            else -> {
                comment.replies.forEachIndexed { index, reply ->
                    CommentRow(
                        reply = reply,
                        copied = copied == index,
                        onClick = {
                            if (copyToClipboard(context, reply, "朋友圈评论")) {
                                copied = index
                            } else {
                                WeLogger.w(TAG, "复制评论失败")
                            }
                        },
                    )
                }
                Text(
                    text = if (comment.imageSkipped) {
                        // 图片模型不可用时重试成了纯文字：这里注明，避免被误认为已读图。
                        "图片未能识别，已按文字生成 · 点一条复制，回朋友圈长按粘贴"
                    } else {
                        "点一条复制，回朋友圈长按文字粘贴"
                    },
                    fontSize = 10.sp,
                    color = WxPalette.textTertiary(),
                )
            }
        }

        // 思考过程默认收起：评论只有三条，展开思考会盖住结果；想看的人自己点开。
        if (comment != null && comment.thinking.isNotBlank()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(WxPalette.divider())
            )
            ThinkingSection(comment.thinking)
        }
    }
}

/** 思考过程：与聊天建议的「思考过程」同一套形态，只是这里只有一轮。 */
@Composable
private fun ThinkingSection(text: String) {
    var open by remember(text) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(220))) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "思考过程",
                fontSize = 11.sp,
                color = WxPalette.textSecondary(),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (open) "收起" else "展开",
                fontSize = 11.sp,
                color = WxPalette.Primary,
            )
        }
        if (open) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                fontSize = 11.sp,
                color = WxPalette.textSecondary(),
                lineHeight = 16.sp,
            )
        }
    }
}

@Composable
private fun CommentRow(reply: String, copied: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(WxPalette.insetSurface())
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = reply,
            fontSize = 14.sp,
            color = WxPalette.textPrimary(),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (copied) "已复制" else "复制",
            fontSize = 11.sp,
            color = if (copied) WxPalette.Primary else WxPalette.textTertiary(),
        )
    }
}

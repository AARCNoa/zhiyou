package com.AARCNoa.zhiyou.features.items.ai

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import com.AARCNoa.zhiyou.activity.settings.ai.UnreadBadge
import com.AARCNoa.zhiyou.ai.AiOverviewEntryState
import com.AARCNoa.zhiyou.ai.AiOverviewStore
import com.AARCNoa.zhiyou.ai.group.GroupDigestStore
import com.AARCNoa.zhiyou.utils.HostInfo
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.getTopMostActivity
import com.AARCNoa.zhiyou.features.items.beautify.HomeSidePanelFeature
import com.AARCNoa.zhiyou.ui.style.WxPalette
import com.AARCNoa.zhiyou.ui.utils.dpToPx
import com.AARCNoa.zhiyou.ui.utils.statusBarHeight
import com.AARCNoa.zhiyou.ui.utils.InjectedUiTheme
import com.AARCNoa.zhiyou.ui.utils.LifecycleOwnerProvider
import com.AARCNoa.zhiyou.ui.utils.setLifecycleOwner
import com.AARCNoa.zhiyou.ui.utils.theme.ThemeSettings

/**
 * 首页「总览」入口：标题栏里、搜索图标左边一枚 48dp 触摸区的图标 + 未读角标。
 *
 * 挂在 `decorView` 上按固定坐标摆放，**不插进微信的标题栏按钮区**（原因见 [attachFloating]：
 * 微信刷新菜单会 `removeAllViews` 重建那层，插进去就会不停闪）。代价是位置与显隐得自己算：
 * - 位置：状态栏 + 标题栏内垂直居中，右侧让出「＋」与搜索两个 48dp 触摸区；
 * - 显隐：400ms 轮询只管「Tab / 面板」；内容来源（群自动总结 / 好友自动回复）由开关写入时
 *   通知（[AiOverviewEntryState]），不在轮询里读 store。两者都没有时不显示。
 */
object GroupDigestEntry {

    private const val SIZE_DP = 48
    private const val POLL_MS = 400L
    private const val LAUNCHER = "com.tencent.mm.ui.LauncherUI"
    private const val TAG = "GroupDigestEntry"

    /** 挂载容器：detach 时只认 parent，不关心挂在哪一层。 */
    private var root: FrameLayout? = null
    private var host: Activity? = null
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private var handler: Handler? = null
    private var polling = false

    /** 只在状态变化时打，避免 400ms 轮询把日志刷满。 */
    private var lastSyncState: String? = null

    /** 上一次 sync 的「首页可见」结论与宿主，开关变化时直接据此重算，不必等下一次轮询。 */
    private var lastVisible = false
    private var lastActivity: Activity? = null

    /** 红点数字；Compose 侧直接读这个 State，写线程不做限制。 */
    private val unread = mutableStateOf(0)

    /**
     * 跟 Activity 生命周期挂载，**不依赖侧边栏功能是否开启**。
     *
     * 侧边栏关闭时它的 400ms 轮询不会启动，所以这里必须自己跟 resume / pause。
     */
    fun register(app: Application) {
        if (callbacks != null) return
        val created = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = onResume(activity)
            override fun onActivityPaused(activity: Activity) = onPause(activity)
            override fun onActivityDestroyed(activity: Activity) {
                if (host === activity) {
                    stopPolling()
                    detach()
                    lastVisible = false
                    lastActivity = null
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        }
        runCatching { app.registerActivityLifecycleCallbacks(created) }
            .onSuccess { callbacks = created }
        // 入口显隐跟着两个开关走，不轮询 store：初值在后台建一次，之后由写入路径通知。
        lastVisible = false
        lastActivity = null
        AiOverviewEntryState.setListener {
            Handler(Looper.getMainLooper()).post { applyEntryVisibility() }
        }
        Thread({
            runCatching { AiOverviewEntryState.initialize() }
            Handler(Looper.getMainLooper()).post { applyEntryVisibility() }
        }, "WCX-OverviewSource").start()
        // 冷启动时模块可能晚于 LauncherUI.onResume 加载，那次 resume 已经错过：
        // 不补这一次，入口要等用户切一次页面才会出现。注册那一刻首页往往还没起来，
        // 所以按秒重试，拿到 LauncherUI 就当作一次 resume。
        retryAttachUntilLauncher(0)
    }

    private fun retryAttachUntilLauncher(attempt: Int) {
        if (root?.parent != null) return
        if (attempt > 40) {
            runCatching { WeLogger.w(TAG, "40 秒内没等到 LauncherUI，入口等用户切页后再挂") }
            return
        }
        val h = Handler(Looper.getMainLooper())
        h.postDelayed({
            if (root?.parent != null) return@postDelayed
            val current = runCatching { getTopMostActivity() }.getOrNull()
            if (current != null && current.javaClass.name == LAUNCHER) {
                onResume(current)
            } else {
                retryAttachUntilLauncher(attempt + 1)
            }
        }, 1000L)
    }

    fun unregister() {
        callbacks?.let { runCatching { HostInfo.application.unregisterActivityLifecycleCallbacks(it) } }
        callbacks = null
        AiOverviewEntryState.setListener(null)
        lastVisible = false
        lastActivity = null
        stopPolling()
        detach()
    }

    private fun onResume(act: Activity) {
        if (act.javaClass.name != LAUNCHER) return
        // 已经从别的页面回来：只要还挂在窗口上就别重挂，否则每返回一次就闪一下。
        val current = root
        if ((current == null || current.parent == null) && AiOverviewEntryState.visible) {
            attachFloating(act)
        }
        startPolling(act)
    }

    private fun onPause(act: Activity) {
        if (host !== act) return
        stopPolling()
        // 不 detach：LauncherUI 只是进后台（打开设置页、切到聊天），入口还在原处；
        // 真销毁由 onActivityDestroyed 清。否则每次返回首页都要重插一次，图标会闪。
    }

    /**
     * 每 400ms 判一次：Tab 切换不发 Activity 生命周期，只能轮询。
     * 侧边栏的轮询在跑时交给它，避免同一个 decor 树被扫两遍。
     */
    private fun startPolling(act: Activity) {
        if (polling) return
        polling = true
        val h = Handler(Looper.getMainLooper())
        handler = h
        h.post(object : Runnable {
            override fun run() {
                if (!polling) return
                if (!HomeSidePanelFeature.isPollerActive) {
                    sync(act, HomeSidePanelFeature.isHomePageActive(act) && !HomeSidePanelFeature.isPanelOpen)
                }
                h.postDelayed(this, POLL_MS)
            }
        })
    }

    private fun stopPolling() {
        polling = false
        handler?.removeCallbacksAndMessages(null)
        handler = null
    }

    fun sync(act: Activity, visible: Boolean) {
        lastVisible = visible
        lastActivity = act
        val current = root
        // 没有任何内容来源时入口不出现：空面板只会占标题栏。
        val show = visible && AiOverviewEntryState.visible
        val state = "visible=$visible show=$show root=${current != null} " +
            "parent=${current?.parent != null} host=${host?.javaClass?.simpleName}"
        if (state != lastSyncState) {
            lastSyncState = state
            runCatching { WeLogger.d(TAG, "sync $state") }
        }
        if (current != null && current.parent != null) {
            // 四个 Tab 共用同一棵 decorView：显隐按「当前是不是微信 Tab」来。
            current.visibility = if (show) View.VISIBLE else View.GONE
            if (show) refreshUnread()
            return
        }
        if (show) attachFloating(act)
    }

    fun refreshUnread() {
        // 总览里有两块：群摘要的未读，和自动回复记下的好友约定。
        // 两边都有进程内缓存，轮询里各读一次不解析 JSON。
        val total = runCatching {
            GroupDigestStore.unreadTotal() + AiOverviewStore.unreadTotal()
        }.getOrDefault(0)
        if (unread.value != total) unread.value = total
    }

    /**
     * 开关变化时立即应用；未挂载但当前在首页就顺手挂上，不必等下一次 400ms 轮询。
     *
     * 调用方保证在主线程（[AiOverviewEntryState] 的监听已 post 到 main）。
     */
    private fun applyEntryVisibility() {
        val show = lastVisible && AiOverviewEntryState.visible
        val current = root
        if (current != null && current.parent != null) {
            current.visibility = if (show) View.VISIBLE else View.GONE
            if (show) refreshUnread()
            return
        }
        if (show) lastActivity?.let { attachFloating(it) }
    }

    fun detach() {
        val view = root ?: return
        runCatching { (view.parent as? ViewGroup)?.removeView(view) }
        root = null
        host = null
    }

    private fun actionBarHeight(context: android.content.Context): Int {
        val attrs = context.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
        return try {
            attrs.getDimensionPixelSize(0, 56.dpToPx(context))
        } finally {
            attrs.recycle()
        }
    }

    /**
     * 挂在 decorView 上，按标题栏的固定位置摆放。
     *
     * 试过两种「更贴」的做法，都不采用：
     * 1. `addView` 进标题栏按钮区——微信每次刷新菜单都会 `removeAllViews` 重建那层，图标被清掉
     *    又补回来，肉眼就是不停闪；
     * 2. 按搜索按钮的 `getLocationOnScreen` 贴到它左边——取到的是按钮容器的左边缘，比原位置
     *    再偏左 4dp，看起来离放大镜更远。
     */
    private fun attachFloating(act: Activity) {
        val decor = act.window?.decorView as? ViewGroup ?: run {
            runCatching { WeLogger.w(TAG, "挂载失败：decorView 不是 ViewGroup") }
            return
        }
        val size = SIZE_DP.dpToPx(act)
        val container = buildContainer(act, size, size)
        val lp = FrameLayout.LayoutParams(size, size).apply {
            gravity = Gravity.END or Gravity.TOP
            // 标题栏内、搜索图标左边：状态栏 + 标题栏内垂直居中；右侧让出「＋」和
            // 「搜索」两个 48dp 触摸区。View 只有 48dp，方块外的触摸落到微信自己的控件上。
            // 86 = 92 - 6：整体右移 6dp，离放大镜近一档。
            topMargin = act.statusBarHeight() + ((actionBarHeight(act) - size) / 2)
            rightMargin = 86.dpToPx(act)
        }
        val added = runCatching { decor.addView(container, lp) }
        val error = added.exceptionOrNull()
        if (error != null) {
            // 宿主正在重建 View 树时 addView 会抛；不吞掉，否则入口凭空消失且日志干净。
            runCatching { WeLogger.e(TAG, "挂载失败：${error.javaClass.simpleName}", error) }
            return
        }
        root = container
        host = act
        runCatching { WeLogger.d(TAG, "入口已挂载：top=${lp.topMargin} right=${lp.rightMargin}") }
        refreshUnread()
    }

    private fun buildContainer(act: Activity, width: Int, height: Int): FrameLayout {
        val container = FrameLayout(act)
        val compose = ComposeView(act).apply {
            setLifecycleOwner(LifecycleOwnerProvider.lifecycleOwner)
            setContent {
                InjectedUiTheme(darkTheme = ThemeSettings.themeMode.resolve()) {
                    GroupDigestButton(unread.value) {
                        runCatching { HomeSidePanelFeature.showGroupDigest(act) }
                    }
                }
            }
        }
        container.addView(compose, FrameLayout.LayoutParams(width, height))
        return container
    }


}

/** 便签图标：细线描边，线宽贴微信标题栏原生图标，不用 Material 默认的粗描边。 */
private val NoteIcon: ImageVector by lazy {
    ImageVector.Builder(
        name = "Note",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply {
        path(
            fill = null,
            stroke = SolidColor(Color.Black),
            // 微信标题栏的线性图标在 3x 屏上约 3px；1.05 个 viewport 单位换算下来正好，
            // 再粗一档就明显比旁边的搜索、＋ 重。
            strokeLineWidth = 1.05f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            // 纸张 + 右上角折角
            moveTo(5.5f, 3.8f)
            lineTo(15.2f, 3.8f)
            lineTo(18.6f, 7.2f)
            lineTo(18.6f, 20.4f)
            lineTo(5.5f, 20.4f)
            close()
            moveTo(15.2f, 3.8f)
            lineTo(15.2f, 7.2f)
            lineTo(18.6f, 7.2f)
            // 两条内容线：三条在 24dp 里太密，原生便签也只有两三条
            moveTo(8.4f, 11.4f)
            lineTo(15.7f, 11.4f)
            moveTo(8.4f, 15.2f)
            lineTo(15.7f, 15.2f)
            moveTo(8.4f, 19f)
            lineTo(12.8f, 19f)
        }
    }.build()
}

@Composable
private fun GroupDigestButton(count: Int, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                // 只留 48dp 触摸区，不画底色；clip 只用来收住点击涟漪。
                .clip(CircleShape)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            // 图标与角标放同一个盒子里：角标才能贴住图标的右上角，
            // 而不是飘在 48dp 触摸区的角落、离图标很远。
            Box {
                // 与微信标题栏自身的图标同色：浅色下深灰、深色下近白，两种主题都不会糊在背景里。
                Icon(
                    imageVector = NoteIcon,
                    contentDescription = "总览",
                    tint = WxPalette.textPrimary(),
                    modifier = Modifier.size(24.dp),
                )
                UnreadBadge(
                    count = count,
                    diameter = 14.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 5.dp, y = (-5).dp),
                )
            }
        }
    }
}

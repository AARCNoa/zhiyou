package com.AARCNoa.zhiyou.features.items.beautify

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.AARCNoa.zhiyou.BuildConfig
import com.AARCNoa.zhiyou.features.api.ui.WeChatMessageViewApi
import com.AARCNoa.zhiyou.features.core.ClickableFeature
import com.AARCNoa.zhiyou.features.core.Feature
import com.AARCNoa.zhiyou.preferences.WePrefs
import com.AARCNoa.zhiyou.preferences.WePrefs.Companion.prefOption
import com.AARCNoa.zhiyou.ui.content.AlertDialogContent
import com.AARCNoa.zhiyou.ui.content.Button
import com.AARCNoa.zhiyou.ui.content.DefaultColumn
import com.AARCNoa.zhiyou.ui.content.TextButton
import com.AARCNoa.zhiyou.ui.utils.showComposeDialog
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.showToast
import com.AARCNoa.zhiyou.utils.fs.KnownPaths
import com.AARCNoa.zhiyou.utils.fs.createDirsSafe
import dev.ujhhgtg.reflekt.reflekt
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.WeakHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * 主题商店：
 * - 入口：wcx 设置 → 设置 → 微信主题 → 主题商城
 * - 六款内置主题：Hello Kitty（花朵气泡）/ 小熊（miko 小狗边框气泡）/ 线条小狗（加油鸭鸭气泡）/
 *   吉伊卡哇（花朵气泡）/ 美食小狗（面包🍞气泡）/ 小兔子（猫咪气泡）
 * - 壁纸只作用于五个页面：消息主页、通讯录、发现、我、聊天背景。
 *   四个 Tab 的壁纸放在各自页容器的最底层（页面背景），聊天页写入微信自己的聊天背景 View，
 *   壁纸始终位于内容下方，不遮挡控件。
 * - 四个 Tab 内的中性底衬（白/灰/黑）在原 Drawable 上压 alpha，彩色背景、文字与图片不动；
 *   整页大小的灰底直接全透明；聊天页内部不处理，其它页面不接管。
 * - 气泡按「自定义消息气泡」的方式应用：主题气泡写入 <模块数据>/assets/left_bubble.9.png（对方）
 *   与 right_bubble.9.png（自己），9-patch 黑边解析为 NinePatchDrawable，同时开启该功能开关
 * - 界面透明度 5%~100% 滑杆调节（只改控件底衬，壁纸/文字/图片不透明，即时生效）
 * - 恢复默认主题：还原壁纸层/页面原背景/聊天背景与应用主题前的气泡
 * - 素材内置在模块 APK assets/theme_store/（壁纸 + 每主题 left/right 气泡）
 */
@Feature(
    name = "主题商城",
    categories = ["界面美化"],
    description = "六款卡通主题一键应用：消息主页、通讯录、发现、我、聊天背景五页壁纸 + 对应主题气泡，支持透明度调节与恢复默认主题"
)
object ThemeStore : ClickableFeature() {

    private const val TAG = "ThemeStore"
    /**
     * 控件底衬的不透明度：透明度滑杆越高，底衬越透明、壁纸越明显。
     * 只作用于背景，不影响文字/图片本身。
     */
    private fun backgroundAlpha(): Int =
        255 - (overlayOpacityPct.coerceIn(5, 100) * 255 / 100)
    private const val CHAT_BG_VIEW_CLASS = "com.tencent.mm.ui.chatting.ChattingImageBGView"
    private const val BOUNCE_VIEW_CLASS = "com.tencent.mm.ui.widget.pulldown.WeUIBounceViewV2"
    private val BOUNCE_COLOR_SETTERS = arrayOf(
        "setBgColor",
        "setStart2EndBgColor",
        "setEnd2StartBgColor",
        "setStart2EndBgColorByActionBar",
        "setEnd2StartBgColorByNavigationBar",
    )
    private val BOUNCE_DRAWABLE_SETTERS = arrayOf("setBg", "setStart2EndBg", "setEnd2StartBg")
    private const val LAUNCHER_UI_CLASS = "com.tencent.mm.ui.LauncherUI"
    private const val CHATTING_UI_CLASS = "com.tencent.mm.ui.chatting.ChattingUI"
    private const val LEFT_BUBBLE_FILE = "left_bubble.9.png"   // 对方
    private const val RIGHT_BUBBLE_FILE = "right_bubble.9.png" // 自己
    private const val CUSTOM_BUBBLES_PREF_KEY = "自定义消息气泡"
    /** 应用主题前用户没有自定义气泡的标记：恢复默认时据此删除主题气泡。 */
    private const val NO_BUBBLE_MARKER = "no_existing_bubbles"

    // ══════════════════════════════════════════════════════════
    // 持久化
    // ══════════════════════════════════════════════════════════
    private var activeThemeId by prefOption("theme_store_active_id", "")
    private var overlayOpacityPct by prefOption("theme_store_overlay_opacity", 40)

    // ══════════════════════════════════════════════════════════
    // 壁纸层 / 页面背景状态（都在主线程读写）
    // ══════════════════════════════════════════════════════════
    /** Tab 页容器 -> 铺在页面背景上的壁纸 Drawable。 */
    private val tabWallpapers = WeakHashMap<ViewGroup, Drawable>()

    /** Tab 页当前壁纸路径（换主题时据此重建 Drawable）。 */
    private val tabWallpaperPaths = WeakHashMap<ViewGroup, String?>()

    /** Tab 页原本的背景（恢复默认时还原）。 */
    private val pageOriginalBackgrounds = WeakHashMap<ViewGroup, Drawable?>()

    /** 当前已经被调成半透明的 Drawable（同一个 Drawable 不重复处理）。 */
    private val appliedBackgrounds = WeakHashMap<View, Drawable>()

    /** View 背景 Drawable 的原始 alpha（恢复默认时还原）。 */
    private val originalBgAlpha = WeakHashMap<View, Int>()
    private val originalImageAlpha = WeakHashMap<ImageView, Int>()


    /** 被清空的灰阶 tint（恢复默认时还原）。 */
    private val clearedTints = WeakHashMap<View, ColorStateList?>()

    /** Tab 页上次清背景的时间，避免每次布局都整页递归。 */
    private val lastBackgroundClearAt = WeakHashMap<ViewGroup, Long>()

    /** 已经处理过下拉回弹背景的页（每页只处理一次）。 */
    private val bounceHandledPages = WeakHashMap<ViewGroup, Boolean>()

    /** 已关闭磨砂层的页（FrostedContentView 会把内容模糊后再盖一层，壁纸全被挡）。 */
    private val frostedDisabledPages = WeakHashMap<ViewGroup, Boolean>()

    /** 已经挂了布局监听的 ViewPager。 */
    private val watchedPagers = java.util.Collections.newSetFromMap(WeakHashMap<ViewGroup, Boolean>())

    /** 聊天背景 View -> 原图（恢复默认时还原）。 */
    private val chatBackgrounds = WeakHashMap<ImageView, ChatBgBackup>()

    private data class ChatBgBackup(val drawable: Drawable?, val imageAlpha: Int)

    @Volatile private var chatHooksInstalled = false
    private var chatHookFailureLogged = false
    @Volatile private var bounceHooksInstalled = false
    @Volatile private var backgroundHooksInstalled = false
    @Volatile private var overlayHooksInstalled = false

    /** 当前壁纸位图缓存：同一张壁纸在四个页面共用，避免每次切页重复解码。 */
    private var wallpaperBitmapPath: String? = null
    private var wallpaperBitmap: Bitmap? = null

    // ══════════════════════════════════════════════════════════
    // 主题定义
    // ══════════════════════════════════════════════════════════
    private data class ThemeInfo(
        val id: String,
        val name: String,
        val wallpaperAsset: String,
        val bubbleOtherAsset: String, // 对方气泡（left）
        val bubbleSelfAsset: String,  // 自己气泡（right）
        val previewColor: Long
    )

    private val builtinThemes = listOf(
        ThemeInfo(
            id = "hellokitty", name = "Hello Kitty",
            wallpaperAsset = "theme_store/wallpaper/hellokitty.jpg",
            bubbleOtherAsset = "theme_store/bubbles/hellokitty/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/hellokitty/right_bubble.9.png",
            previewColor = 0xFFFFB6C1L
        ),
        ThemeInfo(
            id = "bear", name = "小熊",
            wallpaperAsset = "theme_store/wallpaper/bear.jpg",
            bubbleOtherAsset = "theme_store/bubbles/bear/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/bear/right_bubble.9.png",
            previewColor = 0xFFD7A76FL
        ),
        ThemeInfo(
            id = "puppy", name = "线条小狗",
            wallpaperAsset = "theme_store/wallpaper/puppy.jpg",
            bubbleOtherAsset = "theme_store/bubbles/puppy/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/puppy/right_bubble.9.png",
            previewColor = 0xFFFFE082L
        ),
        ThemeInfo(
            id = "chiikawa", name = "吉伊卡哇",
            wallpaperAsset = "theme_store/wallpaper/chiikawa.jpg",
            bubbleOtherAsset = "theme_store/bubbles/chiikawa/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/chiikawa/right_bubble.9.png",
            previewColor = 0xFFA8D8F0L
        ),
        ThemeInfo(
            id = "food_puppy", name = "美食小狗",
            wallpaperAsset = "theme_store/wallpaper/food_puppy.jpg",
            bubbleOtherAsset = "theme_store/bubbles/food_puppy/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/food_puppy/right_bubble.9.png",
            previewColor = 0xFFF7E0B0L
        ),
        ThemeInfo(
            id = "bunny", name = "小兔子",
            wallpaperAsset = "theme_store/wallpaper/bunny.jpg",
            bubbleOtherAsset = "theme_store/bubbles/bunny/left_bubble.9.png",
            bubbleSelfAsset = "theme_store/bubbles/bunny/right_bubble.9.png",
            previewColor = 0xFFFAD0DCL
        )
    )

    private fun builtinTheme(id: String): ThemeInfo? = builtinThemes.firstOrNull { it.id == id }

    /** 当前生效的主题（内置主题）。 */
    private fun currentTheme(): ThemeInfo? =
        builtinThemes.firstOrNull { it.id == activeThemeId }

    // ══════════════════════════════════════════════════════════
    // 素材目录（模块数据镜像）
    // ══════════════════════════════════════════════════════════
    private val themeStoreDir by lazy { (KnownPaths.moduleData / "theme_store").createDirsSafe() }
    private val builtinDir by lazy { (themeStoreDir / "builtin").createDirsSafe() }

    /**
     * 气泡备份目录。
     *
     * **不能用 lazy**：恢复默认会删掉整个目录，之后再次应用主题时 lazy 缓存的 Path 已失效，
     * `listDirectoryEntries()` 会抛 `NoSuchFileException`（日志里那条「备份气泡异常」就是它）。
     * 每次都 `createDirsSafe()` 才是幂等的。
     */
    private val backupBubbleDir: Path
        get() = (themeStoreDir / "backup_bubbles").createDirsSafe()

    private fun builtinThemeDir(theme: ThemeInfo) = builtinDir / theme.id

    private fun builtinWallpaperFile(theme: ThemeInfo) = builtinThemeDir(theme) / theme.wallpaperAsset.substringAfterLast('/')

    private fun builtinBubbleFile(theme: ThemeInfo, sideName: String) = builtinThemeDir(theme) / sideName

    // ══════════════════════════════════════════════════════════
    // 生命周期
    // ══════════════════════════════════════════════════════════
    @Volatile private var callbacksRegistered = false
    /** 已经排过一次「等 Application 就绪再注册」的主线程任务，避免重复入队。 */
    @Volatile private var callbackRetryPosted = false

    /** 已恢复过的主题 id，用来给 [ensureRestored] 去重。 */
    @Volatile private var restoredThemeId: String? = null

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {
            // 进程启动早期注册可能失败（Application 还没 attach、MMKV 还没就绪），
            // 这里每次 resume 都补一次 —— 这是「划掉后台重开、主题就丢了」的兜底。
            ensureCallbacks()
            ensureChatHooks()
            ensureBounceHooks()
            ensureRestored("Activity.onResume")
            if (activeThemeId.isNotEmpty()) {
                try { applyThemeTo(activity) } catch (e: Throwable) { WeLogger.w(TAG, "onActivityResumed 应用主题异常", e) }
            }
        }
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    override fun onEnable() {
        ensureCallbacks()
        ensureChatHooks()
        ensureBounceHooks()
        ensureRestored("onEnable")
    }

    override fun onDisable() {
        restoreDefault()
        registerCallbacks(false)
        restoredThemeId = null
    }

    /**
     * 幂等注册生命周期回调。
     *
     * [onEnable] 跑在 `callApplicationOnCreate` 里，此时 [HostApplication] 理论上可用，
     * 但不同 ROM 的 Application 初始化顺序并不一致；拿不到就等下次 resume 再试，
     * 不能像原来那样「失败一次就永久放弃」。
     */
    private fun ensureCallbacks() {
        if (callbacksRegistered) return
        val app = HostApplication
        if (app == null) {
            // Application 还没 attach：排到下一条主线程消息再试（onEnable 可能跑在 attach 中途）。
            if (!callbackRetryPosted) {
                callbackRetryPosted = true
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    callbackRetryPosted = false
                    ensureCallbacks()
                    ensureRestored("Application 就绪")
                }
            }
            return
        }
        try {
            app.registerActivityLifecycleCallbacks(activityCallbacks)
            callbacksRegistered = true
            ensureChatHooks()
            ensureBounceHooks()
            WeLogger.d(TAG, "已注册 Activity 生命周期回调")
        } catch (e: Throwable) {
            WeLogger.w(TAG, "注册生命周期回调异常", e)
        }
    }

    /**
     * 补一次主题恢复。
     *
     * [onEnable] 时刻可能还没有任何 Activity（`applyThemeToAllLive()` 遍历到空集合），
     * 也可能 MMKV 尚未就绪（`activeThemeId` 读成空串）。只在那里恢复一次，就会出现
     * 「配置里明明存着主题，重启后壁纸和气泡都没了」。
     *
     * 用 [restoredThemeId] 去重：同一个主题只全量应用一次，后续由各 Activity 的
     * `onResume` 单独补挂。
     */
    private fun ensureRestored(from: String) {
        val id = activeThemeId
        if (id.isEmpty() || id == restoredThemeId) return
        restoredThemeId = id
        applyThemeToAllLive()
        WeLogger.i(TAG, "已恢复主题：$id（触发点：$from）")
    }

    private fun registerCallbacks(register: Boolean) {
        try {
            val app = HostApplication ?: return
            if (register && !callbacksRegistered) {
                app.registerActivityLifecycleCallbacks(activityCallbacks)
                callbacksRegistered = true
            } else if (!register && callbacksRegistered) {
                app.unregisterActivityLifecycleCallbacks(activityCallbacks)
                callbacksRegistered = false
            }
        } catch (e: Throwable) { WeLogger.e(TAG, "registerCallbacks 异常", e) }
    }

    /**
     * 宿主 Application。
     *
     * **必须是 getter，不能用 `by lazy`**：`onEnable` 可能早于 Application attach，
     * 第一次访问拿到 null 就被永久缓存，之后 `createPackageContext`、注册生命周期回调
     * 全部失效 —— 表现就是「重启后主题没了、气泡也不生效」。
     */
    private val HostApplication: Application?
        get() = runCatching {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentApplication").invoke(null) as? Application
        }.getOrNull()

    // ══════════════════════════════════════════════════════════
    // 素材读取（模块 APK 内置 assets → 一次性提取到模块数据目录）
    // ══════════════════════════════════════════════════════════
    /** 从模块 APK assets 读取内置素材；失败返回 null。 */
    private fun readBuiltinAsset(assetPath: String): ByteArray? = try {
        val pkgCtx = HostApplication?.createPackageContext(BuildConfig.APPLICATION_ID, 0)
            ?: return null
        pkgCtx.assets.open(assetPath).use { it.readBytes() }
    } catch (e: Throwable) {
        WeLogger.w(TAG, "读取内置素材失败：$assetPath（$e）")
        null
    }

    /** 确保某个主题的素材已提取到模块数据目录（壁纸 + 两个气泡）。 */
    private fun ensureBuiltinExtracted(theme: ThemeInfo, force: Boolean = false) {
        val dir = builtinThemeDir(theme)
        val entries = listOf(
            theme.wallpaperAsset to builtinWallpaperFile(theme),
            theme.bubbleOtherAsset to builtinBubbleFile(theme, LEFT_BUBBLE_FILE),
            theme.bubbleSelfAsset to builtinBubbleFile(theme, RIGHT_BUBBLE_FILE)
        )
        for ((asset, dest) in entries) {
            if (!force && dest.exists()) continue
            val bytes = readBuiltinAsset(asset) ?: continue
            try {
                dir.createDirsSafe()
                Files.write(dest, bytes)
            } catch (e: Throwable) { WeLogger.w(TAG, "提取素材失败：$asset", e) }
        }
    }

    /** 对话框打开时一次性提取全部内置主题素材（缩略图直接从本地文件加载）。 */
    private fun ensureAllBuiltinExtracted() {
        try { builtinThemes.forEach { ensureBuiltinExtracted(it) } } catch (e: Throwable) { WeLogger.w(TAG, "提取全部素材异常", e) }
    }

    // ══════════════════════════════════════════════════════════
    // 应用主题
    // ══════════════════════════════════════════════════════════
    private fun applyThemeInternal(id: String) {
        val theme = builtinTheme(id) ?: run { WeLogger.w(TAG, "未知主题：$id"); return }
        try {
            // 应用主题时必然有 Activity，是注册生命周期回调最可靠的时机。
            ensureCallbacks()
            ensureChatHooks()
            ensureBounceHooks()
            ensureBuiltinExtracted(theme)
            backupExistingBubbles()
            // 气泡写入模块 assets（left=对方 / right=自己），并开启「自定义消息气泡」开关
            copySafely(builtinBubbleFile(theme, LEFT_BUBBLE_FILE), KnownPaths.moduleAssets / LEFT_BUBBLE_FILE)
            copySafely(builtinBubbleFile(theme, RIGHT_BUBBLE_FILE), KnownPaths.moduleAssets / RIGHT_BUBBLE_FILE)
            WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, true)
            // 只写 pref 不会改变**当前进程**的运行时状态：主题是在设置页应用的，回到聊天页时
            // CustomMessageBubbles 仍是「关」，气泡要等下次重启微信才出现 —— 这就是
            // 「应用主题后气泡不见了」的根因。这里同步把运行时开关打开。
            if (!CustomMessageBubbles.isEnabled) {
                CustomMessageBubbles.isEnabled = true
                WeLogger.i(TAG, "已同时启用「自定义消息气泡」")
            }
            activeThemeId = id
            applyThemeToAllLive()
            // 气泡是 View 级样式，已经渲染出来的消息不会自己刷新；
            // 让聊天列表重绑一次，回到聊天页立刻就能看到新气泡。
            WeChatMessageViewApi.refreshChattingList()
            WeLogger.i(TAG, "已应用主题：${theme.name}")
        } catch (e: Throwable) {
            WeLogger.e(TAG, "applyThemeInternal 异常", e)
        }
    }

    private fun copySafely(src: java.nio.file.Path, dst: java.nio.file.Path) {
        if (!src.exists()) { WeLogger.w(TAG, "复制源不存在：${src.absolutePathString()}"); return }
        try {
            dst.parent?.createDirsSafe()
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) { WeLogger.w(TAG, "复制失败：${src.name} → ${dst.name}", e) }
    }

    /** 壁纸文件（内置主题提取文件）。 */
    private fun wallpaperSource(): java.nio.file.Path? =
        builtinTheme(activeThemeId)?.let { builtinWallpaperFile(it) }?.takeIf { it.exists() }

    // ══════════════════════════════════════════════════════════
    // 应用范围：只有消息主页/通讯录/发现/我（LauncherUI 四页）与聊天背景
    // ══════════════════════════════════════════════════════════
    private fun applyThemeToAllLive() {
        liveActivities().forEach { act ->
            try { applyThemeTo(act) } catch (e: Throwable) { WeLogger.w(TAG, "应用主题到 ${act.javaClass.simpleName} 异常", e) }
        }
    }

    private fun applyThemeTo(activity: Activity) {
        if (activeThemeId.isEmpty()) return
        if (isLauncherUI(activity)) {
            applyTabWallpapers(activity)
            // 聊天页在 8.x 既可能是独立 Activity，也可能是 LauncherUI 里的 Fragment；
            // 后者没有 Activity resume 信号，这里顺带扫一次。
            applyChatBackground(activity)
            runCatching { convertPanelBackgrounds(activity) }
        } else if (isChattingPage(activity)) {
            applyChatBackground(activity)
        }
    }

    private fun isLauncherUI(activity: Activity): Boolean =
        activity.javaClass.name == LAUNCHER_UI_CLASS

    /**
     * 聊天页判定：沿父类链匹配 `ChattingUI` 及其 8.0.80 的 26+ 个变体
     * （ChattingMainUI / BizHalfScreenChattingUI / FinderChattingUI / VoipChattingUI /
     * OpenImKefuServiceChattingUI / variants.AppBrandChattingUI 等）。
     * 精确等号只能覆盖主类，因此用包含关系；未来新增子类同样命中。
     */
    private fun isChattingPage(activity: Activity): Boolean {
        var clazz: Class<*>? = activity.javaClass
        var depth = 0
        while (clazz != null && depth < 8) {
            val name = clazz.name
            if (name == CHATTING_UI_CLASS ||
                name.contains("ChattingUI") ||
                name.contains("ChattingMainUI")
            ) {
                return true
            }
            clazz = clazz.superclass
            depth++
        }
        return false
    }

    // ══════════════════════════════════════════════════════════
    // 四个 Tab：壁纸插在各自页容器的 index 0（所有 UI 之下）
    // ══════════════════════════════════════════════════════════
    private fun applyTabWallpapers(activity: Activity) {
        val decor = activity.window?.decorView ?: return
        fun decorateOnce() {
            if (activeThemeId.isEmpty()) return
            val pager = findTabPager(activity) ?: return
            watchPager(pager)
            decorateTabPages(pager)
        }
        decorateOnce()
        // ViewPager / 子页可能晚于 onResume 创建（尤其冷启动）。
        // 补几次「重新找 pager」，不能只在找到 pager 后补。
        for (delay in longArrayOf(300L, 900L, 1800L, 3000L)) {
            decor.postDelayed({ runCatching { decorateOnce() } }, delay)
        }
    }

    /** 在 LauncherUI 里找 Tab 的 ViewPager；四个 Tab 页就是它的前四个子 View。 */
    private fun findTabPager(activity: Activity): ViewGroup? {
        val decor = activity.window?.decorView as? ViewGroup ?: return null
        var best: ViewGroup? = null
        var bestScore = Long.MIN_VALUE
        var visited = 0
        fun walk(v: View, depth: Int) {
            if (visited >= 6000 || depth > 32) return
            visited++
            if (v is ViewGroup && v.childCount >= 2 && isViewPagerLike(v)) {
                // 有 4 个子页的优先；同为 4 页时取面积最大的（排除页面内部的小 Pager）。
                val fourPages = if (v.childCount >= 4) 1_000_000_000L else 0L
                val score = fourPages + v.width.toLong() * v.height
                if (score > bestScore) {
                    bestScore = score
                    best = v
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(decor, 0)
        return best
    }

    private fun isViewPagerLike(v: ViewGroup): Boolean {
        var clazz: Class<*>? = v.javaClass
        var depth = 0
        while (clazz != null && depth < 6) {
            if (clazz.name.contains("ViewPager")) return true
            clazz = clazz.superclass
            depth++
        }
        return false
    }

    /** 页面被懒创建/切换时重新铺一次；监听只装一次，随 pager 自己销毁。 */
    private fun watchPager(pager: ViewGroup) {
        if (!watchedPagers.add(pager)) return
        runCatching {
            pager.viewTreeObserver.addOnGlobalLayoutListener {
                if (activeThemeId.isEmpty()) return@addOnGlobalLayoutListener
                runCatching { decorateTabPages(pager) }
            }
        }.onFailure {
            watchedPagers.remove(pager)
            WeLogger.d(TAG, "Tab 页布局监听安装失败：${it.message}")
        }
    }

    private fun decorateTabPages(pager: ViewGroup) {
        if (activeThemeId.isEmpty()) return
        val count = minOf(4, pager.childCount)
        for (index in 0 until count) {
            val page = pager.getChildAt(index) as? ViewGroup ?: continue
            runCatching { ensureTabWallpaper(page, index) }
        }
    }

    private fun ensureTabWallpaper(page: ViewGroup, index: Int) {
        if (activeThemeId.isEmpty()) return
        val theme = currentTheme() ?: return
        val wallpaperPath = wallpaperSource()?.absolutePathString()
        val current = tabWallpapers[page]
        // 背景可能被微信重设；换主题时路径也会变，两种情况都重建一次。
        if (current == null || tabWallpaperPaths[page] != wallpaperPath || page.background !== current) {
            val bitmap = wallpaperBitmapFor(wallpaperSource())
            val drawable: Drawable = if (bitmap != null) {
                BitmapDrawable(page.resources, bitmap)
            } else {
                // 壁纸文件缺失：回退主题色
                ColorDrawable(theme.previewColor.toInt())
            }
            if (!pageOriginalBackgrounds.containsKey(page)) {
                pageOriginalBackgrounds[page] = page.background
            }
            page.background = drawable
            tabWallpapers[page] = drawable
            tabWallpaperPaths[page] = wallpaperPath
            WeLogger.d(TAG, "tab #$index 壁纸层已挂载：${page.javaClass.simpleName}")
        } else {
            // 壁纸本身始终不透明；透明度只作用于列表/卡片的底衬。
            current.alpha = 255
        }
        runCatching {
            page.javaClass.getMethod("setFrostedEnabled", java.lang.Boolean.TYPE).invoke(page, false)
        }
        if (frostedDisabledPages.put(page, true) == null) {
            WeLogger.d(TAG, "tab #$index 已关闭页面磨砂层")
        }
        if (bounceHandledPages.put(page, true) == null) {
            runCatching { clearBounceBackground(page) }
        }
        // 只处理这四个页面：清掉内部的白/灰/黑底衬，让壁纸从 UI 下方透出来。
        // 页面内容是懒加载的，所以按页节流重扫，迟到创建/被微信重设的背景也能清掉。
        val now = android.os.SystemClock.uptimeMillis()
        val last = lastBackgroundClearAt[page] ?: 0L
        if (now - last >= 400L) {
            lastBackgroundClearAt[page] = now
            val cleared = runCatching { clearPageBackgrounds(page) }.getOrDefault(0)
            if (cleared > 0) WeLogger.d(TAG, "tab #$index 透明底衬 $cleared 个")
        }
    }

    private fun wallpaperBitmapFor(src: java.nio.file.Path?): Bitmap? {
        if (src == null) return null
        val path = src.absolutePathString()
        wallpaperBitmap?.takeIf { wallpaperBitmapPath == path && !it.isRecycled }?.let { return it }
        val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        wallpaperBitmapPath = path
        wallpaperBitmap = bitmap
        return bitmap
    }

    /**
     * 把四个 Tab 页内部的中性底衬改成半透明，让底层壁纸透出来。
     *
     * 白/浅灰/深灰/黑都算底衬：只在原 Drawable 上改 alpha，不替换背景对象，
     * 圆角、九宫格留白和状态都不变。占页面一半以上的 ColorDrawable 直接全透明，
     * 避免整页灰纱；列表行、小卡片按滑杆保留底衬。
     * 彩色背景（红点、彩色标签、彩色卡片）保持原样；文字、头像、按钮等控件
     * 自身的背景不处理。每页最多遍历 6000 个节点，被改的 View 记录原 alpha，
     * 恢复默认时还原。
     */
    private fun clearPageBackgrounds(root: ViewGroup): Int {
        val dm = root.resources.displayMetrics
        val screen = dm.widthPixels.toLong() * dm.heightPixels
        var visited = 0
        var cleared = 0
        fun walk(v: View, depth: Int) {
            if (visited >= 6000 || depth > 32) return
            visited++
            val fullPage = v.width.toLong() * v.height >= screen / 2
            val control = v is TextView || v is ImageView || v is ProgressBar ||
                v is SeekBar || v is CompoundButton
            if (!control) {
                val bg = v.background
                if (bg != null) {
                    if (bg === appliedBackgrounds[v]) {
                        // 微信可能自己改回不透明，每轮按滑杆重新压一次。
                        val original = originalBgAlpha[v] ?: 255
                        runCatching {
                            bg.alpha = if (fullPage && bg is ColorDrawable) {
                                0
                            } else {
                                minOf(original, backgroundAlpha())
                            }
                        }
                    } else {
                        val originalAlpha = bg.alpha
                        if (makeBackgroundTranslucent(bg, fullPage = fullPage)) {
                            if (!originalBgAlpha.containsKey(v)) {
                                originalBgAlpha[v] = originalAlpha
                            }
                            appliedBackgrounds[v] = bg
                            cleared++
                        }
                    }
                }
                val tint = v.backgroundTintList
                if (tint != null && isNeutralBackdrop(tint.defaultColor)) {
                    if (!clearedTints.containsKey(v)) clearedTints[v] = tint
                    runCatching { v.backgroundTintList = null }
                }
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(root, 0)
        return cleared
    }

    /**
     * 直接改 View 现有背景 Drawable 的 alpha（不换背景对象）。
     *
     * 只调 Drawable.alpha，不碰 padding / 圆角 / 状态列表，所以控件位置不会漂移，
     * 也不会受「先 setBackground 再被微信重设」的影响。
     * - BitmapDrawable（普通图片）不动；
     * - ColorDrawable 只动灰阶（白/浅灰/深灰/黑）；
     * - NinePatch / Gradient / StateList / Ripple / Layer 等底衬统一 setAlpha。
     */
    private fun makeBackgroundTranslucent(
        bg: Drawable,
        allowBitmap: Boolean = false,
        fullPage: Boolean = false,
    ): Boolean {
        // 普通页面里的 BitmapDrawable 是头像/图片，不能动；
        // 下拉小程序面板里的 BitmapDrawable 是模糊灰层，要允许压透明。
        if (bg is BitmapDrawable && !allowBitmap) return false
        if (bg is ColorDrawable && !isNeutralBackdrop(bg.color)) return false
        return runCatching {
            bg.mutate()
            val original = bg.alpha
            // 整页大小的灰底（通讯录列表底）直接全透明，否则会留一层灰纱，
            // 看起来比消息页暗。列表行/小卡片仍按滑杆保留底衬。
            bg.alpha = if (fullPage && bg is ColorDrawable) 0 else minOf(original, backgroundAlpha())
            true
        }.getOrDefault(false)
    }

    /**
     * 灰阶都算「底衬」（白/浅灰/深灰/黑），纯彩色（红点、彩色标签/卡片）不动。
     * 下拉小程序的灰色遮罩也由这里命中。
     */
    private fun isNeutralBackdrop(color: Int): Boolean {
        if ((color ushr 24) == 0) return false
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return maxOf(r, g, b) - minOf(r, g, b) <= 12
    }

    private fun removeTabWallpapers() {
        tabWallpapers.entries.toList().forEach { (page, drawable) ->
            runCatching {
                if (page.background === drawable) {
                    page.background = pageOriginalBackgrounds[page]
                }
                page.javaClass.getMethod("setFrostedEnabled", java.lang.Boolean.TYPE)
                    .invoke(page, true)
            }
        }
        tabWallpapers.clear()
        tabWallpaperPaths.clear()
        pageOriginalBackgrounds.clear()
        frostedDisabledPages.clear()
    }

    private fun restorePageBackgrounds() {
        originalBgAlpha.entries.toList().forEach { (view, alpha) ->
            runCatching { view.background?.alpha = alpha }
        }
        clearedTints.entries.toList().forEach { (view, tint) ->
            runCatching { view.backgroundTintList = tint }
        }
        originalImageAlpha.entries.toList().forEach { (view, alpha) ->
            runCatching { view.imageAlpha = alpha }
        }
        originalBgAlpha.clear()
        originalImageAlpha.clear()
        clearedTints.clear()
        appliedBackgrounds.clear()
        lastBackgroundClearAt.clear()
    }

    // ══════════════════════════════════════════════════════════
    // 聊天背景：写微信自己的 ChattingImageBGView
    // ══════════════════════════════════════════════════════════
    private fun ensureChatHooks() {
        if (chatHooksInstalled) return
        val app = HostApplication ?: return
        runCatching {
            val clazz = Class.forName(CHAT_BG_VIEW_CLASS, false, app.classLoader)
            clazz.reflekt().constructors().forEach { ctor ->
                ctor.hookAfter {
                    val iv = thisObject as? ImageView ?: return@hookAfter
                    if (activeThemeId.isEmpty()) return@hookAfter
                    // 微信在构造后还会写默认/用户背景，这里延后覆盖一次。
                    iv.post { runCatching { applyChatWallpaperTo(iv) } }
                    iv.postDelayed({ runCatching { applyChatWallpaperTo(iv) } }, 600L)
                }
            }
            chatHooksInstalled = true
            WeLogger.d(TAG, "聊天背景 hook 已安装")
        }.onFailure {
            if (!chatHookFailureLogged) {
                chatHookFailureLogged = true
                WeLogger.w(TAG, "聊天背景 hook 安装失败（已降级）：${it.message}")
            } else {
                WeLogger.d(TAG, "聊天背景 hook 仍未安装：${it.message}")
            }
        }
    }

    /**
     * 实时把新创建/复用的列表行背景压成半透明。
     *
     * 只在原 Drawable 上调 alpha，不替换对象、不动 padding，所以不会漂移；
     * 在 setBackground* 的当帧生效，滑列表时不会再闪一下白底。
     */
    private fun ensureBackgroundAlphaHooks() {
        if (backgroundHooksInstalled) return
        backgroundHooksInstalled = true
        for (name in arrayOf("setBackground", "setBackgroundDrawable")) {
            runCatching {
                View::class.reflekt()
                    .firstMethodOrNull { this.name = name; parameterCount = 1 }
                    ?.hookBefore {
                        if (activeThemeId.isEmpty()) return@hookBefore
                        val view = thisObject as? View ?: return@hookBefore
                        if (!shouldTint(view)) {
                            view.post {
                                if (activeThemeId.isNotEmpty() && shouldTint(view)) {
                                    convertBackground(view)
                                }
                            }
                            return@hookBefore
                        }
                        val drawable = args.getOrNull(0) as? Drawable ?: return@hookBefore
                        val original = drawable.alpha
                        if (makeBackgroundTranslucent(drawable)) {
                            if (!originalBgAlpha.containsKey(view)) {
                                originalBgAlpha[view] = original
                            }
                            appliedBackgrounds[view] = drawable
                        }
                    }
            }
        }
        // 下拉小程序的"模糊灰"可能是 RenderEffect 模糊；主题开启时直接去掉。
        runCatching {
            View::class.reflekt()
                .firstMethodOrNull { name = "setRenderEffect"; parameterCount = 1 }
                ?.hookBefore {
                    if (activeThemeId.isEmpty()) return@hookBefore
                    val view = thisObject as? View ?: return@hookBefore
                    if (isLauncherContext(view.context)) args[0] = null
                }
        }
        // 面板的模糊底图常是 ImageView.setImageBitmap，不是背景。
        for (name in arrayOf("setImageDrawable", "setImageBitmap", "setImageResource")) {
            runCatching {
                ImageView::class.reflekt()
                    .firstMethodOrNull { this.name = name; parameterCount = 1 }
                    ?.hookAfter {
                        if (activeThemeId.isEmpty()) return@hookAfter
                        val view = thisObject as? ImageView ?: return@hookAfter
                        if (!isLauncherContext(view.context)) return@hookAfter
                        if (!isMiniProgramPanel(view)) return@hookAfter
                        convertBackground(view)
                        view.post {
                            if (activeThemeId.isNotEmpty() && isMiniProgramPanel(view)) {
                                convertBackground(view)
                            }
                        }
                    }
            }
        }
        for (name in arrayOf(
            "setBackgroundColor",
            "setBackgroundResource",
            "setBackgroundTintList",
        )) {
            runCatching {
                View::class.reflekt()
                    .firstMethodOrNull { this.name = name; parameterCount = 1 }
                    ?.hookAfter {
                        if (activeThemeId.isEmpty()) return@hookAfter
                        val view = thisObject as? View ?: return@hookAfter
                        if (!shouldTint(view)) return@hookAfter
                        convertBackground(view)
                        // 行可能先 bind 后 attach，attach 后再补一次。
                        view.post { if (activeThemeId.isNotEmpty()) convertBackground(view) }
                    }
            }
        }
        WeLogger.d(TAG, "列表行背景实时透明 hook 已安装")
    }

    private fun shouldTint(view: View): Boolean {
        if (view is TextView || view is ProgressBar || view is SeekBar || view is CompoundButton) {
            return false
        }
        if (!isLauncherContext(view.context)) return false
        // 聊天页是 LauncherUI 里的 Fragment，不能按四个 Tab 的页面处理，
        // 否则输入栏背景会被压透明、透出壁纸发灰。
        if (isInChatView(view)) return false
        if (view is ImageView) return isMiniProgramPanel(view)
        return isInThemedPage(view) || isMiniProgramPanel(view)
    }

    private fun isInChatView(view: View): Boolean {
        var current: View? = view
        var depth = 0
        while (current != null && depth++ < 40) {
            val name = current.javaClass.name
            if (name.contains("Chatting") ||
                name.contains("ChatFooter") ||
                name.contains("ChatBottom")
            ) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun isInThemedPage(view: View): Boolean {
        var current: View? = view
        var depth = 0
        while (current != null && depth++ < 40) {
            if (tabWallpapers.containsKey(current)) return true
            current = current.parent as? View
        }
        return false
    }

    /** 下拉小程序面板：它可能是页面的兄弟节点，不在四个 Tab 页内部。 */
    private fun isMiniProgramPanel(view: View): Boolean {
        var current: View? = view
        var depth = 0
        while (current != null && depth++ < 40) {
            val name = current.javaClass.name
            if (name.contains("AppBrand") ||
                name.contains("MultiTask") ||
                name.contains("TaskBar") ||
                name.contains("Pulldown") ||
                name.contains("PullDown") ||
                name.contains("Recent")
            ) {
                return true
            }
            current = current.parent as? View
        }
        return false
    }

    private fun convertBackground(view: View) {
        val panel = isMiniProgramPanel(view)
        val dm = view.resources.displayMetrics
        val screen = dm.widthPixels.toLong() * dm.heightPixels
        val fullPage = view.width.toLong() * view.height >= screen / 2
        val bg = view.background
        if (bg != null) {
            val original = bg.alpha
            if (makeBackgroundTranslucent(bg, allowBitmap = panel, fullPage = fullPage)) {
                if (!originalBgAlpha.containsKey(view)) originalBgAlpha[view] = original
                appliedBackgrounds[view] = bg
            }
        }
        val tint = view.backgroundTintList
        if (tint != null && isNeutralBackdrop(tint.defaultColor)) {
            if (!clearedTints.containsKey(view)) clearedTints[view] = tint
            runCatching { view.backgroundTintList = null }
        }
        if (panel) {
            // 面板的"模糊灰"也可能来自前景 Drawable 或 RenderEffect。
            runCatching {
                val fg = view.foreground
                if (fg != null) makeBackgroundTranslucent(fg, allowBitmap = true)
            }
            // 大面积 ImageView 是模糊截图底图，隐藏它；小图标不受影响。
            if (view is ImageView && view.drawable is BitmapDrawable) {
                view.post {
                    val area = view.width.toLong() * view.height
                    val screen = view.resources.displayMetrics.run {
                        widthPixels.toLong() * heightPixels
                    }
                    if (area >= screen / 4) {
                        if (!originalImageAlpha.containsKey(view)) {
                            originalImageAlpha[view] = view.imageAlpha
                        }
                        view.imageAlpha = 0
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    view.javaClass.methods
                        .firstOrNull { it.name == "setRenderEffect" && it.parameterCount == 1 }
                        ?.invoke(view, null)
                }
            }
        }
    }

    /** 扫一遍 LauncherUI：把已经存在的下拉小程序面板背景也压成半透明。 */
    private fun convertPanelBackgrounds(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        var visited = 0
        fun walk(v: View, depth: Int) {
            if (visited++ > 8000 || depth > 40) return
            if (v.javaClass.name == "com.tencent.mm.ui.FrostedContentView") {
                runCatching {
                    v.javaClass.getMethod("setFrostedEnabled", java.lang.Boolean.TYPE)
                        .invoke(v, false)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    v.javaClass.methods
                        .firstOrNull { it.name == "setRenderEffect" && it.parameterCount == 1 }
                        ?.invoke(v, null)
                }
            }
            if (shouldTint(v)) runCatching { convertBackground(v) }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(decor, 0)
    }

    /**
     * 微信下拉小程序/多任务面板的灰色来自自绘的动态背景层，不是 View.background。
     * 主题开启时直接跳过这些 onDraw，露出下面的主题壁纸。
     */
    private fun ensureOverlayHooks() {
        if (overlayHooksInstalled) return
        val app = HostApplication ?: return
        for (className in arrayOf(
            "com.tencent.mm.dynamicbackground.view.GradientColorBackgroundView",
            "com.tencent.mm.plugin.taskbar.ui.TaskBarBottomView",
            "com.tencent.mm.plugin.taskbar.ui.TaskBarContainer",
            "com.tencent.mm.plugin.appbrand.widget.desktop.AppBrandDesktopContainerView",
        )) {
            runCatching {
                val clazz = Class.forName(className, false, app.classLoader)
                clazz.reflekt()
                    .firstMethodOrNull { name = "onDraw"; parameterCount = 1 }
                    ?.hookBefore {
                        if (activeThemeId.isNotEmpty()) result = null
                    }
            }
        }
        overlayHooksInstalled = true
        WeLogger.d(TAG, "动态背景/多任务灰层 hook 已安装")
    }

    /**
     * 微信 8.0.80 的 Tab 页内容外面套了一层 `MMWeUIBounceView`（继承 [BOUNCE_VIEW_CLASS]）。
     * 它不用 View.background，而是自己在 draw 里画 `setBgColor` 写入的 ColorDrawable（默认白色）。
     * 这里把颜色 setter 在 LauncherUI 里统一改成 0，并主动清一次已写入的颜色。
     */
    private fun ensureBounceHooks() {
        ensureOverlayHooks()
        ensureBackgroundAlphaHooks()
        if (bounceHooksInstalled) return
        val app = HostApplication ?: return
        runCatching {
            val clazz = Class.forName(BOUNCE_VIEW_CLASS, false, app.classLoader)
            for (name in BOUNCE_COLOR_SETTERS) {
                runCatching {
                    clazz.reflekt().firstMethodOrNull {
                        this.name = name
                        parameterCount = 1
                    }?.hookBefore {
                        if (activeThemeId.isEmpty()) return@hookBefore
                        val view = thisObject as? View ?: return@hookBefore
                        if (!isLauncherContext(view.context)) return@hookBefore
                        if (args.getOrNull(0) is Int) args[0] = 0
                    }
                }
            }
            // 下拉小程序时会换成 Drawable 背景（灰色遮罩），颜色 setter 拦不到。
            for (name in BOUNCE_DRAWABLE_SETTERS) {
                runCatching {
                    clazz.reflekt().firstMethodOrNull {
                        this.name = name
                        parameterCount = 1
                    }?.hookBefore {
                        if (activeThemeId.isEmpty()) return@hookBefore
                        val view = thisObject as? View ?: return@hookBefore
                        if (!isLauncherContext(view.context)) return@hookBefore
                        val drawable = args.getOrNull(0) as? Drawable ?: return@hookBefore
                        makeBackgroundTranslucent(drawable, allowBitmap = true)
                    }
                }
            }
            bounceHooksInstalled = true
            WeLogger.d(TAG, "下拉回弹背景 hook 已安装")
        }.onFailure {
            WeLogger.d(TAG, "下拉回弹背景 hook 安装失败：${it.message}")
        }
    }

    private fun clearBounceBackground(page: ViewGroup) {
        val bounce = findBounceView(page) ?: return
        for (name in BOUNCE_COLOR_SETTERS) {
            runCatching {
                bounce.javaClass.getMethod(name, Integer.TYPE).invoke(bounce, 0)
            }
        }
    }

    /** 沿 ContextWrapper 链确认是不是 LauncherUI（View.context 可能被主题包装）。 */
    private fun isLauncherContext(context: Context?): Boolean {
        var current: Context? = context
        var depth = 0
        while (current != null && depth++ < 8) {
            if (current.javaClass.name == LAUNCHER_UI_CLASS) return true
            current = (current as? ContextWrapper)?.baseContext
        }
        return false
    }

    private fun findBounceView(page: ViewGroup): View? {
        var found: View? = null
        fun walk(v: View, depth: Int) {
            if (found != null || depth > 8) return
            if (v.javaClass.name == "com.tencent.mm.ui.widget.pulldown.MMWeUIBounceView") {
                found = v
                return
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(page, 0)
        return found
    }

    private fun applyChatBackground(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        var visited = 0
        fun walk(v: View, depth: Int) {
            if (visited >= 6000 || depth > 40) return
            visited++
            if (v is ImageView && v.javaClass.name == CHAT_BG_VIEW_CLASS) {
                runCatching { applyChatWallpaperTo(v) }
                return
            }
            if (v is ViewGroup) {
                for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
        }
        walk(decor, 0)
    }

    private fun applyChatWallpaperTo(iv: ImageView) {
        if (activeThemeId.isEmpty()) return
        val theme = currentTheme() ?: return
        val src = wallpaperSource()
        if (!chatBackgrounds.containsKey(iv)) {
            chatBackgrounds[iv] = ChatBgBackup(iv.drawable, iv.imageAlpha)
            WeLogger.d(TAG, "聊天背景已接管")
        }
        val bitmap = wallpaperBitmapFor(src)
        if (bitmap != null) iv.setImageBitmap(bitmap)
        else iv.setImageDrawable(ColorDrawable(theme.previewColor.toInt()))
        iv.imageAlpha = 255
    }

    private fun restoreChatBackgrounds() {
        chatBackgrounds.entries.toList().forEach { (iv, backup) ->
            runCatching {
                iv.setImageDrawable(backup.drawable)
                iv.imageAlpha = backup.imageAlpha
            }
        }
        chatBackgrounds.clear()
    }

    /**
     * 恢复默认：清空主题 id、还原壁纸层/页面原背景/聊天背景、还原应用主题前的气泡文件。
     *
     * 三件事必须一起做，缺一条就会出现「点了恢复默认，气泡还在」：
     * 1. 还原气泡文件（用户原本没有则删掉主题气泡）；
     * 2. 把当前已经渲染出来的气泡 View 换回原背景；
     * 3. 移除四 Tab 壁纸层、还原被清空的页面背景与聊天背景。
     */
    private fun restoreDefault() {
        try {
            restoreBubbleBackup()
            // 气泡由 CustomMessageBubbles 统一绘制并记录原背景，这里让它把已渲染的视图换回去。
            // （原来 ThemeStore 自己也有一份 painter + paintedBubbles，但只有「主题商城」
            //  开关本身处于启用时才会注册监听；用户通常只开「自定义消息气泡」，那份记录永远是空的，
            //  于是「恢复默认」还原不了任何一个气泡。）
            CustomMessageBubbles.resetPaintedBubbles()
            // 若用户本来自定义过气泡（有备份），上面只是换回原生背景，
            // 还需要重绑一次让「自定义消息气泡」把它们自己的气泡重新贴上去。
            WeChatMessageViewApi.refreshChattingList()
            activeThemeId = ""
            removeTabWallpapers()
            restorePageBackgrounds()
            restoreChatBackgrounds()
            wallpaperBitmapPath = null
            wallpaperBitmap = null
        } catch (e: Throwable) {
            WeLogger.e(TAG, "restoreDefault 异常", e)
        }
    }

/** 通过 ActivityThread.mActivities 获取进程内所有存活 Activity。 */
    private fun liveActivities(): List<Activity> = try {
        val atClass = Class.forName("android.app.ActivityThread")
        val at = atClass.getDeclaredMethod("currentActivityThread").invoke(null)
        val activitiesField = atClass.getDeclaredField("mActivities")
        activitiesField.isAccessible = true
        val activities = activitiesField.get(at) as? Map<*, *> ?: return emptyList()
        buildList {
            for (record in activities.values) {
                val activity = record?.javaClass?.getDeclaredField("activity")
                    ?.apply { isAccessible = true }
                    ?.get(record) as? Activity ?: continue
                if (activity.isFinishing || activity.isDestroyed) continue
                add(activity)
            }
        }
    } catch (e: Throwable) {
        WeLogger.d(TAG, "liveActivities 失败：${e.message}")
        emptyList()
    }

    /**
     * 滑杆即时生效：只改控件底衬的 alpha，壁纸与聊天背景始终保持不透明。
     * 滑杆越高 → 底衬越透明 → 壁纸越明显。
     */
    private fun updateAlphaOnLive(alpha: Float) {
        val bgAlpha = 255 - (alpha.coerceIn(0f, 1f) * 255).toInt().coerceIn(0, 255)
        tabWallpapers.values.forEach { drawable ->
            runCatching { drawable.alpha = 255 }
        }
        appliedBackgrounds.entries.toList().forEach { (view, drawable) ->
            val original = originalBgAlpha[view] ?: 255
            runCatching { drawable.alpha = minOf(original, bgAlpha) }
        }
        chatBackgrounds.keys.forEach { iv ->
            runCatching { iv.imageAlpha = 255 }
        }
    }

    // ══════════════════════════════════════════════════════════
    // 气泡备份/恢复（应用主题前先备份用户原有气泡，恢复默认时还原）
    // ══════════════════════════════════════════════════════════
    /**
     * 应用主题前先备份用户原有气泡。
     *
     * 三种情形要分开记：
     * - 模块 assets 里有 left/right → 各自复制进备份目录；
     * - 一个都没有（用户原本就用微信默认气泡）→ 写 [NO_BUBBLE_MARKER] 标记，
     *   恢复默认时据此**删除**主题气泡，否则主题气泡会留在原地继续生效；
     * - 备份目录里已有内容（或已有标记）→ 说明是重复应用，保留第一次的备份。
     */
    private fun backupExistingBubbles() {
        try {
            val dir = backupBubbleDir
            if (dir.listDirectoryEntries().isNotEmpty()) return
            val left = KnownPaths.moduleAssets / LEFT_BUBBLE_FILE
            val right = KnownPaths.moduleAssets / RIGHT_BUBBLE_FILE
            var copied = false
            if (left.exists()) {
                Files.copy(left, dir / LEFT_BUBBLE_FILE, StandardCopyOption.REPLACE_EXISTING)
                copied = true
            }
            if (right.exists()) {
                Files.copy(right, dir / RIGHT_BUBBLE_FILE, StandardCopyOption.REPLACE_EXISTING)
                copied = true
            }
            if (!copied) Files.write(dir / NO_BUBBLE_MARKER, ByteArray(0))
        } catch (e: Throwable) { WeLogger.w(TAG, "备份气泡异常", e) }
    }

    private fun restoreBubbleBackup() {
        try {
            val dir = backupBubbleDir
            val left = dir / LEFT_BUBBLE_FILE
            val right = dir / RIGHT_BUBBLE_FILE
            val hasBackup = left.exists() || right.exists()
            // 没有可还原的用户气泡（应用主题前本来就用微信默认气泡，或备份目录已丢失）
            // → 删掉主题气泡并关掉开关。否则「恢复默认」之后气泡样式还挂在消息上。
            if (!hasBackup) {
                runCatching { (KnownPaths.moduleAssets / LEFT_BUBBLE_FILE).toFile().takeIf { it.exists() }?.delete() }
                runCatching { (KnownPaths.moduleAssets / RIGHT_BUBBLE_FILE).toFile().takeIf { it.exists() }?.delete() }
                WePrefs.putBool(CUSTOM_BUBBLES_PREF_KEY, false)
                // 同理：pref 关掉不等于运行时关掉，否则「恢复默认」后气泡还在，
                // 要等重启微信才消失。
                if (CustomMessageBubbles.isEnabled) {
                    CustomMessageBubbles.isEnabled = false
                    WeLogger.i(TAG, "已同时关闭「自定义消息气泡」")
                }
            }
            if (left.exists()) Files.copy(left, KnownPaths.moduleAssets / LEFT_BUBBLE_FILE, StandardCopyOption.REPLACE_EXISTING)
            if (right.exists()) Files.copy(right, KnownPaths.moduleAssets / RIGHT_BUBBLE_FILE, StandardCopyOption.REPLACE_EXISTING)
            runCatching { dir.toFile().deleteRecursively() }
        } catch (e: Throwable) { WeLogger.w(TAG, "还原气泡异常", e) }
    }

    // ══════════════════════════════════════════════════════════
    // 设置 UI：主题商城（入口：wcx 设置 → 设置 → 微信主题）
    // ══════════════════════════════════════════════════════════
    override fun onClick(context: ComponentActivity) {
        try {
            // 用户主动打开面板时必然有 Activity —— 顺便把「启动早期没注册上」的回调和主题补一次。
            ensureCallbacks()
            ensureRestored("打开主题商城")
            ensureAllBuiltinExtracted()
            showComposeDialog(context) {
                var currentId by remember { mutableStateOf(activeThemeId) }
                var opacity by remember { mutableStateOf(overlayOpacityPct.coerceIn(5, 100)) }
                AlertDialogContent(
                    title = { Text("主题商城") },
                    text = {
                        DefaultColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text("六款卡通主题：一键应用主页四页与聊天背景壁纸，并匹配对应气泡", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(6.dp))
                            builtinThemes.forEach { theme ->
                                ThemeRow(
                                    theme = theme,
                                    active = currentId == theme.id,
                                    onApply = {
                                        applyThemeInternal(theme.id)
                                        currentId = theme.id
                                        updateAlphaOnLive(opacity / 100f)
                                    }
                                )
                                Spacer(Modifier.height(4.dp))
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "界面透明度 ${opacity}%",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Slider(
                                    value = opacity.toFloat(),
                                    onValueChange = {
                                        opacity = it.toInt().coerceIn(5, 100)
                                        overlayOpacityPct = opacity
                                        updateAlphaOnLive(opacity / 100f)
                                    },
                                    valueRange = 5f..100f,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Row {
                                TextButton(onClick = {
                                    restoreDefault()
                                    currentId = ""
                                    showToast(context, "已恢复默认主题")
                                }) { Text("恢复默认主题") }
                            }
                        }
                    },
                    dismissButton = { TextButton(onDismiss) { Text("取消") } },
                    confirmButton = {
                        Button(onClick = onDismiss) { Text("关闭") }
                    }
                )
            }
        } catch (e: Throwable) {
            WeLogger.e(TAG, "onClick 异常", e)
            showToast(context, "主题商城打开失败")
        }
    }

    /**
     * 类初始化就把「恢复」链路挂上。
     *
     * [onEnable] 只在「主题商城」这个开关被用户打开时才执行，而它默认是关的 ——
     * 用户通常是直接从设置页的「主题工坊」入口进来（[onClick] 不检查开关），
     * 于是 `onEnable` 从来没跑过。结果就是：应用主题当下有效，**重启微信后壁纸全没了**。
     * 这里不依赖开关，只要类被 features 列表实例化就注册回调。
     *
     * 放在文件靠后位置是有意的：Kotlin 按声明顺序初始化，[activityCallbacks] 等属性
     * 必须先就位，`init` 里引用它们才安全。
     */
    init {
        runCatching {
            ensureCallbacks()
            ensureRestored("类初始化")
        }.onFailure { WeLogger.w(TAG, "初始化恢复链路异常", it) }
    }

    @Composable
    private fun ThemeRow(theme: ThemeInfo, active: Boolean, onApply: () -> Unit) {
        val thumbFile = remember(theme.id) { builtinWallpaperFile(theme).toFile().takeIf { it.exists() } }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ComposeColor(theme.previewColor))
            ) {
                if (thumbFile != null) {
                    AsyncImage(
                        model = thumbFile,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().size(44.dp)
                    )
                }
            }
            Text(
                text = theme.name + (if (active) "（使用中）" else ""),
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp)
            )
            Button(onClick = onApply) { Text(if (active) "已应用" else "应用") }
        }
    }
}
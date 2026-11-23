package com.AARCNoa.zhiyou.features.items.beautify

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AndroidColor
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
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
import com.AARCNoa.zhiyou.ui.utils.findViewWhich
import com.AARCNoa.zhiyou.ui.utils.showComposeDialog
import com.AARCNoa.zhiyou.utils.WeLogger
import com.AARCNoa.zhiyou.utils.android.showToast
import com.AARCNoa.zhiyou.utils.fs.KnownPaths
import com.AARCNoa.zhiyou.utils.fs.createDirsSafe
import java.io.File
import java.lang.ref.WeakReference
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.absolutePathString
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

/**
 * 主题商店（v245 全新编写）：
 * - 入口：wcx 设置 → 设置 → 微信主题 → 主题商城
 * - 六款内置主题：Hello Kitty（花朵气泡）/ 小熊（miko 小狗边框气泡）/ 线条小狗（加油鸭鸭气泡）/
 *   吉伊卡哇（花朵气泡）/ 美食小狗（面包🍞气泡）/ 小兔子（猫咪气泡）
 * - 使用主题后全局沉浸式：状态栏/导航栏透明，壁纸悬浮层铺满全屏（含状态栏/导航栏区域），
 *   微信主页、设置页、朋友圈等所有微信页面一律生效；**扫一扫与查看相册页面不挂壁纸**
 *   （仅取消壁纸覆盖，沉浸式等其余不变，避免相机取景区域被遮挡影响扫码）
 * - 气泡按「自定义消息气泡」的方式应用：主题气泡写入 <模块数据>/assets/left_bubble.9.png（对方）
 *   与 right_bubble.9.png（自己），9-patch 黑边解析为 NinePatchDrawable，同时开启该功能开关
 * - 壁纸透明度 5%~100% 滑杆调节（即时生效）
 * - 恢复默认主题：还原系统栏/壁纸层与应用主题前的气泡（应用前自动备份用户原有气泡）
 * - 素材内置在模块 APK assets/theme_store/（壁纸 + 每主题 left/right 气泡）
 */
@Feature(
    name = "主题商城",
    categories = ["界面美化"],
    description = "六款卡通主题一键应用：全局沉浸壁纸（主页/设置/朋友圈等全部页面，扫一扫除外）+ 对应主题气泡，支持透明度调节与恢复默认主题"
)
object ThemeStore : ClickableFeature() {

    private const val TAG = "ThemeStore"
    private const val WALLPAPER_TAG = "wcx_theme_store_wallpaper"
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
    // 沉浸式窗口备份
    // ══════════════════════════════════════════════════════════
    private class WindowBackup(
        val actRef: WeakReference<Activity>,
        val statusBarColor: Int,
        val navBarColor: Int,
        val sysUi: Int,
        val contrastStatusEnforced: Boolean,
        val contrastNavEnforced: Boolean
    )

    private val windowBackups = ConcurrentHashMap<Int, WindowBackup>()

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
            ensureRestored("Activity.onResume")
            if (activeThemeId.isNotEmpty()) {
                try { applyThemeTo(activity) } catch (e: Throwable) { WeLogger.w(TAG, "onActivityResumed 应用主题异常", e) }
            }
        }
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {
            windowBackups.remove(System.identityHashCode(activity))
        }
    }

    override fun onEnable() {
        ensureCallbacks()
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
    // 全局应用：对所有存活 Activity 挂载沉浸式 + 壁纸悬浮层
    // ══════════════════════════════════════════════════════════
    private fun applyThemeToAllLive() {
        liveActivities().forEach { act ->
            try { applyThemeTo(act) } catch (e: Throwable) { WeLogger.w(TAG, "应用主题到 ${act.javaClass.simpleName} 异常", e) }
        }
    }

    private fun applyThemeTo(activity: Activity) {
        if (activeThemeId.isEmpty()) return
        applyImmersive(activity)
        // 扫一扫页面与照片/视频「放大查看」页面不挂壁纸：相机取景被遮挡影响扫码、
        // 查看页被壁纸盖住会与照片重叠。查看页多为半透明 Activity / 同窗口浮层 / 独立窗口，
        // 不一定有 Activity resume 信号——因此聊天页（ChattingUI）本身也不挂壁纸，
        // 从源头消除「照片透出聊天页壁纸蒙版」的可能；返回时其他页面 onResume 自动恢复。
        if (isScannerPage(activity) || isPhotoViewerPage(activity) || isChattingPage(activity)) {
            liveActivities().forEach { act ->
                try { removeWallpaper(act) } catch (e: Throwable) { WeLogger.w(TAG, "豁免页移除壁纸异常", e) }
            }
        } else {
            applyWallpaper(activity)
        }
    }

    /**
     * 聊天页判定：壁纸挂在聊天页上会透过照片查看浮层/半透明页盖住内容，故聊天页一律不挂壁纸。
     *
     * ## 类名修正（聊天页浸透的根因）
     * 微信 8.x 的真实类名是 `com.tencent.mm.ui.chatting.ChattingUI`，
     * 此前误写成 `com.tencent.mm.ui.ChattingUI`（少一层 `.chatting`），判定永不命中，
     * 导致内置主题的壁纸被挂到聊天页 decorView 顶层 → 聊天页看到主题壁纸（浸透）。
     *
     * ## 变体兼容
     * 微信 8.0.80 共有 26+ 个继承 ChattingUI 的变体（ChattingMainUI / BizHalfScreenChattingUI /
     * FinderChattingUI / VoipChattingUI / OpenImKefuServiceChattingUI / variants.AppBrandChattingUI 等），
     * 精确等号只能覆盖主类，故改为沿父类链向上匹配，未来新增子类同样命中。
     */
    private fun isChattingPage(activity: Activity): Boolean {
        var clazz: Class<*>? = activity.javaClass
        var depth = 0
        while (clazz != null && depth < 8) {
            val name = clazz.name
            if (name == "com.tencent.mm.ui.chatting.ChattingUI" ||
                name == "com.tencent.mm.ui.ChattingUI"
            ) {
                return true
            }
            clazz = clazz.superclass
            depth++
        }
        return false
    }

    

    /** 微信「扫一扫」相关页面：plugin.scanner 包（扫码页/选模式/相册扫码/扫码结果等）。 */
    private fun isScannerPage(activity: Activity): Boolean =
        activity.javaClass.name?.startsWith("com.tencent.mm.plugin.scanner") == true

    /**
     * 照片/视频「放大查看」页面：壁纸若悬浮其上会盖住照片，一律不挂壁纸。
     * 类名取自微信 8.0.77 manifest 实测：
     * - ui.chatting.gallery.* ：聊天图片/视频查看（ImageGalleryUI 等）与聊天记录媒体页
     * - plugin.gallery.*      ：相册选图预览
     * - plugin.sns.ui.SnsGalleryUI / SnsSightPlayerUI / VideoFullScreenActivity：朋友圈照片/视频
     * - plugin.fav.ui.FavImgGalleryUI / gallery.FavMediaGalleryUI / detail.FavoriteSightDetailUI：收藏媒体
     * - view.activity.ImageQueryGalleryUI、ui.media.MediaGalleryContainerUI：通用媒体查看容器
     */
    private fun isPhotoViewerPage(activity: Activity): Boolean {
        val name = activity.javaClass.name ?: return false
        return name.startsWith("com.tencent.mm.ui.chatting.gallery") ||
            name.startsWith("com.tencent.mm.plugin.gallery") ||
            name == "com.tencent.mm.plugin.sns.ui.SnsGalleryUI" ||
            name == "com.tencent.mm.plugin.sns.ui.SnsSightPlayerUI" ||
            name == "com.tencent.mm.plugin.sns.ui.VideoFullScreenActivity" ||
            name == "com.tencent.mm.plugin.fav.ui.FavImgGalleryUI" ||
            name == "com.tencent.mm.plugin.fav.ui.gallery.FavMediaGalleryUI" ||
            name == "com.tencent.mm.plugin.fav.ui.detail.FavoriteSightDetailUI" ||
            name == "com.tencent.mm.view.activity.ImageQueryGalleryUI" ||
            name == "com.tencent.mm.ui.media.MediaGalleryContainerUI"
    }

    /**
     * 沉浸式系统栏：状态栏/导航栏透明 + 浅色图标（可恢复）。
     * 注意：不设置 LAYOUT_FULLSCREEN/LAYOUT_STABLE 布局旗标——那会让微信页面按全屏布局
     * 把内容顶到状态栏下（如「我 → 服务」页面整体上移被遮挡）。透明系统栏 + 壁纸悬浮层
     * 覆盖在 decorView 顶层（含系统栏区域），视觉上同样是全屏沉浸，且不改变页面布局。
     */
    private fun applyImmersive(activity: Activity) {
        val w = activity.window ?: return
        val hash = System.identityHashCode(activity)
        try {
            @Suppress("DEPRECATION")
            if (!windowBackups.containsKey(hash)) {
                windowBackups[hash] = WindowBackup(
                    WeakReference(activity),
                    w.statusBarColor,
                    w.navigationBarColor,
                    w.decorView.systemUiVisibility,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && w.isStatusBarContrastEnforced,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && w.isNavigationBarContrastEnforced
                )
            }
            @Suppress("DEPRECATION")
            w.statusBarColor = AndroidColor.TRANSPARENT
            w.navigationBarColor = AndroidColor.TRANSPARENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                w.isStatusBarContrastEnforced = false
                w.isNavigationBarContrastEnforced = false
            }
            // 仅切换图标为浅色（可读），不加任何布局旗标
            @Suppress("DEPRECATION")
            w.decorView.systemUiVisibility = w.decorView.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        } catch (e: Throwable) {
            WeLogger.w(TAG, "applyImmersive 异常", e)
        }
    }

    /**
     * 壁纸悬浮层：挂在 decorView 顶层（触摸透明），铺满全屏（含状态栏/导航栏区域）。
     * 已存在则复用并刷新壁纸图/透明度，避免闪烁。
     * 注：曾尝试「内容底衬」（挂在窗体容器 index 0）——微信各页根布局不透明会完全盖住底衬
     * （看不到壁纸），且影响微信自绘窗口（wcx 设置入口卡启动图），故回退为顶层悬浮。
     */
    private fun applyWallpaper(activity: Activity) {
        // 兜底：聊天页永不挂壁纸。
        // onActivityResumed 分支已做豁免，这里再拦一次，防止 resume 时序竞态
        // （转场期间壁纸先挂后判、liveActivities 快照不全等）导致漏判而浸透。
        if (isChattingPage(activity)) {
            removeWallpaper(activity)
            return
        }
        val decor = activity.window?.decorView as? ViewGroup ?: return
        val alpha = overlayOpacityPct.coerceIn(5, 100) / 100f
        val theme = currentTheme()
        val src = wallpaperSource()
        try {
            var container = decor.findViewWithTag<FrameLayout>(WALLPAPER_TAG)
            if (container == null) {
                container = FrameLayout(activity).apply {
                    tag = WALLPAPER_TAG
                    isClickable = false
                    isFocusable = false
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                // 保持在最上层，但不盖住之后挂载的侧边栏面板/触发按钮
                decor.addView(container)
            }
            if (decor.indexOfChild(container) != decor.childCount - 1) {
                decor.removeView(container)
                decor.addView(container)
            }
            val iv = container.getChildAt(0) as? ImageView ?: ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }.also { container.addView(it) }
            val bmp = src?.let { runCatching { BitmapFactory.decodeFile(it.absolutePathString()) }.getOrNull() }
            if (bmp != null && iv.tag != src.absolutePathString()) {
                iv.setImageBitmap(bmp)
                iv.tag = src.absolutePathString()
            }
            if (bmp == null) {
                // 壁纸缺失兜底：主题色
                iv.setImageDrawable(ColorDrawable(theme?.previewColor?.toInt() ?: 0xFFF0F0F0.toInt()))
            }
            iv.alpha = alpha
            container.setBackgroundColor(AndroidColor.TRANSPARENT)
        } catch (e: Throwable) {
            WeLogger.w(TAG, "applyWallpaper 异常", e)
        }
    }

    private fun removeWallpaper(activity: Activity) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        try {
            decor.findViewWithTag<View>(WALLPAPER_TAG)?.let { decor.removeView(it) }
        } catch (e: Throwable) { WeLogger.w(TAG, "removeWallpaper 异常", e) }
    }

    private fun restoreWindow(activity: Activity) {
        val hash = System.identityHashCode(activity)
        val backup = windowBackups.remove(hash) ?: return
        val act = backup.actRef.get() ?: return
        try {
            @Suppress("DEPRECATION")
            act.window?.statusBarColor = backup.statusBarColor
            act.window?.navigationBarColor = backup.navBarColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                act.window?.isStatusBarContrastEnforced = backup.contrastStatusEnforced
                act.window?.isNavigationBarContrastEnforced = backup.contrastNavEnforced
            }
            @Suppress("DEPRECATION")
            act.window?.decorView?.systemUiVisibility = backup.sysUi
        } catch (e: Throwable) { WeLogger.w(TAG, "restoreWindow 异常", e) }
    }

    /**
     * 恢复默认：清空主题 id、还原窗口/壁纸层、还原应用主题前的气泡文件。
     *
     * 三件事必须一起做，缺一条就会出现「点了恢复默认，气泡还在」：
     * 1. 还原气泡文件（用户原本没有则删掉主题气泡）；
     * 2. 把当前已经渲染出来的气泡 View 换回原背景；
     * 3. 移除壁纸层、还原系统栏颜色。
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
            liveActivities().forEach { act ->
                runCatching { removeWallpaper(act) }
                runCatching { restoreWindow(act) }
            }
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

    /** 更新所有存活页面已挂载壁纸层的透明度（滑杆即时生效）。 */
    private fun updateAlphaOnLive(alpha: Float) {
        liveActivities().forEach { act ->
            try {
                val decor = act.window?.decorView as? ViewGroup ?: return@forEach
                (decor.findViewWithTag<FrameLayout>(WALLPAPER_TAG)?.getChildAt(0) as? ImageView)?.alpha = alpha
            } catch (e: Throwable) { WeLogger.d(TAG, "更新透明度失败：${e.message}") }
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
                            Text("六款卡通主题：一键应用全局沉浸壁纸与对应气泡", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    "壁纸透明度 ${opacity}%",
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